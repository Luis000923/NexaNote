package com.nexanote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexanote.app.canvas.CanvasTransform
import com.nexanote.app.canvas.SampleDocument
import com.nexanote.app.canvas.SceneParser
import com.nexanote.app.canvas.ScenePrimitive
import com.nexanote.app.canvas.SceneTemplate
import com.nexanote.app.canvas.StrokeColor
import com.nexanote.app.canvas.StrokeGesture
import com.nexanote.core.NativeBridge
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica la comunicación UI/ViewModel ↔ puente de Rust para los datos de
 * render: el núcleo construye el documento y su escena, y la capa Kotlin la
 * consume sin recalcular lógica de dominio.
 */
@RunWith(AndroidJUnit4::class)
class DocumentRenderBridgeTest {

    @Test
    fun coreProducesRenderSceneForAPage() {
        var doc = NativeBridge.documentCreate("Render")
        doc = NativeBridge.documentAddPage(
            doc,
            """{"size":{"format":"A4"},"template":{"kind":"Grid","spacing":24.0}}""",
        )
        val pageId = JSONObject(doc).getJSONArray("pages").getJSONObject(0).getString("id")
        doc = NativeBridge.documentAddElement(
            doc, pageId,
            """{"type":"Shape","kind":"Rectangle",
                "bounds":{"x":10.0,"y":20.0,"width":30.0,"height":40.0},
                "stroke_color":{"r":0,"g":0,"b":0,"a":255},
                "fill_color":null,"stroke_width":2.0}""",
        )

        val scene = SceneParser.parse(NativeBridge.documentRenderPage(doc, 0))

        // A4 en px @96dpi: 210mm -> ~793.7px
        assertEquals(793.7f, scene.widthPx, 1f)
        assertTrue(scene.template is SceneTemplate.Grid)
        assertEquals(1, scene.primitives.size)
        val rect = scene.primitives[0]
        assertTrue(rect is ScenePrimitive.Rect)
        rect as ScenePrimitive.Rect
        assertEquals(10f, rect.topLeft.x, 1e-3f)
        assertEquals(30f, rect.size.width, 1e-3f)
    }

    @Test
    fun invalidPageIndexRaisesInsteadOfCrashing() {
        val doc = NativeBridge.documentCreate("vacio")
        try {
            NativeBridge.documentRenderPage(doc, 7)
            throw AssertionError("esperaba IllegalStateException")
        } catch (expected: IllegalStateException) {
            // ok: el error del núcleo cruza como excepción controlada
        }
    }

    @Test
    fun sampleDocumentSceneHasEveryPrimitiveKind() {
        val scene = SampleDocument.buildScene(NativeBridge)
        val kinds = scene.primitives.map { it::class.simpleName }.toSet()
        assertTrue("Rect" in kinds)
        assertTrue("Ellipse" in kinds)
        assertTrue("Arrow" in kinds)
        assertTrue("Line" in kinds)
        assertTrue("Polyline" in kinds)
        assertTrue("Text" in kinds)
        assertTrue("Formula" in kinds)
    }

    @Test
    fun stylusStrokeIsCapturedProjectedAndPersistedInRust() {
        var doc = NativeBridge.documentCreate("Trazo")
        doc = NativeBridge.documentAddPage(
            doc,
            """{"size":{"format":"A4"},"template":{"kind":"Blank"}}""",
        )
        val pageId = JSONObject(doc).getJSONArray("pages").getJSONObject(0).getString("id")

        // Gesto simulado: puntos de pantalla -> espacio de documento (identidad).
        val gesture = StrokeGesture(CanvasTransform(), startUptimeMs = 0L)
        gesture.addScreenPoint(10f, 10f, 0.4f, 0L)
        gesture.addScreenPoint(20f, 30f, 0.9f, 16L)
        gesture.addScreenPoint(40f, 25f, 2.0f, 32L) // presión fuera de rango -> se satura

        val strokeJson = StrokeGesture.buildStrokeJson(
            gesture.samples, StrokeColor.Ink, StrokeGesture.DEFAULT_WIDTH,
        )
        doc = NativeBridge.documentAddStroke(doc, pageId, strokeJson)

        val scene = SceneParser.parse(NativeBridge.documentRenderPage(doc, 0))
        val polyline = scene.primitives.filterIsInstance<ScenePrimitive.Polyline>().single()
        assertEquals(3, polyline.points.size)
        assertEquals(40f, polyline.points[2].x, 1e-3f)
        assertEquals(25f, polyline.points[2].y, 1e-3f)
        assertEquals(StrokeGesture.DEFAULT_WIDTH, polyline.width, 1e-3f)
    }

    @Test
    fun emptyStrokeRaisesControlledErrorInsteadOfCrashing() {
        var doc = NativeBridge.documentCreate("x")
        doc = NativeBridge.documentAddPage(doc, "{}")
        val pageId = JSONObject(doc).getJSONArray("pages").getJSONObject(0).getString("id")
        try {
            NativeBridge.documentAddStroke(
                doc,
                pageId,
                """{"points":[],"color":{"r":0,"g":0,"b":0,"a":255},"width":3.0}""",
            )
            throw AssertionError("esperaba IllegalStateException")
        } catch (expected: IllegalStateException) {
            // ok: el núcleo rechaza el trazo sin puntos como error controlado.
        }
    }

    @Test
    fun viewModelCommitsStrokeAndRepublishesScene() = runBlocking {
        val vm = DocumentViewModel(core = NativeBridge, bridgeAvailable = true)
        val ready = vm.buildState()
        assertTrue(ready is SceneUiState.Ready)
        ready as SceneUiState.Ready
        val before = ready.scene.primitives.count { it is ScenePrimitive.Polyline }

        vm.commitStroke(
            listOf(
                com.nexanote.app.canvas.StrokeSample(60f, 60f, 0.5f, 0L),
                com.nexanote.app.canvas.StrokeSample(120f, 90f, 0.7f, 16L),
            ),
        )
        // commitStroke lanza una corrutina; se le da tiempo a completarse.
        var after = before
        repeat(50) {
            val s = vm.state.value
            if (s is SceneUiState.Ready) {
                after = s.scene.primitives.count { it is ScenePrimitive.Polyline }
            }
            if (after > before) return@repeat
            Thread.sleep(20)
        }
        assertEquals(before + 1, after)
    }

    @Test
    fun viewModelExposesReadyStateFromCore() = runBlocking {
        val vm = DocumentViewModel(core = NativeBridge, bridgeAvailable = true)
        val state = vm.buildState()
        assertTrue("estado inesperado: $state", state is SceneUiState.Ready)
        state as SceneUiState.Ready
        assertTrue(state.scene.primitives.isNotEmpty())
    }

    @Test
    fun viewModelReportsErrorWhenBridgeUnavailable() = runBlocking {
        val vm = DocumentViewModel(core = NativeBridge, bridgeAvailable = false)
        assertTrue(vm.buildState() is SceneUiState.Error)
    }
}
