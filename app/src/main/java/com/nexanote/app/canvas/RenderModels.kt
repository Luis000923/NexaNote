package com.nexanote.app.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * Modelo de *render* del lado Kotlin: espejo inmutable de la `ScenePage` que
 * produce el núcleo Rust (`documentRenderPage`).
 *
 * La UI sólo consume estos tipos; no conoce el modelo de documento ni realiza
 * conversiones de unidades (eso ya lo hizo el núcleo).
 */
data class ScenePage(
    val pageIndex: Int,
    val pageId: String,
    val widthPx: Float,
    val heightPx: Float,
    val background: Color,
    val template: SceneTemplate,
    /** Primitivas ordenadas de atrás hacia delante. */
    val primitives: List<ScenePrimitive>,
)

sealed interface SceneTemplate {
    data object Blank : SceneTemplate
    data class Grid(val spacingPx: Float) : SceneTemplate
    data class Ruled(val spacingPx: Float) : SceneTemplate
    data class Dotted(val spacingPx: Float) : SceneTemplate
}

sealed interface ScenePrimitive {
    data class Polyline(
        val points: List<Offset>,
        val color: Color,
        val width: Float,
    ) : ScenePrimitive

    data class Rect(
        val topLeft: Offset,
        val size: androidx.compose.ui.geometry.Size,
        val stroke: Color,
        val fill: Color?,
        val strokeWidth: Float,
    ) : ScenePrimitive

    data class Ellipse(
        val topLeft: Offset,
        val size: androidx.compose.ui.geometry.Size,
        val stroke: Color,
        val fill: Color?,
        val strokeWidth: Float,
    ) : ScenePrimitive

    data class Line(
        val start: Offset,
        val end: Offset,
        val color: Color,
        val width: Float,
    ) : ScenePrimitive

    data class Arrow(
        val start: Offset,
        val end: Offset,
        val color: Color,
        val width: Float,
    ) : ScenePrimitive

    data class Text(
        val origin: Offset,
        val content: String,
        val fontSize: Float,
        val color: Color,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
    ) : ScenePrimitive

    data class Formula(
        val origin: Offset,
        val latex: String,
        val color: Color,
        /** Valor numérico si la expresión es cerrada (sin símbolos libres); si no, `null`. */
        val value: Double? = null,
    ) : ScenePrimitive

    /**
     * Gráfica de una función: marco, cuadrícula, ejes y la curva ya muestreada por
     * el núcleo Rust, toda en coordenadas de página (px lógicos @1x).
     */
    data class Graph(
        val topLeft: Offset,
        val size: androidx.compose.ui.geometry.Size,
        val expression: String,
        val color: Color,
        /** Posiciones x (página) de las líneas verticales de la cuadrícula. */
        val gridX: List<Float>,
        /** Posiciones y (página) de las líneas horizontales de la cuadrícula. */
        val gridY: List<Float>,
        /** x (página) del eje vertical (`var = 0`), si cae dentro del marco. */
        val axisX: Float?,
        /** y (página) del eje horizontal (`f = 0`), si cae dentro del marco. */
        val axisY: Float?,
        /** Tramos continuos de la curva; cada hueco de la función parte la lista. */
        val polylines: List<List<Offset>>,
    ) : ScenePrimitive

    /**
     * Imagen rasterizada: marco de destino en coordenadas de página (px lógicos
     * @1x) y ruta relativa del recurso en el almacén local de la app. El bitmap se
     * decodifica fuera del hilo de dibujo (ver `ImageImporter`).
     */
    data class Image(
        val topLeft: Offset,
        val size: androidx.compose.ui.geometry.Size,
        val source: String,
        val naturalWidth: Float,
        val naturalHeight: Float,
    ) : ScenePrimitive
}
