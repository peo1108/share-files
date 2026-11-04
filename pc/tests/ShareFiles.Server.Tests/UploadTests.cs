using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Xunit;

namespace ShareFiles.Server.Tests;

public class UploadTests
{
    private static async Task<JsonElement> BeginAsync(TestHost host, string token, string fileKey, string name, long size, DateTimeOffset? modifiedAt = null)
    {
        var response = await host.SendAsync(HttpMethod.Post, "/v1/uploads", token,
            JsonContent.Create(new { fileKey, name, size, modifiedAt }));
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private static async Task<HttpResponseMessage> PatchAsync(TestHost host, string token, string id, long offset, byte[] body)
    {
        var request = host.Authed(HttpMethod.Patch, $"/v1/uploads/{id}", token);
        request.Headers.Add("Upload-Offset", offset.ToString());
        request.Content = new ByteArrayContent(body);
        return await host.Http.SendAsync(request);
    }

    [Fact]
    public async Task Upload_Complete()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var data = Bytes.Random(5_000_000);
        var modified = new DateTimeOffset(2024, 5, 1, 10, 0, 0, TimeSpan.Zero);

        var begun = await BeginAsync(host, token, "k1", "IMG_1.HEIC", data.Length, modified);
        Assert.Equal(0, begun.GetProperty("offset").GetInt64());

        var patch = await PatchAsync(host, token, begun.GetProperty("id").GetString()!, 0, data);
        var result = await patch.Content.ReadFromJsonAsync<JsonElement>();

        Assert.True(result.GetProperty("completed").GetBoolean());
        Assert.Equal("IMG_1.HEIC", result.GetProperty("savedName").GetString());
        var saved = Path.Combine(host.Inbox, "IMG_1.HEIC");
        Assert.Equal(Bytes.Sha(data), Bytes.Sha(await File.ReadAllBytesAsync(saved)));
        Assert.Equal(modified.UtcDateTime, File.GetLastWriteTimeUtc(saved));
    }

    /// <summary>Sends the first part of a body, then kills the connection.</summary>
    private sealed class AbortingContent(byte[] data, int sendBytes) : HttpContent
    {
        protected override async Task SerializeToStreamAsync(Stream stream, TransportContext? context)
        {
            await stream.WriteAsync(data.AsMemory(0, sendBytes));
            await stream.FlushAsync();
            await Task.Delay(300);
            throw new IOException("simulated network loss");
        }

        protected override bool TryComputeLength(out long length)
        {
            length = data.Length;
            return true;
        }
    }

    [Fact]
    public async Task Upload_ResumeAfterAbort()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var data = Bytes.Random(8_000_000);
        const int firstPart = 3_000_000;

        var begun = await BeginAsync(host, token, "k1", "big.bin", data.Length);
        var id = begun.GetProperty("id").GetString()!;

        var aborted = host.Authed(HttpMethod.Patch, $"/v1/uploads/{id}", token);
        aborted.Headers.Add("Upload-Offset", "0");
        aborted.Content = new AbortingContent(data, firstPart);
        await Assert.ThrowsAnyAsync<Exception>(() => host.Http.SendAsync(aborted));

        // The server flushes what it received once it sees the abort.
        long offset = 0;
        for (var i = 0; i < 50 && offset < firstPart; i++)
        {
            await Task.Delay(100);
            offset = (await BeginAsync(host, token, "k1", "big.bin", data.Length)).GetProperty("offset").GetInt64();
        }
        Assert.Equal(firstPart, offset);

        var patch = await PatchAsync(host, token, id, offset, data[(int)offset..]);
        var result = await patch.Content.ReadFromJsonAsync<JsonElement>();

