using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Serialization;

namespace ShareFiles.Server;

public sealed class DeviceRecord
{
    public required string DeviceId { get; init; }
    public required string Name { get; init; }
    public required string Platform { get; init; }
    public required string TokenHash { get; init; }
    public required DateTimeOffset PairedAt { get; init; }

    [JsonIgnore]
    public DateTimeOffset? LastSeen { get; set; }
}

/// <summary>Paired devices. Only token hashes are kept on disk.</summary>
public sealed class DeviceRegistry
{
    private readonly string _path;
    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly Dictionary<string, DeviceRecord> _byId;

    public event Action? Changed;

    public DeviceRegistry(string dataDirectory, TimeProvider time)
    {
        _path = Path.Combine(dataDirectory, "devices.json");
        _time = time;
        var stored = JsonFile.Read<List<DeviceRecord>>(_path) ?? [];
        _byId = stored.ToDictionary(d => d.DeviceId);
    }

    public IReadOnlyList<DeviceRecord> All
    {
        get { lock (_lock) return _byId.Values.OrderBy(d => d.Name).ToList(); }
    }

    /// <summary>Adds or replaces a device and returns its new token. Any earlier token stops working.</summary>
    public string Register(string deviceId, string name, string platform)
    {
        var token = Base64Url(RandomNumberGenerator.GetBytes(32));
        lock (_lock)
        {
            _byId[deviceId] = new DeviceRecord
            {
                DeviceId = deviceId,
                Name = name,
                Platform = platform,
                TokenHash = Hash(token),
                PairedAt = _time.GetUtcNow(),
                LastSeen = _time.GetUtcNow(),
            };
            Save();
        }
        Changed?.Invoke();
        return token;
    }

    public DeviceRecord? Authenticate(string token)
    {
        var hash = Hash(token);
        lock (_lock)
        {
            var device = _byId.Values.FirstOrDefault(d => d.TokenHash == hash);
            if (device is not null) device.LastSeen = _time.GetUtcNow();
            return device;
        }
    }

    public bool Revoke(string deviceId)
    {
        bool removed;
        lock (_lock)
        {
            removed = _byId.Remove(deviceId);
            if (removed) Save();
        }
        if (removed) Changed?.Invoke();
        return removed;
    }

    public bool IsOnline(DeviceRecord device) =>
        device.LastSeen is { } seen && _time.GetUtcNow() - seen < TimeSpan.FromSeconds(10);

    private void Save() => JsonFile.Write(_path, _byId.Values.ToList());

    private static string Hash(string token) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    internal static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
