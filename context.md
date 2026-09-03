# NexaNote — Mapa de Contexto del Proyecto

> Documento generado por inspección del repositorio (Fase 9). Su propósito es
> servir de mapa de arquitectura para futuras sesiones de desarrollo sin tener
> que re-explorar todo el árbol. **No sustituye a [`claude.md`]**, que es el
> documento vinculante de reglas.
>
> Última actualización: tras la Fase 9 (historial Undo/Redo + autosave).
>
> **Nota:** en el árbol de trabajo hay cambios **sin commit** de una Fase 10 en
> curso (elemento **imagen** importada: `ElementKind::Image` / `ImageRef`,
> `api::add_image`, `documentAddImage` en el puente, con almacenamiento local de
> activos). Se señalan abajo como «(Fase 10, en curso)» donde aplica.

---

## 1. Resumen del Proyecto

**NexaNote** es una aplicación Android de **cuaderno digital avanzado**, orientada
a **tablets con lápiz/stylus**. No es una app CRUD: es una aplicación gráfica y
documental compleja que unifica en un único motor: escritura manuscrita de alta
frecuencia, dibujo y formas, matemáticas simbólicas/numéricas, gráficas de
funciones, imágenes/OCR (futuro), IA desacoplada (futuro), historial/versionado,
autosave offline-first y exportación a PDF (futuro).

### Stack tecnológico actual

| Capa | Tecnología | Ubicación |
|---|---|---|
| UI | Kotlin + Jetpack Compose (Material 3) | `app/src/main/java/com/nexanote/app/` |
| Puente | JNI / FFI con marshalling **JSON por cadenas** | `app/.../core/NativeBridge.kt` ↔ `rust/.../bridge.rs` |
| Núcleo | Rust (workspace Cargo, crate `nexanote-core`) | `rust/nexanote-core/src/` |

- **AGP** 8.5.2 · **Kotlin** 2.0.20 · **Compose BOM** 2024.09.02 · **JDK** 17
- **compileSdk/targetSdk** 34 · **minSdk** 26
- ABIs del núcleo: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
- Rust deps (mínimas y justificadas): `jni` 0.21 (acotado a `bridge`), `serde` 1 + `serde_json` 1
- Sin librerías de terceros en UI más allá de Compose (iconos vectoriales propios; sin `material-icons-extended`)

### Fases completadas

1–3: Andamiaje del puente · modelo de documento · render a escena plana y visualización.
4: Captura de stylus / escritura manuscrita. 5: Formas geométricas. 6: Texto tipográfico.
7: Motor matemático (parser + AST). 8: Gráficas de funciones (muestreo en el núcleo).
**9: Historial Undo/Redo (instantáneas acotadas) + autosave / recuperación de sesión.**
10: Imágenes en el núcleo + selector nativo en Android.
**11: Exportación a PDF multipágina (`app/.../pdf/`, API nativa `PdfDocument` sobre la escena del núcleo).**
**12: Integración de IA con credenciales del usuario cifradas (`app/.../ai/`).**

---

## 2. Mapa de Directorios

