//! Historial de edición (Undo/Redo) del documento.
//!
//! # Estrategia: instantáneas acotadas
//!
//! El núcleo es *stateless* (ver [`api`](super::api)): el documento viaja como
//! JSON en cada llamada FFI. El historial sigue el mismo contrato: viaja como su
//! propio blob JSON ([`History`]) y cada función lo recibe y lo devuelve.
//!
//! Se elige un modelo de **instantáneas** (no un registro de comandos) porque:
//!  - es agnóstico al tipo de operación (trazo, forma, texto, fórmula, gráfica,
//!    borrado, traslación...): cualquier mutación futura entra sin tocar aquí;
//!  - `undo`/`redo` son O(1) y no re-ejecutan lógica, así que la UI nunca sufre
//!    tirones;
//!  - la integridad es trivial: una instantánea *es* un documento válido.
//!
//! El coste -- memoria -- se acota con [`History::DEFAULT_LIMIT`]: sólo se
//! conservan las últimas *N* instantáneas de cada pila; las más antiguas se
//! descartan. Las instantáneas se guardan como [`serde_json::Value`] (no como
//! `String`) para no anidar JSON escapado y para que comparar dos estados
//! (evitar entradas duplicadas) sea directo.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::error::{DocResult, DocumentError};
use super::model::Document;

fn default_limit() -> usize {
    History::DEFAULT_LIMIT
}

/// Pila de instantáneas del documento con un cursor implícito en `present`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct History {
    /// Estados anteriores, del más antiguo al más reciente. `undo` saca de aquí.
    past: Vec<Value>,
    /// Estado actual del documento.
    present: Value,
    /// Estados deshechos, del más reciente al más antiguo. `redo` saca de aquí.
    future: Vec<Value>,
    /// Máximo de instantáneas por pila. Ver [`History::DEFAULT_LIMIT`].
    #[serde(default = "default_limit")]
    limit: usize,
}

impl History {
    /// Profundidad máxima de cada pila (`past` y `future`). Un documento típico
    /// serializado ronda unas pocas decenas de KB; 250 instantáneas mantienen el
    /// historial "ilimitado" en la práctica sin que la memoria crezca sin freno.
    pub const DEFAULT_LIMIT: usize = 250;

    fn new(present: Value) -> Self {
        History {
            past: Vec::new(),
            present,
            future: Vec::new(),
            limit: Self::DEFAULT_LIMIT,
        }
    }

    fn effective_limit(&self) -> usize {
        self.limit.max(1)
    }

    /// Recorta una pila a las últimas `limit` entradas, descartando por el frente.
    fn cap_front(stack: &mut Vec<Value>, limit: usize) {
        if stack.len() > limit {
            let overflow = stack.len() - limit;
            stack.drain(0..overflow);
        }
    }

    /// `past.len()` -- cuántas veces se puede deshacer.
    pub fn undo_depth(&self) -> usize {
        self.past.len()
    }

    /// `future.len()` -- cuántas veces se puede rehacer.
    pub fn redo_depth(&self) -> usize {
        self.future.len()
    }
}

fn parse_history(json: &str) -> DocResult<History> {
    serde_json::from_str(json)
        .map_err(|e| DocumentError::Serialization(format!("history: {e}")))
}

fn dump_history(h: &History) -> DocResult<String> {
    serde_json::to_string(h).map_err(|e| DocumentError::Serialization(e.to_string()))
}

/// Valida que `document_json` es un [`Document`] y lo devuelve como `Value`.
fn document_value(document_json: &str) -> DocResult<Value> {
    // `from_json` es la autoridad sobre la validez del documento; reutilizarla
    // garantiza que el historial nunca guarde una instantánea corrupta.
    let doc = Document::from_json(document_json)?;
    serde_json::to_value(&doc).map_err(|e| DocumentError::Serialization(e.to_string()))
}

/// Crea un historial nuevo anclado en `document_json` (sin nada que deshacer).
pub fn init(document_json: &str) -> DocResult<String> {
    let present = document_value(document_json)?;
    dump_history(&History::new(present))
}

