package com.nexanote.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reglas puras de la biblioteca de cuadernos: saneado de títulos e ids y la
 * traducción del tipo de lienzo a la especificación de página del núcleo. El
 * acceso a disco se prueba en los tests instrumentados.
 */
class NotebookLibraryTest {

    @Test
    fun blankTitlesFallBackToThePlaceholder() {
        assertEquals(NotebookLibrary.UNTITLED, NotebookLibrary.sanitizeTitle("   "))
        assertEquals("Física I", NotebookLibrary.sanitizeTitle("  Física I  "))
    }

    @Test
    fun longTitlesAreTruncated() {
        val title = NotebookLibrary.sanitizeTitle("x".repeat(500))
        assertEquals(NotebookLibrary.MAX_TITLE_LEN, title.length)
    }

    @Test
    fun idsCannotEscapeTheLibraryDirectory() {
        assertEquals("etcpasswd", NotebookLibrary.safeId("../../etc/passwd"))
        assertEquals("nb-123-a4f", NotebookLibrary.safeId("nb-123-a4f"))
        assertTrue(NotebookLibrary.safeId("z".repeat(200)).length <= 64)
    }

    @Test
    fun canvasKindMapsToTheCorePageSpec() {
        assertTrue(CanvasKind.A4.pageSpecJson.contains(""""format":"A4""""))
        assertTrue(CanvasKind.Infinite.pageSpecJson.contains(""""format":"Infinite""""))
    }

    @Test
    fun unknownStoredCanvasFallsBackToA4() {
        assertEquals(CanvasKind.Infinite, CanvasKind.fromName("Infinite"))
        assertEquals(CanvasKind.A4, CanvasKind.fromName("Hexagonal"))
        assertEquals(CanvasKind.A4, CanvasKind.fromName(null))
        // El nombre guardado es sensible a mayúsculas: un valor deformado no cuela.
        assertEquals(CanvasKind.A4, CanvasKind.fromName("infinite"))
    }

    @Test
    fun everyCanvasKindHasATemplateAndAHumanLabel() {
        for (kind in CanvasKind.entries) {
            assertTrue(kind.pageSpecJson.contains(""""template""""))
            assertTrue(kind.label.isNotBlank())
        }
    }

    @Test
    fun safeIdKeepsHyphensAndDigitsButStripsSeparators() {
        assertEquals("nb-1788-ab", NotebookLibrary.safeId("nb-1788-ab"))
        assertEquals("abc", NotebookLibrary.safeId("a/b\\c"))
        assertEquals("nb_1a", NotebookLibrary.safeId("nb_1.a"))
    }
}
