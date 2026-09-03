package com.nexanote.app.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lienzo de documento: pinta una [ScenePage] (producida por el núcleo Rust) en un
 * `Canvas` de Compose, con zoom y desplazamiento por gestos y **captura de trazos
 * a mano alzada** con el stylus o el dedo (Fase 4).
 *
 * Rendimiento: el `Path` de fondo se calcula una vez por tamaño de viewport; el
 * dibujo de primitivas es directo sobre el `DrawScope` sin asignaciones en el
 * bucle salvo las estrictamente necesarias. La captura de puntos del stylus sólo
 * hace una proyección afín por muestra; la serialización y el envío al núcleo
 * ocurren fuera del hilo principal, tras levantar el lápiz.
 */
@Composable
fun DocumentCanvas(
    scene: ScenePage,
    transform: CanvasTransform,
    onTransformChange: (CanvasTransform) -> Unit,
    modifier: Modifier = Modifier,
    tool: DrawingTool = DrawingTool.Pen,
    onStrokeCommit: (List<StrokeSample>) -> Unit = {},
) {
    // Trazo en curso (coordenadas del documento). Se conserva pintado hasta que
    // llega la nueva escena del núcleo que ya lo incluye: así no hay parpadeo.
    val active = remember { ActiveStroke() }
    LaunchedEffect(scene) { active.clear() }

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
            }
            .pointerInput(scene.pageId, tool, transform) {
                if (tool != DrawingTool.Pen) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val gesture = StrokeGesture(transform, down.uptimeMillis)
                    active.begin()
                    recordSample(gesture, active, transform, down)
                    down.consume()

                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        // Un segundo puntero: es un gesto de navegación, no de
                        // escritura. Se cancela el trazo y se cede a zoom/pan.
                        if (event.changes.size > 1) {
                            cancelled = true
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        recordSample(gesture, active, transform, change)
                        val lifted = !change.pressed
                        change.consume()
                        if (lifted) break
                    }

                    if (cancelled || !gesture.isDrawable) {
                        active.clear()
                    } else {
                        onStrokeCommit(gesture.samples)
                    }
                }
            },
    ) {
        val revision = active.revision // suscribe el redibujado al trazo activo
        withTransform({
            translate(transform.offsetX, transform.offsetY)
            scale(transform.scale, transform.scale, Offset.Zero)
        }) {
            drawPageBackground(scene)
            scene.primitives.forEach { drawPrimitive(it) }
            if (revision >= 0) drawActiveStroke(active.points)
        }
    }
}

/**
 * Estado mutable del trazo en curso. Los puntos son una lista plana (sin overhead
 * de `SnapshotStateList`); un contador de revisión, ese sí observable, dispara el
 * redibujado del `Canvas` en cada muestra nueva para lograr baja latencia.
 */
private class ActiveStroke {
    val points = ArrayList<Offset>(256)

    var revision by mutableIntStateOf(0)
        private set

    fun begin() {
        points.clear()
        revision++
    }

    fun add(point: Offset) {
        points.add(point)
        revision++
    }

    fun clear() {
        if (points.isEmpty()) return
        points.clear()
        revision++
    }
}

/** Registra la muestra actual del puntero en el trazo en curso. */
private fun recordSample(
    gesture: StrokeGesture,
    active: ActiveStroke,
    transform: CanvasTransform,
    change: PointerInputChange,
) {
    gesture.addScreenPoint(change.position.x, change.position.y, change.pressure, change.uptimeMillis)
    active.add(modelPoint(transform, change.position))
}

private fun modelPoint(transform: CanvasTransform, screen: Offset): Offset {
    val (x, y) = transform.screenToModel(screen.x, screen.y)
    return Offset(x, y)
}

private fun DrawScope.drawActiveStroke(points: List<Offset>) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(
        path = path,
        color = Color(0xFF141821),
        style = Stroke(
            width = StrokeGesture.DEFAULT_WIDTH,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
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
