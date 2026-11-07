using System.IO;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using RevitVisualizer.Core;
using RevitVisualizer.Windows;
using RevitVisualizer.Tests;
using static RevitVisualizer.Tests.Checks;

internal static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        RenderOptions.ProcessRenderMode = System.Windows.Interop.RenderMode.SoftwareOnly;
        var application = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        application.Resources.MergedDictionaries.Add(new ResourceDictionary
        { Source = new Uri("pack://application:,,,/Vizruna;component/Theme.xaml", UriKind.Absolute) });
        application.DispatcherUnhandledException += (_, e) => { Console.Error.WriteLine(e.Exception.GetType().Name); e.Handled = true; application.Shutdown(1); };
        application.Startup += async (_, _) =>
        {
            int exit;
            try { exit = await Run(args.FirstOrDefault() ?? "artifacts", args.ElementAtOrDefault(1)); }
            catch (Exception error) { Console.Error.WriteLine(error); exit = 1; }
            application.Shutdown(exit);
        };
        return application.Run();
    }

    private static async Task<int> Run(string artifactFolder, string? publishedExecutable)
    {
        var checks = new Checks();
        using var files = new TemporaryDirectory();
        checks.Test("Native PNG codec preserves dimensions, orientation and source file bytes", () =>
        {
            byte[] png = Fixture.Png(128, 96); string path = files.File("source.png"); File.WriteAllBytes(path, png);
            var loaded = Imaging.Load(path); Equal(128, loaded.Raster.Width); Equal(96, loaded.Raster.Height); True(loaded.UnchangedPng);
            var warped = loaded.Raster.Prepare(new Selection([new(0.1, 0.1), new(0.9, 0.05), new(0.95, 0.9), new(0.15, 0.95)]), 16d / 9);
            var output = PngImage.Read(Imaging.EncodePng(warped)); Equal(warped.Width, output.Width); Equal(warped.Height, output.Height);
            string result = files.File("output.png"); File.WriteAllBytes(result, output.Bytes);
            var decoded = Imaging.Load(result); Bytes(warped.Pixels, decoded.Raster.Pixels); Bytes(png, File.ReadAllBytes(path));
        });
        checks.Test("DPAPI encrypts, restores, overwrites and clears the remembered credential", () =>
        {
            string root = files.File("credentials"); var vault = new CredentialStore(root); char[] key = "local-test-placeholder".ToCharArray();
            try
            {
                True(vault.Load() is null); vault.Save(key); True(vault.Exists);
                byte[] encrypted = File.ReadAllBytes(Path.Combine(root, "openai-key.dpapi"));
                True(!System.Text.Encoding.UTF8.GetString(encrypted).Contains(new string(key)));
                char[] restored = new CredentialStore(root).Load()!;
                try { True(key.SequenceEqual(restored)); } finally { Array.Clear(restored); }
                vault.Save("different-local-placeholder".ToCharArray());
                var changed = vault.Load()!; try { Equal("different-local-placeholder", new string(changed)); } finally { Array.Clear(changed); }
                vault.Clear(); True(!vault.Exists); True(vault.Load() is null);
            }
            finally { Array.Clear(key); vault.Clear(); }
        });
        checks.Test("Tampered DPAPI data fails without exposing a credential or path", () =>
        {
            string root = files.File("tampered"); var vault = new CredentialStore(root); vault.Save("local-test-placeholder".ToCharArray());
            string path = Path.Combine(root, "openai-key.dpapi"); byte[] bytes = File.ReadAllBytes(path); bytes[bytes.Length / 2] ^= 0x20; File.WriteAllBytes(path, bytes);
            var error = Throws<InvalidDataException>(() => vault.Load());
            True(!error.Message.Contains("local-test-placeholder") && !error.Message.Contains(root)); vault.Clear();
        });
        checks.Test("Atomic PNG export preserves the exact response and leaves no temporary files", () =>
        {
            byte[] png = Fixture.Png(); string path = files.File("saved.png");
            SessionFiles.WriteExact(path, png); Bytes(png, File.ReadAllBytes(path));
            SessionFiles.WriteExact(path, png); Bytes(png, File.ReadAllBytes(path));
            True(!Directory.GetFiles(files.Path, "*.tmp").Any());
        });
        checks.Test("Session cleanup preserves active sessions and the remembered credential", () =>
        {
            string root = files.File("sessions"); var vault = new CredentialStore(root); vault.Save("local-test-placeholder".ToCharArray());
            using (var first = new SessionFiles(root))
            {
                string original = first.NewPath("original"); File.WriteAllBytes(original, Fixture.Png());
                using (var second = new SessionFiles(root)) True(File.Exists(original));
                True(File.Exists(original));
            }
            True(vault.Exists); vault.Clear();
        });
        checks.Test("Comparison renders original, result and both opacity endpoints without stretching", () =>
        {
            var original = Solid(64, 48, 255, 0, 0); var result = Solid(48, 64, 0, 0, 255);
            var viewer = new ComparisonViewer { Original = original, Result = result };
            viewer.Measure(new(320, 240)); viewer.Arrange(new Rect(0, 0, 320, 240)); viewer.UpdateLayout();
            viewer.Mode = 0; Bytes([255, 0, 0, 255], Center(viewer));
            viewer.Mode = 1; Bytes([0, 0, 255, 255], Center(viewer));
            viewer.Mode = 2; viewer.OriginalOpacity = 1; Bytes([255, 0, 0, 255], Center(viewer));
            viewer.OriginalOpacity = 0; Bytes([0, 0, 255, 255], Center(viewer));
            viewer.RaiseEvent(new MouseWheelEventArgs(Mouse.PrimaryDevice, Environment.TickCount, 120) { RoutedEvent = Mouse.MouseWheelEvent });
            True(viewer.Zoom > 1); viewer.ResetView(); Equal(1d, viewer.Zoom);
            var fit = ControlImages.Fit(result, 320, 240); Equal(180d, fit.Width); Equal(240d, fit.Height);
        });
        await checks.TestAsync("Real image import opens the WPF preparation layout at normal and minimum sizes", async () =>
        {
            byte[] source = Fixture.Png(640, 480); string path = files.File("viewport.png"); File.WriteAllBytes(path, source);
            File.SetAttributes(path, FileAttributes.ReadOnly);
            var window = new MainWindow(files.File("window-data"));
            var errors = new List<string>(); window.FailureReported += errors.Add;
            try
            {
                window.Show(); await window.ImportImageAsync(path);
                True(errors.Count == 0, errors.FirstOrDefault() ?? "Image import failed.");
                True(((Image)window.FindName("SourcePreview")).Source is not null);
                var prepare = (Button)window.FindName("PrepareButton"); True(prepare.IsEnabled); prepare.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                var panel = (Grid)window.FindName("PreparePanel"); True(panel.IsVisible);
                var editor = (CropEditor)window.FindName("Editor"); True(editor.Selection.IsValid);
                foreach (var size in new[] { (1180d, 800d), (860d, 620d) })
                {
                    window.Width = size.Item1; window.Height = size.Item2; window.UpdateLayout();
                    True(editor.ActualWidth > 250 && editor.ActualHeight > 250, "Crop area collapsed.");
                }
                window.Width = 1180; window.Height = 800;
                ((CheckBox)window.FindName("PerspectiveToggle")).IsChecked = true;
                ((TextBox)window.FindName("AspectEntry")).Text = "16:9";
                window.UpdateLayout(); True(((StackPanel)window.FindName("AspectPanel")).IsVisible);
                Directory.CreateDirectory(artifactFolder);
                var screenshot = new RenderTargetBitmap((int)window.ActualWidth, (int)window.ActualHeight, 96, 96, PixelFormats.Pbgra32);
                screenshot.Render(window); var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(screenshot));
                using (var output = File.Create(Path.Combine(artifactFolder, "windows-prepare-smoke.png"))) encoder.Save(output);
                Bytes(source, File.ReadAllBytes(path));
            }
            finally { window.Close(); File.SetAttributes(path, FileAttributes.Normal); }
        });
        if (publishedExecutable is not null) await checks.TestAsync("The self-contained published executable opens a real Windows window", async () =>
        {
            using var process = Process.Start(new ProcessStartInfo(Path.GetFullPath(publishedExecutable)) { UseShellExecute = false })
                ?? throw new Exception("The published executable did not start.");
            try
            {
                var timeout = Stopwatch.StartNew();
                while (timeout.Elapsed < TimeSpan.FromSeconds(45))
                {
                    process.Refresh();
                    if (process.HasExited) throw new Exception($"Published executable exited before opening a window (code {process.ExitCode}).");
                    if (process.MainWindowHandle != 0 && process.MainWindowTitle == "Vizruna") break;
                    await Task.Delay(200);
                }
                process.Refresh(); True(process.MainWindowHandle != 0 && process.MainWindowTitle == "Vizruna", "Published window did not appear.");
                True(process.CloseMainWindow());
                using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                await process.WaitForExitAsync(stop.Token); Equal(0, process.ExitCode);
            }
            finally { if (!process.HasExited) process.Kill(entireProcessTree: true); }
        });
        return checks.Finish();
    }

    private static BitmapSource Solid(int width, int height, byte b, byte g, byte r)
    {
        var pixels = new byte[width * height * 4];
        for (int i = 0; i < pixels.Length; i += 4) { pixels[i] = b; pixels[i + 1] = g; pixels[i + 2] = r; pixels[i + 3] = 255; }
        return Imaging.Preview(new(width, height, pixels));
    }
    private static byte[] Center(FrameworkElement viewer)
    {
        viewer.InvalidateVisual(); viewer.UpdateLayout();
        var rendered = new RenderTargetBitmap(320, 240, 96, 96, PixelFormats.Pbgra32); rendered.Render(viewer);
        var pixel = new byte[4]; rendered.CopyPixels(new Int32Rect(160, 120, 1, 1), pixel, 4, 0); return pixel;
    }
}
