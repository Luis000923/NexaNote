package com.nexanote.app.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Una selección resuelta **por el núcleo Rust**: los ids elegidos y la caja que
 * los envuelve. Kotlin no decide qué cae dentro de un área; sólo lo pinta y
 * ofrece las acciones.
 */
data class Selection(
    val ids: List<String> = emptyList(),
    val bounds: Rect? = null,
) {
    val isEmpty: Boolean get() = ids.isEmpty()
    val isNotEmpty: Boolean get() = ids.isNotEmpty()

    /** `true` si algún elemento seleccionado admite relleno (forma cerrada). */
    fun hasFillable(hits: List<SceneHit>): Boolean =
        hits.any { it.fillable && it.id in ids }

    /**
     * Reproyecta la selección sobre una escena nueva: descarta los elementos que
     * ya no existen y recalcula la caja envolvente a partir de las que publica el
     * propio núcleo ([SceneHit]). No decide nada: sólo relee lo ya resuelto, para
     * que el realce siga al contenido tras mover, colorear o deshacer.
     */
    fun refreshed(hits: List<SceneHit>): Selection {
        if (isEmpty) return Empty
        val byId = hits.associateBy { it.id }
        val kept = ids.filter { it in byId }
        if (kept.isEmpty()) return Empty
        var box: Rect? = null
        for (id in kept) {
            val hit = byId.getValue(id)
            box = box?.expandToInclude(hit.bounds) ?: hit.bounds
        }
        return Selection(kept, box)
    }

    companion object {
        val Empty = Selection()

        private fun Rect.expandToInclude(other: Rect): Rect = Rect(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom),
        )
    }
}

/**
 * Serialización de las entradas de las operaciones de selección y color.
 *
 * Es aritmética y armado de cadenas puros -- sin Android ni `org.json` -- de modo
 * que se puede probar en la JVM. La validez real (área finita, color de trazo no
 * nulo, ids existentes) la sigue decidiendo el núcleo.
 */
object SelectionInput {

    /**
     * Lado mínimo (px de documento) de un marco de selección para considerarlo
     * un arrastre intencionado y no un toque con temblor de mano.
     */
    const val MIN_MARQUEE_EXTENT = 6f

    /** Desplazamiento por defecto de una copia respecto del original. */
    const val DUPLICATE_OFFSET = 24f

    /** Rectángulo normalizado del arrastre de selección, en coordenadas del documento. */
    fun marquee(startX: Float, startY: Float, endX: Float, endY: Float): Rect = Rect(
        offset = Offset(min(startX, endX), min(startY, endY)),
        size = Size(abs(endX - startX), abs(endY - startY)),
    )

    /** ¿El arrastre da para una selección por área, o fue en realidad un toque? */
    fun isMarqueeDrag(area: Rect): Boolean =
        max(area.width, area.height) >= MIN_MARQUEE_EXTENT

    /** Serializa un área como `{"x":..,"y":..,"width":..,"height":..}`. */
    fun toAreaJson(area: Rect): String =
        """{"x":${area.left},"y":${area.top},"width":${area.width},"height":${area.height}}"""

    /** Serializa una lista de ids como `["<hex>", ...]`. Los ids del núcleo son hexadecimales. */
    fun toIdsJson(ids: List<String>): String =
        ids.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"${it.filter(::isIdChar)}\"" }

    /** Serializa el cambio de tinta de la selección. */
    fun toStrokeColorJson(color: StrokeColor): String =
        """{"target":"stroke","color":${colorJson(color)}}"""

    /** Serializa el cambio de relleno; `null` lo quita. */
    fun toFillColorJson(color: StrokeColor?): String =
        """{"target":"fill","color":${color?.let(::colorJson) ?: "null"}}"""

    private fun colorJson(c: StrokeColor): String =
        """{"r":${c.r},"g":${c.g},"b":${c.b},"a":${c.a}}"""

    /**
     * Sólo dígitos hexadecimales pueden formar parte de un id del núcleo: filtrar
     * el resto impide inyectar comillas u otro JSON en la cadena que se envía.
     */
    private fun isIdChar(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}

/**
 * Paleta de tinta y relleno de la aplicación. Son colores de documento, no de
 * tema: se guardan tal cual en el modelo, así que un cuaderno se ve igual en
 * claro y en oscuro y al exportarlo a PDF.
 */
object NexaPalette {

    /** Entrada de la paleta: su color de documento y el nombre que lee el lector de pantalla. */
    data class Swatch(val label: String, val color: StrokeColor)

    val Swatches: List<Swatch> = listOf(
        Swatch("Tinta", StrokeColor.Ink),
        Swatch("Rojo", StrokeColor(198, 40, 40)),
        Swatch("Naranja", StrokeColor(239, 108, 0)),
        Swatch("Ámbar", StrokeColor(249, 168, 37)),
        Swatch("Verde", StrokeColor(46, 125, 50)),
        Swatch("Turquesa", StrokeColor(0, 137, 123)),
        Swatch("Azul", StrokeColor(21, 101, 192)),
        Swatch("Violeta", StrokeColor(106, 27, 154)),
        Swatch("Magenta", StrokeColor(194, 24, 91)),
        Swatch("Gris", StrokeColor(117, 117, 117)),
    )

    /** Rellenos: tonos suaves para no tapar lo que ya hay dibujado encima. */
    val Fills: List<Swatch> = listOf(
        Swatch("Amarillo suave", StrokeColor(255, 249, 196)),
        Swatch("Verde suave", StrokeColor(220, 237, 200)),
        Swatch("Azul suave", StrokeColor(207, 228, 250)),
        Swatch("Rosa suave", StrokeColor(250, 216, 228)),
        Swatch("Gris suave", StrokeColor(233, 236, 239)),
        Swatch("Blanco", StrokeColor(255, 255, 255)),
    )
}