```
NexaNote/
├── claude.md                     # Reglas vinculantes de arquitectura y desarrollo
├── context.md                    # (este archivo) mapa de contexto
├── README.md                     # Resumen público, comandos de build/test
├── build.gradle.kts              # Raíz: declara plugins (apply false)
├── settings.gradle.kts           # include(":app"), repos
├── gradle/
│   ├── libs.versions.toml        # Version catalog (única fuente de versiones)
│   └── wrapper/                   # gradle-wrapper 8.9 (comprometido)
│
├── app/                          # Módulo Android
│   ├── build.gradle.kts          # Config app + task `buildRustCore` (preBuild → build-android.sh)
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml        # 1 Activity (MainActivity), sin permisos
│       │   ├── res/values/                # strings.xml (app_name), themes.xml (Theme.NexaNote)
│       │   └── java/com/nexanote/
│       │       ├── core/
│       │       │   └── NativeBridge.kt    # ÚNICA frontera JNI + interfaz NativeCore
│       │       └── app/
│       │           ├── MainActivity.kt        # ComponentActivity; inyecta DocumentStore; flush() en onPause
│       │           ├── DocumentScreen.kt      # Pantalla Compose: Scaffold, TopAppBar (Undo/Redo), paleta, controles
│       │           ├── DocumentViewModel.kt   # Estado UI, orquesta núcleo, historial, autosave
│       │           ├── DocumentHistory.kt     # Gestión Undo/Redo (delegado del ViewModel)
│       │           ├── DocumentStore.kt       # Autosave: interfaz + FileDocumentStore + NoOp
│       │           └── canvas/
│       │               ├── DocumentCanvas.kt  # Compose Canvas: pinta ScenePage, captura trazos/formas, gestos
│       │               ├── CanvasTransform.kt # Aritmética pura de pan/zoom (tests JVM)
│       │               ├── RenderModels.kt    # ScenePage / SceneTemplate / ScenePrimitive (modelo Kotlin de render)
│       │               ├── SceneParser.kt     # JSON del núcleo → RenderModels (mapeo puro de datos)
│       │               ├── SampleDocument.kt  # Construye el documento de ejemplo EN el núcleo; firstPageId()
│       │               ├── NexaIcons.kt       # Iconos ImageVector propios (Undo, Redo, Pen, Hand, Shape*, etc.)
│       │               ├── StrokeInput.kt     # DrawingTool enum, StrokeColor/Sample, StrokeGesture (captura → JSON)
│       │               ├── ShapeInput.kt      # ShapeKind, ShapeBounds, ShapeGeometry (bounds + JSON)
│       │               ├── TextInput.kt       # Validación y serialización de bloques de texto
│       │               ├── FormulaInput.kt    # Validación y serialización de fórmulas
│       │               └── GraphInput.kt      # Validación de función/dominio + serialización de gráficas
│       ├── test/java/com/nexanote/app/        # Tests JVM puros (junit4)
│       │   ├── DocumentHistoryTest.kt         # Undo/Redo con FakeCore (semántica de instantáneas en memoria)
│       │   └── canvas/*Test.kt                # CanvasTransform, ShapeInput, TextInput, FormulaInput, GraphInput, StrokeInput
│       └── androidTest/java/com/nexanote/app/ # Instrumentados (requieren dispositivo/emulador)
│           ├── NativeBridgeTest.kt            # Carga de .so, greeting, modelo, historyUndo/Redo
│           └── DocumentRenderBridgeTest.kt    # UI/ViewModel ↔ núcleo para datos de render
│
└── rust/                          # Workspace Cargo
    ├── Cargo.toml                 # members = ["nexanote-core"], profile.release (lto, panic=abort, strip)
    ├── .cargo/config.toml         # Nota: linkers Android por env desde build-android.sh
    ├── build-android.sh           # Cross-compila a jniLibs/<abi>/ (busca NDK en ANDROID_NDK_HOME, etc.)
    └── nexanote-core/
        ├── Cargo.toml             # crate-type = ["rlib", "cdylib"]; deps jni, serde, serde_json
        └── src/
            ├── lib.rs             # #![deny(warnings)]; greeting(), CORE_VERSION; mods bridge + document
            ├── bridge.rs          # Adaptador JNI (ÚNICO unsafe): Java_com_nexanote_core_NativeBridge_*
            └── document/
                ├── mod.rs         # Reexports; now_ms(); integration_tests
                ├── error.rs       # DocumentError (enum serializable) + DocResult
                ├── id.rs          # Id = u128 (millis<<64 | contador); hex de 32 chars en JSON
                ├── geometry.rs    # Point, Rect, Color (RGBA u8), trait Transformable (translate/scale)
                ├── element.rs     # Element/ElementKind (Stroke, Text, Formula, Shape, Graph), FormulaNode (AST)
                ├── page.rs        # Page, PageSize (A4/A5/Letter/Custom en mm), PageTemplate (Blank/Grid/Ruled/Dotted)
                ├── model.rs       # Document (id + metadata + Vec<Page>), SCHEMA_VERSION, to_json/from_json
                ├── api.rs         # Capa stateless JSON: create/add_page/add_element/add_stroke/shape/text/formula/graph/…
                ├── math.rs        # Parser descenso recursivo + evaluador del AST; sample_function (muestreo de curva)
                ├── render.rs      # Modelo → ScenePage plana (mm→px, z-order, muestreo de gráficas, cuadrículas)
                └── history.rs     # Historial Undo/Redo por instantáneas acotadas (Fase 9)
```

**No versionado** (`.gitignore`): `build/`, `/rust/target/`, `/app/src/main/jniLibs/`
(los `.so` se recompilan), `local.properties`, `.idea/`, `*.apk/*.aab/*.keystore`,
`/PROMT/`, `/contex.md`.

---

## 3. Núcleo de Rust (`rust/nexanote-core`)

Regla transversal: **cero `panic!`/`unwrap()`/`expect()`** en rutas de producción;
todo fallo se propaga como `Result`. `#![deny(warnings)]`. Un módulo, una
responsabilidad. `unsafe` sólo en `bridge.rs`.

### `lib.rs`
Raíz del crate. Expone `greeting(name)` (prueba de vida del FFI), la constante
`CORE_VERSION` (= versión de `Cargo.toml`), y declara los módulos `bridge` y
`document`.

### `bridge.rs` — Adaptador JNI (única frontera `unsafe`)
- Traduce entre Java/Kotlin y el núcleo. **Ningún error cruza sin control**: todo
  `DocumentError` se convierte en `IllegalStateException` Java vía `throw_doc_error`;
  fallos de creación de `jstring` → `RuntimeException`.
- Helpers: `run_api` (ejecuta `Result<String, DocumentError>` → `jstring`),
  `to_jstring`, `read_string`.
- Cada función `Java_com_nexanote_core_NativeBridge_<nombre>` es un envoltorio
  fino: lee `JString`s, delega en `api::` / `render::` / `history::`, devuelve JSON.
