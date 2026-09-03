package com.nexanote.app.ai

/** Tipo de contenido que el usuario pide explicar (ajusta el *system prompt*). */
enum class AssistKind(val label: String) {
    Text("Texto"),
    Formula("Fórmula"),
}

/** Un turno de conversación tal y como lo consume un proveedor (`user`/`assistant`). */
data class AiMessage(val role: String, val content: String)

/**
 * Petición al asistente, ya independiente del proveedor concreto. El
 * [systemPrompt] fija el rol y [userPrompt] es el turno del usuario en las
 * llamadas de un solo tiro (p. ej. "explica esto").
 *
 * Para una conversación con memoria se rellena [messages] con el historial
 * completo; si va vacío, el proveedor usa `[user: userPrompt]`.
 */
data class AiRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val model: String,
    /** Historial de turnos; vacío = petición de un solo tiro con [userPrompt]. */
    val messages: List<AiMessage> = emptyList(),
    /** Cota superior de tokens de la respuesta; los proveedores la respetan. */
    val maxTokens: Int = 600,
    val temperature: Double = 0.2,
) {
    /** Turnos efectivos: el historial si lo hay, o el turno único de [userPrompt]. */
    val effectiveMessages: List<AiMessage>
        get() = messages.ifEmpty { listOf(AiMessage("user", userPrompt)) }
}

/** Resultado de una llamada al asistente. Nunca contiene la API key. */
sealed interface AiResult {
    data class Success(val text: String) : AiResult
    data class Failure(val message: String) : AiResult
}

/**
 * Abstracción de un proveedor de IA, desacoplada de la UI y de Android. Una
 * implementación traduce [AiRequest] al formato del proveedor, hace la llamada
 * HTTP en [kotlinx.coroutines.Dispatchers.IO] y normaliza la respuesta.
 */
interface AiProvider {
    val id: AiProviderId

    /** Ejecuta la petición. No lanza: los fallos se devuelven como [AiResult.Failure]. */
    suspend fun complete(request: AiRequest): AiResult
}
