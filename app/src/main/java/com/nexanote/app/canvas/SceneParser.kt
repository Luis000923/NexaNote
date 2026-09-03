package com.nexanote.app.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * Traduce el JSON `ScenePage` del núcleo Rust a [ScenePage]. Es puro *mapeo* de
 * datos: sin lógica de dominio ni de dibujo.
 */
object SceneParser {

    fun parse(json: String): ScenePage {
        val root = JSONObject(json)
        val primitives = root.getJSONArray("primitives").let { arr ->
            buildList(arr.length()) {
                for (i in 0 until arr.length()) add(parsePrimitive(arr.getJSONObject(i)))
            }
        }
        val hits = root.optJSONArray("hits").let { arr ->
            if (arr == null) emptyList() else buildList(arr.length()) {
                for (i in 0 until arr.length()) add(parseHit(arr.getJSONObject(i)))
            }
        }
        return ScenePage(
            pageIndex = root.getInt("page_index"),
            pageId = root.getString("page_id"),
            widthPx = root.getDouble("width_px").toFloat(),
            heightPx = root.getDouble("height_px").toFloat(),
            background = color(root.getJSONObject("background")),
            template = parseTemplate(root.getJSONObject("template")),
            infinite = root.optBoolean("infinite", false),
            primitives = primitives,
            hits = hits,
        )
    }

    private fun parseHit(o: JSONObject): SceneHit = SceneHit(
        id = o.getString("id"),
        kind = o.getString("kind"),
        bounds = Rect(
            offset = Offset(o.f("x"), o.f("y")),
            size = Size(o.f("width"), o.f("height")),
        ),
        fillable = o.optBoolean("fillable", false),
    )

    private fun parseTemplate(o: JSONObject): SceneTemplate {
        val spacing = { o.getDouble("spacing_px").toFloat() }
        return when (val kind = o.getString("kind")) {
            "Blank" -> SceneTemplate.Blank
            "Grid" -> SceneTemplate.Grid(spacing())
            "Ruled" -> SceneTemplate.Ruled(spacing())
            "Dotted" -> SceneTemplate.Dotted(spacing())
            else -> throw IllegalArgumentException("plantilla desconocida: $kind")
        }
    }

    private fun parsePrimitive(o: JSONObject): ScenePrimitive = when (val type = o.getString("type")) {
        "Polyline" -> ScenePrimitive.Polyline(
            points = o.getJSONArray("points").let { pts ->
                buildList(pts.length()) {
                    for (i in 0 until pts.length()) {
                        val p = pts.getJSONObject(i)
                        add(Offset(p.getDouble("x").toFloat(), p.getDouble("y").toFloat()))
                    }
                }
            },
            color = color(o.getJSONObject("color")),
            width = o.getDouble("width").toFloat(),
        )

        "Rect" -> ScenePrimitive.Rect(
            topLeft = Offset(o.f("x"), o.f("y")),
            size = Size(o.f("width"), o.f("height")),
            stroke = color(o.getJSONObject("stroke")),
            fill = o.optJSONObject("fill")?.let(::color),
            strokeWidth = o.f("stroke_width"),
        )

        "Ellipse" -> ScenePrimitive.Ellipse(
            topLeft = Offset(o.f("x"), o.f("y")),
            size = Size(o.f("width"), o.f("height")),
            stroke = color(o.getJSONObject("stroke")),
            fill = o.optJSONObject("fill")?.let(::color),
            strokeWidth = o.f("stroke_width"),
        )

        "Line" -> ScenePrimitive.Line(
            start = Offset(o.f("x1"), o.f("y1")),
            end = Offset(o.f("x2"), o.f("y2")),
            color = color(o.getJSONObject("color")),
            width = o.f("width"),
        )

        "Arrow" -> ScenePrimitive.Arrow(
            start = Offset(o.f("x1"), o.f("y1")),
            end = Offset(o.f("x2"), o.f("y2")),
            color = color(o.getJSONObject("color")),
            width = o.f("width"),
        )

        "Text" -> ScenePrimitive.Text(
            origin = Offset(o.f("x"), o.f("y")),
            content = o.getString("content"),
            fontSize = o.f("font_size"),
            color = color(o.getJSONObject("color")),
            bold = o.getBoolean("bold"),
            italic = o.getBoolean("italic"),
            underline = o.getBoolean("underline"),
        )

        "Formula" -> ScenePrimitive.Formula(
            origin = Offset(o.f("x"), o.f("y")),
            latex = o.getString("latex"),
            color = color(o.getJSONObject("color")),
            value = if (o.isNull("value")) null else o.getDouble("value"),
        )

        "Graph" -> ScenePrimitive.Graph(
            topLeft = Offset(o.f("x"), o.f("y")),
            size = Size(o.f("width"), o.f("height")),
            expression = o.getString("expression"),
            color = color(o.getJSONObject("color")),
            gridX = o.getJSONArray("grid_x").let { a -> buildList(a.length()) { for (i in 0 until a.length()) add(a.getDouble(i).toFloat()) } },
            gridY = o.getJSONArray("grid_y").let { a -> buildList(a.length()) { for (i in 0 until a.length()) add(a.getDouble(i).toFloat()) } },
            axisX = if (o.isNull("axis_x")) null else o.getDouble("axis_x").toFloat(),
            axisY = if (o.isNull("axis_y")) null else o.getDouble("axis_y").toFloat(),
            polylines = o.getJSONArray("polylines").let { segs ->
                buildList(segs.length()) {
                    for (i in 0 until segs.length()) {
                        val seg = segs.getJSONArray(i)
                        add(
                            buildList(seg.length()) {
                                for (j in 0 until seg.length()) {
                                    val p = seg.getJSONObject(j)
                                    add(Offset(p.getDouble("x").toFloat(), p.getDouble("y").toFloat()))
                                }
                            },
                        )
                    }
                }
            },
        )

        "Image" -> ScenePrimitive.Image(
            topLeft = Offset(o.f("x"), o.f("y")),
            size = Size(o.f("width"), o.f("height")),
            source = o.getString("source"),
            naturalWidth = o.f("natural_width"),
            naturalHeight = o.f("natural_height"),
        )

        else -> throw IllegalArgumentException("primitiva desconocida: $type")
    }

    private fun JSONObject.f(key: String): Float = getDouble(key).toFloat()

    private fun color(o: JSONObject): Color = Color(
        red = o.getInt("r"),
        green = o.getInt("g"),
        blue = o.getInt("b"),
        alpha = o.getInt("a"),
    )
}

/**
 * Traduce las respuestas de selección del núcleo (`documentSelectInArea`,
 * `documentSelectAt`, `documentDuplicateElements`). Igual que [SceneParser], es
 * puro mapeo de datos.
 */
object SelectionParser {

    /** `{"ids":[...],"bounds":{...}|null}` → [Selection]. */
    fun parse(json: String): Selection = parse(JSONObject(json))

    /** `{"document":"<json>","selection":{...}}` → documento + selección de las copias. */
    fun parseDuplicate(json: String): Pair<String, Selection> {
        val root = JSONObject(json)
        return root.getString("document") to parse(root.getJSONObject("selection"))
    }

    private fun parse(o: JSONObject): Selection {
        val arr = o.getJSONArray("ids")
        val ids = buildList(arr.length()) {
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
        val bounds = o.optJSONObject("bounds")?.let { b ->
            Rect(
                offset = Offset(b.getDouble("x").toFloat(), b.getDouble("y").toFloat()),
                size = Size(b.getDouble("width").toFloat(), b.getDouble("height").toFloat()),
            )
        }
        return Selection(ids, bounds)
    }
}
