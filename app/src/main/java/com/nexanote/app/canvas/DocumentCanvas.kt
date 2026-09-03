package com.nexanote.app.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lienzo de documento: pinta una [ScenePage] (producida por el núcleo Rust) en un
 * `Canvas` de Compose, con zoom y desplazamiento por gestos, **captura de trazos
 * a mano alzada** (Fase 4) y **dibujo de formas geométricas con vista previa**
 * (Fase 5).
 *
 * Rendimiento: el `Path` de fondo se calcula una vez por tamaño de viewport; el
 * dibujo de primitivas es directo sobre el `DrawScope` sin asignaciones en el
 * bucle salvo las estrictamente necesarias. La captura del puntero sólo hace una
 * proyección afín por muestra; la serialización y el envío al núcleo ocurren
 * fuera del hilo principal, al levantar el puntero.
 */
@Composable
fun DocumentCanvas(
    scene: ScenePage,
    transform: CanvasTransform,
    onTransformChange: (CanvasTransform) -> Unit,
    modifier: Modifier = Modifier,
    tool: DrawingTool = DrawingTool.Pen,
    /** Grosor (unidades del documento) del lápiz: se refleja en la vista previa del trazo. */
    strokeWidth: Float = StrokeGesture.DEFAULT_WIDTH,
    /** Color de la vista previa del trazo en curso (la tinta activa). */
    inkColor: Color = Color(0xFF141821),
    onStrokeCommit: (List<StrokeSample>) -> Unit = {},
    onShapeCommit: (ShapeKind, ShapeBounds) -> Unit = { _, _ -> },
    onTextRequest: (Float, Float) -> Unit = { _, _ -> },
    onFormulaRequest: (Float, Float) -> Unit = { _, _ -> },
    onGraphRequest: (Float, Float) -> Unit = { _, _ -> },
    onImageRequest: (Float, Float) -> Unit = { _, _ -> },
    /** Selección vigente, resuelta por el núcleo. Se resalta y se puede arrastrar. */
    selection: Selection = Selection.Empty,
    /** El usuario delimitó un área (coordenadas del documento): pide la selección al núcleo. */
    onSelectArea: (Rect) -> Unit = {},
    /** El usuario tocó un punto con la herramienta de selección activa. */
    onSelectTap: (Float, Float) -> Unit = { _, _ -> },
    /** El usuario soltó tras arrastrar la selección: desplazamiento en coordenadas del documento. */
    onSelectionMove: (Float, Float) -> Unit = { _, _ -> },
    /** Resuelve la ruta relativa de una imagen a su bitmap ya decodificado, o `null` si aún no está listo. */
    imageProvider: (String) -> ImageBitmap? = { null },
) {
    // Trazo / forma en curso (coordenadas del documento). Se conservan pintados
    // hasta que llega la nueva escena del núcleo que ya los incluye: sin parpadeo.
    val active = remember { ActiveStroke() }
    val activeShape = remember { ActiveShape() }
    val activeSelect = remember { ActiveSelection() }
    LaunchedEffect(scene) {
        active.clear()
        activeShape.clear()
        activeSelect.clear()
    }

    // La captura de puntero lee siempre el `transform`/`selection` más recientes
    // sin reiniciar el pipeline de gestos: así un zoom o una nueva selección no
    // aborta un trazo en curso ni descarta muestras (lo que se veía como "líneas
    // rectas") y no reconstruye la maquinaria de gestos en cada fotograma.
    val currentTransform by rememberUpdatedState(transform)
    val currentSelection by rememberUpdatedState(selection)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFE9EBEF))
            .clipToBounds()
            .pointerInput(scene.pageId) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    onTransformChange(
                        currentTransform.applyGesture(pan.x, pan.y, zoom, centroid.x, centroid.y),
                    )
                }
            }
            .pointerInput(scene.pageId, tool) {
                val shapeKind = tool.asShapeKind()
                when {
                    tool == DrawingTool.Select ->
                        captureSelection(
                            activeSelect, { currentTransform }, { currentSelection },
                            onSelectArea, onSelectTap, onSelectionMove,
                        )

                    tool == DrawingTool.Pen ->
                        captureFreehand(active, { currentTransform }, onStrokeCommit)

                    shapeKind != null ->
                        captureShape(shapeKind, activeShape, { currentTransform }, onShapeCommit)

                    tool == DrawingTool.Text ->
                        detectTapGestures { pos ->
                            val (mx, my) = currentTransform.screenToModel(pos.x, pos.y)
                            onTextRequest(mx, my)
                        }

                    tool == DrawingTool.Formula ->
                        detectTapGestures { pos ->
                            val (mx, my) = currentTransform.screenToModel(pos.x, pos.y)
                            onFormulaRequest(mx, my)
                        }

                    tool == DrawingTool.Graph ->
                        detectTapGestures { pos ->
                            val (mx, my) = currentTransform.screenToModel(pos.x, pos.y)
                            onGraphRequest(mx, my)
                        }

                    tool == DrawingTool.Image ->
                        detectTapGestures { pos ->
                            val (mx, my) = currentTransform.screenToModel(pos.x, pos.y)
                            onImageRequest(mx, my)
                        }

                    else -> Unit // DrawingTool.Pan: sólo navega (transform gestures).
                }
            },
    ) {
        // Capa 1 -- la escena que produce el núcleo. Sólo se repinta cuando cambian
        // la escena o la transformación: nunca por una muestra del trazo en curso.
        Canvas(modifier = Modifier.fillMaxSize()) {
            withTransform({
                translate(transform.offsetX, transform.offsetY)
                scale(transform.scale, transform.scale, Offset.Zero)
            }) {
                drawPageBackground(scene)
                scene.primitives.forEach { drawPrimitive(it, imageProvider) }
            }
        }
        // Capa 2 -- trazo / forma / marco de selección en curso. Es ligera (unos
        // pocos `drawPath`), así que puede invalidarse en cada muestra del puntero
        // sin arrastrar consigo el redibujado de toda la escena.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeRevision = active.revision // suscribe el redibujado al trazo activo
            val shapeRevision = activeShape.revision // y a la forma en curso
            val selectRevision = activeSelect.revision // y al marco/arrastre de selección
            withTransform({
                translate(transform.offsetX, transform.offsetY)
                scale(transform.scale, transform.scale, Offset.Zero)
            }) {
                if (strokeRevision >= 0) drawActiveStroke(active, strokeWidth, inkColor)
                if (shapeRevision >= 0) drawActiveShape(activeShape)
                if (selectRevision >= 0) {
                    drawSelection(scene, selection, activeSelect, transform.scale)
                }
            }
        }
    }
}

