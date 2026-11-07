using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using RevitVisualizer.Core;

namespace RevitVisualizer.Windows;

public sealed record LoadedImage(RasterImage Raster, bool UnchangedPng);

public static class Imaging
{
    public static LoadedImage Load(string path)
    {
        using var stream = File.OpenRead(path);
        if (stream.Length > 100L * 1024 * 1024) throw new ArgumentException("The source image exceeds 100 MB. Import a smaller image.");
        var decoder = BitmapDecoder.Create(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnDemand);
        var frame = decoder.Frames[0];
        RasterImage.ValidateSize(frame.PixelWidth, frame.PixelHeight);
        int orientation = 1;
        if (frame.Metadata is BitmapMetadata metadata)
        {
            foreach (string query in new[] { "/app1/ifd/{ushort=274}", "/ifd/{ushort=274}" })
            {
                try { if (metadata.GetQuery(query) is ushort value && value is >= 1 and <= 8) { orientation = value; break; } }
                catch (Exception error) when (error is NotSupportedException or ArgumentException) { }
            }
        }
        var converted = new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0);
        var pixels = new byte[checked(frame.PixelWidth * frame.PixelHeight * 4)];
        converted.CopyPixels(pixels, frame.PixelWidth * 4, 0);
        return new(new RasterImage(frame.PixelWidth, frame.PixelHeight, pixels).Orient(orientation), decoder is PngBitmapDecoder && orientation == 1);
    }

    public static RasterImage FromBitmap(BitmapSource bitmap)
    {
        RasterImage.ValidateSize(bitmap.PixelWidth, bitmap.PixelHeight);
        var converted = new FormatConvertedBitmap(bitmap, PixelFormats.Bgra32, null, 0);
        var pixels = new byte[checked(bitmap.PixelWidth * bitmap.PixelHeight * 4)];
        converted.CopyPixels(pixels, bitmap.PixelWidth * 4, 0);
        return new(bitmap.PixelWidth, bitmap.PixelHeight, pixels);
    }

    public static BitmapSource Preview(RasterImage raster, int maxEdge = 1800)
    {
        double scale = Math.Min(1, (double)maxEdge / Math.Max(raster.Width, raster.Height));
        int width = Math.Max(1, (int)Math.Round(raster.Width * scale)), height = Math.Max(1, (int)Math.Round(raster.Height * scale));
        var pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
        {
            int sx = Math.Min(raster.Width - 1, (int)((x + 0.5) / scale)), sy = Math.Min(raster.Height - 1, (int)((y + 0.5) / scale));
            Buffer.BlockCopy(raster.Pixels, (sy * raster.Width + sx) * 4, pixels, (y * width + x) * 4, 4);
        }
        var image = BitmapSource.Create(width, height, 96, 96, PixelFormats.Bgra32, null, pixels, width * 4);
        image.Freeze();
        return image;
    }

    public static byte[] EncodePng(RasterImage image)
    {
        var bitmap = BitmapSource.Create(image.Width, image.Height, 96, 96, PixelFormats.Bgra32, null, image.Pixels, image.Width * 4);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }

    public static BitmapSource DecodePreview(byte[] png)
    {
        PngImage.Read(png);
        using var stream = new MemoryStream(png, writable: false);
        var decoder = new PngBitmapDecoder(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        return Preview(FromBitmap(decoder.Frames[0]));
    }
}
