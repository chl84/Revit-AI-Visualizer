package com.revitvisualizer.app.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    @Test fun rectangularCropKeepsAlignedEdges() {
        val selection = Selection().moveCorner(0, ImagePoint(0.25f, 0.2f), perspective = false)
        assertEquals(ImagePoint(1f, 0.2f), selection.corners[1])
        assertEquals(ImagePoint(0.25f, 1f), selection.corners[3])
        assertEquals(1200 to 800, selection.outputSize(1600, 1000))
    }

    @Test fun invalidCornerDragKeepsPreviousSelection() {
        val selection = Selection()
        assertEquals(selection, selection.moveCorner(0, ImagePoint(1f, 1f), perspective = true))
        assertFalse(Selection(listOf(ImagePoint(0f, 0f), ImagePoint(1f, 1f),
            ImagePoint(1f, 0f), ImagePoint(0f, 1f))).isValid())
    }

    @Test fun perspectiveSelectionRetainsIndependentCorners() {
        val selected = Selection().moveCorner(0, ImagePoint(0.1f, 0.2f), perspective = true)
        assertTrue(selected.isValid())
        assertEquals(ImagePoint(1f, 0f), selected.corners[1])
        val (width, height) = selected.outputSize(1600, 1000, 16f / 9)
        assertEquals(16f / 9, width.toFloat() / height, 0.002f)
    }

    @Test fun malformedRatiosAndNonFiniteCornersAreRejected() {
        listOf("", "NaN:9", "Infinity:1", "16:0", "-1:2", "100:1", "16/9").forEach { assertNull(parseAspectRatio(it)) }
        assertEquals(16f / 9, parseAspectRatio(" 16 : 9 ")!!, 0.0001f)
        assertFalse(Selection().copy(corners = listOf(ImagePoint(Float.NaN, 0f)) + Selection.fullFrame.drop(1)).isValid())
    }

    @Test fun comparisonFitsBothAspectRatiosWithoutStretching() {
        val landscape = fittedSize(1600, 900, 1000f, 1000f)
        val portrait = fittedSize(900, 1600, 1000f, 1000f)
        assertEquals(1000f, landscape.first, 0.01f)
        assertEquals(562.5f, landscape.second, 0.01f)
        assertEquals(562.5f, portrait.first, 0.01f)
        assertEquals(1000f, portrait.second, 0.01f)
    }
}
