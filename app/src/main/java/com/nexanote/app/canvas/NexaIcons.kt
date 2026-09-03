package com.nexanote.app.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Iconografía vectorial propia (sin emojis, sin dependencias de terceros): cada
 * icono es un `ImageVector` construido con nodos de trazado sobre un lienzo de
 * 24x24, al estilo de un Android Vector Drawable.
 *
 * Se evita `material-icons-extended` a propósito para no arrastrar esa librería.
 */
object NexaIcons {

    private fun vector(
        name: String,
        fillType: PathFillType = PathFillType.NonZero,
        block: PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = fillType, pathBuilder = block)
        }.build()

    private fun PathBuilder.magnifier() {
        moveTo(15.5f, 14f)
        horizontalLineToRelative(-0.79f)
        lineToRelative(-0.28f, -0.27f)
        curveToRelative(1.2f, -1.4f, 1.82f, -3.31f, 1.48f, -5.34f)
        curveToRelative(-0.47f, -2.78f, -2.79f, -5.0f, -5.59f, -5.34f)
        curveToRelative(-4.23f, -0.52f, -7.79f, 3.04f, -7.27f, 7.27f)
        curveToRelative(0.34f, 2.8f, 2.56f, 5.12f, 5.34f, 5.59f)
        curveToRelative(2.03f, 0.34f, 3.94f, -0.28f, 5.34f, -1.48f)
        lineToRelative(0.27f, 0.28f)
        verticalLineToRelative(0.79f)
        lineToRelative(4.25f, 4.25f)
        curveToRelative(0.41f, 0.41f, 1.08f, 0.41f, 1.49f, 0f)
        curveToRelative(0.41f, -0.41f, 0.41f, -1.08f, 0f, -1.49f)
        close()
        moveTo(9.5f, 14f)
        curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
        reflectiveCurveTo(7.01f, 5f, 9.5f, 5f)
        reflectiveCurveTo(14f, 7.01f, 14f, 9.5f)
        reflectiveCurveTo(11.99f, 14f, 9.5f, 14f)
        close()
    }

    val ZoomIn: ImageVector by lazy {
        vector("nexa_zoom_in") {
            magnifier()
            moveTo(9f, 7f)
            horizontalLineToRelative(1f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(1f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-1f)
            verticalLineToRelative(-2f)
            horizontalLineTo(7f)
            verticalLineTo(9f)
            horizontalLineToRelative(2f)
            close()
        }
    }

    val ZoomOut: ImageVector by lazy {
        vector("nexa_zoom_out") {
            magnifier()
            moveTo(7f, 9f)
            horizontalLineToRelative(5f)
            verticalLineToRelative(1f)
            horizontalLineTo(7f)
            close()
        }
    }

    val FitScreen: ImageVector by lazy {
        vector("nexa_fit_screen") {
            // marco exterior
            moveTo(3f, 5f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(2f)
            horizontalLineTo(5f)
            verticalLineToRelative(4f)
            horizontalLineTo(3f)
            close()
            moveTo(15f, 5f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-2f)
            verticalLineTo(7f)
            horizontalLineToRelative(-4f)
            close()
            moveTo(3f, 13f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(4f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(2f)
            horizontalLineTo(3f)
            close()
            moveTo(19f, 13f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-6f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(4f)
            close()
            // pantalla interior
            moveTo(8f, 8f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(8f)
            horizontalLineTo(8f)
            close()
        }
    }

    /** Herramienta de escritura/dibujo (punta de lápiz). */
    val Pen: ImageVector by lazy {
        vector("nexa_pen") {
            moveTo(3f, 17.25f)
            verticalLineTo(21f)
            horizontalLineToRelative(3.75f)
            lineTo(17.81f, 9.94f)
            lineToRelative(-3.75f, -3.75f)
            lineTo(3f, 17.25f)
            close()
            moveTo(20.71f, 7.04f)
            curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
            lineToRelative(-2.34f, -2.34f)
            curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
            lineToRelative(-1.83f, 1.83f)
            lineToRelative(3.75f, 3.75f)
            lineToRelative(1.83f, -1.83f)
            close()
        }
    }

    /** Herramienta de navegación (mover el lienzo en las cuatro direcciones). */
    val Hand: ImageVector by lazy {
        vector("nexa_pan") {
            moveTo(10f, 9f)
            horizontalLineToRelative(4f)
            verticalLineTo(6f)
            horizontalLineToRelative(3f)
            lineToRelative(-5f, -5f)
            lineToRelative(-5f, 5f)
            horizontalLineToRelative(3f)
            close()
            moveTo(9f, 10f)
            horizontalLineTo(6f)
            verticalLineTo(7f)
            lineToRelative(-5f, 5f)
            lineToRelative(5f, 5f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(3f)
            close()
            moveTo(23f, 12f)
            lineToRelative(-5f, -5f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(-3f)
            verticalLineToRelative(4f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(3f)
            close()
            moveTo(14f, 15f)
            horizontalLineToRelative(-4f)
            verticalLineToRelative(3f)
            horizontalLineTo(7f)
            lineToRelative(5f, 5f)
            lineToRelative(5f, -5f)
            horizontalLineToRelative(-3f)
            close()
        }
    }

    /** Herramienta de forma: línea recta (barra diagonal). */
    val ShapeLine: ImageVector by lazy {
        vector("nexa_shape_line") {
            moveTo(4f, 18f)
            lineTo(18f, 4f)
            lineTo(20f, 6f)
            lineTo(6f, 20f)
            close()
        }
    }

    /** Herramienta de forma: rectángulo (marco hueco). */
    val ShapeRectangle: ImageVector by lazy {
        vector("nexa_shape_rectangle", PathFillType.EvenOdd) {
            moveTo(3f, 5f)
            lineTo(21f, 5f)
            lineTo(21f, 19f)
            lineTo(3f, 19f)
            close()
            moveTo(6f, 8f)
            lineTo(18f, 8f)
            lineTo(18f, 16f)
            lineTo(6f, 16f)
            close()
        }
    }

    /** Herramienta de forma: elipse (anillo). */
    val ShapeEllipse: ImageVector by lazy {
        vector("nexa_shape_ellipse", PathFillType.EvenOdd) {
            moveTo(3f, 12f)
            arcToRelative(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18f, 0f)
            arcToRelative(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, -18f, 0f)
            close()
            moveTo(6f, 12f)
            arcToRelative(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f, 0f)
            arcToRelative(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, -12f, 0f)
            close()
        }
    }

    /** Herramienta de forma: flecha (diagonal con punta). */
    val ShapeArrow: ImageVector by lazy {
        vector("nexa_shape_arrow") {
            moveTo(9f, 5f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(6.59f)
            lineTo(4f, 18.59f)
            lineTo(5.41f, 20f)
            lineTo(17f, 8.41f)
            verticalLineTo(15f)
            horizontalLineToRelative(2f)
            verticalLineTo(5f)
            close()
        }
    }

    val Refresh: ImageVector by lazy {
        vector("nexa_refresh") {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
            curveToRelative(-4.42f, 0f, -7.99f, 3.58f, -7.99f, 8f)
            reflectiveCurveTo(7.58f, 20f, 12f, 20f)
            curveToRelative(3.73f, 0f, 6.84f, -2.55f, 7.73f, -6f)
            horizontalLineToRelative(-2.08f)
            curveToRelative(-0.82f, 2.33f, -3.04f, 4f, -5.65f, 4f)
            curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
            reflectiveCurveToRelative(2.69f, -6f, 6f, -6f)
            curveToRelative(1.66f, 0f, 3.14f, 0.69f, 4.22f, 1.78f)
            lineTo(13f, 11f)
            horizontalLineToRelative(7f)
            verticalLineTo(4f)
            close()
        }
    }
}
