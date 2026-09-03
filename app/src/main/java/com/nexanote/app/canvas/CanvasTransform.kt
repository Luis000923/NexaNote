package com.nexanote.app.canvas

/**
 * Estado de la vista del lienzo: un factor de escala (zoom) y un desplazamiento
 * (pan) en píxeles de pantalla. La transformación aplicada a un punto del modelo
 * `(mx, my)` es:  `screen = m * scale + offset`.
 *
 * Es una clase pura (sin dependencias de Android/Compose) para poder probar la
 * aritmética de zoom/pan en tests de JVM.
 */
data class CanvasTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** Aplica un gesto de pan (`panX/panY`) y zoom (`zoom`, relativo) centrado en `(pivotX, pivotY)`. */
    fun applyGesture(
        panX: Float,
        panY: Float,
        zoom: Float,
        pivotX: Float,
        pivotY: Float,
        minScale: Float = MIN_SCALE,
        maxScale: Float = MAX_SCALE,
    ): CanvasTransform {
        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
        val applied = newScale / scale
        // Mantener el punto bajo el pivote fijo al hacer zoom, y sumar el pan.
        val newOffsetX = pivotX + (offsetX - pivotX) * applied + panX
        val newOffsetY = pivotY + (offsetY - pivotY) * applied + panY
        return CanvasTransform(newScale, newOffsetX, newOffsetY)
    }

    /** Multiplica el zoom por `factor` manteniendo fijo el punto `(pivotX, pivotY)`. */
    fun zoomBy(
        factor: Float,
        pivotX: Float,
        pivotY: Float,
        minScale: Float = MIN_SCALE,
        maxScale: Float = MAX_SCALE,
    ): CanvasTransform = applyGesture(0f, 0f, factor, pivotX, pivotY, minScale, maxScale)

    fun modelToScreen(mx: Float, my: Float): Pair<Float, Float> =
        (mx * scale + offsetX) to (my * scale + offsetY)

    /**
     * Inversa de [modelToScreen]: proyecta un punto en píxeles de pantalla al
     * espacio del documento. Se usa para convertir los eventos del stylus (que
     * llegan en coordenadas de pantalla) a coordenadas del modelo antes de
     * enviarlos al núcleo.
     */
    fun screenToModel(sx: Float, sy: Float): Pair<Float, Float> =
        ((sx - offsetX) / scale) to ((sy - offsetY) / scale)

    companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 8f

        /**
         * Encaja una página de `contentW x contentH` (px de modelo) dentro de un
         * viewport de `viewportW x viewportH`, centrada, con un margen relativo.
         */
        fun fitToViewport(
            contentW: Float,
            contentH: Float,
            viewportW: Float,
            viewportH: Float,
            margin: Float = 0.92f,
        ): CanvasTransform {
            if (contentW <= 0f || contentH <= 0f || viewportW <= 0f || viewportH <= 0f) {
                return CanvasTransform()
            }
            val scale = (minOf(viewportW / contentW, viewportH / contentH) * margin)
                .coerceIn(MIN_SCALE, MAX_SCALE)
            val offsetX = (viewportW - contentW * scale) / 2f
            val offsetY = (viewportH - contentH * scale) / 2f
            return CanvasTransform(scale, offsetX, offsetY)
        }
    }
}
