//! Capa de *render*: traduce una [`Page`] del modelo a una **escena plana** lista
//! para pintar, de modo que la UI (Compose Canvas) sólo tenga que recorrer una
//! lista de primitivas y dibujarlas, sin conocer el modelo ni hacer conversiones
//! de unidades.
//!
//! Toda la lógica que "decide" algo vive aquí:
//!  - conversión de milímetros (tamaño de página) a píxeles lógicos,
//!  - orden de pintado por `z_index`,
//!  - normalización de formas (línea/flecha a extremos, etc.).
//!
//! Las coordenadas de los elementos ya están en píxeles lógicos `@1x` (ver
//! [`geometry`](super::geometry)), así que no se reescalan; sólo el tamaño de la
//! página (que el modelo guarda en mm) se convierte.

use serde::Serialize;

use super::element::{Element, ElementKind, ShapeKind};
use super::error::{DocResult, DocumentError};
use super::geometry::Color;
use super::model::Document;
use super::page::PageTemplate;

/// Píxeles lógicos por milímetro (96 dpi). Constante única de conversión.
pub const PX_PER_MM: f32 = 3.779_527_6;

/// Un punto de la escena, en píxeles lógicos.
#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
pub struct ScenePoint {
    pub x: f32,
    pub y: f32,
}

/// Fondo/plantilla de la página, ya en píxeles.
#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
#[serde(tag = "kind")]
pub enum SceneTemplate {
    Blank,
    Grid { spacing_px: f32 },
    Ruled { spacing_px: f32 },
    Dotted { spacing_px: f32 },
}

/// Primitiva de dibujo. La UI hace un `match` sobre `type` y llama al `draw*`
/// correspondiente del `DrawScope` de Compose.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type")]
pub enum ScenePrimitive {
    /// Trazo a mano alzada como polilínea.
    Polyline {
        points: Vec<ScenePoint>,
        color: Color,
        width: f32,
    },
    Rect {
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        stroke: Color,
        fill: Option<Color>,
        stroke_width: f32,
    },
    Ellipse {
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        stroke: Color,
        fill: Option<Color>,
        stroke_width: f32,
    },
    Line {
        x1: f32,
        y1: f32,
        x2: f32,
        y2: f32,
        color: Color,
        width: f32,
    },
    /// Igual que `Line`, pero la UI añade la punta en `(x2, y2)`.
    Arrow {
        x1: f32,
        y1: f32,
        x2: f32,
        y2: f32,
        color: Color,
        width: f32,
    },
    Text {
        x: f32,
        y: f32,
        content: String,
        font_size: f32,
        color: Color,
        bold: bool,
        italic: bool,
        underline: bool,
    },
    /// Fórmula: se entrega la fuente para pintarla y, si el AST se evalúa sin
    /// símbolos libres, su valor numérico (`value`), que la UI muestra como
    /// `fuente = valor`.
    Formula {
        x: f32,
        y: f32,
        latex: String,
        color: Color,
        value: Option<f64>,
    },
}

/// Escena completa de una página.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ScenePage {
    pub page_index: usize,
    pub page_id: String,
    pub width_px: f32,
    pub height_px: f32,
    pub background: Color,
    pub template: SceneTemplate,
    /// Primitivas ya ordenadas de atrás hacia delante (`z_index` ascendente).
    pub primitives: Vec<ScenePrimitive>,
}

fn template_to_scene(t: PageTemplate) -> SceneTemplate {
    match t {
        PageTemplate::Blank => SceneTemplate::Blank,
        PageTemplate::Grid(s) => SceneTemplate::Grid { spacing_px: s },
        PageTemplate::Ruled(s) => SceneTemplate::Ruled { spacing_px: s },
        PageTemplate::Dotted(s) => SceneTemplate::Dotted { spacing_px: s },
    }
}

