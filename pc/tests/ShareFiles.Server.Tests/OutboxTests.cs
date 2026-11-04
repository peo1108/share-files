using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Xunit;

namespace ShareFiles.Server.Tests;

public class OutboxTests
{
    private static async Task<JsonElement[]> ListAsync(TestHost host, string token)
    {
        var response = await host.SendAsync(HttpMethod.Get, "/v1/outbox", token);
        var json = await response.Content.ReadFromJsonAsync<JsonElement>();
        return json.EnumerateArray().ToArray();
    }

    private static async Task<string> MakeFile(TestHost host, string name, byte[] data)
    {
        var path = Path.Combine(host.Root, name);
        await File.WriteAllBytesAsync(path, data);
        return path;
    }

    [Fact]
    public async Task Outbox_ListDownloadAck()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync("d1");
        var data = Bytes.Random(50_000);
        host.Server.Outbox.Enqueue("d1", await MakeFile(host, "movie.bin", data));

        var items = await ListAsync(host, token);
        var item = Assert.Single(items);
        Assert.Equal("movie.bin", item.GetProperty("name").GetString());
        Assert.Equal(data.Length, item.GetProperty("size").GetInt64());
        Assert.False(item.GetProperty("sourceMissing").GetBoolean());
        var id = item.GetProperty("id").GetString()!;

        var request = host.Authed(HttpMethod.Get, $"/v1/outbox/{id}/content", token);
        request.Headers.Range = new RangeHeaderValue(40_000, null);
        var partial = await host.Http.SendAsync(request);
        Assert.Equal(HttpStatusCode.PartialContent, partial.StatusCode);
        Assert.Equal(Bytes.Sha(data[40_000..]), Bytes.Sha(await partial.Content.ReadAsByteArrayAsync()));

        var ack = await host.SendAsync(HttpMethod.Post, $"/v1/outbox/{id}/ack", token);
        Assert.Equal(HttpStatusCode.NoContent, ack.StatusCode);
        Assert.Empty(await ListAsync(host, token));
    }

    [Fact]
    public async Task Outbox_SourceGone410()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync("d1");
        var path = await MakeFile(host, "gone.bin", Bytes.Random(10));
        host.Server.Outbox.Enqueue("d1", path);
        File.Delete(path);

        var item = Assert.Single(await ListAsync(host, token));
        Assert.True(item.GetProperty("sourceMissing").GetBoolean());

        var response = await host.SendAsync(HttpMethod.Get, $"/v1/outbox/{item.GetProperty("id").GetString()}/content", token);
        Assert.Equal(HttpStatusCode.Gone, response.StatusCode);
    }

    [Fact]
    public async Task Outbox_OnlyOwnDevice()
    {
        await using var host = await TestHost.StartAsync();
        var mine = await host.PairAsync("d1");
        var theirs = await host.PairAsync("d2");
        var item = host.Server.Outbox.Enqueue("d1", await MakeFile(host, "a.bin", Bytes.Random(10)));

        Assert.Empty(await ListAsync(host, theirs));
        Assert.Equal(HttpStatusCode.NotFound, (await host.SendAsync(HttpMethod.Get, $"/v1/outbox/{item.Id}/content", theirs)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await host.SendAsync(HttpMethod.Post, $"/v1/outbox/{item.Id}/ack", theirs)).StatusCode);
        Assert.Single(await ListAsync(host, mine));
    }

    [Fact]
    public async Task Outbox_Delete()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync("d1");
        var item = host.Server.Outbox.Enqueue("d1", await MakeFile(host, "a.bin", Bytes.Random(10)));

        var response = await host.SendAsync(HttpMethod.Delete, $"/v1/outbox/{item.Id}", token);

        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        Assert.Empty(await ListAsync(host, token));
    }

    [Fact]
    public async Task Outbox_SurvivesRestart()
    {
        await using var host = await TestHost.StartAsync();
        await host.PairAsync("d1");
        var path = await MakeFile(host, "a.bin", Bytes.Random(10));
        host.Server.Outbox.Enqueue("d1", path);

        var reloaded = new OutboxStore(Path.Combine(host.Root, "data"), TimeProvider.System);
        Assert.Equal(path, Assert.Single(reloaded.ListFor("d1")).Path);
    }

    [Fact]
    public async Task Revoke_ClearsDeviceOutbox()
    {
        await using var host = await TestHost.StartAsync();
        await host.PairAsync("d1");
        host.Server.Outbox.Enqueue("d1", await MakeFile(host, "a.bin", Bytes.Random(10)));

        host.Server.RevokeDevice("d1");

        Assert.Empty(host.Server.Outbox.ListFor("d1"));
    }
}
