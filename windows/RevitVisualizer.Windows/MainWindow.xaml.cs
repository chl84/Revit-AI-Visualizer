using System.IO;
using System.Security.Cryptography;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using RevitVisualizer.Core;

namespace RevitVisualizer.Windows;

public partial class MainWindow : Window
{
    public event Action<string>? FailureReported;
    private readonly SessionFiles files;
    private readonly CredentialStore vault;
    private readonly OpenAiImagesClient api = new();
    private CancellationTokenSource? operation;
    private char[]? credential;
    private LoadedImage? source;
    private string? sourceCopy, importedPath, preparedPath;
    private PngImage? result;
    private bool busy, generating, closing;

    public MainWindow() : this(SessionFiles.DefaultRoot) { }
    public MainWindow(string dataRoot)
    {
        InitializeComponent();
        files = new(dataRoot); vault = new(dataRoot);
        Comparison.ZoomChanged += zoom => ZoomLabel.Text = $"Zoom {zoom:P0} · Scroll to zoom, drag to pan";
        Loaded += async (_, _) =>
        {
            UpdateKeyButton();
            if (!vault.Exists) return;
            await RunWork("Unlocking the saved API key…", async token =>
            {
                char[]? saved = await Task.Run(vault.Load);
                try
                {
                    token.ThrowIfCancellationRequested();
                    credential = saved; saved = null; UpdateKeyButton();
                }
                finally { if (saved is not null) Array.Clear(saved); }
            });
        };
        Closed += (_, _) =>
        {
            closing = true; operation?.Cancel();
            if (credential is not null) Array.Clear(credential);
            credential = null; api.Dispose(); files.Dispose();
        };
    }

    private void SetStage(int stage)
    {
        CapturePanel.Visibility = stage == 0 ? Visibility.Visible : Visibility.Collapsed;
        PreparePanel.Visibility = stage == 1 ? Visibility.Visible : Visibility.Collapsed;
        ResultPanel.Visibility = stage == 2 ? Visibility.Visible : Visibility.Collapsed;
        StageLabel.Text = $"{stage + 1} / 3 · {new[] { "Capture", "Prepare", "Result" }[stage]}";
    }

    private async void OpenImage_Click(object sender, RoutedEventArgs e)
    {
        var picker = new OpenFileDialog { Title = "Open a Revit viewport image", Filter = "Images|*.png;*.jpg;*.jpeg;*.bmp;*.tif;*.tiff", CheckFileExists = true };
        if (picker.ShowDialog(this) != true) return;
        await ImportImageAsync(picker.FileName);
    }

    public async Task ImportImageAsync(string path)
    {
        await RunWork("Opening the image at its original resolution…", async token =>
        {
            string copy = files.NewPath("original");
            try
            {
                var loaded = await Task.Run(() =>
                {
                    if (new FileInfo(path).Length > 100L * 1024 * 1024) throw new ArgumentException("The source image exceeds 100 MB. Import a smaller image.");
                    File.Copy(path, copy);
                    File.SetAttributes(copy, File.GetAttributes(copy) & ~FileAttributes.ReadOnly);
                    return Imaging.Load(copy);
                }, token);
                token.ThrowIfCancellationRequested();
                SetSource(loaded, copy, path);
            }
            catch { if (File.Exists(copy)) File.Delete(copy); throw; }
        });
    }

    private async void CaptureScreen_Click(object sender, RoutedEventArgs e)
    {
        if (busy) return;
        BitmapSource? selected = null;
        Hide();
        try
        {
            await Task.Delay(250);
            var clipper = new ScreenClipper();
            if (clipper.ShowDialog() == true) selected = clipper.SelectedImage;
        }
        catch { ShowFailure("Screen capture is unavailable. Open an image or paste a screen clipping instead."); }
        finally { if (!closing) { Show(); Activate(); } }
        if (selected is not null) await AcceptBitmap(selected);
    }