- **No hace unwind**: los `panic` que cruzarían FFI están prohibidos por diseño.

### `document/mod.rs`
Fachada del motor de documento. Reexporta los tipos públicos. Provee
`now_ms()` (epoch ms, `0` si el reloj falla, sin propagar error). Contiene
`integration_tests` (ciclo de vida completo del documento y paridad
modelo-tipado ↔ capa API).

### `document/error.rs`
`DocumentError` — enum `#[serde(tag = "kind", content = "detail")]`, `Clone` y
serializable: `PageNotFound`, `ElementNotFound`, `IndexOutOfBounds{index,len}`,
`InvalidId`, `Serialization`, `InvalidArgument`. Alias `DocResult<T>`.

### `document/id.rs`
`Id(u128)` = `(unix_millis << 64) | contador_atómico_monótono`. Unicidad práctica
**sin dependencias** (`uuid`/`getrandom`), mantiene orden de creación. Se
serializa como **cadena hex de 32 caracteres** (evita pérdida de precisión en
JSON de 53 bits). Aliases: `DocumentId`, `PageId`, `ElementId`.

### `document/geometry.rs`
- `Point{x,y}` (f32, px lógicos @1x), `Rect{x,y,width,height}`, `Color{r,g,b,a}` (u8).
- `trait Transformable { translate(dx,dy); scale(factor, origin) }` — escalado
  uniforme respecto a un `origin` explícito (determinista). Implementado para
  `Point` y `Rect`.

### `document/element.rs`
- `StrokePoint{position, pressure∈[0,1], timestamp_ms}`, `Stroke{points, color, width}`.
- `TextStyle{font_size, bold, italic, underline, color}`, `TextBox{content, position, style, max_width?}`.
- `Formula{latex, position, ast: Option<FormulaNode>}`.
- `Graph{expression, ast?, var, frame: Rect, x_min, x_max, samples}` — **el muestreo
  de la curva NO se guarda**; lo hace `render` a partir del AST.
- `Shape{kind, bounds, stroke_color, fill_color?, stroke_width}`; `ShapeKind` ∈
  `{Rectangle, Ellipse, Line, Arrow}` (para Line/Arrow, `bounds` = extremos).
- `ImageRef{source, frame: Rect, natural_width, natural_height}` **(Fase 10, en
  curso)** — `source` es ruta **relativa** al almacén de activos de la app
  (`images/…`), nunca absoluta ni externa (portabilidad offline-first).
- `FormulaNode` — **AST** `#[serde(tag="node", content="value")]`: `Number`,
  `Symbol`, `Binary{op,lhs,rhs}`, `Fraction`, `Neg`, `Sqrt`, `Root{degree,radicand}`,
  `Sum{var,from,to,body}`, `Call{func,arg}`. `BinaryOp` ∈ `{Add,Sub,Mul,Div,Pow}`.
  `MathFunc` ∈ `{Sin,Cos,Tan,Ln,Log,Exp,Abs}`.
- `ElementKind` — `#[serde(tag = "type")]` ∈ `{Stroke, Text, Formula, Shape, Graph}`
  (+ `Image` en Fase 10, en curso).
- `Element{id, z_index, kind}`. `Transformable` despacha por variante.

### `document/page.rs`
- `PageSize` — `#[serde(tag="format", content="size")]` ∈ `{A4, A5, Letter, Custom{width_mm,height_mm}}`;
  `dimensions_mm()`, validación de dimensiones positivas.
- `PageTemplate` — `#[serde(tag="kind", content="spacing")]` ∈ `{Blank, Grid(f32), Ruled(f32), Dotted(f32)}`.
- `Page{id, size, template, elements: Vec<Element>}`. `add_element` asigna
  `z_index = max+1`. `element`/`element_mut`/`remove_element`/`element_count`.

### `document/model.rs`
- `SCHEMA_VERSION = 1`.
- `DocumentMetadata{title, created_ms, modified_ms, schema_version}`.
- `Document{id, metadata, pages: Vec<Page>}`. Operaciones: `add_page`,
  `insert_page`, `remove_page`, `move_page`, `page`/`page_mut` (marca `modified_ms`),
  `page_count`, `element_count`. `to_json`/`to_json_pretty`/`from_json`.

### `document/api.rs` — Capa stateless (superficie FFI)
Cada función recibe y devuelve **`String` JSON** y **nunca hace `panic`**. El
documento vive en Kotlin y viaja como JSON en cada llamada → capa trivialmente
testeable. El núcleo es la **única autoridad** sobre la validez de cada entrada
(la sanea/normaliza):

