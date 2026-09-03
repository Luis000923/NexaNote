package com.nexanote.app.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.nexanote.app.canvas.ScenePage
import com.nexanote.app.canvas.ScenePrimitive
import com.nexanote.app.canvas.SceneTemplate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pinta una [ScenePage] del núcleo Rust sobre un [Canvas] de `android.graphics`,
 * en coordenadas de página (píxeles lógicos `@1x`). Es la contraparte "a papel"
 * de `DocumentCanvas`: la misma lista de primitivas ordenada por el núcleo, el
 * mismo aspecto, pero sin gestos, sombra de hoja ni vista previa de edición.
 *
 * El llamante ([AndroidPdfWriter]) escala el lienzo (px -> puntos PDF) antes de
 * invocar, así que aquí sólo se dibuja con las coordenadas de la escena tal cual.
 */
internal object ScenePdfPainter {

    private val GUIDE = 0xFFB9C4D0.toInt()
    private val BORDER = 0xFF9AA6B2.toInt()
    private val GRAPH_BG = 0xFFFCFDFF.toInt()
    private val GRAPH_GRID = 0xFFD3DBE6.toInt()
    private val GRAPH_AXIS = 0xFF5B6472.toInt()
    private val IMAGE_PLACEHOLDER = 0xFFEEF1F5.toInt()

    fun paint(canvas: Canvas, scene: ScenePage, images: (String) -> Bitmap?) {
        drawBackground(canvas, scene)
        scene.primitives.forEach { drawPrimitive(canvas, it, images) }
    }

