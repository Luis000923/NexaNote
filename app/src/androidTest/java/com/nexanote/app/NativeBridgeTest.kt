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

    @Test(expected = IllegalStateException::class)
    fun invalidPageIdRaisesInsteadOfCrashing() {
        val doc = NativeBridge.documentCreate("d")
        NativeBridge.documentRemovePage(doc, "not-a-valid-id")
    }
}
