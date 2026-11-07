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
import com.revitvisualizer.app.advanced.EnvironmentSettings
import com.revitvisualizer.app.advanced.EnvironmentException
import com.revitvisualizer.app.advanced.NorthArrow
import com.revitvisualizer.app.advanced.NorthGuide
import com.revitvisualizer.app.advanced.Place
import com.revitvisualizer.app.advanced.PlaceCandidate
import com.revitvisualizer.app.advanced.PlaceSearch
import com.revitvisualizer.app.advanced.ManualPlaceDraft
import com.revitvisualizer.app.advanced.LightingAnalysisReport
import com.revitvisualizer.app.openai.ImageApiException
import com.revitvisualizer.app.openai.OpenAiImages
import com.revitvisualizer.app.storage.ImageStore
import com.revitvisualizer.app.storage.DeviceCredentialStore
import com.revitvisualizer.app.storage.CredentialStorageException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class Stage { Capture, Prepare, Result }

class VisualizerViewModel @JvmOverloads constructor(
    application: Application,
    private val credentialStore: DeviceCredentialStore = DeviceCredentialStore(application),
) : AndroidViewModel(application) {
    private val store = ImageStore(application)
    private val api = OpenAiImages()
    private val places = PlaceSearch(application)
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
    var failure by mutableStateOf<String?>(null)
        private set
    var hasCredential by mutableStateOf(false)
        private set
    var hasSavedCredential by mutableStateOf(credentialStore.hasSavedCredential)
        private set
    var showCredentialDialog by mutableStateOf(false)
        private set
    var environment by mutableStateOf(EnvironmentSettings())
        private set
    var placeQuery by mutableStateOf("")
        private set
    var placeCandidates by mutableStateOf<List<PlaceCandidate>>(emptyList())
        private set
    var manualPlaceDraft by mutableStateOf<ManualPlaceDraft?>(null)
        private set
    var lightingAnalysis by mutableStateOf<LightingAnalysisReport?>(null)
        private set
    var northPreview by mutableStateOf<LocalImage?>(null)
        private set
    var showNorthEditor by mutableStateOf(false)
        private set

    init {
        if (hasSavedCredential) runTask("Unlocking saved API key…") {
            val saved = credentialStore.load()
            try {
                withContext(Dispatchers.Main.immediate) {
                    ensureActive()
                    credential = saved?.copyOf()
                    hasCredential = credential != null
                }
            } finally { saved?.fill('\u0000') }
        }
    }

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
        environment = EnvironmentSettings()
        placeQuery = ""
        placeCandidates = emptyList()
        manualPlaceDraft = null
        lightingAnalysis = null
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
        environment = EnvironmentSettings()
        placeQuery = ""
        placeCandidates = emptyList()
        manualPlaceDraft = null
        lightingAnalysis = null
    }

    fun back() {
        if (busy != null) return
        stage = when (stage) {
            Stage.Result -> Stage.Prepare
            Stage.Prepare -> Stage.Capture
            Stage.Capture -> Stage.Capture
        }
    }

    private fun invalidateNorth() {
        environment = environment.copy(northConfirmed = false)
        northPreview?.file?.delete()
        northPreview = null
        showNorthEditor = false
    }

    fun updateEnvironment(value: EnvironmentSettings) { if (busy == null) environment = value }
    fun updatePlaceQuery(value: String) {
        if (busy != null) return
        placeQuery = value.take(200)
        environment = environment.copy(place = null, offset = null)
        placeCandidates = emptyList()
    }
    fun findPlaces() = runTask("Finding places…") { placeCandidates = places.search(placeQuery) }
    fun choosePlace(value: PlaceCandidate) = runTask("Resolving the place's time zone…") {
        environment = environment.copy(place = places.resolve(value), offset = null)
        placeCandidates = emptyList()
    }
    fun useManualPlace(value: Place) {
        if (busy != null) return
        environment = environment.copy(place = value, offset = null)
        placeCandidates = emptyList()
        placeQuery = value.name
        manualPlaceDraft = null
    }
    fun openManualPlace() {
        if (busy != null) return
        val place = environment.place
        manualPlaceDraft = ManualPlaceDraft(place?.name ?: placeQuery,
            place?.latitude?.toString().orEmpty(), place?.longitude?.toString().orEmpty(),
            place?.zone?.id.orEmpty(), place)
    }
    fun updateManualPlaceDraft(value: ManualPlaceDraft) {
        if (busy == null && manualPlaceDraft != null) manualPlaceDraft = value
    }
    fun dismissManualPlace() { if (busy == null) manualPlaceDraft = null }
    fun openNorthEditor() {
        val image = source ?: return
        val selected = selection
        val aspect = if (perspective) parseAspectRatio(aspectText) else null
        if (perspective && aspectText.isNotBlank() && aspect == null) {
            message = "Enter the viewport aspect ratio before placing the north arrow."
            return
        }
        runTask("Preparing north arrow preview…") {
            val file = store.newFile("north-preview", "png")
            try {
                val preview = ImageProcessor.prepare(image, selected, aspect, file)
                ensureActive()
                northPreview?.file?.delete()
                northPreview = preview
                showNorthEditor = true
            } catch (error: Throwable) { file.delete(); throw error }
        }
    }
    fun confirmNorth(value: NorthArrow) {
        val preview = northPreview ?: return
        if (!value.valid(preview.width, preview.height)) return
        environment = environment.copy(north = value, northConfirmed = true)
        showNorthEditor = false
    }
    fun closeNorthEditor() { showNorthEditor = false }

    fun select(value: Selection) { if (busy == null && selection != value) { invalidateNorth(); selection = value } }
    fun setPerspectiveMode(value: Boolean) {
        if (busy != null) return
        invalidateNorth()
        perspective = value
        selection = Selection()
        aspectText = ""
    }
    fun setAspect(value: String) { if (busy == null && aspectText != value) { invalidateNorth(); aspectText = value.take(24) } }
    fun resetSelection() { invalidateNorth(); selection = Selection(); aspectText = ""; perspective = false }
    fun dismissMessage() { message = null }
    fun dismissFailure() { failure = null }
    fun reportCameraError() { message = "The camera is unavailable. Check camera access or import an image." }

    fun visualize() {
        if (busy != null || source == null) return
        try { if (environment.enabled) environment.resolve() }
        catch (error: EnvironmentException) { failure = error.message; return }
        if (!hasCredential) { showCredentialDialog = true; return }
        generate()
    }

    fun dismissCredentialDialog() { showCredentialDialog = false }

    fun submitCredential(value: String, rememberOnDevice: Boolean = false) {
        if (busy != null) return
        if (value.isBlank() || value.length > 4096 || value.any { it.code !in 33..126 }) {
            message = "Enter a valid API key without spaces or line breaks."
            return
        }
        showCredentialDialog = false
        val submitted = value.toCharArray()
        fun useCredential() {
            credential?.fill('\u0000')
            credential = submitted.copyOf()
            hasCredential = true
        }
        if (!rememberOnDevice && !hasSavedCredential) {
            try { useCredential() } finally { submitted.fill('\u0000') }
            generate()
        } else {
            runTask("Updating saved API key…", onSuccess = ::generate) {
                try {
                    if (rememberOnDevice) credentialStore.save(submitted) else credentialStore.clear()
                    withContext(Dispatchers.Main.immediate) {
                        ensureActive()
                        useCredential()
                    }
                } finally { submitted.fill('\u0000') }
            }
        }
    }

    fun clearCredential() {
        if (busy != null && !generating) return
        val previous = task
        if (generating) task?.cancel()
        credential?.fill('\u0000')
        credential = null
        hasCredential = false
        showCredentialDialog = false
        if (hasSavedCredential) viewModelScope.launch {
            previous?.join()
            runTask("Removing saved API key…") {
                credentialStore.clear()
                hasSavedCredential = false
            }
        }
    }

    private fun generate() {
        val image = source ?: return
        val settings = try { if (environment.enabled) environment.resolve() else null }
        catch (error: EnvironmentException) { failure = error.message; return }
        val aspect = if (perspective) parseAspectRatio(aspectText) else null
        if (perspective && aspectText.isNotBlank() && aspect == null) {
            message = "Enter the viewport aspect ratio as width:height, for example 16:9."
            return
        }
        val selected = selection
        val sessionCredential = credential?.copyOf() ?: return
        lightingAnalysis = null
        generating = true
        runTask("Preparing full-resolution image…") {
            val inputFile = store.newFile("prepared", "png")
            val outputFile = store.newFile("result", "png")
            val guideFile = if (settings != null) store.newFile("north-guide", "png") else null
            try {
                val input = ImageProcessor.prepare(image, selected, aspect, inputFile)
                val guide = guideFile?.let { NorthGuide.create(input, settings!!.north, it) }
                ensureActive()
                val generationContext = coroutineContext
                val output = api.edit(input, sessionCredential, outputFile, settings, guide,
                    onOrientationAnalysis = { analysis ->
                        if (generationContext.isActive) lightingAnalysis = LightingAnalysisReport(settings!!, analysis)
                    },
                ) { phase ->
                    if (generationContext.isActive) busy = phase.description
                }
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
            } finally { guideFile?.delete(); sessionCredential.fill('\u0000') }
        }
    }

    fun cancelGeneration() { if (generating) task?.cancel() }

    fun save() {
        val image = result ?: return
        runTask("Saving PNG…") {
            store.export(image)
            message = "Image saved to Pictures / Vizruna."
        }
    }

    private fun runTask(label: String, onSuccess: () -> Unit = {}, work: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        if (busy != null) return
        busy = label
        message = null
        failure = null
        task = viewModelScope.launch {
            var completed = false
            try {
                withContext(Dispatchers.IO, work)
                completed = true
            } catch (error: CancellationException) {
                message = if (generating) "Generation cancelled locally. OpenAI may still finish and bill a submitted request." else null
                throw error
            } catch (error: Exception) {
                // Never display raw network, provider, or filesystem errors.
                failure = when (error) {
                    is ImageApiException -> error.message
                    is CredentialStorageException -> error.message
                    is EnvironmentException -> error.message
                    is IllegalArgumentException -> safeValidationMessage(error)
                    is IOException -> "The image could not be read or saved. Check available storage and try again."
                    else -> "The operation could not be completed. Try another image."
                }
            } catch (_: OutOfMemoryError) {
                failure = "There is not enough memory to process this image. Import a smaller image. The original is unchanged."
            } finally {
                generating = false
                busy = null
                task = null
                hasSavedCredential = credentialStore.hasSavedCredential
            }
            if (completed) onSuccess()
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
