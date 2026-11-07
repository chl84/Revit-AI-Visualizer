package com.revitvisualizer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.revitvisualizer.app.advanced.NorthArrow
import com.revitvisualizer.app.imaging.ImagePoint
import com.revitvisualizer.app.imaging.LocalImage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private val NorthArrowSaver = listSaver<NorthArrow, Float>(
    save = { listOf(it.tail.x, it.tail.y, it.head.x, it.head.y) },
    restore = { NorthArrow(ImagePoint(it[0], it[1]), ImagePoint(it[2], it[3])) },
)

@Composable
fun NorthArrowDialog(image: LocalImage, initial: NorthArrow, onDismiss: () -> Unit, onConfirm: (NorthArrow) -> Unit) {
    var arrow by rememberSaveable(image.file.path, stateSaver = NorthArrowSaver) { mutableStateOf(initial) }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                val controls: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("North on this image", style = MaterialTheme.typography.titleLarge)
                        Text("Drag the base to move the arrow. Drag N to point north in the scene. The image is already cropped and perspective corrected.", style = MaterialTheme.typography.bodySmall)
                        val angle = ((Math.toDegrees(atan2(((arrow.head.x - arrow.tail.x) * image.width).toDouble(),
                            (-(arrow.head.y - arrow.tail.y) * image.height).toDouble())) + 360) % 360).toFloat()
                        Text("Arrow rotation: ${angle.toInt()}° on the image", style = MaterialTheme.typography.labelSmall)
                        Slider(angle, { degrees ->
                            // Rotate around the centre, translating away from edges when necessary.
                            val radians = Math.toRadians(degrees.toDouble())
                            val length = minOf(image.width, image.height) * 0.25f
                            val dx = (sin(radians) * length / image.width).toFloat()
                            val dy = (-cos(radians) * length / image.height).toFloat()
                            val cx = ((arrow.head.x + arrow.tail.x) / 2).coerceIn(0.04f + kotlin.math.abs(dx) / 2, 0.96f - kotlin.math.abs(dx) / 2)
                            val cy = ((arrow.head.y + arrow.tail.y) / 2).coerceIn(0.04f + kotlin.math.abs(dy) / 2, 0.96f - kotlin.math.abs(dy) / 2)
                            arrow = NorthArrow(ImagePoint(cx - dx / 2, cy - dy / 2), ImagePoint(cx + dx / 2, cy + dy / 2))
                        }, valueRange = 0f..360f, modifier = Modifier.semantics { contentDescription = "North arrow rotation" })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ arrow = NorthArrow() }) { Text("Reset arrow") }
                            TextButton(onDismiss) { Text("Cancel") }
                        }
                        Button({ onConfirm(arrow) }, Modifier.fillMaxWidth(), enabled = arrow.valid(image.width, image.height)) { Text("Confirm north arrow") }
                    }
                }
                if (maxWidth > maxHeight && maxWidth > 600.dp) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        NorthArrowPreview(image, arrow, { arrow = it }, Modifier.weight(1f).fillMaxHeight())
                        controls(Modifier.width(260.dp).fillMaxHeight().padding(start = 16.dp))
                    }
                } else Column(Modifier.fillMaxSize()) {
                    NorthArrowPreview(image, arrow, { arrow = it }, Modifier.weight(1f).fillMaxWidth())
                    controls(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
