package com.nexanote.app.canvas

/**
 * Procesamiento de la entrada de un bloque de texto: validación del contenido
 * escrito por el usuario y serialización al formato `TextBox` del núcleo Rust.
 *
 * Lógica pura, sin Android ni Compose: probable en tests de JVM y reutilizable
 * por el `DocumentViewModel` al persistir. El núcleo Rust es la autoridad final
 * sobre la validez (recorta, satura el tamaño de fuente, rechaza vacíos); aquí
 * sólo se evita el viaje al FFI cuando el bloque es obviamente descartable.
 */
object TextInput {

    /** Tamaño de fuente por defecto de un bloque nuevo, en unidades lógicas. */
    const val DEFAULT_FONT_SIZE = 20f

    /** Límites de tamaño de fuente, en el mismo rango que acepta el núcleo Rust. */
    const val MIN_FONT_SIZE = 6f
    const val MAX_FONT_SIZE = 512f

    /** Longitud máxima del contenido (el núcleo trunca a este mismo valor). */
    const val MAX_LEN = 4096

    /**
     * ¿El texto escrito da para consolidar un bloque? Se descartan las entradas
     * en blanco (sólo espacios o saltos de línea) sin molestar al núcleo.
     */
    fun isCommittable(content: String): Boolean = content.isNotBlank()

    /**
     * Serializa un bloque como `ElementKind::Text` (sin la etiqueta `type`, igual
     * que [ShapeGeometry.toShapeJson]) para el núcleo. `(x, y)` es la línea base
     * del texto en coordenadas del documento (px lógicos @1x).
     *
     * Se arma a mano para no arrastrar `JSONObject`, con el contenido escapado a
     * JSON. No valida: [isCommittable] es el filtro previo y el núcleo el final.
     */
    fun toTextJson(
        content: String,
        x: Float,
        y: Float,
        fontSize: Float = DEFAULT_FONT_SIZE,
        color: StrokeColor = StrokeColor.Ink,
        bold: Boolean = false,
        italic: Boolean = false,
        underline: Boolean = false,
    ): String {
        val clamped = fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        val sb = StringBuilder(128 + content.length)
        sb.append("{\"content\":").append(jsonString(content))
            .append(",\"position\":{\"x\":").append(x).append(",\"y\":").append(y).append('}')
            .append(",\"style\":{\"font_size\":").append(clamped)
            .append(",\"bold\":").append(bold)
            .append(",\"italic\":").append(italic)
            .append(",\"underline\":").append(underline)
            .append(",\"color\":{\"r\":").append(color.r)
            .append(",\"g\":").append(color.g)
            .append(",\"b\":").append(color.b)
            .append(",\"a\":").append(color.a)
            .append("}},\"max_width\":null}")
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
