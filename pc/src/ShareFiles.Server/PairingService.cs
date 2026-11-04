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
        // 128 bits is plenty for a single-use secret that lives five minutes, and a shorter link
        // makes a QR code with larger modules, which a camera reads more reliably off a screen.
        var ticket = new PairingTicket(
            DeviceRegistry.Base64Url(RandomNumberGenerator.GetBytes(16)),
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
