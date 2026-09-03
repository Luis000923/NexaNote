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

use super::element::{Element, ElementKind, Graph, ShapeKind};
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
    /// Gráfica de una función: marco, cuadrícula, ejes cartesianos y la curva ya
    /// muestreada, **todo en píxeles de página**. La UI sólo traza líneas y
    /// polilíneas; el muestreo numérico ya lo hizo el núcleo.
    Graph {
        /// Marco de la gráfica (esquina superior-izquierda + tamaño).
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        /// Fuente de la función, para rotularla.
        expression: String,
        color: Color,
        /// Líneas verticales de la cuadrícula (x de página).
        grid_x: Vec<f32>,
        /// Líneas horizontales de la cuadrícula (y de página).
        grid_y: Vec<f32>,
        /// `x` de página del eje Y (`var = 0`), si cae dentro del marco.
        axis_x: Option<f32>,
        /// `y` de página del eje X (`f = 0`), si cae dentro del marco.
        axis_y: Option<f32>,
        /// Tramos continuos de la curva; se rompe en cada hueco de la función.
        polylines: Vec<Vec<ScenePoint>>,
    },
    /// Imagen rasterizada: marco de destino en píxeles de página y ruta relativa
    /// del recurso local. La UI decodifica el bitmap fuera del hilo de dibujo.
    Image {
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        /// Ruta relativa al almacén de activos de la app.
        source: String,
        natural_width: f32,
        natural_height: f32,
    },
}

