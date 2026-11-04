using System.Security.Cryptography;

namespace ShareFiles.Server;

public sealed record PairingTicket(string Secret, DateTimeOffset ExpiresAt);

/// <summary>Issues single-use pairing secrets that expire after five minutes.</summary>
public sealed class PairingService(TimeProvider time)
{
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(5);

    private readonly object _lock = new();
    private readonly Dictionary<string, DateTimeOffset> _tickets = [];

    public PairingTicket CreateTicket()
    {
        var ticket = new PairingTicket(
            DeviceRegistry.Base64Url(RandomNumberGenerator.GetBytes(32)),
            time.GetUtcNow() + Lifetime);
        lock (_lock)
        {
            foreach (var expired in _tickets.Where(t => t.Value <= time.GetUtcNow()).Select(t => t.Key).ToList())
            {
                _tickets.Remove(expired);
            }
            _tickets[ticket.Secret] = ticket.ExpiresAt;
        }
        return ticket;
    }

    public bool TryConsume(string secret)
    {
        lock (_lock)
        {
            return _tickets.Remove(secret, out var expiresAt) && expiresAt > time.GetUtcNow();
        }
    }
}