fn element_to_primitive(el: &Element) -> ScenePrimitive {
    match &el.kind {
        ElementKind::Stroke(s) => ScenePrimitive::Polyline {
            points: s
                .points
                .iter()
                .map(|p| ScenePoint {
                    x: p.position.x,
                    y: p.position.y,
                })
                .collect(),
            color: s.color,
            width: s.width,
        },
        ElementKind::Text(t) => ScenePrimitive::Text {
            x: t.position.x,
            y: t.position.y,
            content: t.content.clone(),
            font_size: t.style.font_size,
            color: t.style.color,
            bold: t.style.bold,
            italic: t.style.italic,
            underline: t.style.underline,
        },
        ElementKind::Formula(f) => ScenePrimitive::Formula {
            x: f.position.x,
            y: f.position.y,
            latex: f.latex.clone(),
            color: Color::BLACK,
            // El AST se evalúa sin variables: sólo hay valor si la expresión es
            // cerrada (constantes y operaciones, sin símbolos libres).
            value: f
                .ast
                .as_ref()
                .and_then(|ast| super::math::evaluate(ast, &std::collections::HashMap::new()).ok()),
        },
        ElementKind::Shape(sh) => {
            let b = sh.bounds;
            match sh.kind {
                ShapeKind::Rectangle => ScenePrimitive::Rect {
                    x: b.x,
                    y: b.y,
                    width: b.width,
                    height: b.height,
                    stroke: sh.stroke_color,
                    fill: sh.fill_color,
                    stroke_width: sh.stroke_width,
                },
                ShapeKind::Ellipse => ScenePrimitive::Ellipse {
                    x: b.x,
                    y: b.y,
                    width: b.width,
                    height: b.height,
                    stroke: sh.stroke_color,
                    fill: sh.fill_color,
                    stroke_width: sh.stroke_width,
                },
                ShapeKind::Line => ScenePrimitive::Line {
                    x1: b.x,
                    y1: b.y,
                    x2: b.x + b.width,
                    y2: b.y + b.height,
                    color: sh.stroke_color,
                    width: sh.stroke_width,
                },
                ShapeKind::Arrow => ScenePrimitive::Arrow {
                    x1: b.x,
                    y1: b.y,
                    x2: b.x + b.width,
                    y2: b.y + b.height,
                    color: sh.stroke_color,
                    width: sh.stroke_width,
                },
            }
        }
    }
}

/// Construye la [`ScenePage`] de la página `page_index` del documento.
pub fn build_scene(document_json: &str, page_index: usize) -> DocResult<ScenePage> {
    let doc = Document::from_json(document_json)?;
    let page = doc
        .pages
        .get(page_index)
        .ok_or(DocumentError::IndexOutOfBounds {
            index: page_index,
            len: doc.pages.len(),
        })?;

    let (w_mm, h_mm) = page.size.dimensions_mm();

    let mut ordered: Vec<&Element> = page.elements.iter().collect();
    ordered.sort_by_key(|e| e.z_index);
    let primitives = ordered.iter().map(|e| element_to_primitive(e)).collect();

    Ok(ScenePage {
        page_index,
        page_id: page.id.to_string(),
        width_px: w_mm * PX_PER_MM,
        height_px: h_mm * PX_PER_MM,
        background: Color {
            r: 255,
            g: 255,
            b: 255,
            a: 255,
        },
        template: template_to_scene(page.template),
        primitives,
    })
}

