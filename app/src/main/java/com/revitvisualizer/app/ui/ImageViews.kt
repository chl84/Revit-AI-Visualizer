package com.revitvisualizer.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.revitvisualizer.app.imaging.ImagePoint
import com.revitvisualizer.app.imaging.ImageProcessor
import com.revitvisualizer.app.imaging.LocalImage
import com.revitvisualizer.app.imaging.Selection
import com.revitvisualizer.app.imaging.fittedSize
import com.revitvisualizer.app.advanced.NorthArrow
import com.revitvisualizer.app.advanced.NorthGuide
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Preview(val bitmap: Bitmap? = null, val failed: Boolean = false)

@Composable
private fun loadPreview(image: LocalImage): Preview {
    val preview by produceState(Preview(), image.file) {
        value = Preview()
        value = withContext(Dispatchers.IO) {
            try { Preview(ImageProcessor.preview(image)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { Preview(failed = true) }
            catch (_: OutOfMemoryError) { Preview(failed = true) }
        }
    }
    return preview
}

@Composable
fun ImagePreview(image: LocalImage, modifier: Modifier = Modifier) {
    val preview = loadPreview(image)
    val bitmap = remember(preview.bitmap) { preview.bitmap?.asImageBitmap() }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Source image preview" }) {
            drawFitted(bitmap, Rect(Offset.Zero, size))
        } else PreviewStatus(preview.failed)
    }
}

@Composable
private fun PreviewStatus(failed: Boolean) {
    if (failed) Text("Preview unavailable. Try importing another image.", Modifier.padding(24.dp))
    else CircularProgressIndicator()
}

private fun imageRect(bitmap: ImageBitmap, viewport: IntSize, inset: Float): Rect {
    val (width, height) = fittedSize(bitmap.width, bitmap.height,
        (viewport.width - inset * 2).coerceAtLeast(0f), (viewport.height - inset * 2).coerceAtLeast(0f))
    return Rect((viewport.width - width) / 2, (viewport.height - height) / 2,
        (viewport.width + width) / 2, (viewport.height + height) / 2)
}

@Composable
fun CropPreview(
    image: LocalImage,
    selection: Selection,
    perspective: Boolean,
    onSelection: (Selection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = loadPreview(image)
    val bitmap = remember(preview.bitmap) { preview.bitmap?.asImageBitmap() }
    val current by rememberUpdatedState(selection)
    val select by rememberUpdatedState(onSelection)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap == null) PreviewStatus(preview.failed)
        else Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }
            .semantics { contentDescription = "Crop selection. Drag the four corner handles to frame the Revit viewport." }
            .pointerInput(bitmap, perspective, viewport) {
                val rect = imageRect(bitmap, viewport, 16.dp.toPx())
                var handle = -1
                detectDragGestures(
                    onDragStart = { position ->
                        handle = current.corners.indices.minByOrNull { i ->
                            val point = current.corners[i]
                            (Offset(rect.left + point.x * rect.width, rect.top + point.y * rect.height) - position).getDistance()
                        } ?: -1
                        if (handle >= 0) {
                            val p = current.corners[handle]
                            if ((Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height) - position)
                                    .getDistance() > 48.dp.toPx()) handle = -1
                        }
                    },
                    onDragEnd = { handle = -1 },
                    onDragCancel = { handle = -1 },
                    onDrag = { change, _ ->
                        if (handle >= 0 && rect.width > 0 && rect.height > 0) {
                            change.consume()
                            select(current.moveCorner(handle, ImagePoint(
                                (change.position.x - rect.left) / rect.width,
                                (change.position.y - rect.top) / rect.height,
                            ), perspective))
                        }
                    },
                )
            }) {
            val rect = imageRect(bitmap, viewport, 16.dp.toPx())
            drawImage(bitmap, dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
            val points = selection.corners.map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
            val outline = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(rect)
                addPath(outline)
            }, Color.Black.copy(alpha = 0.65f))
            drawPath(outline, accent, style = Stroke(2.dp.toPx()))
            points.forEach {
                drawCircle(Color.Black, 11.dp.toPx(), it)
                drawCircle(accent, 8.dp.toPx(), it)
            }
        }
    }
}

