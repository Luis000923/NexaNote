package com.nexanote.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.nexanote.app.canvas.SampleDocument
import com.nexanote.app.canvas.SceneHit
import com.nexanote.app.canvas.ScenePage
import com.nexanote.app.canvas.SceneTemplate
import com.nexanote.app.canvas.Selection
import com.nexanote.app.canvas.ShapeBounds
import com.nexanote.app.canvas.ShapeKind
import com.nexanote.app.canvas.StrokeColor
import com.nexanote.app.canvas.StrokeSample
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

/**
 * Orquestación de la selección, el borrado, el duplicado y el color en
 * [DocumentViewModel] (Fase 13).
 *
 * Comprueba **el cableado**, no el dominio: que cada acción llegue al núcleo con
 * los ids y la geometría correctos, y que la selección publicada sea siempre la
 * que devolvió el núcleo. Quién cae dentro de un área lo deciden los tests de
 * Rust.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSelectionTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Una llamada registrada al núcleo: el método y sus argumentos relevantes. */
    private data class Call(val method: String, val args: List<Any?>)

    /**
     * Núcleo falso: registra las llamadas y devuelve documentos reconocibles, para
     * poder afirmar exactamente qué se le pidió.
     */
    private class RecordingCore : NativeCore {
        val calls = mutableListOf<Call>()

        override fun documentAddStroke(documentJson: String, pageId: String, strokeJson: String): String {
            calls += Call("addStroke", listOf(strokeJson))
            return "DOC-1"
        }

        override fun documentAddShape(documentJson: String, pageId: String, shapeJson: String): String {
            calls += Call("addShape", listOf(shapeJson))
            return "DOC-1"
        }

        override fun documentSelectInArea(documentJson: String, pageId: String, areaJson: String): String {
            calls += Call("selectInArea", listOf(areaJson))
            return SELECTION
        }

        override fun documentSelectAt(documentJson: String, pageId: String, x: Float, y: Float): String {
            calls += Call("selectAt", listOf(x, y))
            return SELECTION
        }

        override fun documentRemoveElements(documentJson: String, pageId: String, idsJson: String): String {
            calls += Call("removeElements", listOf(idsJson))
            return "DOC-1"
        }

        override fun documentTranslateElements(
            documentJson: String,
            pageId: String,
            idsJson: String,
            dx: Float,
            dy: Float,
        ): String {
            calls += Call("translateElements", listOf(idsJson, dx, dy))
            return "DOC-1"
        }

        override fun documentDuplicateElements(
            documentJson: String,
            pageId: String,
            idsJson: String,
            dx: Float,
            dy: Float,
        ): String {
            calls += Call("duplicateElements", listOf(idsJson, dx, dy))
            return "DUPLICATE"
        }

        override fun documentSetElementsColor(
            documentJson: String,
            pageId: String,
            idsJson: String,
            colorJson: String,
        ): String {
            calls += Call("setElementsColor", listOf(idsJson, colorJson))
            return "DOC-1"
        }

        override fun historyInit(documentJson: String) = "H"
        override fun historyRecord(historyJson: String, documentJson: String) = "H"
        override fun historyUndo(historyJson: String) = "H"
        override fun historyRedo(historyJson: String) = "H"
        override fun historyDocument(historyJson: String) = "DOC-1"
        override fun historyStatus(historyJson: String) = "0,0"

        override fun greeting(name: String) = unsupported()
        override fun coreVersion() = unsupported()
        override fun documentCreate(title: String) = unsupported()
        override fun documentAddPage(documentJson: String, pageSpecJson: String) = unsupported()
        override fun documentRemovePage(documentJson: String, pageId: String) = unsupported()
        override fun documentAddElement(documentJson: String, pageId: String, elementJson: String) = unsupported()
        override fun documentAddText(documentJson: String, pageId: String, textJson: String) = unsupported()
        override fun documentAddFormula(documentJson: String, pageId: String, formulaJson: String) = unsupported()
        override fun documentAddGraph(documentJson: String, pageId: String, graphJson: String) = unsupported()
        override fun documentAddImage(documentJson: String, pageId: String, imageJson: String) = unsupported()
        override fun documentRemoveElement(documentJson: String, pageId: String, elementId: String) = unsupported()
        override fun documentTranslatePageElements(documentJson: String, pageId: String, dx: Float, dy: Float) = unsupported()
        override fun documentSummary(documentJson: String) = unsupported()
        override fun documentRenderPage(documentJson: String, pageIndex: Int) = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()

        companion object {
            const val SELECTION = "SELECTION"
        }
    }

    private val hits = listOf(
        SceneHit("aa", "Shape", Rect(Offset(0f, 0f), Size(10f, 10f)), fillable = true),
        SceneHit("bb", "Stroke", Rect(Offset(40f, 40f), Size(10f, 10f)), fillable = false),
    )

    private fun scene() = ScenePage(
        pageIndex = 0,
        pageId = "PAGE-0",
        widthPx = 800f,
        heightPx = 1000f,
        background = Color.White,
        template = SceneTemplate.Blank,
        primitives = emptyList(),
        hits = hits,
    )

    private fun viewModel(core: RecordingCore) = DocumentViewModel(
        core = core,
        bridgeAvailable = true,
        loadDocument = { SampleDocument.LoadedDocument("DOC-0", "PAGE-0") },
        pageIdOf = { "PAGE-0" },
        renderScene = { _, _ -> scene() },
        pageCountOf = { 1 },
        parseSelection = { Selection(listOf("aa", "bb"), Rect(Offset(0f, 0f), Size(50f, 50f))) },
        parseDuplicate = { "DOC-COPIES" to Selection(listOf("cc"), null) },
    ).also { waitUntil { it.state.value is SceneUiState.Ready } }

    @Test
    fun selectingAnAreaPublishesWhatTheCoreResolved() {
        val core = RecordingCore()
        val vm = viewModel(core)

        vm.selectArea(Rect(Offset(1f, 2f), Size(30f, 40f)))
        waitUntil { vm.selection.value.isNotEmpty }

        assertEquals(listOf("aa", "bb"), vm.selection.value.ids)
        val call = core.calls.single { it.method == "selectInArea" }
        assertEquals("""{"x":1.0,"y":2.0,"width":30.0,"height":40.0}""", call.args[0])
    }

    @Test
    fun tappingAskesTheCoreForTheElementUnderThePoint() {
        val core = RecordingCore()
        val vm = viewModel(core)

        vm.selectAt(12f, 34f)
        waitUntil { vm.selection.value.isNotEmpty }

        assertEquals(listOf(12f, 34f), core.calls.single { it.method == "selectAt" }.args)
    }

    @Test
    fun deletingSendsTheSelectedIdsAndClearsTheSelection() {
        val core = RecordingCore()
        val vm = viewModel(core)
        vm.selectAt(0f, 0f)
        waitUntil { vm.selection.value.isNotEmpty }

        vm.deleteSelection()
        waitUntil { vm.selection.value.isEmpty }

        assertEquals("""["aa","bb"]""", core.calls.single { it.method == "removeElements" }.args[0])
    }

    @Test
    fun movingTranslatesOnlyTheSelectionAndKeepsIt() {
        val core = RecordingCore()
        val vm = viewModel(core)
        vm.selectAt(0f, 0f)
        waitUntil { vm.selection.value.isNotEmpty }

        vm.moveSelection(15f, -5f)
        waitUntil { core.calls.any { it.method == "translateElements" } }

        val call = core.calls.single { it.method == "translateElements" }
        assertEquals(listOf("""["aa","bb"]""", 15f, -5f), call.args)
        // La selección sigue viva, reproyectada sobre las cajas de la escena nueva.
        assertEquals(listOf("aa", "bb"), vm.selection.value.ids)
    }

    @Test
    fun aZeroMoveNeverReachesTheCore() {
        val core = RecordingCore()
        val vm = viewModel(core)
        vm.selectAt(0f, 0f)
        waitUntil { vm.selection.value.isNotEmpty }

        vm.moveSelection(0f, 0f)

        assertTrue(core.calls.none { it.method == "translateElements" })
    }

    @Test
    fun duplicatingLeavesTheCopiesSelected() {
        val core = RecordingCore()
        val vm = viewModel(core)
        vm.selectAt(0f, 0f)
        waitUntil { vm.selection.value.isNotEmpty }

        vm.duplicateSelection()
        waitUntil { vm.selection.value.ids == listOf("cc") }

        assertEquals(listOf("cc"), vm.selection.value.ids)
    }

    @Test
    fun inkColorAppliesToTheSelectionAndToWhatIsDrawnNext() {
        val core = RecordingCore()
        val vm = viewModel(core)
        vm.selectAt(0f, 0f)
        waitUntil { vm.selection.value.isNotEmpty }

        val red = StrokeColor(198, 40, 40)
        vm.applyInkColor(red)
        waitUntil { core.calls.any { it.method == "setElementsColor" } }

        assertEquals(red, vm.inkColor.value)
        val call = core.calls.single { it.method == "setElementsColor" }
        assertEquals("""{"target":"stroke","color":{"r":198,"g":40,"b":40,"a":255}}""", call.args[1])

        // El color activo es el que viaja con el siguiente trazo y la siguiente forma.
        vm.commitStroke(listOf(StrokeSample(0f, 0f, 0.5f, 0L)))
        waitUntil { core.calls.any { it.method == "addStroke" } }
        assertTrue((core.calls.single { it.method == "addStroke" }.args[0] as String).contains("\"r\":198"))

        vm.commitShape(ShapeKind.Rectangle, ShapeBounds(0f, 0f, 10f, 10f))
        waitUntil { core.calls.any { it.method == "addShape" } }
        assertTrue((core.calls.single { it.method == "addShape" }.args[0] as String).contains("\"r\":198"))
    }

    @Test
    fun fillIsRemovableAndOnlyRunsWithASelection() {
        val core = RecordingCore()
        val vm = viewModel(core)

        // Sin selección no hay nada que rellenar.
        vm.applyFillColor(StrokeColor(255, 249, 196))
        assertTrue(core.calls.none { it.method == "setElementsColor" })

        vm.selectAt(0f, 0f)
        waitUntil { vm.selection.value.isNotEmpty }
        vm.applyFillColor(null)
        waitUntil { core.calls.any { it.method == "setElementsColor" } }

        assertEquals(
            """{"target":"fill","color":null}""",
            core.calls.single { it.method == "setElementsColor" }.args[1],
        )
    }

    @Test
    fun clearSelectionNeverTouchesTheDocument() {
        val core = RecordingCore()
        val vm = viewModel(core)
        vm.selectAt(0f, 0f)
        waitUntil { vm.selection.value.isNotEmpty }

        vm.clearSelection()

        assertTrue(vm.selection.value.isEmpty)
        assertTrue(core.calls.none { it.method == "removeElements" })
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        if (!condition()) throw AssertionError("condición no cumplida en $timeoutMs ms")
    }
}
