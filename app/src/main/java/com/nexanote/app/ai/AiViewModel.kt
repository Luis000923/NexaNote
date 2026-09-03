package com.nexanote.app.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado del panel de asistencia de IA. */
sealed interface AssistUiState {
    data object Idle : AssistUiState
    data class Loading(val kind: AssistKind) : AssistUiState
    data class Answer(val kind: AssistKind, val prompt: String, val text: String) : AssistUiState
    data class Error(val message: String) : AssistUiState
}

/**
 * ViewModel de la integración de IA (Fase 12): expone la configuración del
 * usuario (proveedor + clave, respaldada por almacenamiento cifrado) y el flujo
 * de asistencia sobre un texto o fórmula.
 *
 * No conoce Android más allá de `ViewModel`: recibe el [SecureSettingsRepository]
 * ya construido y una fábrica de [AiProvider], lo que lo hace probable en la JVM.
 * El trabajo de disco y de red va fuera del hilo principal.
 */
class AiViewModel(
    private val repository: SecureSettingsRepository,
    private val providerFactory: (AiSettings) -> AiProvider? = AiProviders::forSettings,
) : ViewModel() {

    private val _settings = MutableStateFlow(AiSettings())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    private val _assist = MutableStateFlow<AssistUiState>(AssistUiState.Idle)
    val assist: StateFlow<AssistUiState> = _assist.asStateFlow()

    private var assistJob: Job? = null

    init {
        viewModelScope.launch {
            _settings.value = withContext(Dispatchers.IO) {
                runCatching { repository.load() }.getOrDefault(AiSettings())
            }
        }
    }

    /** ¿Hay credenciales configuradas? (para habilitar la acción de asistencia). */
    val isConfigured: Boolean
        get() = _settings.value.isConfigured

    fun saveSettings(provider: AiProviderId, apiKey: String, model: String) {
        val next = AiSettings(provider, apiKey, model)
        _settings.value = next
        viewModelScope.launch(Dispatchers.IO) { runCatching { repository.save(next) } }
    }

    fun clearSettings() {
        _settings.value = AiSettings()
        viewModelScope.launch(Dispatchers.IO) { runCatching { repository.clear() } }
    }

    /**
     * Pide al proveedor configurado que explique [content]. Cancela cualquier
     * petición en curso. El resultado se publica en [assist].
     */
    fun explain(content: String, kind: AssistKind) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            _assist.value = AssistUiState.Error("No hay nada que explicar.")
            return
        }
        val current = _settings.value
        val provider = providerFactory(current)
        if (provider == null) {
            _assist.value = AssistUiState.Error("Configura un proveedor y tu API key en Ajustes de IA.")
            return
        }

        assistJob?.cancel()
        _assist.value = AssistUiState.Loading(kind)
        assistJob = viewModelScope.launch {
            val request = AiRequest(
                systemPrompt = AssistPrompt.system(kind),
                userPrompt = trimmed,
                model = current.effectiveModel,
            )
            _assist.value = when (val result = provider.complete(request)) {
                is AiResult.Success -> AssistUiState.Answer(kind, trimmed, result.text)
                is AiResult.Failure -> AssistUiState.Error(result.message)
            }
        }
    }

    fun dismissAssist() {
        assistJob?.cancel()
        _assist.value = AssistUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        assistJob?.cancel()
    }
}
