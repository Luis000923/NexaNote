package com.nexanote.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexanote.app.canvas.CanvasTransform
import com.nexanote.app.canvas.DocumentCanvas
import com.nexanote.app.canvas.DrawingTool
import com.nexanote.app.canvas.GraphInput
import com.nexanote.app.canvas.NexaIcons
import com.nexanote.app.canvas.NexaPalette
import com.nexanote.app.canvas.Selection
import com.nexanote.app.canvas.StrokeColor
import com.nexanote.app.canvas.StrokeGesture
import com.nexanote.app.canvas.ScenePrimitive
import com.nexanote.app.ai.AiViewModel
import com.nexanote.app.ai.ChatStore

/**
 * Pantalla de edición de un cuaderno: pinta el documento del núcleo Rust con
 * [DocumentCanvas] y ofrece las herramientas de escritura, la selección de área
 * y la paleta de color. Los controles son exclusivamente iconos vectoriales
 * ([NexaIcons]): en toda la interfaz no hay un solo emoji.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    viewModel: DocumentViewModel = viewModel(),
    aiViewModel: AiViewModel,
    /** Persistencia de la conversación de IA de este cuaderno (Fase 16). */
    chatStore: ChatStore = ChatStore.NoOp,
    /** Título del cuaderno abierto, mostrado en la barra superior. */
    title: String = "NexaNote",
    /** Vuelve al explorador de cuadernos; `null` oculta el botón de volver. */
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()
    val pages by viewModel.pages.collectAsState()
    val export by viewModel.export.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val inkColor by viewModel.inkColor.collectAsState()
    val strokeWidth by viewModel.strokeWidth.collectAsState()

    val aiSettings by aiViewModel.settings.collectAsState()
    val chat by aiViewModel.chat.collectAsState()
    val sending by aiViewModel.sending.collectAsState()
    val pendingCommands by aiViewModel.pendingCommands.collectAsState()
    var showAiSettings by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }

    // Tamaño real del lienzo, para colocar en su centro lo que inserte la IA.
    var canvasSize by remember { mutableStateOf(IntSize(1000, 1600)) }

    LaunchedEffect(Unit) { aiViewModel.openConversation(chatStore) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Diálogo del sistema (Storage Access Framework) para elegir dónde guardar el
    // PDF: no requiere permisos y permite guardarlo o compartirlo desde el picker.
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportPdf(
                images = { source -> ImageImporter.decodeBitmap(context, source) },
                openStream = { context.contentResolver.openOutputStream(uri) },
            )
        }
    }

    LaunchedEffect(export.message) {
        export.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeExportState()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(NexaIcons.Back, contentDescription = "Volver a mis cuadernos")
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::addPage,
                        enabled = state is SceneUiState.Ready,
                    ) {
                        Icon(NexaIcons.PageAdd, contentDescription = "Nueva página")
                    }
                    IconButton(onClick = { showChat = true }) {
                        Icon(NexaIcons.Assistant, contentDescription = "Asistente de IA")
                    }
                    IconButton(onClick = { showAiSettings = true }) {
                        Icon(NexaIcons.Settings, contentDescription = "Ajustes de IA")
                    }
                    IconButton(
                        onClick = { pdfLauncher.launch("NexaNote.pdf") },
                        enabled = state is SceneUiState.Ready && export.phase != ExportPhase.Working,
                    ) {
                        Icon(NexaIcons.ExportPdf, contentDescription = "Exportar a PDF")
                    }
                    IconButton(onClick = viewModel::undo, enabled = history.canUndo) {
                        Icon(NexaIcons.Undo, contentDescription = "Deshacer")
                    }
                    IconButton(onClick = viewModel::redo, enabled = history.canRedo) {
                        Icon(NexaIcons.Redo, contentDescription = "Rehacer")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { if (it.width > 0 && it.height > 0) canvasSize = it },
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is SceneUiState.Loading -> CircularProgressIndicator()

                is SceneUiState.Error -> Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )

                is SceneUiState.Ready -> {
                    var transform by remember(s.scene.pageId) {
                        mutableStateOf(
                            CanvasTransform.fitToViewport(
                                s.scene.widthPx, s.scene.heightPx, 1000f, 1600f,
                            ),
                        )
                    }
                    var tool by remember { mutableStateOf(DrawingTool.Pen) }
                    // Paleta flotante abierta: tinta del trazo o relleno de la selección.
                    var palette by remember { mutableStateOf<PaletteMode?>(null) }
                    // Deslizador de grosor del lápiz abierto.
                    var showWidth by remember { mutableStateOf(false) }
                    // Posición (coords del documento) de un bloque de texto pendiente de escribir.
                    var pendingText by remember(s.scene.pageId) {
                        mutableStateOf<Pair<Float, Float>?>(null)
                    }
                    // Posición (coords del documento) de una fórmula pendiente de escribir.
                    var pendingFormula by remember(s.scene.pageId) {
                        mutableStateOf<Pair<Float, Float>?>(null)
                    }
                    // Esquina (coords del documento) de una gráfica pendiente de definir.
                    var pendingGraph by remember(s.scene.pageId) {
                        mutableStateOf<Pair<Float, Float>?>(null)
                    }
                    // Esquina (coords del documento) donde colocar una imagen aún por elegir.
                    var pendingImage by remember(s.scene.pageId) {
                        mutableStateOf<Pair<Float, Float>?>(null)
                    }
                    // Elemento (texto o fórmula) abierto para editar su contenido con el teclado.
                    var pendingEdit by remember(s.scene.pageId) {
                        mutableStateOf<EditTarget?>(null)
                    }

                    // Lo que la IA pidió insertar se coloca en el centro del viewport.
                    LaunchedEffect(pendingCommands) {
                        if (pendingCommands.isNotEmpty()) {
                            val (cx, cy) = transform.screenToModel(
                                canvasSize.width / 2f,
                                canvasSize.height / 2f,
                            )
                            viewModel.insertAiElements(pendingCommands, cx, cy)
                            aiViewModel.consumeCommands()
                        }
                    }

                    // Único elemento seleccionado que se puede reabrir para editar.
                    val editable: EditTarget? = editTargetFor(selection, s.scene)

                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()

                    // Caché de bitmaps por ruta relativa: se decodifican fuera del hilo de UI
                    // y se piden una sola vez por página.
                    val imageCache = remember(s.scene.pageId) {
                        mutableStateMapOf<String, ImageBitmap?>()
                    }
                    val imageProvider: (String) -> ImageBitmap? = provider@{ source ->
                        if (!imageCache.containsKey(source)) {
                            imageCache[source] = null
                            scope.launch {
                                ImageImporter.loadBitmap(context, source)?.let { imageCache[source] = it }
                            }
                        }
                        imageCache[source]
                    }

                    val imagePicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent(),
                    ) { uri ->
                        val pos = pendingImage
                        pendingImage = null
                        if (uri != null && pos != null) {
                            scope.launch {
                                ImageImporter.importFromUri(context, uri)?.let {
                                    viewModel.commitImage(pos.first, pos.second, it)
                                }
                            }
                        }
                    }
                    LaunchedEffect(pendingImage) {
                        if (pendingImage != null) imagePicker.launch("image/*")
                    }

                    DocumentCanvas(
                        scene = s.scene,
                        transform = transform,
                        onTransformChange = { transform = it },
                        tool = tool,
                        strokeWidth = strokeWidth,
                        inkColor = Color(inkColor.r, inkColor.g, inkColor.b, inkColor.a),
                        onStrokeCommit = viewModel::commitStroke,
                        onShapeCommit = viewModel::commitShape,
                        onTextRequest = { x, y -> pendingText = x to y },
                        onFormulaRequest = { x, y -> pendingFormula = x to y },
                        onGraphRequest = { x, y -> pendingGraph = x to y },
                        onImageRequest = { x, y -> pendingImage = x to y },
                        selection = selection,
                        onSelectArea = viewModel::selectArea,
                        onSelectTap = viewModel::selectAt,
                        onSelectionMove = viewModel::moveSelection,
                        imageProvider = imageProvider,
                        modifier = Modifier.fillMaxSize(),
                    )
                    pendingText?.let { (x, y) ->
                        EntryDialog(
                            title = "Nuevo bloque de texto",
                            label = "Contenido",
                            onDismiss = { pendingText = null },
                            onConfirm = { content ->
                                viewModel.commitText(x, y, content)
                                pendingText = null
                            },
                        )
                    }
                    pendingFormula?.let { (x, y) ->
                        EntryDialog(
                            title = "Nueva fórmula matemática",
                            label = "Expresión (p. ej. \\frac{a}{b} + \\sqrt{x})",
                            onDismiss = { pendingFormula = null },
                            onConfirm = { expression ->
                                viewModel.commitFormula(x, y, expression)
                                pendingFormula = null
                            },
                        )
                    }
                    pendingGraph?.let { (x, y) ->
                        GraphDialog(
                            onDismiss = { pendingGraph = null },
                            onConfirm = { expression, xMin, xMax ->
                                viewModel.commitGraph(x, y, expression, xMin, xMax)
                                pendingGraph = null
                            },
                        )
                    }
                    pendingEdit?.let { target ->
                        EntryDialog(
                            title = if (target.kind == ElementSourceKind.Text) "Editar texto" else "Editar fórmula",
                            label = "Contenido",
                            initial = target.source,
                            confirmLabel = "Guardar",
                            onDismiss = { pendingEdit = null },
                            onConfirm = { content ->
                                viewModel.editElement(target.id, target.kind, content, target.x, target.y)
                                viewModel.clearSelection()
                                pendingEdit = null
                            },
                        )
                    }
                    ToolPalette(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        selected = tool,
                        onSelect = { newTool ->
                            tool = newTool
                            // Salir de la selección la descarta: el marco no debe
                            // quedar flotando mientras se escribe con otra herramienta.
                            if (newTool != DrawingTool.Select) viewModel.clearSelection()
                        },
                        inkColor = inkColor,
                        strokeWidth = strokeWidth,
                        onOpenInkPalette = {
                            showWidth = false
                            palette = PaletteMode.Ink
                        },
                        onOpenWidth = {
                            palette = null
                            showWidth = !showWidth
                        },
                    )

                    if (showWidth) {
                        StrokeWidthPopup(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
                            width = strokeWidth,
                            onWidth = viewModel::setStrokeWidth,
                            onDismiss = { showWidth = false },
                        )
                    }

                    // Navegador de páginas: sólo cuando hay más de una y la barra
                    // de selección no está ocupando ese mismo borde superior.
                    if (!selection.isNotEmpty && pages.count > 1) {
                        PageNavigator(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp),
                            pages = pages,
                            onPrevious = { viewModel.goToPage(pages.index - 1) },
                            onNext = { viewModel.goToPage(pages.index + 1) },
                            onAddPage = viewModel::addPage,
                        )
                    }

                    // Barra contextual de la selección: eliminar, duplicar y color.
                    if (selection.isNotEmpty) {
                        SelectionBar(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp),
                            canFill = selection.hasFillable(s.scene.hits),
                            canEdit = editable != null,
                            onEdit = { pendingEdit = editable },
                            onDelete = viewModel::deleteSelection,
                            onDuplicate = viewModel::duplicateSelection,
                            onInk = { palette = PaletteMode.Ink },
                            onFill = { palette = PaletteMode.Fill },
                            onDismiss = viewModel::clearSelection,
                        )
                    }

                    palette?.let { mode ->
                        ColorPalette(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
                            mode = mode,
                            selected = inkColor,
                            onPick = { color ->
                                when (mode) {
                                    PaletteMode.Ink -> viewModel.applyInkColor(color)
                                    PaletteMode.Fill -> viewModel.applyFillColor(color)
                                }
                                palette = null
                            },
                            onClearFill = {
                                viewModel.applyFillColor(null)
                                palette = null
                            },
                            onDismiss = { palette = null },
                        )
                    }
                    CanvasControls(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        onZoomIn = { transform = transform.zoomBy(1.25f, 500f, 800f) },
                        onZoomOut = { transform = transform.zoomBy(0.8f, 500f, 800f) },
                        onFit = {
                            transform = CanvasTransform.fitToViewport(
                                s.scene.widthPx, s.scene.heightPx, 1000f, 1600f,
                            )
                        },
                        onReload = viewModel::reload,
                    )
                }
            }
        }
    }

    if (showAiSettings) {
        AiSettingsDialog(
            current = aiSettings,
            onDismiss = { showAiSettings = false },
            onSave = { provider, key, model ->
                aiViewModel.saveSettings(provider, key, model)
                showAiSettings = false
            },
            onClear = {
                aiViewModel.clearSettings()
                showAiSettings = false
            },
        )
    }

    if (showChat) {
        AiChatPanel(
            history = chat,
            sending = sending,
            configured = aiSettings.isConfigured,
            onSend = aiViewModel::send,
            onClear = aiViewModel::clearChat,
            onDismiss = { showChat = false },
        )
    }
}

