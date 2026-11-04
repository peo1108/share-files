using System.Buffers;
using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.Net.Http.Headers;

namespace ShareFiles.Server;

internal static class Endpoints
{
    private const string DeviceKey = "device";
    private const long MaxSpeedtestBytes = 4L * 1024 * 1024 * 1024;
    private const int FileBufferSize = 1 << 20;
    private static readonly byte[] SpeedtestBlock = RandomNumberGenerator.GetBytes(1 << 20);

    private sealed record PairRequest(string? Secret, string? DeviceId, string? DeviceName, string? Platform);
    private sealed record UploadRequest(string? FileKey, string? Name, long Size, DateTimeOffset? ModifiedAt);
    private sealed record BrowseEntry(string Name, bool IsDirectory, long Size, string ModifiedAt);

    public static void MapApi(this WebApplication app, ShareServer server)
    {
        app.Use(ErrorHandling(server));
        app.Use(Authentication(server));

        var api = app.MapGroup("/v1");
        api.MapGet("/info", () => Results.Ok(new
        {
            id = server.Config.Current.ServerId,
            name = server.Config.Current.ServerName,
            apiVersion = 1,
        }));
        api.MapPost("/pair", (PairRequest request) => Pair(server, request));
        api.MapGet("/roots", () => server.Config.Current.Roots
            .Where(r => Directory.Exists(r.Path))
            .Select(r => new { id = r.Id, name = r.Name }));
        api.MapGet("/browse", (string root, string? path) => Browse(server, root, path));
        api.MapGet("/download", (HttpContext context, string root, string path) => Download(server, context, root, path));
        MapUploads(api, server);
        MapOutbox(api, server);
        MapSpeedtest(api);
    }

    private static Func<HttpContext, RequestDelegate, Task> ErrorHandling(ShareServer server) => async (context, next) =>
    {
        try
        {
            await next(context);
        }
        catch (ApiException e)
        {
            await WriteError(context, e);
        }
        catch (Exception e) when (e is not OperationCanceledException && !context.RequestAborted.IsCancellationRequested)
        {
            server.Log($"{context.Request.Method} {context.Request.Path}: {e}");
            await WriteError(context, new ApiException(500, "internal", "Lỗi trên PC"));
        }
    };

    private static async Task WriteError(HttpContext context, ApiException error)
    {
        if (context.Response.HasStarted) return;
        context.Response.Clear();
        context.Response.StatusCode = error.Status;
        await context.Response.WriteAsJsonAsync(new { code = error.Code, message = error.Message, offset = error.Offset });
    }

    private static Func<HttpContext, RequestDelegate, Task> Authentication(ShareServer server) => async (context, next) =>
    {
        var path = context.Request.Path.Value;
        if (path is "/v1/info" or "/v1/pair")
        {
            await next(context);
            return;
        }

        var header = context.Request.Headers.Authorization.ToString();
        var device = header.StartsWith("Bearer ", StringComparison.Ordinal)
            ? server.Devices.Authenticate(header["Bearer ".Length..])
            : null;
        if (device is null) throw new ApiException(401, "unauthorized", "Thiết bị chưa được ghép đôi");

        context.Items[DeviceKey] = device;
        await next(context);
    };

    private static DeviceRecord Device(HttpContext context) => (DeviceRecord)context.Items[DeviceKey]!;

    private static IResult Pair(ShareServer server, PairRequest request)
    {
        if (string.IsNullOrEmpty(request.Secret) || !server.Pairing.TryConsume(request.Secret))
        {
            throw new ApiException(403, "pair_invalid", "Mã ghép đôi sai hoặc đã hết hạn");
        }
        if (string.IsNullOrEmpty(request.DeviceId) || request.Platform is not ("android" or "ios"))
        {
            throw ApiException.BadRequest("Thiếu thông tin thiết bị");
        }

        var name = string.IsNullOrWhiteSpace(request.DeviceName) ? request.Platform : request.DeviceName.Trim();
        var token = server.Devices.Register(request.DeviceId, name, request.Platform);
        return Results.Ok(new
        {
            token,
            serverId = server.Config.Current.ServerId,
            serverName = server.Config.Current.ServerName,
        });
    }

