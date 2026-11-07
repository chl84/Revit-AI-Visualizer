using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.RegularExpressions;
using RevitVisualizer.Core;
using RevitVisualizer.Tests;
using static RevitVisualizer.Tests.Checks;

var checks = new Checks();
checks.Test("Crop retains rectangular edges and rejects crossed corners", () =>
{
    var selection = Selection.FullFrame.MoveCorner(0, new(0.25, 0.25), false);
    Equal(new ImagePoint(1, 0.25), selection.Corners[1]); Equal(new ImagePoint(0.25, 1), selection.Corners[3]);
    True(ReferenceEquals(selection, selection.MoveCorner(0, new(1, 1), true)));
    True(!new Selection([new(0, 0), new(1, 1), new(1, 0), new(0, 1)]).IsValid);
});
checks.Test("Ratio parsing and proportional fit preserve aspect", () =>
{
    True(Math.Abs(Selection.ParseAspectRatio("16:9")!.Value - 16d / 9) < 1e-12);
    True(Selection.ParseAspectRatio("NaN:9") is null && Selection.ParseAspectRatio("0:9") is null);
    Equal(new ImageSize(1600, 900), Selection.FullFrame.OutputSize(1600, 1200, 16d / 9));
    Equal((300d, 200d), Selection.Fit(1200, 800, 300, 500));
});
checks.Test("Projective mapping reaches all four source corners", () =>
{
    var selected = new Selection([new(0.1, 0.2), new(0.85, 0.1), new(0.9, 0.8), new(0.2, 0.9)]);
    var size = selected.OutputSize(640, 480); var map = new ProjectiveMap(selected, 640, 480, size);
    ImagePoint[] output = [new(0, 0), new(size.Width, 0), new(size.Width, size.Height), new(0, size.Height)];
    for (int i = 0; i < 4; i++)
    {
        var p = map.Transform(output[i].X, output[i].Y);
        True(Math.Abs(p.X - selected.Corners[i].X * 640) < 1e-7 && Math.Abs(p.Y - selected.Corners[i].Y * 480) < 1e-7);
    }
});
checks.Test("Full frame and crop preserve pixels and original bytes", () =>
{
    var bytes = Enumerable.Range(0, 128 * 96 * 4).Select(i => (byte)(i % 251)).ToArray();
    var raster = new RasterImage(128, 96, bytes); var original = (byte[])bytes.Clone();
    True(ReferenceEquals(raster, raster.Prepare(Selection.FullFrame, null)));
    var crop = raster.Prepare(new Selection([new(0.25, 0.25), new(0.75, 0.25), new(0.75, 0.75), new(0.25, 0.75)]), null);
    Equal(64, crop.Width); Equal(48, crop.Height);
    for (int y = 0; y < crop.Height; y++) Bytes(bytes.AsSpan(((y + 24) * 128 + 32) * 4, 64 * 4).ToArray(), crop.Pixels.AsSpan(y * 64 * 4, 64 * 4).ToArray());
    Bytes(original, raster.Pixels);
});
checks.Test("EXIF rotations and reflections move the correct pixels", () =>
{
    var bytes = new byte[24]; for (int i = 0; i < 6; i++) bytes[i * 4] = (byte)(i + 1);
    var raster = new RasterImage(2, 3, bytes); var rotated = raster.Orient(6);
    Equal(3, rotated.Width); Equal(2, rotated.Height);
    Bytes([5, 3, 1, 6, 4, 2], Enumerable.Range(0, 6).Select(i => rotated.Pixels[i * 4]).ToArray());
    var mirrored = raster.Orient(2); Equal((byte)2, mirrored.Pixels[0]); Equal((byte)1, mirrored.Pixels[4]);
    for (int orientation = 2; orientation <= 8; orientation++)
        Bytes([1, 2, 3, 4, 5, 6], Enumerable.Range(0, 6).Select(i => raster.Orient(orientation).Pixels[i * 4]).Order().ToArray());
});
checks.Test("Image size guards and cancellation stop processing", () =>
{
    Throws<ArgumentException>(() => RasterImage.ValidateSize(100_000, 100_000));
    using var cancellation = new CancellationTokenSource(); cancellation.Cancel();
    Throws<OperationCanceledException>(() => new RasterImage(64, 64, new byte[64 * 64 * 4]).Prepare(Selection.FullFrame.MoveCorner(0, new(0.1, 0.1), false), null, cancellation.Token));
});
checks.Test("PNG validation rejects truncation and preserves exact bytes", () =>
{
    byte[] bytes = Fixture.Png(); var parsed = PngImage.Read(bytes);
    Equal(64, parsed.Width); Equal(48, parsed.Height); True(ReferenceEquals(bytes, parsed.Bytes));
    Throws<InvalidDataException>(() => PngImage.Read(bytes[..^1]));
    Throws<InvalidDataException>(() => PngImage.Read(Encoding.ASCII.GetBytes("not an image")));
});
checks.Test("Windows prompt exactly matches the Android architecture prompt", () =>
{
    DirectoryInfo? directory = new(AppContext.BaseDirectory);
    string relative = "app/src/main/java/com/revitvisualizer/app/openai/ArchitecturePrompt.kt";
    while (directory is not null && !File.Exists(Path.Combine(directory.FullName, relative))) directory = directory.Parent;
    True(directory is not null, "Repository prompt source was not found.");
    string android = File.ReadAllText(Path.Combine(directory!.FullName, relative)).Split("\"\"\"")[1];
    string normalized = string.Join('\n', android.Replace("\r", "").Split('\n').Select(line => line.Trim())).Trim();
    Equal(normalized, OpenAiImagesClient.Prompt);
});

