package com.nexanote.app

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexanote.app.canvas.FormulaInput
import com.nexanote.app.canvas.GraphInput
import com.nexanote.app.canvas.ImageInput
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
import com.nexanote.app.pdf.AndroidPdfWriter
import com.nexanote.app.pdf.PdfWriter
import com.nexanote.core.NativeBridge
import com.nexanote.core.NativeCore
import java.io.OutputStream
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

/** Fase de la exportación a PDF (Fase 11). */
enum class ExportPhase { Idle, Working, Done, Failed }

/** Estado del flujo de exportación a PDF, para dar retroalimentación en la UI. */
data class PdfExportUiState(
    val phase: ExportPhase = ExportPhase.Idle,
    /** Mensaje breve para mostrar una sola vez (éxito o error); `null` en reposo. */
    val message: String? = null,
)

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
    /** Renderiza la página `index` del documento a su escena. Inyectable para tests sin `org.json`. */
    private val renderScene: (String, Int) -> ScenePage = { documentJson, index ->
        SceneParser.parse(core.documentRenderPage(documentJson, index))
    },
    /** Número de páginas del documento. Inyectable para tests sin `org.json`. */
    private val pageCountOf: (String) -> Int = SampleDocument::pageCount,
    /** Serializador de PDF. Inyectable para tests (la API nativa no existe en JVM). */
    private val pdfWriter: PdfWriter = AndroidPdfWriter,
) : ViewModel() {

    /** Constructor sin argumentos requerido por la factoría por defecto de `viewModel()`. */
    constructor() : this(NativeBridge, NativeBridge.isLoaded)

    private val _state = MutableStateFlow<SceneUiState>(SceneUiState.Loading)
    val state: StateFlow<SceneUiState> = _state.asStateFlow()

    private val _history = MutableStateFlow(HistoryUiState())
    val history: StateFlow<HistoryUiState> = _history.asStateFlow()

    private val _export = MutableStateFlow(PdfExportUiState())
    val export: StateFlow<PdfExportUiState> = _export.asStateFlow()

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
            renderScene(documentJson!!, 0)
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

    /**
     * Inserta en el lienzo una imagen ya importada al almacén local por
     * [ImageImporter]. El marco inicial conserva la proporción del bitmap. La
     * validación final (ruta relativa, marco sensato) la hace el núcleo Rust.
     */
    fun commitImage(x: Float, y: Float, image: ImportedImage) {
        if (!ImageInput.isCommittable(image.source)) return
        val doc = documentJson ?: return
        val page = pageId ?: return
        val natW = image.naturalWidth.toFloat()
        val natH = image.naturalHeight.toFloat()
        val (w, h) = ImageInput.fitFrame(natW, natH)
        edit {
            core.documentAddImage(doc, page, ImageInput.toImageJson(image.source, x, y, w, h, natW, natH))
        }
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
     * Exporta el documento activo a un **PDF multipágina** (Fase 11).
     *
     * Todo el trabajo -- renderizar cada página en el núcleo, rasterizar las
     * primitivas y escribir el PDF -- ocurre fuera del hilo principal
     * (`Dispatchers.Default` para CPU, `Dispatchers.IO` para el volcado). La UI
     * sólo observa [export].
     *
     * @param images resuelve la ruta relativa de una imagen a su bitmap (la capa
     *   Android la decodifica del almacén local); devuelve `null` si no procede.
     * @param openStream abre el destino del PDF (p. ej. el `OutputStream` de un
     *   `Uri` del Storage Access Framework). Se invoca ya fuera del hilo de UI.
     */
    fun exportPdf(images: (String) -> Bitmap?, openStream: () -> OutputStream?) {
        val doc = documentJson
        if (doc == null) {
            _export.value = PdfExportUiState(ExportPhase.Failed, "El documento aún no está listo")
            return
        }
        if (_export.value.phase == ExportPhase.Working) return
        _export.value = PdfExportUiState(ExportPhase.Working)

        viewModelScope.launch {
            val result = runCatching {
                val pages = withContext(Dispatchers.Default) {
                    val count = pageCountOf(doc).coerceAtLeast(1)
                    (0 until count).map { index -> renderScene(doc, index) }
                }
                withContext(Dispatchers.IO) {
                    val stream = openStream() ?: error("No se pudo abrir el destino del PDF")
                    stream.use { pdfWriter.write(pages, images, it) }
                }
                pages.size
            }
            _export.value = result.fold(
                onSuccess = { n ->
                    PdfExportUiState(
                        ExportPhase.Done,
                        "PDF exportado ($n ${if (n == 1) "página" else "páginas"})",
                    )
                },
                onFailure = {
                    PdfExportUiState(ExportPhase.Failed, it.message ?: "No se pudo exportar el PDF")
                },
            )
        }
    }

    /** La UI llama a esto tras mostrar el mensaje de [export] para volver a reposo. */
    fun consumeExportState() {
        _export.value = PdfExportUiState()
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
                    updated to renderScene(updated, 0)
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
                    doc to renderScene(doc, 0)
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
