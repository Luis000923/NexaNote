package com.nexanote.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nexanote.app.ai.AiProviderId
import com.nexanote.app.ai.AiSettings
import com.nexanote.app.ai.ChatHistory
import com.nexanote.app.ai.ChatRole
import com.nexanote.app.canvas.NexaIcons

/**
 * Diálogo de **Ajustes de IA** (Fase 12): elegir proveedor, introducir la API key
 * (oculta por defecto) y, opcionalmente, un modelo. La clave se entrega al
 * ViewModel, que la guarda cifrada; esta capa no la persiste ni la registra.
 * Controles 100% vectoriales, sin emojis.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsDialog(
    current: AiSettings,
    onDismiss: () -> Unit,
    onSave: (AiProviderId, String, String) -> Unit,
    onClear: () -> Unit,
) {
    var provider by remember { mutableStateOf(current.provider) }
    var apiKey by remember { mutableStateOf(current.apiKey) }
    var model by remember { mutableStateOf(current.model) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes de IA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Proveedor", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in AiProviderId.entries) {
                        FilterChip(
                            selected = provider == option,
                            onClick = { provider = option },
                            label = { Text(option.displayName) },
                        )
                    }
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Ocultar clave" else "Mostrar clave")
                }

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Modelo (opcional)") },
                    placeholder = { Text(provider.defaultModel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "Tu clave se guarda cifrada en este dispositivo (EncryptedSharedPreferences) " +
                        "y sólo se usa para llamar al proveedor que elijas.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(provider, apiKey, model) },
                enabled = apiKey.isNotBlank(),
            ) { Text("Guardar") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onClear) { Text("Borrar credenciales") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

/**
 * Panel de **chat con el asistente de IA** (Fase 16). Muestra la conversación del
 * cuaderno en burbujas y ofrece un campo de texto para escribir con el teclado.
 * La IA puede responder con explicaciones o insertando elementos en el lienzo;
 * eso lo gestiona el `AiViewModel`, aquí sólo se pinta la conversación.
 *
 * Sin emojis; el único icono es [NexaIcons.Send].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatPanel(
    history: ChatHistory,
    sending: Boolean,
    configured: Boolean,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(history.messages.size) {
        if (history.messages.isNotEmpty()) {
            listState.animateScrollToItem(history.messages.lastIndex)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Asistente de IA",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClear, enabled = !history.isEmpty) { Text("Limpiar") }
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }

                if (!configured) {
                    Text(
                        "Configura un proveedor y tu API key en Ajustes de IA para conversar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (history.isEmpty) {
                        item {
                            Text(
                                "Pide una explicación, o dile que escriba un texto, una fórmula " +
                                    "(incluidas matrices) o una gráfica en el cuaderno.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    itemsIndexed(history.messages) { _, message ->
                        ChatBubble(fromUser = message.role == ChatRole.User, text = message.text)
                    }
                    if (sending) {
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Text("Pensando...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(4000) },
                        label = { Text("Escribe un mensaje") },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (draft.isNotBlank() && !sending) {
                                    onSend(draft)
                                    draft = ""
                                }
                            },
                        ),
                    )
                    IconButton(
                        onClick = {
                            if (draft.isNotBlank() && !sending) {
                                onSend(draft)
                                draft = ""
                            }
                        },
                        enabled = draft.isNotBlank() && !sending,
                    ) {
                        Icon(NexaIcons.Send, contentDescription = "Enviar mensaje")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(fromUser: Boolean, text: String) {
    val bg = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = bg,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Text(
                text = text,
                color = fg,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
