using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Authentication;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace RevitVisualizer.Core;

public sealed class ImageApiException(string message) : Exception(message);

public sealed class OpenAiImagesClient : IDisposable
{
    public const string Model = "gpt-image-2.6-sunburst";
    public const string Endpoint = "https://api.openai.com/v1/images/edits";
    public static string Prompt { get; } = ReadPrompt();
    private readonly HttpClient client;
    private readonly TimeSpan deadline;

    public OpenAiImagesClient(HttpMessageHandler? handler = null, TimeSpan? requestDeadline = null)
    {
        client = new HttpClient(handler ?? new SocketsHttpHandler
        {
            AllowAutoRedirect = false, UseCookies = false, ConnectTimeout = TimeSpan.FromSeconds(30),
            AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate,
            KeepAlivePingDelay = TimeSpan.FromSeconds(20), KeepAlivePingTimeout = TimeSpan.FromSeconds(30),
            KeepAlivePingPolicy = HttpKeepAlivePingPolicy.Always, MaxResponseHeadersLength = 32
        }) { Timeout = Timeout.InfiniteTimeSpan };
        deadline = requestDeadline ?? TimeSpan.FromMinutes(20);
    }

    private static string ReadPrompt()
    {
        using var stream = typeof(OpenAiImagesClient).Assembly.GetManifestResourceStream("RevitVisualizer.Core.ArchitecturePrompt.txt")!;
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd().Trim().Replace("\r\n", "\n");
    }

    public static bool IsValidCredential(ReadOnlySpan<char> value)
    {
        if (value.Length is < 1 or > 4096) return false;
        foreach (char character in value) if (character is < (char)33 or > (char)126) return false;
        return true;
    }

