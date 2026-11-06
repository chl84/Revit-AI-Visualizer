package com.revitvisualizer.app.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ImagePoint(val x: Float, val y: Float)

/** Coordinates are normalized in the upright image, in clockwise corner order. */
data class Selection(val corners: List<ImagePoint> = fullFrame) {
    fun isValid(): Boolean {
        if (corners.size != 4 || corners.any {
                !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f
            }) return false
        val crosses = corners.indices.map { i ->
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return crosses.all { it > 0.00001f } && area() > 0.0001f
    }

    private fun area(): Float = abs(corners.indices.sumOf { i ->
        val a = corners[i]
        val b = corners[(i + 1) % 4]
        (a.x * b.y - b.x * a.y).toDouble()
    }.toFloat()) / 2f

    fun moveCorner(index: Int, point: ImagePoint, perspective: Boolean): Selection {
        val x = point.x.coerceIn(0f, 1f)
        val y = point.y.coerceIn(0f, 1f)
        val next = corners.toMutableList()
        next[index] = ImagePoint(x, y)
        if (!perspective) {
            when (index) {
                0 -> { next[1] = ImagePoint(next[1].x, y); next[3] = ImagePoint(x, next[3].y) }
                1 -> { next[0] = ImagePoint(next[0].x, y); next[2] = ImagePoint(x, next[2].y) }
                2 -> { next[3] = ImagePoint(next[3].x, y); next[1] = ImagePoint(x, next[1].y) }
                3 -> { next[2] = ImagePoint(next[2].x, y); next[0] = ImagePoint(x, next[0].y) }
            }
        }
        return Selection(next).takeIf { it.isValid() } ?: this
    }

    fun outputSize(width: Int, height: Int, aspect: Float? = null): Pair<Int, Int> {
        require(isValid()) { "Select four corners that form a convex area." }
        require(width > 0 && height > 0)
        require(aspect == null || (aspect.isFinite() && aspect > 0f))
        fun distance(a: Int, b: Int): Float = hypot(
            (corners[a].x - corners[b].x) * width,
            (corners[a].y - corners[b].y) * height,
        )
        var w = max(distance(0, 1), distance(3, 2))
        var h = max(distance(0, 3), distance(1, 2))
        if (aspect != null) {
            w = min(w, h * aspect)
            h = w / aspect
        }
        return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
    }

    companion object {
        val fullFrame = listOf(
            ImagePoint(0f, 0f), ImagePoint(1f, 0f), ImagePoint(1f, 1f), ImagePoint(0f, 1f),
        )
    }
}

fun parseAspectRatio(value: String): Float? {
    if (value.isBlank()) return null
    val parts = value.trim().split(':')
    if (parts.size != 2) return null
    val w = parts[0].trim().toFloatOrNull() ?: return null
    val h = parts[1].trim().toFloatOrNull() ?: return null
    if (!w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) return null
    return (w / h).takeIf { it in 0.1f..10f }
}

/** Fit without cropping or stretching. Both comparison images use this same rule. */
fun fittedSize(imageWidth: Int, imageHeight: Int, boxWidth: Float, boxHeight: Float): Pair<Float, Float> {
    require(imageWidth > 0 && imageHeight > 0 && boxWidth >= 0 && boxHeight >= 0)
    val scale = min(boxWidth / imageWidth, boxHeight / imageHeight)
    return imageWidth * scale to imageHeight * scale
}
