package com.nexanote.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nexanote.app.ai.AiProviderId
import com.nexanote.app.ai.AiSettings
import com.nexanote.app.ai.AssistKind
import com.nexanote.app.ai.AssistUiState

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
 * Diálogo del **asistente de IA**: el usuario pega un texto o una fórmula, elige
 * el tipo y pide una explicación al modelo configurado. La respuesta se muestra
 * aquí mismo, en un panel desplazable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistDialog(
    state: AssistUiState,
    onDismiss: () -> Unit,
    onExplain: (String, AssistKind) -> Unit,
) {
    var content by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AssistKind.Text) }
    val loading = state is AssistUiState.Loading

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Asistente de IA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in AssistKind.entries) {
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(4000) },
                    label = { Text("Texto o fórmula a explicar") },
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (state) {
                    is AssistUiState.Loading -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Consultando al modelo...")
                    }

                    is AssistUiState.Answer -> Text(
                        text = state.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    )

                    is AssistUiState.Error -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    AssistUiState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onExplain(content, kind) },
                enabled = !loading && content.isNotBlank(),
            ) { Text("Explicar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
    )
}