/** Qué está eligiendo la paleta de color abierta. */
private enum class PaletteMode { Ink, Fill }

/** Un elemento seleccionado cuyo contenido fuente se puede reabrir para editarlo. */
private data class EditTarget(
    val id: String,
    val kind: ElementSourceKind,
    val source: String,
    val x: Float,
    val y: Float,
)

/**
 * Devuelve el [EditTarget] si hay **exactamente un** elemento seleccionado y es un
 * bloque de texto o una fórmula. `hits` y `primitives` de la escena van en el
 * mismo orden (ver `RenderModels.kt`), así que basta con localizar el índice del
 * id y leer su primitiva.
 */
private fun editTargetFor(selection: Selection, scene: com.nexanote.app.canvas.ScenePage): EditTarget? {
    val id = selection.ids.singleOrNull() ?: return null
    val index = scene.hits.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
    return when (val primitive = scene.primitives.getOrNull(index)) {
        is ScenePrimitive.Text ->
            EditTarget(id, ElementSourceKind.Text, primitive.content, primitive.origin.x, primitive.origin.y)
        is ScenePrimitive.Formula ->
            EditTarget(id, ElementSourceKind.Formula, primitive.latex, primitive.origin.x, primitive.origin.y)
        else -> null
    }
}

/** Herramientas disponibles, en el orden de la barra. */
private val TOOLS: List<Pair<DrawingTool, Pair<ImageVector, String>>> = listOf(
    DrawingTool.Pen to (NexaIcons.Pen to "Lápiz (mano alzada)"),
    DrawingTool.Select to (NexaIcons.Select to "Seleccionar área"),
    DrawingTool.Line to (NexaIcons.ShapeLine to "Línea"),
    DrawingTool.Rectangle to (NexaIcons.ShapeRectangle to "Rectángulo"),
    DrawingTool.Ellipse to (NexaIcons.ShapeEllipse to "Elipse"),
    DrawingTool.Arrow to (NexaIcons.ShapeArrow to "Flecha"),
    DrawingTool.Text to (NexaIcons.TextTool to "Texto"),
    DrawingTool.Formula to (NexaIcons.Formula to "Fórmula matemática"),
    DrawingTool.Graph to (NexaIcons.GraphTool to "Gráfica de función"),
    DrawingTool.Image to (NexaIcons.Image to "Imagen"),
    DrawingTool.Pan to (NexaIcons.Hand to "Navegación"),
)

