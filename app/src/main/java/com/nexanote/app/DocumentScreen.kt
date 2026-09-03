package com.nexanote.app

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexanote.app.canvas.CanvasTransform
import com.nexanote.app.canvas.DocumentCanvas
import com.nexanote.app.canvas.DrawingTool
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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("NexaNote") })
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
                    DocumentCanvas(
                        scene = s.scene,
                        transform = transform,
                        onTransformChange = { transform = it },
                        tool = tool,
                        onStrokeCommit = viewModel::commitStroke,
                        onShapeCommit = viewModel::commitShape,
                        onTextRequest = { x, y -> pendingText = x to y },
                        onFormulaRequest = { x, y -> pendingFormula = x to y },
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
