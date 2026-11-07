package com.revitvisualizer.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.revitvisualizer.app.imaging.ImagePoint
import com.revitvisualizer.app.imaging.ImageProcessor
import com.revitvisualizer.app.imaging.LocalImage
import com.revitvisualizer.app.imaging.Selection
import com.revitvisualizer.app.imaging.fittedSize
import com.revitvisualizer.app.storage.ImageStore
import com.revitvisualizer.app.ui.Comparison
import com.revitvisualizer.app.ui.FailureDialog
import com.revitvisualizer.app.ui.CredentialDialog
import com.revitvisualizer.app.advanced.EnvironmentSettings
import com.revitvisualizer.app.advanced.Place
import com.revitvisualizer.app.advanced.SunPosition
import com.revitvisualizer.app.advanced.Daylight
import com.revitvisualizer.app.advanced.NorthGuide
import com.revitvisualizer.app.advanced.SceneOrientation
import com.revitvisualizer.app.advanced.LightingAnalysisReport
import com.revitvisualizer.app.openai.OpenAiImages
import com.revitvisualizer.app.openai.ImageApiException
import com.revitvisualizer.app.ui.LightingAnalysisReview
import android.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device checks use synthetic images and placeholder credentials and never contact OpenAI. */
@RunWith(AndroidJUnit4::class)
class DeviceWorkflowTest {
    @get:Rule(order = 0) val cameraPermission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val model get() = ViewModelProvider(compose.activity)[VisualizerViewModel::class.java]

