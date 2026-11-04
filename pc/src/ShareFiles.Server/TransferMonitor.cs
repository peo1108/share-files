using System.Diagnostics;

namespace ShareFiles.Server;

public enum TransferDirection { ToPc, FromPc }

public enum TransferState { Active, Done, Interrupted }

public sealed class TransferEntry
{
    internal long TransferredBytes;
    internal long LastBytes;
    internal long LastTicks;

    public required string Key { get; init; }
    public required string DeviceName { get; init; }
    public required TransferDirection Direction { get; init; }
    public required string Name { get; init; }
    public required long Size { get; init; }
    public TransferState State { get; internal set; } = TransferState.Active;
    public double BytesPerSecond { get; internal set; }
    public long Transferred => Interlocked.Read(ref TransferredBytes);
    public void Report(long position) => Interlocked.Exchange(ref TransferredBytes, position);
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
