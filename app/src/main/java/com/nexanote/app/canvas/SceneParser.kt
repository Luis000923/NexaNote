package com.nexanote.app.canvas

import androidx.compose.ui.geometry.Offset
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
        return ScenePage(
            pageIndex = root.getInt("page_index"),
            pageId = root.getString("page_id"),
            widthPx = root.getDouble("width_px").toFloat(),
            heightPx = root.getDouble("height_px").toFloat(),
            background = color(root.getJSONObject("background")),
            template = parseTemplate(root.getJSONObject("template")),
            primitives = primitives,
        )
    }

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