/// Registra `document_json` como nuevo estado actual.
///
/// Empuja el estado anterior a `past`, limpia `future` (una nueva edición corta
/// la rama de rehacer) y recorta `past` al límite. Si el nuevo estado es idéntico
/// al actual, no hace nada: así, re-registrar tras un render no ensucia la pila.
pub fn record(history_json: &str, document_json: &str) -> DocResult<String> {
    let mut h = parse_history(history_json)?;
    let next = document_value(document_json)?;
    if next == h.present {
        return dump_history(&h);
    }
    let previous = std::mem::replace(&mut h.present, next);
    h.past.push(previous);
    h.future.clear();
    let limit = h.effective_limit();
    History::cap_front(&mut h.past, limit);
    dump_history(&h)
}

/// Deshace un paso. Si no hay nada que deshacer, devuelve el historial intacto.
pub fn undo(history_json: &str) -> DocResult<String> {
    let mut h = parse_history(history_json)?;
    if let Some(previous) = h.past.pop() {
        let current = std::mem::replace(&mut h.present, previous);
        h.future.insert(0, current);
        let limit = h.effective_limit();
        if h.future.len() > limit {
            h.future.truncate(limit);
        }
    }
    dump_history(&h)
}

/// Rehace un paso. Si no hay nada que rehacer, devuelve el historial intacto.
pub fn redo(history_json: &str) -> DocResult<String> {
    let mut h = parse_history(history_json)?;
    if !h.future.is_empty() {
        let next = h.future.remove(0);
        let current = std::mem::replace(&mut h.present, next);
        h.past.push(current);
        let limit = h.effective_limit();
        History::cap_front(&mut h.past, limit);
    }
    dump_history(&h)
}

/// Devuelve el documento (JSON) del estado actual del historial.
pub fn document(history_json: &str) -> DocResult<String> {
    let h = parse_history(history_json)?;
    serde_json::to_string(&h.present).map_err(|e| DocumentError::Serialization(e.to_string()))
}

