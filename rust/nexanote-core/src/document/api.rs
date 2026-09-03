//! API basada en cadenas JSON, pensada para cruzar la frontera FFI.
//!
//! Cada función recibe y devuelve `String` JSON y nunca hace `panic`: todo error
//! se devuelve como [`DocumentError`] (que el adaptador JNI traduce a excepción
//! Java). Es *stateless*: el documento vive en el lado de Kotlin y viaja como
//! JSON en cada llamada, lo que hace la capa trivialmente testeable.

use serde::{Deserialize, Serialize};

use super::element::ElementKind;
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

    #[test]
    fn malformed_document_json_errors() {
        assert!(matches!(
            add_page("{not json}", "{}"),
            Err(DocumentError::Serialization(_))
        ));
    }
}
