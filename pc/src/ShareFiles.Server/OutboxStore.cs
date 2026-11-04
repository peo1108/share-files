namespace ShareFiles.Server;

public sealed record OutboxItem(
    string Id,
    string DeviceId,
    string Path,
    string Name,
    long Size,
    DateTime ModifiedAt,
    DateTimeOffset CreatedAt);

/// <summary>Files waiting to be pulled by a device. Holds a reference to the file, not a copy.</summary>
public sealed class OutboxStore
{
    private readonly string _path;
    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly List<OutboxItem> _items;

    public event Action? Changed;

    public OutboxStore(string dataDirectory, TimeProvider time)
    {
        _path = Path.Combine(dataDirectory, "outbox.json");
        _time = time;
        _items = JsonFile.Read<List<OutboxItem>>(_path) ?? [];
    }

    public OutboxItem Enqueue(string deviceId, string filePath)
    {
        var info = new FileInfo(filePath);
        var item = new OutboxItem(
            Guid.NewGuid().ToString("N"),
            deviceId,
            info.FullName,
            info.Name,
            info.Length,
            info.LastWriteTimeUtc,
            _time.GetUtcNow());
        lock (_lock)
        {
            _items.Add(item);
            Save();
        }
        Changed?.Invoke();
        return item;
    }

    public IReadOnlyList<OutboxItem> ListFor(string deviceId)
    {
        lock (_lock) return _items.Where(i => i.DeviceId == deviceId).ToList();
    }

    public OutboxItem? Find(string deviceId, string id)
    {
        lock (_lock) return _items.FirstOrDefault(i => i.Id == id && i.DeviceId == deviceId);
    }

    public bool Remove(string deviceId, string id) => RemoveWhere(i => i.Id == id && i.DeviceId == deviceId);

    public void RemoveAllFor(string deviceId) => RemoveWhere(i => i.DeviceId == deviceId);

    private bool RemoveWhere(Predicate<OutboxItem> match)
    {
        bool removed;
        lock (_lock)
        {
            removed = _items.RemoveAll(match) > 0;
            if (removed) Save();
        }
        if (removed) Changed?.Invoke();
        return removed;
    }

    private void Save() => JsonFile.Write(_path, _items);
}