/// Estado del historial como `"<undo_depth>,<redo_depth>"` (formato compacto y
/// trivial de parsear desde Kotlin sin un lector JSON).
pub fn status(history_json: &str) -> DocResult<String> {
    let h = parse_history(history_json)?;
    Ok(format!("{},{}", h.undo_depth(), h.redo_depth()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::document::api;

    /// Documento base con una página en blanco: `(document_json, page_id)`.
    fn base_doc() -> (String, String) {
        let doc = api::create_document("historial").unwrap();
        let doc = api::add_page(&doc, "{}").unwrap();
        let page_id = Document::from_json(&doc).unwrap().pages[0].id.to_string();
        (doc, page_id)
    }

    fn stroke_at(x: f32) -> String {
        format!(
            r#"{{"points":[{{"position":{{"x":{x},"y":0.0}},"pressure":0.5,"timestamp_ms":0}}],
               "color":{{"r":0,"g":0,"b":0,"a":255}},"width":2.0}}"#
        )
    }

    fn doc0_title(document_json: &str) -> String {
        Document::from_json(document_json).unwrap().metadata.title
    }

    fn element_count(document_json: &str) -> u64 {
        let summary: serde_json::Value =
            serde_json::from_str(&api::summary(document_json).unwrap()).unwrap();
        summary["element_count"].as_u64().unwrap()
    }

    #[test]
    fn init_has_nothing_to_undo_or_redo() {
        let (doc, _) = base_doc();
        let h = init(&doc).unwrap();
        assert_eq!(status(&h).unwrap(), "0,0");
        // El documento del historial es equivalente al de partida.
        assert_eq!(
            Document::from_json(&document(&h).unwrap()).unwrap(),
            Document::from_json(&doc).unwrap()
        );
    }

    #[test]
    fn record_then_undo_and_redo_restore_state_exactly() {
        let (doc0, page) = base_doc();
        let mut h = init(&doc0).unwrap();

        let doc1 = api::add_stroke(&doc0, &page, &stroke_at(1.0)).unwrap();
        h = record(&h, &doc1).unwrap();
        let doc2 = api::add_stroke(&doc1, &page, &stroke_at(2.0)).unwrap();
        h = record(&h, &doc2).unwrap();
        let doc3 = api::add_stroke(&doc2, &page, &stroke_at(3.0)).unwrap();
        h = record(&h, &doc3).unwrap();

        assert_eq!(status(&h).unwrap(), "3,0");
        assert_eq!(element_count(&document(&h).unwrap()), 3);

        // Tres undos: de vuelta al documento vacío.
        h = undo(&h).unwrap();
        assert_eq!(element_count(&document(&h).unwrap()), 2);
        h = undo(&h).unwrap();
        assert_eq!(element_count(&document(&h).unwrap()), 1);
        h = undo(&h).unwrap();
        assert_eq!(status(&h).unwrap(), "0,3");
        assert_eq!(
            Document::from_json(&document(&h).unwrap()).unwrap(),
            Document::from_json(&doc0).unwrap()
        );

        // Undo extra: no-op estable.
        h = undo(&h).unwrap();
        assert_eq!(status(&h).unwrap(), "0,3");

        // Tres redos: exactamente el estado final.
        h = redo(&h).unwrap();
        h = redo(&h).unwrap();
        h = redo(&h).unwrap();
        assert_eq!(status(&h).unwrap(), "3,0");
        assert_eq!(
            Document::from_json(&document(&h).unwrap()).unwrap(),
            Document::from_json(&doc3).unwrap()
        );

        // Redo extra: no-op estable.
        h = redo(&h).unwrap();
        assert_eq!(status(&h).unwrap(), "3,0");
    }

    #[test]
    fn recording_after_undo_clears_the_redo_branch() {
        let (doc0, page) = base_doc();
        let mut h = init(&doc0).unwrap();
        let doc1 = api::add_stroke(&doc0, &page, &stroke_at(1.0)).unwrap();
        h = record(&h, &doc1).unwrap();
        let doc2 = api::add_stroke(&doc1, &page, &stroke_at(2.0)).unwrap();
        h = record(&h, &doc2).unwrap();

        h = undo(&h).unwrap();
        assert_eq!(status(&h).unwrap(), "1,1");

        // Nueva rama: el redo pendiente desaparece.
        let alt = api::add_stroke(&doc1, &page, &stroke_at(9.0)).unwrap();
        h = record(&h, &alt).unwrap();
        assert_eq!(status(&h).unwrap(), "2,0");
        assert_eq!(
            Document::from_json(&document(&h).unwrap()).unwrap(),
            Document::from_json(&alt).unwrap()
        );
    }

    #[test]
    fn recording_an_identical_state_is_a_no_op() {
        let (doc0, _) = base_doc();
        let mut h = init(&doc0).unwrap();
        h = record(&h, &doc0).unwrap();
        h = record(&h, &doc0).unwrap();
        assert_eq!(status(&h).unwrap(), "0,0");
    }

    #[test]
    fn history_is_bounded_and_evicts_oldest_snapshots() {
        let (doc0, _) = base_doc();
        let mut h = init(&doc0).unwrap();
        // Instantáneas baratas: sólo cambia el título en cada paso.
        let retitled = |n: usize| {
            let mut d = Document::from_json(&doc0).unwrap();
            d.metadata.title = format!("v{n}");
            d.to_json().unwrap()
        };
        let steps = History::DEFAULT_LIMIT + 40;
        for i in 1..=steps {
            h = record(&h, &retitled(i)).unwrap();
        }
        // La pila de undo nunca supera el límite.
        assert_eq!(status(&h).unwrap(), format!("{},0", History::DEFAULT_LIMIT));

        // Se puede deshacer exactamente `DEFAULT_LIMIT` veces; el estado más
        // antiguo alcanzable ya no es el documento original.
        for _ in 0..History::DEFAULT_LIMIT {
            h = undo(&h).unwrap();
        }
        assert_eq!(status(&h).unwrap().split(',').next().unwrap(), "0");
        let oldest: Document = Document::from_json(&document(&h).unwrap()).unwrap();
        assert_ne!(oldest.metadata.title, doc0_title(&doc0));
        assert!(oldest.metadata.title.starts_with('v'));
    }

    #[test]
    fn malformed_history_json_is_reported_not_panicked() {
        assert!(matches!(
            undo("{not json}"),
            Err(DocumentError::Serialization(_))
        ));
        assert!(matches!(
            status("42"),
            Err(DocumentError::Serialization(_))
        ));
    }

    #[test]
    fn recording_a_corrupt_document_is_rejected() {
        let (doc0, _) = base_doc();
        let h = init(&doc0).unwrap();
        assert!(matches!(
            record(&h, "{\"garbage\":true}"),
            Err(DocumentError::Serialization(_))
        ));
    }
}
