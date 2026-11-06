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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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

/** Device checks use synthetic images and never confirm the API-key dialog or contact OpenAI. */
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

    @Test fun cameraCropAndKeyDialogPreserveTheOriginalAcrossRecreation() {
        val shutter = compose.onNodeWithContentDescription("Take photo")
        compose.waitUntil(30_000) { runCatching { shutter.assertIsEnabled() }.isSuccess }
        shutter.performClick()
        awaitText("Prepare image")
        val source = model.source!!
        val hash = MessageDigest.getInstance("SHA-256").digest(source.file.readBytes())
        compose.onNodeWithText("Prepare image").performClick()
        compose.onNode(isToggleable()).performClick()
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
}
