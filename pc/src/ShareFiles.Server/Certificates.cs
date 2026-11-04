using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace ShareFiles.Server;

/// <summary>The server's self-signed certificate. Clients trust it by pinning its fingerprint.</summary>
public static class Certificates
{
    public static X509Certificate2 LoadOrCreate(string dataDirectory)
    {
        var path = Path.Combine(dataDirectory, "server.pfx");
        if (!File.Exists(path))
        {
            Directory.CreateDirectory(dataDirectory);
            using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
            var request = new CertificateRequest("CN=ShareFiles", key, HashAlgorithmName.SHA256);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, false));
            request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
                new OidCollection { new("1.3.6.1.5.5.7.3.1") }, false));

            var now = DateTimeOffset.UtcNow;
            using var created = request.CreateSelfSigned(now.AddDays(-1), now.AddYears(20));
            var temp = path + ".tmp";
            File.WriteAllBytes(temp, created.Export(X509ContentType.Pfx));
            File.Move(temp, path, overwrite: true);
        }
        return X509CertificateLoader.LoadPkcs12FromFile(path, null);
    }

    public static string Fingerprint(X509Certificate2 certificate) =>
        Convert.ToHexStringLower(SHA256.HashData(certificate.RawData));
}
