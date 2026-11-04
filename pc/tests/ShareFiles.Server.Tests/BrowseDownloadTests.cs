using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Xunit;

namespace ShareFiles.Server.Tests;

public class BrowseDownloadTests
{
    private static string Q(string value) => Uri.EscapeDataString(value);

    [Fact]
    public async Task Browse_ListsDirsFirst()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        Directory.CreateDirectory(Path.Combine(host.SharedDir, "z-dir"));
        await File.WriteAllTextAsync(Path.Combine(host.SharedDir, "a.txt"), "hello");

        var response = await host.SendAsync(HttpMethod.Get, $"/v1/browse?root={host.RootId}&path=", token);
        var json = await response.Content.ReadFromJsonAsync<JsonElement>();
        var entries = json.GetProperty("entries").EnumerateArray().ToArray();

        Assert.Equal("z-dir", entries[0].GetProperty("name").GetString());
        Assert.True(entries[0].GetProperty("isDirectory").GetBoolean());
        Assert.Equal("a.txt", entries[1].GetProperty("name").GetString());
        Assert.Equal(5, entries[1].GetProperty("size").GetInt64());
    }

    [Theory]
    [InlineData("..")]
    [InlineData("../..")]
    [InlineData("sub/../..")]
    [InlineData("..\\..")]
    [InlineData("C:\\Windows")]
    [InlineData("/Windows")]
    [InlineData("a.txt:hidden")]
    public async Task Browse_TraversalForbidden(string path)
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();

        var response = await host.SendAsync(HttpMethod.Get, $"/v1/browse?root={host.RootId}&path={Q(path)}", token);

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("path_forbidden", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Download_TraversalForbidden()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        await File.WriteAllTextAsync(Path.Combine(host.Root, "secret.txt"), "secret");

        var response = await host.SendAsync(HttpMethod.Get, $"/v1/download?root={host.RootId}&path={Q("../secret.txt")}", token);

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task Browse_JunctionCannotEscapeRoot()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var outside = Path.Combine(host.Root, "outside");
        Directory.CreateDirectory(outside);
        await File.WriteAllTextAsync(Path.Combine(outside, "secret.txt"), "secret");
        var junction = Path.Combine(host.SharedDir, "link");
        using (var mk = Process.Start(new ProcessStartInfo("cmd.exe", $"/c mklink /J \"{junction}\" \"{outside}\"")
        { UseShellExecute = false, RedirectStandardOutput = true, CreateNoWindow = true })!)
        {
            await mk.WaitForExitAsync();
            Assert.Equal(0, mk.ExitCode);
        }

        var listing = await (await host.SendAsync(HttpMethod.Get, $"/v1/browse?root={host.RootId}&path=", token))
            .Content.ReadFromJsonAsync<JsonElement>();
        Assert.Empty(listing.GetProperty("entries").EnumerateArray());

        var browse = await host.SendAsync(HttpMethod.Get, $"/v1/browse?root={host.RootId}&path=link", token);
        Assert.Equal(HttpStatusCode.Forbidden, browse.StatusCode);

        var download = await host.SendAsync(HttpMethod.Get, $"/v1/download?root={host.RootId}&path={Q("link/secret.txt")}", token);
        Assert.Equal(HttpStatusCode.Forbidden, download.StatusCode);
    }

    [Fact]
    public async Task Browse_UnknownRoot404()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var response = await host.SendAsync(HttpMethod.Get, "/v1/browse?root=nope&path=", token);
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task Download_Range()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var data = Bytes.Random(100_000);
        await File.WriteAllBytesAsync(Path.Combine(host.SharedDir, "a.bin"), data);

        var whole = await host.SendAsync(HttpMethod.Get, $"/v1/download?root={host.RootId}&path=a.bin", token);
        Assert.Equal(Bytes.Sha(data), Bytes.Sha(await whole.Content.ReadAsByteArrayAsync()));
        Assert.NotNull(whole.Headers.ETag);

        var request = host.Authed(HttpMethod.Get, $"/v1/download?root={host.RootId}&path=a.bin", token);
        request.Headers.Range = new RangeHeaderValue(90_000, null);
        var partial = await host.Http.SendAsync(request);

        Assert.Equal(HttpStatusCode.PartialContent, partial.StatusCode);
        Assert.Equal(Bytes.Sha(data[90_000..]), Bytes.Sha(await partial.Content.ReadAsByteArrayAsync()));
    }

    [Fact]
    public async Task Download_NameWithReservedCharacters()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var data = Bytes.Random(100);
        await File.WriteAllBytesAsync(Path.Combine(host.SharedDir, "a+b c&d=ảnh.txt"), data);

        // Exactly what the clients send: "+" must arrive as a plus, not as a space.
        var response = await host.SendAsync(HttpMethod.Get,
            $"/v1/download?root={host.RootId}&path=a%2Bb%20c%26d%3D%E1%BA%A3nh.txt", token);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(Bytes.Sha(data), Bytes.Sha(await response.Content.ReadAsByteArrayAsync()));
    }

    [Fact]
    public async Task Download_FinishesWithoutServerError()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        await File.WriteAllBytesAsync(Path.Combine(host.SharedDir, "a.bin"), Bytes.Random(1000));

        var response = await host.SendAsync(HttpMethod.Get, $"/v1/download?root={host.RootId}&path=a.bin", token);
        await response.Content.ReadAsByteArrayAsync();
        await Task.Delay(200);

        var log = Path.Combine(host.Root, "data", "server.log");
        Assert.False(File.Exists(log), File.Exists(log) ? await File.ReadAllTextAsync(log) : "");
        var entry = Assert.Single(host.Server.Monitor.Snapshot());
        Assert.Equal(TransferState.Done, entry.State);
    }

    [Fact]
    public async Task Speedtest_ExactBytes()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();

        var download = await host.SendAsync(HttpMethod.Get, "/v1/speedtest/download?bytes=3000000", token);
        Assert.Equal(3_000_000, (await download.Content.ReadAsByteArrayAsync()).Length);

        var upload = await host.SendAsync(HttpMethod.Post, "/v1/speedtest/upload", token, new ByteArrayContent(Bytes.Random(2_000_000)));
        var json = await upload.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(2_000_000, json.GetProperty("bytes").GetInt64());
    }

    [Fact]
    public async Task Speedtest_RejectsOverLimit()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var response = await host.SendAsync(HttpMethod.Get, $"/v1/speedtest/download?bytes={5L * 1024 * 1024 * 1024}", token);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }
}