| Función | Notas de saneo |
|---|---|
| `create_document(title)` | — |
| `add_page(doc, page_spec)` | `{}` = A4 en blanco |
| `remove_page(doc, page_id)` | — |
| `add_element(doc, page_id, element)` | `ElementKind` etiquetado |
| `add_stroke(doc, page_id, stroke)` | descarta puntos no finitos, exige ≥1, satura presión, sube `width` a `MIN_STROKE_WIDTH` |
| `add_shape(doc, page_id, shape)` | normaliza rect/elipse a esquina+tamaño≥0, conserva signo de línea/flecha, rechaza degeneradas |
| `add_text(doc, page_id, text)` | recorta, exige no vacío, trunca a `MAX_TEXT_LEN`, satura `font_size`, descarta `max_width` inválido |
| `add_formula(doc, page_id, {expression, position})` | recorta, límite `MAX_FORMULA_LEN`, **parsea a AST** (fallo → `InvalidArgument`) |
| `add_graph(doc, page_id, GraphSpec)` | valida marco/dominio/`samples`, parsea AST, rechaza símbolos libres ≠ `var` |
| `add_image(doc, page_id, ImageSpec)` **(Fase 10, en curso)** | exige `source` relativa (`images/…`, sin `..`), marco finito y acotado, dimensiones intrínsecas positivas |
| `remove_element(doc, page_id, element_id)` | — |
| `translate_page_elements(doc, page_id, dx, dy)` | — |
| `summary(doc)` | `{id,title,schema_version,page_count,element_count}` |

### `document/math.rs` — Motor matemático
- **Parser** de descenso recursivo con precedencia clásica (`+ -` < `* /` <
  unario < `^`). Dos dialectos entremezclados: ASCII (`a/b`, `sqrt(x)`, `sin(x)`,
  `sum(i,1,n,i^2)`) y subconjunto LaTeX (`\frac{a}{b}`, `\sqrt[3]{x}`,
  `\sum_{i=1}^{n}`, `\cdot`, `\pi`, `\left(`…).
- **Acotado**: `MAX_DEPTH = 128`, `MAX_SUM_ITERS = 100_000` (entrada patológica no
  agota pila ni bloquea el hilo).
- `MathError` (serializable): `Empty`, `Syntax`, `TooDeep`, `UnknownSymbol`,
  `DivisionByZero`, `Domain`, `SumRange`, `NotFinite`. La capa `api` lo traduce a
  `DocumentError::InvalidArgument`.
- API: `parse(expr) -> FormulaNode`, `evaluate(node, vars) -> f64`,
  `parse_and_evaluate`, `free_symbols(node) -> BTreeSet<String>`,
  `sample_function(...) -> Vec<CurveSample{x,y}>` (muestreo numérico de la curva).

### `document/render.rs` — Capa de render
Traduce una `Page` del modelo a una **escena plana** que la UI pinta sin conocer
el modelo. Aquí vive **toda la lógica que "decide"**:
- `PX_PER_MM = 3.779_527_6` (96 dpi): única conversión mm→px (sólo el tamaño de
  página; los elementos ya están en px @1x).
- Orden de pintado por `z_index`; normalización de línea/flecha a extremos.
- Gráficas: muestrea la curva (`math::sample_function`), rango vertical robusto
  (percentiles 5–95, fuerza incluir `y=0`, evita que una asíntota reviente la
  escala), pasos "bonitos" `1/2/5·10^k` (`nice_step`), líneas de cuadrícula
  acotadas a 256 (`grid_lines`).
- `SceneTemplate` ∈ `{Blank, Grid{spacing_px}, Ruled{…}, Dotted{…}}`.
- `ScenePrimitive` — `#[serde(tag="type")]` ∈ `Polyline`, `Rect`, `Ellipse`,
  `Line`, `Arrow`, `Text`, `Formula`, `Graph` (con `grid_x/grid_y`, `axis_x/axis_y`,
  `polylines` — segmentos, la curva se parte en discontinuidades).
- `ScenePage{page_index, page_id, width_px, height_px, background, template, primitives}`.
- API: `build_scene(doc_json, page_index) -> ScenePage`,
  `render_page(...) -> String` (JSON, para el FFI).

### `document/history.rs` — Historial Undo/Redo (Fase 9)
- **Estrategia: instantáneas acotadas** (no registro de comandos). Motivos:
  agnóstico al tipo de operación, `undo`/`redo` O(1) sin re-ejecutar lógica (UI
  sin tirones), integridad trivial (una instantánea *es* un documento válido).
- `History { past: Vec<Value>, present: Value, future: Vec<Value>, limit }`.
  Instantáneas como `serde_json::Value` (sin JSON escapado anidado; comparar
  estados es directo). `DEFAULT_LIMIT = 250` por pila → memoria acotada; las
  instantáneas más antiguas se descartan por el frente.
- Stateless igual que `api`: el historial viaja como su propio blob JSON. Toda
  instantánea se valida con `Document::from_json` antes de guardarse.
- API (todas `&str -> DocResult<String>`, sin `panic`):
  - `init(document_json)` → historial anclado, nada que deshacer.
  - `record(history_json, document_json)` → empuja el estado anterior a `past`,
    **limpia `future`** (una edición nueva corta la rama de redo), recorta al
    límite. **No-op si el estado es idéntico** al actual.
  - `undo(history_json)` / `redo(history_json)` → mueven el cursor; no-op si la
    pila está vacía.
  - `document(history_json)` → JSON del documento del estado actual.
  - `status(history_json)` → `"<undo_depth>,<redo_depth>"` (formato compacto,
    trivial de parsear desde Kotlin sin lector JSON).

