package com.nexanote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexanote.app.canvas.FormulaInput
import com.nexanote.app.canvas.GraphInput
import com.nexanote.app.canvas.SceneParser
import com.nexanote.app.pdf.AndroidPdfWriter
import com.nexanote.app.pdf.PdfPageLayout
import com.nexanote.core.NativeBridge
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Exportación a PDF (Fase 11) de extremo a extremo con el puente real: el núcleo
 * Rust rinde cada página, [AndroidPdfWriter] la rasteriza y se comprueba que el
 * archivo resultante es un PDF válido y multipágina.
 */
@RunWith(AndroidJUnit4::class)
class PdfExportBridgeTest {

    @Test
    fun writerProducesValidMultiPagePdfFromCoreScenes() {
        var doc = NativeBridge.documentCreate("Exportación")
        doc = NativeBridge.documentAddPage(
            doc,
            """{"size":{"format":"A4"},"template":{"kind":"Grid","spacing":24.0}}""",
        )
        doc = NativeBridge.documentAddPage(
            doc,
            """{"size":{"format":"A5"},"template":{"kind":"Blank"}}""",
        )
        val firstPage = JSONObject(doc).getJSONArray("pages").getJSONObject(0).getString("id")
        doc = NativeBridge.documentAddElement(
            doc, firstPage,
            """{"type":"Text","content":"Página con contenido","position":{"x":40.0,"y":60.0},
                "style":{"font_size":20.0,"bold":true,"italic":false,"underline":false,
                "color":{"r":20,"g":20,"b":20,"a":255}},"max_width":null}""",
        )
        doc = NativeBridge.documentAddFormula(doc, firstPage, FormulaInput.toFormulaJson("2^{10}", 40f, 120f))
        doc = NativeBridge.documentAddGraph(
            doc, firstPage,
            GraphInput.toGraphJson("sin(x)", -3.14, 3.14, 40f, 160f, 200f, 160f),
        )

        val count = JSONObject(doc).getJSONArray("pages").length()
        assertEquals(2, count)
        val scenes = (0 until count).map { SceneParser.parse(NativeBridge.documentRenderPage(doc, it)) }

        val out = ByteArrayOutputStream()
        AndroidPdfWriter.write(scenes, { null }, out)
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)

        assertTrue("cabecera PDF", text.startsWith("%PDF-"))
        assertTrue("marcador de fin", text.trimEnd().endsWith("%%EOF"))
        assertTrue("tamaño razonable", bytes.size > 800)

        // La primera página conserva el tamaño A4 en puntos.
        val a4 = PdfPageLayout.pagePoints(scenes[0].widthPx, scenes[0].heightPx)
        assertEquals(595, a4.widthPt)
        assertEquals(842, a4.heightPt)
    }

    @Test
    fun emptyPageListIsRejected() {
        try {
            AndroidPdfWriter.write(emptyList(), { null }, ByteArrayOutputStream())
            throw AssertionError("esperaba IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun viewModelExportsSampleDocumentToAFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vm = DocumentViewModel(core = NativeBridge, bridgeAvailable = true)
        assertTrue(waitFor { vm.state.value is SceneUiState.Ready })

        val file = File(context.cacheDir, "nexanote-export-${System.nanoTime()}.pdf")
        vm.exportPdf(
            images = { source -> ImageImporter.decodeBitmap(context, source) },
            openStream = { file.outputStream() },
        )

        assertTrue(waitFor { vm.export.value.phase != ExportPhase.Working })
        assertEquals(ExportPhase.Done, vm.export.value.phase)
        assertTrue("el PDF no está vacío", file.length() > 0)

        val header = ByteArray(5)
        file.inputStream().use { it.read(header) }
        assertEquals("%PDF-", String(header, Charsets.ISO_8859_1))
        file.delete()
    }

    private fun waitFor(timeoutMs: Long = 6_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
