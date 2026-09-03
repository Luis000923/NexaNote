package com.nexanote.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
                    DocumentCanvas(
                        scene = s.scene,
                        transform = transform,
                        onTransformChange = { transform = it },
                        tool = tool,
                        onStrokeCommit = viewModel::commitStroke,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CanvasControls(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        tool = tool,
                        onToggleTool = {
                            tool = if (tool == DrawingTool.Pen) DrawingTool.Pan else DrawingTool.Pen
                        },
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

@Composable
private fun CanvasControls(
    modifier: Modifier,
    tool: DrawingTool,
    onToggleTool: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onReload: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (tool) {
            DrawingTool.Pen -> ControlButton(NexaIcons.Pen, "Herramienta: lápiz (toca para navegar)", onToggleTool)
            DrawingTool.Pan -> ControlButton(NexaIcons.Hand, "Herramienta: navegación (toca para escribir)", onToggleTool)
        }
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
