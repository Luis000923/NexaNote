package com.nexanote.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexanote.app.canvas.NexaIcons
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla de inicio: el explorador de cuadernos locales.
 *
 * Lista lo que hay en [NotebookLibrary], permite crear un cuaderno eligiendo el
 * tipo de lienzo (lienzo infinito o páginas A4) y abre el elegido. Como el resto
 * de la interfaz, no usa un solo emoji: todos los símbolos son [NexaIcons].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    library: NotebookLibrary,
    onOpen: (Notebook) -> Unit,
) {
    var notebooks by remember { mutableStateOf<List<Notebook>?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Notebook?>(null) }
    var reloadToken by remember { mutableStateOf(0) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // El listado toca disco: nunca en el hilo principal. Antes de listar se
    // recupera, si la hay, la sesión guardada por una versión previa de la app.
    LaunchedEffect(reloadToken) {
        notebooks = withContext(Dispatchers.IO) {
            library.importLegacyAutosave(FileDocumentStore(context.applicationContext))
            library.list()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("NexaNote") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(NexaIcons.Add, contentDescription = "Nuevo cuaderno")
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val current = notebooks
            when {
                current == null -> Unit // primer listado en curso: sin parpadeo

                current.isEmpty() -> EmptyLibrary(
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(current, key = { it.id }) { notebook ->
                        NotebookCard(
                            notebook = notebook,
                            onOpen = { onOpen(notebook) },
                            onDelete = { pendingDelete = notebook },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateNotebookDialog(
            onDismiss = { showCreate = false },
            onConfirm = { title, canvas ->
                showCreate = false
                scope.launch {
                    val created = withContext(Dispatchers.IO) { library.create(title, canvas) }
                    reloadToken++
                    onOpen(created)
                }
            },
        )
    }

    pendingDelete?.let { notebook ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eliminar cuaderno") },
            text = { Text("Se borrará \"${notebook.title}\" y todo su contenido. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            withContext(Dispatchers.IO) { library.delete(notebook.id) }
                            reloadToken++
                        }
                    },
                ) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = NexaIcons.Notebook,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text("Todavía no hay cuadernos", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Crea el primero y elige si quieres un lienzo infinito o páginas A4.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotebookCard(
    notebook: Notebook,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = notebook.canvas.icon,
                contentDescription = notebook.canvas.label,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = notebook.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${notebook.canvas.label} · ${formatTimestamp(notebook.modifiedMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(NexaIcons.Delete, contentDescription = "Eliminar ${notebook.title}")
            }
        }
    }
}

/** Diálogo de creación: título libre y elección explícita del tipo de lienzo. */
@Composable
private fun CreateNotebookDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, CanvasKind) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var canvas by remember { mutableStateOf(CanvasKind.A4) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo cuaderno") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(NotebookLibrary.MAX_TITLE_LEN) },
                    label = { Text("Título") },
                    singleLine = true,
                )
                Text("Tipo de lienzo", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (kind in CanvasKind.entries) {
                        CanvasChoice(
                            kind = kind,
                            selected = kind == canvas,
                            onClick = { canvas = kind },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, canvas) }) { Text("Crear") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun CanvasChoice(
    kind: CanvasKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = kind.icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
        Text(
            text = kind.label + if (selected) " (elegido)" else "",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
    }
}

/** Icono vectorial que representa cada tipo de lienzo. */
private val CanvasKind.icon: ImageVector
    get() = when (this) {
        CanvasKind.A4 -> NexaIcons.PageA4
        CanvasKind.Infinite -> NexaIcons.InfiniteCanvas
    }

/** Fecha y hora local de la última edición, en el formato corto del sistema. */
private fun formatTimestamp(epochMs: Long): String =
    if (epochMs <= 0L) {
        "Sin abrir"
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
    }
