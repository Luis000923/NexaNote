package com.nexanote.app.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lógica de cálculo de la vista previa de las formas geométricas: bounding box a
 * partir del arrastre, umbral de "arrastre intencionado" y serialización al
 * formato `Shape` del núcleo Rust. Pura, sin Android.
 */
class ShapeInputTest {

    @Test
    fun rectangleBoundsAreNormalizedRegardlessOfDragDirection() {
        // Arrastre de abajo-derecha hacia arriba-izquierda.
        val b = ShapeGeometry.boundsFor(ShapeKind.Rectangle, startX = 100f, startY = 80f, endX = 40f, endY = 20f)
        assertEquals(40f, b.x, 1e-4f)
        assertEquals(20f, b.y, 1e-4f)
        assertEquals(60f, b.width, 1e-4f)
        assertEquals(60f, b.height, 1e-4f)
    }

    @Test
    fun ellipseBoundsUseSameNormalizationAsRectangle() {
        val b = ShapeGeometry.boundsFor(ShapeKind.Ellipse, 10f, 10f, 50f, 30f)
        assertEquals(10f, b.x, 1e-4f)
        assertEquals(40f, b.width, 1e-4f)
        assertEquals(20f, b.height, 1e-4f)
    }

    @Test
    fun lineAndArrowBoundsKeepSignedVector() {
        val line = ShapeGeometry.boundsFor(ShapeKind.Line, 100f, 100f, 60f, 130f)
        assertEquals(100f, line.x, 1e-4f)
        assertEquals(100f, line.y, 1e-4f)
        assertEquals(-40f, line.width, 1e-4f)
        assertEquals(30f, line.height, 1e-4f)

        val arrow = ShapeGeometry.boundsFor(ShapeKind.Arrow, 0f, 0f, -5f, -12f)
        assertEquals(-5f, arrow.width, 1e-4f)
        assertEquals(-12f, arrow.height, 1e-4f)
    }

    @Test
    fun tinyDragIsNotDrawable() {
        val rect = ShapeGeometry.boundsFor(ShapeKind.Rectangle, 10f, 10f, 11f, 11f)
        assertFalse(ShapeGeometry.isDrawable(ShapeKind.Rectangle, rect))

        val line = ShapeGeometry.boundsFor(ShapeKind.Line, 10f, 10f, 11f, 10f)
        assertFalse(ShapeGeometry.isDrawable(ShapeKind.Line, line))
    }

    @Test
    fun sufficientDragIsDrawable() {
        val ellipse = ShapeGeometry.boundsFor(ShapeKind.Ellipse, 0f, 0f, 50f, 1f)
        // Basta con que un eje supere el umbral (rectángulo/elipse fino es válido).
        assertTrue(ShapeGeometry.isDrawable(ShapeKind.Ellipse, ellipse))

        val arrow = ShapeGeometry.boundsFor(ShapeKind.Arrow, 0f, 0f, 3f, 4f)
        assertTrue(ShapeGeometry.isDrawable(ShapeKind.Arrow, arrow))
    }

    @Test
    fun shapeJsonMatchesCoreSchema() {
        val json = ShapeGeometry.toShapeJson(
            kind = ShapeKind.Rectangle,
            bounds = ShapeBounds(10f, 20f, 30f, 40f),
            stroke = StrokeColor(1, 2, 3, 255),
            fill = null,
            strokeWidth = 2.5f,
        )
        assertEquals(
            """{"kind":"Rectangle","bounds":{"x":10.0,"y":20.0,"width":30.0,"height":40.0},""" +
                """"stroke_color":{"r":1,"g":2,"b":3,"a":255},"fill_color":null,"stroke_width":2.5}""",
            json,
        )
    }

    @Test
    fun shapeJsonSerializesOptionalFill() {
        val json = ShapeGeometry.toShapeJson(
            kind = ShapeKind.Ellipse,
            bounds = ShapeBounds(0f, 0f, 12f, 8f),
            stroke = StrokeColor.Ink,
            fill = StrokeColor(200, 210, 220, 128),
            strokeWidth = 2f,
        )
        assertTrue(json.contains(""""fill_color":{"r":200,"g":210,"b":220,"a":128}"""))
        assertTrue(json.startsWith("""{"kind":"Ellipse","""))
    }

    @Test
    fun toolMapsToShapeKind() {
        assertEquals(ShapeKind.Line, DrawingTool.Line.asShapeKind())
        assertEquals(ShapeKind.Rectangle, DrawingTool.Rectangle.asShapeKind())
        assertEquals(ShapeKind.Ellipse, DrawingTool.Ellipse.asShapeKind())
        assertEquals(ShapeKind.Arrow, DrawingTool.Arrow.asShapeKind())
        assertNull(DrawingTool.Pen.asShapeKind())
        assertNull(DrawingTool.Pan.asShapeKind())
    }
}
