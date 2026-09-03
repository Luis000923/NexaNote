//! API basada en cadenas JSON, pensada para cruzar la frontera FFI.
//!
//! Cada función recibe y devuelve `String` JSON y nunca hace `panic`: todo error
//! se devuelve como [`DocumentError`] (que el adaptador JNI traduce a excepción
//! Java). Es *stateless*: el documento vive en el lado de Kotlin y viaja como
//! JSON en cada llamada, lo que hace la capa trivialmente testeable.

use serde::{Deserialize, Serialize};

use super::element::{ElementKind, Shape, ShapeKind, Stroke};
use super::error::{DocResult, DocumentError};
use super::model::Document;
use super::page::{PageSize, PageTemplate};

/// Especificación para crear una página (campos opcionales con valor por defecto).
#[derive(Debug, Clone, Deserialize)]
#[serde(default, deny_unknown_fields)]
struct PageSpec {
    size: PageSize,
    template: PageTemplate,
}

impl Default for PageSpec {
    fn default() -> Self {
        PageSpec {
            size: PageSize::default(),
            template: PageTemplate::default(),
        }
    }
}

/// Resumen compacto de un documento, para aserciones rápidas desde Kotlin.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct DocumentSummary {
    pub id: String,
    pub title: String,
    pub schema_version: u32,
    pub page_count: usize,
    pub element_count: usize,
}

fn parse<T: for<'de> Deserialize<'de>>(json: &str, what: &str) -> DocResult<T> {
    serde_json::from_str(json)
        .map_err(|e| DocumentError::Serialization(format!("{what}: {e}")))
}

fn dump<T: Serialize>(value: &T) -> DocResult<String> {
    serde_json::to_string(value).map_err(|e| DocumentError::Serialization(e.to_string()))
}

/// Crea un documento vacío y lo devuelve como JSON.
pub fn create_document(title: &str) -> DocResult<String> {
    Document::new(title).to_json()
}

/// Añade una página al documento `document_json` según `page_spec_json`
/// (`{}` = A4 en blanco). Devuelve el documento actualizado.
pub fn add_page(document_json: &str, page_spec_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let spec: PageSpec = parse(page_spec_json, "page_spec")?;
    doc.add_page(spec.size, spec.template)?;
    doc.to_json()
}

/// Elimina la página `page_id` del documento. Devuelve el documento actualizado.
pub fn remove_page(document_json: &str, page_id: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    doc.remove_page(id)?;
    doc.to_json()
}

/// Inserta el elemento `element_json` en la página `page_id`. Devuelve el
/// documento actualizado.
pub fn add_element(document_json: &str, page_id: &str, element_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    let kind: ElementKind = parse(element_json, "element")?;
    doc.page_mut(id)?.add_element(kind);
    doc.to_json()
}

/// Grosor mínimo (unidades lógicas) para que un trazo sea visible al pintarse.
const MIN_STROKE_WIDTH: f32 = 0.5;

/// Inserta un **trazo a mano alzada** en la página `page_id`.
///
/// `stroke_json` es un [`Stroke`] serializado (`points`, `color`, `width`), tal
/// como lo captura la capa de stylus. Antes de insertarlo, el núcleo lo **sanea**
/// -- es la única autoridad sobre la validez del trazo, de modo que la UI se
/// limita a capturar puntos en alta frecuencia sin decidir nada:
///
///  - descarta muestras con coordenadas no finitas;
///  - exige al menos un punto válido (si no, [`DocumentError::InvalidArgument`]);
///  - satura la presión de cada muestra a `[0.0, 1.0]` (`0.5` si no es finita);
///  - eleva el grosor a [`MIN_STROKE_WIDTH`] si viene por debajo o no es finito.
///
/// Devuelve el documento actualizado.
pub fn add_stroke(document_json: &str, page_id: &str, stroke_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    let raw: Stroke = parse(stroke_json, "stroke")?;
    let stroke = sanitize_stroke(raw)?;
    doc.page_mut(id)?.add_element(ElementKind::Stroke(stroke));
    doc.to_json()
}

