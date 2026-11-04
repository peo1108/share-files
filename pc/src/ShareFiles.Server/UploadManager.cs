using System.Buffers;
using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Http;

namespace ShareFiles.Server;

public sealed record UploadStatus(string Id, long Offset, bool Completed, string? SavedName);

internal sealed class UploadMeta
{
    public required string Id { get; init; }
    public required string DeviceId { get; init; }
    public required string Name { get; init; }
    public required long Size { get; init; }
    public DateTimeOffset? ModifiedAt { get; init; }
    public required string PartPath { get; init; }
    public required DateTimeOffset CreatedAt { get; init; }
    public bool Completed { get; set; }
    public string? SavedName { get; set; }
}

/// <summary>
/// Resumable uploads. Data goes to a .part file next to the inbox and is renamed when complete.
/// The file length on disk is the source of truth for the resume offset.
/// </summary>
public sealed class UploadManager
{
    private const int BufferSize = 1 << 20;
    private const int ErrorDiskFull = unchecked((int)0x80070070);
    private const int ErrorHandleDiskFull = unchecked((int)0x80070027);
    private const string PartialFolder = ".sharefiles-partial";
    private static readonly TimeSpan CompletedRetention = TimeSpan.FromDays(7);
    private static readonly TimeSpan AbandonedRetention = TimeSpan.FromDays(30);

    private readonly string _metaDirectory;
    private readonly ConfigStore _config;
    private readonly TimeProvider _time;
    private readonly ConcurrentDictionary<string, UploadMeta> _uploads = new();
    private readonly ConcurrentDictionary<string, WriterSlot> _slots = new();

    /// <summary>Only one writer per upload. A newer request displaces a stalled older one.</summary>
    private sealed class WriterSlot
    {
        public readonly SemaphoreSlim Gate = new(1, 1);
        public CancellationTokenSource? Current;
    }

    public UploadManager(string dataDirectory, ConfigStore config, TimeProvider time)
    {
        _metaDirectory = Path.Combine(dataDirectory, "uploads");
        _config = config;
        _time = time;
        Directory.CreateDirectory(_metaDirectory);
        LoadExisting();
    }

    public UploadStatus Begin(string deviceId, string fileKey, string? name, long size, DateTimeOffset? modifiedAt)
    {
        if (size < 0) throw ApiException.BadRequest("size không hợp lệ");
        if (string.IsNullOrEmpty(fileKey)) throw ApiException.BadRequest("Thiếu fileKey");

        var id = UploadId(deviceId, fileKey);
        var cleanName = FileNames.Sanitize(name);

        if (_uploads.TryGetValue(id, out var existing))
        {
            if (existing.Completed && existing.Size == size && existing.Name == cleanName)
            {
                return new UploadStatus(id, size, true, existing.SavedName);
            }
            if (!existing.Completed && existing.Size == size && existing.Name == cleanName)
            {
                var offset = PartLength(existing);
                EnsureSpace(existing.PartPath, size - offset);
                return new UploadStatus(id, offset, false, null);
            }
            Discard(existing);
        }

        var inbox = _config.Current.InboxDirectory;
        var partDirectory = Path.Combine(inbox, PartialFolder);
        Directory.CreateDirectory(partDirectory);
        File.SetAttributes(partDirectory, File.GetAttributes(partDirectory) | FileAttributes.Hidden);
        var partPath = Path.Combine(partDirectory, id + ".part");
        EnsureSpace(partPath, size);

        var meta = new UploadMeta
        {
            Id = id,
            DeviceId = deviceId,
            Name = cleanName,
            Size = size,
            ModifiedAt = modifiedAt,
            PartPath = partPath,
            CreatedAt = _time.GetUtcNow(),
        };
        File.WriteAllBytes(partPath, []);
        _uploads[id] = meta;
        Persist(meta);

        if (size == 0) return Complete(meta);
        return new UploadStatus(id, 0, false, null);
    }

