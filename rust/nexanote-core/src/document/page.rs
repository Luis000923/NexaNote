//! Páginas: tamaño, plantilla de fondo y colección ordenada de elementos.

use serde::{Deserialize, Serialize};

use super::element::{Element, ElementKind};
use super::error::{DocResult, DocumentError};
use super::geometry::{Color, Rect};
use super::id::{ElementId, PageId};

/// Tamaño de página. Las dimensiones se expresan en milímetros para los formatos
/// estándar; `Custom` permite cualquier lienzo y `Infinite` describe un **lienzo
/// sin límites**, que crece con su contenido.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(tag = "format", content = "size")]
pub enum PageSize {
    A4,
    A5,
    Letter,
    Custom { width_mm: f32, height_mm: f32 },
    /// Lienzo infinito: no hay hoja delimitada. Las dimensiones que reporta son
    /// sólo la **extensión mínima** inicial; el render la amplía para cubrir todo
    /// el contenido (ver `Page::content_bounds`).
    Infinite,
}

/// Extensión mínima (mm) de un lienzo infinito recién creado.
pub const INFINITE_MIN_WIDTH_MM: f32 = 420.0;
/// Altura mínima (mm) de un lienzo infinito recién creado.
pub const INFINITE_MIN_HEIGHT_MM: f32 = 420.0;

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
            PageSize::Infinite => (INFINITE_MIN_WIDTH_MM, INFINITE_MIN_HEIGHT_MM),
        }
    }

    /// `true` si el lienzo no está delimitado por una hoja.
    pub fn is_infinite(self) -> bool {
        matches!(self, PageSize::Infinite)
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

    /// Caja que envuelve **todo** el contenido de la página, o `None` si está
    /// vacía. Es la base del crecimiento del lienzo infinito y del encuadre de
    /// una selección.
    pub fn content_bounds(&self) -> Option<Rect> {
        let mut iter = self.elements.iter();
        let mut acc = iter.next()?.bounds();
        for element in iter {
            acc = acc.union(element.bounds());
        }
        Some(acc)
    }

    /// Ids de los elementos **contenidos por completo** en `area`, en orden de
    /// pintado (`z_index` ascendente).
    pub fn elements_in(&self, area: Rect) -> Vec<ElementId> {
        let mut hits: Vec<&Element> = self
            .elements
            .iter()
            .filter(|e| area.contains_rect(e.bounds()))
            .collect();
        hits.sort_by_key(|e| e.z_index);
        hits.iter().map(|e| e.id).collect()
    }

    /// Elemento **más al frente** cuya caja contiene el punto `(x, y)`, con un
    /// margen de tolerancia para que tocar un trazo fino sea viable con el dedo.
    pub fn element_at(&self, x: f32, y: f32, tolerance: f32) -> Option<ElementId> {
        let point = super::geometry::Point::new(x, y);
        self.elements
            .iter()
            .filter(|e| e.bounds().inflated(tolerance.max(0.0)).contains_point(point))
            .max_by_key(|e| e.z_index)
            .map(|e| e.id)
    }

    /// Duplica los elementos indicados desplazados `(dx, dy)`, los coloca encima
    /// del resto y devuelve los ids de las copias, en el mismo orden.
    pub fn duplicate_elements(&mut self, ids: &[ElementId], dx: f32, dy: f32) -> Vec<ElementId> {
        use super::geometry::Transformable;
        let mut copies: Vec<Element> = Vec::with_capacity(ids.len());
        for id in ids {
            if let Some(source) = self.element(*id) {
                let mut clone = Element::new(source.kind.clone(), 0);
                clone.translate(dx, dy);
                copies.push(clone);
            }
        }
        let mut next_z = self.elements.iter().map(|e| e.z_index).max().unwrap_or(-1) + 1;
        let mut created = Vec::with_capacity(copies.len());
        for mut copy in copies {
            copy.z_index = next_z;
            next_z += 1;
            created.push(copy.id);
            self.elements.push(copy);
        }
        created
    }

    /// Color de fondo con el que se pinta la página.
    pub fn background(&self) -> Color {
        Color::rgb(255, 255, 255)
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

    use crate::document::element::{Shape, ShapeKind};

    fn rect(x: f32, y: f32, w: f32, h: f32) -> ElementKind {
        ElementKind::Shape(Shape {
            kind: ShapeKind::Rectangle,
            bounds: Rect::new(x, y, w, h),
            stroke_color: Color::BLACK,
            fill_color: None,
            stroke_width: 0.0,
        })
    }

    #[test]
    fn content_bounds_is_none_when_empty_and_unions_every_element() {
        let mut page = Page::new(PageSize::A4, PageTemplate::Blank).unwrap();
        assert_eq!(page.content_bounds(), None);
        page.add_element(rect(0.0, 0.0, 10.0, 10.0));
        page.add_element(rect(90.0, 40.0, 10.0, 20.0));
        assert_eq!(page.content_bounds(), Some(Rect::new(0.0, 0.0, 100.0, 60.0)));
    }

    #[test]
    fn elements_in_takes_only_fully_contained_in_paint_order() {
        let mut page = Page::new(PageSize::A4, PageTemplate::Blank).unwrap();
        let inside = page.add_element(rect(10.0, 10.0, 20.0, 20.0));
        let _straddling = page.add_element(rect(90.0, 90.0, 40.0, 40.0));
        let _outside = page.add_element(rect(500.0, 500.0, 5.0, 5.0));
        assert_eq!(page.elements_in(Rect::new(0.0, 0.0, 100.0, 100.0)), vec![inside]);
        assert!(page.elements_in(Rect::new(0.0, 0.0, 1.0, 1.0)).is_empty());
    }

    #[test]
    fn element_at_returns_the_topmost_hit_and_none_on_a_miss() {
        let mut page = Page::new(PageSize::A4, PageTemplate::Blank).unwrap();
        let _low = page.add_element(rect(0.0, 0.0, 50.0, 50.0));
        let high = page.add_element(rect(10.0, 10.0, 50.0, 50.0));
        assert_eq!(page.element_at(20.0, 20.0, 0.0), Some(high));
        assert_eq!(page.element_at(500.0, 500.0, 0.0), None);
        // La tolerancia permite tocar justo fuera de la caja.
        assert_eq!(page.element_at(-3.0, 20.0, 8.0), Some(_low));
    }

    #[test]
    fn duplicate_elements_skips_unknown_ids_and_stacks_copies_on_top() {
        let mut page = Page::new(PageSize::A4, PageTemplate::Blank).unwrap();
        let a = page.add_element(rect(0.0, 0.0, 10.0, 10.0));
        let ghost = ElementId::generate();
        let created = page.duplicate_elements(&[a, ghost], 5.0, 5.0);
        assert_eq!(created.len(), 1);
        assert_ne!(created[0], a);
        assert_eq!(page.element_count(), 2);
        let copy = page.element(created[0]).unwrap();
        assert!(copy.z_index > page.element(a).unwrap().z_index);
    }
}
