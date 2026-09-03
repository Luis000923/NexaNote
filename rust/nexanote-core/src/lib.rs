//! Núcleo de NexaNote.
//!
//! En esta fase sólo contiene la lógica mínima para validar el puente Kotlin↔Rust.
//! Los motores futuros (documento, dibujo, matemáticas, gráficas, OCR, IA,
//! exportación) se añadirán como submódulos con fronteras claras.
//!
//! Reglas (ver `claude.md`):
//!  - Sin `panic!` / `unwrap()` en rutas de producción.
//!  - `unsafe` sólo en el adaptador JNI ([`bridge`]), acotado y justificado.

#![deny(warnings)]

pub mod bridge;

/// Versión pública del núcleo. La UI la muestra para diagnóstico.
pub const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Construye el saludo de prueba del puente.
///
/// Es la única "lógica" del núcleo en esta fase: sirve para demostrar que los
/// datos cruzan el FFI de ida y vuelta sin corromperse.
pub fn greeting(name: &str) -> String {
    let name = name.trim();
    if name.is_empty() {
        "Hola 👋".to_string()
    } else {
        format!("Hola, {name} 👋")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn greeting_with_name() {
        assert_eq!(greeting("NexaNote"), "Hola, NexaNote 👋");
    }

    #[test]
    fn greeting_trims_whitespace() {
        assert_eq!(greeting("  NexaNote  "), "Hola, NexaNote 👋");
    }

    #[test]
    fn greeting_empty_falls_back() {
        assert_eq!(greeting("   "), "Hola 👋");
    }

    #[test]
    fn core_version_is_semver_like() {
        assert_eq!(CORE_VERSION.split('.').count(), 3);
    }
}
