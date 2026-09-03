package com.nexanote.core

/**
 * Único punto de contacto entre la capa UI (Kotlin/Compose) y el núcleo NexaNote
 * escrito en Rust. Toda comunicación con el núcleo pasa por aquí.
 *
 * Reglas (ver `claude.md`):
 *  - Este objeto es el único lugar que hace [System.loadLibrary] y declara
 *    métodos `external`.
 *  - La superficie se mantiene mínima, explícita y versionable.
 *  - El resto del código depende de [NativeCore], no de este objeto directamente.
 */
object NativeBridge : NativeCore {

    /** `true` si `libnexanote_core` se cargó correctamente. */
    val isLoaded: Boolean

    init {
        isLoaded = runCatching { System.loadLibrary("nexanote_core") }.isSuccess
    }

    /**
     * Función de prueba del puente: devuelve un saludo generado por el núcleo Rust
     * a partir de [name]. Sirve para validar que el FFI está operativo.
     */
    external override fun greeting(name: String): String

    /** Versión del núcleo Rust (cadena semver), útil para diagnóstico. */
    external override fun coreVersion(): String
}

/** Contrato del núcleo. La UI depende de esta interfaz, no de la implementación JNI. */
interface NativeCore {
    fun greeting(name: String): String
    fun coreVersion(): String
}
