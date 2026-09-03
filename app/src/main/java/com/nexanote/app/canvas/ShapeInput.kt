package com.nexanote.app.canvas

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Forma geométrica estructurada que puede dibujar una herramienta de la barra.
 * Los nombres coinciden exactamente con las variantes de `ShapeKind` del núcleo
 * Rust, de modo que la serialización es directa.
 */
enum class ShapeKind { Rectangle, Ellipse, Line, Arrow }

/** Traducción de una [DrawingTool] a su [ShapeKind], o `null` si no es de formas. */
fun DrawingTool.asShapeKind(): ShapeKind? = when (this) {
    DrawingTool.Rectangle -> ShapeKind.Rectangle
    DrawingTool.Ellipse -> ShapeKind.Ellipse
    DrawingTool.Line -> ShapeKind.Line
    DrawingTool.Arrow -> ShapeKind.Arrow
    DrawingTool.Pen, DrawingTool.Pan, DrawingTool.Select, DrawingTool.Text, DrawingTool.Formula,
    DrawingTool.Graph, DrawingTool.Image -> null
}

/**
 * Bounding box de una forma, en coordenadas del documento (px lógicos @1x).
 *
 * Para `Rectangle`/`Ellipse`, `(x, y)` es la esquina superior-izquierda y
 * `(width, height)` es siempre no negativo. Para `Line`/`Arrow`, `(x, y)` es el
 * punto inicial y `(width, height)` es el vector -- con signo -- hasta el final.
 */
data class ShapeBounds(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Cálculo puro de la geometría de una forma a partir del arrastre del puntero.
 * Sin dependencias de Android: probable en JVM y reutilizable por la vista previa
 * del lienzo y por el ViewModel al persistir.
 */
object ShapeGeometry {

    /** Grosor de trazo por defecto de una forma nueva, en unidades lógicas. */
    const val DEFAULT_STROKE_WIDTH = 2.5f

    /**
     * Umbral de "arrastre intencionado": por debajo de esto el gesto se descarta
     * y no se crea forma. El núcleo Rust aplica además su propio suelo defensivo.
     */
    const val MIN_EXTENT = 2f

    /**
     * Bounding box de la forma [kind] para un arrastre desde `(startX, startY)`
     * hasta `(endX, endY)`, todo en coordenadas del documento.
     *
     * - `Rectangle`/`Ellipse`: se normaliza a esquina superior-izquierda + tamaño
     *   positivo, así el arrastre en cualquier dirección produce la misma figura.
     * - `Line`/`Arrow`: se conserva el signo; `(width, height)` es el vector del
     *   inicio al fin, para no perder la orientación de la flecha.
     */
    fun boundsFor(
        kind: ShapeKind,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): ShapeBounds = when (kind) {
        ShapeKind.Rectangle, ShapeKind.Ellipse -> ShapeBounds(
            x = minOf(startX, endX),
            y = minOf(startY, endY),
            width = abs(endX - startX),
            height = abs(endY - startY),
        )

        ShapeKind.Line, ShapeKind.Arrow -> ShapeBounds(
            x = startX,
            y = startY,
            width = endX - startX,
            height = endY - startY,
        )
    }

    /** ¿El arrastre da para consolidar una forma de este tipo? */
    fun isDrawable(kind: ShapeKind, bounds: ShapeBounds): Boolean = when (kind) {
        ShapeKind.Rectangle, ShapeKind.Ellipse ->
            bounds.width >= MIN_EXTENT || bounds.height >= MIN_EXTENT

        ShapeKind.Line, ShapeKind.Arrow ->
            hypot(bounds.width, bounds.height) >= MIN_EXTENT
    }

    /** Serializa la forma como `ElementKind::Shape` (sin la etiqueta `type`) para el núcleo. */
    fun toShapeJson(
        kind: ShapeKind,
        bounds: ShapeBounds,
        stroke: StrokeColor = StrokeColor.Ink,
        fill: StrokeColor? = null,
        strokeWidth: Float = DEFAULT_STROKE_WIDTH,
    ): String {
        val sb = StringBuilder(176)
        sb.append("{\"kind\":\"").append(kind.name).append("\",\"bounds\":{\"x\":").append(bounds.x)
            .append(",\"y\":").append(bounds.y)
            .append(",\"width\":").append(bounds.width)
            .append(",\"height\":").append(bounds.height)
            .append("},\"stroke_color\":").append(colorJson(stroke))
            .append(",\"fill_color\":").append(fill?.let(::colorJson) ?: "null")
            .append(",\"stroke_width\":").append(strokeWidth)
            .append('}')
        return sb.toString()
    }

    private fun colorJson(c: StrokeColor): String =
        "{\"r\":${c.r},\"g\":${c.g},\"b\":${c.b},\"a\":${c.a}}"
}
