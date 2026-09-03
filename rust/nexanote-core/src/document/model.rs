//! El documento: metadatos + lista ordenada de páginas.

use serde::{Deserialize, Serialize};

use super::error::{DocResult, DocumentError};
use super::id::{DocumentId, PageId};
use super::now_ms;
use super::page::{Page, PageSize, PageTemplate};

/// Versión del esquema serializado. Se incrementa ante cambios incompatibles.
pub const SCHEMA_VERSION: u32 = 1;

/// Metadatos del documento.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DocumentMetadata {
    pub title: String,
    /// Epoch en milisegundos de creación.
    pub created_ms: u64,
    /// Epoch en milisegundos de la última modificación.
    pub modified_ms: u64,
    pub schema_version: u32,
}

/// Documento completo de NexaNote.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Document {
    pub id: DocumentId,
    pub metadata: DocumentMetadata,
    pub pages: Vec<Page>,
}

impl Document {
    /// Crea un documento nuevo (sin páginas) con id y marcas de tiempo.
    pub fn new(title: impl Into<String>) -> Self {
        let ts = now_ms();
        Document {
            id: DocumentId::generate(),
            metadata: DocumentMetadata {
                title: title.into(),
                created_ms: ts,
                modified_ms: ts,
                schema_version: SCHEMA_VERSION,
            },
            pages: Vec::new(),
        }
    }

    fn touch(&mut self) {
        self.metadata.modified_ms = now_ms();
    }

    /// Añade una página al final y devuelve su id.
    pub fn add_page(&mut self, size: PageSize, template: PageTemplate) -> DocResult<PageId> {
        let page = Page::new(size, template)?;
        let id = page.id;
        self.pages.push(page);
        self.touch();
        Ok(id)
    }

    /// Inserta una página en `index` (`index == len` equivale a añadir al final).
    pub fn insert_page(
        &mut self,
        index: usize,
        size: PageSize,
        template: PageTemplate,
    ) -> DocResult<PageId> {
        if index > self.pages.len() {
            return Err(DocumentError::IndexOutOfBounds {
                index,
                len: self.pages.len(),
            });
        }
        let page = Page::new(size, template)?;
        let id = page.id;
        self.pages.insert(index, page);
        self.touch();
        Ok(id)
    }

    /// Elimina una página por id y la devuelve.
    pub fn remove_page(&mut self, id: PageId) -> DocResult<Page> {
        match self.pages.iter().position(|p| p.id == id) {
            Some(index) => {
                let page = self.pages.remove(index);
                self.touch();
                Ok(page)
            }
            None => Err(DocumentError::PageNotFound(id.to_string())),
        }
    }

    /// Mueve una página de `from` a `to` (índices).
    pub fn move_page(&mut self, from: usize, to: usize) -> DocResult<()> {
        let len = self.pages.len();
        if from >= len {
            return Err(DocumentError::IndexOutOfBounds { index: from, len });
        }
        if to >= len {
            return Err(DocumentError::IndexOutOfBounds { index: to, len });
        }
        let page = self.pages.remove(from);
        self.pages.insert(to, page);
        self.touch();
        Ok(())
    }

    pub fn page(&self, id: PageId) -> Option<&Page> {
        self.pages.iter().find(|p| p.id == id)
    }

    /// Referencia mutable a una página; marca el documento como modificado.
    pub fn page_mut(&mut self, id: PageId) -> DocResult<&mut Page> {
        let exists = self.pages.iter().any(|p| p.id == id);
        if !exists {
            return Err(DocumentError::PageNotFound(id.to_string()));
        }
        self.metadata.modified_ms = now_ms();
        // `exists` garantiza que el `find` siguiente encuentra la página.
        self.pages
            .iter_mut()
            .find(|p| p.id == id)
            .ok_or_else(|| DocumentError::PageNotFound(id.to_string()))
    }

    pub fn page_count(&self) -> usize {
        self.pages.len()
    }

    /// Total de elementos en todas las páginas.
    pub fn element_count(&self) -> usize {
        self.pages.iter().map(Page::element_count).sum()
    }

    /// Serializa a JSON.
    pub fn to_json(&self) -> DocResult<String> {
        serde_json::to_string(self).map_err(|e| DocumentError::Serialization(e.to_string()))
    }

    /// Serializa a JSON legible (con sangría).
    pub fn to_json_pretty(&self) -> DocResult<String> {
        serde_json::to_string_pretty(self).map_err(|e| DocumentError::Serialization(e.to_string()))
    }

    /// Deserializa desde JSON.
    pub fn from_json(json: &str) -> DocResult<Self> {
        serde_json::from_str(json).map_err(|e| DocumentError::Serialization(e.to_string()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_document_has_metadata_and_no_pages() {
        let doc = Document::new("Cuaderno");
        assert_eq!(doc.metadata.title, "Cuaderno");
        assert_eq!(doc.metadata.schema_version, SCHEMA_VERSION);
        assert_eq!(doc.page_count(), 0);
        assert_eq!(doc.metadata.created_ms, doc.metadata.modified_ms);
    }

    #[test]
    fn add_and_remove_pages() {
        let mut doc = Document::new("d");
        let p1 = doc.add_page(PageSize::A4, PageTemplate::Blank).unwrap();
        let p2 = doc.add_page(PageSize::A5, PageTemplate::Grid(20.0)).unwrap();
        assert_eq!(doc.page_count(), 2);

        let removed = doc.remove_page(p1).unwrap();
        assert_eq!(removed.id, p1);
        assert_eq!(doc.page_count(), 1);
        assert_eq!(doc.pages[0].id, p2);
    }

    #[test]
    fn insert_page_out_of_bounds_errors() {
        let mut doc = Document::new("d");
        let err = doc
            .insert_page(3, PageSize::A4, PageTemplate::Blank)
            .unwrap_err();
        assert!(matches!(err, DocumentError::IndexOutOfBounds { index: 3, len: 0 }));
    }

    #[test]
    fn move_page_reorders() {
        let mut doc = Document::new("d");
        let a = doc.add_page(PageSize::A4, PageTemplate::Blank).unwrap();
        let b = doc.add_page(PageSize::A4, PageTemplate::Blank).unwrap();
        let c = doc.add_page(PageSize::A4, PageTemplate::Blank).unwrap();
        doc.move_page(0, 2).unwrap();
        assert_eq!(
            doc.pages.iter().map(|p| p.id).collect::<Vec<_>>(),
            vec![b, c, a]
        );
    }

    #[test]
    fn remove_missing_page_errors() {
        let mut doc = Document::new("d");
        let ghost = PageId::generate();
        assert!(matches!(
            doc.remove_page(ghost),
            Err(DocumentError::PageNotFound(_))
        ));
    }

    #[test]
    fn document_roundtrips_through_json() {
        let mut doc = Document::new("Con contenido");
        let page = doc.add_page(PageSize::A4, PageTemplate::Ruled(28.0)).unwrap();
        doc.page_mut(page).unwrap();
        let json = doc.to_json().unwrap();
        let back = Document::from_json(&json).unwrap();
        assert_eq!(back, doc);
    }
}
