package com.revitvisualizer.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageProcessorTest {
    @get:Rule val files = TemporaryFolder()

    private fun source(): LocalImage {
        val file = files.newFile("source.png")
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, 300f, 200f, Paint().apply { color = Color.RED })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return ImageProcessor.describe(file)
    }

    @Test fun unchangedPngIsCopiedByteForByte() {
        val source = source()
        val originalBytes = source.file.readBytes()
        val prepared = ImageProcessor.prepare(source, Selection(), null, files.newFile("prepared.png"))
        assertArrayEquals(originalBytes, prepared.file.readBytes())
        assertArrayEquals(originalBytes, source.file.readBytes())
    }

    @Test fun cropSelectsExpectedPixelsAndPreservesOriginal() {
        val source = source()
        val originalBytes = source.file.readBytes()
        val selection = Selection().moveCorner(2, ImagePoint(0.5f, 0.5f), perspective = false)
        val prepared = ImageProcessor.prepare(source, selection, null, files.newFile("crop.png"))
        assertEquals(300, prepared.width)
        assertEquals(200, prepared.height)
        val output = BitmapFactory.decodeFile(prepared.file.path)
        assertEquals(Color.RED, output.getPixel(150, 100))
        output.recycle()
        assertArrayEquals(originalBytes, source.file.readBytes())
    }

    @Test fun perspectiveCorrectionProducesRequestedRatioWithoutTouchingSource() {
        val source = source()
        val bytes = source.file.readBytes()
        val selection = Selection(listOf(ImagePoint(0.1f, 0.1f), ImagePoint(0.9f, 0.05f),
            ImagePoint(0.85f, 0.9f), ImagePoint(0.2f, 0.95f)))
        val output = ImageProcessor.prepare(source, selection, 16f / 9, files.newFile("perspective.png"))
        assertEquals(16f / 9, output.width.toFloat() / output.height, 0.01f)
        val bitmap = BitmapFactory.decodeFile(output.file.path)
        assertEquals(Color.RED, bitmap.getPixel(20, 20))
        assertEquals(Color.BLUE, bitmap.getPixel(output.width - 20, output.height - 20))
        bitmap.recycle()
        assertArrayEquals(bytes, source.file.readBytes())
    }

    @Test fun exifRotationIsAppliedToPreviewAndPreparedInputOnly() {
        val file = files.newFile("rotated.jpg")
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val bytes = file.readBytes()
        val source = ImageProcessor.describe(file)
        assertEquals(400, source.width)
        assertEquals(600, source.height)
        val preview = ImageProcessor.preview(source)
        assertEquals(400, preview.width)
        assertEquals(600, preview.height)
        preview.recycle()
        val output = ImageProcessor.prepare(source, Selection(), null, files.newFile("upright.png"))
        assertEquals(400, output.width)
        assertEquals(600, output.height)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun sourceCannotBeUsedAsOutput() {
        val source = source()
        val bytes = source.file.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            ImageProcessor.prepare(source, Selection(), null, source.file)
        }
        assertArrayEquals(bytes, source.file.readBytes())
    }
}
