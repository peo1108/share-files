using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;

namespace ShareFiles.Server.Tests;

/// <summary>Runs a real server over TLS on a random loopback port, in a temp folder.</summary>
public sealed class TestHost : IAsyncDisposable
{
    public string Root { get; }
    public string Inbox { get; }
    public string SharedDir { get; }
    public ManualTime Time { get; } = new();
    public ShareServer Server { get; }
    public HttpClient Http { get; }

    private TestHost(string root)
    {
        Root = root;
        Inbox = Path.Combine(root, "inbox");
        SharedDir = Path.Combine(root, "shared");
        Directory.CreateDirectory(SharedDir);

        Server = new ShareServer(new ServerOptions
        {
            DataDirectory = Path.Combine(root, "data"),
            Port = 0,
            BindAddress = IPAddress.Loopback,
            DefaultInbox = Inbox,
            DefaultRoot = SharedDir,
            TimeProvider = Time,
        });
        Http = new HttpClient(new SocketsHttpHandler
        {
            SslOptions = { RemoteCertificateValidationCallback = (_, _, _, _) => true },
        })
        { Timeout = TimeSpan.FromSeconds(60) };
    }

    public static async Task<TestHost> StartAsync()
    {
        var root = Path.Combine(Path.GetTempPath(), "sharefiles-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var host = new TestHost(root);
        await host.Server.StartAsync();
        host.Http.BaseAddress = new Uri($"https://127.0.0.1:{host.Server.BoundPort}");
        return host;
    }

    public async Task<string> PairAsync(string deviceId = "device-1", string name = "Y700")
    {
        var ticket = Server.Pairing.CreateTicket();
        var response = await Http.PostAsJsonAsync("/v1/pair", new
        {
            secret = ticket.Secret,
            deviceId,
            deviceName = name,
            platform = "android",
        });
        response.EnsureSuccessStatusCode();
        var json = await response.Content.ReadFromJsonAsync<JsonElement>();
        return json.GetProperty("token").GetString()!;
    }

    public HttpRequestMessage Authed(HttpMethod method, string url, string token) =>
        new(method, url) { Headers = { Authorization = new AuthenticationHeaderValue("Bearer", token) } };

    public async Task<HttpResponseMessage> SendAsync(HttpMethod method, string url, string token, HttpContent? content = null)
    {
        var request = Authed(method, url, token);
        request.Content = content;
        return await Http.SendAsync(request);
    }

    public string RootId => Server.Config.Current.Roots.Single().Id;

    public async ValueTask DisposeAsync()
    {
        Http.Dispose();
        await Server.StopAsync();
        try { Directory.Delete(Root, recursive: true); } catch (IOException) { }
    }
}

public sealed class ManualTime : TimeProvider
{
    private DateTimeOffset _now = DateTimeOffset.UtcNow;
    public override DateTimeOffset GetUtcNow() => _now;
    public void Advance(TimeSpan by) => _now += by;
}

public static class Bytes
{
    public static byte[] Random(int length)
    {
        var data = new byte[length];
        RandomNumberGenerator.Fill(data);
        return data;
    }

    public static string Sha(byte[] data) => Convert.ToHexString(SHA256.HashData(data));
}
