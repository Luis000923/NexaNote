//! Adaptador JNI: traduce entre el mundo Java/Kotlin y el núcleo Rust.
//!
//! Este es el **único** módulo con `unsafe` y el único que conoce JNI. La lógica
//! real vive en el resto del crate; aquí sólo se convierten tipos y se gestionan
//! los errores para que **ningún fallo cruce la frontera FFI sin control**.

use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use crate::document::{api, history, render, DocumentError};
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

/// `external fun documentAddStroke(documentJson: String, pageId: String, strokeJson: String): String`
///
/// Inserta un trazo a mano alzada (capturado por el stylus) en la página dada. El
/// núcleo sanea los puntos, la presión y el grosor antes de persistirlo.
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentAddStroke<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    stroke_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let stroke = read_string(&mut env, &stroke_json);
    run_api(&mut env, api::add_stroke(&doc, &page_id, &stroke))
}

/// `external fun documentAddShape(documentJson: String, pageId: String, shapeJson: String): String`
///
/// Inserta una forma geométrica (`Rectangle`, `Ellipse`, `Line`, `Arrow`) en la
/// página dada. El núcleo normaliza y valida los límites antes de persistirla.
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentAddShape<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    shape_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let shape = read_string(&mut env, &shape_json);
    run_api(&mut env, api::add_shape(&doc, &page_id, &shape))
}

/// `external fun documentAddText(documentJson: String, pageId: String, textJson: String): String`
///
/// Inserta un bloque de texto tipográfico en la página dada. El núcleo recorta el
/// contenido, exige que no quede vacío, satura el tamaño de fuente y valida la
/// posición antes de persistirlo.
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentAddText<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    text_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let text = read_string(&mut env, &text_json);
    run_api(&mut env, api::add_text(&doc, &page_id, &text))
}

/// `external fun documentAddFormula(documentJson: String, pageId: String, formulaJson: String): String`
///
/// Inserta una fórmula matemática estructurada en la página dada. El núcleo
/// parsea la expresión a un AST tipado con el motor matemático; un fallo de
/// sintaxis se propaga como IllegalStateException, nunca como `panic`.
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentAddFormula<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    formula_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let formula = read_string(&mut env, &formula_json);
    run_api(&mut env, api::add_formula(&doc, &page_id, &formula))
}

/// `external fun documentAddGraph(documentJson: String, pageId: String, graphJson: String): String`
///
/// Inserta una gráfica de función `y = f(var)` sobre un dominio. El núcleo parsea
/// la función a un AST y valida marco, dominio y símbolos; el muestreo numérico de
/// la curva ocurre al construir la escena, nunca en el hilo de UI.
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentAddGraph<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    graph_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let graph = read_string(&mut env, &graph_json);
    run_api(&mut env, api::add_graph(&doc, &page_id, &graph))
}

/// `external fun documentAddImage(documentJson: String, pageId: String, imageJson: String): String`
///
/// Inserta una imagen (ya copiada al almacén local de la app) en la página dada.
/// El núcleo valida que la ruta sea relativa y sin travesía, y que el marco y las
/// dimensiones intrínsecas sean sensatos, antes de persistirla.
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentAddImage<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    image_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let image = read_string(&mut env, &image_json);
    run_api(&mut env, api::add_image(&doc, &page_id, &image))
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

// ---------------------------------------------------------------------------
// Selección de área, manipulación en lote y color/relleno (Fase 13).
// ---------------------------------------------------------------------------

/// `external fun documentSelectInArea(documentJson: String, pageId: String, areaJson: String): String`
///
/// Devuelve `{"ids":[...],"bounds":{...}|null}` con los elementos contenidos por
/// completo en el área. La UI no decide qué cae dentro: sólo entrega el marco.
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentSelectInArea<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    area_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let area = read_string(&mut env, &area_json);
    run_api(&mut env, api::select_in_area(&doc, &page_id, &area))
}

/// `external fun documentSelectAt(documentJson: String, pageId: String, x: Float, y: Float): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentSelectAt<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    x: jni::sys::jfloat,
    y: jni::sys::jfloat,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    run_api(&mut env, api::select_at(&doc, &page_id, x, y))
}