@Composable
fun Comparison(original: LocalImage, result: LocalImage, modifier: Modifier = Modifier) {
    val source = loadPreview(original)
    val output = loadPreview(result)
    val before = remember(source.bitmap) { source.bitmap?.asImageBitmap() }
    val after = remember(output.bitmap) { output.bitmap?.asImageBitmap() }
    var mode by remember(original.file, result.file) { mutableIntStateOf(1) }
    var opacity by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember(original.file, result.file) { mutableFloatStateOf(1f) }
    var pan by remember(original.file, result.file) { mutableStateOf(Offset.Zero) }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (before == null || after == null) PreviewStatus(source.failed || output.failed)
            else Canvas(Modifier.fillMaxSize().semantics {
                contentDescription = "Original and result comparison. Pinch to zoom and drag to pan both images together."
            }.pointerInput(original.file, result.file) {
                detectTransformGestures { centroid, gesturePan, gestureZoom, _ ->
                    val next = (zoom * gestureZoom).coerceIn(1f, 8f)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val offset = centroid - center
                    val moved = (pan - offset) * (next / zoom) + offset + gesturePan
                    pan = Offset(
                        moved.x.coerceIn(-size.width * (next - 1) / 2, size.width * (next - 1) / 2),
                        moved.y.coerceIn(-size.height * (next - 1) / 2, size.height * (next - 1) / 2),
                    )
                    zoom = next
                }
            }) {
                clipRect {
                    withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, center) }) {
                        val rect = Rect(Offset.Zero, size)
                        if (mode == 0) drawFitted(before, rect)
                        else {
                            drawFitted(after, rect)
                            if (mode == 2) drawFitted(before, rect, opacity)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Original", "Result", "Overlay").forEachIndexed { index, label ->
                FilterChip(selected = mode == index, onClick = { mode = index }, label = { Text(label) })
            }
        }
        if (mode == 2) {
            Text("Original opacity: ${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(value = opacity, onValueChange = { opacity = it }, modifier = Modifier.semantics { contentDescription = "Original overlay opacity" })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Pinch to zoom · ${"%.1f".format(zoom)}×", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Reset view") }
        }
        if (original.width.toLong() * result.height != result.width.toLong() * original.height) {
            Text("Different aspect ratios: images are fitted without stretching.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun DrawScope.drawFitted(bitmap: ImageBitmap, rect: Rect, alpha: Float = 1f) {
    val (width, height) = fittedSize(bitmap.width, bitmap.height, rect.width, rect.height)
    drawImage(bitmap, dstOffset = IntOffset((rect.left + (rect.width - width) / 2).roundToInt(),
        (rect.top + (rect.height - height) / 2).roundToInt()),
        dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)), alpha = alpha)
}

@Composable
fun NorthArrowPreview(image: LocalImage, north: NorthArrow, onNorth: (NorthArrow) -> Unit, modifier: Modifier = Modifier) {
    val preview = loadPreview(image)
    val bitmap = remember(preview.bitmap) { preview.bitmap?.asImageBitmap() }
    val current by rememberUpdatedState(north)
    val update by rememberUpdatedState(onNorth)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap == null) PreviewStatus(preview.failed)
        else Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }.semantics {
            contentDescription = "North arrow editor. Drag the base to move the arrow; drag N to change its direction and length."
        }.pointerInput(bitmap, viewport) {
            val rect = imageRect(bitmap, viewport, 16.dp.toPx())
            var handle = -1
            detectDragGestures(onDragStart = { position ->
                val points = listOf(current.tail, current.head).map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
                handle = points.indices.minByOrNull { (points[it] - position).getDistance() } ?: -1
                if (handle >= 0 && (points[handle] - position).getDistance() > 48.dp.toPx()) handle = -1
            }, onDragEnd = { handle = -1 }, onDragCancel = { handle = -1 }) { change, delta ->
                if (handle >= 0 && rect.width > 0 && rect.height > 0) {
                    change.consume()
                    val arrow = current
                    val next = if (handle == 0) {
                        val dx = (delta.x / rect.width).coerceIn(0.04f - minOf(arrow.tail.x, arrow.head.x), 0.96f - maxOf(arrow.tail.x, arrow.head.x))
                        val dy = (delta.y / rect.height).coerceIn(0.04f - minOf(arrow.tail.y, arrow.head.y), 0.96f - maxOf(arrow.tail.y, arrow.head.y))
                        arrow.copy(tail = ImagePoint(arrow.tail.x + dx, arrow.tail.y + dy), head = ImagePoint(arrow.head.x + dx, arrow.head.y + dy))
                    } else arrow.copy(head = ImagePoint(((change.position.x - rect.left) / rect.width).coerceIn(0.04f, 0.96f),
                        ((change.position.y - rect.top) / rect.height).coerceIn(0.04f, 0.96f)))
                    if (next.valid(image.width, image.height)) update(next)
                }
            }
        }) {
            val rect = imageRect(bitmap, viewport, 16.dp.toPx())
            drawImage(bitmap, dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
            drawContext.canvas.nativeCanvas.apply {
                save(); translate(rect.left, rect.top)
                NorthGuide.draw(this, rect.width, rect.height, north)
                restore()
            }
            listOf(north.tail, north.head).forEach { point ->
                drawCircle(Color.White, 12.dp.toPx(), Offset(rect.left + point.x * rect.width, rect.top + point.y * rect.height), style = Stroke(2.dp.toPx()))
            }
        }
    }
}
