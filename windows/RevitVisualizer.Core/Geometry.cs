using System.Globalization;

namespace RevitVisualizer.Core;

public readonly record struct ImagePoint(double X, double Y);
public readonly record struct ImageSize(int Width, int Height);

public sealed class Selection
{
    private readonly ImagePoint[] corners;
    public IReadOnlyList<ImagePoint> Corners => Array.AsReadOnly(corners);
    public static Selection FullFrame => new([new(0, 0), new(1, 0), new(1, 1), new(0, 1)]);
    public bool IsFullFrame => corners.SequenceEqual(FullFrame.corners);

    public Selection(IEnumerable<ImagePoint> points) => corners = points.ToArray();

    public bool IsValid
    {
        get
        {
            if (corners.Length != 4 || corners.Any(p => !double.IsFinite(p.X) || !double.IsFinite(p.Y) || p.X < 0 || p.X > 1 || p.Y < 0 || p.Y > 1)) return false;
            double area = 0;
            for (int i = 0; i < 4; i++)
            {
                var a = corners[i]; var b = corners[(i + 1) % 4]; var c = corners[(i + 2) % 4];
                if ((b.X - a.X) * (c.Y - b.Y) - (b.Y - a.Y) * (c.X - b.X) <= 0.00001) return false;
                area += a.X * b.Y - b.X * a.Y;
            }
            return area / 2 > 0.0001;
        }
    }

    public Selection MoveCorner(int index, ImagePoint point, bool perspective)
    {
        if (index is < 0 or > 3 || !double.IsFinite(point.X) || !double.IsFinite(point.Y)) return this;
        var next = (ImagePoint[])corners.Clone();
        double x = Math.Clamp(point.X, 0, 1), y = Math.Clamp(point.Y, 0, 1);
        next[index] = new(x, y);
        if (!perspective)
        {
            int horizontal = index switch { 0 => 1, 1 => 0, 2 => 3, _ => 2 };
            int vertical = index switch { 0 => 3, 1 => 2, 2 => 1, _ => 0 };
            next[horizontal] = next[horizontal] with { Y = y };
            next[vertical] = next[vertical] with { X = x };
        }
        var result = new Selection(next);
        return result.IsValid ? result : this;
    }

    public ImageSize OutputSize(int width, int height, double? aspect = null)
    {
        if (!IsValid || width <= 0 || height <= 0 || aspect is not null && (!double.IsFinite(aspect.Value) || aspect <= 0))
            throw new ArgumentException("Select four corners that form a valid convex area.");
        double Distance(int a, int b) => Math.Sqrt(Math.Pow((corners[a].X - corners[b].X) * width, 2) + Math.Pow((corners[a].Y - corners[b].Y) * height, 2));
        double w = Math.Max(Distance(0, 1), Distance(3, 2)), h = Math.Max(Distance(0, 3), Distance(1, 2));
        if (aspect is not null) { w = Math.Min(w, h * aspect.Value); h = w / aspect.Value; }
        return new(Math.Max(1, (int)Math.Round(w, MidpointRounding.AwayFromZero)), Math.Max(1, (int)Math.Round(h, MidpointRounding.AwayFromZero)));
    }

    public static double? ParseAspectRatio(string text)
    {
        var parts = text.Trim().Split(':');
        if (parts.Length != 2 || !double.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out double w) ||
            !double.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out double h) ||
            !double.IsFinite(w) || !double.IsFinite(h) || w <= 0 || h <= 0 || w / h is < 0.1 or > 10) return null;
        return w / h;
    }

    public static (double Width, double Height) Fit(int imageWidth, int imageHeight, double boxWidth, double boxHeight)
    {
        if (imageWidth <= 0 || imageHeight <= 0 || boxWidth < 0 || boxHeight < 0) throw new ArgumentOutOfRangeException(nameof(imageWidth));
        double scale = Math.Min(boxWidth / imageWidth, boxHeight / imageHeight);
        return (imageWidth * scale, imageHeight * scale);
    }
}

/// <summary>Maps an output rectangle to the selected source quadrilateral, in source pixels.</summary>
public sealed class ProjectiveMap
{
    private readonly double[] h;
    public ProjectiveMap(Selection selection, int sourceWidth, int sourceHeight, ImageSize output)
    {
        if (!selection.IsValid || output.Width <= 0 || output.Height <= 0) throw new ArgumentException("Invalid projective selection.");
        var target = new ImagePoint[] { new(0, 0), new(output.Width, 0), new(output.Width, output.Height), new(0, output.Height) };
        var matrix = new double[8, 9];
        for (int i = 0; i < 4; i++)
        {
            double x = target[i].X, y = target[i].Y, u = selection.Corners[i].X * sourceWidth, v = selection.Corners[i].Y * sourceHeight;
            int r = i * 2;
            matrix[r, 0] = x; matrix[r, 1] = y; matrix[r, 2] = 1; matrix[r, 6] = -u * x; matrix[r, 7] = -u * y; matrix[r, 8] = u;
            matrix[r + 1, 3] = x; matrix[r + 1, 4] = y; matrix[r + 1, 5] = 1; matrix[r + 1, 6] = -v * x; matrix[r + 1, 7] = -v * y; matrix[r + 1, 8] = v;
        }
        for (int column = 0; column < 8; column++)
        {
            int pivot = column;
            for (int row = column + 1; row < 8; row++) if (Math.Abs(matrix[row, column]) > Math.Abs(matrix[pivot, column])) pivot = row;
            if (Math.Abs(matrix[pivot, column]) < 1e-12) throw new ArgumentException("These corners cannot be corrected. Adjust the selection.");
            for (int j = column; j <= 8; j++) (matrix[column, j], matrix[pivot, j]) = (matrix[pivot, j], matrix[column, j]);
            double divisor = matrix[column, column];
            for (int j = column; j <= 8; j++) matrix[column, j] /= divisor;
            for (int row = 0; row < 8; row++) if (row != column)
            {
                double factor = matrix[row, column];
                for (int j = column; j <= 8; j++) matrix[row, j] -= factor * matrix[column, j];
            }
        }
        h = Enumerable.Range(0, 8).Select(i => matrix[i, 8]).ToArray();
    }
    public ImagePoint Transform(double x, double y)
    {
        double divisor = h[6] * x + h[7] * y + 1;
        if (Math.Abs(divisor) < 1e-12) throw new ArgumentException("These corners cannot be corrected.");
        return new((h[0] * x + h[1] * y + h[2]) / divisor, (h[3] * x + h[4] * y + h[5]) / divisor);
    }
}
