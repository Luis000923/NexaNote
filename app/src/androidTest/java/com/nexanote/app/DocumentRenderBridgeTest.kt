package com.nexanote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexanote.app.canvas.SampleDocument
import com.nexanote.app.canvas.SceneParser
import com.nexanote.app.canvas.ScenePrimitive
import com.nexanote.app.canvas.SceneTemplate
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
