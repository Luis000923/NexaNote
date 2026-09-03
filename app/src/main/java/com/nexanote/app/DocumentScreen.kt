package com.nexanote.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexanote.app.canvas.CanvasTransform
import com.nexanote.app.canvas.DocumentCanvas
import com.nexanote.app.canvas.DrawingTool
import com.nexanote.app.canvas.GraphInput
import com.nexanote.app.canvas.NexaIcons

/**
 * Pantalla de visualización de documento (Fase 3): carga el documento de ejemplo
 * desde el núcleo Rust y lo pinta con [DocumentCanvas]. Los controles son
 * exclusivamente iconos vectoriales (sin emojis).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(viewModel: DocumentViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()
    val export by viewModel.export.collectAsState()

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
                title = { Text("NexaNote") },
                actions = {
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
                .padding(padding),
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
                        onStrokeCommit = viewModel::commitStroke,
                        onShapeCommit = viewModel::commitShape,
                        onTextRequest = { x, y -> pendingText = x to y },
                        onFormulaRequest = { x, y -> pendingFormula = x to y },
                        onGraphRequest = { x, y -> pendingGraph = x to y },
                        onImageRequest = { x, y -> pendingImage = x to y },
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
                    ToolPalette(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        selected = tool,
                        onSelect = { tool = it },
                    )
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
}

/** Herramientas disponibles, en el orden de la barra. */
private val TOOLS: List<Pair<DrawingTool, Pair<ImageVector, String>>> = listOf(
    DrawingTool.Pen to (NexaIcons.Pen to "Lápiz (mano alzada)"),
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
) {
    var content by remember { mutableStateOf("") }
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
            ) { Text("Añadir") }
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
    }
}

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
