package com.revitvisualizer.app.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

@Composable
fun CameraCapture(
    createFile: () -> File,
    onCaptured: (File) -> Unit,
    onError: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    var permitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(true) }
    val currentError by rememberUpdatedState(onError)
    val view = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    if (permitted) {
        DisposableEffect(owner, view) {
            var active = true
            var provider: ProcessCameraProvider? = null
            val preview = Preview.Builder().build().apply { surfaceProvider = view.surfaceProvider }
            val photo = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
                .build()
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (active) {
                    try {
                        provider = future.get()
                        val selector = if (provider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        } else CameraSelector.DEFAULT_FRONT_CAMERA
                        provider!!.bindToLifecycle(owner, selector, preview, photo)
                        capture = photo
                        available = true
                    } catch (_: Exception) {
                        available = false
                        currentError()
                    }
                }
            }, executor)
            onDispose {
                active = false
                capture = null
                provider?.unbind(preview, photo)
            }
        }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            if (permitted && available) AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            else Column(
                Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (available) "Photograph your Revit viewport" else "Camera unavailable", style = MaterialTheme.typography.titleMedium)
                Text("Frame the viewport as straight as possible, or import an existing image.")
                if (!permitted) Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Enable camera") }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = permitted && capture != null && !capturing,
            onClick = {
                val camera = capture ?: return@Button
                val file = createFile()
                capturing = true
                camera.targetRotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                camera.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            capturing = false
                            onCaptured(file)
                        }
                        override fun onError(exception: ImageCaptureException) {
                            capturing = false
                            file.delete()
                            currentError()
                        }
                    })
            },
            shape = CircleShape,
            modifier = Modifier.size(76.dp).semantics { contentDescription = "Take photo" },
        ) { Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape)) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onImport, enabled = !capturing, modifier = Modifier.fillMaxWidth()) { Text("Import image") }
    }
}
