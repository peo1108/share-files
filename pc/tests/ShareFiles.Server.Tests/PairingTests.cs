using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Xunit;

namespace ShareFiles.Server.Tests;

public class PairingTests
{
    private static object PairBody(string secret, string deviceId = "d1") =>
        new { secret, deviceId, deviceName = "Y700", platform = "android" };

    [Fact]
    public async Task Info_NoAuth()
    {
        await using var host = await TestHost.StartAsync();
        var json = await host.Http.GetFromJsonAsync<JsonElement>("/v1/info");
        Assert.Equal(host.Server.Config.Current.ServerId, json.GetProperty("id").GetString());
        Assert.Equal(1, json.GetProperty("apiVersion").GetInt32());
    }

    [Fact]
    public async Task Pair_SecretOneUse()
    {
        await using var host = await TestHost.StartAsync();
        var ticket = host.Server.Pairing.CreateTicket();

        var first = await host.Http.PostAsJsonAsync("/v1/pair", PairBody(ticket.Secret));
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);

        var second = await host.Http.PostAsJsonAsync("/v1/pair", PairBody(ticket.Secret, "d2"));
        Assert.Equal(HttpStatusCode.Forbidden, second.StatusCode);
        var error = await second.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("pair_invalid", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Pair_Expired()
    {
        await using var host = await TestHost.StartAsync();
        var ticket = host.Server.Pairing.CreateTicket();
        host.Time.Advance(TimeSpan.FromMinutes(5) + TimeSpan.FromSeconds(1));

        var response = await host.Http.PostAsJsonAsync("/v1/pair", PairBody(ticket.Secret));
        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task Pair_LinkContainsFingerprintAndSecret()
    {
        await using var host = await TestHost.StartAsync();
        var ticket = host.Server.Pairing.CreateTicket();
        var link = host.Server.BuildPairLink(ticket, ["192.168.50.211", "10.0.2.2"]);

        Assert.StartsWith("sharefiles://pair?", link);
        Assert.Contains("fp=" + host.Server.Fingerprint, link);
        Assert.Contains("secret=" + ticket.Secret, link);
        Assert.Contains("addrs=192.168.50.211,10.0.2.2", link);
        Assert.Contains("port=" + host.Server.BoundPort, link);
    }

    [Fact]
    public async Task Auth_Missing401()
    {
        await using var host = await TestHost.StartAsync();
        var response = await host.Http.GetAsync("/v1/roots");
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Auth_WrongToken401()
    {
        await using var host = await TestHost.StartAsync();
        var response = await host.SendAsync(HttpMethod.Get, "/v1/roots", "not-a-token");
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Auth_RevokedToken401()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync("d1");
        Assert.Equal(HttpStatusCode.OK, (await host.SendAsync(HttpMethod.Get, "/v1/roots", token)).StatusCode);

        host.Server.Devices.Revoke("d1");

        Assert.Equal(HttpStatusCode.Unauthorized, (await host.SendAsync(HttpMethod.Get, "/v1/roots", token)).StatusCode);
    }

    [Fact]
    public async Task Pair_AgainInvalidatesOldToken()
    {
        await using var host = await TestHost.StartAsync();
        var oldToken = await host.PairAsync("d1");
        var newToken = await host.PairAsync("d1");

        Assert.Equal(HttpStatusCode.Unauthorized, (await host.SendAsync(HttpMethod.Get, "/v1/roots", oldToken)).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await host.SendAsync(HttpMethod.Get, "/v1/roots", newToken)).StatusCode);
    }

    [Fact]
    public async Task Token_StoredOnlyAsHash()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync("d1");

        var stored = await File.ReadAllTextAsync(Path.Combine(host.Root, "data", "devices.json"));
        Assert.DoesNotContain(token, stored);
    }

    [Fact]
    public async Task Devices_SurviveRestart()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync("d1");

        var reloaded = new DeviceRegistry(Path.Combine(host.Root, "data"), TimeProvider.System);
        Assert.Equal("d1", reloaded.Authenticate(token)?.DeviceId);
    }
}