/** Diálogo de entrada de una sola línea/párrafo (bloque de texto o fórmula). */
@Composable
private fun EntryDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
    confirmLabel: String = "Añadir",
) {
    var content by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it.take(4096) },
                label = { Text(label) },
                singleLine = false,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(content) },
                enabled = content.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

/** Diálogo para definir una gráfica de función: expresión y dominio `[x min, x max]`. */
@Composable
private fun GraphDialog(
    onDismiss: () -> Unit,
    onConfirm: (expression: String, xMin: Double, xMax: Double) -> Unit,
) {
    var expression by remember { mutableStateOf("") }
    var xMin by remember { mutableStateOf(GraphInput.DEFAULT_X_MIN.toString()) }
    var xMax by remember { mutableStateOf(GraphInput.DEFAULT_X_MAX.toString()) }
    val validation = GraphInput.validate(expression, xMin, xMax)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva gráfica de función") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = expression,
                    onValueChange = { expression = it.take(GraphInput.MAX_LEN) },
                    label = { Text("Función y = f(x), p. ej. x^2 o \\sin(x)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = xMin,
                    onValueChange = { xMin = it },
                    label = { Text("x mínimo") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = xMax,
                    onValueChange = { xMax = it },
                    label = { Text("x máximo") },
                    singleLine = true,
                )
                if (validation is GraphInput.Validation.Invalid && expression.isNotBlank()) {
                    Text(
                        text = validation.reason,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    (validation as? GraphInput.Validation.Valid)?.let {
                        onConfirm(it.expression, it.xMin, it.xMax)
                    }
                },
                enabled = validation is GraphInput.Validation.Valid,
            ) { Text("Graficar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun ToolPalette(
    modifier: Modifier,
    selected: DrawingTool,
    onSelect: (DrawingTool) -> Unit,
    inkColor: StrokeColor,
    strokeWidth: Float,
    onOpenInkPalette: () -> Unit,
    onOpenWidth: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for ((tool, iconAndLabel) in TOOLS) {
            val (icon, label) = iconAndLabel
            ToolButton(
                icon = icon,
                description = label,
                selected = tool == selected,
                onClick = { onSelect(tool) },
            )
        }
        // Grosor del lápiz: abre el deslizador y muestra el calibre vigente como
        // un punto proporcional, sin ningún texto ni emoji.
        Box(contentAlignment = Alignment.Center) {
            FilledTonalIconButton(onClick = onOpenWidth) {
                Icon(NexaIcons.LineWeight, contentDescription = "Grosor del lápiz")
            }
            Box(
                modifier = Modifier
                    .size(strokeWidth.coerceIn(2f, 16f).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
        // Abre la paleta y, a la vez, muestra el color de tinta vigente.
        Box(contentAlignment = Alignment.Center) {
            FilledTonalIconButton(onClick = onOpenInkPalette) {
                Icon(NexaIcons.Palette, contentDescription = "Color de tinta")
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(inkColor.toComposeColor())
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
    }
}

/**
 * Deslizador flotante del grosor del lápiz. El cambio se aplica en vivo: la vista
 * previa del trazo y el próximo [DocumentViewModel.commitStroke] ya usan el valor
 * nuevo. Una muestra circular refleja el calibre a escala real.
 */
@Composable
private fun StrokeWidthPopup(
    modifier: Modifier,
    width: Float,
    onWidth: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width.coerceIn(2f, 24f).dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface),
                )
            }
            Slider(
                value = width,
                onValueChange = onWidth,
                valueRange = StrokeGesture.MIN_WIDTH..StrokeGesture.MAX_WIDTH,
                modifier = Modifier.width(200.dp),
            )
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    }
}

/**
 * Navegador de páginas: anterior / "n de total" / siguiente, y un atajo para
 * añadir una página nueva. Sólo iconos vectoriales, ningún emoji.
 */
@Composable
private fun PageNavigator(
    modifier: Modifier,
    pages: PageUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAddPage: () -> Unit,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = pages.hasPrevious) {
                Icon(NexaIcons.Back, contentDescription = "Página anterior")
            }
            Text(
                text = "${pages.index + 1} / ${pages.count}",
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = onNext, enabled = pages.hasNext) {
                Icon(NexaIcons.Forward, contentDescription = "Página siguiente")
            }
            IconButton(onClick = onAddPage) {
                Icon(NexaIcons.PageAdd, contentDescription = "Nueva página")
            }
        }
    }
}

/**
 * Barra contextual de la selección. Aparece en cuanto el núcleo devuelve algo
 * seleccionado -- tanto al tocar un elemento como al delimitar un área -- y es la
 * vía para eliminarlo, duplicarlo o cambiarle el color.
 */
@Composable
private fun SelectionBar(
    modifier: Modifier,
    canFill: Boolean,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onInk: () -> Unit,
    onFill: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onEdit, enabled = canEdit) {
                Icon(NexaIcons.Edit, contentDescription = "Editar el contenido")
            }
            IconButton(onClick = onDelete) {
                Icon(NexaIcons.Delete, contentDescription = "Eliminar la selección")
            }
            IconButton(onClick = onDuplicate) {
                Icon(NexaIcons.Duplicate, contentDescription = "Duplicar la selección")
            }
            IconButton(onClick = onInk) {
                Icon(NexaIcons.Palette, contentDescription = "Color de la selección")
            }
            IconButton(onClick = onFill, enabled = canFill) {
                Icon(NexaIcons.Fill, contentDescription = "Relleno de la selección")
            }
            IconButton(onClick = onDismiss) {
                Icon(NexaIcons.Back, contentDescription = "Cancelar la selección")
            }
        }
    }
}

