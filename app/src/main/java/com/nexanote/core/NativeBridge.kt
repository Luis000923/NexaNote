package com.nexanote.core

/**
 * Único punto de contacto entre la capa UI (Kotlin/Compose) y el núcleo NexaNote
 * escrito en Rust. Toda comunicación con el núcleo pasa por aquí.
 *
 * Reglas (ver `claude.md`):
 *  - Este objeto es el único lugar que hace [System.loadLibrary] y declara
 *    métodos `external`.
 *  - La superficie se mantiene mínima, explícita y versionable.
 *  - El resto del código depende de [NativeCore], no de este objeto directamente.
 *
 * ## Modelo de documento (Fase 2)
 *
 * El modelo vive en Rust. Las operaciones son *stateless*: el documento viaja
 * como JSON en cada llamada y la función devuelve el documento actualizado (o
 * lanza [IllegalStateException] si la operación falla, sin propagar `panic`).
 */
object NativeBridge : NativeCore {

    /** `true` si `libnexanote_core` se cargó correctamente. */
    val isLoaded: Boolean

    init {
        isLoaded = runCatching { System.loadLibrary("nexanote_core") }.isSuccess
    }

    external override fun greeting(name: String): String

    external override fun coreVersion(): String

    external override fun documentCreate(title: String): String

    external override fun documentAddPage(documentJson: String, pageSpecJson: String): String

    external override fun documentRemovePage(documentJson: String, pageId: String): String

    external override fun documentAddElement(
        documentJson: String,
        pageId: String,
        elementJson: String,
    ): String

    external override fun documentAddStroke(
        documentJson: String,
        pageId: String,
        strokeJson: String,
    ): String

    external override fun documentAddShape(
        documentJson: String,
        pageId: String,
        shapeJson: String,
    ): String

    external override fun documentAddText(
        documentJson: String,
        pageId: String,
        textJson: String,
    ): String

    external override fun documentRemoveElement(
        documentJson: String,
        pageId: String,
        elementId: String,
    ): String

    external override fun documentTranslatePageElements(
        documentJson: String,
        pageId: String,
        dx: Float,
        dy: Float,
    ): String

    external override fun documentSummary(documentJson: String): String

    external override fun documentRenderPage(documentJson: String, pageIndex: Int): String
}

/** Contrato del núcleo. La UI depende de esta interfaz, no de la implementación JNI. */
interface NativeCore {
    fun greeting(name: String): String
    fun coreVersion(): String

    /** Crea un documento vacío; devuelve su JSON. */
    fun documentCreate(title: String): String

    /**
     * Añade una página. [pageSpecJson] admite `{}` (A4 en blanco) o, por ejemplo,
     * `{"size":{"format":"A5"},"template":{"kind":"Grid","spacing":24.0}}`.
     * Devuelve el documento actualizado.
     */
    fun documentAddPage(documentJson: String, pageSpecJson: String): String

    /** Elimina la página [pageId]. Devuelve el documento actualizado. */
    fun documentRemovePage(documentJson: String, pageId: String): String

    /**
     * Inserta un elemento en la página [pageId]. [elementJson] es un `ElementKind`
     * etiquetado, p. ej. `{"type":"Text","content":"hola", ...}`.
     * Devuelve el documento actualizado.
     */
    fun documentAddElement(documentJson: String, pageId: String, elementJson: String): String

    /**
     * Inserta un **trazo a mano alzada** (capturado por el stylus) en la página
     * [pageId]. [strokeJson] es un `Stroke` serializado
     * (`{"points":[{"position":{"x","y"},"pressure","timestamp_ms"}],"color":{...},"width"}`).
     * El núcleo Rust sanea puntos, presión y grosor. Devuelve el documento
     * actualizado.
     */
    fun documentAddStroke(documentJson: String, pageId: String, strokeJson: String): String

    /**
     * Inserta una **forma geométrica** en la página [pageId]. [shapeJson] es un
     * `Shape` serializado
     * (`{"kind":"Rectangle","bounds":{"x","y","width","height"},"stroke_color":{...},"fill_color":null,"stroke_width"}`),
     * con `kind` en `{Rectangle, Ellipse, Line, Arrow}`. El núcleo Rust normaliza
     * los límites (esquina + tamaño positivo para rect/elipse; vector con signo
     * para línea/flecha) y rechaza geometrías degeneradas. Devuelve el documento
     * actualizado.
     */
    fun documentAddShape(documentJson: String, pageId: String, shapeJson: String): String

    /**
     * Inserta un **bloque de texto** tipográfico en la página [pageId]. [textJson]
     * es un `TextBox` serializado
     * (`{"content","position":{"x","y"},"style":{"font_size","bold","italic","underline","color":{...}},"max_width":null}`).
     * El núcleo Rust recorta el contenido, lo rechaza si queda vacío, satura el
     * tamaño de fuente y valida la posición. Devuelve el documento actualizado.
     */
    fun documentAddText(documentJson: String, pageId: String, textJson: String): String

    /** Elimina el elemento [elementId] de la página [pageId]. */
    fun documentRemoveElement(documentJson: String, pageId: String, elementId: String): String

    /** Traslada todos los elementos de la página [pageId] por `(dx, dy)`. */
    fun documentTranslatePageElements(
        documentJson: String,
        pageId: String,
        dx: Float,
        dy: Float,
    ): String

    /**
     * Resumen compacto del documento:
     * `{"id","title","schema_version","page_count","element_count"}`.
     */
    fun documentSummary(documentJson: String): String

    /**
     * Devuelve la *escena plana* (`ScenePage` en JSON) de la página `pageIndex`:
     * tamaño en píxeles, plantilla de fondo y lista de primitivas de dibujo ya
     * ordenadas. La UI la pinta tal cual; no interpreta el modelo.
     */
    fun documentRenderPage(documentJson: String, pageIndex: Int): String
}
