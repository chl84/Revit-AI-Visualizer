package com.revitvisualizer.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.revitvisualizer.app.imaging.ImageProcessor
import com.revitvisualizer.app.imaging.LocalImage
import com.revitvisualizer.app.imaging.Selection
import com.revitvisualizer.app.imaging.parseAspectRatio
import com.revitvisualizer.app.openai.ImageApiException
import com.revitvisualizer.app.openai.OpenAiImages
import com.revitvisualizer.app.storage.ImageStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Stage { Capture, Prepare, Result }

class VisualizerViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ImageStore(application)
    private val api = OpenAiImages()
    private var credential: CharArray? = null
    private var task: Job? = null

    var stage by mutableStateOf(Stage.Capture)
        private set
    var source by mutableStateOf<LocalImage?>(null)
        private set
    var prepared by mutableStateOf<LocalImage?>(null)
        private set
    var result by mutableStateOf<LocalImage?>(null)
        private set
    var selection by mutableStateOf(Selection())
        private set
    var perspective by mutableStateOf(false)
        private set
    var aspectText by mutableStateOf("")
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var generating by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var hasCredential by mutableStateOf(false)
        private set
    var showCredentialDialog by mutableStateOf(false)
        private set

    fun captureFile() = store.captureFile()

    fun acceptCapture(file: File) = runTask("Opening photo…") {
        val image = ImageProcessor.describe(file)
        replaceSource(image)
    }

    fun import(uri: Uri) = runTask("Importing image…") { replaceSource(store.import(uri)) }

    private fun replaceSource(image: LocalImage) {
        val obsolete = listOfNotNull(source?.file, prepared?.file, result?.file)
        source = image
        prepared = null
        result = null
        resetSelection()
        stage = Stage.Capture
        obsolete.filter { it != image.file }.forEach { it.delete() }
    }

    fun openPrepare() { if (source != null && busy == null) stage = Stage.Prepare }

    fun retake() {
        if (busy != null) return
        listOfNotNull(source?.file, prepared?.file, result?.file).forEach { it.delete() }
        source = null
        prepared = null
        result = null
        stage = Stage.Capture
        resetSelection()
    }

    fun back() {
        if (busy != null) return
        stage = when (stage) {
            Stage.Result -> Stage.Prepare
            Stage.Prepare -> Stage.Capture
            Stage.Capture -> Stage.Capture
        }
    }

    fun select(value: Selection) { if (busy == null) selection = value }
    fun setPerspectiveMode(value: Boolean) {
        if (busy != null) return
        perspective = value
        selection = Selection()
        aspectText = ""
    }
    fun setAspect(value: String) { if (busy == null) aspectText = value.take(24) }
    fun resetSelection() { selection = Selection(); aspectText = ""; perspective = false }
    fun dismissMessage() { message = null }
    fun reportCameraError() { message = "The camera is unavailable. Check camera access or import an image." }

    fun visualize() {
        if (busy != null || source == null) return
        if (!hasCredential) { showCredentialDialog = true; return }
        generate()
    }

    fun dismissCredentialDialog() { showCredentialDialog = false }

    fun submitCredential(value: String) {
        if (value.isBlank() || value.any { it.code !in 33..126 }) {
            message = "Enter a valid API key without spaces or line breaks."
            return
        }
        credential?.fill('\u0000')
        credential = value.toCharArray()
        hasCredential = true
        showCredentialDialog = false
        generate()
    }

    fun clearCredential() {
        if (generating) task?.cancel()
        credential?.fill('\u0000')
        credential = null
        hasCredential = false
    }

    private fun generate() {
        val image = source ?: return
        val aspect = if (perspective) parseAspectRatio(aspectText) else null
        if (perspective && aspectText.isNotBlank() && aspect == null) {
            message = "Enter the viewport aspect ratio as width:height, for example 16:9."
            return
        }
        val selected = selection
        val sessionCredential = credential?.copyOf() ?: return
        generating = true
        runTask("Preparing full-resolution image…") {
            val inputFile = store.newFile("prepared", "png")
            val outputFile = store.newFile("result", "png")
            try {
                val input = ImageProcessor.prepare(image, selected, aspect, inputFile)
                ensureActive()
                withContext(Dispatchers.Main) { busy = "Visualizing with GPT Image 2.6 Sunburst…" }
                val output = api.edit(input, sessionCredential, outputFile)
                ensureActive()
                val obsolete = listOfNotNull(prepared?.file, result?.file)
                prepared = input
                result = output
                stage = Stage.Result
                obsolete.forEach { it.delete() }
            } catch (error: Throwable) {
                inputFile.delete()
                outputFile.delete()
                throw error
            } finally { sessionCredential.fill('\u0000') }
        }
    }

    fun cancelGeneration() { if (generating) task?.cancel() }

    fun save() {
        val image = result ?: return
        runTask("Saving PNG…") {
            store.export(image)
            message = "Image saved to Pictures / Revit AI Visualizer."
        }
    }

    private fun runTask(label: String, work: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        if (busy != null) return
        busy = label
        message = null
        task = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO, work)
            } catch (error: CancellationException) {
                message = if (generating) "Generation cancelled locally. OpenAI may still finish and bill a submitted request." else null
                throw error
            } catch (error: Exception) {
                // Never display raw network, provider, or filesystem errors.
                message = when (error) {
                    is ImageApiException -> error.message
                    is IllegalArgumentException -> safeValidationMessage(error)
                    is IOException -> "The image could not be read or saved. Check available storage and try again."
                    else -> "The operation could not be completed. Try another image."
                }
            } catch (_: OutOfMemoryError) {
                message = "There is not enough memory to process this image. Import a smaller image. The original is unchanged."
            } finally {
                generating = false
                busy = null
                task = null
            }
        }
    }

    private fun safeValidationMessage(error: IllegalArgumentException): String {
        val text = error.message.orEmpty()
        return if (text.startsWith("Select ") || text.startsWith("This image ") ||
            text.startsWith("The source image ") || text.startsWith("These corners ")) text
        else "The image could not be prepared. Select a valid area or import another image."
    }

    override fun onCleared() {
        task?.cancel()
        credential?.fill('\u0000')
        credential = null
        store.clear()
    }
}
