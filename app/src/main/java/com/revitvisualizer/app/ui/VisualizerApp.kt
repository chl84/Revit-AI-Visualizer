package com.revitvisualizer.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.viewmodel.compose.viewModel
import com.revitvisualizer.app.Stage
import com.revitvisualizer.app.VisualizerViewModel
import com.revitvisualizer.app.camera.CameraCapture
import com.revitvisualizer.app.imaging.parseAspectRatio
import com.revitvisualizer.app.imaging.LocalImage

@Composable
fun VisualizerApp(model: VisualizerViewModel = viewModel()) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFC2DDCB), onPrimary = Color(0xFF183226),
        background = Color(0xFF101416), surface = Color(0xFF171D20),
        onSurface = Color(0xFFE5EBE7), onBackground = Color(0xFFE5EBE7),
        surfaceVariant = Color(0xFF26302F), onSurfaceVariant = Color(0xFFAFBAB4),
    )) {
        val notifications = remember { SnackbarHostState() }
        val view = LocalView.current
        DisposableEffect(view, model.generating) {
            val previous = view.keepScreenOn
            if (model.generating) view.keepScreenOn = true
            onDispose { view.keepScreenOn = previous }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) model.import(uri)
        }
        val import = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        LaunchedEffect(model.message) {
            model.message?.let {
                notifications.showSnackbar(it)
                model.dismissMessage()
            }
        }
        BackHandler(enabled = model.busy != null || model.stage != Stage.Capture || model.source != null) {
            if (model.busy == null) {
                if (model.stage == Stage.Capture) model.retake() else model.back()
            }
        }
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing, snackbarHost = { SnackbarHost(notifications) }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Vizruna", style = MaterialTheme.typography.titleLarge)
                        Text("${model.stage.ordinal + 1} / 3 · ${model.stage.name}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (model.hasCredential || model.hasSavedCredential) {
                        TextButton(onClick = model::clearCredential, enabled = model.busy == null) { Text("Clear key") }
                    }
                }
                Spacer(Modifier.height(20.dp))
                when (model.stage) {
                    Stage.Capture -> {
                        val image = model.source
                        if (image == null) CameraCapture(
                            createFile = model::captureFile, onCaptured = model::acceptCapture,
                            onError = model::reportCameraError, onImport = import, modifier = Modifier.weight(1f),
                        )
                        else CapturedImageReview(image, model::openPrepare, model::retake, import,
                            Modifier.weight(1f).fillMaxWidth())
                    }
                    Stage.Prepare -> PrepareScreen(model, Modifier.weight(1f).fillMaxWidth())
                    Stage.Result -> {
                        val original = model.prepared
                        val result = model.result
                        if (original != null && result != null) {
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                val comparisonHeight = (maxHeight * 0.72f).coerceAtLeast(340.dp)
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    Text("Review the architecture", style = MaterialTheme.typography.titleMedium)
                                    Text("AI can change geometry. Check windows, rooflines and proportions against the original.",
                                        style = MaterialTheme.typography.bodySmall)
                                    Comparison(original, result, Modifier.height(comparisonHeight).fillMaxWidth())
                                    Text("Original shows the prepared AI input. Your untouched capture is retained for this session.",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("PNG · ${result.width} × ${result.height}", style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(vertical = 8.dp))
                                    model.lightingAnalysis?.let { LightingAnalysisReview(it) }
                                    Button(onClick = model::save, modifier = Modifier.fillMaxWidth()) { Text("Save Image") }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = model::back) { Text("Back to prepare") }
                                        TextButton(onClick = model::retake) { Text("New image") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (model.showCredentialDialog) CredentialDialog(onDismiss = model::dismissCredentialDialog, onSubmit = model::submitCredential,
            advanced = model.environment.enabled)
        if (model.showNorthEditor) model.northPreview?.let {
            NorthArrowDialog(it, model.environment.north, model::closeNorthEditor, model::confirmNorth)
        }
        model.failure?.let { FailureDialog(it, model::dismissFailure) }
        model.busy?.let { label ->
            Dialog(onDismissRequest = {}, properties = DialogProperties(
                dismissOnBackPress = false, dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn,
            )) {
                Surface(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(label)
                        if (model.generating) {
                            Text("This can take several minutes. Keep the app open.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = model::cancelGeneration) { Text("Cancel generation") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapturedImageReview(
    image: LocalImage,
    onPrepare: () -> Unit,
    onRetake: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                ImagePreview(image, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(16.dp))
                Column(Modifier.width(200.dp).align(Alignment.CenterVertically).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${image.width} × ${image.height} · Original preserved", style = MaterialTheme.typography.labelSmall)
                    Button(onClick = onPrepare, modifier = Modifier.fillMaxWidth()) { Text("Prepare image") }
                    TextButton(onClick = onRetake, modifier = Modifier.fillMaxWidth()) { Text("Retake") }
                    TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import another image") }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ImagePreview(image, Modifier.weight(1f).fillMaxWidth())
                Text("${image.width} × ${image.height} · Original preserved", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPrepare, modifier = Modifier.fillMaxWidth()) { Text("Prepare image") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = onRetake) { Text("Retake") }
                    TextButton(onClick = onImport) { Text("Import another image") }
                }
            }
        }
    }
}

@Composable
fun FailureDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Could not complete action") },
        text = {
            SelectionContainer {
                Text(message, Modifier.verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PrepareScreen(model: VisualizerViewModel, modifier: Modifier = Modifier) {
    val image = model.source ?: return
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BoxWithConstraints(modifier) {
        val controlsHeight = maxHeight * 0.55f
        val imageArea: @Composable (Modifier) -> Unit = { area ->
            Column(area) {
                Text("Frame the Revit viewport", style = MaterialTheme.typography.titleMedium)
                Text("Drag the four handles to crop out the screen and interface.", style = MaterialTheme.typography.bodySmall)
                CropPreview(image, model.selection, model.perspective, model::select,
                    Modifier.weight(1f).fillMaxWidth())
            }
        }
        val controls: @Composable (Modifier) -> Unit = { area ->
            Column(area.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Correct perspective", Modifier.weight(1f))
                    Switch(checked = model.perspective, onCheckedChange = model::setPerspectiveMode,
                        modifier = Modifier.semantics { contentDescription = "Correct perspective" })
                }
                if (model.perspective) {
                    OutlinedTextField(
                        value = model.aspectText, onValueChange = model::setAspect,
                        label = { Text("Viewport aspect ratio, e.g. 16:9") },
                        placeholder = { Text("Blank = estimate from selection") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
                        isError = model.aspectText.isNotBlank() && parseAspectRatio(model.aspectText) == null,
                    )
                    Text("Use the viewport's true ratio to avoid changing building proportions. A blank ratio is an estimate.",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = model::back) { Text("Back") }
                    TextButton(onClick = model::resetSelection) { Text("Reset selection") }
                }
                AdvancedControls(model)
                Text("Sends this prepared image to OpenAI using your paid API account.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(onClick = model::visualize, modifier = Modifier.fillMaxWidth(),
                    enabled = !model.perspective || model.aspectText.isBlank() || parseAspectRatio(model.aspectText) != null,
                ) { Text("Visualize") }
            }
        }
        if (maxWidth > 600.dp && maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                imageArea(Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(20.dp))
                controls(Modifier.weight(0.85f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                imageArea(Modifier.weight(1f).fillMaxWidth())
                controls(Modifier.fillMaxWidth().heightIn(max = controlsHeight))
            }
        }
    }
}

@Composable
fun CredentialDialog(onDismiss: () -> Unit, onSubmit: (String, Boolean) -> Unit, advanced: Boolean = false) {
    // Unsaved entry never enters saved-instance state. Persistence is an explicit device-local choice.
    var value by remember { mutableStateOf("") }
    var rememberOnDevice by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { value = ""; onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("OpenAI API key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("For personal development and testing. Enter your own OpenAI API key.")
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().toggleable(rememberOnDevice, role = Role.Checkbox,
                    onValueChange = { rememberOnDevice = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberOnDevice, onCheckedChange = null)
                    Text("Remember on this device")
                }
                Text(if (rememberOnDevice) "Stored encrypted on this device. Clear key removes the saved copy."
                    else "Kept only in memory until the app closes. Turn on Remember on this device to reuse it next time.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Visualize sends the prepared image to OpenAI and uses your API credits. Direct device access is not a secure distribution model for a public app.",
                    style = MaterialTheme.typography.bodySmall)
                if (advanced) Text("Advanced includes a separate paid orientation analysis before image generation.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { val submitted = value; value = ""; onSubmit(submitted, rememberOnDevice) }, enabled = value.isNotBlank()) { Text("Visualize") }
        },
        dismissButton = { TextButton(onClick = { value = ""; onDismiss() }) { Text("Cancel") } },
    )
}