---

## 4. Capa Android (`app/src/main/java/com/nexanote`)

Regla transversal: **sin lógica de dominio** (sólo estado de UI, navegación y
llamadas al puente). Composables puros, estado elevado. **Nunca bloquear el hilo
principal**: todo el trabajo del núcleo y de disco va a `Dispatchers.Default`/`IO`.

### Paquete `com.nexanote.core`

**`NativeBridge.kt`** — ÚNICO punto que hace `System.loadLibrary("nexanote_core")`
y declara métodos `external`. Expone `isLoaded: Boolean`. Implementa la interfaz
**`NativeCore`** (el resto del código depende de la interfaz, no del objeto JNI).
Superficie: prueba de vida (`greeting`, `coreVersion`), modelo
(`documentCreate/AddPage/RemovePage/AddElement/AddStroke/AddShape/AddText/AddFormula/AddGraph/RemoveElement/TranslatePageElements/Summary`),
render (`documentRenderPage`), **historial** (`historyInit/Record/Undo/Redo/Document/Status`).
Fase 10 (en curso): `documentAddImage`.

### Paquete `com.nexanote.app`

**`MainActivity.kt`** — `ComponentActivity`. Construye el `DocumentViewModel` con
una `ViewModelProvider.Factory` para **inyectarle `FileDocumentStore(applicationContext)`**
(no usa `viewModel()` en el composable). `onPause()` → `viewModel.flush()`
(cualquier pérdida de foco fija el documento en disco).

**`DocumentScreen.kt`** — Pantalla Compose. `Scaffold` + `TopAppBar` con
**botones Undo/Redo** (`IconButton` con `enabled` según `HistoryUiState`).
Estados: `SceneUiState.{Loading, Ready(scene), Error}`. `DocumentCanvas` recibe
callbacks `onStrokeCommit/onShapeCommit/onTextRequest/onFormulaRequest/onGraphRequest`.
Diálogos: `EntryDialog` (texto/fórmula), `GraphDialog` (función + dominio).
`ToolPalette` (abajo-izq, lista `TOOLS`), `CanvasControls` (abajo-der: zoom, fit,
reload). Todos los controles son **iconos vectoriales** (`NexaIcons`), cero emojis.

**`DocumentViewModel.kt`** — orquesta núcleo + historial + autosave.
- Ctor inyectable: `core: NativeCore`, `bridgeAvailable`, `store: DocumentStore = NoOp`,
  `loadDocument: (NativeCore) -> LoadedDocument`, `pageIdOf: (String) -> String`
  (para testeo sin `org.json`). Ctor sin args para la factoría por defecto.
- Flows: `state: StateFlow<SceneUiState>`, `history: StateFlow<HistoryUiState{canUndo,canRedo}>`.
- `buildState(fromAutosave)` — si hay estado guardado y `fromAutosave`, **recupera**;
  si no, construye el documento de ejemplo. Ancla el historial (`historyController.begin`).
- `commitStroke/Shape/Text/Formula/Graph` → helper `edit { mutate() }`: ejecuta la
  mutación del núcleo fuera del hilo principal, aplica el documento, **lo registra
  en el historial**, **lo autoguarda** y publica la escena. Fallo del núcleo → la
  escena y el historial quedan intactos.
- `undo()` / `redo()` → helper `navigate { step() }`: instantáneo (el núcleo no
  re-ejecuta lógica), re-renderiza y autoguarda.
- `flush()` — guardado inmediato en `Dispatchers.IO` (cancela el rebote pendiente).
- `autosave(json)` — **rebote de 600 ms** (`AUTOSAVE_DEBOUNCE_MS`) para coalescer
  ráfagas de ediciones; nunca en cada frame de dibujo. `onCleared` cancela el job.
- `reset()` — descarta el estado guardado y reconstruye el ejemplo.

**`DocumentHistory.kt`** — gestión de Undo/Redo del lado de Kotlin (aquello en lo
que el ViewModel delega). **No implementa la lógica** (vive en Rust): conserva el
blob JSON opaco y expone `canUndo`/`canRedo` + `begin`/`record`/`undo()`/`redo()`
(devuelven el nuevo documento o `null` si no procede). `refresh()` parte
`historyStatus` por la coma.

**`DocumentStore.kt`** — persistencia local (autosave / recuperación).
- `interface DocumentStore { persist(json); restore(): String?; clear() }` +
  `object NoOp` (tests / sin contexto Android).
- `FileDocumentStore(context)` → `filesDir/autosave/active-document.json`.
  **Escritura atómica**: fichero temporal + `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`
  → un cierre a mitad de guardado nunca deja el fichero corrupto.

### Paquete `com.nexanote.app.canvas`

