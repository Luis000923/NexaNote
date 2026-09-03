package com.nexanote.app.ai

/** Tipo de contenido que el usuario pide explicar (ajusta el *system prompt*). */
enum class AssistKind(val label: String) {
    Text("Texto"),
    Formula("Fórmula"),
}

/**
 * Petición al asistente, ya independiente del proveedor concreto. El
 * [systemPrompt] fija el rol ("eres un tutor…") y [userPrompt] es el contenido a
 * explicar.
 */
data class AiRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val model: String,
    /** Cota superior de tokens de la respuesta; los proveedores la respetan. */
    val maxTokens: Int = 600,
    val temperature: Double = 0.2,
)

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

/**
 * Construye el prompt del sistema según el tipo de contenido. Puro: se prueba en
 * la JVM.
 */
object AssistPrompt {
    fun system(kind: AssistKind): String = when (kind) {
        AssistKind.Text ->
            "Eres un tutor que explica de forma clara y concisa, en español. " +
                "Explica el siguiente texto de un cuaderno de estudio, resaltando las ideas clave."
        AssistKind.Formula ->
            "Eres un tutor de matemáticas que explica en español. Explica paso a paso qué " +
                "representa la siguiente expresión o fórmula, qué significan sus símbolos y para qué se usa."
    }
}