        Assert.True(result.GetProperty("completed").GetBoolean());
        Assert.Equal(Bytes.Sha(data), Bytes.Sha(await File.ReadAllBytesAsync(Path.Combine(host.Inbox, "big.bin"))));
    }

    [Fact]
    public async Task Upload_OffsetMismatch409()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var begun = await BeginAsync(host, token, "k1", "a.bin", 1000);
        var id = begun.GetProperty("id").GetString()!;

        var response = await PatchAsync(host, token, id, 500, new byte[500]);

        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("offset_mismatch", error.GetProperty("code").GetString());
        Assert.Equal(0, error.GetProperty("offset").GetInt64());
    }

    [Fact]
    public async Task Upload_BodyLargerThanDeclared400()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var begun = await BeginAsync(host, token, "k1", "a.bin", 10);

        var response = await PatchAsync(host, token, begun.GetProperty("id").GetString()!, 0, new byte[11]);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.False(File.Exists(Path.Combine(host.Inbox, "a.bin")));
    }

    [Fact]
    public async Task Upload_DuplicateNameGetsSuffix()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        Directory.CreateDirectory(host.Inbox);
        await File.WriteAllTextAsync(Path.Combine(host.Inbox, "a.txt"), "original");
        var data = Bytes.Random(10);

        var begun = await BeginAsync(host, token, "k1", "a.txt", data.Length);
        var patch = await PatchAsync(host, token, begun.GetProperty("id").GetString()!, 0, data);
        var result = await patch.Content.ReadFromJsonAsync<JsonElement>();

        Assert.Equal("a (1).txt", result.GetProperty("savedName").GetString());
        Assert.Equal("original", await File.ReadAllTextAsync(Path.Combine(host.Inbox, "a.txt")));
    }

    [Theory]
    [InlineData("../../evil.txt", "evil.txt")]
    [InlineData("..\\..\\evil.txt", "evil.txt")]
    [InlineData("C:\\Windows\\evil.txt", "evil.txt")]
    [InlineData("/etc/passwd", "passwd")]
    [InlineData("CON", "_CON")]
    [InlineData("con.txt", "_con.txt")]
    [InlineData("a<b>c:d?e*.txt", "a_b_c_d_e_.txt")]
    [InlineData("trailing. . ", "trailing")]
    [InlineData("..", "file")]
    [InlineData("", "file")]
    [InlineData("ảnh đẹp.heic", "ảnh đẹp.heic")]
    public async Task Upload_SanitizesName(string name, string expected)
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var data = Bytes.Random(4);

        var begun = await BeginAsync(host, token, "k1", name, data.Length);
        var patch = await PatchAsync(host, token, begun.GetProperty("id").GetString()!, 0, data);
        var result = await patch.Content.ReadFromJsonAsync<JsonElement>();

        Assert.Equal(expected, result.GetProperty("savedName").GetString());
        Assert.True(File.Exists(Path.Combine(host.Inbox, expected)));
    }

    [Fact]
    public async Task Upload_EmptyFile()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();

        var begun = await BeginAsync(host, token, "k1", "empty.txt", 0);

        Assert.True(begun.GetProperty("completed").GetBoolean());
        Assert.Equal("empty.txt", begun.GetProperty("savedName").GetString());
        Assert.Equal(0, new FileInfo(Path.Combine(host.Inbox, "empty.txt")).Length);
    }

    [Fact]
    public async Task Upload_RetryAfterCompletedDoesNotDuplicate()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var data = Bytes.Random(100);

        var begun = await BeginAsync(host, token, "k1", "a.bin", data.Length);
        await PatchAsync(host, token, begun.GetProperty("id").GetString()!, 0, data);

        // Client lost the final response and starts over with the same key.
        var again = await BeginAsync(host, token, "k1", "a.bin", data.Length);

        Assert.True(again.GetProperty("completed").GetBoolean());
        Assert.Equal("a.bin", again.GetProperty("savedName").GetString());
        Assert.Single(Directory.GetFiles(host.Inbox));
    }

    [Fact]
    public async Task Upload_Delete_RemovesPartial()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var begun = await BeginAsync(host, token, "k1", "a.bin", 100);
        var id = begun.GetProperty("id").GetString()!;
        await PatchAsync(host, token, id, 0, new byte[50]);

        var deleted = await host.SendAsync(HttpMethod.Delete, $"/v1/uploads/{id}", token);
        Assert.Equal(HttpStatusCode.NoContent, deleted.StatusCode);

        var again = await BeginAsync(host, token, "k1", "a.bin", 100);
        Assert.Equal(0, again.GetProperty("offset").GetInt64());
    }

    [Fact]
    public async Task Upload_OtherDeviceCannotTouch()
    {
        await using var host = await TestHost.StartAsync();
        var owner = await host.PairAsync("d1");
        var other = await host.PairAsync("d2");
        var begun = await BeginAsync(host, owner, "k1", "a.bin", 10);

        var response = await PatchAsync(host, other, begun.GetProperty("id").GetString()!, 0, new byte[10]);

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task Upload_ResumesAcrossServerRestart()
    {
        await using var host = await TestHost.StartAsync();
        var token = await host.PairAsync();
        var data = Bytes.Random(1000);
        var begun = await BeginAsync(host, token, "k1", "a.bin", data.Length);
        await PatchAsync(host, token, begun.GetProperty("id").GetString()!, 0, data[..400]);

        await host.Server.StopAsync();
        await host.Server.StartAsync();
        host.Http.Dispose();
        using var http = new HttpClient(new SocketsHttpHandler
        {
            SslOptions = { RemoteCertificateValidationCallback = (_, _, _, _) => true },
        })
        { BaseAddress = new Uri($"https://127.0.0.1:{host.Server.BoundPort}") };

        var request = host.Authed(HttpMethod.Post, "/v1/uploads", token);
        request.Content = JsonContent.Create(new { fileKey = "k1", name = "a.bin", size = data.Length });
        var json = await (await http.SendAsync(request)).Content.ReadFromJsonAsync<JsonElement>();

        Assert.Equal(400, json.GetProperty("offset").GetInt64());
    }
}
