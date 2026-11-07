namespace RevitVisualizer.Core;

/// <summary>Full-resolution, upright, straight-alpha BGRA pixels. Only display previews are sampled.</summary>
public sealed record RasterImage(int Width, int Height, byte[] Pixels)
{
    public const long MaxPixels = 40_000_000;
    public static void ValidateSize(int width, int height)
    {
        if (width <= 0 || height <= 0 || (long)width * height > MaxPixels)
            throw new ArgumentException("This image exceeds the safe 40-megapixel processing limit. The original is unchanged.");
    }

    public RasterImage Orient(int orientation)
    {
        if (orientation is < 2 or > 8) return this;
        int width = orientation >= 5 ? Height : Width, height = orientation >= 5 ? Width : Height;
        var pixels = new byte[checked(width * height * 4)];
        for (int y = 0; y < Height; y++) for (int x = 0; x < Width; x++)
        {
            (int dx, int dy) = orientation switch
            {
                2 => (Width - 1 - x, y), 3 => (Width - 1 - x, Height - 1 - y), 4 => (x, Height - 1 - y),
                5 => (y, x), 6 => (Height - 1 - y, x), 7 => (Height - 1 - y, Width - 1 - x), _ => (y, Width - 1 - x)
            };
            Buffer.BlockCopy(Pixels, (y * Width + x) * 4, pixels, (dy * width + dx) * 4, 4);
        }
        return new(width, height, pixels);
    }

    public RasterImage Prepare(Selection selection, double? aspect, CancellationToken cancellation = default)
    {
        var size = selection.OutputSize(Width, Height, aspect);
        if (size.Width < 32 || size.Height < 32) throw new ArgumentException("Select a larger image area (at least 32 pixels per side).");
        ValidateSize(size.Width, size.Height);
        if (selection.IsFullFrame && aspect is null) return this;
        var map = new ProjectiveMap(selection, Width, Height, size);
        var pixels = new byte[checked(size.Width * size.Height * 4)];
        for (int y = 0; y < size.Height; y++)
        {
            cancellation.ThrowIfCancellationRequested();
            for (int x = 0; x < size.Width; x++)
            {
                var source = map.Transform(x + 0.5, y + 0.5);
                double sx = Math.Clamp(source.X - 0.5, 0, Width - 1), sy = Math.Clamp(source.Y - 0.5, 0, Height - 1);
                int x0 = (int)sx, y0 = (int)sy, x1 = Math.Min(x0 + 1, Width - 1), y1 = Math.Min(y0 + 1, Height - 1);
                double fx = sx - x0, fy = sy - y0;
                for (int channel = 0; channel < 4; channel++)
                {
                    double a = Pixels[(y0 * Width + x0) * 4 + channel] * (1 - fx) + Pixels[(y0 * Width + x1) * 4 + channel] * fx;
                    double b = Pixels[(y1 * Width + x0) * 4 + channel] * (1 - fx) + Pixels[(y1 * Width + x1) * 4 + channel] * fx;
                    pixels[(y * size.Width + x) * 4 + channel] = (byte)Math.Clamp(Math.Round(a * (1 - fy) + b * fy), 0, 255);
                }
            }
        }
        return new(size.Width, size.Height, pixels);
    }
}
