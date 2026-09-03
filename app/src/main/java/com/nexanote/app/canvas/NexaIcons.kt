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

    /** Herramienta de texto: la letra "T" (barra superior y asta). */
    val TextTool: ImageVector by lazy {
        vector("nexa_text_tool") {
            moveTo(4f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 7.5f)
            lineTo(13.75f, 7.5f)
            lineTo(13.75f, 20f)
            lineTo(10.25f, 20f)
            lineTo(10.25f, 7.5f)
            lineTo(4f, 7.5f)
            close()
        }
    }

    /** Herramienta de fórmulas matemáticas: sumatoria griega (sigma mayúscula). */
    val Formula: ImageVector by lazy {
        vector("nexa_formula", PathFillType.EvenOdd) {
            // Contorno exterior de la sigma.
            moveTo(6f, 4f)
            lineTo(18f, 4f)
            lineTo(18f, 7.5f)
            lineTo(16.5f, 7.5f)
            lineTo(16.5f, 6f)
            lineTo(9.5f, 6f)
            lineTo(14.5f, 11.4f)
            lineTo(14.5f, 12.6f)
            lineTo(9.5f, 18f)
            lineTo(16.5f, 18f)
            lineTo(16.5f, 16.5f)
            lineTo(18f, 16.5f)
            lineTo(18f, 20f)
            lineTo(6f, 20f)
            lineTo(6f, 18.8f)
            lineTo(11.7f, 12f)
            lineTo(6f, 5.2f)
            close()
        }
    }

    /** Herramienta de gráficas de funciones: ejes cartesianos con una curva. */
    val GraphTool: ImageVector by lazy {
        vector("nexa_graph_tool", PathFillType.EvenOdd) {
            // Eje vertical.
            moveTo(4f, 3f)
            lineTo(5.4f, 3f)
            lineTo(5.4f, 20f)
            lineTo(4f, 20f)
            close()
            // Eje horizontal.
            moveTo(4f, 18.6f)
            lineTo(21f, 18.6f)
            lineTo(21f, 20f)
            lineTo(4f, 20f)
            close()
            // Curva creciente (banda con grosor).
            moveTo(5.4f, 17.5f)
            quadToRelative(6f, -1f, 9.4f, -7f)
            quadToRelative(2.2f, -3.8f, 4.6f, -5.8f)
            lineToRelative(1.1f, 1.2f)
            quadToRelative(-2.2f, 1.9f, -4.3f, 5.5f)
            quadToRelative(-3.8f, 6.4f, -10.4f, 7.5f)
            close()
        }
    }

    /** Herramienta de imagen: marco con un sol y una silueta de montaña. */
    val Image: ImageVector by lazy {
        vector("nexa_image", PathFillType.EvenOdd) {
            // Marco (anillo rectangular).
            moveTo(3f, 5f)
            lineTo(21f, 5f)
            lineTo(21f, 19f)
            lineTo(3f, 19f)
            close()
            moveTo(5f, 7f)
            lineTo(19f, 7f)
            lineTo(19f, 17f)
            lineTo(5f, 17f)
            close()
            // Sol.
            moveTo(8f, 10.5f)
            arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3.4f, 0f)
            arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = true, isPositiveArc = true, -3.4f, 0f)
            close()
            // Montaña.
            moveTo(6f, 16f)
            lineTo(11f, 10.5f)
            lineTo(14.5f, 14f)
            lineTo(16f, 12.5f)
            lineTo(18f, 16f)
            close()
        }
    }

    /** Deshacer: flecha curva que gira a la izquierda. */
    val Undo: ImageVector by lazy {
        vector("nexa_undo") {
            moveTo(12.5f, 8f)
            curveToRelative(-2.65f, 0f, -5.05f, 0.99f, -6.9f, 2.6f)
            lineTo(2f, 7f)
            verticalLineToRelative(9f)
            horizontalLineToRelative(9f)
            lineToRelative(-3.62f, -3.62f)
            curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
            curveToRelative(3.54f, 0f, 6.55f, 2.31f, 7.6f, 5.5f)
            lineToRelative(2.37f, -0.78f)
            curveTo(19.08f, 11.03f, 15.15f, 8f, 12.5f, 8f)
            close()
        }
    }

    /** Rehacer: flecha curva que gira a la derecha (espejo de [Undo]). */
    val Redo: ImageVector by lazy {
        vector("nexa_redo") {
            moveTo(18.4f, 10.6f)
            curveTo(16.55f, 8.99f, 14.15f, 8f, 11.5f, 8f)
            curveToRelative(-4.65f, 0f, -8.58f, 3.03f, -9.96f, 7.22f)
            lineTo(3.9f, 16f)
            curveToRelative(1.05f, -3.19f, 4.05f, -5.5f, 7.6f, -5.5f)
            curveToRelative(1.95f, 0f, 3.73f, 0.72f, 5.12f, 1.88f)
            lineTo(13f, 16f)
            horizontalLineToRelative(9f)
            verticalLineTo(7f)
            lineToRelative(-3.6f, 3.6f)
            close()
        }
    }

    /** Exportar/compartir a PDF: flecha descendente sobre una bandeja. */
    val ExportPdf: ImageVector by lazy {
        vector("nexa_export_pdf") {
            // Asta y punta de la flecha.
            moveTo(11f, 3f)
            lineTo(13f, 3f)
            lineTo(13f, 12.17f)
            lineTo(16.59f, 8.59f)
            lineTo(18f, 10f)
            lineTo(12f, 16f)
            lineTo(6f, 10f)
            lineTo(7.41f, 8.59f)
            lineTo(11f, 12.17f)
            close()
            // Bandeja de destino.
            moveTo(5f, 18f)
            lineTo(7f, 18f)
            lineTo(7f, 19f)
            lineTo(17f, 19f)
            lineTo(17f, 18f)
            lineTo(19f, 18f)
            lineTo(19f, 21f)
            lineTo(5f, 21f)
            close()
        }
    }

    /** Ajustes: rueda dentada con eje central. */
    val Settings: ImageVector by lazy {
        vector("nexa_settings", PathFillType.EvenOdd) {
            // Corona dentada aproximada (octágono con muescas) + hueco central.
            moveTo(10.5f, 2f)
            lineTo(13.5f, 2f)
            lineTo(14f, 4.4f)
            lineTo(16.1f, 5.3f)
            lineTo(18.2f, 4f)
            lineTo(20f, 5.8f)
            lineTo(18.7f, 7.9f)
            lineTo(19.6f, 10f)
            lineTo(22f, 10.5f)
            lineTo(22f, 13.5f)
            lineTo(19.6f, 14f)
            lineTo(18.7f, 16.1f)
            lineTo(20f, 18.2f)
            lineTo(18.2f, 20f)
            lineTo(16.1f, 18.7f)
            lineTo(14f, 19.6f)
            lineTo(13.5f, 22f)
            lineTo(10.5f, 22f)
            lineTo(10f, 19.6f)
            lineTo(7.9f, 18.7f)
            lineTo(5.8f, 20f)
            lineTo(4f, 18.2f)
            lineTo(5.3f, 16.1f)
            lineTo(4.4f, 14f)
            lineTo(2f, 13.5f)
            lineTo(2f, 10.5f)
            lineTo(4.4f, 10f)
            lineTo(5.3f, 7.9f)
            lineTo(4f, 5.8f)
            lineTo(5.8f, 4f)
            lineTo(7.9f, 5.3f)
            lineTo(10f, 4.4f)
            close()
            moveTo(12f, 8.5f)
            arcToRelative(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, 7f)
            arcToRelative(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, -7f)
            close()
        }
    }

    /** Asistente de IA: bocadillo de diálogo con una chispa. */
    val Assistant: ImageVector by lazy {
        vector("nexa_assistant", PathFillType.EvenOdd) {
            // Bocadillo (anillo rectangular con pico).
            moveTo(3f, 4f)
            lineTo(21f, 4f)
            lineTo(21f, 16f)
            lineTo(9f, 16f)
            lineTo(5f, 20f)
            lineTo(5f, 16f)
            lineTo(3f, 16f)
            close()
            moveTo(5f, 6f)
            lineTo(19f, 6f)
            lineTo(19f, 14f)
            lineTo(5f, 14f)
            close()
            // Chispa central.
            moveTo(12f, 7f)
            lineTo(12.9f, 9.1f)
            lineTo(15f, 10f)
            lineTo(12.9f, 10.9f)
            lineTo(12f, 13f)
            lineTo(11.1f, 10.9f)
            lineTo(9f, 10f)
            lineTo(11.1f, 9.1f)
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

    /** Herramienta de selección: marco de recorte punteado con tirador. */
    val Select: ImageVector by lazy {
        vector("nexa_select", PathFillType.EvenOdd) {
            // Cuatro esquinas en escuadra: la marca clásica de "selección".
            moveTo(3f, 3f); lineTo(9f, 3f); lineTo(9f, 5f); lineTo(5f, 5f); lineTo(5f, 9f)
            lineTo(3f, 9f); close()
            moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f); lineTo(19f, 9f); lineTo(19f, 5f)
            lineTo(15f, 5f); close()
            moveTo(3f, 15f); lineTo(5f, 15f); lineTo(5f, 19f); lineTo(9f, 19f); lineTo(9f, 21f)
            lineTo(3f, 21f); close()
            moveTo(19f, 15f); lineTo(21f, 15f); lineTo(21f, 21f); lineTo(15f, 21f); lineTo(15f, 19f)
            lineTo(19f, 19f); close()
            // Centro: rectángulo sólido que sugiere el contenido agrupado.
            moveTo(8.5f, 8.5f); lineTo(15.5f, 8.5f); lineTo(15.5f, 15.5f); lineTo(8.5f, 15.5f)
            close()
        }
    }

    /** Eliminar: papelera con tapa. */
    val Delete: ImageVector by lazy {
        vector("nexa_delete") {
            moveTo(9f, 3f)
            lineTo(15f, 3f)
            lineTo(16f, 5f)
            lineTo(20f, 5f)
            lineTo(20f, 7f)
            lineTo(4f, 7f)
            lineTo(4f, 5f)
            lineTo(8f, 5f)
            close()
            moveTo(6f, 9f)
            lineTo(18f, 9f)
            lineTo(17f, 21f)
            lineTo(7f, 21f)
            close()
        }
    }

    /** Duplicar: dos hojas superpuestas. */
    val Duplicate: ImageVector by lazy {
        vector("nexa_duplicate", PathFillType.EvenOdd) {
            moveTo(4f, 2f); lineTo(15f, 2f); lineTo(15f, 16f); lineTo(4f, 16f); close()
            moveTo(6f, 4f); lineTo(13f, 4f); lineTo(13f, 14f); lineTo(6f, 14f); close()
            moveTo(9f, 18f); lineTo(20f, 18f); lineTo(20f, 6f); lineTo(18f, 6f); lineTo(18f, 16f)
            lineTo(9f, 16f); close()
            moveTo(9f, 18f); lineTo(9f, 22f); lineTo(20f, 22f); lineTo(20f, 18f); close()
        }
    }

    /** Color de tinta: paleta de pintor. */
    val Palette: ImageVector by lazy {
        vector("nexa_palette", PathFillType.EvenOdd) {
            moveTo(12f, 3f)
            curveToRelative(-4.97f, 0f, -9f, 3.58f, -9f, 8f)
            curveToRelative(0f, 4.42f, 4.03f, 8f, 9f, 8f)
            curveToRelative(0.83f, 0f, 1.5f, -0.67f, 1.5f, -1.5f)
            curveToRelative(0f, -0.39f, -0.15f, -0.74f, -0.39f, -1.01f)
            curveToRelative(-0.23f, -0.26f, -0.38f, -0.61f, -0.38f, -0.99f)
            curveToRelative(0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f)
            horizontalLineTo(16f)
            curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f)
            curveToRelative(0f, -3.31f, -4.03f, -6f, -9f, -6f)
            close()
            moveTo(6.5f, 11f)
            arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3f, 0f)
            arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -3f, 0f)
            close()
            moveTo(9.5f, 7f)
            arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3f, 0f)
            arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -3f, 0f)
            close()
            moveTo(14.5f, 7f)
            arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3f, 0f)
            arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -3f, 0f)
            close()
        }
    }

    /** Relleno: cubo de pintura volcado con una gota. */
    val Fill: ImageVector by lazy {
        vector("nexa_fill") {
            moveTo(4f, 13f)
            lineTo(11f, 6f)
            lineTo(18f, 13f)
            lineTo(11f, 20f)
            close()
            moveTo(20f, 15f)
            curveToRelative(1.2f, 1.6f, 2f, 2.8f, 2f, 3.6f)
            curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
            reflectiveCurveToRelative(-2f, -0.9f, -2f, -2f)
            curveToRelative(0f, -0.8f, 0.8f, -2f, 2f, -3.6f)
            close()
        }
    }

    /** Crear: signo "más". */
    val Add: ImageVector by lazy {
        vector("nexa_add") {
            moveTo(11f, 4f)
            lineTo(13f, 4f)
            lineTo(13f, 11f)
            lineTo(20f, 11f)
            lineTo(20f, 13f)
            lineTo(13f, 13f)
            lineTo(13f, 20f)
            lineTo(11f, 20f)
            lineTo(11f, 13f)
            lineTo(4f, 13f)
            lineTo(4f, 11f)
            lineTo(11f, 11f)
            close()
        }
    }

    /** Volver: flecha hacia la izquierda. */
    val Back: ImageVector by lazy {
        vector("nexa_back") {
            moveTo(11f, 4f)
            lineTo(12.4f, 5.4f)
            lineTo(6.8f, 11f)
            lineTo(20f, 11f)
            lineTo(20f, 13f)
            lineTo(6.8f, 13f)
            lineTo(12.4f, 18.6f)
            lineTo(11f, 20f)
            lineTo(3f, 12f)
            close()
        }
    }

    /** Cuaderno: hoja con lomo cosido. */
    val Notebook: ImageVector by lazy {
        vector("nexa_notebook", PathFillType.EvenOdd) {
            moveTo(6f, 3f); lineTo(20f, 3f); lineTo(20f, 21f); lineTo(6f, 21f); close()
            moveTo(8f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(8f, 19f); close()
            moveTo(3f, 5f); lineTo(5f, 5f); lineTo(5f, 8f); lineTo(3f, 8f); close()
            moveTo(3f, 10.5f); lineTo(5f, 10.5f); lineTo(5f, 13.5f); lineTo(3f, 13.5f); close()
            moveTo(3f, 16f); lineTo(5f, 16f); lineTo(5f, 19f); lineTo(3f, 19f); close()
        }
    }

    /** Formato A4: hoja vertical con una esquina doblada. */
    val PageA4: ImageVector by lazy {
        vector("nexa_page_a4", PathFillType.EvenOdd) {
            moveTo(5f, 2f); lineTo(14f, 2f); lineTo(19f, 7f); lineTo(19f, 22f); lineTo(5f, 22f)
            close()
            moveTo(7f, 4f); lineTo(13f, 4f); lineTo(13f, 8f); lineTo(17f, 8f); lineTo(17f, 20f)
            lineTo(7f, 20f); close()
        }
    }

    /** Nueva página: una hoja con un signo "más" en el centro. */
    val PageAdd: ImageVector by lazy {
        vector("nexa_page_add", PathFillType.EvenOdd) {
            // Hoja (anillo rectangular con la esquina superior derecha doblada).
            moveTo(5f, 2f); lineTo(14f, 2f); lineTo(19f, 7f); lineTo(19f, 22f); lineTo(5f, 22f)
            close()
            moveTo(7f, 4f); lineTo(13f, 4f); lineTo(13f, 8f); lineTo(17f, 8f); lineTo(17f, 20f)
            lineTo(7f, 20f); close()
            // Signo "más" centrado en la hoja.
            moveTo(11f, 9.5f); lineTo(13f, 9.5f); lineTo(13f, 13f); lineTo(16.5f, 13f)
            lineTo(16.5f, 15f); lineTo(13f, 15f); lineTo(13f, 18.5f); lineTo(11f, 18.5f)
            lineTo(11f, 15f); lineTo(7.5f, 15f); lineTo(7.5f, 13f); lineTo(11f, 13f); close()
        }
    }

    /** Avanzar: flecha hacia la derecha (espejo de [Back]). */
    val Forward: ImageVector by lazy {
        vector("nexa_forward") {
            moveTo(13f, 4f)
            lineTo(11.6f, 5.4f)
            lineTo(17.2f, 11f)
            lineTo(4f, 11f)
            lineTo(4f, 13f)
            lineTo(17.2f, 13f)
            lineTo(11.6f, 18.6f)
            lineTo(13f, 20f)
            lineTo(21f, 12f)
            close()
        }
    }

    /** Lienzo infinito: el lazo del símbolo de infinito. */
    val InfiniteCanvas: ImageVector by lazy {
        vector("nexa_infinite_canvas", PathFillType.EvenOdd) {
            moveTo(6.5f, 7f)
            curveToRelative(-2.49f, 0f, -4.5f, 2.24f, -4.5f, 5f)
            reflectiveCurveToRelative(2.01f, 5f, 4.5f, 5f)
            curveToRelative(1.5f, 0f, 2.6f, -0.8f, 3.4f, -1.8f)
            lineTo(12f, 12.6f)
            lineToRelative(2.1f, 2.6f)
            curveToRelative(0.8f, 1f, 1.9f, 1.8f, 3.4f, 1.8f)
            curveToRelative(2.49f, 0f, 4.5f, -2.24f, 4.5f, -5f)
            reflectiveCurveToRelative(-2.01f, -5f, -4.5f, -5f)
            curveToRelative(-1.5f, 0f, -2.6f, 0.8f, -3.4f, 1.8f)
            lineTo(12f, 11.4f)
            lineTo(9.9f, 8.8f)
            curveTo(9.1f, 7.8f, 8f, 7f, 6.5f, 7f)
            close()
            moveTo(6.5f, 9f)
            curveToRelative(0.8f, 0f, 1.4f, 0.45f, 2.05f, 1.25f)
            lineTo(10.7f, 12f)
            lineToRelative(-2.15f, 1.75f)
            curveTo(7.9f, 14.55f, 7.3f, 15f, 6.5f, 15f)
            curveToRelative(-1.38f, 0f, -2.5f, -1.35f, -2.5f, -3f)
            reflectiveCurveToRelative(1.12f, -3f, 2.5f, -3f)
            close()
            moveTo(17.5f, 9f)
            curveToRelative(1.38f, 0f, 2.5f, 1.35f, 2.5f, 3f)
            reflectiveCurveToRelative(-1.12f, 3f, -2.5f, 3f)
            curveToRelative(-0.8f, 0f, -1.4f, -0.45f, -2.05f, -1.25f)
            lineTo(13.3f, 12f)
            lineToRelative(2.15f, -1.75f)
            curveTo(16.1f, 9.45f, 16.7f, 9f, 17.5f, 9f)
            close()
        }
    }
}
