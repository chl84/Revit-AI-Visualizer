package com.revitvisualizer.app.advanced

import androidx.lifecycle.ViewModelProvider
import com.revitvisualizer.app.MainActivity
import com.revitvisualizer.app.VisualizerViewModel
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ManualPlaceDraftTest {
    @Test fun unconfirmedFieldsAndValidationErrorSurviveActivityRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val model = ViewModelProvider(controller.get())[VisualizerViewModel::class.java]
            model.openManualPlace()
            val draft = ManualPlaceDraft("Bø i Telemark", "59.41299", "9.0693", "Europe/Oslo",
                error = "Validation fixture")
            model.updateManualPlaceDraft(draft)
            assertNull(model.environment.place)
            controller.recreate()
            val restored = ViewModelProvider(controller.get())[VisualizerViewModel::class.java]
            assertSame(model, restored)
            assertEquals(draft, restored.manualPlaceDraft)
            assertNull(restored.environment.place)
            restored.dismissManualPlace()
            assertNull(restored.manualPlaceDraft)
            restored.openManualPlace()
            assertEquals("", restored.manualPlaceDraft!!.name)
            assertNull(restored.manualPlaceDraft!!.error)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun confirmationAndNewImageClearDraftWhileCancelPreservesSelectedPlace() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val model = ViewModelProvider(controller.get())[VisualizerViewModel::class.java]
            val place = Place("Bø", 59.41299, 9.0693, ZoneId.of("Europe/Oslo"), 67.0)
            model.useManualPlace(place)
            assertNull(model.manualPlaceDraft)
            model.openManualPlace()
            assertEquals(place, model.manualPlaceDraft!!.initialPlace)
            model.updateManualPlaceDraft(model.manualPlaceDraft!!.copy(name = "Unconfirmed change"))
            model.dismissManualPlace()
            assertEquals(place, model.environment.place)
            model.openManualPlace()
            assertEquals("Bø", model.manualPlaceDraft!!.name)
            model.retake()
            assertNull(model.manualPlaceDraft)
            assertNull(model.environment.place)
        } finally { controller.pause().stop().destroy() }
    }
}
