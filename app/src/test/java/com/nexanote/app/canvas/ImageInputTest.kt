package com.nexanote.app.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lógica pura de la importación de imágenes: aceptación de la ruta del recurso,
 * encaje del marco conservando la proporción y serialización al formato
 * `ImageSpec` del núcleo Rust. Sin Android.
 */
class ImageInputTest {

    @Test
    fun relativePathsInsideTheStoreAreCommittable() {
        assertTrue(ImageInput.isCommittable("images/9f3c.png"))
        assertTrue(ImageInput.isCommittable("images/sub/photo.jpg"))
    }

    @Test
    fun absoluteTraversingOrDriveQualifiedPathsAreRejected() {
        assertFalse(ImageInput.isCommittable(""))
        assertFalse(ImageInput.isCommittable("   "))
        assertFalse(ImageInput.isCommittable("/sdcard/pic.png"))
        assertFalse(ImageInput.isCommittable("\\pic.png"))
        assertFalse(ImageInput.isCommittable("images/../../secret.png"))
        assertFalse(ImageInput.isCommittable("C:\\pics\\x.png"))
        assertFalse(ImageInput.isCommittable("content://media/external/images/42"))
        assertFalse(ImageInput.isCommittable("x".repeat(ImageInput.MAX_SOURCE_LEN + 1)))
    }

    @Test
    fun fitFrameShrinksLargeImagesKeepingAspectRatio() {
        val (w, h) = ImageInput.fitFrame(4000f, 2000f, maxWidth = 400f, maxHeight = 400f)
        assertEquals(400f, w, 1e-3f)
        assertEquals(200f, h, 1e-3f)
    }

    @Test
    fun fitFrameNeverUpscalesSmallImages() {
        val (w, h) = ImageInput.fitFrame(60f, 40f, maxWidth = 400f, maxHeight = 400f)
        assertEquals(60f, w, 1e-3f)
        assertEquals(40f, h, 1e-3f)
    }

    @Test
    fun fitFrameFallsBackWhenNaturalDimensionsAreInvalid() {
        val (w, h) = ImageInput.fitFrame(0f, -3f, maxWidth = 320f, maxHeight = 240f)
        assertEquals(320f, w, 1e-3f)
        assertEquals(240f, h, 1e-3f)
    }

    @Test
    fun imageJsonMatchesCoreSchema() {
        val json = ImageInput.toImageJson(
            source = "images/pic.png",
            x = 10f,
            y = 20f,
            width = 200f,
            height = 150f,
            naturalWidth = 1600f,
            naturalHeight = 1200f,
        )
        assertEquals(
            """{"source":"images/pic.png","position":{"x":10.0,"y":20.0},""" +
                """"width":200.0,"height":150.0,"natural_width":1600.0,"natural_height":1200.0}""",
            json,
        )
    }

    @Test
    fun imageJsonEscapesTheSourcePath() {
        val json = ImageInput.toImageJson("images/a\"b.png", 0f, 0f, 8f, 8f, 8f, 8f)
        assertTrue(json.contains("""images/a\"b.png"""))
    }

    @Test
    fun imageToolIsNotAShapeKind() {
        org.junit.Assert.assertNull(DrawingTool.Image.asShapeKind())
    }
}
