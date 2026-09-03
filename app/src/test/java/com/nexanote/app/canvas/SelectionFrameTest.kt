package com.nexanote.app.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comportamiento del marco de selección táctil y de la paleta de la Fase 13,
 * complementando a [SelectionInputTest]. Puro JVM: aritmética y datos, sin
 * Android ni núcleo.
 */
class SelectionFrameTest {

    private fun hit(id: String, x: Float, y: Float, w: Float, h: Float) =
        SceneHit(id, "Shape", Rect(Offset(x, y), Size(w, h)), fillable = false)

    @Test
    fun marqueeAcceptsNegativeDocumentCoordinates() {
        val area = SelectionInput.marquee(-40f, -10f, -100f, -60f)
        assertEquals(-100f, area.left, 0f)
        assertEquals(-60f, area.top, 0f)
        assertEquals(60f, area.width, 0f)
        assertEquals(50f, area.height, 0f)
    }

    @Test
    fun marqueeDragThresholdIsInclusiveOnASingleAxis() {
        val belowBoth = SelectionInput.marquee(0f, 0f, 5.9f, 5.9f)
        assertFalse(SelectionInput.isMarqueeDrag(belowBoth))
        val atThresholdOnX = SelectionInput.marquee(0f, 0f, SelectionInput.MIN_MARQUEE_EXTENT, 1f)
        assertTrue(SelectionInput.isMarqueeDrag(atThresholdOnX))
    }

    @Test
    fun duplicateOffsetIsAPositiveNudge() {
        assertTrue(SelectionInput.DUPLICATE_OFFSET > 0f)
    }

    @Test
    fun refreshingAnEmptySelectionReturnsTheSharedEmptyInstance() {
        assertSame(Selection.Empty, Selection.Empty.refreshed(listOf(hit("a", 0f, 0f, 1f, 1f))))
    }

    @Test
    fun refreshedBoxTracksASelectionThatMovedFarFromTheOrigin() {
        val selection = Selection(listOf("a"), Rect(Offset(0f, 0f), Size(10f, 10f)))
        val refreshed = selection.refreshed(listOf(hit("a", -500f, 300f, 20f, 40f)))
        assertEquals(Rect(Offset(-500f, 300f), Size(20f, 40f)), refreshed.bounds)
    }

    @Test
    fun paletteEntriesAreOpaqueDocumentColoursInRange() {
        val all = NexaPalette.Swatches + NexaPalette.Fills
        assertTrue(all.isNotEmpty())
        for (swatch in all) {
            assertTrue(swatch.label.isNotBlank())
            for (channel in intArrayOf(swatch.color.r, swatch.color.g, swatch.color.b)) {
                assertTrue(channel in 0..255)
            }
            assertEquals(255, swatch.color.a)
        }
    }

    @Test
    fun onlyHexDigitsSurviveIdSerialisationEvenForAnEmptyResult() {
        assertEquals("""[""]""", SelectionInput.toIdsJson(listOf("\"; zzz")))
        assertEquals("[]", SelectionInput.toIdsJson(emptyList()))
    }
}
