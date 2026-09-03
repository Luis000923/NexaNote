//! Errores del modelo de documento. Todos son `Clone` y serializables para poder
//! cruzar la frontera FFI como datos si hiciera falta.

use std::fmt;

use serde::{Deserialize, Serialize};

/// Error recuperable de una operación sobre el modelo de documento.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "detail")]
pub enum DocumentError {
    /// No existe una página con el identificador dado.
    PageNotFound(String),
    /// No existe un elemento con el identificador dado.
    ElementNotFound(String),
    /// Índice fuera de rango al insertar/mover.
    IndexOutOfBounds { index: usize, len: usize },
    /// Un identificador no tiene formato válido.
    InvalidId(String),
    /// Fallo al (de)serializar JSON en la capa de API/FFI.
    Serialization(String),
    /// Parámetro inválido (p. ej. dimensiones no positivas).
    InvalidArgument(String),
}

impl fmt::Display for DocumentError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::PageNotFound(id) => write!(f, "página no encontrada: {id}"),
            Self::ElementNotFound(id) => write!(f, "elemento no encontrado: {id}"),
            Self::IndexOutOfBounds { index, len } => {
                write!(f, "índice {index} fuera de rango (longitud {len})")
            }
            Self::InvalidId(s) => write!(f, "identificador inválido: {s}"),
            Self::Serialization(s) => write!(f, "error de serialización: {s}"),
            Self::InvalidArgument(s) => write!(f, "argumento inválido: {s}"),
        }
    }
}

impl std::error::Error for DocumentError {}

/// Alias corto para los resultados del módulo.
pub type DocResult<T> = Result<T, DocumentError>;
