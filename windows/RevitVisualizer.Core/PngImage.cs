using System.Buffers.Binary;

namespace RevitVisualizer.Core;

public sealed record PngImage(byte[] Bytes, int Width, int Height)
{
    public const int MaxBytes = 64 * 1024 * 1024;
    public static PngImage Read(byte[] bytes)
    {
        ReadOnlySpan<byte> signature = [137, 80, 78, 71, 13, 10, 26, 10];
        if (bytes.Length < 45 || bytes.Length > MaxBytes || !bytes.AsSpan(0, 8).SequenceEqual(signature))
            throw new InvalidDataException("The response is not a complete PNG image.");
        int offset = 8, width = 0, height = 0;
        bool data = false;
        while (offset <= bytes.Length - 12)
        {
            uint length = BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(offset, 4));
            if (length > (uint)(bytes.Length - offset - 12)) throw new InvalidDataException("The PNG is incomplete.");
            var type = bytes.AsSpan(offset + 4, 4);
            if (offset == 8)
            {
                if (!type.SequenceEqual("IHDR"u8) || length != 13) throw new InvalidDataException("The PNG header is invalid.");
                width = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(offset + 8, 4));
                height = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(offset + 12, 4));
                RasterImage.ValidateSize(width, height);
            }
            else if (type.SequenceEqual("IHDR"u8)) throw new InvalidDataException("The PNG has multiple headers.");
            if (type.SequenceEqual("IDAT"u8)) data = true;
            offset += (int)length + 12;
            if (type.SequenceEqual("IEND"u8))
            {
                if (length != 0 || !data || offset != bytes.Length) throw new InvalidDataException("The PNG is incomplete.");
                return new(bytes, width, height);
            }
        }
        throw new InvalidDataException("The PNG is incomplete.");
    }
}