    /// <summary>
    /// Appends a request body at <paramref name="offset"/>. If the connection drops, what arrived is
    /// kept and the returned status reports the new offset.
    /// </summary>
    public async Task<UploadStatus> AppendAsync(
        string deviceId, string id, long offset, Stream body, Action<long>? progress, CancellationToken cancellation)
    {
        var meta = FindOwned(deviceId, id);
        if (meta.Completed) return new UploadStatus(id, meta.Size, true, meta.SavedName);

        var slot = _slots.GetOrAdd(id, _ => new WriterSlot());
        using var displaced = new CancellationTokenSource();
        Interlocked.Exchange(ref slot.Current, displaced)?.Cancel();

        await slot.Gate.WaitAsync(cancellation);
        try
        {
            if (!_uploads.ContainsKey(id)) throw ApiException.NotFound();

            long position;
            using (var handle = File.OpenHandle(
                       meta.PartPath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.Read,
                       FileOptions.Asynchronous | FileOptions.SequentialScan))
            {
                var length = RandomAccess.GetLength(handle);
                if (offset != length)
                {
                    throw new ApiException(409, "offset_mismatch", "Offset không khớp", length);
                }
                using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellation, displaced.Token);
                position = await CopyBodyAsync(handle, body, offset, meta.Size, progress, linked.Token);
            }

            return position == meta.Size ? Complete(meta) : new UploadStatus(id, position, false, null);
        }
        finally
        {
            Interlocked.CompareExchange(ref slot.Current, null, displaced);
            slot.Gate.Release();
        }
    }

    public async Task DeleteAsync(string deviceId, string id)
    {
        var meta = FindOwned(deviceId, id);
        var slot = _slots.GetOrAdd(id, _ => new WriterSlot());
        Interlocked.Exchange(ref slot.Current, null)?.Cancel();
        await slot.Gate.WaitAsync();
        try
        {
            Discard(meta);
        }
        finally
        {
            slot.Gate.Release();
        }
    }

    internal UploadMeta? Find(string id) => _uploads.GetValueOrDefault(id);

    private static async Task<long> CopyBodyAsync(
        Microsoft.Win32.SafeHandles.SafeFileHandle handle, Stream body, long offset, long size,
        Action<long>? progress, CancellationToken cancellation)
    {
        var buffer = ArrayPool<byte>.Shared.Rent(BufferSize);
        try
        {
            var position = offset;
            var filled = 0;
            while (true)
            {
                int read;
                try
                {
                    read = await body.ReadAsync(buffer.AsMemory(filled), cancellation);
                }
                catch (Exception e) when (e is IOException or OperationCanceledException or BadHttpRequestException)
                {
                    // The connection is gone or a retry displaced us. Keep what we have.
                    break;
                }
                if (read == 0) break;

                filled += read;
                if (position + filled > size) throw ApiException.BadRequest("Dữ liệu nhiều hơn size đã khai báo");

                if (filled == buffer.Length)
                {
                    await WriteAsync(handle, buffer, filled, position);
                    position += filled;
                    filled = 0;
                    progress?.Invoke(position);
                }
            }

            await WriteAsync(handle, buffer, filled, position);
            position += filled;
            progress?.Invoke(position);
            return position;
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private static async Task WriteAsync(
        Microsoft.Win32.SafeHandles.SafeFileHandle handle, byte[] buffer, int count, long position)
    {
        if (count == 0) return;
        try
        {
            await RandomAccess.WriteAsync(handle, buffer.AsMemory(0, count), position);
        }
        catch (IOException e) when (e.HResult is ErrorDiskFull or ErrorHandleDiskFull)
        {
            throw ApiException.InsufficientStorage();
        }
    }

    private UploadStatus Complete(UploadMeta meta)
    {
        if (meta.ModifiedAt is { } modified)
        {
            File.SetLastWriteTimeUtc(meta.PartPath, modified.UtcDateTime);
        }
        meta.SavedName = FileNames.MoveToUniqueName(meta.PartPath, _config.Current.InboxDirectory, meta.Name);
        meta.Completed = true;
        Persist(meta);
        return new UploadStatus(meta.Id, meta.Size, true, meta.SavedName);
    }

    private UploadMeta FindOwned(string deviceId, string id)
    {
        if (_uploads.TryGetValue(id, out var meta) && meta.DeviceId == deviceId) return meta;
        throw ApiException.NotFound();
    }

    private void Discard(UploadMeta meta)
    {
        _uploads.TryRemove(meta.Id, out _);
        TryDelete(meta.PartPath);
        TryDelete(MetaPath(meta.Id));
    }

    private void LoadExisting()
    {
        foreach (var file in Directory.EnumerateFiles(_metaDirectory, "*.json"))
        {
            var meta = JsonFile.Read<UploadMeta>(file);
            if (meta is null) continue;

            var age = _time.GetUtcNow() - meta.CreatedAt;
            if (meta.Completed ? age > CompletedRetention : age > AbandonedRetention)
            {
                Discard(meta);
                continue;
            }
            _uploads[meta.Id] = meta;
        }
    }

    private void Persist(UploadMeta meta) => JsonFile.Write(MetaPath(meta.Id), meta);

    private string MetaPath(string id) => Path.Combine(_metaDirectory, id + ".json");

    private static long PartLength(UploadMeta meta)
    {
        var info = new FileInfo(meta.PartPath);
        return info.Exists ? info.Length : 0;
    }

    private static void EnsureSpace(string path, long needed)
    {
        var root = Path.GetPathRoot(Path.GetFullPath(path));
        if (string.IsNullOrEmpty(root)) return;
        if (needed > new DriveInfo(root).AvailableFreeSpace) throw ApiException.InsufficientStorage();
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    private static string UploadId(string deviceId, string fileKey) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(deviceId + "\n" + fileKey)))[..32];
}