    private static RootFolder FindRoot(ShareServer server, string rootId) =>
        server.Config.Current.Roots.FirstOrDefault(r => r.Id == rootId) ?? throw ApiException.NotFound();

    private static IResult Browse(ShareServer server, string rootId, string? path)
    {
        var directory = PathGuard.Resolve(FindRoot(server, rootId).Path, path);
        if (!Directory.Exists(directory)) throw ApiException.NotFound();

        var options = new EnumerationOptions
        {
            IgnoreInaccessible = true,
            AttributesToSkip = FileAttributes.Hidden | FileAttributes.System | FileAttributes.ReparsePoint,
        };
        var entries = new DirectoryInfo(directory)
            .EnumerateFileSystemInfos("*", options)
            .Select(info => new BrowseEntry(
                info.Name,
                info is DirectoryInfo,
                info is FileInfo file ? file.Length : 0,
                Iso(info.LastWriteTimeUtc)))
            .OrderByDescending(e => e.IsDirectory)
            .ThenBy(e => e.Name, StringComparer.Create(CultureInfo.InvariantCulture, CompareOptions.NumericOrdering | CompareOptions.IgnoreCase))
            .ToList();

        return Results.Ok(new { root = rootId, path = path ?? "", entries });
    }

    private static IResult Download(ShareServer server, HttpContext context, string rootId, string path)
    {
        var file = PathGuard.Resolve(FindRoot(server, rootId).Path, path);
        if (!File.Exists(file)) throw ApiException.NotFound();
        return SendFile(server, context, file, Path.GetFileName(file));
    }

    private static IResult SendFile(ShareServer server, HttpContext context, string file, string displayName)
    {
        var info = new FileInfo(file);
        var etag = new EntityTagHeaderValue($"\"{info.Length:x}-{info.LastWriteTimeUtc.Ticks:x}\"");
        var entry = server.Monitor.TrackDownload(
            $"dl:{Device(context).DeviceId}:{file}",
            Device(context).Name,
            displayName,
            info.Length,
            RequestedBytes(context.Request, info.Length, etag));
        var stream = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete,
            FileBufferSize, FileOptions.Asynchronous | FileOptions.SequentialScan);

