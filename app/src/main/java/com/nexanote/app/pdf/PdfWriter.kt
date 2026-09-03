package com.nexanote.app.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import com.nexanote.app.canvas.ScenePage
import java.io.OutputStream

/**
 * Serializa una lista de páginas ya renderizadas por el núcleo Rust como un
 * documento **PDF multipágina**.
 *
 * Es una interfaz para poder inyectar un doble en los tests de JVM (donde
 * `android.graphics.pdf.PdfDocument` no existe); la implementación real es
 * [AndroidPdfWriter].
 */
interface PdfWriter {

    /**
     * Escribe [pages] como PDF en [out]. Trabajo intensivo de CPU y de E/S: el
     * llamante **debe** invocarlo fuera del hilo principal. No cierra [out].
     *
     * @param images resuelve la ruta relativa de una imagen del documento a su
     *   bitmap ya decodificado, o `null` si no está disponible (se pinta entonces
     *   un marcador de posición, igual que en el lienzo).
     */
    fun write(pages: List<ScenePage>, images: (String) -> Bitmap?, out: OutputStream)
}

/**
 * Implementación con la API nativa [PdfDocument]. Cada página del documento se
 * vuelca en una página del PDF con su tamaño exacto en puntos (ver
 * [PdfPageLayout]); el lienzo se escala de píxeles lógicos a puntos y se delega
 * el pintado en [ScenePdfPainter], que reproduce las mismas primitivas que
 * `DocumentCanvas`.
 *
 * Se elige la API de Android (y no una crate de PDF en Rust) porque el núcleo ya
 * es la autoridad única sobre unidades, orden de pintado y muestreo de curvas
 * (`render.rs`): aquí sólo se rasteriza esa escena, sin volver a "decidir" nada,
 * y se evita arrastrar una dependencia pesada de generación de PDF.
 */
object AndroidPdfWriter : PdfWriter {

    override fun write(pages: List<ScenePage>, images: (String) -> Bitmap?, out: OutputStream) {
        require(pages.isNotEmpty()) { "no hay páginas que exportar" }
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, scene ->
                val size = PdfPageLayout.pagePoints(scene.widthPx, scene.heightPx)
                val info = PdfDocument.PageInfo
                    .Builder(size.widthPt, size.heightPt, index + 1)
                    .create()
                val page = pdf.startPage(info)
                page.canvas.scale(PdfPageLayout.PX_TO_POINT, PdfPageLayout.PX_TO_POINT)
                ScenePdfPainter.paint(page.canvas, scene, images)
                pdf.finishPage(page)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }
}
