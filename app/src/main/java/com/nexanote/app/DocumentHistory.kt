package com.nexanote.app

import com.nexanote.core.NativeCore

/**
 * Gestión del historial de edición (Undo/Redo) del lado de Kotlin.
 *
 * No implementa la lógica del historial -- esa vive en el núcleo Rust
 * (`document::history`) -- sino que conserva el *blob* JSON opaco que el núcleo
 * devuelve y ofrece a la UI una superficie cómoda: [canUndo] / [canRedo] y las
 * operaciones [undo] / [redo] que devuelven el nuevo documento (o `null` si no
 * había nada que hacer).
 *
 * Todas las llamadas al puente son baratas (deshacer/rehacer son O(1) en el
 * núcleo: no re-ejecutan lógica), así que pueden invocarse desde una corrutina
 * de fondo sin riesgo de tirones en la UI.
 */
class DocumentHistory(private val core: NativeCore) {

    private var historyJson: String? = null

    var canUndo: Boolean = false
        private set

    var canRedo: Boolean = false
        private set

    /** Ancla el historial en [documentJson]. Descarta cualquier historial previo. */
    fun begin(documentJson: String) {
        historyJson = core.historyInit(documentJson)
        refresh()
    }

    /** Registra [documentJson] como nuevo estado tras una edición confirmada. */
    fun record(documentJson: String) {
        val current = historyJson ?: return
        historyJson = core.historyRecord(current, documentJson)
        refresh()
    }

    /** Deshace un paso; devuelve el documento resultante o `null` si no procede. */
    fun undo(): String? {
        val current = historyJson ?: return null
        if (!canUndo) return null
        val updated = core.historyUndo(current)
        historyJson = updated
        refresh()
        return core.historyDocument(updated)
    }

    /** Rehace un paso; devuelve el documento resultante o `null` si no procede. */
    fun redo(): String? {
        val current = historyJson ?: return null
        if (!canRedo) return null
        val updated = core.historyRedo(current)
        historyJson = updated
        refresh()
        return core.historyDocument(updated)
    }

    private fun refresh() {
        val current = historyJson
        if (current == null) {
            canUndo = false
            canRedo = false
            return
        }
        val parts = core.historyStatus(current).split(',')
        canUndo = (parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0) > 0
        canRedo = (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0) > 0
    }
}
