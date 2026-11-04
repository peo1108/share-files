using System.Security.Cryptography.X509Certificates;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace ShareFiles.Server;

/// <summary>The Share Files service: state, HTTPS endpoint and lifecycle. Has no UI dependencies.</summary>
public sealed class ShareServer
{
    private const long MaxLogBytes = 1024 * 1024;

    private readonly ServerOptions _options;
    private readonly X509Certificate2 _certificate;
    private readonly string _logPath;
    private readonly object _logLock = new();
    private WebApplication? _app;

    public ConfigStore Config { get; }
    public DeviceRegistry Devices { get; }
    public PairingService Pairing { get; }
    public UploadManager Uploads { get; }
    public OutboxStore Outbox { get; }
    public TransferMonitor Monitor { get; } = new();
    public string Fingerprint { get; }
    public int BoundPort { get; private set; }

    public ShareServer(ServerOptions options)
    {
        _options = options;
        Directory.CreateDirectory(options.DataDirectory);
        _logPath = Path.Combine(options.DataDirectory, "server.log");
        _certificate = Certificates.LoadOrCreate(options.DataDirectory);
        Fingerprint = Certificates.Fingerprint(_certificate);
        Config = new ConfigStore(options);
        Devices = new DeviceRegistry(options.DataDirectory, options.TimeProvider);
        Pairing = new PairingService(options.TimeProvider);
        Uploads = new UploadManager(options.DataDirectory, Config, options.TimeProvider);
        Outbox = new OutboxStore(options.DataDirectory, options.TimeProvider);
    }

    public async Task StartAsync()
    {
        if (_app is not null) return;

        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions
        {
            ContentRootPath = _options.DataDirectory,
        });
        builder.Logging.ClearProviders();
        builder.WebHost.UseKestrelHttpsConfiguration();
        builder.WebHost.ConfigureKestrel(kestrel =>
        {
            // No speed floors or size caps: slow phones and huge files are both normal here.
            kestrel.Limits.MaxRequestBodySize = null;
            kestrel.Limits.MinRequestBodyDataRate = null;
            kestrel.Limits.MinResponseDataRate = null;
            kestrel.Limits.MaxRequestBufferSize = 4 * 1024 * 1024;
            kestrel.Limits.MaxResponseBufferSize = 4 * 1024 * 1024;
            kestrel.Listen(_options.BindAddress, _options.Port, listen =>
            {
                listen.Protocols = HttpProtocols.Http1;
                listen.UseHttps(_certificate);
            });
        });

        var app = builder.Build();
        app.MapApi(this);
        await app.StartAsync();

        var address = app.Services.GetRequiredService<IServer>().Features
            .Get<IServerAddressesFeature>()!.Addresses.First();
        BoundPort = new Uri(address.Replace("0.0.0.0", "127.0.0.1")).Port;
        _app = app;
    }

    public async Task StopAsync()
    {
        var app = Interlocked.Exchange(ref _app, null);
        if (app is null) return;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        await app.StopAsync(timeout.Token);
        await app.DisposeAsync();
    }

    public string BuildPairLink(PairingTicket ticket, IEnumerable<string> addresses) =>
        "sharefiles://pair"
        + $"?id={Config.Current.ServerId}"
        + $"&name={Uri.EscapeDataString(Config.Current.ServerName)}"
        + $"&fp={Fingerprint}"
        + $"&secret={ticket.Secret}"
        + $"&port={BoundPort}"
        + $"&addrs={string.Join(',', addresses)}";

    public void RevokeDevice(string deviceId)
    {
        Devices.Revoke(deviceId);
        Outbox.RemoveAllFor(deviceId);
    }

    internal void Log(string message)
    {
        try
        {
            lock (_logLock)
            {
                if (File.Exists(_logPath) && new FileInfo(_logPath).Length > MaxLogBytes)
                {
                    File.Move(_logPath, _logPath + ".old", overwrite: true);
                }
                File.AppendAllText(_logPath, $"{DateTimeOffset.Now:O} {message}{Environment.NewLine}");
            }
        }
        catch (IOException)
        {
            // Logging must never take a transfer down with it.
        }
    }
}
