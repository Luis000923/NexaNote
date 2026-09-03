package com.nexanote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexanote.core.NativeBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Valida que la librería nativa (`libnexanote_core`) se carga y que el puente
 * JNI responde correctamente.
 */
@RunWith(AndroidJUnit4::class)
class NativeBridgeTest {

    @Test
    fun nativeLibraryLoads() {
        assertTrue("libnexanote_core no se cargó", NativeBridge.isLoaded)
    }

    @Test
    fun greetingCrossesTheBridge() {
        assertEquals("Hola, NexaNote", NativeBridge.greeting("NexaNote"))
    }

    @Test
    fun coreReportsVersion() {
        assertEquals("0.1.0", NativeBridge.coreVersion())
    }

    @Test
    fun documentModelIsBuiltInRustAcrossTheBridge() {
        var doc = NativeBridge.documentCreate("Cuaderno de prueba")
        val pageId = JSONObject(
            NativeBridge.documentAddPage(doc, "{}").also { doc = it },
        ).getJSONArray("pages").getJSONObject(0).getString("id")

        val element = """
            {"type":"Text","content":"hola","position":{"x":0.0,"y":0.0},
             "style":{"font_size":16.0,"bold":false,"italic":false,"underline":false,
             "color":{"r":0,"g":0,"b":0,"a":255}},"max_width":null}
        """.trimIndent()
        doc = NativeBridge.documentAddElement(doc, pageId, element)

        val summary = JSONObject(NativeBridge.documentSummary(doc))
        assertEquals("Cuaderno de prueba", summary.getString("title"))
        assertEquals(1, summary.getInt("page_count"))
        assertEquals(1, summary.getInt("element_count"))
    }

    @Test
    fun textBlockIsSanitizedAndPersistedInRust() {
        var doc = NativeBridge.documentCreate("Texto")
        val pageId = JSONObject(
            NativeBridge.documentAddPage(doc, "{}").also { doc = it },
        ).getJSONArray("pages").getJSONObject(0).getString("id")

        val text = """
            {"content":"  Hola mundo  ","position":{"x":12.0,"y":24.0},
             "style":{"font_size":20.0,"bold":true,"italic":false,"underline":false,
             "color":{"r":0,"g":0,"b":0,"a":255}},"max_width":null}
        """.trimIndent()
        doc = NativeBridge.documentAddText(doc, pageId, text)

        val scene = JSONObject(NativeBridge.documentRenderPage(doc, 0))
        val prim = scene.getJSONArray("primitives").getJSONObject(0)
        assertEquals("Text", prim.getString("type"))
        assertEquals("Hola mundo", prim.getString("content"))
    }

    @Test(expected = IllegalStateException::class)
    fun emptyTextBlockRaisesInsteadOfCrashing() {
        var doc = NativeBridge.documentCreate("d")
        val pageId = JSONObject(
            NativeBridge.documentAddPage(doc, "{}").also { doc = it },
        ).getJSONArray("pages").getJSONObject(0).getString("id")
        val blank = """
            {"content":"   ","position":{"x":0.0,"y":0.0},
             "style":{"font_size":16.0,"bold":false,"italic":false,"underline":false,
             "color":{"r":0,"g":0,"b":0,"a":255}},"max_width":null}
        """.trimIndent()
        NativeBridge.documentAddText(doc, pageId, blank)
    }

    @Test
    fun historyUndoAndRedoCrossTheBridge() {
        var doc = NativeBridge.documentCreate("Historial")
        val pageId = JSONObject(
            NativeBridge.documentAddPage(doc, "{}").also { doc = it },
        ).getJSONArray("pages").getJSONObject(0).getString("id")

        var history = NativeBridge.historyInit(doc)
        assertEquals("0,0", NativeBridge.historyStatus(history))

        val stroke = """
            {"points":[{"position":{"x":1.0,"y":2.0},"pressure":0.5,"timestamp_ms":0}],
             "color":{"r":0,"g":0,"b":0,"a":255},"width":2.0}
        """.trimIndent()
        doc = NativeBridge.documentAddStroke(doc, pageId, stroke)
        history = NativeBridge.historyRecord(history, doc)
        assertEquals("1,0", NativeBridge.historyStatus(history))

        history = NativeBridge.historyUndo(history)
        assertEquals("0,1", NativeBridge.historyStatus(history))
        val backToEmpty = JSONObject(NativeBridge.documentSummary(NativeBridge.historyDocument(history)))
        assertEquals(0, backToEmpty.getInt("element_count"))

        history = NativeBridge.historyRedo(history)
        val redone = JSONObject(NativeBridge.documentSummary(NativeBridge.historyDocument(history)))
        assertEquals(1, redone.getInt("element_count"))
    }

    @Test(expected = IllegalStateException::class)
    fun invalidPageIdRaisesInsteadOfCrashing() {
        val doc = NativeBridge.documentCreate("d")
        NativeBridge.documentRemovePage(doc, "not-a-valid-id")
    }
}
