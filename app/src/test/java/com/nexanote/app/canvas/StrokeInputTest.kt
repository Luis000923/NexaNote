package com.nexanote.app.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Procesamiento de los puntos de un trazo del stylus: proyección al espacio del
 * documento, normalización de presión y serialización al formato del núcleo Rust.
 * Lógica pura, sin Android.
 */
class StrokeInputTest {

    @Test
    fun screenPointsAreProjectedIntoDocumentSpace() {
        // screen = model * 2 + (100, 50)  ->  model = (screen - offset) / 2
        val transform = CanvasTransform(scale = 2f, offsetX = 100f, offsetY = 50f)
        val gesture = StrokeGesture(transform, startUptimeMs = 1_000L)

        gesture.addScreenPoint(screenX = 300f, screenY = 250f, pressure = 0.7f, uptimeMs = 1_016L)
        gesture.addScreenPoint(screenX = 500f, screenY = 450f, pressure = 0.9f, uptimeMs = 1_032L)

        val first = gesture.samples[0]
        assertEquals(100f, first.x, 1e-3f)
        assertEquals(100f, first.y, 1e-3f)
        assertEquals(0.7f, first.pressure, 1e-3f)
        assertEquals(16L, first.timestampMs)

        val second = gesture.samples[1]
        assertEquals(200f, second.x, 1e-3f)
        assertEquals(200f, second.y, 1e-3f)
        assertEquals(32L, second.timestampMs)
    }

    @Test
    fun pressureIsClampedWithFallbackForFingerInput() {
        assertEquals(0.5f, StrokeGesture.normalizePressure(0f), 1e-6f)
        assertEquals(0.5f, StrokeGesture.normalizePressure(Float.NaN), 1e-6f)
        assertEquals(0.5f, StrokeGesture.normalizePressure(-1f), 1e-6f)
        assertEquals(1f, StrokeGesture.normalizePressure(3.5f), 1e-6f)
        assertEquals(0.05f, StrokeGesture.normalizePressure(0.01f), 1e-6f)
        assertEquals(0.6f, StrokeGesture.normalizePressure(0.6f), 1e-6f)
    }

    @Test
    fun gestureNeedsAtLeastOneSampleToBeDrawable() {
        val gesture = StrokeGesture(CanvasTransform(), startUptimeMs = 0L)
        assertFalse(gesture.isDrawable)
        gesture.addScreenPoint(1f, 1f, 1f, 0L)
        assertTrue(gesture.isDrawable)
    }

    @Test
    fun strokeJsonMatchesCoreSchema() {
        val json = StrokeGesture.buildStrokeJson(
            samples = listOf(
                StrokeSample(x = 1f, y = 2f, pressure = 0.5f, timestampMs = 0L),
                StrokeSample(x = 3f, y = 4f, pressure = 0.8f, timestampMs = 16L),
            ),
            color = StrokeColor(10, 20, 30, 255),
            width = 3f,
        )
        assertEquals(
            """{"points":[{"position":{"x":1.0,"y":2.0},"pressure":0.5,"timestamp_ms":0},""" +
                """{"position":{"x":3.0,"y":4.0},"pressure":0.8,"timestamp_ms":16}],""" +
                """"color":{"r":10,"g":20,"b":30,"a":255},"width":3.0}""",
            json,
        )
    }

    @Test
    fun identityTransformKeepsScreenCoordinates() {
        val gesture = StrokeGesture(CanvasTransform(), startUptimeMs = 5L)
        gesture.addScreenPoint(42f, 7f, 0.3f, 5L)
        val s = gesture.samples.single()
        assertEquals(42f, s.x, 1e-4f)
        assertEquals(7f, s.y, 1e-4f)
        assertEquals(0L, s.timestampMs)
    }
}
