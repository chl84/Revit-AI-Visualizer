using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace RevitVisualizer.Windows;

/// <summary>Retains only the selected screen pixels. The full desktop capture stays in memory.</summary>
public sealed class ScreenClipper : Window
{
    private readonly BitmapSource desktop;
    private readonly int screenLeft, screenTop, screenWidth, screenHeight;
    private Point? start;
    private Point end;
    public BitmapSource? SelectedImage { get; private set; }

    public ScreenClipper()
    {
        screenLeft = GetSystemMetrics(76); screenTop = GetSystemMetrics(77);
        screenWidth = GetSystemMetrics(78); screenHeight = GetSystemMetrics(79);
        RevitVisualizer.Core.RasterImage.ValidateSize(screenWidth, screenHeight);
        nint source = GetDC(0), destination = 0, bitmap = 0, previous = 0;
        try
        {
            destination = CreateCompatibleDC(source); bitmap = CreateCompatibleBitmap(source, screenWidth, screenHeight);
            if (source == 0 || destination == 0 || bitmap == 0) throw new InvalidOperationException("Screen capture is unavailable.");
            previous = SelectObject(destination, bitmap);
            if (!BitBlt(destination, 0, 0, screenWidth, screenHeight, source, screenLeft, screenTop, 0x40CC0020))
                throw new InvalidOperationException("Screen capture is unavailable.");
            desktop = System.Windows.Interop.Imaging.CreateBitmapSourceFromHBitmap(bitmap, 0, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions());
            desktop.Freeze();
        }
        finally
        {
            if (previous != 0 && destination != 0) SelectObject(destination, previous);
            if (bitmap != 0) DeleteObject(bitmap);
            if (destination != 0) DeleteDC(destination);
            if (source != 0) ReleaseDC(0, source);
        }
        WindowStyle = WindowStyle.None; ResizeMode = ResizeMode.NoResize; ShowInTaskbar = false; Topmost = true;
        Cursor = Cursors.Cross; Background = Brushes.Black;
        SourceInitialized += (_, _) => SetWindowPos(new WindowInteropHelper(this).Handle, new nint(-1), screenLeft, screenTop, screenWidth, screenHeight, 0x0040);
        Loaded += (_, _) => SetWindowPos(new WindowInteropHelper(this).Handle, new nint(-1), screenLeft, screenTop, screenWidth, screenHeight, 0x0040);
        KeyDown += (_, e) => { if (e.Key == Key.Escape) DialogResult = false; };
    }

    protected override void OnRender(DrawingContext drawing)
    {
        base.OnRender(drawing);
        var bounds = new Rect(0, 0, ActualWidth, ActualHeight);
        drawing.DrawImage(desktop, bounds);
        Rect selected = start is null ? Rect.Empty : DisplayRect(PhysicalRect());
        var shade = new SolidColorBrush(Color.FromArgb(140, 0, 0, 0));
        if (selected.IsEmpty) drawing.DrawRectangle(shade, null, bounds);
        else
        {
            drawing.DrawGeometry(shade, null, new CombinedGeometry(GeometryCombineMode.Exclude, new RectangleGeometry(bounds), new RectangleGeometry(selected)));
            drawing.DrawRectangle(null, new Pen(new SolidColorBrush(Color.FromRgb(194, 221, 203)), 2), selected);
        }
        var label = new FormattedText("Drag around the Revit viewport · Esc to cancel", System.Globalization.CultureInfo.InvariantCulture,
            FlowDirection.LeftToRight, new Typeface("Segoe UI"), 18, Brushes.White, VisualTreeHelper.GetDpi(this).PixelsPerDip);
        drawing.DrawRectangle(new SolidColorBrush(Color.FromArgb(220, 16, 20, 22)), null, new(20, 20, label.Width + 32, label.Height + 24));
        drawing.DrawText(label, new(36, 32));
    }

    private Point CursorPosition()
    {
        if (!GetCursorPos(out var point)) throw new InvalidOperationException("The screen selection could not be read.");
        return new(Math.Clamp(point.X - screenLeft, 0, screenWidth), Math.Clamp(point.Y - screenTop, 0, screenHeight));
    }
    private Rect PhysicalRect() => start is null ? Rect.Empty : new(start.Value, end);
    private Rect DisplayRect(Rect physical) => new(physical.X * ActualWidth / screenWidth, physical.Y * ActualHeight / screenHeight,
        physical.Width * ActualWidth / screenWidth, physical.Height * ActualHeight / screenHeight);
    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e) { start = end = CursorPosition(); CaptureMouse(); InvalidateVisual(); }
    protected override void OnMouseMove(MouseEventArgs e) { if (start is not null) { end = CursorPosition(); InvalidateVisual(); } }
    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        if (start is null) return;
        end = CursorPosition(); ReleaseMouseCapture(); var rect = PhysicalRect();
        if (rect.Width < 32 || rect.Height < 32) { start = null; InvalidateVisual(); return; }
        var pixels = new Int32Rect((int)rect.X, (int)rect.Y, (int)rect.Width, (int)rect.Height);
        SelectedImage = new CroppedBitmap(desktop, pixels); SelectedImage.Freeze(); DialogResult = true;
    }

    [StructLayout(LayoutKind.Sequential)] private struct NativePoint { public int X; public int Y; }
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out NativePoint point);
    [DllImport("user32.dll")] private static extern nint GetDC(nint window);
    [DllImport("user32.dll")] private static extern int ReleaseDC(nint window, nint dc);
    [DllImport("user32.dll")] private static extern bool SetWindowPos(nint window, nint after, int x, int y, int width, int height, uint flags);
    [DllImport("gdi32.dll")] private static extern nint CreateCompatibleDC(nint dc);
    [DllImport("gdi32.dll")] private static extern nint CreateCompatibleBitmap(nint dc, int width, int height);
    [DllImport("gdi32.dll")] private static extern nint SelectObject(nint dc, nint handle);
    [DllImport("gdi32.dll")] private static extern bool BitBlt(nint target, int x, int y, int width, int height, nint source, int sx, int sy, uint operation);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(nint handle);
    [DllImport("gdi32.dll")] private static extern bool DeleteDC(nint dc);
}
