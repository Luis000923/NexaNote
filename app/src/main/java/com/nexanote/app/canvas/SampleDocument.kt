package com.nexanote.app.canvas

import com.nexanote.core.NativeCore
import org.json.JSONObject

/**
 * Construye un documento de ejemplo **en el núcleo Rust** y devuelve la escena de
 * su primera página. Toda la creación de estructura se hace vía el puente; aquí
 * sólo se arman las cadenas JSON de entrada.
 */
object SampleDocument {

    private const val PAGE_SPEC = """{"size":{"format":"A4"},"template":{"kind":"Grid","spacing":26.0}}"""

    /** Elementos de ejemplo (un `ElementKind` etiquetado por entrada). */
    private val ELEMENTS: List<String> = listOf(
        // Marco rectangular.
        """{"type":"Shape","kind":"Rectangle",
            "bounds":{"x":60.0,"y":70.0,"width":460.0,"height":300.0},
            "stroke_color":{"r":33,"g":33,"b":33,"a":255},
            "fill_color":{"r":245,"g":247,"b":250,"a":255},"stroke_width":2.0}""",
        // Elipse.
        """{"type":"Shape","kind":"Ellipse",
            "bounds":{"x":110.0,"y":120.0,"width":180.0,"height":120.0},
            "stroke_color":{"r":21,"g":101,"b":192,"a":255},
            "fill_color":null,"stroke_width":3.0}""",
        // Flecha diagonal.
        """{"type":"Shape","kind":"Arrow",
            "bounds":{"x":320.0,"y":140.0,"width":150.0,"height":90.0},
            "stroke_color":{"r":198,"g":40,"b":40,"a":255},
            "fill_color":null,"stroke_width":3.0}""",
        // Línea horizontal.
        """{"type":"Shape","kind":"Line",
            "bounds":{"x":90.0,"y":300.0,"width":400.0,"height":0.0},
            "stroke_color":{"r":120,"g":120,"b":120,"a":255},
            "fill_color":null,"stroke_width":1.5}""",
        // Trazo a mano alzada (onda).
        """{"type":"Stroke","points":[
            {"position":{"x":90.0,"y":340.0},"pressure":0.3,"timestamp_ms":0},
            {"position":{"x":150.0,"y":320.0},"pressure":0.6,"timestamp_ms":16},
            {"position":{"x":210.0,"y":352.0},"pressure":0.8,"timestamp_ms":32},
            {"position":{"x":280.0,"y":320.0},"pressure":0.7,"timestamp_ms":48},
            {"position":{"x":350.0,"y":348.0},"pressure":0.5,"timestamp_ms":64},
            {"position":{"x":430.0,"y":328.0},"pressure":0.4,"timestamp_ms":80}],
            "color":{"r":46,"g":125,"b":50,"a":255},"width":3.0}""",
        // Texto.
        """{"type":"Text","content":"NexaNote - Fase 3","position":{"x":80.0,"y":95.0},
            "style":{"font_size":22.0,"bold":true,"italic":false,"underline":false,
            "color":{"r":33,"g":33,"b":33,"a":255}},"max_width":null}""",
    )

    /** Fórmulas de ejemplo `(expresión, x, y)`, insertadas vía el motor matemático. */
    private val FORMULAS: List<Triple<String, Float, Float>> = listOf(
        Triple("\\frac{-b + \\sqrt{b^2 - 4 a c}}{2 a}", 150f, 175f),
        Triple("\\sum_{i=1}^{10} i", 150f, 210f),
    )

    /** Gráficas de ejemplo `(expresión, xMin, xMax, x, y)`, muestreadas por el núcleo. */
    private val GRAPHS: List<GraphSpecSample> = listOf(
        GraphSpecSample("sin(x)", -6.2832, 6.2832, 60f, 400f),
        GraphSpecSample("x^2 / 4 - 2", -6.0, 6.0, 300f, 400f),
    )

    private data class GraphSpecSample(
        val expression: String,
        val xMin: Double,
        val xMax: Double,
        val x: Float,
        val y: Float,
    )

    /** Documento de ejemplo ya construido en el núcleo: su JSON y el id de su página. */
    data class LoadedDocument(val documentJson: String, val pageId: String)

    /**
     * Construye el documento de ejemplo **en el núcleo Rust** y devuelve su estado
     * (JSON + id de página), para que la capa de UI pueda seguir editándolo
     * (p. ej. añadir trazos del stylus).
     */
    fun buildDocument(core: NativeCore, title: String = "Documento de ejemplo"): LoadedDocument {
        var doc = core.documentCreate(title)
        doc = core.documentAddPage(doc, PAGE_SPEC)
        val pageId = JSONObject(doc).getJSONArray("pages").getJSONObject(0).getString("id")
        for (element in ELEMENTS) {
            doc = core.documentAddElement(doc, pageId, element)
        }
        for ((expression, x, y) in FORMULAS) {
            doc = core.documentAddFormula(doc, pageId, FormulaInput.toFormulaJson(expression, x, y))
        }
        for (g in GRAPHS) {
            doc = core.documentAddGraph(
                doc,
                pageId,
                GraphInput.toGraphJson(g.expression, g.xMin, g.xMax, g.x, g.y, width = 220f, height = 170f),
            )
        }
        return LoadedDocument(doc, pageId)
    }

    /** Id de la primera página de un documento serializado. */
    fun firstPageId(documentJson: String): String =
        JSONObject(documentJson).getJSONArray("pages").getJSONObject(0).getString("id")

    fun buildScene(core: NativeCore, title: String = "Documento de ejemplo"): ScenePage {
        val loaded = buildDocument(core, title)
        return SceneParser.parse(core.documentRenderPage(loaded.documentJson, 0))
    }
}
