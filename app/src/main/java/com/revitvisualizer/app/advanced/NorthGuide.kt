package com.revitvisualizer.app.advanced

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.revitvisualizer.app.imaging.ImageProcessor
import com.revitvisualizer.app.imaging.LocalImage
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object NorthGuide {
    fun create(input: LocalImage, north: NorthArrow, destination: File): LocalImage {
        require(input.file.canonicalFile != destination.canonicalFile)
        require(north.valid(input.width, input.height))
        val preview = ImageProcessor.preview(input)
        val bitmap = try { preview.copy(Bitmap.Config.ARGB_8888, true) } finally { preview.recycle() }
        try {
            draw(Canvas(bitmap), bitmap.width.toFloat(), bitmap.height.toFloat(), north)
            destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return LocalImage(destination, bitmap.width, bitmap.height)
        } catch (error: Throwable) { destination.delete(); throw error }
        finally { bitmap.recycle() }
    }

    /** Shared by the editor and exported guide, in prepared-image coordinates. */
    fun draw(canvas: Canvas, width: Float, height: Float, arrow: NorthArrow) {
        val x = arrow.tail.x * width
        val y = arrow.tail.y * height
        val hx = arrow.head.x * width
        val hy = arrow.head.y * height
        val unit = minOf(width, height)
        val angle = atan2(hy - y, hx - x)
        val headSize = unit * 0.045f
        val shape = Path().apply {
            moveTo(x, y); lineTo(hx, hy)
            moveTo(hx - headSize * cos(angle - 0.5f), hy - headSize * sin(angle - 0.5f))
            lineTo(hx, hy)
            lineTo(hx - headSize * cos(angle + 0.5f), hy - headSize * sin(angle + 0.5f))
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        paint.color = Color.BLACK; paint.strokeWidth = unit * 0.014f
        canvas.drawPath(shape, paint)
        paint.color = Color.CYAN; paint.strokeWidth = unit * 0.007f
        canvas.drawPath(shape, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, unit * 0.012f, paint)
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = unit * 0.05f
        val labelX = (hx + unit * 0.045f).coerceIn(unit * 0.04f, width - unit * 0.04f)
        val labelY = (hy - unit * 0.025f).coerceIn(unit * 0.06f, height - unit * 0.02f)
        paint.style = Paint.Style.STROKE; paint.color = Color.BLACK; paint.strokeWidth = unit * 0.009f
        canvas.drawText("N", labelX, labelY, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.CYAN
        canvas.drawText("N", labelX, labelY, paint)
    }
}