using var files = new TemporaryDirectory();
byte[] png = Fixture.Png(); string input = files.File("input.png"); File.WriteAllBytes(input, png);
string finalEvent = $"event: image_edit.completed\ndata: {{\"type\":\"image_edit.completed\",\"b64_json\":\"{Convert.ToBase64String(png)}\"}}\n\n";
char[] key = "local-test-placeholder".ToCharArray();

await checks.TestAsync("Request uses the fixed model, one PNG, original bytes and required parameters", async () =>
{
    var handler = new FixtureHandler(async (request, token) =>
    {
        Equal(OpenAiImagesClient.Endpoint, request.RequestUri!.ToString()); Equal(HttpMethod.Post, request.Method);
        byte[] sent = await request.Content!.ReadAsByteArrayAsync(token);
        string text = Encoding.UTF8.GetString(sent);
        foreach (string part in new[] { "gpt-image-2.6-sunburst", "partial_images", "quality", "max", "size", "auto", "output_format", "png", "stream", "true", "revit-view.png" }) True(text.Contains(part));
        True(text.Contains(OpenAiImagesClient.Prompt));
        string boundary = request.Content.Headers.ContentType!.Parameters.Single(p => p.Name == "boundary").Value!.Trim('"');
        var parts = text.Split("--" + boundary);
        foreach (var (field, value) in new[] { ("n", "1"), ("partial_images", "0"), ("stream", "true"), ("quality", "max"), ("size", "auto"), ("output_format", "png") })
        {
            string part = parts.Single(p => Regex.IsMatch(p, "name=\"?" + field + "\"?\\r?\\n"));
            Equal(value, part[(part.IndexOf("\r\n\r\n", StringComparison.Ordinal) + 4)..].TrimEnd('\r', '\n'));
        }
        True(sent.AsSpan().IndexOf(png) >= 0, "Uploaded PNG bytes changed.");
        True(!text.Contains("input_fidelity"));
        True(request.Headers.Contains("X-Client-Request-Id"));
        return Sse(finalEvent);
    });
    using var api = new OpenAiImagesClient(handler);
    Bytes(png, (await api.EditAsync(input, key)).Bytes); Bytes(png, File.ReadAllBytes(input)); Equal(1, handler.Calls);
});
await checks.TestAsync("Final SSE completes without waiting for EOF and ignores partial previews", async () =>
{
    var stream = new FinalWithoutEofStream(Encoding.UTF8.GetBytes(": keep-alive\n\nevent: image_edit.partial_image\ndata: invalid-partial\n\n" + finalEvent));
    var handler = new FixtureHandler((_, _) => Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
        { Content = new StreamContent(stream) { Headers = { ContentType = new MediaTypeHeaderValue("text/event-stream") } } }));
    using var api = new OpenAiImagesClient(handler);
    Bytes(png, (await api.EditAsync(input, key).WaitAsync(TimeSpan.FromSeconds(3))).Bytes); True(stream.Disposed); Equal(1, handler.Calls);
});
await checks.TestAsync("Multiline final events and JSON fallback decode exactly", async () =>
{
    string json = $"{{\"type\":\"image_edit.completed\",\n\"b64_json\":\"{Convert.ToBase64String(png)}\"}}";
    using (var api = new OpenAiImagesClient(new FixtureHandler((_, _) => Task.FromResult(Sse("data: " + json.Replace("\n", "\ndata: ") + "\n\n")))))
        Bytes(png, (await api.EditAsync(input, key)).Bytes);
    using (var api = new OpenAiImagesClient(new FixtureHandler((_, _) => Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
        { Content = new StringContent($"{{\"data\":[{{\"b64_json\":\"{Convert.ToBase64String(png)}\"}}]}}", Encoding.UTF8, "application/json") }))))
        Bytes(png, (await api.EditAsync(input, key)).Bytes);
});
await checks.TestAsync("HTTP errors are redacted and never retried", async () =>
{
    var handler = new FixtureHandler((_, _) => Task.FromResult(new HttpResponseMessage(HttpStatusCode.GatewayTimeout)
        { Content = new StringContent("untrusted-private-response local-test-placeholder") }));
    using var api = new OpenAiImagesClient(handler);
    var error = await ThrowsAsync<ImageApiException>(() => api.EditAsync(input, key));
    True(error.Message.Contains("HTTP_504")); True(!error.Message.Contains("untrusted-private") && !error.Message.Contains(new string(key))); Equal(1, handler.Calls);
});
await checks.TestAsync("Interrupted connection remains a transport failure and no resubmission occurs", async () =>
{
    var handler = new FixtureHandler((_, _) => throw new HttpRequestException(HttpRequestError.ResponseEnded, "untrusted-private-error"));
    using var api = new OpenAiImagesClient(handler);
    var error = await ThrowsAsync<ImageApiException>(() => api.EditAsync(input, key));
    True(error.Message.Contains("RESPONSE_EOF")); True(!error.Message.Contains("untrusted-private")); Equal(1, handler.Calls);
});
await checks.TestAsync("Deadline and explicit cancellation stop the pending request", async () =>
{
    var handler = new FixtureHandler(async (_, token) => { await Task.Delay(10_000, token); return Sse(finalEvent); });
    using (var api = new OpenAiImagesClient(handler, TimeSpan.FromMilliseconds(100)))
    {
        var error = await ThrowsAsync<ImageApiException>(() => api.EditAsync(input, key)); True(error.Message.Contains("REQUEST_DEADLINE"));
    }
    using var stop = new CancellationTokenSource(100);
    using var cancelled = new OpenAiImagesClient(new FixtureHandler(async (_, token) => { await Task.Delay(10_000, token); return Sse(finalEvent); }));
    await ThrowsAsync<OperationCanceledException>(() => cancelled.EditAsync(input, key, cancellation: stop.Token));
});
await checks.TestAsync("Closed partial stream, invalid PNG and error events fail safely", async () =>
{
    foreach (string payload in new[] { "event: image_edit.partial_image\ndata: {}\n\n", "event: error\ndata: untrusted-private-message\n\n",
                 "event: image_edit.completed\ndata: {\"b64_json\":\"bm90LWFuLWltYWdl\"}\n\n" })
    {
        using var api = new OpenAiImagesClient(new FixtureHandler((_, _) => Task.FromResult(Sse(payload))));
        var error = await ThrowsAsync<ImageApiException>(() => api.EditAsync(input, key));
        True(!error.Message.Contains("untrusted-private"));
    }
});
await checks.TestAsync("A deadline still applies after SSE headers arrive", async () =>
{
    var stream = new FinalWithoutEofStream(Encoding.UTF8.GetBytes(": waiting\n\n"));
    var handler = new FixtureHandler((_, _) => Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
        { Content = new StreamContent(stream) { Headers = { ContentType = new MediaTypeHeaderValue("text/event-stream") } } }));
    using var api = new OpenAiImagesClient(handler, TimeSpan.FromMilliseconds(100));
    var error = await ThrowsAsync<ImageApiException>(() => api.EditAsync(input, key));
    True(error.Message.Contains("REQUEST_DEADLINE")); True(stream.Disposed); Equal(1, handler.Calls);
});
await checks.TestAsync("The request body cannot be serialized a second time", async () =>
{
    using var first = new MemoryStream(); using var second = new MemoryStream();
    var handler = new FixtureHandler(async (request, token) =>
    {
        await request.Content!.CopyToAsync(first, token);
        await request.Content.CopyToAsync(second, token);
        return Sse(finalEvent);
    });
    using var api = new OpenAiImagesClient(handler);
    await ThrowsAsync<ImageApiException>(() => api.EditAsync(input, key));
    True(first.Length > 0); Equal(0L, second.Length); Equal(1, handler.Calls);
});
await checks.TestAsync("Oversized responses are rejected before reading their bodies", async () =>
{
    var response = Sse(""); response.Content.Headers.ContentLength = 96L * 1024 * 1024 + 1;
    using var api = new OpenAiImagesClient(new FixtureHandler((_, _) => Task.FromResult(response)));
    var error = await ThrowsAsync<ImageApiException>(() => api.EditAsync(input, key));
    True(error.Message.Contains("RESPONSE_SIZE"));
});
Array.Clear(key);
return checks.Finish();

static HttpResponseMessage Sse(string text) => new(HttpStatusCode.OK) { Content = new StringContent(text, Encoding.UTF8, "text/event-stream") };

sealed class FixtureHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> respond) : HttpMessageHandler
{
    public int Calls { get; private set; }
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    { Calls++; return respond(request, cancellationToken); }
}

sealed class FinalWithoutEofStream(byte[] bytes) : Stream
{
    private int position;
    public bool Disposed { get; private set; }
    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellation = default)
    {
        if (position == bytes.Length) { await Task.Delay(Timeout.Infinite, cancellation); return 0; }
        int count = Math.Min(buffer.Length, bytes.Length - position); bytes.AsMemory(position, count).CopyTo(buffer); position += count; return count;
    }
    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    public override bool CanRead => true; public override bool CanSeek => false; public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => position; set => throw new NotSupportedException(); }
    public override void Flush() => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    protected override void Dispose(bool disposing) { Disposed = true; base.Dispose(disposing); }
}
