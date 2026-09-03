package com.nexanote.app.ai

/** Quién emitió un mensaje del chat de IA. */
enum class ChatRole { User, Assistant }

/**
 * Un turno de la conversación con el asistente. Es un dato plano y serializable:
 * la persistencia (por cuaderno) la hace [ChatStore] a través de [ChatHistoryCodec].
 */
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val timestampMs: Long,
)

/**
 * Historial de la conversación de IA de un cuaderno. Inmutable: cada turno nuevo
 * produce un historial nuevo con [appended], que además **acota** la memoria
 * (mismo espíritu que `History.DEFAULT_LIMIT` del núcleo):
 *
 *  - se conservan como mucho [MAX_MESSAGES] turnos, descartando los más antiguos;
 *  - el texto de cada turno se trunca a [MAX_MESSAGE_LEN] caracteres.
 *
 * Lógica pura, sin Android: se prueba en la JVM.
 */
data class ChatHistory(val messages: List<ChatMessage> = emptyList()) {

    /** Devuelve un historial nuevo con [message] al final, ya acotado y truncado. */
    fun appended(message: ChatMessage): ChatHistory {
        val trimmed = message.copy(text = message.text.take(MAX_MESSAGE_LEN))
        val next = (messages + trimmed)
        return ChatHistory(next.takeLast(MAX_MESSAGES))
    }

    val isEmpty: Boolean get() = messages.isEmpty()

    companion object {
        /** Turnos máximos que se guardan y se envían como contexto al proveedor. */
        const val MAX_MESSAGES = 40

        /** Longitud máxima (caracteres) del texto de un turno. */
        const val MAX_MESSAGE_LEN = 8000
    }
}
