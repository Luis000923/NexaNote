package com.nexanote.app.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lienzo de documento: pinta una [ScenePage] (producida por el núcleo Rust) en un
 * `Canvas` de Compose, con zoom y desplazamiento por gestos.
 *
 * Rendimiento: el `Path` de fondo se calcula una vez por tamaño de viewport; el
 * dibujo de primitivas es directo sobre el `DrawScope` sin asignaciones en el
 * bucle salvo las estrictamente necesarias.
 */
@Composable
fun DocumentCanvas(
    scene: ScenePage,
    transform: CanvasTransform,
    onTransformChange: (CanvasTransform) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFE9EBEF))
            .clipToBounds()
            .pointerInput(scene.pageId) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    onTransformChange(
                        transform.applyGesture(pan.x, pan.y, zoom, centroid.x, centroid.y),
                    )
                }
            },
    ) {
        withTransform({
            translate(transform.offsetX, transform.offsetY)
            scale(transform.scale, transform.scale, Offset.Zero)
        }) {
            drawPageBackground(scene)
            scene.primitives.forEach { drawPrimitive(it) }
        }
    }
}

private fun DrawScope.drawPageBackground(scene: ScenePage) {
    val w = scene.widthPx
    val h = scene.heightPx
    // Sombra sutil + hoja.
    drawRect(Color(0x22000000), topLeft = Offset(3f, 4f), size = Size(w, h))
    drawRect(scene.background, size = Size(w, h))

    val guide = Color(0xFFB9C4D0)
    when (val t = scene.template) {
        SceneTemplate.Blank -> Unit
        is SceneTemplate.Grid -> {
            var x = t.spacingPx
            while (x < w) {
                drawLine(guide, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                x += t.spacingPx
            }
            var y = t.spacingPx
            while (y < h) {
                drawLine(guide, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                y += t.spacingPx
            }
        }
        is SceneTemplate.Ruled -> {
            var y = t.spacingPx
            while (y < h) {
                drawLine(guide, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                y += t.spacingPx
            }
        }
        is SceneTemplate.Dotted -> {
            var y = t.spacingPx
            while (y < h) {
                var x = t.spacingPx
                while (x < w) {
                    drawCircle(guide, radius = 1.2f, center = Offset(x, y))
                    x += t.spacingPx
                }
                y += t.spacingPx
            }
        }
    }
    // Borde de página.
    drawRect(Color(0xFF9AA6B2), size = Size(w, h), style = Stroke(width = 1f))
}

private fun DrawScope.drawPrimitive(p: ScenePrimitive) {
    when (p) {
        is ScenePrimitive.Polyline -> {
            if (p.points.size >= 2) {
                val path = Path().apply {
                    moveTo(p.points[0].x, p.points[0].y)
                    for (i in 1 until p.points.size) lineTo(p.points[i].x, p.points[i].y)
                }
                drawPath(path, p.color, style = Stroke(width = p.width, cap = StrokeCap.Round))
            }
        }

        is ScenePrimitive.Rect -> {
            p.fill?.let { drawRect(it, topLeft = p.topLeft, size = p.size) }
            drawRect(p.stroke, topLeft = p.topLeft, size = p.size, style = Stroke(width = p.strokeWidth))
        }

        is ScenePrimitive.Ellipse -> {
            p.fill?.let { drawOval(it, topLeft = p.topLeft, size = p.size) }
            drawOval(p.stroke, topLeft = p.topLeft, size = p.size, style = Stroke(width = p.strokeWidth))
        }

        is ScenePrimitive.Line ->
            drawLine(p.color, p.start, p.end, strokeWidth = p.width, cap = StrokeCap.Round)

        is ScenePrimitive.Arrow -> {
            drawLine(p.color, p.start, p.end, strokeWidth = p.width, cap = StrokeCap.Round)
            val angle = atan2(p.end.y - p.start.y, p.end.x - p.start.x)
            val head = 14f
            val spread = 0.5f
            for (s in floatArrayOf(-spread, spread)) {
                val hx = p.end.x - head * cos(angle + s)
                val hy = p.end.y - head * sin(angle + s)
                drawLine(p.color, p.end, Offset(hx, hy), strokeWidth = p.width, cap = StrokeCap.Round)
            }
        }

        is ScenePrimitive.Text -> drawNativeText(
            p.content, p.origin, p.fontSize, p.color, p.bold, p.italic, p.underline,
        )

        is ScenePrimitive.Formula -> drawNativeText(
            p.latex, p.origin, 18f, p.color, bold = false, italic = true, underline = false,
        )
    }
}

private fun DrawScope.drawNativeText(
    text: String,
    origin: Offset,
    fontSize: Float,
    color: Color,
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = fontSize
        isFakeBoldText = bold
        isUnderlineText = underline
        textSkewX = if (italic) -0.25f else 0f
    }
    // `origin.y` es la línea base del texto, consistente con el modelo.
    drawContext.canvas.nativeCanvas.drawText(text, origin.x, origin.y, paint)
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(), (red * 255f).toInt(), (green * 255f).toInt(), (blue * 255f).toInt(),
)