/// Igual que [`build_scene`], devolviendo JSON (para el FFI).
pub fn render_page(document_json: &str, page_index: usize) -> DocResult<String> {
    let scene = build_scene(document_json, page_index)?;
    serde_json::to_string(&scene).map_err(|e| DocumentError::Serialization(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::document::api;

    fn doc_with_grid_page() -> (String, String) {
        let doc = api::create_document("render").unwrap();
        let doc = api::add_page(&doc, r#"{"template":{"kind":"Grid","spacing":24.0}}"#).unwrap();
        let page_id = Document::from_json(&doc).unwrap().pages[0].id.to_string();
        (doc, page_id)
    }

    #[test]
    fn scene_has_page_size_in_pixels_and_template() {
        let (doc, _) = doc_with_grid_page();
        let scene = build_scene(&doc, 0).unwrap();
        // A4: 210 x 297 mm.
        assert!((scene.width_px - 210.0 * PX_PER_MM).abs() < 0.01);
        assert!((scene.height_px - 297.0 * PX_PER_MM).abs() < 0.01);
        assert_eq!(scene.template, SceneTemplate::Grid { spacing_px: 24.0 });
    }

    #[test]
    fn missing_page_reports_out_of_bounds() {
        let (doc, _) = doc_with_grid_page();
        assert!(matches!(
            build_scene(&doc, 5),
            Err(DocumentError::IndexOutOfBounds { index: 5, len: 1 })
        ));
    }

    #[test]
    fn shapes_and_strokes_become_primitives_in_z_order() {
        let (doc, page_id) = doc_with_grid_page();
        let rect = r#"{"type":"Shape","kind":"Rectangle",
            "bounds":{"x":10.0,"y":20.0,"width":30.0,"height":40.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#;
        let stroke = r#"{"type":"Stroke","points":[
            {"position":{"x":0.0,"y":0.0},"pressure":0.5,"timestamp_ms":0},
            {"position":{"x":5.0,"y":9.0},"pressure":0.7,"timestamp_ms":8}],
            "color":{"r":10,"g":20,"b":30,"a":255},"width":1.5}"#;
        let doc = api::add_element(&doc, &page_id, rect).unwrap();
        let doc = api::add_element(&doc, &page_id, stroke).unwrap();

        let scene = build_scene(&doc, 0).unwrap();
        assert_eq!(scene.primitives.len(), 2);
        match &scene.primitives[0] {
            ScenePrimitive::Rect { x, y, width, height, stroke_width, .. } => {
                assert_eq!((*x, *y, *width, *height, *stroke_width), (10.0, 20.0, 30.0, 40.0, 2.0));
            }
            other => panic!("esperaba Rect, no {other:?}"),
        }
        match &scene.primitives[1] {
            ScenePrimitive::Polyline { points, width, .. } => {
                assert_eq!(points.len(), 2);
                assert_eq!(*width, 1.5);
                assert_eq!(points[1], ScenePoint { x: 5.0, y: 9.0 });
            }
            other => panic!("esperaba Polyline, no {other:?}"),
        }
    }

    #[test]
    fn line_shape_expands_to_endpoints() {
        let (doc, page_id) = doc_with_grid_page();
        let line = r#"{"type":"Shape","kind":"Line",
            "bounds":{"x":3.0,"y":4.0,"width":10.0,"height":20.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":1.0}"#;
        let doc = api::add_element(&doc, &page_id, line).unwrap();
        let scene = build_scene(&doc, 0).unwrap();
        match scene.primitives[0] {
            ScenePrimitive::Line { x1, y1, x2, y2, .. } => {
                assert_eq!((x1, y1, x2, y2), (3.0, 4.0, 13.0, 24.0));
            }
            ref other => panic!("esperaba Line, no {other:?}"),
        }
    }

    #[test]
    fn formula_primitive_carries_source_and_closed_form_value() {
        let (doc, page_id) = doc_with_grid_page();
        let spec = r#"{"expression":"2 * (3 + 4)","position":{"x":5.0,"y":6.0}}"#;
        let doc = api::add_formula(&doc, &page_id, spec).unwrap();
        let scene = build_scene(&doc, 0).unwrap();
        match &scene.primitives[0] {
            ScenePrimitive::Formula { x, y, latex, value, .. } => {
                assert_eq!((*x, *y), (5.0, 6.0));
                assert_eq!(latex, "2 * (3 + 4)");
                assert_eq!(*value, Some(14.0));
            }
            other => panic!("esperaba Formula, no {other:?}"),
        }
    }

    #[test]
    fn formula_with_free_symbol_has_no_value() {
        let (doc, page_id) = doc_with_grid_page();
        let spec = r#"{"expression":"x + 1","position":{"x":0.0,"y":0.0}}"#;
        let doc = api::add_formula(&doc, &page_id, spec).unwrap();
        let scene = build_scene(&doc, 0).unwrap();
        match &scene.primitives[0] {
            ScenePrimitive::Formula { value, .. } => assert_eq!(*value, None),
            other => panic!("esperaba Formula, no {other:?}"),
        }
    }

    #[test]
    fn render_page_emits_valid_json() {
        let (doc, page_id) = doc_with_grid_page();
        let text = r#"{"type":"Text","content":"hola","position":{"x":1.0,"y":2.0},
            "style":{"font_size":18.0,"bold":true,"italic":false,"underline":false,
            "color":{"r":0,"g":0,"b":0,"a":255}},"max_width":null}"#;
        let doc = api::add_element(&doc, &page_id, text).unwrap();
        let json = render_page(&doc, 0).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v["primitives"][0]["type"], "Text");
        assert_eq!(v["primitives"][0]["content"], "hola");
        assert_eq!(v["primitives"][0]["bold"], true);
        assert_eq!(v["template"]["kind"], "Grid");
    }
}
