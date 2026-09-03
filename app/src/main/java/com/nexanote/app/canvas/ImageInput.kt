package com.nexanote.app.canvas

/**
 * Procesamiento de la entrada de una imagen: validación de la ruta del recurso,
 * cálculo del marco inicial conservando la proporción y serialización al formato
 * `ImageSpec` del núcleo Rust.
 *
 * Lógica pura, sin Android ni Compose: probable en tests de JVM y reutilizable
 * por el `DocumentViewModel` al persistir. La copia del archivo al almacén local
 * la hace `ImageImporter` (capa Android); el núcleo Rust es la autoridad final
 * sobre la validez (rechaza rutas absolutas o con travesía, marcos degenerados).
 */
object ImageInput {

    /** Longitud máxima de la ruta del recurso (el núcleo aplica el mismo límite). */
    const val MAX_SOURCE_LEN = 512

    /** Extensión mínima y máxima de un lado del marco (px lógicos @1x). */
    const val MIN_EXTENT = 8f
    const val MAX_EXTENT = 20_000f

    /** Tamaño máximo por defecto del marco de una imagen recién insertada. */
    const val DEFAULT_MAX_WIDTH = 420f
    const val DEFAULT_MAX_HEIGHT = 420f

    /**
     * ¿La ruta del recurso es aceptable? Debe ser **relativa** al almacén de la
     * app: sin raíz (`/`, `\`), sin unidad (`C:`) y sin segmentos `..`. Es el
     * mismo criterio que aplica el núcleo Rust, replicado aquí para no viajar al
     * FFI con una ruta obviamente inválida.
     */
    fun isCommittable(source: String): Boolean {
        if (source.isBlank() || source.length > MAX_SOURCE_LEN) return false
        if (source.startsWith('/') || source.startsWith('\\') || source.contains(':')) return false
        return source.split('/', '\\').none { it == ".." }
    }

    /**
     * Encaja unas dimensiones intrínsecas `(naturalWidth, naturalHeight)` dentro
     * de `(maxWidth, maxHeight)` conservando la proporción y sin ampliar por
     * encima del tamaño natural. El resultado queda acotado a
     * `[MIN_EXTENT, MAX_EXTENT]` en ambos ejes.
     */
    fun fitFrame(
        naturalWidth: Float,
        naturalHeight: Float,
        maxWidth: Float = DEFAULT_MAX_WIDTH,
        maxHeight: Float = DEFAULT_MAX_HEIGHT,
    ): Pair<Float, Float> {
        if (naturalWidth <= 0f || naturalHeight <= 0f ||
            !naturalWidth.isFinite() || !naturalHeight.isFinite()
        ) {
            return maxWidth.coerceIn(MIN_EXTENT, MAX_EXTENT) to
                maxHeight.coerceIn(MIN_EXTENT, MAX_EXTENT)
        }
        val scale = minOf(maxWidth / naturalWidth, maxHeight / naturalHeight, 1f)
        val w = (naturalWidth * scale).coerceIn(MIN_EXTENT, MAX_EXTENT)
        val h = (naturalHeight * scale).coerceIn(MIN_EXTENT, MAX_EXTENT)
        return w to h
    }

    /**
     * Serializa el `ImageSpec` para el núcleo. `(x, y)` es la esquina superior
     * izquierda del marco en coordenadas del documento (px lógicos @1x). Se arma a
     * mano, con la ruta escapada a literal JSON.
     */
    fun toImageJson(
        source: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        naturalWidth: Float,
        naturalHeight: Float,
    ): String {
        val sb = StringBuilder(128 + source.length)
        sb.append("{\"source\":").append(jsonString(source))
            .append(",\"position\":{\"x\":").append(x).append(",\"y\":").append(y).append('}')
            .append(",\"width\":").append(width)
            .append(",\"height\":").append(height)
            .append(",\"natural_width\":").append(naturalWidth)
            .append(",\"natural_height\":").append(naturalHeight)
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