    public async Task<PngImage> EditAsync(string inputPng, char[] credential, IProgress<string>? progress = null, CancellationToken cancellation = default)
    {
        if (!IsValidCredential(credential)) throw new ArgumentException("Enter a valid API key without spaces or line breaks.");
        if (new FileInfo(inputPng).Length >= 50_000_000) throw new ArgumentException("The prepared PNG exceeds the API's 50 MB input limit. Select a smaller area.");
        var trace = new RequestTrace(progress);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(deadline);
        using var input = File.OpenRead(inputPng);
        using var multipart = new MultipartFormDataContent();
        foreach (var (name, value) in new[] { ("model", Model), ("prompt", Prompt), ("n", "1"), ("partial_images", "0"),
                     ("stream", "true"), ("quality", "max"), ("size", "auto"), ("output_format", "png") })
            multipart.Add(new StringContent(value), name);
        var image = new StreamContent(input);
        image.Headers.ContentType = new("image/png");
        multipart.Add(image, "image[]", "revit-view.png");
        using var request = new HttpRequestMessage(HttpMethod.Post, Endpoint)
        {
            Version = HttpVersion.Version20, VersionPolicy = HttpVersionPolicy.RequestVersionOrLower,
            Content = new OneShotContent(multipart, trace)
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", new string(credential));
        request.Headers.Accept.Add(new("text/event-stream"));
        request.Headers.Add("X-Client-Request-Id", trace.ClientId);
        trace.Phase("Connecting securely to OpenAI…", "connecting");
        try
        {
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
            trace.Status = (int)response.StatusCode;
            trace.Version = response.Version.ToString();
            if (response.Headers.TryGetValues("x-request-id", out var ids))
            {
                string? id = ids.FirstOrDefault();
                if (id is not null && Regex.IsMatch(id, "^req_[A-Za-z0-9_-]{1,96}$")) trace.ServerId = id;
            }
            if (!response.IsSuccessStatusCode) throw trace.Error($"HTTP_{trace.Status}", StatusMessage(trace.Status.Value));
            if (response.Content.Headers.ContentLength > BoundedReadStream.Limit) throw trace.Error("RESPONSE_SIZE", "The image response exceeds the safe size limit.");
            await using var body = new BoundedReadStream(await response.Content.ReadAsStreamAsync(timeout.Token).ConfigureAwait(false), trace);
            string? media = response.Content.Headers.ContentType?.MediaType;
            if (media == "application/json")
            {
                trace.Phase("Receiving the final PNG…", "receiving the image");
                using var json = await JsonDocument.ParseAsync(body, cancellationToken: timeout.Token).ConfigureAwait(false);
                var images = json.RootElement.GetProperty("data");
                if (images.GetArrayLength() != 1) throw new InvalidDataException();
                return Decode(images[0]);
            }
            if (media != "text/event-stream") throw trace.Error("RESPONSE_FORMAT", "OpenAI returned an unexpected response format.");
            trace.Phase("Waiting for the final image from OpenAI…", "waiting for OpenAI");
            using var reader = new StreamReader(body, Encoding.UTF8, false, 8192, leaveOpen: true);
            var data = new StringBuilder();
            string? type = null;
            while (await reader.ReadLineAsync(timeout.Token).ConfigureAwait(false) is { } line)
            {
                if (line.Length == 0)
                {
                    if (ReadEvent(type, data.ToString(), trace) is { } result) return result;
                    type = null; data.Clear();
                }
                else if (line.StartsWith("event:", StringComparison.Ordinal)) type = line[6..].TrimStart(' ');
                else if (line.StartsWith("data:", StringComparison.Ordinal))
                {
                    if (data.Length > 0) data.Append('\n');
                    data.Append(line[5..].TrimStart(' '));
                    if (data.Length > BoundedReadStream.Limit) throw trace.Error("RESPONSE_SIZE", "The image response exceeds the safe size limit.");
                }
            }
            if (ReadEvent(type, data.ToString(), trace) is { } final) return final;
            throw trace.Error("INCOMPLETE_STREAM", "The connection closed before a complete image arrived.");
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { throw; }
        catch (OperationCanceledException) { throw trace.Error(timeout.IsCancellationRequested ? "REQUEST_DEADLINE" : "TRANSFER_TIMEOUT", "The request exceeded a connection, upload, or overall waiting limit."); }
        catch (ImageApiException) { throw; }
        catch (AuthenticationException) { throw trace.Error("TLS", "A secure connection to OpenAI could not be established."); }
        catch (HttpRequestException error)
        {
            string code = error.HttpRequestError switch
            {
                HttpRequestError.NameResolutionError => "DNS", HttpRequestError.SecureConnectionError => "TLS",
                HttpRequestError.HttpProtocolError => "HTTP_PROTOCOL", HttpRequestError.ResponseEnded => "RESPONSE_EOF", _ => "CONNECTION_LOST"
            };
            throw trace.Error(code, "The connection to OpenAI was interrupted before the final image arrived.");
        }
        catch (ResponseLimitException) { throw trace.Error("RESPONSE_SIZE", "The image response exceeds the safe size limit."); }
        catch (InvalidDataException) { throw trace.Error("INVALID_IMAGE", "OpenAI did not return one complete, readable PNG image."); }
        catch (IOException) { throw trace.Error("CONNECTION_LOST", "The connection ended while reading the image response."); }
        catch (Exception error) when (error is JsonException or FormatException or InvalidOperationException or KeyNotFoundException or ArgumentException)
        { throw trace.Error("INVALID_IMAGE", "OpenAI did not return one complete, readable PNG image."); }
    }

    private static PngImage? ReadEvent(string? type, string data, RequestTrace trace)
    {
        if (type == "error") throw trace.Error("STREAM_ERROR", "OpenAI stopped the image request before completion.");
        if (data.Length == 0 || type is not (null or "message" or "image_edit.completed")) return null;
        using var json = JsonDocument.Parse(data);
        var root = json.RootElement;
        string? payloadType = root.TryGetProperty("type", out var t) ? t.GetString() : null;
        if (root.TryGetProperty("error", out _) || payloadType == "error") throw trace.Error("STREAM_ERROR", "OpenAI stopped the image request before completion.");
        if (payloadType is not null && payloadType != "image_edit.completed" || payloadType is null && type != "image_edit.completed") return null;
        trace.Phase("Receiving and validating the final PNG…", "receiving the image");
        return Decode(root);
    }

    private static PngImage Decode(JsonElement item)
    {
        string encoded = item.GetProperty("b64_json").GetString() ?? throw new FormatException();
        if (encoded.Length > PngImage.MaxBytes * 4L / 3 + 4) throw new ResponseLimitException();
        return PngImage.Read(Convert.FromBase64String(encoded));
    }

    private static string StatusMessage(int status) => status switch
    {
        401 => "OpenAI rejected the API key. Clear it and enter a valid key.",
        403 or 404 => "Your OpenAI project does not have access to GPT Image 2.6 Sunburst, or the model is unavailable.",
        400 or 413 or 422 => "OpenAI rejected this image request. Check the input size and your project's model access.",
        429 => "OpenAI reported a rate or billing limit. Check your account before trying again.",
        408 or 504 => "OpenAI or an upstream gateway reported a request timeout.",
        _ => "OpenAI could not complete the image request."
    };
    public void Dispose() => client.Dispose();

    private sealed class OneShotContent : HttpContent
    {
        private readonly HttpContent inner;
        private readonly RequestTrace trace;
        private int writes;
        public OneShotContent(HttpContent inner, RequestTrace trace)
        {
            this.inner = inner; this.trace = trace;
            foreach (var header in inner.Headers) Headers.TryAddWithoutValidation(header.Key, header.Value);
        }
        protected override bool TryComputeLength(out long length) { length = inner.Headers.ContentLength ?? 0; return inner.Headers.ContentLength.HasValue; }
        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) => SerializeToStreamAsync(stream, context, CancellationToken.None);
        protected override async Task SerializeToStreamAsync(Stream stream, TransportContext? context, CancellationToken cancellation)
        {
            if (Interlocked.Increment(ref writes) != 1) throw new IOException("Request replay is disabled.");
            trace.Phase("Uploading the prepared image…", "uploading");
            using var upload = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            upload.CancelAfter(TimeSpan.FromMinutes(2));
            await inner.CopyToAsync(stream, context, upload.Token).ConfigureAwait(false);
            trace.Phase("Waiting for OpenAI to respond…", "waiting for response headers");
        }
    }

    private sealed class RequestTrace(IProgress<string>? progress)
    {
        private readonly Stopwatch elapsed = Stopwatch.StartNew();
        private string phase = "connecting";
        public string ClientId { get; } = Guid.NewGuid().ToString();
        public string? ServerId { get; set; }
        public string? Version { get; set; }
        public int? Status { get; set; }
        public long Bytes;
        public void Phase(string label, string diagnostic) { phase = diagnostic; progress?.Report(label); }
        public ImageApiException Error(string code, string description) => new($"{description}\n\nStage: {phase}. Elapsed: {(int)elapsed.Elapsed.TotalSeconds}s. Diagnostic: {code}.\n" +
            $"Response: {(Status is null ? "no response headers" : $"HTTP {Status} over {Version}")}. Read: {Bytes} bytes.\nClient request ID: {ClientId}." +
            (ServerId is null ? "" : $"\nOpenAI request ID: {ServerId}.") + "\nNo automatic retry was made. A submitted request may still finish and be billed.");
    }

    private sealed class ResponseLimitException : Exception;
    private sealed class BoundedReadStream(Stream inner, RequestTrace trace) : Stream
    {
        public const long Limit = 96L * 1024 * 1024;
        private int Count(int count)
        {
            trace.Bytes += count;
            if (trace.Bytes > Limit) throw new ResponseLimitException();
            return count;
        }
        public override int Read(byte[] buffer, int offset, int count) => Count(inner.Read(buffer, offset, count));
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellation = default) => Count(await inner.ReadAsync(buffer, cancellation).ConfigureAwait(false));
        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellation) => ReadAsync(buffer.AsMemory(offset, count), cancellation).AsTask();
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => trace.Bytes; set => throw new NotSupportedException(); }
        public override void Flush() => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        protected override void Dispose(bool disposing) { if (disposing) inner.Dispose(); base.Dispose(disposing); }
    }
}
