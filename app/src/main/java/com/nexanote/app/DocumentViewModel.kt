package com.nexanote.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexanote.app.canvas.FormulaInput
import com.nexanote.app.canvas.GraphInput
import com.nexanote.app.canvas.SampleDocument
import com.nexanote.app.canvas.SceneParser
import com.nexanote.app.canvas.ScenePage
import com.nexanote.app.canvas.ShapeBounds
import com.nexanote.app.canvas.ShapeGeometry
import com.nexanote.app.canvas.ShapeKind
import com.nexanote.app.canvas.StrokeColor
import com.nexanote.app.canvas.StrokeGesture
import com.nexanote.app.canvas.StrokeSample
import com.nexanote.app.canvas.TextInput
import com.nexanote.core.NativeBridge
import com.nexanote.core.NativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado de la pantalla de visualización. */
sealed interface SceneUiState {
    data object Loading : SceneUiState
    data class Ready(val scene: ScenePage) : SceneUiState
    data class Error(val message: String) : SceneUiState
}

/** Estado de los controles de historial (habilitar/deshabilitar Undo y Redo). */
data class HistoryUiState(val canUndo: Boolean = false, val canRedo: Boolean = false)

/**
 * ViewModel de la pantalla de documento. No contiene lógica de dominio: pide al
 * núcleo Rust (a través de [NativeCore]) que construya el documento, conserva su
 * JSON y le añade los elementos que captura el lienzo, devolviendo cada vez la
 * escena de render.
 *
 * Fase 9 añade:
 *  - **Historial** ([DocumentHistory]): cada edición confirmada se registra; los
 *    botones de Deshacer/Rehacer navegan la pila en el núcleo (operación O(1)).
 *  - **Autosave / recuperación** ([DocumentStore]): tras cada edición el
 *    documento se persiste localmente con un pequeño rebote; al arrancar, si hay
 *    un estado guardado se recupera en lugar de reconstruir el de ejemplo.
 *
 * Todo el trabajo del puente (serialización incluida) y el de disco se hace
 * fuera del hilo principal.
 */