/// `external fun documentRemoveElements(documentJson: String, pageId: String, idsJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentRemoveElements<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    ids_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let ids = read_string(&mut env, &ids_json);
    run_api(&mut env, api::remove_elements(&doc, &page_id, &ids))
}

/// `external fun documentTranslateElements(documentJson: String, pageId: String, idsJson: String, dx: Float, dy: Float): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentTranslateElements<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    ids_json: JString<'local>,
    dx: jni::sys::jfloat,
    dy: jni::sys::jfloat,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let ids = read_string(&mut env, &ids_json);
    run_api(&mut env, api::translate_elements(&doc, &page_id, &ids, dx, dy))
}

/// `external fun documentDuplicateElements(documentJson: String, pageId: String, idsJson: String, dx: Float, dy: Float): String`
///
/// Devuelve `{"document":"<json>","selection":{...}}`: el documento con las copias
/// y la selección ya movida a ellas.
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentDuplicateElements<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    ids_json: JString<'local>,
    dx: jni::sys::jfloat,
    dy: jni::sys::jfloat,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let ids = read_string(&mut env, &ids_json);
    run_api(&mut env, api::duplicate_elements(&doc, &page_id, &ids, dx, dy))
}

/// `external fun documentSetElementsColor(documentJson: String, pageId: String, idsJson: String, colorJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentSetElementsColor<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_id: JString<'local>,
    ids_json: JString<'local>,
    color_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let page_id = read_string(&mut env, &page_id);
    let ids = read_string(&mut env, &ids_json);
    let color = read_string(&mut env, &color_json);
    run_api(&mut env, api::set_elements_color(&doc, &page_id, &ids, &color))
}

/// `external fun documentRenderPage(documentJson: String, pageIndex: Int): String`
///
/// Devuelve la **escena plana** (JSON `ScenePage`) de la página indicada, lista
/// para que el Canvas de Compose la pinte sin conocer el modelo.
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_documentRenderPage<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
    page_index: jni::sys::jint,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    let index = if page_index < 0 { 0usize } else { page_index as usize };
    run_api(&mut env, render::render_page(&doc, index))
}

// ---------------------------------------------------------------------------
// Historial de edición (Fase 9). El historial viaja como su propio blob JSON,
// igual que el documento: el núcleo sigue siendo *stateless*.
// ---------------------------------------------------------------------------

/// `external fun historyInit(documentJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_historyInit<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    document_json: JString<'local>,
) -> jstring {
    let doc = read_string(&mut env, &document_json);
    run_api(&mut env, history::init(&doc))
}

/// `external fun historyRecord(historyJson: String, documentJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_historyRecord<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    history_json: JString<'local>,
    document_json: JString<'local>,
) -> jstring {
    let hist = read_string(&mut env, &history_json);
    let doc = read_string(&mut env, &document_json);
    run_api(&mut env, history::record(&hist, &doc))
}

/// `external fun historyUndo(historyJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_historyUndo<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    history_json: JString<'local>,
) -> jstring {
    let hist = read_string(&mut env, &history_json);
    run_api(&mut env, history::undo(&hist))
}

/// `external fun historyRedo(historyJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_historyRedo<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    history_json: JString<'local>,
) -> jstring {
    let hist = read_string(&mut env, &history_json);
    run_api(&mut env, history::redo(&hist))
}

/// `external fun historyDocument(historyJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_historyDocument<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    history_json: JString<'local>,
) -> jstring {
    let hist = read_string(&mut env, &history_json);
    run_api(&mut env, history::document(&hist))
}

/// `external fun historyStatus(historyJson: String): String` -> `"<undo>,<redo>"`
#[no_mangle]
pub extern "system" fn Java_com_nexanote_core_NativeBridge_historyStatus<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    history_json: JString<'local>,
) -> jstring {
    let hist = read_string(&mut env, &history_json);
    run_api(&mut env, history::status(&hist))
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
