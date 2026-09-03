# NexaNote

Cuaderno digital avanzado para Android (tablet + stylus). Núcleo en **Rust**,
interfaz en **Kotlin + Jetpack Compose**, comunicación por **JNI/FFI**.

> Las reglas de arquitectura y desarrollo son **vinculantes**: ver [`claude.md`](claude.md).

## Estructura

```
NexaNote/
├── app/                       # Aplicación Android (Kotlin + Compose)
│   └── src/main/java/com/nexanote/
│       ├── app/               # UI: Activities, ViewModel y pantallas Compose
│       ├── app/canvas/        # Lienzo de documento (Compose Canvas) + modelo de render
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

- **Rust:** `cd rust && cargo test` — tests unitarios del núcleo (incl. `render`).
- **Android (JVM):** `./gradlew :app:testDebugUnitTest` — aritmética de
  `CanvasTransform`.
- **Android (instrumentado):** `./gradlew :app:connectedDebugAndroidTest` — carga
  de `libnexanote_core`, respuesta del puente y comunicación ViewModel ↔ núcleo
  para los datos de render (requiere dispositivo/emulador).

## Puente de comunicación

`com.nexanote.core.NativeBridge` es la única frontera JNI (resuelta contra
`rust/nexanote-core/src/bridge.rs`). Superficie actual:

- `greeting(name)`, `coreVersion()` — prueba de vida del FFI.
- Modelo de documento (Fase 2): `documentCreate`, `documentAddPage`,
  `documentAddElement`, `documentRemove*`, `documentTranslatePageElements`,
  `documentSummary`.
- Render (Fase 3): `documentRenderPage(documentJson, pageIndex)` devuelve una
  **escena plana** (`ScenePage`: tamaño en px, plantilla y primitivas ordenadas)
  que el `DocumentCanvas` de Compose pinta sin interpretar el modelo.

## Fase 3 — Visualización

`MainActivity → DocumentScreen` carga un documento de ejemplo construido **en el
núcleo Rust** (`SampleDocument`) y lo pinta con `DocumentCanvas`:

- Límites de página (A4) y plantilla (cuadrícula / renglones / puntos / blanca).
- Trazos y formas (rectángulo, elipse, línea, flecha), texto y fórmula.
- Zoom y desplazamiento por gestos (`detectTransformGestures`) y por controles.
- Interfaz 100 % vectorial: iconos propios en `NexaIcons` (`ImageVector`), sin
  emojis ni `material-icons-extended`.

La aritmética de zoom/pan vive en `CanvasTransform` (clase pura, con tests JVM).
