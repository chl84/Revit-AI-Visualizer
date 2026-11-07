using System.Buffers.Binary;
using System.IO;
using System.IO.Compression;
using System.Text;

namespace RevitVisualizer.Tests;

public sealed class Checks
{
    private int passed, failed;
    public void Test(string name, Action test) => TestAsync(name, () => { test(); return Task.CompletedTask; }).GetAwaiter().GetResult();
    public async Task TestAsync(string name, Func<Task> test)
    {
        try { await test(); Console.WriteLine($"PASS {name}"); passed++; }
        catch (Exception error) { Console.Error.WriteLine($"FAIL {name}: {error.GetType().Name}: {error.Message}"); failed++; }
    }
    public int Finish() { Console.WriteLine($"{passed} passed; {failed} failed."); return failed == 0 ? 0 : 1; }
    public static void True(bool condition, string message = "Assertion failed") { if (!condition) throw new Exception(message); }
    public static void Equal<T>(T expected, T actual) { if (!EqualityComparer<T>.Default.Equals(expected, actual)) throw new Exception($"Expected {expected}; got {actual}."); }
    public static void Bytes(byte[] expected, byte[] actual) => True(expected.AsSpan().SequenceEqual(actual), "Byte sequences differ.");
    public static T Throws<T>(Action action) where T : Exception
    {
        try { action(); } catch (T error) { return error; }
        throw new Exception($"Expected {typeof(T).Name}.");
    }
    public static async Task<T> ThrowsAsync<T>(Func<Task> action) where T : Exception
    {
        try { await action(); } catch (T error) { return error; }
        throw new Exception($"Expected {typeof(T).Name}.");
    }
}

public sealed class TemporaryDirectory : IDisposable
{
    public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), $"revit-visualizer-test-{Guid.NewGuid():N}");
    public TemporaryDirectory() => Directory.CreateDirectory(Path);
    public string File(string name) => System.IO.Path.Combine(Path, name);
    public void Dispose() => Directory.Delete(Path, recursive: true);
}

public static class Fixture
{
    public static byte[] Png(int width = 64, int height = 48)
    {
        using var result = new MemoryStream();
        result.Write(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 });
        var header = new byte[13];
        BinaryPrimitives.WriteInt32BigEndian(header, width); BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(4), height);
        header[8] = 8; header[9] = 6;
        Chunk(result, "IHDR", header);
        using var compressed = new MemoryStream();
        using (var zip = new ZLibStream(compressed, CompressionLevel.SmallestSize, leaveOpen: true))
        {
            for (int y = 0; y < height; y++)
            {
                zip.WriteByte(0);
                for (int x = 0; x < width; x++) zip.Write(new byte[] { (byte)(x % 256), (byte)(y % 256), 100, 255 });
            }
        }
        Chunk(result, "IDAT", compressed.ToArray()); Chunk(result, "IEND", []);
        return result.ToArray();
    }
    private static void Chunk(Stream output, string type, byte[] data)
    {
        var length = new byte[4]; BinaryPrimitives.WriteInt32BigEndian(length, data.Length); output.Write(length);
        byte[] kind = Encoding.ASCII.GetBytes(type); output.Write(kind); output.Write(data);
        uint crc = uint.MaxValue;
        foreach (byte value in kind.Concat(data))
        {
            crc ^= value;
            for (int bit = 0; bit < 8; bit++) crc = (crc >> 1) ^ ((crc & 1) != 0 ? 0xEDB88320u : 0);
        }
        BinaryPrimitives.WriteUInt32BigEndian(length, ~crc); output.Write(length);
    }
}