/** Bucle de captura de un trazo a mano alzada. Nunca retorna. */
private suspend fun PointerInputScope.captureFreehand(
    active: ActiveStroke,
    transform: () -> CanvasTransform,
    onStrokeCommit: (List<StrokeSample>) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // La transformación queda fijada al empezar el trazo: durante el trazo no
        // cambia, y así cada muestra se proyecta con un marco de referencia estable.
        val tf = transform()
        val gesture = StrokeGesture(tf, down.uptimeMillis)
        active.begin()
        recordSample(gesture, active, tf, down)
        down.consume()

        var cancelled = false
        while (true) {
            val event = awaitPointerEvent()
            // Un segundo puntero es navegación, no escritura: se cede a zoom/pan.
            if (event.changes.size > 1) {
                cancelled = true
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            recordSample(gesture, active, tf, change)
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
}

/** Bucle de captura de una forma geométrica con vista previa. Nunca retorna. */
private suspend fun PointerInputScope.captureShape(
    kind: ShapeKind,
    activeShape: ActiveShape,
    transform: () -> CanvasTransform,
    onShapeCommit: (ShapeKind, ShapeBounds) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tf = transform()
        val (startX, startY) = tf.screenToModel(down.position.x, down.position.y)
        activeShape.begin(kind, startX, startY)
        down.consume()

        var cancelled = false
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.size > 1) {
                cancelled = true
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val (mx, my) = tf.screenToModel(change.position.x, change.position.y)
            activeShape.update(mx, my)
            val lifted = !change.pressed
            change.consume()
            if (lifted) break
        }

        val bounds = ShapeGeometry.boundsFor(
            kind, activeShape.startX, activeShape.startY, activeShape.endX, activeShape.endY,
        )
        if (cancelled || !ShapeGeometry.isDrawable(kind, bounds)) {
            activeShape.clear()
        } else {
            onShapeCommit(kind, bounds)
        }
    }
}

/**
 * Bucle de la herramienta de selección. Nunca retorna.
 *
 * Un arrastre que empieza **dentro** del marco de la selección vigente la mueve;
 * cualquier otro arrastre delimita un área nueva. Si apenas hubo movimiento, el
 * gesto se interpreta como un toque sobre un elemento. En los tres casos aquí
 * sólo se acumula geometría: quién queda seleccionado y qué se traslada lo
 * resuelve el núcleo al soltar.
 */
