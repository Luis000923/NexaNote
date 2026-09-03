package com.nexanote.app.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aritmética de zoom/pan del lienzo (pura, sin Android). */
class CanvasTransformTest {

    @Test
    fun identityMapsModelToItself() {
        val (x, y) = CanvasTransform().modelToScreen(10f, 20f)
        assertEquals(10f, x, 1e-4f)
        assertEquals(20f, y, 1e-4f)
    }

    @Test
    fun panShiftsOffsetOnly() {
        val t = CanvasTransform(scale = 2f).applyGesture(panX = 5f, panY = -3f, zoom = 1f, pivotX = 0f, pivotY = 0f)
        assertEquals(2f, t.scale, 1e-4f)
        assertEquals(5f, t.offsetX, 1e-4f)
        assertEquals(-3f, t.offsetY, 1e-4f)
    }

    @Test
    fun zoomKeepsPivotPointStable() {
        val t = CanvasTransform()
        val pivotX = 300f
        val pivotY = 400f
        val before = t.modelToScreen(pivotX, pivotY) // pivote está en el punto de modelo (300,400) al inicio
        val zoomed = t.zoomBy(2f, pivotX, pivotY)
        // El punto de modelo que estaba bajo el pivote sigue proyectándose al pivote.
        val modelUnderPivotX = (pivotX - t.offsetX) / t.scale
        val modelUnderPivotY = (pivotY - t.offsetY) / t.scale
        val (sx, sy) = zoomed.modelToScreen(modelUnderPivotX, modelUnderPivotY)
        assertEquals(pivotX, sx, 1e-3f)
        assertEquals(pivotY, sy, 1e-3f)
        assertEquals(2f, zoomed.scale, 1e-4f)
        assertEquals(before.first, pivotX, 1e-4f)
    }

    @Test
    fun scaleIsClamped() {
        val zoomedWayIn = CanvasTransform().zoomBy(1000f, 0f, 0f)
        assertEquals(CanvasTransform.MAX_SCALE, zoomedWayIn.scale, 1e-4f)
        val zoomedWayOut = CanvasTransform().zoomBy(0.00001f, 0f, 0f)
        assertEquals(CanvasTransform.MIN_SCALE, zoomedWayOut.scale, 1e-4f)
    }

    @Test
    fun fitToViewportCentersContent() {
        val t = CanvasTransform.fitToViewport(
            contentW = 200f, contentH = 400f, viewportW = 400f, viewportH = 400f, margin = 1f,
        )
        // Limitante es la altura: 400/400 = 1.0
        assertEquals(1f, t.scale, 1e-4f)
        // Centrado horizontal: (400 - 200*1)/2 = 100
        assertEquals(100f, t.offsetX, 1e-4f)
        assertEquals(0f, t.offsetY, 1e-4f)
    }

    @Test
    fun fitToViewportHandlesDegenerateInput() {
        val t = CanvasTransform.fitToViewport(0f, 0f, 100f, 100f)
        assertTrue(t == CanvasTransform())
    }
}