class DocumentViewModel(
    private val core: NativeCore = NativeBridge,
    private val bridgeAvailable: Boolean = NativeBridge.isLoaded,
    private val store: DocumentStore = DocumentStore.NoOp,
    private val loadDocument: (NativeCore) -> SampleDocument.LoadedDocument = {
        SampleDocument.buildDocument(it)
    },
    private val pageIdOf: (String) -> String = SampleDocument::firstPageId,
) : ViewModel() {

    /** Constructor sin argumentos requerido por la factoría por defecto de `viewModel()`. */
    constructor() : this(NativeBridge, NativeBridge.isLoaded)

    private val _state = MutableStateFlow<SceneUiState>(SceneUiState.Loading)
    val state: StateFlow<SceneUiState> = _state.asStateFlow()

    private val _history = MutableStateFlow(HistoryUiState())
    val history: StateFlow<HistoryUiState> = _history.asStateFlow()

    private val historyController = DocumentHistory(core)

    /** Estado del documento vivo en el núcleo. `@Volatile`: se toca desde varias corrutinas. */
    @Volatile
    private var documentJson: String? = null

    @Volatile
    private var pageId: String? = null

    @Volatile
    private var autosaveJob: Job? = null

    init {
        reload()
    }

    /** Recarga desde cero: descarta el estado guardado y reconstruye el documento de ejemplo. */
    fun reset() {
        store.clear()
        reload(fromAutosave = false)
    }

    fun reload() = reload(fromAutosave = true)

    private fun reload(fromAutosave: Boolean) {
        _state.value = SceneUiState.Loading
        viewModelScope.launch { _state.value = buildState(fromAutosave) }
    }

    /** Construye el estado inicial de forma síncrona-suspendida; reutilizable en tests. */
    suspend fun buildState(fromAutosave: Boolean = true): SceneUiState = withContext(Dispatchers.Default) {
        if (!bridgeAvailable) {
            return@withContext SceneUiState.Error("El núcleo nativo no está disponible")
        }
        runCatching {
            val recovered = if (fromAutosave) runCatching { store.restore() }.getOrNull() else null
            if (recovered != null) {
                documentJson = recovered
                pageId = pageIdOf(recovered)
            } else {
                val loaded = loadDocument(core)
                documentJson = loaded.documentJson
                pageId = loaded.pageId
            }
            historyController.begin(documentJson!!)
            publishHistory()
            SceneParser.parse(core.documentRenderPage(documentJson!!, 0))
        }.fold(
            onSuccess = { SceneUiState.Ready(it) },
            onFailure = { SceneUiState.Error(it.message ?: it.javaClass.simpleName) },
        )
    }

    fun commitStroke(samples: List<StrokeSample>) {
        if (samples.isEmpty()) return
        val doc = documentJson ?: return
        val page = pageId ?: return
        val snapshot = samples.toList()
        edit {
            val strokeJson = StrokeGesture.buildStrokeJson(
                snapshot, StrokeColor.Ink, StrokeGesture.DEFAULT_WIDTH,
            )
            core.documentAddStroke(doc, page, strokeJson)
        }
    }

    fun commitShape(kind: ShapeKind, bounds: ShapeBounds) {
        val doc = documentJson ?: return
        val page = pageId ?: return
        edit { core.documentAddShape(doc, page, ShapeGeometry.toShapeJson(kind, bounds)) }
    }

    fun commitText(x: Float, y: Float, content: String) {
        if (!TextInput.isCommittable(content)) return
        val doc = documentJson ?: return
        val page = pageId ?: return
        edit { core.documentAddText(doc, page, TextInput.toTextJson(content, x, y)) }
    }

    fun commitFormula(x: Float, y: Float, expression: String) {
        if (!FormulaInput.isCommittable(expression)) return
        val doc = documentJson ?: return
        val page = pageId ?: return
        edit { core.documentAddFormula(doc, page, FormulaInput.toFormulaJson(expression, x, y)) }
    }

    fun commitGraph(x: Float, y: Float, expression: String, xMin: Double, xMax: Double) {
        val doc = documentJson ?: return
        val page = pageId ?: return
        edit { core.documentAddGraph(doc, page, GraphInput.toGraphJson(expression, xMin, xMax, x, y)) }
    }

    /** Deshace la última edición confirmada. Instantáneo: el núcleo no re-ejecuta lógica. */
    fun undo() = navigate { historyController.undo() }

    /** Rehace la última edición deshecha. */
    fun redo() = navigate { historyController.redo() }

    /** Fuerza un guardado inmediato del documento activo (p. ej. al pausar la Activity). */
    fun flush() {
        val doc = documentJson ?: return
        autosaveJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.persist(doc) } }
    }

    /**
     * Ejecuta una edición del núcleo fuera del hilo principal: aplica el
     * documento resultante, lo registra en el historial, lo autoguarda y publica
     * la escena. Un fallo del núcleo deja la escena y el historial intactos.
     */
    private fun edit(mutate: suspend () -> String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val updated = mutate()
                    updated to SceneParser.parse(core.documentRenderPage(updated, 0))
                }
            }
            result.onSuccess { (updated, scene) ->
                documentJson = updated
                _state.value = SceneUiState.Ready(scene)
                withContext(Dispatchers.Default) { historyController.record(updated) }
                publishHistory()
                autosave(updated)
            }
        }
    }

    private fun navigate(step: () -> String?) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val doc = step() ?: return@runCatching null
                    doc to SceneParser.parse(core.documentRenderPage(doc, 0))
                }
            }
            result.onSuccess { pair ->
                pair?.let { (doc, scene) ->
                    documentJson = doc
                    _state.value = SceneUiState.Ready(scene)
                    publishHistory()
                    autosave(doc)
                }
            }
        }
    }

    private fun publishHistory() {
        _history.value = HistoryUiState(historyController.canUndo, historyController.canRedo)
    }

    /** Autosave por eventos clave con rebote: coalesce ráfagas de ediciones seguidas. */
    private fun autosave(documentJson: String) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(AUTOSAVE_DEBOUNCE_MS)
            runCatching { store.persist(documentJson) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        autosaveJob?.cancel()
    }

    private companion object {
        const val AUTOSAVE_DEBOUNCE_MS = 600L
    }
}
