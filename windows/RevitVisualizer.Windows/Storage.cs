using System.IO;
using System.Security.Cryptography;
using System.Text;
using RevitVisualizer.Core;

namespace RevitVisualizer.Windows;

public sealed class CredentialStore(string root)
{
    private readonly string path = Path.Combine(root, "openai-key.dpapi");
    private static readonly byte[] entropy = Encoding.UTF8.GetBytes("Revit AI Visualizer / Windows credential / v1");
    public bool Exists => File.Exists(path);

    public char[]? Load()
    {
        if (!Exists) return null;
        if (new FileInfo(path).Length > 32 * 1024) throw new InvalidDataException("The saved API key could not be unlocked. Clear it and enter it again.");
        byte[]? plaintext = null;
        try
        {
            plaintext = ProtectedData.Unprotect(File.ReadAllBytes(path), entropy, DataProtectionScope.CurrentUser);
            char[] value = Encoding.UTF8.GetChars(plaintext);
            if (OpenAiImagesClient.IsValidCredential(value)) return value;
            Array.Clear(value);
            throw new CryptographicException();
        }
        catch (Exception error) when (error is CryptographicException or IOException or UnauthorizedAccessException)
        { throw new InvalidDataException("The saved API key could not be unlocked. Clear it and enter it again."); }
        finally { if (plaintext is not null) CryptographicOperations.ZeroMemory(plaintext); }
    }

    public void Save(char[] value)
    {
        if (!OpenAiImagesClient.IsValidCredential(value)) throw new ArgumentException("Enter a valid API key without spaces or line breaks.");
        byte[] plaintext = Encoding.UTF8.GetBytes(value);
        try
        {
            byte[] encrypted = ProtectedData.Protect(plaintext, entropy, DataProtectionScope.CurrentUser);
            SessionFiles.WriteExact(path, encrypted);
        }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    }

    public void Clear() { if (Exists) File.Delete(path); }
}

public sealed class SessionFiles : IDisposable
{
    private readonly FileStream sessionLock;
    public static string DefaultRoot => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Revit AI Visualizer");
    public string DirectoryPath { get; }
    public SessionFiles(string root)
    {
        string sessions = Path.Combine(root, "Sessions");
        Directory.CreateDirectory(sessions);
        foreach (string old in Directory.GetDirectories(sessions))
        {
            string marker = Path.Combine(old, "session.lock");
            if (!File.Exists(marker)) continue;
            try
            {
                using (new FileStream(marker, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) { }
                Directory.Delete(old, recursive: true);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
        }
        DirectoryPath = Path.Combine(sessions, Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(DirectoryPath);
        sessionLock = new FileStream(Path.Combine(DirectoryPath, "session.lock"), FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None);
    }
    public string NewPath(string name) => Path.Combine(DirectoryPath, $"{name}-{Guid.NewGuid():N}.png");
    public static void WriteExact(string path, byte[] bytes)
    {
        string full = Path.GetFullPath(path);
        Directory.CreateDirectory(Path.GetDirectoryName(full)!);
        string temporary = full + $".{Guid.NewGuid():N}.tmp";
        try
        {
            using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            { output.Write(bytes); output.Flush(flushToDisk: true); }
            File.Move(temporary, full, overwrite: true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
    public void Dispose()
    {
        sessionLock.Dispose();
        try { if (Directory.Exists(DirectoryPath)) Directory.Delete(DirectoryPath, recursive: true); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }
}