    private async void PasteImage_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (!Clipboard.ContainsImage()) { StatusLabel.Text = "The clipboard does not contain an image. Copy an image or a screen clipping first."; return; }
            if (Clipboard.GetImage() is { } bitmap) await AcceptBitmap(bitmap);
        }
        catch { ShowFailure("The clipboard image could not be opened. Try copying it again or use Open image."); }
    }

    private async Task AcceptBitmap(BitmapSource bitmap)
    {
        if (bitmap.CanFreeze) bitmap.Freeze();
        await RunWork("Opening the captured image…", async token =>
        {
            string path = files.NewPath("capture");
            try
            {
                var raster = bitmap.IsFrozen ? await Task.Run(() => Imaging.FromBitmap(bitmap), token) : Imaging.FromBitmap(bitmap);
                await Task.Run(() => SessionFiles.WriteExact(path, Imaging.EncodePng(raster)), token);
                token.ThrowIfCancellationRequested();
                SetSource(new(raster, true), path, null);
            }
            catch { if (File.Exists(path)) File.Delete(path); throw; }
        });
    }

    private void SetSource(LoadedImage loaded, string copy, string? originalPath)
    {
        var preview = Imaging.Preview(loaded.Raster);
        string? obsolete = sourceCopy;
        source = loaded; sourceCopy = copy; importedPath = originalPath;
        SourcePreview.Source = preview; Editor.Image = preview;
        SourcePreview.Visibility = Visibility.Visible; Welcome.Visibility = Visibility.Collapsed;
        SourceInfo.Text = $"{loaded.Raster.Width} × {loaded.Raster.Height} · Original preserved";
        PrepareButton.IsEnabled = true;
        result = null; Comparison.Original = null; Comparison.Result = null;
        Editor.Selection = Selection.FullFrame; PerspectiveToggle.IsChecked = false; AspectEntry.Clear();
        if (obsolete is not null && obsolete != copy) File.Delete(obsolete);
        if (preparedPath is not null) { File.Delete(preparedPath); preparedPath = null; }
        SetStage(0);
    }

    private void Prepare_Click(object sender, RoutedEventArgs e) { if (source is not null) SetStage(1); }
    private void BackToCapture_Click(object sender, RoutedEventArgs e) => SetStage(0);
    private void BackToPrepare_Click(object sender, RoutedEventArgs e) => SetStage(1);
    private void Perspective_Changed(object sender, RoutedEventArgs e)
    {
        if (Editor is null || AspectPanel is null) return;
        Editor.Perspective = PerspectiveToggle.IsChecked == true;
        Editor.Selection = Selection.FullFrame; AspectEntry.Clear();
        AspectPanel.Visibility = Editor.Perspective ? Visibility.Visible : Visibility.Collapsed;
    }
    private void ResetSelection_Click(object sender, RoutedEventArgs e) { Editor.Selection = Selection.FullFrame; AspectEntry.Clear(); }

    private async void Visualize_Click(object sender, RoutedEventArgs e)
    {
        if (busy || source is null || sourceCopy is null) return;
        double? aspect = Editor.Perspective ? Selection.ParseAspectRatio(AspectEntry.Text) : null;
        if (Editor.Perspective && !string.IsNullOrWhiteSpace(AspectEntry.Text) && aspect is null)
        { ShowFailure("Enter the viewport's true width:height ratio, for example 16:9, or leave it blank to estimate."); return; }
        if (credential is null)
        {
            var dialog = new CredentialDialog { Owner = this };
            if (dialog.ShowDialog() != true || dialog.Credential is not { } entered) return;
            try
            {
                bool accepted = await RunWork("Updating the saved API key…", async token =>
                {
                    await Task.Run(() => { if (dialog.Remember) vault.Save(entered); else vault.Clear(); });
                    token.ThrowIfCancellationRequested();
                    credential = (char[])entered.Clone(); UpdateKeyButton();
                });
                if (!accepted) return;
            }
            finally { Array.Clear(entered); }
        }
        if (credential is null) return;
        var key = (char[])credential.Clone();
        var selection = Editor.Selection;
        var original = source;
        string originalCopy = sourceCopy;
        string input = files.NewPath("prepared");
        bool finished = false;
        try
        {
            finished = await RunWork("Preparing the full-resolution image…", async token =>
            {
                var prepared = await Task.Run(() =>
                {
                    if (selection.IsFullFrame && aspect is null && original.UnchangedPng)
                    {
                        File.Copy(originalCopy, input);
                        return original.Raster;
                    }
                    var raster = original.Raster.Prepare(selection, aspect, token);
                    SessionFiles.WriteExact(input, Imaging.EncodePng(raster));
                    return raster;
                }, token);
                var received = await api.EditAsync(input, key, new Progress<string>(label => { if (!closing) StatusLabel.Text = label; }), token);
                var finalPreview = await Task.Run(() => Imaging.DecodePreview(received.Bytes), token);
                token.ThrowIfCancellationRequested();
                if (preparedPath is not null) File.Delete(preparedPath);
                preparedPath = input; result = received;
                Comparison.Original = Imaging.Preview(prepared); Comparison.Result = finalPreview;
                ResultMode.IsChecked = true; Comparison.Mode = 1; Comparison.ResetView();
                bool differentRatio = Math.Abs((double)prepared.Width / prepared.Height - (double)received.Width / received.Height) > 0.001;
                ResultInfo.Text = $"PNG · {received.Width} × {received.Height}. Original shows the prepared AI input." +
                    (differentRatio ? " Image ratios differ; centered fit preserves proportions." : "");
                SetStage(2);
            }, isGeneration: true);
        }
        finally { Array.Clear(key); if (!finished && File.Exists(input)) File.Delete(input); }
    }

    private async void ClearKey_Click(object sender, RoutedEventArgs e)
    {
        if (credential is not null) Array.Clear(credential);
        credential = null;
        await RunWork("Removing the saved API key…", _ => Task.Run(vault.Clear));
        UpdateKeyButton();
    }
    private void UpdateKeyButton() => ClearKeyButton.Visibility = credential is not null || vault.Exists ? Visibility.Visible : Visibility.Collapsed;
    private void Cancel_Click(object sender, RoutedEventArgs e) => operation?.Cancel();

    private void CompareMode_Changed(object sender, RoutedEventArgs e)
    {
        if (Comparison is null || OpacitySlider is null) return;
        Comparison.Mode = OriginalMode.IsChecked == true ? 0 : OverlayMode.IsChecked == true ? 2 : 1;
        OpacitySlider.IsEnabled = Comparison.Mode == 2;
    }
    private void Opacity_Changed(object sender, RoutedPropertyChangedEventArgs<double> e) { if (Comparison is not null) Comparison.OriginalOpacity = e.NewValue / 100; }
    private void ResetView_Click(object sender, RoutedEventArgs e) => Comparison.ResetView();

    private async void Save_Click(object sender, RoutedEventArgs e)
    {
        if (result is null) return;
        var picker = new SaveFileDialog { Title = "Save the returned PNG", Filter = "PNG image|*.png", DefaultExt = ".png", AddExtension = true,
            OverwritePrompt = true, FileName = $"Vizruna-{DateTime.Now:yyyyMMdd-HHmmss}.png" };
        if (picker.ShowDialog(this) != true) return;
        string full = Path.GetFullPath(picker.FileName);
        if (!full.EndsWith(".png", StringComparison.OrdinalIgnoreCase)) { ShowFailure("Choose a file name ending in .png."); return; }
        if (new[] { importedPath, sourceCopy, preparedPath }.Any(path => path is not null && string.Equals(Path.GetFullPath(path), full, StringComparison.OrdinalIgnoreCase)))
        { ShowFailure("Choose another file name. The source image must not be overwritten."); return; }
        byte[] png = result.Bytes;
        if (await RunWork("Saving the exact returned PNG…", _ => Task.Run(() => SessionFiles.WriteExact(full, png)))) StatusLabel.Text = "PNG saved at its original resolution.";
    }
    private void NewImage_Click(object sender, RoutedEventArgs e)
    {
        if (sourceCopy is not null) File.Delete(sourceCopy);
        if (preparedPath is not null) File.Delete(preparedPath);
        source = null; sourceCopy = importedPath = preparedPath = null; result = null;
        SourcePreview.Source = null; Editor.Image = null; Comparison.Original = Comparison.Result = null;
        Welcome.Visibility = Visibility.Visible; SourcePreview.Visibility = Visibility.Collapsed; PrepareButton.IsEnabled = false;
        SourceInfo.Text = "Your original image is preserved."; SetStage(0);
    }

    private async Task<bool> RunWork(string label, Func<CancellationToken, Task> action, bool isGeneration = false)
    {
        if (busy || closing) return false;
        busy = true; generating = isGeneration; Workspace.IsEnabled = ClearKeyButton.IsEnabled = false;
        StatusLabel.Text = label; BusyProgress.Visibility = Visibility.Visible;
        CancelButton.Visibility = generating ? Visibility.Visible : Visibility.Collapsed;
        using var cancellation = new CancellationTokenSource(); operation = cancellation;
        try
        {
            await action(cancellation.Token);
            if (closing) return false;
            StatusLabel.Text = "Ready";
            return true;
        }
        catch (OperationCanceledException) { if (!closing) StatusLabel.Text = generating ? "Cancelled locally. OpenAI may still finish and bill a submitted request." : "Cancelled."; }
        catch (Exception error)
        {
            if (!closing)
            {
                StatusLabel.Text = "The action could not be completed.";
                string text = error switch
                {
                    ImageApiException => error.Message,
                    OutOfMemoryException => "There is not enough memory to process this image. Import a smaller image. The original is unchanged.",
                    ArgumentException when error.Message.StartsWith("Select ", StringComparison.Ordinal) || error.Message.StartsWith("This image ", StringComparison.Ordinal) ||
                        error.Message.StartsWith("The source image ", StringComparison.Ordinal) || error.Message.StartsWith("Enter ", StringComparison.Ordinal) => error.Message,
                    InvalidDataException when error.Message == "The saved API key could not be unlocked. Clear it and enter it again." => error.Message,
                    CryptographicException => "The API key could not be remembered for this Windows account. Try again with Remember on this PC off.",
                    _ => "The image or local data could not be read, processed, or saved. Check available storage and try a PNG or JPEG image."
                };
                ShowFailure(text);
            }
        }
        finally
        {
            operation = null; busy = generating = false;
            if (!closing)
            {
                Workspace.IsEnabled = ClearKeyButton.IsEnabled = true;
                BusyProgress.Visibility = CancelButton.Visibility = Visibility.Collapsed;
                UpdateKeyButton();
            }
        }
        return false;
    }

    private void ShowFailure(string message)
    {
        if (closing) return;
        if (FailureReported is { } presenter) { presenter(message); return; }
        var dialog = new Window { Owner = IsVisible ? this : null, Title = "Could not complete action", Width = 580, Height = 410,
            MinWidth = 420, MinHeight = 260, WindowStartupLocation = IsVisible ? WindowStartupLocation.CenterOwner : WindowStartupLocation.CenterScreen };
        var layout = new DockPanel { Margin = new Thickness(24) };
        var close = new Button { Content = "Close", IsCancel = true, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 12, 0, 0) };
        close.Click += (_, _) => dialog.Close(); DockPanel.SetDock(close, Dock.Bottom); layout.Children.Add(close);
        layout.Children.Add(new TextBox { Text = message, IsReadOnly = true, TextWrapping = TextWrapping.Wrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, BorderThickness = new Thickness(0), Background = System.Windows.Media.Brushes.Transparent });
        dialog.Content = layout; dialog.ShowDialog();
    }
}