        return Results.File(
            new TrackingStream(stream, server.Monitor, entry),
            "application/octet-stream",
            lastModified: info.LastWriteTimeUtc,
            entityTag: etag,
            enableRangeProcessing: true);
    }

    /// <summary>How many bytes this request will be sent, following the same rules as range processing.</summary>
    private static long RequestedBytes(HttpRequest request, long length, EntityTagHeaderValue etag)
    {
        var headers = request.GetTypedHeaders();
        if (headers.Range is not { Ranges.Count: 1 } range) return length;
        // A stale validator makes range processing send the whole file.
        if (headers.IfRange?.EntityTag is { } tag && !tag.Compare(etag, useStrongComparison: true)) return length;

        var item = range.Ranges.First();
        long start, end;
        if (item.From is { } from)
        {
            start = from;
            end = Math.Min(item.To ?? length - 1, length - 1);
        }
        else if (item.To is { } suffix)
        {
            start = Math.Max(0, length - suffix);
            end = length - 1;
        }
        else
        {
            return length;
        }
        return start > end ? 0 : end - start + 1;
    }

    private static void MapUploads(RouteGroupBuilder api, ShareServer server)
    {
        api.MapPost("/uploads", (HttpContext context, UploadRequest request) =>
            Results.Ok(server.Uploads.Begin(Device(context).DeviceId, request.FileKey ?? "", request.Name, request.Size, request.ModifiedAt)));

        api.MapPatch("/uploads/{id}", async (HttpContext context, string id) =>
        {
            if (!long.TryParse(context.Request.Headers["Upload-Offset"].ToString(), out var offset) || offset < 0)
            {
                throw ApiException.BadRequest("Thiếu header Upload-Offset");
            }

            var device = Device(context);
            var meta = server.Uploads.Find(id);
            TransferEntry? entry = meta is { Completed: false } && meta.DeviceId == device.DeviceId
                ? server.Monitor.Track(id, device.Name, TransferDirection.ToPc, meta.Name, meta.Size)
                : null;
            entry?.Report(offset);

            try
            {
                var status = await server.Uploads.AppendAsync(
                    device.DeviceId, id, offset, context.Request.Body, p => entry?.Report(p), context.RequestAborted);
                if (entry is not null)
                {
                    server.Monitor.Finish(entry, status.Completed ? TransferState.Done : TransferState.Interrupted);
                }
                return Results.Ok(status);
            }
            catch
            {
                if (entry is not null) server.Monitor.Finish(entry, TransferState.Interrupted);
                throw;
            }
        });

        api.MapDelete("/uploads/{id}", async (HttpContext context, string id) =>
        {
            await server.Uploads.DeleteAsync(Device(context).DeviceId, id);
            return Results.NoContent();
        });
    }

    private static void MapOutbox(RouteGroupBuilder api, ShareServer server)
    {
        api.MapGet("/outbox", (HttpContext context) => server.Outbox.ListFor(Device(context).DeviceId).Select(i => new
        {
            id = i.Id,
            name = i.Name,
            size = i.Size,
            modifiedAt = Iso(i.ModifiedAt),
            sourceMissing = !File.Exists(i.Path),
        }));

        api.MapGet("/outbox/{id}/content", (HttpContext context, string id) =>
        {
            var item = server.Outbox.Find(Device(context).DeviceId, id) ?? throw ApiException.NotFound();
            if (!File.Exists(item.Path)) throw new ApiException(410, "source_gone", "File nguồn đã bị xoá khỏi PC");
            return SendFile(server, context, item.Path, item.Name);
        });

        api.MapPost("/outbox/{id}/ack", (HttpContext context, string id) => RemoveOutbox(server, context, id));
        api.MapDelete("/outbox/{id}", (HttpContext context, string id) => RemoveOutbox(server, context, id));
    }

    private static IResult RemoveOutbox(ShareServer server, HttpContext context, string id)
    {
        if (!server.Outbox.Remove(Device(context).DeviceId, id)) throw ApiException.NotFound();
        return Results.NoContent();
    }

    private static void MapSpeedtest(RouteGroupBuilder api)
    {
        api.MapGet("/speedtest/download", async (HttpContext context, long bytes) =>
        {
            if (bytes is < 0 or > MaxSpeedtestBytes) throw ApiException.BadRequest("bytes vượt giới hạn");

            context.Response.ContentType = "application/octet-stream";
            context.Response.ContentLength = bytes;
            var remaining = bytes;
            while (remaining > 0)
            {
                var chunk = (int)Math.Min(remaining, SpeedtestBlock.Length);
                await context.Response.Body.WriteAsync(SpeedtestBlock.AsMemory(0, chunk), context.RequestAborted);
                remaining -= chunk;
            }
        });

        api.MapPost("/speedtest/upload", async (HttpContext context) =>
        {
            var buffer = ArrayPool<byte>.Shared.Rent(1 << 20);
            try
            {
                long total = 0;
                var started = Stopwatch.GetTimestamp();
                int read;
                while ((read = await context.Request.Body.ReadAsync(buffer, context.RequestAborted)) > 0)
                {
                    total += read;
                }
                return Results.Ok(new { bytes = total, seconds = Stopwatch.GetElapsedTime(started).TotalSeconds });
            }
            finally
            {
                ArrayPool<byte>.Shared.Return(buffer);
            }
        });
    }

    private static string Iso(DateTime utc) => utc.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture);
}
