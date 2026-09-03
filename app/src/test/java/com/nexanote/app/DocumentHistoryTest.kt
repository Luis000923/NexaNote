package com.nexanote.app

import com.nexanote.core.NativeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gestión del historial de edición del lado de Kotlin ([DocumentHistory], que es
 * en lo que el ViewModel delega Undo/Redo). Puro JVM: [FakeCore] reproduce la
 * semántica de instantáneas del núcleo Rust en memoria.
 */
class DocumentHistoryTest {

    /** Núcleo falso con un historial de instantáneas fiel al de Rust. */
    private class FakeCore : NativeCore {
        private val past = ArrayDeque<String>()
        private var present: String = ""
        private val future = ArrayDeque<String>()
        var undoCalls = 0
            private set

        override fun historyInit(documentJson: String): String {
            past.clear(); future.clear(); present = documentJson
            return TOKEN
        }

        override fun historyRecord(historyJson: String, documentJson: String): String {
            if (documentJson != present) {
                past.addLast(present)
                present = documentJson
                future.clear()
            }
            return TOKEN
        }

        override fun historyUndo(historyJson: String): String {
            undoCalls++
            if (past.isNotEmpty()) {
                future.addFirst(present)
                present = past.removeLast()
            }
            return TOKEN
        }

        override fun historyRedo(historyJson: String): String {
            if (future.isNotEmpty()) {
                past.addLast(present)
                present = future.removeFirst()
            }
            return TOKEN
        }

        override fun historyDocument(historyJson: String): String = present
        override fun historyStatus(historyJson: String): String = "${past.size},${future.size}"

        // -- Resto del contrato: no usado por estas pruebas --
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

        companion object {
            const val TOKEN = "H"
        }
    }

    @Test
    fun freshHistoryHasNothingToUndoOrRedo() {
        val h = DocumentHistory(FakeCore())
        h.begin("doc-0")
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertNull(h.undo())
        assertNull(h.redo())
    }

    @Test
    fun recordThenUndoAndRedoWalkTheFullSequence() {
        val h = DocumentHistory(FakeCore())
        h.begin("doc-0")
        h.record("doc-1")
        h.record("doc-2")
        h.record("doc-3")
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)

        assertEquals("doc-2", h.undo())
        assertEquals("doc-1", h.undo())
        assertEquals("doc-0", h.undo())
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)

        assertEquals("doc-1", h.redo())
        assertEquals("doc-2", h.redo())
        assertEquals("doc-3", h.redo())
        assertFalse(h.canRedo)
        assertTrue(h.canUndo)
    }

    @Test
    fun undoIsGuardedWhenStackIsEmpty() {
        val core = FakeCore()
        val h = DocumentHistory(core)
        h.begin("doc-0")
        h.record("doc-1")
        h.undo()
        assertNull(h.undo()) // ya en el fondo: no debe tocar el núcleo
        assertEquals(1, core.undoCalls)
    }

    @Test
    fun recordingAfterUndoClearsTheRedoBranch() {
        val h = DocumentHistory(FakeCore())
        h.begin("doc-0")
        h.record("doc-1")
        h.record("doc-2")
        h.undo()
        assertTrue(h.canRedo)

        h.record("doc-9") // nueva rama
        assertFalse(h.canRedo)
        assertTrue(h.canUndo)
        assertEquals("doc-1", h.undo())
    }

    @Test
    fun recordingAnIdenticalStateIsANoOp() {
        val h = DocumentHistory(FakeCore())
        h.begin("doc-0")
        h.record("doc-0")
        h.record("doc-0")
        assertFalse(h.canUndo)
    }

    @Test
    fun operationsBeforeBeginAreNoOps() {
        val h = DocumentHistory(FakeCore())
        h.record("doc-1")
        assertNull(h.undo())
        assertNull(h.redo())
        assertFalse(h.canUndo)
    }
}
