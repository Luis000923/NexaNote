//! Adaptador JNI: traduce entre el mundo Java/Kotlin y el núcleo Rust.
//!
//! Este es el **único** módulo con `unsafe` y el único que conoce JNI. La lógica
//! real vive en el resto del crate; aquí sólo se convierten tipos y se gestionan
//! los errores para que **ningún fallo cruce la frontera FFI sin control**.

use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use crate::document::{api, DocumentError};
use crate::{greeting, CORE_VERSION};

/// Traduce un [`DocumentError`] a una excepción Java y devuelve un `jstring` nulo.
///
/// Se elige `IllegalStateException` como excepción genérica del dominio; el
/// mensaje incluye la variante para diagnóstico. **Ningún error cruza la frontera
/// sin control.**
fn throw_doc_error(env: &mut JNIEnv, err: &DocumentError) -> jstring {
    let _ = env.throw_new("java/lang/IllegalStateException", format!("nexanote-core: {err}"));
    JObject::null().into_raw()
}

/// Ejecuta una operación de la capa `api` (`&str... -> Result<String, _>`) y la
/// convierte en `jstring`, propagando errores como excepción Java.
fn run_api<'local>(
    env: &mut JNIEnv<'local>,
    result: Result<String, DocumentError>,
) -> jstring {
    match result {
        Ok(json) => to_jstring(env, &json),
        Err(e) => throw_doc_error(env, &e),
    }
}

/// Convierte un `&str` de Rust en un `jstring` de Java.
///
/// Si la creación de la cadena falla, lanza una excepción Java y devuelve un
/// puntero nulo (el patrón esperado por JNI ante error).
fn to_jstring(env: &mut JNIEnv, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            let _ = env.throw_new("java/lang/RuntimeException", format!("core: {e}"));
            JObject::null().into_raw()
        }
    }
}

/// Lee un `JString` como `String` de Rust, con valor por defecto ante error.
fn read_string(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value)
        .map(|s| s.into())
        .unwrap_or_default()
}

/// `external fun greeting(name: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_greeting<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    name: JString<'local>,
) -> jstring {
    let name = read_string(&mut env, &name);
    to_jstring(&mut env, &greeting(&name))
}

/// `external fun coreVersion(): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_coreVersion<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    to_jstring(&mut env, CORE_VERSION)
}

// ---------------------------------------------------------------------------
// Modelo de documento (Fase 2). Superficie JSON: cada método recibe y devuelve
// cadenas JSON. Los errores se lanzan como IllegalStateException.
// ---------------------------------------------------------------------------

/// `external fun documentCreate(title: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentCreate<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    title: JString<'local>,
) -> jstring {
    let title = read_string(&mut env, &title);
    run_api(&mut env, api::create_document(&title))
}

/// `external fun documentAddPage(documentJson: String, pageSpecJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentAddPage<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_spec_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let spec = read_string(&mut env, &page_spec_json);
    run_api(&mut env, api::add_page(&doc, &spec))
}

/// `external fun documentRemovePage(documentJson: String, pageId: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentRemovePage<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    run_api(&mut env, api::remove_page(&doc, &page_id))
}

/// `external fun documentAddElement(documentJson: String, pageId: String, elementJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentAddElement<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    element_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let element = read_string(&mut env, &element_json);
    run_api(&mut env, api::add_element(&doc, &page_id, &element))
}

/// `external fun documentRemoveElement(documentJson: String, pageId: String, elementId: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentRemoveElement<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    element_id: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let element_id = read_string(&mut env, &element_id);
    run_api(&mut env, api::remove_element(&doc, &page_id, &element_id))
}

/// `external fun documentTranslatePageElements(documentJson: String, pageId: String, dx: Float, dy: Float): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentTranslatePageElements<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    dx: jni::sys::jfloat,
    dy: jni::sys::jfloat,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    run_api(
        &mut env,
        api::translate_page_elements(&doc, &page_id, dx, dy),
    )
}

/// `external fun documentSummary(documentJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentSummary<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    run_api(&mut env, api::summary(&doc))
}