    private fun drawBackground(canvas: Canvas, scene: ScenePage) {
        val w = scene.widthPx
        val h = scene.heightPx
        canvas.drawRect(0f, 0f, w, h, fill(scene.background.toArgb()))

        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GUIDE
            strokeWidth = 1f
        }
        when (val t = scene.template) {
            SceneTemplate.Blank -> Unit
            is SceneTemplate.Grid -> if (t.spacingPx > 0f) {
                var x = t.spacingPx
                while (x < w) { canvas.drawLine(x, 0f, x, h, guide); x += t.spacingPx }
                var y = t.spacingPx
                while (y < h) { canvas.drawLine(0f, y, w, y, guide); y += t.spacingPx }
            }
            is SceneTemplate.Ruled -> if (t.spacingPx > 0f) {
                var y = t.spacingPx
                while (y < h) { canvas.drawLine(0f, y, w, y, guide); y += t.spacingPx }
            }
            is SceneTemplate.Dotted -> if (t.spacingPx > 0f) {
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GUIDE; style = Paint.Style.FILL }
                var y = t.spacingPx
                while (y < h) {
                    var x = t.spacingPx
                    while (x < w) { canvas.drawCircle(x, y, 1.2f, dot); x += t.spacingPx }
                    y += t.spacingPx
                }
            }
        }
        canvas.drawRect(0f, 0f, w, h, stroke(BORDER, 1f))
    }

    private fun drawPrimitive(canvas: Canvas, p: ScenePrimitive, images: (String) -> Bitmap?) {
        when (p) {
            is ScenePrimitive.Polyline -> if (p.points.size >= 2) {
                canvas.drawPath(polyline(p.points.map { it.x to it.y }), stroke(p.color.toArgb(), p.width, round = true))
            }

            is ScenePrimitive.Rect -> {
                val r = rect(p.topLeft.x, p.topLeft.y, p.size.width, p.size.height)
                p.fill?.let { canvas.drawRect(r, fill(it.toArgb())) }
                canvas.drawRect(r, stroke(p.stroke.toArgb(), p.strokeWidth))
            }

            is ScenePrimitive.Ellipse -> {
                val r = rect(p.topLeft.x, p.topLeft.y, p.size.width, p.size.height)
                p.fill?.let { canvas.drawOval(r, fill(it.toArgb())) }
                canvas.drawOval(r, stroke(p.stroke.toArgb(), p.strokeWidth))
            }

            is ScenePrimitive.Line ->
                canvas.drawLine(p.start.x, p.start.y, p.end.x, p.end.y, stroke(p.color.toArgb(), p.width, round = true))

            is ScenePrimitive.Arrow -> {
                val paint = stroke(p.color.toArgb(), p.width, round = true)
                canvas.drawLine(p.start.x, p.start.y, p.end.x, p.end.y, paint)
                val angle = atan2(p.end.y - p.start.y, p.end.x - p.start.x)
                val head = 14f
                val spread = 0.5f
                for (s in floatArrayOf(-spread, spread)) {
                    val hx = p.end.x - head * cos(angle + s)
                    val hy = p.end.y - head * sin(angle + s)
                    canvas.drawLine(p.end.x, p.end.y, hx, hy, paint)
                }
            }

            is ScenePrimitive.Text ->
                drawText(canvas, p.content, p.origin.x, p.origin.y, p.fontSize, p.color.toArgb(), p.bold, p.italic, p.underline)

            is ScenePrimitive.Formula -> {
                val text = p.value?.let { "${p.latex} = ${formatValue(it)}" } ?: p.latex
                drawText(canvas, text, p.origin.x, p.origin.y, 18f, p.color.toArgb(), bold = false, italic = true, underline = false)
            }

            is ScenePrimitive.Graph -> drawGraph(canvas, p)

            is ScenePrimitive.Image -> drawImage(canvas, p, images(p.source))
        }
    }

    private fun drawGraph(canvas: Canvas, g: ScenePrimitive.Graph) {
        val x0 = g.topLeft.x
        val y0 = g.topLeft.y
        val w = g.size.width
        val h = g.size.height

        canvas.drawRect(x0, y0, x0 + w, y0 + h, fill(GRAPH_BG))

        val grid = stroke(GRAPH_GRID, 1f)
        for (x in g.gridX) canvas.drawLine(x, y0, x, y0 + h, grid)
        for (y in g.gridY) canvas.drawLine(x0, y, x0 + w, y, grid)

        val axis = stroke(GRAPH_AXIS, 1.6f)
        g.axisX?.let { canvas.drawLine(it, y0, it, y0 + h, axis) }
        g.axisY?.let { canvas.drawLine(x0, it, x0 + w, it, axis) }

        val curve = stroke(g.color.toArgb(), 2f, round = true)
        for (segment in g.polylines) {
            if (segment.size < 2) continue
            canvas.drawPath(polyline(segment.map { it.x to it.y }), curve)
        }

        canvas.drawRect(x0, y0, x0 + w, y0 + h, stroke(BORDER, 1.2f))
        drawText(canvas, g.expression, x0 + 4f, y0 + 14f, 13f, GRAPH_AXIS, bold = false, italic = true, underline = false)
    }

    private fun drawImage(canvas: Canvas, p: ScenePrimitive.Image, bitmap: Bitmap?) {
        val l = p.topLeft.x
        val t = p.topLeft.y
        val w = p.size.width
        val h = p.size.height
        val dst = rect(l, t, w, h)

        if (bitmap != null && !bitmap.isRecycled && w > 0f && h > 0f) {
            canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                dst,
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        } else {
            canvas.drawRect(dst, fill(IMAGE_PLACEHOLDER))
            val diagonal = stroke(BORDER, 1f)
            canvas.drawLine(l, t, l + w, t + h, diagonal)
            canvas.drawLine(l + w, t, l, t + h, diagonal)
        }
        canvas.drawRect(dst, stroke(BORDER, 1.2f))
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        argb: Int,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            textSize = size
            isFakeBoldText = bold
            isUnderlineText = underline
            textSkewX = if (italic) -0.25f else 0f
        }
        // `y` es la línea base, consistente con el modelo y con `DocumentCanvas`.
        canvas.drawText(text, x, y, paint)
    }

    private fun polyline(points: List<Pair<Float, Float>>): Path = Path().apply {
        moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float) = RectF(x, y, x + w, y + h)

    private fun stroke(argb: Int, width: Float, round: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = Paint.Style.STROKE
            strokeWidth = width
            if (round) {
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        }

    private fun fill(argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        style = Paint.Style.FILL
    }

    /** Igual que `DocumentCanvas`: entero si es exacto, si no 4 decimales. */
    private fun formatValue(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.4f".format(v)
}