| Archivo | Contenido |
|---|---|
| `DocumentCanvas.kt` | `@Composable DocumentCanvas`: pinta `ScenePage` (fondo, plantilla, primitivas), captura de trazo y de forma con vista previa, gestos pan/zoom (`detectTransformGestures`). Estados internos `StrokeGesture`/`ShapePreview`. Dibuja gráficas (ejes, cuadrícula, curva) y presenta valores de fórmula. |
| `CanvasTransform.kt` | `data class CanvasTransform(scale, offsetX, offsetY, …)`: `applyGesture`, `zoomBy`, `modelToScreen`, `screenToModel`, `fitToViewport`. **Clase pura, con tests JVM.** |
| `RenderModels.kt` | Modelo Kotlin de render: `ScenePage`, `SceneTemplate` (sealed), `ScenePrimitive` (sealed: `Polyline/Rect/Ellipse/Line/Arrow/Text/Formula/Graph`). |
| `SceneParser.kt` | `parse(json) -> ScenePage`. **Mapeo puro de datos** JSON (`org.json`) → `RenderModels` + tipos de Compose (`Offset/Size/Color`). Sin lógica de dominio. |
| `SampleDocument.kt` | Construye el documento de ejemplo **en el núcleo** (formas, trazo, texto, 2 fórmulas, 2 gráficas). `buildDocument(core) -> LoadedDocument{documentJson, pageId}`; `firstPageId(json)`. |
| `NexaIcons.kt` | Iconos `ImageVector` propios sobre lienzo 24×24 (estilo Vector Drawable). `Undo`, `Redo`, `Pen`, `Hand`, `ShapeLine/Rectangle/Ellipse/Arrow`, `TextTool`, `Formula`, `GraphTool`, `ZoomIn/Out`, `FitScreen`, `Refresh`. **Sin emojis, sin `material-icons-extended`.** |
| `StrokeInput.kt` | `enum DrawingTool { Pen, Pan, Line, Rectangle, Ellipse, Arrow, Text, Formula, Graph }`. `StrokeColor(r,g,b,a)` (`.Ink`), `StrokeSample`. `StrokeGesture`: acumula muestras en coords de documento (`addScreenPoint`), `toStrokeJson(color,width)` → `ElementKind::Stroke`. |
| `ShapeInput.kt` | `enum ShapeKind`, `DrawingTool.asShapeKind()`, `data class ShapeBounds(x,y,w,h)`. `object ShapeGeometry`: `boundsFor`, `isDrawable`, `toShapeJson(kind,bounds)` (sin la etiqueta `type`). |
| `TextInput.kt` | `object TextInput`: `isCommittable`, `toTextJson(content,x,y)`; límites de `font_size` y longitud iguales a los del núcleo; escape JSON. |
| `FormulaInput.kt` | `object FormulaInput`: `isCommittable`, `toFormulaJson(expression,x,y)` (`{expression,position}`); escape de backslashes LaTeX. |
| `GraphInput.kt` | `object GraphInput`: `validate(expr,xMin,xMax) -> Validation.{Valid,Invalid}`, `toGraphJson(...)` (`GraphSpec` del núcleo); `DEFAULT_X_MIN/MAX`, `DEFAULT_SIZE`, `MAX_LEN`. |
| `ImageInput.kt` **(Fase 10, en curso)** | Copia la imagen elegida al almacén local de activos de la app y serializa el `ImageSpec` para `documentAddImage`. |

### Paquete `com.nexanote.app.pdf` (Fase 11)

