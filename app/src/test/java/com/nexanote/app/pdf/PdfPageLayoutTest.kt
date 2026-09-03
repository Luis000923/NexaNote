package com.nexanote.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Aritmética pura de la exportación a PDF: la escena del núcleo está en píxeles
 * lógicos `@96dpi` y una página del PDF se mide en puntos (1/72").
 */
class PdfPageLayoutTest {

    @Test
    fun a4SceneBecomesStandardA4InPoints() {
        val widthPx = 210f * PX_PER_MM
        val heightPx = 297f * PX_PER_MM
        val points = PdfPageLayout.pagePoints(widthPx, heightPx)
        // A4 estándar: 595 x 842 puntos.
        assertEquals(595, points.widthPt)
        assertEquals(842, points.heightPt)
    }

    @Test
    fun scaleFactorMapsNinetySixDpiToSeventyTwo() {
        assertEquals(0.75f, PdfPageLayout.PX_TO_POINT, 1e-6f)
    }

    @Test
    fun degenerateOrNonFiniteSizesClampToOnePoint() {
        assertEquals(PdfPageLayout.PagePoints(1, 1), PdfPageLayout.pagePoints(0f, 0f))
        assertEquals(PdfPageLayout.PagePoints(1, 1), PdfPageLayout.pagePoints(-10f, -10f))
        assertEquals(PdfPageLayout.PagePoints(1, 1), PdfPageLayout.pagePoints(Float.NaN, Float.POSITIVE_INFINITY))
    }

    @Test
    fun sizeRoundsToNearestPoint() {
        // 100 px -> 75 pt exactos; 101 px -> 75.75 -> 76.
        assertEquals(75, PdfPageLayout.pagePoints(100f, 100f).widthPt)
        assertEquals(76, PdfPageLayout.pagePoints(101f, 101f).widthPt)
    }

    private companion object {
        const val PX_PER_MM = 3.779_527_6f
    }
}