private suspend fun PointerInputScope.captureSelection(
    activeSelect: ActiveSelection,
    transform: () -> CanvasTransform,
    selectionOf: () -> Selection,
    onSelectArea: (Rect) -> Unit,
    onSelectTap: (Float, Float) -> Unit,
    onSelectionMove: (Float, Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tf = transform()
        val selection = selectionOf()
        val (startX, startY) = tf.screenToModel(down.position.x, down.position.y)
        val moving = selection.isNotEmpty &&
            selection.bounds?.contains(Offset(startX, startY)) == true
        activeSelect.begin(moving, startX, startY)
        down.consume()

        var cancelled = false
        while (true) {
            val event = awaitPointerEvent()
            // Un segundo puntero es navegación: se cede el gesto a zoom/pan.
            if (event.changes.size > 1) {
                cancelled = true
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val (mx, my) = tf.screenToModel(change.position.x, change.position.y)
            activeSelect.update(mx, my)
            val lifted = !change.pressed
            change.consume()
            if (lifted) break
        }

        val area = SelectionInput.marquee(
            activeSelect.startX, activeSelect.startY, activeSelect.endX, activeSelect.endY,
        )
        val dx = activeSelect.endX - activeSelect.startX
        val dy = activeSelect.endY - activeSelect.startY
        activeSelect.clear()

        when {
            cancelled -> Unit
            moving -> if (dx != 0f || dy != 0f) onSelectionMove(dx, dy)
            SelectionInput.isMarqueeDrag(area) -> onSelectArea(area)
            else -> onSelectTap(startX, startY)
        }
    }
}

/**
 * Estado mutable del gesto de selección en curso: si delimita un área o mueve lo
 * ya seleccionado, y los extremos del arrastre en coordenadas del documento.
 * Igual que [ActiveStroke], sólo el contador de revisión es observable.
 */
private class ActiveSelection {
    var active = false
        private set
    var moving = false
        private set
    var startX = 0f
        private set
    var startY = 0f
        private set
    var endX = 0f
        private set
    var endY = 0f
        private set

    var revision by mutableIntStateOf(0)
        private set

    fun begin(moving: Boolean, x: Float, y: Float) {
        active = true
        this.moving = moving
        startX = x
        startY = y
        endX = x
        endY = y
        revision++
    }

    fun update(x: Float, y: Float) {
        endX = x
        endY = y
        revision++
    }

    fun clear() {
        if (!active) return
        active = false
        moving = false
        revision++
    }
}

/**
 * Resalta la selección vigente y, si la hay, el gesto en curso.
 *
 * Los grosores se dividen por [scale] para que el realce mantenga el mismo peso
 * visual con cualquier zoom.
 */
private fun DrawScope.drawSelection(
    scene: ScenePage,
    selection: Selection,
    gesture: ActiveSelection,
    scale: Float,
) {
    val accent = Color(0xFF2D6CDF)
    val hairline = (1.5f / scale.coerceAtLeast(0.01f))
    val dashes = PathEffect.dashPathEffect(
        floatArrayOf(8f / scale.coerceAtLeast(0.01f), 6f / scale.coerceAtLeast(0.01f)),
    )

    // Desplazamiento en curso del arrastre, para que el realce siga al dedo.
    val (dx, dy) = if (gesture.active && gesture.moving) {
        (gesture.endX - gesture.startX) to (gesture.endY - gesture.startY)
    } else {
        0f to 0f
    }

    if (selection.isNotEmpty) {
        val ids = selection.ids.toHashSet()
        for (hit in scene.hits) {
            if (hit.id !in ids) continue
            drawRect(
                color = accent.copy(alpha = 0.55f),
                topLeft = Offset(hit.bounds.left + dx, hit.bounds.top + dy),
                size = Size(hit.bounds.width, hit.bounds.height),
                style = Stroke(width = hairline, pathEffect = dashes),
            )
        }
        selection.bounds?.let { b ->
            drawRect(
                color = accent.copy(alpha = 0.10f),
                topLeft = Offset(b.left + dx, b.top + dy),
                size = Size(b.width, b.height),
            )
            drawRect(
                color = accent,
                topLeft = Offset(b.left + dx, b.top + dy),
                size = Size(b.width, b.height),
                style = Stroke(width = hairline * 1.4f),
            )
        }
    }

    // Marco de selección que el usuario está trazando ahora mismo.
    if (gesture.active && !gesture.moving) {
        val area = SelectionInput.marquee(gesture.startX, gesture.startY, gesture.endX, gesture.endY)
        drawRect(accent.copy(alpha = 0.08f), topLeft = area.topLeft, size = area.size)
        drawRect(
            color = accent,
            topLeft = area.topLeft,
            size = area.size,
            style = Stroke(width = hairline, pathEffect = dashes),
        )
    }
}

/**
 * Estado mutable del trazo en curso, optimizado para el bucle de captura:
 *
 *  - las coordenadas viven en un `FloatArray` que crece por duplicación -- cero
 *    autoboxing de `Offset`, cero `SnapshotStateList`;
 *  - el [Path] de la vista previa se construye **incrementalmente** (`lineTo` por
 *    cada punto nuevo), nunca se reconstruye entero en cada fotograma;
 *  - sólo [revision] es estado observable, y se incrementa **una vez por evento**
 *    de puntero (no una vez por muestra), así una ráfaga de puntos `historical`
 *    provoca un único redibujado.
 */
private class ActiveStroke {
    private var xy = FloatArray(1024)

    /** Número de puntos acumulados. */
    var size = 0
        private set

    /** Ruta de la vista previa, lista para `drawPath` sin más trabajo. */
    val path = Path()

    var revision by mutableIntStateOf(0)
        private set

    fun begin() {
        size = 0
        path.rewind()
        revision++
    }

    /** Añade un punto (coords del documento) al buffer y a la ruta. No redibuja. */
    fun add(x: Float, y: Float) {
        val i = size * 2
        if (i == xy.size) xy = xy.copyOf(xy.size * 2)
        xy[i] = x
        xy[i + 1] = y
        if (size == 0) path.moveTo(x, y) else path.lineTo(x, y)
        size++
    }

    /** Publica los puntos añadidos desde el último `touch`: un solo redibujado. */
    fun touch() {
        revision++
    }

    fun clear() {
        if (size == 0) return
        size = 0
        path.rewind()
        revision++
    }
}

/**
 * Estado mutable de la forma geométrica en curso: su tipo y los puntos inicial y
 * final del arrastre, en coordenadas del documento. Igual que [ActiveStroke], sólo
 * el contador de revisión es observable y dispara el redibujado de la vista previa.
 */
private class ActiveShape {
    var kind: ShapeKind? = null
        private set
    var startX = 0f
        private set
    var startY = 0f
        private set
    var endX = 0f
        private set
    var endY = 0f
        private set

    var revision by mutableIntStateOf(0)
        private set

    fun begin(kind: ShapeKind, x: Float, y: Float) {
        this.kind = kind
        startX = x
        startY = y
        endX = x
        endY = y
        revision++
    }

    fun update(x: Float, y: Float) {
        endX = x
        endY = y
        revision++
    }

    fun clear() {
        if (kind == null) return
        kind = null
        revision++
    }
}

/** Pinta la vista previa de la forma en curso reutilizando el motor de primitivas. */
private fun DrawScope.drawActiveShape(shape: ActiveShape) {
    val kind = shape.kind ?: return
    val bounds = ShapeGeometry.boundsFor(kind, shape.startX, shape.startY, shape.endX, shape.endY)
    drawPrimitive(shapePreviewPrimitive(kind, bounds))
}

/** Traduce (tipo, límites) a la [ScenePrimitive] equivalente, en color de vista previa. */
private fun shapePreviewPrimitive(kind: ShapeKind, b: ShapeBounds): ScenePrimitive {
    val color = Color(0xFF2D6CDF)
    val w = ShapeGeometry.DEFAULT_STROKE_WIDTH
    return when (kind) {
        ShapeKind.Rectangle -> ScenePrimitive.Rect(
            topLeft = Offset(b.x, b.y), size = Size(b.width, b.height),
            stroke = color, fill = null, strokeWidth = w,
        )
        ShapeKind.Ellipse -> ScenePrimitive.Ellipse(
            topLeft = Offset(b.x, b.y), size = Size(b.width, b.height),
            stroke = color, fill = null, strokeWidth = w,
        )
        ShapeKind.Line -> ScenePrimitive.Line(
            start = Offset(b.x, b.y), end = Offset(b.x + b.width, b.y + b.height),
            color = color, width = w,
        )
        ShapeKind.Arrow -> ScenePrimitive.Arrow(
            start = Offset(b.x, b.y), end = Offset(b.x + b.width, b.y + b.height),
            color = color, width = w,
        )
    }
}

/**
 * Registra en el trazo en curso **todas** las posiciones que trae el evento: los
 * `historical` (muestras intermedias que el sistema agrupó entre dos fotogramas,
 * a la frecuencia real del panel/stylus) y, por último, la posición actual. Sin
 * esto, un panel de 240 Hz sobre una UI de 60/120 Hz entrega un punto por
 * fotograma y las curvas rápidas se ven como segmentos rectos.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun recordSample(
    gesture: StrokeGesture,
    active: ActiveStroke,
    transform: CanvasTransform,
    change: PointerInputChange,
) {
    for (h in change.historical) {
        // La presión histórica no está en esta versión de Compose: se usa la de
        // la muestra actual, suficiente para el grosor variable del trazo.
        gesture.addScreenPoint(h.position.x, h.position.y, change.pressure, h.uptimeMillis)
        val (mx, my) = transform.screenToModel(h.position.x, h.position.y)
        active.add(mx, my)
    }
    gesture.addScreenPoint(change.position.x, change.position.y, change.pressure, change.uptimeMillis)
    val (mx, my) = transform.screenToModel(change.position.x, change.position.y)
    active.add(mx, my)
    // Un único redibujado para todos los puntos de este evento.
    active.touch()
}

/**
 * Pinta la ruta ya construida del trazo en curso. No hace ningún cálculo de
 * geometría: la matriz de zoom/desplazamiento la aplica el `DrawScope` una sola
 * vez, y la ruta se acumuló incrementalmente en [ActiveStroke].
 */
private fun DrawScope.drawActiveStroke(active: ActiveStroke, width: Float, color: Color) {
    if (active.size < 2) return
    drawPath(
        path = active.path,
        color = color,
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawPageBackground(scene: ScenePage) {
    val w = scene.widthPx
    val h = scene.heightPx
    // Un lienzo infinito no es una hoja: nada de sombra ni de borde, y el fondo
    // se extiende con generosidad más allá de la extensión ya ocupada.
    if (scene.infinite) {
        drawRect(
            scene.background,
            topLeft = Offset(-INFINITE_BLEED, -INFINITE_BLEED),
            size = Size(w + INFINITE_BLEED * 2f, h + INFINITE_BLEED * 2f),
        )
    } else {
        // Sombra sutil + hoja.
        drawRect(Color(0x22000000), topLeft = Offset(3f, 4f), size = Size(w, h))
        drawRect(scene.background, size = Size(w, h))
    }

    val guide = Color(0xFFB9C4D0)
    // El pautado de un lienzo infinito se extiende con el fondo, para que no se
    // corte en seco en el límite de la extensión ya ocupada.
    val left = if (scene.infinite) -INFINITE_BLEED else 0f
    val top = if (scene.infinite) -INFINITE_BLEED else 0f
    val right = if (scene.infinite) w + INFINITE_BLEED else w
    val bottom = if (scene.infinite) h + INFINITE_BLEED else h
    when (val t = scene.template) {
        SceneTemplate.Blank -> Unit
        is SceneTemplate.Grid -> {
            forEachStep(left, right, t.spacingPx) { x ->
                drawLine(guide, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
            }
            forEachStep(top, bottom, t.spacingPx) { y ->
                drawLine(guide, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            }
        }
        is SceneTemplate.Ruled -> forEachStep(top, bottom, t.spacingPx) { y ->
            drawLine(guide, Offset(left, y), Offset(right, y), strokeWidth = 1f)
        }
        is SceneTemplate.Dotted -> forEachStep(top, bottom, t.spacingPx) { y ->
            forEachStep(left, right, t.spacingPx) { x ->
                drawCircle(guide, radius = 1.2f, center = Offset(x, y))
            }
        }
    }
    // Borde de página: sólo cuando hay hoja que delimitar.
    if (!scene.infinite) {
        drawRect(Color(0xFF9AA6B2), size = Size(w, h), style = Stroke(width = 1f))
    }
}

/** Sangrado (px de documento) con el que el lienzo infinito se pinta más allá de su contenido. */
private const val INFINITE_BLEED = 4000f

/**
 * Recorre `(from, to)` en pasos de `step` empezando en el primer múltiplo de
 * `step` posterior a `from`, para que el pautado quede alineado con el origen del
 * documento aunque el recorrido empiece en negativo. Acotado a [MAX_GUIDES]
 * repeticiones: un `spacing` diminuto nunca debe bloquear el hilo de dibujo.
 */
private inline fun forEachStep(from: Float, to: Float, step: Float, action: (Float) -> Unit) {
    if (step <= 0f || to <= from) return
    var value = kotlin.math.ceil(from / step) * step
    var drawn = 0
    while (value < to && drawn < MAX_GUIDES) {
        action(value)
        value += step
        drawn++
    }
}

/** Tope de líneas/puntos de pautado por eje y fotograma. */
private const val MAX_GUIDES = 4096

private fun DrawScope.drawPrimitive(
    p: ScenePrimitive,
    imageProvider: (String) -> ImageBitmap? = { null },
) {
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

        is ScenePrimitive.Formula -> {
            val text = p.value?.let { "${p.latex} = ${formatValue(it)}" } ?: p.latex
            drawNativeText(text, p.origin, 18f, p.color, bold = false, italic = true, underline = false)
        }

        is ScenePrimitive.Graph -> drawGraph(p)

        is ScenePrimitive.Image -> drawImagePrimitive(p, imageProvider(p.source))
    }
}

/**
 * Pinta una imagen en su marco. Mientras el bitmap se decodifica fuera del hilo
 * de UI ([ImageBitmap] `null`), muestra un marcador de posición con las diagonales
 * del encuadre.
 */
private fun DrawScope.drawImagePrimitive(p: ScenePrimitive.Image, bitmap: ImageBitmap?) {
    if (bitmap != null && p.size.width > 0f && p.size.height > 0f) {
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(p.topLeft.x.roundToInt(), p.topLeft.y.roundToInt()),
            dstSize = IntSize(p.size.width.roundToInt(), p.size.height.roundToInt()),
        )
    } else {
        drawRect(Color(0xFFEEF1F5), topLeft = p.topLeft, size = p.size)
        val br = Offset(p.topLeft.x + p.size.width, p.topLeft.y + p.size.height)
        val tr = Offset(p.topLeft.x + p.size.width, p.topLeft.y)
        val bl = Offset(p.topLeft.x, p.topLeft.y + p.size.height)
        drawLine(Color(0xFF9AA6B2), p.topLeft, br, strokeWidth = 1f)
        drawLine(Color(0xFF9AA6B2), tr, bl, strokeWidth = 1f)
    }
    drawRect(Color(0xFF9AA6B2), topLeft = p.topLeft, size = p.size, style = Stroke(width = 1.2f))
}

/** Pinta una gráfica de función: fondo, cuadrícula, ejes cartesianos y la curva. */
private fun DrawScope.drawGraph(g: ScenePrimitive.Graph) {
    val grid = Color(0xFFD3DBE6)
    val axis = Color(0xFF5B6472)
    val frame = Color(0xFF9AA6B2)

    drawRect(Color(0xFFFCFDFF), topLeft = g.topLeft, size = g.size)

    for (x in g.gridX) {
        drawLine(grid, Offset(x, g.topLeft.y), Offset(x, g.topLeft.y + g.size.height), strokeWidth = 1f)
    }
    for (y in g.gridY) {
        drawLine(grid, Offset(g.topLeft.x, y), Offset(g.topLeft.x + g.size.width, y), strokeWidth = 1f)
    }

    g.axisX?.let { x ->
        drawLine(axis, Offset(x, g.topLeft.y), Offset(x, g.topLeft.y + g.size.height), strokeWidth = 1.6f)
    }
    g.axisY?.let { y ->
        drawLine(axis, Offset(g.topLeft.x, y), Offset(g.topLeft.x + g.size.width, y), strokeWidth = 1.6f)
    }

    for (segment in g.polylines) {
        if (segment.size < 2) continue
        val path = Path().apply {
            moveTo(segment[0].x, segment[0].y)
            for (i in 1 until segment.size) lineTo(segment[i].x, segment[i].y)
        }
        drawPath(path, g.color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    drawRect(frame, topLeft = g.topLeft, size = g.size, style = Stroke(width = 1.2f))
    drawNativeText(
        g.expression, Offset(g.topLeft.x + 4f, g.topLeft.y + 14f), 13f, axis,
        bold = false, italic = true, underline = false,
    )
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

/** Presenta el valor de una fórmula: entero si es exacto, si no con 4 decimales. */
private fun formatValue(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.4f".format(v)

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(), (red * 255f).toInt(), (green * 255f).toInt(), (blue * 255f).toInt(),
)