/// Caja de impacto de un elemento: lo que la UI necesita para resolver un toque
/// o una selección por área **sin conocer el modelo**. Va en el mismo orden que
/// `primitives`, de atrás hacia delante.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct SceneHit {
    /// Id del elemento (hex), tal y como lo esperan las operaciones de la `api`.
    pub id: String,
    /// Nombre estable del tipo (`Stroke`, `Text`, ...), para rotular la acción.
    pub kind: String,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    /// `true` si el elemento admite relleno (formas cerradas).
    pub fillable: bool,
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
    /// `true` si la página es un **lienzo infinito**: la UI no dibuja hoja ni
    /// borde, y `width_px`/`height_px` son sólo la extensión ocupada.
    pub infinite: bool,
    /// Primitivas ya ordenadas de atrás hacia delante (`z_index` ascendente).
    pub primitives: Vec<ScenePrimitive>,
    /// Cajas de impacto, en el mismo orden que `primitives`.
    pub hits: Vec<SceneHit>,
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
        ElementKind::Graph(g) => graph_to_primitive(g),
        ElementKind::Image(im) => ScenePrimitive::Image {
            x: im.frame.x,
            y: im.frame.y,
            width: im.frame.width,
            height: im.frame.height,
            source: im.source.clone(),
            natural_width: im.natural_width,
            natural_height: im.natural_height,
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

/// Margen libre (px) que el lienzo infinito deja más allá de su contenido.
const INFINITE_MARGIN_PX: f32 = 600.0;

/// Caja de impacto de un elemento, calculada por el modelo ([`Element::bounds`]).
fn element_to_hit(el: &Element) -> SceneHit {
    let b = el.bounds();
    SceneHit {
        id: el.id.to_string(),
        kind: el.kind_name().to_string(),
        x: b.x,
        y: b.y,
        width: b.width,
        height: b.height,
        fillable: matches!(&el.kind, ElementKind::Shape(sh) if sh.kind.is_closed()),
    }
}

/// Divisor "bonito" (1, 2, 5 x 10^k) más cercano a `span / target`.
fn nice_step(span: f64, target: f64) -> f64 {
    if !span.is_finite() || span <= 0.0 || target <= 0.0 {
        return 1.0;
    }
    let raw = span / target;
    let mag = 10f64.powf(raw.log10().floor());
    let norm = raw / mag;
    let factor = if norm < 1.5 {
        1.0
    } else if norm < 3.0 {
        2.0
    } else if norm < 7.0 {
        5.0
    } else {
        10.0
    };
    factor * mag
}

/// Rango vertical robusto de la curva: percentiles 5-95 de las ordenadas finitas,
/// forzando a incluir el eje `f = 0` y con un pequeño margen. Evita que una
/// asíntota (p. ej. `tan`) reviente la escala. `(-1, 1)` si no hay datos.
fn curve_y_range(samples: &[super::math::CurveSample]) -> (f64, f64) {
    let mut ys: Vec<f64> = samples.iter().filter_map(|s| s.y).collect();
    if ys.is_empty() {
        return (-1.0, 1.0);
    }
    ys.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let at = |q: f64| ys[(((ys.len() - 1) as f64) * q).round() as usize];
    let mut lo = at(0.05).min(0.0);
    let mut hi = at(0.95).max(0.0);
    if (hi - lo).abs() < 1e-9 {
        lo -= 1.0;
        hi += 1.0;
    }
    let pad = (hi - lo) * 0.08;
    (lo - pad, hi + pad)
}

/// Posiciones de cuadrícula (en píxeles de página) para un eje: los múltiplos de
/// `step` dentro de `[min, max]`, mapeados con `to_px`. Acotado a 256 líneas.
fn grid_lines(min: f64, max: f64, step: f64, to_px: impl Fn(f64) -> f32) -> Vec<f32> {
    let mut out = Vec::new();
    if step <= 0.0 || !step.is_finite() {
        return out;
    }
    let first = (min / step).ceil() as i64;
    let last = (max / step).floor() as i64;
    if last < first || last - first > 256 {
        return out;
    }
    for k in first..=last {
        out.push(to_px(k as f64 * step));
    }
    out
}

/// Traduce un [`Graph`] a su primitiva de escena: muestrea la curva con el motor
/// matemático y proyecta dominio/rango al marco en píxeles de página.
fn graph_to_primitive(g: &Graph) -> ScenePrimitive {
    let frame = g.frame;
    let color = Color::rgb(21, 101, 192);

    let samples = g
        .ast
        .as_ref()
        .and_then(|ast| {
            super::math::sample_function(ast, &g.var, g.x_min, g.x_max, g.samples).ok()
        })
        .unwrap_or_default();

    let (y_min, y_max) = curve_y_range(&samples);
    let x_span = (g.x_max - g.x_min).max(f64::MIN_POSITIVE);
    let y_span = (y_max - y_min).max(f64::MIN_POSITIVE);

    let to_px_x = |x: f64| frame.x + ((x - g.x_min) / x_span) as f32 * frame.width;
    // El eje vertical de la pantalla crece hacia abajo: se invierte.
    let to_px_y = |y: f64| frame.y + ((y_max - y) / y_span) as f32 * frame.height;

    let x_step = nice_step(x_span, 8.0);
    let y_step = nice_step(y_span, 6.0);
    let grid_x = grid_lines(g.x_min, g.x_max, x_step, to_px_x);
    let grid_y = grid_lines(y_min, y_max, y_step, to_px_y);

    let axis_x = (g.x_min <= 0.0 && g.x_max >= 0.0).then(|| to_px_x(0.0));
    let axis_y = (y_min <= 0.0 && y_max >= 0.0).then(|| to_px_y(0.0));

    // Segmentos continuos: se corta en cada hueco y al salir del rango vertical.
    let mut polylines: Vec<Vec<ScenePoint>> = Vec::new();
    let mut current: Vec<ScenePoint> = Vec::new();
    for s in &samples {
        match s.y {
            Some(y) if y >= y_min && y <= y_max => current.push(ScenePoint {
                x: to_px_x(s.x),
                y: to_px_y(y),
            }),
            _ => {
                if current.len() >= 2 {
                    polylines.push(std::mem::take(&mut current));
                } else {
                    current.clear();
                }
            }
        }
    }
    if current.len() >= 2 {
        polylines.push(current);
    }

    ScenePrimitive::Graph {
        x: frame.x,
        y: frame.y,
        width: frame.width,
        height: frame.height,
        expression: g.expression.clone(),
        color,
        grid_x,
        grid_y,
        axis_x,
        axis_y,
        polylines,
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
    let (mut width_px, mut height_px) = (w_mm * PX_PER_MM, h_mm * PX_PER_MM);

    // Un lienzo infinito no tiene hoja: su extensión crece con el contenido, con
    // un margen para que siempre quede sitio libre por delante del trazo.
    if page.size.is_infinite() {
        if let Some(content) = page.content_bounds() {
            width_px = width_px.max(content.right() + INFINITE_MARGIN_PX);
            height_px = height_px.max(content.bottom() + INFINITE_MARGIN_PX);
        }
    }

    let mut ordered: Vec<&Element> = page.elements.iter().collect();
    ordered.sort_by_key(|e| e.z_index);
    let primitives = ordered.iter().map(|e| element_to_primitive(e)).collect();
    let hits = ordered.iter().map(|e| element_to_hit(e)).collect();

    Ok(ScenePage {
        page_index,
        page_id: page.id.to_string(),
        width_px,
        height_px,
        background: page.background(),
        template: template_to_scene(page.template),
        infinite: page.size.is_infinite(),
        primitives,
        hits,
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
    fn graph_primitive_samples_curve_and_places_axes() {
        let (doc, page_id) = doc_with_grid_page();
        let spec = r#"{"expression":"x^2","position":{"x":0.0,"y":0.0},
            "width":200.0,"height":200.0,"x_min":-2.0,"x_max":2.0,"samples":33}"#;
        let doc = api::add_graph(&doc, &page_id, spec).unwrap();
        let scene = build_scene(&doc, 0).unwrap();
        match &scene.primitives[0] {
            ScenePrimitive::Graph {
                width,
                height,
                axis_x,
                polylines,
                expression,
                ..
            } => {
                assert_eq!((*width, *height), (200.0, 200.0));
                assert_eq!(expression, "x^2");
                // El eje Y (x = 0) está en el centro del marco de 200 px.
                assert!((axis_x.unwrap() - 100.0).abs() < 1.0);
                assert_eq!(polylines.len(), 1);
                assert!(polylines[0].len() > 20);
            }
            other => panic!("esperaba Graph, no {other:?}"),
        }
    }

    #[test]
    fn graph_primitive_breaks_curve_at_discontinuity() {
        let (doc, page_id) = doc_with_grid_page();
        let spec = r#"{"expression":"1 / x","position":{"x":0.0,"y":0.0},
            "width":100.0,"height":100.0,"x_min":-3.0,"x_max":3.0,"samples":61}"#;
        let doc = api::add_graph(&doc, &page_id, spec).unwrap();
        let scene = build_scene(&doc, 0).unwrap();
        match &scene.primitives[0] {
            ScenePrimitive::Graph { polylines, .. } => {
                assert!(polylines.len() >= 2, "la curva debe partirse en x = 0");
            }
            other => panic!("esperaba Graph, no {other:?}"),
        }
    }

    #[test]
    fn image_becomes_primitive_with_frame_in_pixels() {
        let (doc, page_id) = doc_with_grid_page();
        let spec = r#"{"source":"images/pic.png","position":{"x":12.0,"y":34.0},
            "width":180.0,"height":120.0,"natural_width":900.0,"natural_height":600.0}"#;
        let doc = api::add_image(&doc, &page_id, spec).unwrap();
        let scene = build_scene(&doc, 0).unwrap();
        match &scene.primitives[0] {
            ScenePrimitive::Image { x, y, width, height, source, natural_width, .. } => {
                assert_eq!((*x, *y, *width, *height), (12.0, 34.0, 180.0, 120.0));
                assert_eq!(source, "images/pic.png");
                assert_eq!(*natural_width, 900.0);
            }
            other => panic!("esperaba Image, no {other:?}"),
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
