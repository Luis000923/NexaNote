package com.nexanote.app.ai

import org.json.JSONObject

/**
 * Extrae de la respuesta del asistente los **comandos JSON** que pide ejecutar en
 * el lienzo, y deja aparte el texto conversacional.
 *
 * Es deliberadamente tolerante: la IA alucina. Un objeto sin `tool` reconocido o
 * con campos inválidos se ignora; una respuesta sin ningún JSON válido se
 * devuelve tal cual como [Parsed.reply] sin comandos. **Nunca lanza.**
 *
 * Lógica pura (sólo `org.json`, disponible en los tests de JVM).
 */
object AiCommandParser {

    /** Resultado del análisis: el texto a mostrar y los comandos a ejecutar. */
    data class Parsed(val reply: String, val commands: List<AiCommand>)

    fun parse(assistantText: String?): Parsed {
        val text = assistantText ?: return Parsed("", emptyList())
        val commands = mutableListOf<AiCommand>()
        // Rangos (en `text`) de los objetos JSON que sí se reconocieron como comando.
        val consumed = mutableListOf<IntRange>()

        for ((range, candidate) in topLevelJsonObjects(text)) {
            val command = runCatching { toCommand(JSONObject(candidate)) }.getOrNull() ?: continue
            commands += command
            consumed += range
        }

        if (commands.isEmpty()) return Parsed(text.trim(), emptyList())

        val reply = stripRanges(text, consumed).trim()
            .ifBlank {
                val n = commands.size
                if (n == 1) "He añadido 1 elemento al lienzo." else "He añadido $n elementos al lienzo."
            }
        return Parsed(reply, commands)
    }

    /** Mapea un objeto JSON a un [AiCommand], o `null` si no es un comando válido. */
    private fun toCommand(obj: JSONObject): AiCommand? = when (obj.optString("tool").trim().lowercase()) {
        "insert_text" -> obj.optString("text").trim().takeIf { it.isNotEmpty() }?.let(AiCommand::InsertText)

        "insert_formula", "insert_matrix" ->
            obj.optString("latex").trim().ifBlank { obj.optString("expression").trim() }
                .takeIf { it.isNotEmpty() }
                ?.let(AiCommand::InsertFormula)

        "insert_graph" -> {
            val expr = obj.optString("expression").trim().ifBlank { obj.optString("function").trim() }
            if (expr.isEmpty()) {
                null
            } else {
                val lo = finiteOr(obj.opt("x_min"), DEFAULT_X_MIN)
                val hi = finiteOr(obj.opt("x_max"), DEFAULT_X_MAX)
                if (lo < hi) AiCommand.InsertGraph(expr, lo, hi) else AiCommand.InsertGraph(expr, DEFAULT_X_MIN, DEFAULT_X_MAX)
            }
        }

        else -> null
    }

    private fun finiteOr(value: Any?, fallback: Double): Double {
        val d = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
        return if (d != null && d.isFinite()) d else fallback
    }

    /**
     * Localiza los objetos JSON de **nivel superior** en `text` por emparejado de
     * llaves, respetando las cadenas entre comillas y sus escapes. Devuelve, por
     * cada uno, su rango en `text` y su subcadena.
     */
    private fun topLevelJsonObjects(text: String): List<Pair<IntRange, String>> {
        val found = mutableListOf<Pair<IntRange, String>>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') {
                i++
                continue
            }
            val start = i
            var depth = 0
            var inString = false
            var escaped = false
            var j = i
            var closed = false
            while (j < text.length) {
                val c = text[j]
                when {
                    escaped -> escaped = false
                    inString && c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) {
                            closed = true
                        }
                    }
                }
                j++
                if (closed) break
            }
            if (closed) {
                found += (start until j) to text.substring(start, j)
                i = j
            } else {
                // Llave sin cerrar: no hay más objetos completos.
                break
            }
        }
        return found
    }

    private fun stripRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var cursor = 0
        for (range in ranges.sortedBy { it.first }) {
            if (range.first > cursor) sb.append(text, cursor, range.first)
            cursor = range.last + 1
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private const val DEFAULT_X_MIN = -10.0
    private const val DEFAULT_X_MAX = 10.0
}
