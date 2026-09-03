//! Modelo de documento de NexaNote.
//!
//! Jerarquía: [`Document`] → [`Page`] → [`Element`] ([`ElementKind`]).
//! Cada nivel tiene un [`Id`] propio y el contenido es fuertemente tipado y
//! (de)serializable con `serde` (JSON) para persistencia y para cruzar el FFI.
//!
//! Diseño (ver `claude.md`):
//!  - Sin `panic!`/`unwrap()` en producción: las operaciones falibles devuelven
//!    [`DocResult`].
//!  - La capa [`api`] es *stateless* y basada en JSON, para un puente FFI simple
//!    y seguro.

use std::time::{SystemTime, UNIX_EPOCH};

pub mod api;
pub mod element;
pub mod error;
pub mod geometry;
pub mod id;
pub mod model;
pub mod page;
pub mod render;

pub use element::{
    BinaryOp, Element, ElementKind, Formula, FormulaNode, Shape, ShapeKind, Stroke, StrokePoint,
    TextBox, TextStyle,
};
pub use error::{DocResult, DocumentError};
pub use geometry::{Color, Point, Rect, Transformable};
pub use id::{DocumentId, ElementId, Id, PageId};
pub use model::{Document, DocumentMetadata, SCHEMA_VERSION};
pub use page::{Page, PageSize, PageTemplate};
pub use render::{build_scene, render_page, ScenePage, ScenePrimitive, SceneTemplate};

/// Epoch actual en milisegundos. `0` si el reloj del sistema es anterior a 1970
/// (no se propaga el error: una marca de tiempo degradada no debe tumbar una
/// operación de edición).
pub(crate) fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod integration_tests {
    use super::*;

    /// Flujo completo: crear documento → añadir páginas → insertar varios tipos
    /// de elemento → transformarlos → serializar y recuperar.
    #[test]
    fn full_document_lifecycle() {
        let mut doc = Document::new("Cuaderno de física");
        assert_eq!(doc.page_count(), 0);

        let p1 = doc.add_page(PageSize::A4, PageTemplate::Grid(24.0)).unwrap();
        let _p2 = doc.add_page(PageSize::A5, PageTemplate::Blank).unwrap();
        assert_eq!(doc.page_count(), 2);

        // Un trazo, un texto, una fórmula y una forma en la primera página.
        let page = doc.page_mut(p1).unwrap();
        let stroke = page.add_element(ElementKind::Stroke(Stroke {
            points: vec![
                StrokePoint { position: Point::new(0.0, 0.0), pressure: 0.2, timestamp_ms: 0 },
                StrokePoint { position: Point::new(4.0, 8.0), pressure: 0.9, timestamp_ms: 12 },
            ],
            color: Color::rgb(20, 20, 200),
            width: 1.5,
        }));
        page.add_element(ElementKind::Text(TextBox {
            content: "F = m·a".to_string(),
            position: Point::new(30.0, 40.0),
            style: TextStyle::default(),
            max_width: Some(200.0),
        }));
        page.add_element(ElementKind::Formula(Formula {
            latex: r"\frac{d}{dt}p".to_string(),
            position: Point::new(30.0, 80.0),
            ast: Some(FormulaNode::Fraction {
                numerator: Box::new(FormulaNode::Symbol("dp".to_string())),
                denominator: Box::new(FormulaNode::Symbol("dt".to_string())),
            }),
        }));
        page.add_element(ElementKind::Shape(Shape {
            kind: ShapeKind::Arrow,
            bounds: Rect::new(10.0, 10.0, 50.0, 0.0),
            stroke_color: Color::BLACK,
            fill_color: None,
            stroke_width: 2.0,
        }));
        assert_eq!(doc.element_count(), 4);

        // Transformar: mover el trazo y comprobar.
        let page = doc.page_mut(p1).unwrap();
        page.element_mut(stroke).unwrap().translate(100.0, 0.0);
        if let ElementKind::Stroke(s) = &page.element(stroke).unwrap().kind {
            assert_eq!(s.points[0].position, Point::new(100.0, 0.0));
        } else {
            unreachable!("el elemento debe seguir siendo un trazo");
        }

        // Eliminar un elemento.
        let removed = doc.page_mut(p1).unwrap().remove_element(stroke).unwrap();
        assert_eq!(removed.id, stroke);
        assert_eq!(doc.element_count(), 3);

        // Serializar y recuperar sin pérdidas.
        let json = doc.to_json_pretty().unwrap();
        let restored = Document::from_json(&json).unwrap();
        assert_eq!(restored, doc);
    }

    #[test]
    fn api_layer_matches_typed_model() {
        // El mismo flujo a través de la capa JSON/FFI.
        let doc = api::create_document("via API").unwrap();
        let doc = api::add_page(&doc, r#"{"template":{"kind":"Dotted","spacing":18.0}}"#).unwrap();
        let page_id = Document::from_json(&doc).unwrap().pages[0].id.to_string();
        let shape = r#"{"type":"Shape","kind":"Ellipse",
            "bounds":{"x":0.0,"y":0.0,"width":20.0,"height":20.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":1.0}"#;
        let doc = api::add_element(&doc, &page_id, shape).unwrap();

        let summary: DocumentSummaryShape =
            serde_json::from_str(&api::summary(&doc).unwrap()).unwrap();
        assert_eq!(summary.page_count, 1);
        assert_eq!(summary.element_count, 1);
    }

    #[derive(serde::Deserialize)]
    struct DocumentSummaryShape {
        page_count: usize,
        element_count: usize,
    }
}
