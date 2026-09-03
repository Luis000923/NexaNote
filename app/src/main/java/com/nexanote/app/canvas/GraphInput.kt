package com.nexanote.app.canvas

/**
 * Procesamiento de la entrada de una gráfica de función: validación mínima de la
 * función y del dominio, y serialización al formato `GraphSpec` del núcleo Rust.
 *
 * Lógica pura, sin Android ni Compose. El núcleo Rust es la autoridad final: es
 * quien parsea la función a un AST, valida los símbolos y muestrea la curva; aquí
 * sólo se evita el viaje al FFI cuando la entrada es obviamente descartable.
 */
object GraphInput {

    /** Longitud máxima de la función (el núcleo aplica el mismo límite). */
    const val MAX_LEN = 2048

    /** Tamaño por defecto del marco de una gráfica nueva (px lógicos @1x). */
    const val DEFAULT_WIDTH = 340f
    const val DEFAULT_HEIGHT = 260f

    /** Dominio por defecto de una gráfica nueva. */
    const val DEFAULT_X_MIN = -10.0
    const val DEFAULT_X_MAX = 10.0

    /** Resultado de validar la entrada de una gráfica antes de enviarla al núcleo. */
    sealed interface Validation {
        data class Valid(val expression: String, val xMin: Double, val xMax: Double) : Validation
        data class Invalid(val reason: String) : Validation
    }

    /**
     * Valida la función y el dominio escritos por el usuario. No parsea la
     * función (eso es tarea del núcleo): sólo descarta lo obviamente inservible.
     */
    fun validate(expression: String, xMin: String, xMax: String): Validation {
        val expr = expression.trim()
        if (expr.isEmpty()) return Validation.Invalid("Escribe una función, p. ej. x^2")
        if (expr.length > MAX_LEN) return Validation.Invalid("La función es demasiado larga")

        val lo = xMin.trim().toDoubleOrNull()
            ?: return Validation.Invalid("El límite inferior no es un número")
        val hi = xMax.trim().toDoubleOrNull()
            ?: return Validation.Invalid("El límite superior no es un número")
        if (!lo.isFinite() || !hi.isFinite()) return Validation.Invalid("El dominio debe ser finito")
        if (lo >= hi) return Validation.Invalid("Debe cumplirse x mínimo < x máximo")

        return Validation.Valid(expr, lo, hi)
    }

    /**
     * Serializa el `GraphSpec` para el núcleo. `(x, y)` es la esquina superior
     * izquierda del marco en coordenadas del documento (px lógicos @1x). Se arma a
     * mano, con la función escapada a literal JSON.
     */
    fun toGraphJson(
        expression: String,
        xMin: Double,
        xMax: Double,
        x: Float,
        y: Float,
        width: Float = DEFAULT_WIDTH,
        height: Float = DEFAULT_HEIGHT,
        variable: String = "x",
    ): String {
        val sb = StringBuilder(96 + expression.length)
        sb.append("{\"expression\":").append(jsonString(expression))
            .append(",\"position\":{\"x\":").append(x).append(",\"y\":").append(y).append('}')
            .append(",\"width\":").append(width)
            .append(",\"height\":").append(height)
            .append(",\"x_min\":").append(xMin)
            .append(",\"x_max\":").append(xMax)
            .append(",\"var\":").append(jsonString(variable))
            .append('}')
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
