# NexaNote

Cuaderno digital avanzado para Android (tablet + stylus). Núcleo en **Rust**,
interfaz en **Kotlin + Jetpack Compose**, comunicación por **JNI/FFI**.

> Las reglas de arquitectura y desarrollo son **vinculantes**: ver [`claude.md`](claude.md).

## Estructura

```
NexaNote/
├── app/                       # Aplicación Android (Kotlin + Compose)
│   └── src/main/java/com/nexanote/
│       ├── app/               # UI: Activities y Composables
│       └── core/              # Puente JNI (NativeBridge) — única frontera con Rust
├── rust/                      # Núcleo nativo (workspace Cargo)
│   ├── nexanote-core/         # Lógica + adaptador JNI (módulo `bridge`)
│   └── build-android.sh       # Cross-compila el núcleo a jniLibs/<abi>/
└── claude.md                  # Reglas estrictas de arquitectura y desarrollo
```

La capa UI **no contiene lógica de dominio**: orquesta y delega en el núcleo Rust.

## Requisitos

- Android SDK 34 + NDK (r26+), JDK 17
- Rust estable + targets:
  `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android`
- `ANDROID_NDK_HOME` (o NDK bajo `$ANDROID_HOME/ndk/`)

## Compilar

```bash
# Núcleo Rust: tests en host
cd rust && cargo test

# App Android (el build invoca build-android.sh automáticamente en preBuild)
./gradlew :app:assembleDebug
```

## Tests

- **Rust:** `cd rust && cargo test` — tests unitarios del núcleo.
- **Android:** `./gradlew :app:connectedDebugAndroidTest` — valida la carga de
  `libnexanote_core` y la respuesta del puente (requiere dispositivo/emulador).

## Puente de comunicación

`com.nexanote.core.NativeBridge` expone `greeting(name)` y `coreVersion()`,
resueltas por JNI contra `rust/nexanote-core/src/bridge.rs`. `MainActivity`
muestra el saludo devuelto por el núcleo como prueba de vida del FFI.