/**
 * Paleta flotante de color. Los colores son de documento (se guardan en el
 * modelo), por eso no salen del esquema del tema. Cada muestra es un círculo
 * vectorial, sin ningún emoji.
 */
@Composable
private fun ColorPalette(
    modifier: Modifier,
    mode: PaletteMode,
    selected: StrokeColor,
    onPick: (StrokeColor) -> Unit,
    onClearFill: () -> Unit,
    onDismiss: () -> Unit,
) {
    val swatches = when (mode) {
        PaletteMode.Ink -> NexaPalette.Swatches
        PaletteMode.Fill -> NexaPalette.Fills
    }
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (mode == PaletteMode.Ink) "Color de tinta" else "Relleno",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (swatch in swatches) {
                    Swatch(
                        swatch = swatch,
                        selected = mode == PaletteMode.Ink && swatch.color == selected,
                        onClick = { onPick(swatch.color) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == PaletteMode.Fill) {
                    TextButton(onClick = onClearFill) { Text("Sin relleno") }
                }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        }
    }
}

@Composable
private fun Swatch(
    swatch: NexaPalette.Swatch,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(if (selected) 34.dp else 30.dp)
            .clip(CircleShape)
            .background(swatch.color.toComposeColor())
            .border(if (selected) 3.dp else 1.dp, outline, CircleShape)
            .clickable(onClick = onClick)
            .semanticsLabel(swatch.label, selected),
    )
}

/** Etiqueta accesible de una muestra de color (no hay texto visible que leer). */
private fun Modifier.semanticsLabel(label: String, selected: Boolean): Modifier =
    this.then(
        Modifier.semantics {
            contentDescription = if (selected) "$label (elegido)" else label
        },
    )

/** Color de documento a color de Compose. */
private fun StrokeColor.toComposeColor(): Color = Color(r, g, b, a)

@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledIconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = "$description (activa)")
        }
    } else {
        FilledTonalIconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = description)
        }
    }
}

@Composable
private fun CanvasControls(
    modifier: Modifier,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onReload: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ControlButton(NexaIcons.ZoomIn, "Acercar", onZoomIn)
        ControlButton(NexaIcons.ZoomOut, "Alejar", onZoomOut)
        ControlButton(NexaIcons.FitScreen, "Ajustar a pantalla", onFit)
        ControlButton(NexaIcons.Refresh, "Recargar documento", onReload)
    }
}

@Composable
private fun ControlButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = description)
    }
}
