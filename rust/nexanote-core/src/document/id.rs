//! Identificadores únicos para documentos, páginas y elementos.
//!
//! Un [`Id`] es un `u128` = `(unix_millis << 64) | contador_monótono`. Esto da
//! unicidad práctica sin dependencias externas (nada de `uuid`/`getrandom`) y
//! mantiene el orden de creación. Se serializa como cadena hexadecimal para no
//! perder precisión en consumidores JSON de 53 bits (JavaScript, etc.).

use std::fmt;
use std::str::FromStr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Deserializer, Serialize, Serializer};

use super::error::DocumentError;

static COUNTER: AtomicU64 = AtomicU64::new(1);

/// Identificador opaco y ordenable.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Id(u128);

impl Id {
    /// Genera un nuevo identificador único.
    pub fn generate() -> Self {
        let millis = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0);
        let counter = COUNTER.fetch_add(1, Ordering::Relaxed) as u128;
        Id((millis << 64) | counter)
    }

    /// Valor numérico subyacente (útil para tests y FFI de bajo nivel).
    pub fn value(self) -> u128 {
        self.0
    }

    /// Construye un `Id` a partir de su valor numérico (para deserialización).
    pub fn from_value(value: u128) -> Self {
        Id(value)
    }
}

impl fmt::Display for Id {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:032x}", self.0)
    }
}

impl FromStr for Id {
    type Err = DocumentError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let trimmed = s.trim().trim_start_matches("0x");
        u128::from_str_radix(trimmed, 16)
            .map(Id)
            .map_err(|_| DocumentError::InvalidId(s.to_string()))
    }
}

impl Serialize for Id {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.to_string())
    }
}

impl<'de> Deserialize<'de> for Id {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let raw = String::deserialize(deserializer)?;
        raw.parse().map_err(serde::de::Error::custom)
    }
}

/// Identificador de documento.
pub type DocumentId = Id;
/// Identificador de página.
pub type PageId = Id;
/// Identificador de elemento.
pub type ElementId = Id;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_are_unique_and_monotonic() {
        let a = Id::generate();
        let b = Id::generate();
        assert_ne!(a, b);
        assert!(b > a);
    }

    #[test]
    fn id_roundtrips_through_string() {
        let id = Id::generate();
        let text = id.to_string();
        assert_eq!(text.len(), 32);
        assert_eq!(text.parse::<Id>().unwrap(), id);
    }

    #[test]
    fn invalid_id_is_rejected() {
        assert!("zzz".parse::<Id>().is_err());
    }

    #[test]
    fn id_serializes_as_json_string() {
        let id = Id::from_value(0x2a);
        let json = serde_json::to_string(&id).unwrap();
        assert_eq!(json, "\"0000000000000000000000000000002a\"");
        assert_eq!(serde_json::from_str::<Id>(&json).unwrap(), id);
    }
}
