using System.Diagnostics;

namespace ShareFiles.Server;

public enum TransferDirection { ToPc, FromPc }

public enum TransferState { Active, Done, Interrupted }

public sealed class TransferEntry
{
    internal long TransferredBytes;
    internal long LastBytes;
    internal long LastTicks;

    // Downloads may arrive as several ranged requests at once. Each adds what it asked for and what
    // it sent, so the entry shows the whole file: bytes nobody asked for are ones the device holds.
    internal bool Ranged;
    internal int Streams;
    internal long RequestedBytes;
    internal long SentBytes;

    public required string Key { get; init; }
    public required string DeviceName { get; init; }
    public required TransferDirection Direction { get; init; }
    public required string Name { get; init; }
    public required long Size { get; init; }
    public TransferState State { get; internal set; } = TransferState.Active;
    public double BytesPerSecond { get; internal set; }
    public long Transferred => Ranged
        ? Math.Clamp(Size - Interlocked.Read(ref RequestedBytes) + Interlocked.Read(ref SentBytes), 0, Size)
        : Interlocked.Read(ref TransferredBytes);

    public void Report(long position) => Interlocked.Exchange(ref TransferredBytes, position);
    internal void AddSent(long bytes) => Interlocked.Add(ref SentBytes, bytes);
}

/// <summary>Live view of transfers for the PC window. Speed is computed when a snapshot is taken.</summary>
public sealed class TransferMonitor
{
    private const int MaxEntries = 200;

    private readonly object _lock = new();
    private readonly List<TransferEntry> _entries = [];

    public TransferEntry Track(string key, string deviceName, TransferDirection direction, string name, long size)
    {
        lock (_lock)
        {
            var existing = _entries.FindIndex(e => e.Key == key);
            if (existing >= 0) _entries.RemoveAt(existing);

            var entry = new TransferEntry
            {
                Key = key,
                DeviceName = deviceName,
                Direction = direction,
                Name = name,
                Size = size,
                LastTicks = Stopwatch.GetTimestamp(),
            };
            _entries.Insert(0, entry);
            if (_entries.Count > MaxEntries) _entries.RemoveAt(_entries.Count - 1);
            return entry;
        }
    }

    /// <summary>
    /// One request for (part of) a file sent to a device. Requests for the same key that overlap in
    /// time join one entry, which ends when the last of them does.
    /// </summary>
    public TransferEntry TrackDownload(string key, string deviceName, string name, long size, long requestedBytes)
    {
        lock (_lock)
        {
            var existing = _entries.Find(e => e.Key == key);
            if (existing is { State: TransferState.Active, Ranged: true })
            {
                existing.Streams++;
                Interlocked.Add(ref existing.RequestedBytes, requestedBytes);
                return existing;
            }

            var entry = Track(key, deviceName, TransferDirection.FromPc, name, size);
            entry.Ranged = true;
            entry.Streams = 1;
            entry.RequestedBytes = requestedBytes;
            entry.LastBytes = entry.Transferred;
            return entry;
        }
    }

    /// <summary>Called once per request from <see cref="TrackDownload"/>.</summary>
    public void EndStream(TransferEntry entry)
    {
        lock (_lock)
        {
            if (--entry.Streams > 0) return;
            var complete = Interlocked.Read(ref entry.SentBytes) >= Interlocked.Read(ref entry.RequestedBytes);
            Finish(entry, complete ? TransferState.Done : TransferState.Interrupted);
        }
    }

    public void Finish(TransferEntry entry, TransferState state)
    {
        lock (_lock)
        {
            entry.State = state;
            entry.BytesPerSecond = 0;
        }
    }

    public IReadOnlyList<TransferEntry> Snapshot()
    {
        lock (_lock)
        {
            var now = Stopwatch.GetTimestamp();
            foreach (var entry in _entries.Where(e => e.State == TransferState.Active))
            {
                var seconds = Stopwatch.GetElapsedTime(entry.LastTicks, now).TotalSeconds;
                if (seconds <= 0) continue;
                var current = entry.Transferred;
                var speed = Math.Max(0, current - entry.LastBytes) / seconds;
                entry.BytesPerSecond = entry.BytesPerSecond * 0.5 + speed * 0.5;
                entry.LastBytes = current;
                entry.LastTicks = now;
            }
            return _entries.ToList();
        }
    }
}