    private fun awaitText(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun fixture(width: Int = 320, height: Int = 200): LocalImage {
        val file = File(context.cacheDir, "device-test-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(85, 120, 105))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return ImageProcessor.describe(file)
    }

    private fun analysisFixture(ambiguous: Boolean = false): String {
        val scene = JSONObject("""{
            "orientation_status":"resolved",
            "north_interpretation":"North points toward the left glazed gable.",
            "sun_side":"Low behind the opposite end of the building.",
            "shadow_direction":"Toward the north gable along the ground plane.",
            "surfaces":[
              {"region":"north glazed gable","normal_azimuth_degrees":0,"normal_elevation_degrees":0,"occlusion":"clear"},
              {"region":"south wall","normal_azimuth_degrees":180,"normal_elevation_degrees":0,"occlusion":"unknown"}],
            "notes":["Synthetic test response, not a real model interpretation."]
        }""")
        if (ambiguous) scene.put("orientation_status", "ambiguous")
        return JSONObject().put("status", "completed").put("error", JSONObject.NULL).put("output", JSONArray()
            .put(JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", scene.toString()))))).toString()
    }

    @Test fun advancedAnalysisThenImageAndLightingReviewRunOnAndroid() {
        val input = fixture()
        val bytes = input.file.readBytes()
        val environment = EnvironmentSettings(place = Place("Ålesund", 62.47226, 6.1549, ZoneId.of("Europe/Oslo")),
            date = LocalDate.of(2026, 11, 7), northConfirmed = true).resolve()
        val guideFile = File(context.cacheDir, "north-test-${UUID.randomUUID()}.png")
        val outputFile = File(context.cacheDir, "image-test-${UUID.randomUUID()}.png")
        val guide = NorthGuide.create(input, environment.north, guideFile)
        val paths = CopyOnWriteArrayList<String>()
        var imageBody = ""
        lateinit var analysis: SceneOrientation
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            paths += path
            val response = if (path == "/v1/responses") analysisFixture() else {
                imageBody = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json", Base64.encodeToString(bytes, Base64.NO_WRAP)))).toString()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Local fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val output = runBlocking {
                OpenAiImages(client).edit(input, "local-test-placeholder".toCharArray(), outputFile, environment, guide,
                    onOrientationAnalysis = { analysis = it })
            }
            assertEquals(listOf("/v1/responses", "/v1/images/edits"), paths.toList())
            assertArrayEquals(bytes, input.file.readBytes())
            assertArrayEquals(bytes, output.file.readBytes())
            assertTrue(imageBody.contains("Back-facing the Sun: NO direct sunlight"))
            assertTrue(imageBody.contains("north glazed gable"))
            compose.runOnUiThread {
                compose.activity.setContent {
                    MaterialTheme { LightingAnalysisReview(LightingAnalysisReport(environment, analysis)) }
                }
            }
            compose.onNodeWithText("Review lighting analysis").performClick()
            compose.onNodeWithText("Lighting analysis").assertIsDisplayed()
            compose.onNodeWithText("Calculated Sun:", substring = true).assertExists()
            compose.onNodeWithText("NO direct sunlight", substring = true).assertExists()
            compose.onNodeWithText("Close").performClick()
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            listOf(input.file, guideFile, outputFile).forEach { it.delete() }
        }
    }

    @Test fun ambiguousAnalysisPreventsImageGenerationOnAndroid() {
        val input = fixture()
        val environment = EnvironmentSettings(place = Place("Ålesund", 62.47226, 6.1549, ZoneId.of("Europe/Oslo")),
            date = LocalDate.of(2026, 11, 7), northConfirmed = true).resolve()
        val guideFile = File(context.cacheDir, "north-test-${UUID.randomUUID()}.png")
        val outputFile = File(context.cacheDir, "never-generated-${UUID.randomUUID()}.png")
        val guide = NorthGuide.create(input, environment.north, guideFile)
        val paths = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Local fixture")
                .body(analysisFixture(ambiguous = true).toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val failure = runBlocking {
                runCatching { OpenAiImages(client).edit(input, "local-test-placeholder".toCharArray(), outputFile, environment, guide) }.exceptionOrNull()
            }
            assertTrue(failure is ImageApiException)
            assertTrue(failure!!.message!!.contains("No image generation was requested"))
            assertEquals(listOf("/v1/responses"), paths.toList())
            assertFalse(outputFile.exists())
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            listOf(input.file, guideFile, outputFile).forEach { it.delete() }
        }
    }

    @Test fun outputLimitedAnalysisReportsReasonAndPreventsImageGenerationOnAndroid() {
        val input = fixture()
        val environment = EnvironmentSettings(place = Place("Ålesund", 62.47226, 6.1549, ZoneId.of("Europe/Oslo")),
            date = LocalDate.of(2026, 11, 7), northConfirmed = true).resolve()
        val guideFile = File(context.cacheDir, "north-test-${UUID.randomUUID()}.png")
        val outputFile = File(context.cacheDir, "never-generated-${UUID.randomUUID()}.png")
        val guide = NorthGuide.create(input, environment.north, guideFile)
        val paths = CopyOnWriteArrayList<String>()
        // Even a completed-looking message must be rejected when the overall response is incomplete.
        val response = JSONObject(analysisFixture()).put("status", "incomplete")
            .put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
            .put("usage", JSONObject().put("output_tokens", 4000)
                .put("output_tokens_details", JSONObject().put("reasoning_tokens", 3950))).toString()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Local fixture")
                .header("x-request-id", "req_device_fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val failure = runBlocking {
                runCatching { OpenAiImages(client).edit(input, "local-test-placeholder".toCharArray(), outputFile, environment, guide) }.exceptionOrNull()
            }
            assertTrue(failure is ImageApiException)
            val message = failure!!.message!!
            listOf("ANALYSIS_OUTPUT_LIMIT", "max_output_tokens", "Output tokens: 4000", "Reasoning tokens: 3950",
                "Requested output budget: 12000", "req_device_fixture", "Analysis client request ID:",
                "No image generation was requested", "No automatic retry was made").forEach { assertTrue(message.contains(it)) }
            assertFalse(message.contains("local-test-placeholder"))
            assertEquals(listOf("/v1/responses"), paths.toList())
            assertFalse(outputFile.exists())
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            listOf(input.file, guideFile, outputFile).forEach { it.delete() }
        }
    }

    @Test fun optionalAdvancedAcceptsCoordinatesAndArrowThenInvalidatesItAfterCrop() {
        val image = fixture(800, 400)
        val bytes = image.file.readBytes()
        compose.runOnIdle { model.acceptCapture(image.file) }
        awaitText("Prepare image")
        compose.onNodeWithText("Prepare image").performClick()
        compose.onNodeWithContentDescription("Enable Advanced").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("Use coordinates").performScrollTo().performClick()
        compose.onNodeWithText("Place / environment name").performTextInput("Bø i Telemark")
        compose.onNodeWithText("Latitude (−90 to 90)").performScrollTo().performTextInput("59.41299")
        compose.onNodeWithText("Longitude (−180 to 180)").performScrollTo().performTextInput("9.0693")
        compose.onNodeWithText("IANA time zone or UTC").performScrollTo().performTextInput("Europe/Oslo")
        compose.activityRule.scenario.recreate()
        awaitText("Precise location")
        mapOf("Place / environment name" to "Bø i Telemark", "Latitude (−90 to 90)" to "59.41299",
            "Longitude (−180 to 180)" to "9.0693", "IANA time zone or UTC" to "Europe/Oslo").forEach { (label, expected) ->
            compose.onNodeWithText(label).performScrollTo().assert(SemanticsMatcher("Unconfirmed location field restored") {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
            })
        }
        compose.onNodeWithText("Use location").performClick()
        compose.onNodeWithText("Snow cover").performScrollTo()
        compose.onNodeWithContentDescription("Snow cover").performClick()
        compose.onNodeWithText("Place north arrow").performScrollTo().performClick()
        awaitText("Confirm north arrow")
        compose.onNodeWithContentDescription("North arrow rotation").performSemanticsAction(SemanticsActions.SetProgress) { it(90f) }
        compose.activityRule.scenario.recreate()
        awaitText("Confirm north arrow")
        compose.onNodeWithText("Arrow rotation: 90° on the image").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirm north arrow").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(model.environment.enabled)
            assertTrue(model.environment.northConfirmed)
            assertTrue(model.environment.snow)
            assertEquals(ZoneId.of("Europe/Oslo"), model.environment.place!!.zone)
            assertTrue(model.environment.north.head.x > model.environment.north.tail.x)
            assertEquals(model.environment.north.head.y, model.environment.north.tail.y, 0.0001f)
            assertArrayEquals(bytes, model.source!!.file.readBytes())
            model.select(Selection().moveCorner(0, ImagePoint(0.1f, 0.1f), false))
            assertFalse(model.environment.northConfirmed)
            assertEquals(null, model.northPreview)
            model.updateEnvironment(model.environment.copy(enabled = false, place = null))
            model.visualize()
        }
        awaitText("OpenAI API key") // Advanced off still reaches the existing standard flow.
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun solarOrientationValidatesDstButRemovingNorthAllowsWeatherOnly() {
        val image = fixture()
        compose.runOnIdle { model.acceptCapture(image.file) }
        awaitText("Prepare image")
        compose.onNodeWithText("Prepare image").performClick()
        compose.runOnIdle {
            model.updateEnvironment(EnvironmentSettings(enabled = true,
                place = Place("Bø", 59.41299, 9.0693, ZoneId.of("Europe/Oslo")),
                date = LocalDate.of(2026, 10, 25), time = LocalTime.of(2, 30), northConfirmed = true))
            model.visualize()
            assertFalse(model.showCredentialDialog)
        }
        awaitText("This local time occurs twice. Select its UTC offset below the time.")
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("UTC+01:00").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("2026-10-25T01:30:00Z", model.environment.localDateTime().toInstant().toString())
            model.visualize()
            assertTrue(model.showCredentialDialog)
        }
        awaitText("OpenAI API key")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Remove north arrow").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(model.environment.northConfirmed)
            // An invalid DST time is irrelevant without solar orientation.
            model.updateEnvironment(model.environment.copy(offset = null))
            model.visualize()
            assertTrue(model.showCredentialDialog)
        }
        awaitText("OpenAI API key")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Remove location").performScrollTo().performClick()
        compose.onNodeWithText("Rain").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Snow cover").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(null, model.environment.place)
            assertTrue(model.environment.snow)
            model.visualize()
            assertTrue(model.showCredentialDialog)
        }
        awaitText("OpenAI API key")
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun nrelSolarLibraryRunsOnAndroidIncludingExactPoles() {
        val place = Place("NREL reference", 39.742476, -105.1786, ZoneOffset.ofHours(-7), 1830.14)
        val sun = SunPosition.calculate(place, ZonedDateTime.of(2003, 10, 17, 12, 30, 30, 0, place.zone))
        assertEquals(194.34024, sun.azimuth, 0.005)
        assertEquals(39.872046, sun.elevation, 0.005)
        val pole = Place("North Pole", 90.0, 0.0, ZoneOffset.UTC)
        val winter = SunPosition.calculate(pole, ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, pole.zone))
        assertEquals(Daylight.Night, winter.daylight)
    }

    @Test fun cameraCropAndKeyDialogPreserveTheOriginalAcrossRecreation() {
        val shutter = compose.onNodeWithContentDescription("Take photo")
        compose.waitUntil(30_000) { runCatching { shutter.assertIsEnabled() }.isSuccess }
        shutter.performClick()
        awaitText("Prepare image")
        val source = model.source!!
        val hash = MessageDigest.getInstance("SHA-256").digest(source.file.readBytes())
        compose.onNodeWithText("Prepare image").performClick()
        compose.onNodeWithContentDescription("Correct perspective").performClick()
        val description = "Crop selection. Drag the four corner handles to frame the Revit viewport."
        try {
            compose.waitUntil(10_000) {
                compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.height >
                    100 * context.resources.displayMetrics.density
            }
        } catch (error: Throwable) {
            val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            if (directory != null) {
                File(directory).mkdirs()
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(directory, "prepare-layout.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(), error)
        }
        compose.onNodeWithContentDescription(description).performTouchInput {
            val inset = 16 * context.resources.displayMetrics.density
            val (width, height) = fittedSize(source.width, source.height, this.width - inset * 2, this.height - inset * 2)
            val corner = Offset((this.width - width) / 2, (this.height - height) / 2)
            swipe(corner, corner + Offset(width * 0.15f, height * 0.15f), 600)
        }
        compose.runOnIdle { assertTrue(model.selection.corners[0].x > 0.05f) }
        compose.onNode(hasSetTextAction()).performTextInput("16:9")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText("Visualize").performScrollTo().performClick()
        awaitText("OpenAI API key")
        val keyField = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        keyField.assertIsDisplayed()
        keyField.performTextInput("local-test-placeholder")
        compose.activityRule.scenario.recreate()
        awaitText("OpenAI API key")
        keyField.assert(
            SemanticsMatcher("Unsaved key field is empty") {
                it.config[SemanticsProperties.EditableText].text.isEmpty()
            },
        )
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(Stage.Prepare, model.stage)
            assertFalse(model.hasCredential)
            assertTrue(model.selection.corners[0].x > 0.05f)
        }
        assertArrayEquals(hash, MessageDigest.getInstance("SHA-256").digest(source.file.readBytes()))
    }

    @Test fun photoPickerLaunchesAndContentUriImportRetainsOriginalBytes() = runBlocking {
        val source = fixture()
        val bytes = source.file.readBytes()
        val uri = ImageStore(context).export(source)
        try {
            compose.onNodeWithText("Import image").performClick()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            compose.waitUntil(15_000) {
                val name = device.currentPackageName.orEmpty()
                name.contains("providers.media") || name.contains("photopicker") || name.contains("documentsui")
            }
            device.pressBack()
            compose.waitForIdle()
            // Import this app-owned gallery URI explicitly; do not select unrelated emulator photos.
            compose.runOnIdle { model.import(uri) }
            awaitText("Prepare image")
            assertArrayEquals(bytes, model.source!!.file.readBytes())
            assertArrayEquals(bytes, source.file.readBytes())
        } finally {
            context.contentResolver.delete(uri, null, null)
            source.file.delete()
        }
    }

    @Test fun nativePerspectiveCorrectionAndMediaStoreExportKeepExactPngBytes() = runBlocking {
        val source = fixture(640, 400)
        val sourceBytes = source.file.readBytes()
        val file = File(context.cacheDir, "prepared-test-${UUID.randomUUID()}.png")
        var galleryUri: android.net.Uri? = null
        try {
            val corrected = ImageProcessor.prepare(source, Selection(listOf(
                ImagePoint(0.1f, 0.1f), ImagePoint(0.9f, 0.05f),
                ImagePoint(0.85f, 0.9f), ImagePoint(0.2f, 0.95f),
            )), 16f / 9, file)
            assertEquals(16f / 9, corrected.width.toFloat() / corrected.height, 0.01f)
            galleryUri = ImageStore(context).export(corrected)
            val exported = context.contentResolver.openInputStream(galleryUri)!!.use { it.readBytes() }
            assertArrayEquals(file.readBytes(), exported)
            assertArrayEquals(sourceBytes, source.file.readBytes())
        } finally {
            galleryUri?.let { context.contentResolver.delete(it, null, null) }
            source.file.delete()
            file.delete()
        }
    }

    @Test fun comparisonSupportsOverlayOpacitySharedZoomAndReset() {
        val original = fixture(320, 200)
        val result = fixture(320, 320)
        try {
            compose.runOnUiThread {
                compose.activity.setContent {
                    MaterialTheme { Comparison(original, result, Modifier.fillMaxSize()) }
                }
            }
            compose.onNodeWithText("Different aspect ratios: images are fitted without stretching.").assertIsDisplayed()
            compose.onNodeWithText("Original").performClick()
            compose.onNodeWithText("Result").performClick()
            compose.onNodeWithText("Overlay").performClick()
            compose.onNodeWithContentDescription("Original overlay opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
            compose.onNodeWithText("Original opacity: 70%").assertIsDisplayed()
            val description = "Original and result comparison. Pinch to zoom and drag to pan both images together."
            compose.waitUntil(15_000) {
                compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(description).performTouchInput {
                pinch(center - Offset(30f, 0f), center - Offset(100f, 0f),
                    center + Offset(30f, 0f), center + Offset(100f, 0f), 600)
            }
            assertTrue(compose.onAllNodesWithText("Pinch to zoom · 1.0×").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText("Reset view").performClick()
            compose.onNodeWithText("Pinch to zoom · 1.0×").assertIsDisplayed()
        } finally {
            original.file.delete()
            result.file.delete()
        }
    }

    @Test fun networkFailureRemainsReadableUntilExplicitlyDismissed() {
        val visible = mutableStateOf(true)
        val message = "The HTTP/2 image stream was reset before the final image arrived.\n\n" +
            "Stage: waiting for OpenAI. Elapsed: 64s. Diagnostic: HTTP2_STREAM_RESET.\n" +
            "Connection: h2. Response: HTTP 200. Read: 12 bytes.\n" +
            "Client request ID: 123e4567-e89b-12d3-a456-426614174000.\n" +
            "OpenAI request ID: req_local_fixture_123.\n" +
            "No automatic retry was made. A submitted request may still finish and be billed."
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme { if (visible.value) FailureDialog(message) { visible.value = false } }
            }
        }
        compose.onNodeWithText("Could not complete action").assertIsDisplayed()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText(message).performTouchInput { swipeUp() }
        compose.waitForIdle()
        val scroll = compose.onNodeWithText(message).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scroll.value() > 0f)
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertTrue(compose.onAllNodesWithText(message).fetchSemanticsNodes().isEmpty())
    }

    @Test fun rememberChoiceIsOptionalAndSubmittedWithAMaskedKey() {
        var submitted: Pair<String, Boolean>? = null
        val visible = mutableStateOf(true)
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    if (visible.value) CredentialDialog(onDismiss = { visible.value = false }, onSubmit = { value, remember ->
                        submitted = value to remember
                        visible.value = false
                    })
                }
            }
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextInput("local-test-placeholder")
        compose.onNode(isToggleable()).performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Stored encrypted on this device. Clear key removes the saved copy.").assertIsDisplayed()
        // This isolated dialog callback cannot launch a generation or call OpenAI.
        compose.onNodeWithText("Visualize").performClick()
        compose.runOnIdle { assertEquals("local-test-placeholder" to true, submitted) }
    }
}
