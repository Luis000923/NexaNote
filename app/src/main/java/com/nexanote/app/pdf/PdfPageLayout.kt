package com.nexanote.app.pdf

import kotlin.math.roundToInt

/**
 * Aritmética pura de la exportación a PDF: convierte el tamaño de página que
 * entrega el núcleo Rust (píxeles lógicos `@96dpi`, ver `render::PX_PER_MM`) a
 * **puntos PostScript** (1/72"), la unidad nativa de [android.graphics.pdf.PdfDocument].
 *
 * Sin Android ni Compose: se cubre con tests de JVM.
 */
object PdfPageLayout {

    /** DPI lógico del núcleo (mismo valor que asume `render.rs`). */
    const val PX_PER_INCH = 96f

    /** Puntos PostScript por pulgada. */
    const val POINTS_PER_INCH = 72f

    /** Factor de escala de píxeles lógicos `@96dpi` a puntos PDF. */
    const val PX_TO_POINT = POINTS_PER_INCH / PX_PER_INCH

    /** Dimensiones de una página del PDF, en puntos enteros (lo que exige `PdfDocument`). */
    data class PagePoints(val widthPt: Int, val heightPt: Int)

    /**
     * Tamaño en puntos de una página cuya escena mide `widthPx` x `heightPx`.
     * Nunca devuelve cero (una página degenerada se acota a 1 punto) para no
     * romper `PdfDocument.PageInfo`.
     */
    fun pagePoints(widthPx: Float, heightPx: Float): PagePoints = PagePoints(
        widthPt = toPoints(widthPx),
        heightPt = toPoints(heightPx),
    )

    private fun toPoints(px: Float): Int {
        if (!px.isFinite() || px <= 0f) return 1
        return (px * PX_TO_POINT).roundToInt().coerceAtLeast(1)
    }
}
