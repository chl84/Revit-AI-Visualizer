package com.revitvisualizer.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

data class LocalImage(val file: File, val width: Int, val height: Int)

object ImageProcessor {
    fun describe(file: File): LocalImage {
        val bounds = bounds(file)
        val rotation = exif(file)?.rotationDegrees ?: 0
        return if (rotation == 90 || rotation == 270) {
            LocalImage(file, bounds.outHeight, bounds.outWidth)
        } else LocalImage(file, bounds.outWidth, bounds.outHeight)
    }

    private fun bounds(file: File): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "This image format cannot be read." }
        return options
    }

    private fun exif(file: File): ExifInterface? = try {
        ExifInterface(file)
    } catch (_: IOException) { null }

    fun preview(image: LocalImage, maxEdge: Int = 1600): Bitmap {
        val options = BitmapFactory.Options()
        val raw = bounds(image.file)
        while (maxOf(raw.outWidth, raw.outHeight) / options.inSampleSize.coerceAtLeast(1) > maxEdge) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        return decodeUpright(image.file, options)
    }

    private fun decodeUpright(file: File, options: BitmapFactory.Options = BitmapFactory.Options()): Bitmap {
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bitmap = BitmapFactory.decodeFile(file.path, options)
            ?: throw IllegalArgumentException("This image could not be decoded.")
        val exif = exif(file)
        if (exif == null || (exif.rotationDegrees == 0 && !exif.isFlipped)) return bitmap
        return try {
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    /** Only previews are downsampled. Full-resolution processing either succeeds or reports a memory limit. */
    fun prepare(source: LocalImage, selection: Selection, aspect: Float?, destination: File): LocalImage {
        require(source.file.canonicalFile != destination.canonicalFile) { "The original must not be overwritten." }
        require(selection.isValid()) { "Select a valid crop area." }
        val (width, height) = selection.outputSize(source.width, source.height, aspect)
        require(width >= 32 && height >= 32) { "Select a larger area of the image." }
        val sourceExif = exif(source.file)
        if (selection == Selection() && aspect == null && bounds(source.file).outMimeType == "image/png" &&
            (sourceExif == null || (sourceExif.rotationDegrees == 0 && !sourceExif.isFlipped))) {
            source.file.copyTo(destination, overwrite = true)
            return LocalImage(destination, width, height)
        }
        val sourceBytes = source.width.toLong() * source.height * 4L
        val estimatedBytes = maxOf(sourceBytes * 2, sourceBytes + width.toLong() * height * 4L) + 16L * 1024 * 1024
        require(estimatedBytes < Runtime.getRuntime().maxMemory() * 0.7) {
            "This image exceeds the device's processing memory. Import a smaller image; the original has not been changed."
        }
        val bitmap = decodeUpright(source.file)
        try {
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val from = selection.corners.flatMap { listOf(it.x * bitmap.width, it.y * bitmap.height) }.toFloatArray()
                val to = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
                val matrix = Matrix()
                require(matrix.setPolyToPoly(from, 0, to, 0, 4)) { "These corners cannot be corrected. Adjust the selection." }
                Canvas(output).apply {
                    drawColor(Color.BLACK)
                    drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                destination.outputStream().use {
                    check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) { "The prepared image could not be written." }
                }
            } finally { output.recycle() }
        } finally { bitmap.recycle() }
        return LocalImage(destination, width, height)
    }

    fun requirePng(file: File): LocalImage {
        val options = bounds(file)
        require(options.outMimeType == "image/png") { "OpenAI did not return a valid PNG image." }
        require(options.outWidth.toLong() * options.outHeight <= 40_000_000L) { "The returned image is too large to display." }
        return LocalImage(file, options.outWidth, options.outHeight)
    }
}
