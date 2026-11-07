using System.Globalization;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using RevitVisualizer.Core;

namespace RevitVisualizer.Windows;

public sealed class CropEditor : FrameworkElement
{
    private BitmapSource? image;
    private Selection selection = Selection.FullFrame;
    private int dragging = -1;
    public bool Perspective { get; set; }
    public BitmapSource? Image { get => image; set { image = value; InvalidateVisual(); } }
    public Selection Selection { get => selection; set { selection = value; InvalidateVisual(); } }
    private Rect ImageRect => ControlImages.Fit(image, Math.Max(0, ActualWidth - 40), Math.Max(0, ActualHeight - 40), new(20, 20));
    public CropEditor() { Focusable = true; Cursor = Cursors.Cross; }
    protected override void OnRender(DrawingContext drawing)
    {
        drawing.DrawRectangle(new SolidColorBrush(Color.FromRgb(12, 16, 18)), null, new(0, 0, ActualWidth, ActualHeight));
        if (image is null) return;
        var rect = ImageRect;
        drawing.DrawImage(image, rect);
        var polygon = new StreamGeometry();
        using (var context = polygon.Open())
        {
            context.BeginFigure(Map(selection.Corners[0], rect), true, true);
            context.PolyLineTo(selection.Corners.Skip(1).Select(p => Map(p, rect)).ToArray(), true, false);
        }
        drawing.DrawGeometry(new SolidColorBrush(Color.FromArgb(150, 0, 0, 0)), null,
            new CombinedGeometry(GeometryCombineMode.Exclude, new RectangleGeometry(rect), polygon));
        var accent = new SolidColorBrush(Color.FromRgb(194, 221, 203));
        drawing.DrawGeometry(null, new Pen(accent, 2), polygon);
        for (int i = 0; i < 4; i++)
        {
            var center = Map(selection.Corners[i], rect);
            drawing.DrawEllipse(accent, new Pen(Brushes.Black, 2), center, 9, 9);
            var label = new FormattedText((i + 1).ToString(CultureInfo.InvariantCulture), CultureInfo.InvariantCulture,
                FlowDirection.LeftToRight, new Typeface("Segoe UI"), 12, Brushes.Black, VisualTreeHelper.GetDpi(this).PixelsPerDip);
            drawing.DrawText(label, center - new Vector(label.Width / 2, label.Height / 2));
        }
    }
    private static Point Map(ImagePoint point, Rect rect) => new(rect.X + point.X * rect.Width, rect.Y + point.Y * rect.Height);
    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonDown(e);
        if (image is null || !selection.IsValid) return;
        var position = e.GetPosition(this); var rect = ImageRect;
        int nearest = Enumerable.Range(0, 4).MinBy(i => (Map(selection.Corners[i], rect) - position).Length);
        if ((Map(selection.Corners[nearest], rect) - position).Length <= 28) { dragging = nearest; CaptureMouse(); e.Handled = true; }
    }
    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (dragging < 0 || !IsMouseCaptured) return;
        var rect = ImageRect; var p = e.GetPosition(this);
        if (rect.Width > 0 && rect.Height > 0) Selection = selection.MoveCorner(dragging, new((p.X - rect.X) / rect.Width, (p.Y - rect.Y) / rect.Height), Perspective);
    }
    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e) { dragging = -1; ReleaseMouseCapture(); base.OnMouseLeftButtonUp(e); }
    protected override void OnLostMouseCapture(MouseEventArgs e) { dragging = -1; base.OnLostMouseCapture(e); }
}

public sealed class ComparisonViewer : FrameworkElement
{
    private BitmapSource? original, result;
    private int mode = 1;
    private double opacity = 0.5, zoom = 1;
    private Vector pan;
    private Point anchor;
    public event Action<double>? ZoomChanged;
    public double Zoom => zoom;
    public BitmapSource? Original { get => original; set { original = value; ResetView(); } }
    public BitmapSource? Result { get => result; set { result = value; ResetView(); } }
    public int Mode { get => mode; set { mode = value; InvalidateVisual(); } }
    public double OriginalOpacity { get => opacity; set { opacity = Math.Clamp(value, 0, 1); InvalidateVisual(); } }
    public ComparisonViewer() { Focusable = true; Cursor = Cursors.SizeAll; ClipToBounds = true; }
    public void ResetView() { zoom = 1; pan = new(); InvalidateVisual(); ZoomChanged?.Invoke(zoom); }
    protected override void OnRender(DrawingContext drawing)
    {
        drawing.DrawRectangle(Brushes.Black, null, new(0, 0, ActualWidth, ActualHeight));
        drawing.PushClip(new RectangleGeometry(new(0, 0, ActualWidth, ActualHeight)));
        drawing.PushTransform(new MatrixTransform(zoom, 0, 0, zoom, ActualWidth / 2 * (1 - zoom) + pan.X, ActualHeight / 2 * (1 - zoom) + pan.Y));
        if (mode != 0 && result is not null) drawing.DrawImage(result, ControlImages.Fit(result, ActualWidth, ActualHeight));
        if (mode != 1 && original is not null)
        {
            drawing.PushOpacity(mode == 0 ? 1 : opacity);
            drawing.DrawImage(original, ControlImages.Fit(original, ActualWidth, ActualHeight));
            drawing.Pop();
        }
        drawing.Pop(); drawing.Pop();
    }
    protected override void OnMouseWheel(MouseWheelEventArgs e)
    {
        double next = Math.Clamp(zoom * (e.Delta > 0 ? 1.15 : 1 / 1.15), 1, 8);
        var position = e.GetPosition(this) - new Point(ActualWidth / 2, ActualHeight / 2);
        pan = position - (position - pan) * (next / zoom);
        zoom = next; ClampPan(); InvalidateVisual(); ZoomChanged?.Invoke(zoom); e.Handled = true;
    }
    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e) { anchor = e.GetPosition(this); CaptureMouse(); e.Handled = true; }
    protected override void OnMouseMove(MouseEventArgs e)
    {
        if (!IsMouseCaptured) return;
        var point = e.GetPosition(this); pan += point - anchor; anchor = point; ClampPan(); InvalidateVisual();
    }
    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e) { ReleaseMouseCapture(); e.Handled = true; }
    protected override void OnRenderSizeChanged(SizeChangedInfo sizeInfo) { base.OnRenderSizeChanged(sizeInfo); ClampPan(); }
    private void ClampPan() => pan = new(Math.Clamp(pan.X, -ActualWidth * (zoom - 1) / 2, ActualWidth * (zoom - 1) / 2),
        Math.Clamp(pan.Y, -ActualHeight * (zoom - 1) / 2, ActualHeight * (zoom - 1) / 2));
}

public static class ControlImages
{
    public static Rect Fit(BitmapSource? image, double width, double height, Point offset = default)
    {
        if (image is null || width <= 0 || height <= 0) return new(offset, new Size());
        var fit = Selection.Fit(image.PixelWidth, image.PixelHeight, width, height);
        return new(offset.X + (width - fit.Width) / 2, offset.Y + (height - fit.Height) / 2, fit.Width, fit.Height);
    }
}
