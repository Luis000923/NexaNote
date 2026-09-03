package com.nexanote.app.canvas

/**
 * Herramienta activa del lienzo.
 *
 *  - [Pen]: los gestos de un puntero (stylus o un dedo) escriben a mano alzada;
 *    dos o más punteros siguen sirviendo para hacer zoom/pan.
 *  - [Pan]: todos los gestos navegan (zoom/pan); no se dibuja.
 *  - [Line], [Rectangle], [Ellipse], [Arrow]: el arrastre de un puntero dibuja
 *    la forma geométrica correspondiente con vista previa en tiempo real; dos o
 *    más punteros siguen navegando.
 *  - [Text]: una pulsación simple fija la posición de un bloque de texto y abre
 *    un campo para escribir su contenido con el teclado virtual.
 *  - [Select]: el arrastre delimita un área y agrupa los elementos que quedan
 *    dentro; una pulsación simple elige el elemento tocado. Con algo
 *    seleccionado, arrastrar desde dentro del marco lo mueve.
 */
enum class DrawingTool { Pen, Pan, Select, Line, Rectangle, Ellipse, Arrow, Text, Formula, Graph, Image }

/** Color RGBA de un trazo, en el mismo formato que espera el núcleo Rust. */
data class StrokeColor(val r: Int, val g: Int, val b: Int, val a: Int = 255) {
    companion object {
        /** Tinta por defecto (azul muy oscuro, casi negro). */
        val Ink = StrokeColor(20, 24, 33)
    }
}

/**
 * Una muestra de trazo **ya en coordenadas del documento** (px lógicos @1x), lista
 * para viajar al núcleo. `timestampMs` es relativo al inicio del trazo.
 */
data class StrokeSample(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timestampMs: Long,
)

/**
 * Acumulador de un trazo en curso.
 *
 * Durante la captura vive en el hilo de UI, pero su único trabajo por evento es
 * agregar una muestra: una proyección afín y un `add` a una lista predimensionada
 * -- O(1), sin geometría pesada ni serialización. La conversión a JSON y el envío
 * al núcleo se hacen después, fuera del hilo principal (ver
 * `DocumentViewModel.commitStroke`).
 */
class StrokeGesture(
    private val transform: CanvasTransform,
    private val startUptimeMs: Long,
) {
    private val _samples = ArrayList<StrokeSample>(256)

    /** Muestras capturadas, en orden y en coordenadas del documento. */
    val samples: List<StrokeSample> get() = _samples

    /** `true` en cuanto hay al menos una muestra: un trazo consolidable. */
    val isDrawable: Boolean get() = _samples.isNotEmpty()

    /**
     * Agrega una muestra dada en **coordenadas de pantalla**; se proyecta al
     * espacio del documento con la [transform] vigente al empezar el trazo.
     */
    fun addScreenPoint(screenX: Float, screenY: Float, pressure: Float, uptimeMs: Long) {
        val (mx, my) = transform.screenToModel(screenX, screenY)
        _samples.add(
            StrokeSample(
                x = mx,
                y = my,
                pressure = normalizePressure(pressure),
                timestampMs = (uptimeMs - startUptimeMs).coerceAtLeast(0L),
            ),
        )
    }

    /** Serializa el trazo como `ElementKind::Stroke` para el núcleo Rust. */
    fun toStrokeJson(color: StrokeColor = StrokeColor.Ink, width: Float = DEFAULT_WIDTH): String =
        buildStrokeJson(_samples, color, width)

    companion object {
        /** Grosor base del trazo, en unidades lógicas del documento. */
        const val DEFAULT_WIDTH = 3.0f

        /**
         * Normaliza la presión de un puntero: un stylus entrega `(0, 1]`; un dedo
         * suele entregar `0` o valores fuera de rango. Se satura a `[0.05, 1]` y
         * se cae a `0.5` cuando no hay señal útil.
         */
        fun normalizePressure(raw: Float): Float =
            if (raw.isFinite() && raw > 0f) raw.coerceIn(0.05f, 1f) else 0.5f

        /**
         * Construye el JSON de un `Stroke` a partir de muestras ya en coordenadas
         * del documento. Se arma a mano (sin `JSONObject`) para no asignar de más
         * en trazos de cientos de puntos.
         */
        fun buildStrokeJson(
            samples: List<StrokeSample>,
            color: StrokeColor,
            width: Float,
        ): String {
            val sb = StringBuilder(48 + samples.size * 72)
            sb.append("{\"points\":[")
            for (i in samples.indices) {
                val s = samples[i]
                if (i > 0) sb.append(',')
                sb.append("{\"position\":{\"x\":").append(s.x)
                    .append(",\"y\":").append(s.y)
                    .append("},\"pressure\":").append(s.pressure)
                    .append(",\"timestamp_ms\":").append(s.timestampMs)
                    .append('}')
            }
            sb.append("],\"color\":{\"r\":").append(color.r)
                .append(",\"g\":").append(color.g)
                .append(",\"b\":").append(color.b)
                .append(",\"a\":").append(color.a)
                .append("},\"width\":").append(width)
                .append('}')
            return sb.toString()
        }
    }
}