/// Aplica las reglas de validez de un trazo capturado. Ver [`add_stroke`].
fn sanitize_stroke(mut stroke: Stroke) -> DocResult<Stroke> {
    stroke
        .points
        .retain(|p| p.position.x.is_finite() && p.position.y.is_finite());
    if stroke.points.is_empty() {
        return Err(DocumentError::InvalidArgument(
            "un trazo necesita al menos un punto válido".to_string(),
        ));
    }
    for p in &mut stroke.points {
        p.pressure = if p.pressure.is_finite() {
            p.pressure.clamp(0.0, 1.0)
        } else {
            0.5
        };
    }
    if !stroke.width.is_finite() || stroke.width < MIN_STROKE_WIDTH {
        stroke.width = MIN_STROKE_WIDTH;
    }
    Ok(stroke)
}

/// Extensión mínima (unidades lógicas) para que una forma no sea degenerada. Es
/// un suelo defensivo: la UI aplica su propio umbral de "arrastre intencionado".
const MIN_SHAPE_EXTENT: f32 = 1.0;

/// Inserta una **forma geométrica** (`Rectangle`, `Ellipse`, `Line`, `Arrow`) en
/// la página `page_id`.
///
/// `shape_json` es un [`Shape`] serializado
/// (`{"kind","bounds":{"x","y","width","height"},"stroke_color","fill_color","stroke_width"}`).
/// El núcleo es la única autoridad sobre la validez de la forma y la **sanea**:
///
///  - exige límites finitos (si no, [`DocumentError::InvalidArgument`]);
///  - `Rectangle`/`Ellipse`: normaliza a esquina superior-izquierda + tamaño no
///    negativo (el arrastre en cualquier dirección es válido) y rechaza la forma
///    si es más pequeña que [`MIN_SHAPE_EXTENT`] en ambos ejes;
///  - `Line`/`Arrow`: conserva el signo de `(width, height)` -- es el vector del
///    extremo inicial al final -- y rechaza el trazo si su longitud es menor que
///    [`MIN_SHAPE_EXTENT`];
///  - eleva `stroke_width` a [`MIN_STROKE_WIDTH`] si viene por debajo o no es finito.
///
/// Devuelve el documento actualizado.
pub fn add_shape(document_json: &str, page_id: &str, shape_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    let raw: Shape = parse(shape_json, "shape")?;
    let shape = sanitize_shape(raw)?;
    doc.page_mut(id)?.add_element(ElementKind::Shape(shape));
    doc.to_json()
}

/// Aplica las reglas de validez de una forma geométrica. Ver [`add_shape`].
fn sanitize_shape(mut shape: Shape) -> DocResult<Shape> {
    let b = shape.bounds;
    if !(b.x.is_finite() && b.y.is_finite() && b.width.is_finite() && b.height.is_finite()) {
        return Err(DocumentError::InvalidArgument(
            "los límites de la forma deben ser finitos".to_string(),
        ));
    }
    match shape.kind {
        ShapeKind::Rectangle | ShapeKind::Ellipse => {
            if shape.bounds.width < 0.0 {
                shape.bounds.x += shape.bounds.width;
                shape.bounds.width = -shape.bounds.width;
            }
            if shape.bounds.height < 0.0 {
                shape.bounds.y += shape.bounds.height;
                shape.bounds.height = -shape.bounds.height;
            }
            if shape.bounds.width < MIN_SHAPE_EXTENT && shape.bounds.height < MIN_SHAPE_EXTENT {
                return Err(DocumentError::InvalidArgument(
                    "la forma es demasiado pequeña para dibujarse".to_string(),
                ));
            }
        }
        ShapeKind::Line | ShapeKind::Arrow => {
            if shape.bounds.width.hypot(shape.bounds.height) < MIN_SHAPE_EXTENT {
                return Err(DocumentError::InvalidArgument(
                    "la línea es demasiado corta para dibujarse".to_string(),
                ));
            }
        }
    }
    if !shape.stroke_width.is_finite() || shape.stroke_width < MIN_STROKE_WIDTH {
        shape.stroke_width = MIN_STROKE_WIDTH;
    }
    Ok(shape)
}

