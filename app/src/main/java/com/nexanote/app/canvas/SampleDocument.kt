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
        // Formula (fuente LaTeX).
        """{"type":"Formula","latex":"e^{i\\pi} + 1 = 0","position":{"x":150.0,"y":175.0},"ast":null}""",
    )

    fun buildScene(core: NativeCore, title: String = "Documento de ejemplo"): ScenePage {
        var doc = core.documentCreate(title)
        doc = core.documentAddPage(doc, PAGE_SPEC)
        val pageId = JSONObject(doc).getJSONArray("pages").getJSONObject(0).getString("id")
        for (element in ELEMENTS) {
            doc = core.documentAddElement(doc, pageId, element)
        }
        return SceneParser.parse(core.documentRenderPage(doc, 0))
    }
}
