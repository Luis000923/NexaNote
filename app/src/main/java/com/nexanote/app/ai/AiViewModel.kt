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

/**
 * ViewModel de la integración de IA.
 *
 * Fase 12: expone la configuración del usuario (proveedor + clave, respaldada por
 * almacenamiento cifrado).
 *
 * Fase 16: convierte al asistente en un **chat con memoria por cuaderno**. El
 * historial se carga y se guarda a través de un [ChatStore] que `MainActivity`
 * reasigna al abrir cada cuaderno ([openConversation]); los ajustes siguen siendo
 * globales. Cuando la IA responde con comandos JSON, se publican en
 * [pendingCommands] para que la capa de documento los ejecute sobre el lienzo.
 *
 * No conoce Android más allá de `ViewModel`: el trabajo de disco y de red va
 * fuera del hilo principal.
 */
class AiViewModel(
    private val repository: SecureSettingsRepository,
    private val providerFactory: (AiSettings) -> AiProvider? = AiProviders::forSettings,
    private var chatStore: ChatStore = ChatStore.NoOp,
) : ViewModel() {

    private val _settings = MutableStateFlow(AiSettings())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    private val _chat = MutableStateFlow(ChatHistory())

    /** Conversación vigente con el asistente (turnos de usuario y de la IA). */
    val chat: StateFlow<ChatHistory> = _chat.asStateFlow()

    private val _sending = MutableStateFlow(false)

    /** `true` mientras hay una petición al proveedor en curso. */
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _pendingCommands = MutableStateFlow<List<AiCommand>>(emptyList())

    /**
     * Comandos que la IA pide ejecutar sobre el lienzo, a la espera de que la capa
     * de documento los aplique y llame a [consumeCommands].
     */
    val pendingCommands: StateFlow<List<AiCommand>> = _pendingCommands.asStateFlow()

    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            _settings.value = withContext(Dispatchers.IO) {
                runCatching { repository.load() }.getOrDefault(AiSettings())
            }
        }
    }

    /** ¿Hay credenciales configuradas? */
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

    // -- Conversación (Fase 16) -------------------------------------------

    /**
     * Vincula la conversación al [store] del cuaderno recién abierto: cancela
     * cualquier envío en curso, descarta comandos pendientes y recarga el
     * historial guardado.
     */
    fun openConversation(store: ChatStore) {
        sendJob?.cancel()
        _sending.value = false
        _pendingCommands.value = emptyList()
        chatStore = store
        _chat.value = ChatHistory()
        viewModelScope.launch {
            _chat.value = withContext(Dispatchers.IO) {
                runCatching { store.load() }.getOrDefault(ChatHistory())
            }
        }
    }

    /**
     * Envía un mensaje del usuario. Añade su turno al historial, consulta al
     * proveedor con **toda** la conversación como contexto y añade el turno de la
     * IA. Si la respuesta trae comandos JSON, se publican en [pendingCommands].
     * Cualquier fallo se muestra como un turno más del asistente, sin romper nada.
     */
    fun send(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || _sending.value) return

        appendMessage(ChatMessage(ChatRole.User, text, now()))

        val provider = providerFactory(_settings.value)
        if (provider == null) {
            appendMessage(
                ChatMessage(
                    ChatRole.Assistant,
                    "Configura un proveedor y tu API key en Ajustes de IA.",
                    now(),
                ),
            )
            return
        }

        _sending.value = true
        sendJob = viewModelScope.launch {
            val request = AiRequest(
                systemPrompt = AiConversation.system(),
                userPrompt = text,
                model = _settings.value.effectiveModel,
                messages = AiConversation.toMessages(_chat.value),
                maxTokens = 900,
            )
            val result = runCatching { provider.complete(request) }
                .getOrElse { AiResult.Failure(it.javaClass.simpleName) }

            when (result) {
                is AiResult.Success -> {
                    val parsed = runCatching { AiCommandParser.parse(result.text) }
                        .getOrDefault(AiCommandParser.Parsed(result.text, emptyList()))
                    appendMessage(ChatMessage(ChatRole.Assistant, parsed.reply, now()))
                    if (parsed.commands.isNotEmpty()) _pendingCommands.value = parsed.commands
                }

                is AiResult.Failure ->
                    appendMessage(
                        ChatMessage(ChatRole.Assistant, "No se pudo responder: ${result.message}", now()),
                    )
            }
            _sending.value = false
        }
    }

    /**
     * Atajo de la Fase 12: pedir la explicación de un texto o fórmula
     * seleccionados. Se reencamina como un mensaje más de la conversación.
     */
    fun explain(content: String, kind: AssistKind) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        val prefix = when (kind) {
            AssistKind.Text -> "Explica este texto de mi cuaderno: "
            AssistKind.Formula -> "Explica esta fórmula de mi cuaderno: "
        }
        send(prefix + trimmed)
    }

    /** Vacía la conversación del cuaderno actual. */
    fun clearChat() {
        sendJob?.cancel()
        _sending.value = false
        _pendingCommands.value = emptyList()
        _chat.value = ChatHistory()
        persist(ChatHistory())
    }

    /** La capa de documento llama a esto tras aplicar los comandos al lienzo. */
    fun consumeCommands() {
        _pendingCommands.value = emptyList()
    }

    private fun appendMessage(message: ChatMessage) {
        val next = _chat.value.appended(message)
        _chat.value = next
        persist(next)
    }

    private fun persist(history: ChatHistory) {
        val store = chatStore
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.persist(history) } }
    }

    private fun now(): Long = System.currentTimeMillis()

    override fun onCleared() {
        super.onCleared()
        sendJob?.cancel()
    }
}
