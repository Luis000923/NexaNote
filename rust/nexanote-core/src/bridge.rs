//! Adaptador JNI: traduce entre el mundo Java/Kotlin y el núcleo Rust.
//!
//! Este es el **único** módulo con `unsafe` y el único que conoce JNI. La lógica
//! real vive en el resto del crate; aquí sólo se convierten tipos y se gestionan
//! los errores para que **ningún fallo cruce la frontera FFI sin control**.

use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use crate::{greeting, CORE_VERSION};

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
