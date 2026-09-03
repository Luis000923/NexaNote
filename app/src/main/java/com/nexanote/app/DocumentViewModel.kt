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

/**
 * ViewModel de la pantalla de documento. No contiene lógica de dominio: pide al
 * núcleo Rust (a través de [NativeCore]) que construya el documento de ejemplo,
 * conserva su JSON y le añade los trazos que captura el lienzo, devolviendo cada
 * vez la escena de render.
 *
 * Todo el trabajo del puente (serialización de trazos incluida) se hace en
 * [Dispatchers.Default], nunca en el hilo principal.
 */
class DocumentViewModel(
    private val core: NativeCore = NativeBridge,
    private val bridgeAvailable: Boolean = NativeBridge.isLoaded,
) : ViewModel() {

    /** Constructor sin argumentos requerido por la factoría por defecto de `viewModel()`. */
    constructor() : this(NativeBridge, NativeBridge.isLoaded)

    private val _state = MutableStateFlow<SceneUiState>(SceneUiState.Loading)
    val state: StateFlow<SceneUiState> = _state.asStateFlow()

    /** Estado del documento vivo en el núcleo. `@Volatile`: se toca desde varias corrutinas. */
    @Volatile
    private var documentJson: String? = null

    @Volatile
    private var pageId: String? = null

    init {
        reload()
    }

    fun reload() {
        _state.value = SceneUiState.Loading
        viewModelScope.launch { _state.value = buildState() }
    }

    /** Construye el estado inicial de forma síncrona-suspendida; reutilizable en tests. */
    suspend fun buildState(): SceneUiState = withContext(Dispatchers.Default) {
        if (!bridgeAvailable) {
            return@withContext SceneUiState.Error("El núcleo nativo no está disponible")
        }
        runCatching {
            val loaded = SampleDocument.buildDocument(core)
            documentJson = loaded.documentJson
            pageId = loaded.pageId
            SceneParser.parse(core.documentRenderPage(loaded.documentJson, 0))
        }.fold(
            onSuccess = { SceneUiState.Ready(it) },
            onFailure = { SceneUiState.Error(it.message ?: it.javaClass.simpleName) },
        )
    }

    /**
     * Persiste un trazo capturado por el lienzo en el núcleo Rust y publica la
     * escena re-renderizada. La serialización y la llamada FFI van fuera del hilo
     * principal; un fallo del núcleo deja la escena actual intacta.
     */
    fun commitStroke(samples: List<StrokeSample>) {
        if (samples.isEmpty()) return
        val doc = documentJson ?: return
        val page = pageId ?: return
        val snapshot = samples.toList()
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                runCatching {
                    val strokeJson = StrokeGesture.buildStrokeJson(
                        snapshot, StrokeColor.Ink, StrokeGesture.DEFAULT_WIDTH,
                    )
                    val updated = core.documentAddStroke(doc, page, strokeJson)
                    updated to SceneParser.parse(core.documentRenderPage(updated, 0))
                }
            }.onSuccess { (updated, scene) ->
                documentJson = updated
                _state.value = SceneUiState.Ready(scene)
            }
        }
    }

    /**
     * Persiste una forma geométrica dibujada en el lienzo. Como [commitStroke], la
     * serialización y la llamada FFI van fuera del hilo principal y un fallo del
     * núcleo (p. ej. forma degenerada) deja la escena actual intacta.
     */
    fun commitShape(kind: ShapeKind, bounds: ShapeBounds) {
        val doc = documentJson ?: return
        val page = pageId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                runCatching {
                    val shapeJson = ShapeGeometry.toShapeJson(kind, bounds)
                    val updated = core.documentAddShape(doc, page, shapeJson)
                    updated to SceneParser.parse(core.documentRenderPage(updated, 0))
                }
            }.onSuccess { (updated, scene) ->
                documentJson = updated
                _state.value = SceneUiState.Ready(scene)
            }
        }
    }

    /**
     * Persiste un bloque de texto creado en el lienzo. `(x, y)` es la línea base
     * del texto en coordenadas del documento. Como [commitStroke], la
     * serialización y la llamada FFI van fuera del hilo principal; el contenido
     * en blanco se ignora y un fallo del núcleo deja la escena actual intacta.
     */
    fun commitText(x: Float, y: Float, content: String) {
        if (!TextInput.isCommittable(content)) return
        val doc = documentJson ?: return
        val page = pageId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                runCatching {
                    val textJson = TextInput.toTextJson(content, x, y)
                    val updated = core.documentAddText(doc, page, textJson)
                    updated to SceneParser.parse(core.documentRenderPage(updated, 0))
                }
            }.onSuccess { (updated, scene) ->
                documentJson = updated
                _state.value = SceneUiState.Ready(scene)
            }
        }
    }

    /**
     * Persiste una fórmula matemática creada en el lienzo. `(x, y)` es la línea
     * base en coordenadas del documento. Como [commitStroke], la serialización y
     * la llamada FFI van fuera del hilo principal; la expresión en blanco se
     * ignora y un fallo del núcleo (sintaxis inválida) deja la escena intacta.
     */
    fun commitFormula(x: Float, y: Float, expression: String) {
        if (!FormulaInput.isCommittable(expression)) return
        val doc = documentJson ?: return
        val page = pageId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                runCatching {
                    val formulaJson = FormulaInput.toFormulaJson(expression, x, y)
                    val updated = core.documentAddFormula(doc, page, formulaJson)
                    updated to SceneParser.parse(core.documentRenderPage(updated, 0))
                }
            }.onSuccess { (updated, scene) ->
                documentJson = updated
                _state.value = SceneUiState.Ready(scene)
            }
        }
    }

    /**
     * Persiste una gráfica de función creada en el lienzo. `(x, y)` es la esquina
     * superior izquierda del marco en coordenadas del documento. Como
     * [commitStroke], la serialización, la llamada FFI y el muestreo de la curva
     * (que hace el núcleo al renderizar) van fuera del hilo principal; una entrada
     * inválida se ignora y un fallo del núcleo deja la escena actual intacta.
     */
    fun commitGraph(x: Float, y: Float, expression: String, xMin: Double, xMax: Double) {
        val doc = documentJson ?: return
        val page = pageId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                runCatching {
                    val graphJson = GraphInput.toGraphJson(expression, xMin, xMax, x, y)
                    val updated = core.documentAddGraph(doc, page, graphJson)
                    updated to SceneParser.parse(core.documentRenderPage(updated, 0))
                }
            }.onSuccess { (updated, scene) ->
                documentJson = updated
                _state.value = SceneUiState.Ready(scene)
            }
        }
    }
}
