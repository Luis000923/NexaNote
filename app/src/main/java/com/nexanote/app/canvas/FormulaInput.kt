package com.nexanote.app.canvas

/**
 * Procesamiento de la entrada de una fórmula matemática: validación mínima del
 * texto escrito y serialización al formato `FormulaSpec` del núcleo Rust.
 *
 * Lógica pura, sin Android ni Compose. El núcleo Rust es la autoridad final: es
 * quien parsea la expresión a un AST y rechaza la sintaxis inválida; aquí sólo se
 * evita el viaje al FFI cuando la entrada es obviamente descartable.
 */
object FormulaInput {

    /** Longitud máxima de la expresión (el núcleo aplica el mismo límite). */
    const val MAX_LEN = 2048

    /** ¿La expresión da para intentar consolidarla? Se descartan las vacías. */
    fun isCommittable(expression: String): Boolean = expression.isNotBlank()

    /**
     * Serializa `{"expression":<texto>,"position":{"x":x,"y":y}}` para el núcleo.
     * `(x, y)` es la línea base de la fórmula en coordenadas del documento (px
     * lógicos @1x). Se arma a mano, con la expresión escapada a literal JSON.
     */
    fun toFormulaJson(expression: String, x: Float, y: Float): String {
        val sb = StringBuilder(48 + expression.length)
        sb.append("{\"expression\":").append(jsonString(expression))
            .append(",\"position\":{\"x\":").append(x).append(",\"y\":").append(y).append("}}")
        return sb.toString()
    }

    /** Escapa una cadena como literal JSON, entre comillas. */
    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