| Archivo | Contenido |
|---|---|
| `PdfPageLayout.kt` | Aritmética pura px lógicos `@96dpi` → puntos PDF (1/72"); `PX_TO_POINT = 0.75`. Tests JVM. |
| `ScenePdfPainter.kt` | Pinta una `ScenePage` del núcleo en un `android.graphics.Canvas` (misma lista de primitivas que `DocumentCanvas`, sin gestos ni sombra). |
| `PdfWriter.kt` | Interfaz `PdfWriter` + `AndroidPdfWriter` (API nativa `android.graphics.pdf.PdfDocument`, una página del PDF por página del documento, a su tamaño exacto). |

La exportación se dispara desde `DocumentScreen` (icono `NexaIcons.ExportPdf`, `CreateDocument` del SAF) → `DocumentViewModel.exportPdf(images, openStream)`, que renderiza todas las páginas y escribe el PDF **fuera del hilo principal** (`Dispatchers.Default` + `Dispatchers.IO`), publicando el resultado en `export: StateFlow<PdfExportUiState>`. Se optó por la API de Android (no una crate de PDF en Rust) porque `render.rs` ya es la autoridad única sobre unidades/orden/muestreo: aquí sólo se rasteriza.

### Paquete `com.nexanote.app.ai` (Fase 12)

| Archivo | Contenido |
|---|---|
| `AiSettings.kt` | `AiProviderId` (OpenAi/Anthropic: nombre, modelo por defecto, endpoint) + `AiSettings` (proveedor, apiKey, modelo). `toString` nunca revela la clave. |
| `SecureSettingsRepository.kt` | Interfaz + `SharedPreferencesSettingsRepository` sobre un `SharedPreferences` inyectable (claves `ai_provider`/`ai_api_key`/`ai_model`). |
| `SecureSettings.kt` | Fábrica de producción: `EncryptedSharedPreferences` (`MasterKey` AES256-GCM en el Keystore). Fichero `nexanote_ai_secure_prefs`. |
| `AiProvider.kt` | Interfaz `AiProvider` + `AiRequest`/`AiResult` + `AssistKind`/`AssistPrompt` (prompt de sistema por tipo). |
| `AiRequestBodies.kt` | Serialización **pura** del cuerpo JSON (OpenAI chat / Anthropic messages). La clave nunca entra en el cuerpo. Tests JVM. |
| `HttpAiProvider.kt` | Base HTTP con `HttpURLConnection` en `Dispatchers.IO` (sin cliente de terceros); `OpenAiProvider` (`Bearer`), `AnthropicProvider` (`x-api-key`); `AiProviders.forSettings`. No registra la clave. |
| `AiViewModel.kt` | `settings: StateFlow<AiSettings>` + `saveSettings`/`clearSettings`; `assist: StateFlow<AssistUiState>` + `explain(content, kind)`/`dismissAssist`. Disco y red fuera del hilo principal. |

UI: `AiScreens.kt` (`AiSettingsDialog`, `AssistDialog`), iconos `NexaIcons.Settings`/`NexaIcons.Assistant` en la `TopAppBar`. `MainActivity` inyecta `AiViewModel(SecureSettings.create(...))`. Permiso `INTERNET` añadido al manifest. La API key sólo se persiste cifrada; nunca en logs, código ni Git.

---

## 5. Puente FFI y Serialización

```
Kotlin (NativeCore)  ──JString JSON──▶  bridge.rs (JNI, unsafe)  ──▶  api/render/history (Rust puro)
      ▲                                        │
      └────────  jstring JSON  ◀───────────────┘   (o IllegalStateException si DocumentError)
```

- **Contrato**: cada método del puente recibe y devuelve **cadenas JSON**
  (o primitivos). Sin objetos con comportamiento cruzando la frontera.
- **Stateless**: el documento (y el historial) **viven en Kotlin** y viajan
  completos como JSON en cada llamada; la función Rust devuelve el estado
  actualizado. Esto hace la capa Rust trivialmente testeable sin JNI.
- **Nombres JNI**: `Java_com_nexanote_core_NativeBridge_<nombreMétodo>`.
- **Errores**: `DocumentError` (Rust) → `java.lang.IllegalStateException`
  (mensaje `"nexanote-core: <variante>"`). **Nunca un `panic` cruza el FFI.**
- **IDs**: `u128` en Rust ↔ cadena hex de 32 chars en JSON (precisión segura).
- **Formatos de tag serde** relevantes para construir JSON desde Kotlin:
  - `ElementKind`: `{"type":"Stroke"|"Text"|"Formula"|"Shape"|"Graph", …}`
  - `PageSize`: `{"format":"A4"}` · `{"format":"Custom","size":{"width_mm":…,"height_mm":…}}`
  - `PageTemplate`: `{"kind":"Grid","spacing":24.0}`
  - `ScenePrimitive` / `SceneTemplate`: `{"kind":…}` / `{"type":…}` (dirección núcleo→UI)
  - `FormulaNode` (AST): `{"node":"Binary","value":{…}}`
- **Historial**: `historyStatus` devuelve `"<undo>,<redo>"` (no JSON) a propósito,
  para que Kotlin no necesite un parser.
- **`SampleDocument` / `DocumentRenderBridgeTest`** usan `org.json.JSONObject`
  para leer `pages[0].id` → sólo funcionan en runtime/instrumentado, no en JVM
  pura (por eso el `DocumentViewModel` acepta `loadDocument`/`pageIdOf` inyectables).

### Build del `.so`
`app/build.gradle.kts` registra la task `buildRustCore` (Exec) enganchada a
`preBuild`, que corre `rust/build-android.sh` (o `.ps1` en Windows). El script
cross-compila `nexanote-core` (`crate-type = cdylib`) para las 4 ABIs y copia los
`.so` a `app/src/main/jniLibs/<abi>/` (directorio ignorado por git). Necesita
`cargo` + targets Android + NDK (`ANDROID_NDK_HOME` o bajo `$ANDROID_HOME/ndk/`).

---

## 6. Reglas de Gobernanza y Diseño

> Fuente vinculante: [`claude.md`]. Resumen operativo:

### Arquitectura
1. **La lógica de negocio y los motores críticos residen en Rust.** Kotlin sólo
   orquesta UI y delega.
2. **`NativeBridge` es la única frontera JNI** (único `System.loadLibrary`, únicos
   `external`). El resto depende de la interfaz `NativeCore`.
3. Capas **aisladas**: se comunican por **datos planos serializables**, nunca por
   objetos con comportamiento.
4. Cada motor futuro (OCR, IA, exportación) = módulo Rust independiente, fronteras
   claras, sin dependencias circulares.

### Código Rust
1. **Cero `panic!`/`unwrap()`/`expect()`** imprudentes en producción → `Result<T,E>`.
   `unwrap()` sólo en tests o invariante local comentada.
2. **La frontera FFI nunca hace unwind**: toda `extern` captura errores y los
   traduce a excepción Java. Un `panic` que cruce FFI es UB → prohibido.
3. **Modularidad**: un módulo, una responsabilidad. Sin `mod.rs` gigantes.
4. **Rendimiento**: evitar asignaciones/copias en rutas calientes (stylus, render).
   Medir antes de optimizar; documentar trade-offs.
5. **`unsafe` sólo en `bridge.rs`**, acotado, comentado, justificado.
6. **`#![deny(warnings)]`** — el código entra sin warnings.
7. **Tests**: cada módulo con lógica lleva `#[cfg(test)]`.

### Código Kotlin / Compose
1. **Sin lógica de dominio** (sólo estado UI, navegación, llamadas al puente).
2. `NativeBridge` es el único punto JNI.
3. Composables puros, sin efectos colaterales ocultos; **estado elevado**.
4. **Sin bloqueos del hilo principal**: el trabajo del núcleo va fuera del main
   thread (`Dispatchers.Default`/`IO`).

### Interfaz de usuario
- **CERO EMOJIS** en toda la UI, sin excepción.
- **Iconografía 100 % vectorial propia** (`NexaIcons`, `ImageVector`). Prohibido
  `material-icons-extended` (para no arrastrar la librería).

### Historial (Fase 9)
- **Patrón de instantáneas acotadas** (no comandos): `past/present/future` de
  `serde_json::Value`, límite `DEFAULT_LIMIT = 250` por pila.
- `undo`/`redo` **O(1)**, no re-ejecutan lógica → UI instantánea y sin tirones.
- Una edición nueva **corta la rama de redo**. Registrar un estado idéntico es
  no-op.

### Autosave / offline-first (Fase 9)
- **Por eventos clave** (cada edición confirmada) + **rebote** (600 ms), nunca en
  cada milisegundo de dibujo continuo.
- **Escritura atómica** (temporal + `ATOMIC_MOVE`).
- `flush()` en `Activity.onPause` (pérdida de foco / cierre inesperado).
- Recuperación automática del último estado al reiniciar la app.

### Dependencias
- **Minimalismo**: nada de terceros salvo estrictamente necesario. Rust: `jni`,
  `serde`, `serde_json`. UI: sólo Compose. Toda dependencia nueva se justifica en
  el PR (por qué, alternativas, coste).

### Git y flujo
- Remoto: <https://github.com/Luis000923/NexaNote> · rama principal `main`.
- **Al concluir cada fase: `commit` + `push` obligatorio.** Mensajes en
  imperativo, agrupados por fase.
- No se commitea: artefactos de build, `local.properties`, `.so`, claves/secretos.

### Testing y validación
- Rust: `cd rust && cargo test` en verde.
- Android JVM: `./gradlew :app:testDebugUnitTest` (clases puras: `CanvasTransform`,
  `*Input`, `DocumentHistory`).
- Android instrumentado: `./gradlew :app:connectedDebugAndroidTest` (carga del
  `.so`, respuesta del puente, ViewModel↔núcleo) — requiere dispositivo/emulador.
- Cada fase se cierra comprobando por **compilación** (`assembleDebug`) que el
  proyecto se arma sin errores.

---

## 7. Notas para futuras sesiones

- **Toolchains instaladas manualmente, NO en PATH.** Env de build:
  `source ~/.cargo/env; export ANDROID_HOME=~/Android/Sdk ANDROID_NDK_HOME=~/Android/Sdk/ndk/26.3.11579264 JAVA_HOME=/usr/lib/jvm/java-17-openjdk`
  (JDK del sistema es 21; AGP necesita 17). Detalle en la memoria `nexanote-toolchains`.
- `adb` del sistema está roto (falta `libprotobuf.so.36`) → **no hay
  emulador/dispositivo aquí**: los tests instrumentados compilan y empaquetan
  (`assembleDebugAndroidTest`) pero no se ejecutan.
- `cargo test` del núcleo puede tardar (compilación release LTO); los tests de
  `history` que construían documentos grandes usan instantáneas baratas
  (cambio de título) para no ser O(n²).
- Para añadir una operación de documento nueva: (1) tipo/lógica en `document/`,
  (2) función stateless en `api.rs` + test, (3) wrapper JNI en `bridge.rs`,
  (4) método en `NativeCore` + `NativeBridge`, (5) consumo en `DocumentViewModel`
  (recordar `historyController.record` + `autosave`), (6) control vectorial en
  `NexaIcons`/`DocumentScreen` si aplica.

[`claude.md`]: ./claude.md
