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
    ) : ScenePrimitive
}
