package com.nexanote.app.ai

/**
 * Arma la conversación que se envía al proveedor: el *system prompt* estricto y el
 * mapeo del historial a mensajes. Lógica pura, sin Android.
 *
 * El prompt instruye a la IA a responder con **texto plano** (explicaciones) o con
 * **comandos JSON** (uno por línea) cuando el usuario pide escribir o dibujar algo
 * en el cuaderno. `AiCommandParser` es quien luego separa ambas cosas.
 *
 * Sin emojis, por diseño (regla de UI del proyecto).
 */
object AiConversation {

    fun system(): String = SYSTEM_PROMPT

    /** Historial -> lista de mensajes de proveedor, en orden y con el rol correcto. */
    fun toMessages(history: ChatHistory): List<AiMessage> =
        history.messages.map {
            AiMessage(
                role = if (it.role == ChatRole.User) "user" else "assistant",
                content = it.text,
            )
        }

    private val SYSTEM_PROMPT = buildString {
        append("Eres el asistente de NexaNote, un cuaderno digital. Respondes en español de forma clara y concisa. ")
        append("No uses emojis nunca. No uses Markdown.\n\n")
        append("Tienes dos modos de respuesta:\n")
        append("1. EXPLICAR: si el usuario pide una explicación o hace una pregunta, responde solo con texto plano.\n")
        append("2. ACTUAR: si el usuario pide escribir, anotar, insertar o dibujar algo en el cuaderno, ")
        append("responde con uno o más comandos JSON, cada uno en su propia línea, y como mucho una frase breve de texto.\n\n")
        append("Comandos disponibles (JSON exacto, sin claves adicionales):\n")
        append("""{"tool":"insert_text","text":"contenido del bloque de texto"}""").append('\n')
        append("""{"tool":"insert_formula","latex":"\\frac{-b+\\sqrt{b^2-4ac}}{2a}"}""").append('\n')
        append("""{"tool":"insert_formula","latex":"\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}"}""").append('\n')
        append("""{"tool":"insert_graph","expression":"x^2","x_min":-5,"x_max":5}""").append('\n')
        append('\n')
        append("Reglas: en 'latex' usa sintaxis LaTeX válida (matrices con \\begin{pmatrix}...\\end{pmatrix}). ")
        append("En 'expression' de las gráficas usa la variable x. ")
        append("No pongas los comandos dentro de bloques de código ni les añadas texto en la misma línea.")
    }
}
