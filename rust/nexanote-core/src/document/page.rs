//! Páginas: tamaño, plantilla de fondo y colección ordenada de elementos.

use serde::{Deserialize, Serialize};

use super::element::{Element, ElementKind};
use super::error::{DocResult, DocumentError};
use super::id::{ElementId, PageId};

/// Tamaño de página. Las dimensiones se expresan en milímetros para los formatos
/// estándar; `Custom` permite cualquier lienzo.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(tag = "format", content = "size")]
pub enum PageSize {
    A4,
    A5,
    Letter,
    Custom { width_mm: f32, height_mm: f32 },
}

impl Default for PageSize {
    fn default() -> Self {
        PageSize::A4
    }
}

impl PageSize {
    /// Dimensiones `(ancho, alto)` en milímetros.
    pub fn dimensions_mm(self) -> (f32, f32) {
        match self {
            PageSize::A4 => (210.0, 297.0),
            PageSize::A5 => (148.0, 210.0),
            PageSize::Letter => (215.9, 279.4),
            PageSize::Custom { width_mm, height_mm } => (width_mm, height_mm),
        }
    }

    fn validate(self) -> DocResult<()> {
        let (w, h) = self.dimensions_mm();
        if w > 0.0 && h > 0.0 {
            Ok(())
        } else {
            Err(DocumentError::InvalidArgument(
                "las dimensiones de página deben ser positivas".to_string(),
            ))
        }
    }
}

/// Plantilla de fondo de la página.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "spacing")]
pub enum PageTemplate {
    Blank,
    /// Cuadrícula con separación en unidades lógicas.
    Grid(f32),
    /// Renglones horizontales.
    Ruled(f32),
    /// Puntos.
    Dotted(f32),
}

impl Default for PageTemplate {
    fn default() -> Self {
        PageTemplate::Blank
    }
}

/// Una página del documento.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Page {
    pub id: PageId,
    pub size: PageSize,
    pub template: PageTemplate,
    pub elements: Vec<Element>,
}

impl Page {
    /// Crea una página vacía con id generado.
    pub fn new(size: PageSize, template: PageTemplate) -> DocResult<Self> {
        size.validate()?;
        Ok(Page {
            id: PageId::generate(),
            size,
            template,
            elements: Vec::new(),
        })
    }

    /// Añade un elemento al final (encima del resto) y devuelve su id.
    pub fn add_element(&mut self, kind: ElementKind) -> ElementId {
        let z = self.elements.iter().map(|e| e.z_index).max().unwrap_or(-1) + 1;
        let element = Element::new(kind, z);
        let id = element.id;
        self.elements.push(element);
        id
    }

    /// Referencia inmutable a un elemento por id.
    pub fn element(&self, id: ElementId) -> Option<&Element> {
        self.elements.iter().find(|e| e.id == id)
    }

    /// Referencia mutable a un elemento por id.
    pub fn element_mut(&mut self, id: ElementId) -> Option<&mut Element> {
        self.elements.iter_mut().find(|e| e.id == id)
    }

    /// Elimina un elemento y lo devuelve.
    pub fn remove_element(&mut self, id: ElementId) -> DocResult<Element> {
        match self.elements.iter().position(|e| e.id == id) {
            Some(index) => Ok(self.elements.remove(index)),
            None => Err(DocumentError::ElementNotFound(id.to_string())),
        }
    }

    /// Número de elementos en la página.
    pub fn element_count(&self) -> usize {
        self.elements.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::document::element::{TextBox, TextStyle};
    use crate::document::geometry::Point;

    fn text(content: &str) -> ElementKind {
        ElementKind::Text(TextBox {
            content: content.to_string(),
            position: Point::ORIGIN,
            style: TextStyle::default(),
            max_width: None,
        })
    }

    #[test]
    fn new_page_is_empty_and_valid() {
        let page = Page::new(PageSize::A4, PageTemplate::Grid(24.0)).unwrap();
        assert_eq!(page.element_count(), 0);
        assert_eq!(page.size.dimensions_mm(), (210.0, 297.0));
    }

    #[test]
    fn custom_size_must_be_positive() {
        let err = Page::new(
            PageSize::Custom { width_mm: 0.0, height_mm: 100.0 },
            PageTemplate::Blank,
        )
        .unwrap_err();
        assert!(matches!(err, DocumentError::InvalidArgument(_)));
    }

    #[test]
    fn add_element_assigns_increasing_z_index() {
        let mut page = Page::new(PageSize::A5, PageTemplate::Blank).unwrap();
        let a = page.add_element(text("a"));
        let b = page.add_element(text("b"));
        assert_ne!(a, b);
        assert_eq!(page.element(a).unwrap().z_index, 0);
        assert_eq!(page.element(b).unwrap().z_index, 1);
    }

    #[test]
    fn remove_missing_element_errors() {
        let mut page = Page::new(PageSize::A4, PageTemplate::Blank).unwrap();
        let ghost = ElementId::generate();
        assert!(matches!(
            page.remove_element(ghost),
            Err(DocumentError::ElementNotFound(_))
        ));
    }
}
