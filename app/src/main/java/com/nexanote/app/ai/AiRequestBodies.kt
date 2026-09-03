package com.nexanote.app.ai

/**
 * Serialización del cuerpo JSON de las peticiones a cada proveedor. **Lógica
 * pura** (sin `org.json` ni Android) para poder cubrirla con tests de JVM y para
 * garantizar que la API key nunca entra en el cuerpo: la clave viaja sólo en las
 * cabeceras (ver `HttpAiProvider`).
 */
object AiRequestBodies {

    /** Cuerpo para el *endpoint* de chat de OpenAI (`/v1/chat/completions`). */
    fun openAi(request: AiRequest): String = buildString {
        append('{')
        append("\"model\":").append(str(request.model)).append(',')
        append("\"messages\":[")
        append("{\"role\":\"system\",\"content\":").append(str(request.systemPrompt)).append('}')
        for (m in request.effectiveMessages) {
            append(",{\"role\":").append(str(m.role))
            append(",\"content\":").append(str(m.content)).append('}')
        }
        append("],")
        append("\"max_tokens\":").append(request.maxTokens).append(',')
        append("\"temperature\":").append(request.temperature)
        append('}')
    }

    /** Cuerpo para el *endpoint* de mensajes de Anthropic (`/v1/messages`). */
    fun anthropic(request: AiRequest): String = buildString {
        append('{')
        append("\"model\":").append(str(request.model)).append(',')
        append("\"max_tokens\":").append(request.maxTokens).append(',')
        append("\"temperature\":").append(request.temperature).append(',')
        append("\"system\":").append(str(request.systemPrompt)).append(',')
        append("\"messages\":[")
        request.effectiveMessages.forEachIndexed { i, m ->
            if (i > 0) append(',')
            append("{\"role\":").append(str(m.role))
            append(",\"content\":").append(str(m.content)).append('}')
        }
        append("]}")
    }

    /** Escapa una cadena como literal JSON entre comillas (RFC 8259). */
    internal fun str(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