/// Elimina el elemento `element_id` de la página `page_id`. Devuelve el documento
/// actualizado.
pub fn remove_element(document_json: &str, page_id: &str, element_id: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let pid = page_id.parse()?;
    let eid = element_id.parse()?;
    doc.page_mut(pid)?.remove_element(eid)?;
    doc.to_json()
}

/// Traslada todos los elementos de una página por `(dx, dy)`.
pub fn translate_page_elements(
    document_json: &str,
    page_id: &str,
    dx: f32,
    dy: f32,
) -> DocResult<String> {
    use super::geometry::Transformable;
    let mut doc = Document::from_json(document_json)?;
    let pid = page_id.parse()?;
    let page = doc.page_mut(pid)?;
    for element in &mut page.elements {
        element.translate(dx, dy);
    }
    doc.to_json()
}

/// Devuelve un [`DocumentSummary`] como JSON.
pub fn summary(document_json: &str) -> DocResult<String> {
    let doc = Document::from_json(document_json)?;
    dump(&DocumentSummary {
        id: doc.id.to_string(),
        title: doc.metadata.title.clone(),
        schema_version: doc.metadata.schema_version,
        page_count: doc.page_count(),
        element_count: doc.element_count(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_then_summarize() {
        let doc = create_document("Prueba").unwrap();
        let sum: serde_json::Value =
            serde_json::from_str(&summary(&doc).unwrap()).unwrap();
        assert_eq!(sum["title"], "Prueba");
        assert_eq!(sum["page_count"], 0);
        assert_eq!(sum["schema_version"], 1);
    }

    #[test]
    fn add_page_with_empty_spec_defaults_to_a4_blank() {
        let doc = create_document("d").unwrap();
        let doc = add_page(&doc, "{}").unwrap();
        let parsed = Document::from_json(&doc).unwrap();
        assert_eq!(parsed.page_count(), 1);
        assert_eq!(parsed.pages[0].size, PageSize::A4);
        assert_eq!(parsed.pages[0].template, PageTemplate::Blank);
    }

    #[test]
    fn add_page_with_grid_spec() {
        let doc = create_document("d").unwrap();
        let spec = r#"{"size":{"format":"A5"},"template":{"kind":"Grid","spacing":24.0}}"#;
        let doc = add_page(&doc, spec).unwrap();
        let parsed = Document::from_json(&doc).unwrap();
        assert_eq!(parsed.pages[0].size, PageSize::A5);
        assert_eq!(parsed.pages[0].template, PageTemplate::Grid(24.0));
    }

    #[test]
    fn add_element_and_count_it() {
        let doc = create_document("d").unwrap();
        let doc = add_page(&doc, "{}").unwrap();
        let page_id = Document::from_json(&doc).unwrap().pages[0].id.to_string();

        let text = r#"{"type":"Text","content":"hola","position":{"x":10.0,"y":20.0},
            "style":{"font_size":16.0,"bold":false,"italic":false,"underline":false,
            "color":{"r":0,"g":0,"b":0,"a":255}},"max_width":null}"#;
        let doc = add_element(&doc, &page_id, text).unwrap();

        let sum: serde_json::Value =
            serde_json::from_str(&summary(&doc).unwrap()).unwrap();
        assert_eq!(sum["element_count"], 1);
    }

    #[test]
    fn add_element_to_missing_page_errors() {
        let doc = create_document("d").unwrap();
        let err = add_element(&doc, "00000000000000000000000000000009", "{}").unwrap_err();
        // El id es válido pero la página no existe.
        assert!(matches!(err, DocumentError::Serialization(_) | DocumentError::PageNotFound(_)));
    }

    #[test]
    fn invalid_page_id_is_reported() {
        let doc = create_document("d").unwrap();
        let err = remove_page(&doc, "no-hex").unwrap_err();
        assert!(matches!(err, DocumentError::InvalidId(_)));
    }

    #[test]
    fn translate_page_elements_moves_shape() {
        let doc = create_document("d").unwrap();
        let doc = add_page(&doc, "{}").unwrap();
        let page_id = Document::from_json(&doc).unwrap().pages[0].id.to_string();
        let shape = r#"{"type":"Shape","kind":"Rectangle",
            "bounds":{"x":0.0,"y":0.0,"width":10.0,"height":10.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":1.0}"#;
        let doc = add_element(&doc, &page_id, shape).unwrap();
        let doc = translate_page_elements(&doc, &page_id, 5.0, 7.0).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        let json = serde_json::to_value(&parsed.pages[0].elements[0]).unwrap();
        assert_eq!(json["kind"]["bounds"]["x"], 5.0);
        assert_eq!(json["kind"]["bounds"]["y"], 7.0);
    }

    fn doc_with_blank_page() -> (String, String) {
        let doc = create_document("trazos").unwrap();
        let doc = add_page(&doc, "{}").unwrap();
        let page_id = Document::from_json(&doc).unwrap().pages[0].id.to_string();
        (doc, page_id)
    }

    #[test]
    fn add_stroke_inserts_stroke_and_clamps_pressure() {
        let (doc, page_id) = doc_with_blank_page();
        let stroke = r#"{"points":[
            {"position":{"x":1.0,"y":2.0},"pressure":0.4,"timestamp_ms":0},
            {"position":{"x":3.0,"y":4.0},"pressure":2.5,"timestamp_ms":16}],
            "color":{"r":10,"g":20,"b":30,"a":255},"width":3.0}"#;
        let doc = add_stroke(&doc, &page_id, stroke).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        match &parsed.pages[0].elements[0].kind {
            ElementKind::Stroke(s) => {
                assert_eq!(s.points.len(), 2);
                assert_eq!(s.points[1].pressure, 1.0);
                assert_eq!(s.points[0].timestamp_ms, 0);
            }
            other => panic!("esperaba Stroke, no {other:?}"),
        }
    }

    #[test]
    fn add_stroke_drops_non_finite_points_and_rejects_empty() {
        let (doc, page_id) = doc_with_blank_page();
        // Todas las muestras son no finitas -> no queda ningún punto.
        let stroke = r#"{"points":[
            {"position":{"x":null,"y":2.0},"pressure":0.4,"timestamp_ms":0}],
            "color":{"r":0,"g":0,"b":0,"a":255},"width":3.0}"#;
        // `null` no es un f32 válido: fallo de serialización controlado.
        assert!(matches!(
            add_stroke(&doc, &page_id, stroke),
            Err(DocumentError::Serialization(_))
        ));

        let empty = r#"{"points":[],"color":{"r":0,"g":0,"b":0,"a":255},"width":3.0}"#;
        assert!(matches!(
            add_stroke(&doc, &page_id, empty),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn add_stroke_raises_min_width() {
        let (doc, page_id) = doc_with_blank_page();
        let stroke = r#"{"points":[
            {"position":{"x":0.0,"y":0.0},"pressure":0.5,"timestamp_ms":0}],
            "color":{"r":0,"g":0,"b":0,"a":255},"width":0.0}"#;
        let doc = add_stroke(&doc, &page_id, stroke).unwrap();
        let parsed = Document::from_json(&doc).unwrap();
        if let ElementKind::Stroke(s) = &parsed.pages[0].elements[0].kind {
            assert_eq!(s.width, MIN_STROKE_WIDTH);
        } else {
            panic!("esperaba Stroke");
        }
    }

    #[test]
    fn add_stroke_to_missing_page_errors() {
        let (doc, _) = doc_with_blank_page();
        let stroke = r#"{"points":[
            {"position":{"x":0.0,"y":0.0},"pressure":0.5,"timestamp_ms":0}],
            "color":{"r":0,"g":0,"b":0,"a":255},"width":2.0}"#;
        let err = add_stroke(&doc, "00000000000000000000000000000009", stroke).unwrap_err();
        assert!(matches!(err, DocumentError::PageNotFound(_)));
    }

    #[test]
    fn add_shape_normalizes_negative_rectangle_bounds() {
        let (doc, page_id) = doc_with_blank_page();
        // Arrastre de abajo-derecha hacia arriba-izquierda: width/height negativos.
        let shape = r#"{"kind":"Rectangle","bounds":{"x":100.0,"y":100.0,"width":-40.0,"height":-30.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#;
        let doc = add_shape(&doc, &page_id, shape).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        match &parsed.pages[0].elements[0].kind {
            ElementKind::Shape(s) => {
                assert_eq!(s.kind, ShapeKind::Rectangle);
                assert_eq!((s.bounds.x, s.bounds.y), (60.0, 70.0));
                assert_eq!((s.bounds.width, s.bounds.height), (40.0, 30.0));
            }
            other => panic!("esperaba Shape, no {other:?}"),
        }
    }

    #[test]
    fn add_shape_keeps_line_direction() {
        let (doc, page_id) = doc_with_blank_page();
        let shape = r#"{"kind":"Line","bounds":{"x":10.0,"y":10.0,"width":-50.0,"height":20.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#;
        let doc = add_shape(&doc, &page_id, shape).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        if let ElementKind::Shape(s) = &parsed.pages[0].elements[0].kind {
            // El signo se conserva: el vector del extremo no se normaliza.
            assert_eq!((s.bounds.width, s.bounds.height), (-50.0, 20.0));
        } else {
            panic!("esperaba Shape");
        }
    }

    #[test]
    fn add_shape_rejects_degenerate_geometry() {
        let (doc, page_id) = doc_with_blank_page();
        let tiny_rect = r#"{"kind":"Ellipse","bounds":{"x":0.0,"y":0.0,"width":0.4,"height":0.3},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#;
        assert!(matches!(
            add_shape(&doc, &page_id, tiny_rect),
            Err(DocumentError::InvalidArgument(_))
        ));

        let short_line = r#"{"kind":"Arrow","bounds":{"x":5.0,"y":5.0,"width":0.2,"height":0.1},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#;
        assert!(matches!(
            add_shape(&doc, &page_id, short_line),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn add_shape_raises_min_stroke_width_and_accepts_fill() {
        let (doc, page_id) = doc_with_blank_page();
        let shape = r#"{"kind":"Rectangle","bounds":{"x":0.0,"y":0.0,"width":50.0,"height":40.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},
            "fill_color":{"r":200,"g":210,"b":220,"a":128},"stroke_width":0.0}"#;
        let doc = add_shape(&doc, &page_id, shape).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        if let ElementKind::Shape(s) = &parsed.pages[0].elements[0].kind {
            assert_eq!(s.stroke_width, MIN_STROKE_WIDTH);
            assert_eq!(s.fill_color.map(|c| c.a), Some(128));
        } else {
            panic!("esperaba Shape");
        }
    }

    #[test]
    fn add_shape_to_missing_page_errors() {
        let (doc, _) = doc_with_blank_page();
        let shape = r#"{"kind":"Rectangle","bounds":{"x":0.0,"y":0.0,"width":10.0,"height":10.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#;
        let err = add_shape(&doc, "00000000000000000000000000000009", shape).unwrap_err();
        assert!(matches!(err, DocumentError::PageNotFound(_)));
    }

    #[test]
    fn sanitize_shape_rejects_non_finite_bounds() {
        use super::super::geometry::{Color, Rect};
        let shape = Shape {
            kind: ShapeKind::Rectangle,
            bounds: Rect::new(f32::NAN, 0.0, 10.0, 10.0),
            stroke_color: Color::BLACK,
            fill_color: None,
            stroke_width: 2.0,
        };
        assert!(matches!(
            sanitize_shape(shape),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn malformed_document_json_errors() {
        assert!(matches!(
            add_page("{not json}", "{}"),
            Err(DocumentError::Serialization(_))
        ));
    }
}
