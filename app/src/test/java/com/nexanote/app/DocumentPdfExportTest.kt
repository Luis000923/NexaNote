package com.nexanote.app

import androidx.compose.ui.graphics.Color
import com.nexanote.app.canvas.SampleDocument
import com.nexanote.app.canvas.ScenePage
import com.nexanote.app.canvas.SceneTemplate
import com.nexanote.app.pdf.PdfWriter
import com.nexanote.core.NativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Flujo de exportación a PDF del [DocumentViewModel] (Fase 11). Puro JVM: la
 * escritura del PDF, el render de escenas y el conteo de páginas se inyectan como
 * dobles (la API `android.graphics.pdf` y `org.json` no existen aquí).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentPdfExportTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Escritor de PDF falso: registra lo que recibe y produce bytes reconocibles. */
    private class FakePdfWriter(private val failWith: Throwable? = null) : PdfWriter {
        var calls = 0
        var received: List<ScenePage>? = null

        override fun write(pages: List<ScenePage>, images: (String) -> android.graphics.Bitmap?, out: OutputStream) {
            calls++
            failWith?.let { throw it }
            received = pages
            out.write("%PDF-1.4\n".toByteArray())
            out.write("%%EOF".toByteArray())
        }
    }

    private fun scene(index: Int) = ScenePage(
        pageIndex = index,
        pageId = "page-$index",
        widthPx = 800f,
        heightPx = 1000f,
        background = Color.White,
        template = SceneTemplate.Blank,
        primitives = emptyList(),
    )

    private fun viewModel(writer: PdfWriter, pageCount: Int) = DocumentViewModel(
        core = FakeCore(),
        bridgeAvailable = true,
        loadDocument = { SampleDocument.LoadedDocument("DOC-JSON", "PAGE-0") },
        pageIdOf = { "PAGE-0" },
        renderScene = { _, index -> scene(index) },
        pageCountOf = { pageCount },
        pdfWriter = writer,
    ).also { waitUntil { it.state.value is SceneUiState.Ready } }

    private fun DocumentViewModel.awaitExport(): PdfExportUiState {
        waitUntil { export.value.phase != ExportPhase.Working && export.value.phase != ExportPhase.Idle }
        return export.value
    }

    @Test
    fun exportsSinglePageDocumentThroughTheWriter() {
        val writer = FakePdfWriter()
        val vm = viewModel(writer, pageCount = 1)
        val sink = ByteArrayOutputStream()

        vm.exportPdf(images = { null }, openStream = { sink })
        val result = vm.awaitExport()

        assertEquals(ExportPhase.Done, result.phase)
        assertEquals(1, writer.calls)
        assertEquals(1, writer.received!!.size)
        assertTrue(sink.toString().startsWith("%PDF"))
        assertTrue(result.message!!.contains("1 página"))
    }

    @Test
    fun exportsEveryPageOfAMultiPageDocument() {
        val writer = FakePdfWriter()
        val vm = viewModel(writer, pageCount = 3)

        vm.exportPdf(images = { null }, openStream = { ByteArrayOutputStream() })
        val result = vm.awaitExport()

        assertEquals(ExportPhase.Done, result.phase)
        assertEquals(listOf("page-0", "page-1", "page-2"), writer.received!!.map { it.pageId })
        assertTrue(result.message!!.contains("3 páginas"))
    }

    @Test
    fun reportsFailureWhenWriterThrows() {
        val vm = viewModel(FakePdfWriter(failWith = IOException("disco lleno")), pageCount = 2)

        vm.exportPdf(images = { null }, openStream = { ByteArrayOutputStream() })
        val result = vm.awaitExport()

        assertEquals(ExportPhase.Failed, result.phase)
        assertTrue(result.message!!.contains("disco lleno"))
    }

    @Test
    fun reportsFailureWhenDestinationCannotBeOpened() {
        val writer = FakePdfWriter()
        val vm = viewModel(writer, pageCount = 1)

        vm.exportPdf(images = { null }, openStream = { null })
        val result = vm.awaitExport()

        assertEquals(ExportPhase.Failed, result.phase)
        assertEquals(0, writer.calls)
    }

    @Test
    fun consumeExportStateReturnsToIdle() {
        val vm = viewModel(FakePdfWriter(), pageCount = 1)
        vm.exportPdf(images = { null }, openStream = { ByteArrayOutputStream() })
        vm.awaitExport()

        vm.consumeExportState()

        assertEquals(ExportPhase.Idle, vm.export.value.phase)
        assertEquals(null, vm.export.value.message)
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        if (!condition()) throw AssertionError("condición no cumplida en $timeoutMs ms")
    }

    /** Núcleo falso: sólo el historial (que [DocumentViewModel] usa al inicializar). */
    private class FakeCore : NativeCore {
        override fun historyInit(documentJson: String) = "H"
        override fun historyRecord(historyJson: String, documentJson: String) = "H"
        override fun historyUndo(historyJson: String) = "H"
        override fun historyRedo(historyJson: String) = "H"
        override fun historyDocument(historyJson: String) = "DOC-JSON"
        override fun historyStatus(historyJson: String) = "0,0"

        override fun greeting(name: String) = unsupported()
        override fun coreVersion() = unsupported()
        override fun documentCreate(title: String) = unsupported()
        override fun documentAddPage(documentJson: String, pageSpecJson: String) = unsupported()
        override fun documentRemovePage(documentJson: String, pageId: String) = unsupported()
        override fun documentAddElement(documentJson: String, pageId: String, elementJson: String) = unsupported()
        override fun documentAddStroke(documentJson: String, pageId: String, strokeJson: String) = unsupported()
        override fun documentAddShape(documentJson: String, pageId: String, shapeJson: String) = unsupported()
        override fun documentAddText(documentJson: String, pageId: String, textJson: String) = unsupported()
        override fun documentAddFormula(documentJson: String, pageId: String, formulaJson: String) = unsupported()
        override fun documentAddGraph(documentJson: String, pageId: String, graphJson: String) = unsupported()
        override fun documentAddImage(documentJson: String, pageId: String, imageJson: String) = unsupported()
        override fun documentRemoveElement(documentJson: String, pageId: String, elementId: String) = unsupported()
        override fun documentTranslatePageElements(documentJson: String, pageId: String, dx: Float, dy: Float) = unsupported()
        override fun documentSummary(documentJson: String) = unsupported()
        override fun documentRenderPage(documentJson: String, pageIndex: Int) = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }
}
