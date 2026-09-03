package com.nexanote.app.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aritmética y serialización de la selección ([SelectionInput], [Selection]).
 * Puro JVM: no toca Android ni el núcleo.
 */
class SelectionInputTest {

    private fun hit(id: String, x: Float, y: Float, w: Float, h: Float, fillable: Boolean = false) =
        SceneHit(id, "Shape", Rect(Offset(x, y), Size(w, h)), fillable)

    @Test
    fun marqueeNormalizesADragInAnyDirection() {
        val forward = SelectionInput.marquee(10f, 20f, 40f, 60f)
        val backward = SelectionInput.marquee(40f, 60f, 10f, 20f)
        assertEquals(forward, backward)
        assertEquals(10f, forward.left, 0f)
        assertEquals(20f, forward.top, 0f)
        assertEquals(30f, forward.width, 0f)
        assertEquals(40f, forward.height, 0f)
    }

    @Test
    fun aTinyDragIsNotAMarquee() {
        assertFalse(SelectionInput.isMarqueeDrag(SelectionInput.marquee(0f, 0f, 2f, 2f)))
        assertTrue(SelectionInput.isMarqueeDrag(SelectionInput.marquee(0f, 0f, 0f, 20f)))
    }

    @Test
    fun areaSerializesAsTheCoreExpects() {
        val json = SelectionInput.toAreaJson(Rect(Offset(1f, 2f), Size(3f, 4f)))
        assertEquals("""{"x":1.0,"y":2.0,"width":3.0,"height":4.0}""", json)
    }

    @Test
    fun idsAreQuotedAndStrippedOfAnythingButHex() {
        // Un id manipulado no puede cerrar la cadena ni inyectar más JSON.
        val json = SelectionInput.toIdsJson(listOf("00ff", """a"],"x":["b"""))
        assertEquals("""["00ff","ab"]""", json)
    }

    @Test
    fun colorTargetsSerializeSeparately() {
        assertEquals(
            """{"target":"stroke","color":{"r":198,"g":40,"b":40,"a":255}}""",
            SelectionInput.toStrokeColorJson(StrokeColor(198, 40, 40)),
        )
        assertEquals("""{"target":"fill","color":null}""", SelectionInput.toFillColorJson(null))
    }

    @Test
    fun refreshedDropsVanishedElementsAndRecomputesTheBox() {
        val selection = Selection(listOf("a", "b"), Rect(Offset(0f, 0f), Size(10f, 10f)))
        val refreshed = selection.refreshed(
            listOf(hit("a", 100f, 100f, 20f, 20f), hit("c", 0f, 0f, 5f, 5f)),
        )
        assertEquals(listOf("a"), refreshed.ids)
        assertEquals(Rect(Offset(100f, 100f), Size(20f, 20f)), refreshed.bounds)
    }

    @Test
    fun refreshedEmptiesWhenNothingSurvives() {
        val selection = Selection(listOf("a"), Rect(Offset(0f, 0f), Size(10f, 10f)))
        val refreshed = selection.refreshed(listOf(hit("z", 0f, 0f, 1f, 1f)))
        assertTrue(refreshed.isEmpty)
        assertNull(refreshed.bounds)
    }

    @Test
    fun refreshedBoxCoversEveryRemainingElement() {
        val selection = Selection(listOf("a", "b"), null)
        val refreshed = selection.refreshed(
            listOf(hit("a", 0f, 0f, 10f, 10f), hit("b", 50f, 20f, 10f, 30f)),
        )
        assertEquals(Rect(Offset(0f, 0f), Size(60f, 50f)), refreshed.bounds)
    }

    @Test
    fun fillabilityComesFromTheCorePublishedHits() {
        val selection = Selection(listOf("a"), null)
        assertFalse(selection.hasFillable(listOf(hit("a", 0f, 0f, 1f, 1f, fillable = false))))
        assertTrue(selection.hasFillable(listOf(hit("a", 0f, 0f, 1f, 1f, fillable = true))))
        // Un elemento rellenable que no está seleccionado no habilita el relleno.
        assertFalse(selection.hasFillable(listOf(hit("z", 0f, 0f, 1f, 1f, fillable = true))))
    }
}
