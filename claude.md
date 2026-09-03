# NexaNote — Reglas estrictas de desarrollo y arquitectura

> Este documento es **vinculante**. Cualquier contribución (humana o asistida por IA)
> debe respetarlo. Ante la duda, se prioriza lo aquí escrito.

---

## 1. Visión del proyecto

NexaNote es una aplicación Android de **cuaderno digital avanzado**, orientada
principalmente a **tablets con lápiz/stylus**. No es una aplicación CRUD: es una
aplicación **gráfica y documental compleja** que unificará en un único motor
coherente:

- Escritura manuscrita de alta frecuencia de muestreo y baja latencia.
- Dibujo, formas y diagramas.
- Procesamiento matemático simbólico y numérico.
- Gráficas de funciones.
- Gestión de imágenes y OCR.
- Componentes reutilizables e IA integrada de forma desacoplada.
- Historial avanzado, versionado, autosave offline-first y exportación a PDF.

## 2. Arquitectura

```
┌─────────────────────────────────────────────┐
│  UI  ·  Kotlin + Jetpack Compose (app/)      │  ← sólo presentación e interacción
├─────────────────────────────────────────────┤
│  Puente  ·  JNI / FFI (com.nexanote.core)    │  ← superficie mínima y estable
├─────────────────────────────────────────────┤
│  Núcleo  ·  Rust (rust/nexanote-core/)       │  ← lógica de negocio y motores
└─────────────────────────────────────────────┘
```

- **La lógica de negocio y los motores críticos residen en Rust.** La capa Kotlin
  no debe contener lógica de dominio: orquesta UI y delega en el núcleo.
- **La comunicación Kotlin↔Rust se hace vía JNI/FFI**, a través de una superficie
  reducida, explícita y versionable (`com.nexanote.core.NativeBridge`).
- Cada motor futuro (documento, dibujo, matemáticas, gráficas, OCR, IA,
  exportación) será un **módulo Rust independiente** dentro del workspace, con
  fronteras claras y sin dependencias circulares.
- La capa UI y la capa núcleo permanecen **aisladas**: se comunican por datos
  planos (structs serializables / tipos primitivos), nunca por objetos con
  comportamiento que crucen la frontera.

## 3. Reglas del código nativo (Rust)

1. **Cero `panic!` / `unwrap()` / `expect()` imprudentes.** En código que puede
   fallar en producción se usa `Result<T, E>` y se propaga el error. `unwrap()`
   sólo se admite en tests o cuando la invariante es local y está comentada.
2. **La frontera FFI nunca hace unwind.** Toda función `extern` expuesta a JNI
   captura sus errores y los traduce a un valor/excepción Java bien definido; un
   `panic` que cruce FFI es *undefined behaviour* y está prohibido.
3. **Modularidad:** un módulo, una responsabilidad. Sin `mod.rs` gigantes.
4. **Rendimiento:** evitar asignaciones y copias en rutas calientes (input del
   stylus, render). Medir antes de optimizar; documentar los *trade-offs*.
5. **Seguridad de memoria:** nada de `unsafe` salvo en el adaptador JNI, y ahí
   acotado, comentado y justificado.
6. **`#![deny(warnings)]` en CI.** El código entra sin warnings.
7. **Tests:** cada módulo con lógica lleva tests unitarios (`#[cfg(test)]`).

## 4. Reglas de la capa Kotlin / Compose

1. Sin lógica de dominio: sólo estado de UI, navegación y llamadas al puente.
2. `NativeBridge` es el **único** punto que hace `System.loadLibrary` y declara
   métodos `external`. El resto del código depende de una interfaz, no del JNI.
3. Composables puros y sin efectos colaterales ocultos; estado elevado.
4. Sin bloqueos del hilo principal: el trabajo del núcleo va fuera del main thread.

## 5. Dependencias

- **Minimalismo.** No se añaden librerías de terceros salvo que sean
  estrictamente necesarias.
- Fase actual: sólo lo esencial para **Compose** y para **JNI**.
  - Rust: crate [`jni`](https://crates.io/crates/jni) — estándar de facto para el
    adaptador JNI; se considera esencial y se mantiene acotado al módulo puente.
- Toda dependencia nueva se justifica en el PR (por qué, alternativas, coste).

## 6. Git y flujo de trabajo

- Repositorio remoto: <https://github.com/Luis000923/NexaNote>
- Rama principal: `main`.
- **Al concluir cada fase u objetivo es OBLIGATORIO hacer `commit` + `push`** al
  remoto. Ninguna fase se considera terminada sin push exitoso.
- Mensajes de commit descriptivos, en imperativo, agrupados por fase.
- No se commitea: artefactos de build, `local.properties`, `.so` que no sean
  necesarios para el repo, claves ni secretos.

## 7. Testing y validación

- Rust: `cargo test` verde.
- Android: al menos un test instrumentado que valide la **carga de la librería
  nativa** y la respuesta del puente.
- Cada fase se cierra comprobando por **compilación** que el proyecto se arma sin
  errores y que el puente nativo responde en la interfaz.

## 8. Qué NO hacer

- No implementar lógica de negocio compleja fuera del núcleo Rust.
- No mezclar responsabilidades UI/núcleo.
- No introducir `panic`/`unwrap` en rutas de producción del código nativo.
- No añadir dependencias no esenciales.
- No cerrar una fase sin push al repositorio remoto.
