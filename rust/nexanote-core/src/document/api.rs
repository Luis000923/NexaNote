//! API basada en cadenas JSON, pensada para cruzar la frontera FFI.
//!
//! Cada función recibe y devuelve `String` JSON y nunca hace `panic`: todo error
//! se devuelve como [`DocumentError`] (que el adaptador JNI traduce a excepción
//! Java). Es *stateless*: el documento vive en el lado de Kotlin y viaja como
//! JSON en cada llamada, lo que hace la capa trivialmente testeable.

use serde::{Deserialize, Serialize};

use super::element::{
    ElementKind, Formula, Graph, ImageRef, Shape, ShapeKind, Stroke, TextBox,
};
use super::error::{DocResult, DocumentError};
use super::geometry::{Color, Point, Rect, Transformable};
use super::id::ElementId;
use super::math;
use super::model::Document;
use super::page::{PageSize, PageTemplate};

/// Especificación para crear una página (campos opcionales con valor por defecto).
#[derive(Debug, Clone, Deserialize)]
#[serde(default, deny_unknown_fields)]
struct PageSpec {
    size: PageSize,
    template: PageTemplate,
}

impl Default for PageSpec {
    fn default() -> Self {
        PageSpec {
            size: PageSize::default(),
            template: PageTemplate::default(),
        }
    }
}

/// Resumen compacto de un documento, para aserciones rápidas desde Kotlin.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct DocumentSummary {
    pub id: String,
    pub title: String,
    pub schema_version: u32,
    pub page_count: usize,
    pub element_count: usize,
}

fn parse<T: for<'de> Deserialize<'de>>(json: &str, what: &str) -> DocResult<T> {
    serde_json::from_str(json)
        .map_err(|e| DocumentError::Serialization(format!("{what}: {e}")))
}

fn dump<T: Serialize>(value: &T) -> DocResult<String> {
    serde_json::to_string(value).map_err(|e| DocumentError::Serialization(e.to_string()))
}

/// Crea un documento vacío y lo devuelve como JSON.
pub fn create_document(title: &str) -> DocResult<String> {
    Document::new(title).to_json()
}

/// Añade una página al documento `document_json` según `page_spec_json`
/// (`{}` = A4 en blanco). Devuelve el documento actualizado.
pub fn add_page(document_json: &str, page_spec_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let spec: PageSpec = parse(page_spec_json, "page_spec")?;
    doc.add_page(spec.size, spec.template)?;
    doc.to_json()
}

/// Elimina la página `page_id` del documento. Devuelve el documento actualizado.
pub fn remove_page(document_json: &str, page_id: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    doc.remove_page(id)?;
    doc.to_json()
}

/// Inserta el elemento `element_json` en la página `page_id`. Devuelve el
/// documento actualizado.
pub fn add_element(document_json: &str, page_id: &str, element_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    let kind: ElementKind = parse(element_json, "element")?;
    doc.page_mut(id)?.add_element(kind);
    doc.to_json()
}

/// Grosor mínimo (unidades lógicas) para que un trazo sea visible al pintarse.
const MIN_STROKE_WIDTH: f32 = 0.5;

/// Inserta un **trazo a mano alzada** en la página `page_id`.
///
/// `stroke_json` es un [`Stroke`] serializado (`points`, `color`, `width`), tal
/// como lo captura la capa de stylus. Antes de insertarlo, el núcleo lo **sanea**
/// -- es la única autoridad sobre la validez del trazo, de modo que la UI se
/// limita a capturar puntos en alta frecuencia sin decidir nada:
///
///  - descarta muestras con coordenadas no finitas;
///  - exige al menos un punto válido (si no, [`DocumentError::InvalidArgument`]);
///  - satura la presión de cada muestra a `[0.0, 1.0]` (`0.5` si no es finita);
///  - eleva el grosor a [`MIN_STROKE_WIDTH`] si viene por debajo o no es finito.
///
/// Devuelve el documento actualizado.
pub fn add_stroke(document_json: &str, page_id: &str, stroke_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    let raw: Stroke = parse(stroke_json, "stroke")?;
    let stroke = sanitize_stroke(raw)?;
    doc.page_mut(id)?.add_element(ElementKind::Stroke(stroke));
    doc.to_json()
}

/// Aplica las reglas de validez de un trazo capturado. Ver [`add_stroke`].
fn sanitize_stroke(mut stroke: Stroke) -> DocResult<Stroke> {
    stroke
        .points
        .retain(|p| p.position.x.is_finite() && p.position.y.is_finite());
    if stroke.points.is_empty() {
        return Err(DocumentError::InvalidArgument(
            "un trazo necesita al menos un punto válido".to_string(),
        ));
    }
    for p in &mut stroke.points {
        p.pressure = if p.pressure.is_finite() {
            p.pressure.clamp(0.0, 1.0)
        } else {
            0.5
        };
    }
    if !stroke.width.is_finite() || stroke.width < MIN_STROKE_WIDTH {
        stroke.width = MIN_STROKE_WIDTH;
    }
    Ok(stroke)
}

/// Extensión mínima (unidades lógicas) para que una forma no sea degenerada. Es
/// un suelo defensivo: la UI aplica su propio umbral de "arrastre intencionado".
const MIN_SHAPE_EXTENT: f32 = 1.0;

/// Inserta una **forma geométrica** (`Rectangle`, `Ellipse`, `Line`, `Arrow`) en
/// la página `page_id`.
///
/// `shape_json` es un [`Shape`] serializado
/// (`{"kind","bounds":{"x","y","width","height"},"stroke_color","fill_color","stroke_width"}`).
/// El núcleo es la única autoridad sobre la validez de la forma y la **sanea**:
///
///  - exige límites finitos (si no, [`DocumentError::InvalidArgument`]);
///  - `Rectangle`/`Ellipse`: normaliza a esquina superior-izquierda + tamaño no
///    negativo (el arrastre en cualquier dirección es válido) y rechaza la forma
///    si es más pequeña que [`MIN_SHAPE_EXTENT`] en ambos ejes;
///  - `Line`/`Arrow`: conserva el signo de `(width, height)` -- es el vector del
///    extremo inicial al final -- y rechaza el trazo si su longitud es menor que
///    [`MIN_SHAPE_EXTENT`];
///  - eleva `stroke_width` a [`MIN_STROKE_WIDTH`] si viene por debajo o no es finito.
///
/// Devuelve el documento actualizado.
pub fn add_shape(document_json: &str, page_id: &str, shape_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    let raw: Shape = parse(shape_json, "shape")?;
    let shape = sanitize_shape(raw)?;
    doc.page_mut(id)?.add_element(ElementKind::Shape(shape));
    doc.to_json()
}

/// Aplica las reglas de validez de una forma geométrica. Ver [`add_shape`].
fn sanitize_shape(mut shape: Shape) -> DocResult<Shape> {
    let b = shape.bounds;
    if !(b.x.is_finite() && b.y.is_finite() && b.width.is_finite() && b.height.is_finite()) {
        return Err(DocumentError::InvalidArgument(
            "los límites de la forma deben ser finitos".to_string(),
        ));
    }
    match shape.kind {
        ShapeKind::Rectangle | ShapeKind::Ellipse => {
            if shape.bounds.width < 0.0 {
                shape.bounds.x += shape.bounds.width;
                shape.bounds.width = -shape.bounds.width;
            }
            if shape.bounds.height < 0.0 {
                shape.bounds.y += shape.bounds.height;
                shape.bounds.height = -shape.bounds.height;
            }
            if shape.bounds.width < MIN_SHAPE_EXTENT && shape.bounds.height < MIN_SHAPE_EXTENT {
                return Err(DocumentError::InvalidArgument(
                    "la forma es demasiado pequeña para dibujarse".to_string(),
                ));
            }
        }
        ShapeKind::Line | ShapeKind::Arrow => {
            if shape.bounds.width.hypot(shape.bounds.height) < MIN_SHAPE_EXTENT {
                return Err(DocumentError::InvalidArgument(
                    "la línea es demasiado corta para dibujarse".to_string(),
                ));
            }
        }
    }
    if !shape.stroke_width.is_finite() || shape.stroke_width < MIN_STROKE_WIDTH {
        shape.stroke_width = MIN_STROKE_WIDTH;
    }
    Ok(shape)
}

/// Tamaño de fuente mínimo y máximo (unidades lógicas) para un bloque de texto.
const MIN_FONT_SIZE: f32 = 6.0;
const MAX_FONT_SIZE: f32 = 512.0;

/// Longitud máxima (en caracteres Unicode) del contenido de un bloque de texto.
/// Fase 6: bloques limpios y acotados, sin formato enriquecido masivo.
const MAX_TEXT_LEN: usize = 4096;

/// Inserta un **bloque de texto** en la página `page_id`.
///
/// `text_json` es un [`TextBox`] serializado
/// (`{"content","position":{"x","y"},"style":{"font_size","bold","italic","underline","color"},"max_width"}`).
/// El núcleo es la única autoridad sobre la validez del bloque y lo **sanea**:
///
///  - recorta espacios y exige contenido no vacío (si no, [`DocumentError::InvalidArgument`]);
///  - trunca el contenido a [`MAX_TEXT_LEN`] caracteres;
///  - exige una posición finita (si no, [`DocumentError::InvalidArgument`]);
///  - satura `font_size` a `[MIN_FONT_SIZE, MAX_FONT_SIZE]` (o `16.0` si no es finito);
///  - descarta `max_width` si no es finito o no es positivo.
///
/// Devuelve el documento actualizado.
pub fn add_text(document_json: &str, page_id: &str, text_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    let raw: TextBox = parse(text_json, "text")?;
    let text = sanitize_text(raw)?;
    doc.page_mut(id)?.add_element(ElementKind::Text(text));
    doc.to_json()
}

/// Aplica las reglas de validez de un bloque de texto. Ver [`add_text`].
fn sanitize_text(mut text: TextBox) -> DocResult<TextBox> {
    let trimmed = text.content.trim();
    if trimmed.is_empty() {
        return Err(DocumentError::InvalidArgument(
            "un bloque de texto necesita contenido no vacío".to_string(),
        ));
    }
    text.content = trimmed.chars().take(MAX_TEXT_LEN).collect();
    if !(text.position.x.is_finite() && text.position.y.is_finite()) {
        return Err(DocumentError::InvalidArgument(
            "la posición del texto debe ser finita".to_string(),
        ));
    }
    text.style.font_size = if text.style.font_size.is_finite() {
        text.style.font_size.clamp(MIN_FONT_SIZE, MAX_FONT_SIZE)
    } else {
        16.0
    };
    text.max_width = text
        .max_width
        .filter(|w| w.is_finite() && *w > 0.0);
    Ok(text)
}

/// Longitud máxima (caracteres Unicode) de la fuente de una fórmula.
const MAX_FORMULA_LEN: usize = 2048;

/// Especificación de entrada para insertar una fórmula: expresión + posición.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct FormulaSpec {
    /// Expresión matemática en dialecto ASCII/LaTeX (ver [`math`]).
    expression: String,
    position: Point,
}

/// Inserta una **fórmula matemática estructurada** en la página `page_id`.
///
/// `formula_json` es un [`FormulaSpec`] serializado
/// (`{"expression":"\\frac{a}{b}","position":{"x":..,"y":..}}`). El núcleo:
///
///  - recorta la expresión y exige que no quede vacía ni supere
///    [`MAX_FORMULA_LEN`] caracteres;
///  - exige una posición finita;
///  - **parsea** la expresión a un AST tipado ([`FormulaNode`](super::element::FormulaNode))
///    con el motor matemático; un fallo léxico o sintáctico se devuelve como
///    [`DocumentError::InvalidArgument`] (nunca `panic`).
///
/// La fórmula se persiste con su fuente original y su AST. Devuelve el documento
/// actualizado.
pub fn add_formula(document_json: &str, page_id: &str, formula_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    let spec: FormulaSpec = parse(formula_json, "formula")?;

    let expression = spec.expression.trim();
    if expression.is_empty() {
        return Err(DocumentError::InvalidArgument(
            "una fórmula necesita una expresión no vacía".to_string(),
        ));
    }
    if expression.chars().count() > MAX_FORMULA_LEN {
        return Err(DocumentError::InvalidArgument(format!(
            "la expresión supera {MAX_FORMULA_LEN} caracteres"
        )));
    }
    if !(spec.position.x.is_finite() && spec.position.y.is_finite()) {
        return Err(DocumentError::InvalidArgument(
            "la posición de la fórmula debe ser finita".to_string(),
        ));
    }

    let ast = math::parse(expression)
        .map_err(|e| DocumentError::InvalidArgument(format!("fórmula inválida: {e}")))?;

    let formula = Formula {
        latex: expression.to_string(),
        position: spec.position,
        ast: Some(ast),
    };
    doc.page_mut(id)?.add_element(ElementKind::Formula(formula));
    doc.to_json()
}

/// Límites del número de puntos de muestreo de una gráfica que la API acepta.
const MIN_GRAPH_SAMPLES: u32 = 8;
const MAX_GRAPH_SAMPLES: u32 = 2048;

/// Tamaño mínimo (px lógicos) del marco de una gráfica.
const MIN_GRAPH_EXTENT: f32 = 16.0;

fn default_graph_var() -> String {
    "x".to_string()
}

fn default_graph_samples() -> u32 {
    256
}

/// Especificación de entrada para insertar una gráfica de función.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct GraphSpec {
    /// Función a graficar, en dialecto ASCII/LaTeX (ver [`math`]).
    expression: String,
    /// Esquina superior-izquierda del marco en la página (px lógicos @1x).
    position: Point,
    width: f32,
    height: f32,
    x_min: f64,
    x_max: f64,
    #[serde(default = "default_graph_var")]
    var: String,
    #[serde(default = "default_graph_samples")]
    samples: u32,
}

/// Inserta una **gráfica de función** `y = f(var)` en la página `page_id`.
///
/// `graph_json` es un [`GraphSpec`] serializado
/// (`{"expression":"x^2","position":{"x":..,"y":..},"width":..,"height":..,"x_min":..,"x_max":..}`;
/// `var` por defecto `"x"`, `samples` por defecto `256`). El núcleo:
///
///  - recorta la expresión y exige que no quede vacía ni supere [`MAX_FORMULA_LEN`];
///  - exige posición y tamaño finitos, y un marco de al menos [`MIN_GRAPH_EXTENT`];
///  - exige un dominio finito con `x_min < x_max`;
///  - satura `samples` a `[MIN_GRAPH_SAMPLES, MAX_GRAPH_SAMPLES]`;
///  - **parsea** la función a un AST y rechaza cualquier símbolo libre distinto de
///    `var` ([`DocumentError::InvalidArgument`], nunca `panic`).
///
/// El muestreo numérico de la curva **no** se hace aquí, sino en la capa de
/// *render* al construir la escena. Devuelve el documento actualizado.
pub fn add_graph(document_json: &str, page_id: &str, graph_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    let spec: GraphSpec = parse(graph_json, "graph")?;

    let expression = spec.expression.trim();
    if expression.is_empty() {
        return Err(DocumentError::InvalidArgument(
            "una gráfica necesita una función no vacía".to_string(),
        ));
    }
    if expression.chars().count() > MAX_FORMULA_LEN {
        return Err(DocumentError::InvalidArgument(format!(
            "la función supera {MAX_FORMULA_LEN} caracteres"
        )));
    }
    let var = spec.var.trim();
    if var.is_empty() {
        return Err(DocumentError::InvalidArgument(
            "el nombre de la variable no puede estar vacío".to_string(),
        ));
    }
    if !(spec.position.x.is_finite()
        && spec.position.y.is_finite()
        && spec.width.is_finite()
        && spec.height.is_finite())
    {
        return Err(DocumentError::InvalidArgument(
            "la posición y el tamaño de la gráfica deben ser finitos".to_string(),
        ));
    }
    if spec.width < MIN_GRAPH_EXTENT || spec.height < MIN_GRAPH_EXTENT {
        return Err(DocumentError::InvalidArgument(format!(
            "el marco de la gráfica debe medir al menos {MIN_GRAPH_EXTENT} px por lado"
        )));
    }
    if !spec.x_min.is_finite() || !spec.x_max.is_finite() || spec.x_min >= spec.x_max {
        return Err(DocumentError::InvalidArgument(
            "el dominio debe ser finito con x_min < x_max".to_string(),
        ));
    }

    let ast = math::parse(expression)
        .map_err(|e| DocumentError::InvalidArgument(format!("función inválida: {e}")))?;

    let free: Vec<String> = math::free_symbols(&ast)
        .into_iter()
        .filter(|s| s != var)
        .collect();
    if !free.is_empty() {
        return Err(DocumentError::InvalidArgument(format!(
            "la función depende de símbolos sin valor: {}",
            free.join(", ")
        )));
    }

    let graph = Graph {
        expression: expression.to_string(),
        ast: Some(ast),
        var: var.to_string(),
        frame: super::geometry::Rect::new(
            spec.position.x,
            spec.position.y,
            spec.width,
            spec.height,
        ),
        x_min: spec.x_min,
        x_max: spec.x_max,
        samples: spec.samples.clamp(MIN_GRAPH_SAMPLES, MAX_GRAPH_SAMPLES),
    };
    doc.page_mut(id)?.add_element(ElementKind::Graph(graph));
    doc.to_json()
}

/// Longitud máxima (caracteres Unicode) de la ruta de un recurso de imagen.
const MAX_IMAGE_SOURCE_LEN: usize = 512;

/// Extensión mínima (px lógicos) de un lado del marco de una imagen.
const MIN_IMAGE_EXTENT: f32 = 8.0;

/// Extensión máxima (px lógicos) de un lado del marco de una imagen: cota
/// defensiva contra encuadres absurdos.
const MAX_IMAGE_EXTENT: f32 = 20_000.0;

/// Especificación de entrada para insertar una imagen ya copiada al almacén local.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct ImageSpec {
    /// Ruta **relativa** al almacén de activos de la app (`images/…`).
    source: String,
    /// Esquina superior-izquierda del marco en la página (px lógicos @1x).
    position: Point,
    width: f32,
    height: f32,
    natural_width: f32,
    natural_height: f32,
}

/// Inserta una **imagen** en la página `page_id`.
///
/// `image_json` es un [`ImageSpec`] serializado
/// (`{"source":"images/x.png","position":{"x":..,"y":..},"width":..,"height":..,"natural_width":..,"natural_height":..}`).
/// El núcleo es la única autoridad sobre la validez y la **sanea**:
///
///  - recorta la ruta y exige que no quede vacía ni supere [`MAX_IMAGE_SOURCE_LEN`];
///  - exige una ruta **relativa** al almacén del documento: rechaza rutas
///    absolutas, unidades (`C:`) y segmentos `..` (portabilidad offline-first,
///    sin dependencias de rutas externas efímeras);
///  - exige posición y tamaño finitos, y un marco entre [`MIN_IMAGE_EXTENT`] y
///    [`MAX_IMAGE_EXTENT`] px por lado;
///  - exige dimensiones intrínsecas positivas y finitas.
///
/// Devuelve el documento actualizado.
pub fn add_image(document_json: &str, page_id: &str, image_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let id = page_id.parse()?;
    let spec: ImageSpec = parse(image_json, "image")?;
    let image = sanitize_image(spec)?;
    doc.page_mut(id)?.add_element(ElementKind::Image(image));
    doc.to_json()
}

/// Aplica las reglas de validez de una imagen importada. Ver [`add_image`].
fn sanitize_image(spec: ImageSpec) -> DocResult<ImageRef> {
    let source = spec.source.trim();
    if source.is_empty() {
        return Err(DocumentError::InvalidArgument(
            "una imagen necesita una ruta de recurso no vacía".to_string(),
        ));
    }
    if source.chars().count() > MAX_IMAGE_SOURCE_LEN {
        return Err(DocumentError::InvalidArgument(format!(
            "la ruta de la imagen supera {MAX_IMAGE_SOURCE_LEN} caracteres"
        )));
    }
    if source.starts_with('/')
        || source.starts_with('\\')
        || source.contains(':')
        || source.split(['/', '\\']).any(|seg| seg == "..")
    {
        return Err(DocumentError::InvalidArgument(
            "la ruta de la imagen debe ser relativa al almacén del documento".to_string(),
        ));
    }
    if !(spec.position.x.is_finite()
        && spec.position.y.is_finite()
        && spec.width.is_finite()
        && spec.height.is_finite())
    {
        return Err(DocumentError::InvalidArgument(
            "la posición y el tamaño de la imagen deben ser finitos".to_string(),
        ));
    }
    if spec.width < MIN_IMAGE_EXTENT || spec.height < MIN_IMAGE_EXTENT {
        return Err(DocumentError::InvalidArgument(format!(
            "el marco de la imagen debe medir al menos {MIN_IMAGE_EXTENT} px por lado"
        )));
    }
    if spec.width > MAX_IMAGE_EXTENT || spec.height > MAX_IMAGE_EXTENT {
        return Err(DocumentError::InvalidArgument(format!(
            "el marco de la imagen no puede superar {MAX_IMAGE_EXTENT} px por lado"
        )));
    }
    if !(spec.natural_width.is_finite() && spec.natural_height.is_finite())
        || spec.natural_width <= 0.0
        || spec.natural_height <= 0.0
    {
        return Err(DocumentError::InvalidArgument(
            "las dimensiones intrínsecas de la imagen deben ser positivas y finitas".to_string(),
        ));
    }
    Ok(ImageRef {
        source: source.to_string(),
        frame: super::geometry::Rect::new(spec.position.x, spec.position.y, spec.width, spec.height),
        natural_width: spec.natural_width,
        natural_height: spec.natural_height,
    })
}

/// Elimina el elemento `element_id` de la página `page_id`. Devuelve el documento
/// actualizado.
pub fn remove_element(document_json: &str, page_id: &str, element_id: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let pid = page_id.parse()?;
    let eid = element_id.parse()?;
    doc.page_mut(pid)?.remove_element(eid)?;
    doc.to_json()
}

/// Traslada todos los elementos de una página por `(dx, dy)`.
pub fn translate_page_elements(
    document_json: &str,
    page_id: &str,
    dx: f32,
    dy: f32,
) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let pid = page_id.parse()?;
    let page = doc.page_mut(pid)?;
    for element in &mut page.elements {
        element.translate(dx, dy);
    }
    doc.to_json()
}


// ---------------------------------------------------------------------------
// Selección de área, manipulación en lote y color/relleno (Fase 13).
//
// Todas estas operaciones deciden en el núcleo: la UI sólo entrega un rectángulo
// o un punto en coordenadas de página y recibe ids ya resueltos.
// ---------------------------------------------------------------------------

/// Tolerancia (px de página) al resolver el elemento tocado: un trazo fino debe
/// poder seleccionarse con el dedo, no sólo con la punta del stylus.
const TAP_TOLERANCE_PX: f32 = 8.0;

/// Rectángulo de selección tal y como lo envía la UI.
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
struct AreaSpec {
    x: f32,
    y: f32,
    width: f32,
    height: f32,
}

impl AreaSpec {
    fn to_rect(self) -> DocResult<Rect> {
        let rect = Rect::new(self.x, self.y, self.width, self.height);
        if [rect.x, rect.y, rect.width, rect.height].iter().all(|v| v.is_finite()) {
            Ok(rect.normalized())
        } else {
            Err(DocumentError::InvalidArgument(
                "el área de selección debe ser finita".to_string(),
            ))
        }
    }
}

/// Resultado de una selección: ids elegidos y su caja envolvente (si hay alguno).
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Selection {
    pub ids: Vec<String>,
    pub bounds: Option<AreaOut>,
}

/// Rectángulo serializable devuelto a la UI.
#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
pub struct AreaOut {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

impl From<Rect> for AreaOut {
    fn from(r: Rect) -> Self {
        let r = r.normalized();
        AreaOut { x: r.x, y: r.y, width: r.width, height: r.height }
    }
}

fn parse_ids(ids_json: &str) -> DocResult<Vec<ElementId>> {
    let raw: Vec<String> = parse(ids_json, "lista de ids")?;
    raw.iter().map(|s| s.parse()).collect()
}

/// Caja envolvente de los elementos `ids` de la página, o `None` si ninguno existe.
fn bounds_of(page: &super::page::Page, ids: &[ElementId]) -> Option<Rect> {
    let mut acc: Option<Rect> = None;
    for id in ids {
        if let Some(element) = page.element(*id) {
            acc = Some(match acc {
                Some(r) => r.union(element.bounds()),
                None => element.bounds(),
            });
        }
    }
    acc
}

fn selection_of(page: &super::page::Page, ids: Vec<ElementId>) -> Selection {
    let bounds = bounds_of(page, &ids).map(AreaOut::from);
    Selection {
        ids: ids.iter().map(|id| id.to_string()).collect(),
        bounds,
    }
}

/// Selecciona los elementos **contenidos por completo** en el área dada.
/// `area_json` es `{"x":..,"y":..,"width":..,"height":..}` en px de página.
/// Devuelve `{"ids":[...],"bounds":{...}|null}`.
pub fn select_in_area(document_json: &str, page_id: &str, area_json: &str) -> DocResult<String> {
    let doc = Document::from_json(document_json)?;
    let pid = page_id.parse()?;
    let page = doc.page(pid).ok_or(DocumentError::PageNotFound(page_id.to_string()))?;
    let area: AreaSpec = parse(area_json, "área de selección")?;
    let ids = page.elements_in(area.to_rect()?);
    dump(&selection_of(page, ids))
}

/// Selecciona el elemento más al frente bajo el punto `(x, y)`. Devuelve la misma
/// forma que [`select_in_area`], con la lista vacía si no se tocó nada.
pub fn select_at(document_json: &str, page_id: &str, x: f32, y: f32) -> DocResult<String> {
    let doc = Document::from_json(document_json)?;
    let pid = page_id.parse()?;
    let page = doc.page(pid).ok_or(DocumentError::PageNotFound(page_id.to_string()))?;
    let ids = page.element_at(x, y, TAP_TOLERANCE_PX).into_iter().collect();
    dump(&selection_of(page, ids))
}

/// Elimina en bloque los elementos `ids_json` (`["<hex>", ...]`) de la página.
/// Los ids que ya no existan se ignoran: borrar dos veces no es un error.
pub fn remove_elements(document_json: &str, page_id: &str, ids_json: &str) -> DocResult<String> {
    let mut doc = Document::from_json(document_json)?;
    let pid = page_id.parse()?;
    let ids = parse_ids(ids_json)?;
    let page = doc.page_mut(pid)?;
    page.elements.retain(|e| !ids.contains(&e.id));
    doc.to_json()
}

/// Traslada `(dx, dy)` los elementos indicados, dejando el resto intacto.
pub fn translate_elements(
    document_json: &str,
    page_id: &str,
    ids_json: &str,
    dx: f32,
    dy: f32,
) -> DocResult<String> {
    if !dx.is_finite() || !dy.is_finite() {
        return Err(DocumentError::InvalidArgument(
            "el desplazamiento debe ser finito".to_string(),
        ));
    }
    let mut doc = Document::from_json(document_json)?;
    let pid = page_id.parse()?;
    let ids = parse_ids(ids_json)?;
    let page = doc.page_mut(pid)?;
    for element in page.elements.iter_mut().filter(|e| ids.contains(&e.id)) {
        element.translate(dx, dy);
    }
    doc.to_json()
}

/// Duplica los elementos indicados desplazados `(dx, dy)`. Devuelve
/// `{"document":"<json>","selection":{"ids":[...],"bounds":...}}` para que la UI
/// pase a tener seleccionadas las copias.
pub fn duplicate_elements(
    document_json: &str,
    page_id: &str,
    ids_json: &str,
    dx: f32,
    dy: f32,
) -> DocResult<String> {
    if !dx.is_finite() || !dy.is_finite() {
        return Err(DocumentError::InvalidArgument(
            "el desplazamiento debe ser finito".to_string(),
        ));
    }
    let mut doc = Document::from_json(document_json)?;
    let pid = page_id.parse()?;
    let ids = parse_ids(ids_json)?;
    let page = doc.page_mut(pid)?;
    let created = page.duplicate_elements(&ids, dx, dy);
    let selection = selection_of(page, created);
    dump(&DuplicateOut {
        document: doc.to_json()?,
        selection,
    })
}

/// Salida de [`duplicate_elements`]: el documento (como cadena JSON anidada, igual
/// que el resto de la superficie FFI) y la selección con las copias creadas.
#[derive(Debug, Serialize)]
struct DuplicateOut {
    document: String,
    selection: Selection,
}

/// Qué color de un elemento se está cambiando.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
enum ColorTarget {
    /// Tinta: trazo, texto y contorno de forma.
    Stroke,
    /// Relleno de las formas cerradas; `null` lo quita.
    Fill,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct ColorSpec {
    target: ColorTarget,
    /// `null` sólo es válido para `fill` (quitar el relleno).
    color: Option<Color>,
}

/// Aplica un color de trazo o de relleno a los elementos indicados.
///
/// `color_json` es `{"target":"stroke","color":{"r":..,"g":..,"b":..,"a":..}}` o
/// `{"target":"fill","color":null}` para quitar el relleno. Los elementos que no
/// admiten esa propiedad se dejan intactos, sin error: cambiar el color de una
/// selección mixta es una operación normal, no un fallo.
pub fn set_elements_color(
    document_json: &str,
    page_id: &str,
    ids_json: &str,
    color_json: &str,
) -> DocResult<String> {
    let spec: ColorSpec = parse(color_json, "color")?;
    if spec.target == ColorTarget::Stroke && spec.color.is_none() {
        return Err(DocumentError::InvalidArgument(
            "el color de trazo no puede ser nulo".to_string(),
        ));
    }
    let mut doc = Document::from_json(document_json)?;
    let pid = page_id.parse()?;
    let ids = parse_ids(ids_json)?;
    let page = doc.page_mut(pid)?;
    for element in page.elements.iter_mut().filter(|e| ids.contains(&e.id)) {
        match spec.target {
            ColorTarget::Stroke => {
                if let Some(color) = spec.color {
                    element.set_stroke_color(color);
                }
            }
            ColorTarget::Fill => {
                element.set_fill_color(spec.color);
            }
        }
    }
    doc.to_json()
}

/// Devuelve un [`DocumentSummary`] como JSON.
pub fn summary(document_json: &str) -> DocResult<String> {
    let doc = Document::from_json(document_json)?;
    dump(&DocumentSummary {
        id: doc.id.to_string(),
        title: doc.metadata.title.clone(),
        schema_version: doc.metadata.schema_version,
        page_count: doc.page_count(),
        element_count: doc.element_count(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Documento de un solo lienzo con un rectángulo y un trazo alejado,
    /// para las pruebas de selección/borrado/color.
    fn doc_with_two_elements() -> (String, String, Vec<String>) {
        let doc = create_document("selección").unwrap();
        let doc = add_page(&doc, r#"{}"#).unwrap();
        let page_id = Document::from_json(&doc).unwrap().pages[0].id.to_string();
        let doc = add_shape(
            &doc,
            &page_id,
            r#"{"kind":"Rectangle","bounds":{"x":10.0,"y":10.0,"width":40.0,"height":40.0},
                "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#,
        )
        .unwrap();
        let doc = add_stroke(
            &doc,
            &page_id,
            r#"{"points":[{"position":{"x":300.0,"y":300.0},"pressure":0.5,"timestamp_ms":0},
                {"position":{"x":320.0,"y":320.0},"pressure":0.5,"timestamp_ms":8}],
                "color":{"r":0,"g":0,"b":0,"a":255},"width":2.0}"#,
        )
        .unwrap();
        let ids = Document::from_json(&doc).unwrap().pages[0]
            .elements
            .iter()
            .map(|e| e.id.to_string())
            .collect();
        (doc, page_id, ids)
    }

    #[test]
    fn select_in_area_takes_only_fully_contained_elements() {
        let (doc, page_id, ids) = doc_with_two_elements();
        let sel: serde_json::Value = serde_json::from_str(
            &select_in_area(&doc, &page_id, r#"{"x":0.0,"y":0.0,"width":100.0,"height":100.0}"#)
                .unwrap(),
        )
        .unwrap();
        assert_eq!(sel["ids"].as_array().unwrap().len(), 1);
        assert_eq!(sel["ids"][0], ids[0]);
        // La caja devuelta envuelve al rectángulo (con medio grosor de trazo).
        assert!(sel["bounds"]["width"].as_f64().unwrap() >= 40.0);
    }

    #[test]
    fn select_in_area_normalizes_a_backwards_drag() {
        let (doc, page_id, _) = doc_with_two_elements();
        let sel: serde_json::Value = serde_json::from_str(
            &select_in_area(&doc, &page_id, r#"{"x":100.0,"y":100.0,"width":-100.0,"height":-100.0}"#)
                .unwrap(),
        )
        .unwrap();
        assert_eq!(sel["ids"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn select_at_picks_the_topmost_element_under_the_point() {
        let (doc, page_id, ids) = doc_with_two_elements();
        let hit: serde_json::Value =
            serde_json::from_str(&select_at(&doc, &page_id, 30.0, 30.0).unwrap()).unwrap();
        assert_eq!(hit["ids"][0], ids[0]);

        let miss: serde_json::Value =
            serde_json::from_str(&select_at(&doc, &page_id, 900.0, 900.0).unwrap()).unwrap();
        assert!(miss["ids"].as_array().unwrap().is_empty());
        assert!(miss["bounds"].is_null());
    }

    #[test]
    fn remove_elements_drops_the_listed_ids_and_ignores_the_unknown() {
        let (doc, page_id, ids) = doc_with_two_elements();
        let updated = remove_elements(&doc, &page_id, &format!(r#"["{}"]"#, ids[0])).unwrap();
        assert_eq!(Document::from_json(&updated).unwrap().element_count(), 1);
        // Repetir el borrado no es un error.
        let again = remove_elements(&updated, &page_id, &format!(r#"["{}"]"#, ids[0])).unwrap();
        assert_eq!(Document::from_json(&again).unwrap().element_count(), 1);
    }

    #[test]
    fn translate_elements_moves_only_the_selection() {
        let (doc, page_id, ids) = doc_with_two_elements();
        let moved =
            translate_elements(&doc, &page_id, &format!(r#"["{}"]"#, ids[0]), 100.0, 0.0).unwrap();
        let page = &Document::from_json(&moved).unwrap().pages[0];
        match &page.element(ids[0].parse().unwrap()).unwrap().kind {
            ElementKind::Shape(sh) => assert_eq!(sh.bounds.x, 110.0),
            other => panic!("tipo inesperado: {other:?}"),
        }
        match &page.element(ids[1].parse().unwrap()).unwrap().kind {
            ElementKind::Stroke(s) => assert_eq!(s.points[0].position.x, 300.0),
            other => panic!("tipo inesperado: {other:?}"),
        }
    }

    #[test]
    fn duplicate_elements_adds_copies_and_returns_them_selected() {
        let (doc, page_id, ids) = doc_with_two_elements();
        let out: serde_json::Value = serde_json::from_str(
            &duplicate_elements(&doc, &page_id, &format!(r#"["{}"]"#, ids[0]), 20.0, 20.0).unwrap(),
        )
        .unwrap();
        let updated = Document::from_json(out["document"].as_str().unwrap()).unwrap();
        assert_eq!(updated.element_count(), 3);
        let copies = out["selection"]["ids"].as_array().unwrap();
        assert_eq!(copies.len(), 1);
        assert_ne!(copies[0].as_str().unwrap(), ids[0]);
    }

    #[test]
    fn set_elements_color_changes_stroke_and_fill_where_it_applies() {
        let (doc, page_id, ids) = doc_with_two_elements();
        let all = format!(r#"["{}","{}"]"#, ids[0], ids[1]);
        let inked = set_elements_color(
            &doc,
            &page_id,
            &all,
            r#"{"target":"stroke","color":{"r":200,"g":0,"b":0,"a":255}}"#,
        )
        .unwrap();
        let filled = set_elements_color(
            &inked,
            &page_id,
            &all,
            r#"{"target":"fill","color":{"r":0,"g":0,"b":200,"a":255}}"#,
        )
        .unwrap();
        let page = &Document::from_json(&filled).unwrap().pages[0];
        match &page.element(ids[0].parse().unwrap()).unwrap().kind {
            ElementKind::Shape(sh) => {
                assert_eq!(sh.stroke_color, super::Color::rgb(200, 0, 0));
                // El relleno sólo se aplica a la forma cerrada.
                assert_eq!(sh.fill_color, Some(super::Color::rgb(0, 0, 200)));
            }
            other => panic!("tipo inesperado: {other:?}"),
        }
        match &page.element(ids[1].parse().unwrap()).unwrap().kind {
            ElementKind::Stroke(s) => assert_eq!(s.color, super::Color::rgb(200, 0, 0)),
            other => panic!("tipo inesperado: {other:?}"),
        }
    }

    #[test]
    fn stroke_color_cannot_be_removed() {
        let (doc, page_id, ids) = doc_with_two_elements();
        let err = set_elements_color(
            &doc,
            &page_id,
            &format!(r#"["{}"]"#, ids[0]),
            r#"{"target":"stroke","color":null}"#,
        )
        .unwrap_err();
        assert!(matches!(err, DocumentError::InvalidArgument(_)));
    }

    #[test]
    fn infinite_page_is_accepted_and_reported_by_the_scene() {
        let doc = create_document("lienzo").unwrap();
        let doc = add_page(&doc, r#"{"size":{"format":"Infinite"}}"#).unwrap();
        let page_id = Document::from_json(&doc).unwrap().pages[0].id.to_string();
        let doc = add_stroke(
            &doc,
            &page_id,
            r#"{"points":[{"position":{"x":5000.0,"y":40.0},"pressure":0.5,"timestamp_ms":0}],
                "color":{"r":0,"g":0,"b":0,"a":255},"width":2.0}"#,
        )
        .unwrap();
        let scene: serde_json::Value =
            serde_json::from_str(&super::super::render::render_page(&doc, 0).unwrap()).unwrap();
        assert_eq!(scene["infinite"], true);
        // El lienzo creció para cubrir un trazo muy a la derecha.
        assert!(scene["width_px"].as_f64().unwrap() > 5000.0);
        assert_eq!(scene["hits"].as_array().unwrap().len(), 1);
        assert_eq!(scene["hits"][0]["kind"], "Stroke");
    }

    #[test]
    fn create_then_summarize() {
        let doc = create_document("Prueba").unwrap();
        let sum: serde_json::Value =
            serde_json::from_str(&summary(&doc).unwrap()).unwrap();
        assert_eq!(sum["title"], "Prueba");
        assert_eq!(sum["page_count"], 0);
        assert_eq!(sum["schema_version"], 1);
    }

    #[test]
    fn add_page_with_empty_spec_defaults_to_a4_blank() {
        let doc = create_document("d").unwrap();
        let doc = add_page(&doc, "{}").unwrap();
        let parsed = Document::from_json(&doc).unwrap();
        assert_eq!(parsed.page_count(), 1);
        assert_eq!(parsed.pages[0].size, PageSize::A4);
        assert_eq!(parsed.pages[0].template, PageTemplate::Blank);
    }

    #[test]
    fn add_page_with_grid_spec() {
        let doc = create_document("d").unwrap();
        let spec = r#"{"size":{"format":"A5"},"template":{"kind":"Grid","spacing":24.0}}"#;
        let doc = add_page(&doc, spec).unwrap();
        let parsed = Document::from_json(&doc).unwrap();
        assert_eq!(parsed.pages[0].size, PageSize::A5);
        assert_eq!(parsed.pages[0].template, PageTemplate::Grid(24.0));
    }

    #[test]
    fn add_element_and_count_it() {
        let doc = create_document("d").unwrap();
        let doc = add_page(&doc, "{}").unwrap();
        let page_id = Document::from_json(&doc).unwrap().pages[0].id.to_string();

        let text = r#"{"type":"Text","content":"hola","position":{"x":10.0,"y":20.0},
            "style":{"font_size":16.0,"bold":false,"italic":false,"underline":false,
            "color":{"r":0,"g":0,"b":0,"a":255}},"max_width":null}"#;
        let doc = add_element(&doc, &page_id, text).unwrap();

        let sum: serde_json::Value =
            serde_json::from_str(&summary(&doc).unwrap()).unwrap();
        assert_eq!(sum["element_count"], 1);
    }

    #[test]
    fn add_element_to_missing_page_errors() {
        let doc = create_document("d").unwrap();
        let err = add_element(&doc, "00000000000000000000000000000009", "{}").unwrap_err();
        // El id es válido pero la página no existe.
        assert!(matches!(err, DocumentError::Serialization(_) | DocumentError::PageNotFound(_)));
    }

    #[test]
    fn invalid_page_id_is_reported() {
        let doc = create_document("d").unwrap();
        let err = remove_page(&doc, "no-hex").unwrap_err();
        assert!(matches!(err, DocumentError::InvalidId(_)));
    }

    #[test]
    fn translate_page_elements_moves_shape() {
        let doc = create_document("d").unwrap();
        let doc = add_page(&doc, "{}").unwrap();
        let page_id = Document::from_json(&doc).unwrap().pages[0].id.to_string();
        let shape = r#"{"type":"Shape","kind":"Rectangle",
            "bounds":{"x":0.0,"y":0.0,"width":10.0,"height":10.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":1.0}"#;
        let doc = add_element(&doc, &page_id, shape).unwrap();
        let doc = translate_page_elements(&doc, &page_id, 5.0, 7.0).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        let json = serde_json::to_value(&parsed.pages[0].elements[0]).unwrap();
        assert_eq!(json["kind"]["bounds"]["x"], 5.0);
        assert_eq!(json["kind"]["bounds"]["y"], 7.0);
    }

    fn doc_with_blank_page() -> (String, String) {
        let doc = create_document("trazos").unwrap();
        let doc = add_page(&doc, "{}").unwrap();
        let page_id = Document::from_json(&doc).unwrap().pages[0].id.to_string();
        (doc, page_id)
    }

    #[test]
    fn add_stroke_inserts_stroke_and_clamps_pressure() {
        let (doc, page_id) = doc_with_blank_page();
        let stroke = r#"{"points":[
            {"position":{"x":1.0,"y":2.0},"pressure":0.4,"timestamp_ms":0},
            {"position":{"x":3.0,"y":4.0},"pressure":2.5,"timestamp_ms":16}],
            "color":{"r":10,"g":20,"b":30,"a":255},"width":3.0}"#;
        let doc = add_stroke(&doc, &page_id, stroke).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        match &parsed.pages[0].elements[0].kind {
            ElementKind::Stroke(s) => {
                assert_eq!(s.points.len(), 2);
                assert_eq!(s.points[1].pressure, 1.0);
                assert_eq!(s.points[0].timestamp_ms, 0);
            }
            other => panic!("esperaba Stroke, no {other:?}"),
        }
    }

    #[test]
    fn add_stroke_drops_non_finite_points_and_rejects_empty() {
        let (doc, page_id) = doc_with_blank_page();
        // Todas las muestras son no finitas -> no queda ningún punto.
        let stroke = r#"{"points":[
            {"position":{"x":null,"y":2.0},"pressure":0.4,"timestamp_ms":0}],
            "color":{"r":0,"g":0,"b":0,"a":255},"width":3.0}"#;
        // `null` no es un f32 válido: fallo de serialización controlado.
        assert!(matches!(
            add_stroke(&doc, &page_id, stroke),
            Err(DocumentError::Serialization(_))
        ));

        let empty = r#"{"points":[],"color":{"r":0,"g":0,"b":0,"a":255},"width":3.0}"#;
        assert!(matches!(
            add_stroke(&doc, &page_id, empty),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn add_stroke_raises_min_width() {
        let (doc, page_id) = doc_with_blank_page();
        let stroke = r#"{"points":[
            {"position":{"x":0.0,"y":0.0},"pressure":0.5,"timestamp_ms":0}],
            "color":{"r":0,"g":0,"b":0,"a":255},"width":0.0}"#;
        let doc = add_stroke(&doc, &page_id, stroke).unwrap();
        let parsed = Document::from_json(&doc).unwrap();
        if let ElementKind::Stroke(s) = &parsed.pages[0].elements[0].kind {
            assert_eq!(s.width, MIN_STROKE_WIDTH);
        } else {
            panic!("esperaba Stroke");
        }
    }

    #[test]
    fn add_stroke_to_missing_page_errors() {
        let (doc, _) = doc_with_blank_page();
        let stroke = r#"{"points":[
            {"position":{"x":0.0,"y":0.0},"pressure":0.5,"timestamp_ms":0}],
            "color":{"r":0,"g":0,"b":0,"a":255},"width":2.0}"#;
        let err = add_stroke(&doc, "00000000000000000000000000000009", stroke).unwrap_err();
        assert!(matches!(err, DocumentError::PageNotFound(_)));
    }

    #[test]
    fn add_shape_normalizes_negative_rectangle_bounds() {
        let (doc, page_id) = doc_with_blank_page();
        // Arrastre de abajo-derecha hacia arriba-izquierda: width/height negativos.
        let shape = r#"{"kind":"Rectangle","bounds":{"x":100.0,"y":100.0,"width":-40.0,"height":-30.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#;
        let doc = add_shape(&doc, &page_id, shape).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        match &parsed.pages[0].elements[0].kind {
            ElementKind::Shape(s) => {
                assert_eq!(s.kind, ShapeKind::Rectangle);
                assert_eq!((s.bounds.x, s.bounds.y), (60.0, 70.0));
                assert_eq!((s.bounds.width, s.bounds.height), (40.0, 30.0));
            }
            other => panic!("esperaba Shape, no {other:?}"),
        }
    }

    #[test]
    fn add_shape_keeps_line_direction() {
        let (doc, page_id) = doc_with_blank_page();
        let shape = r#"{"kind":"Line","bounds":{"x":10.0,"y":10.0,"width":-50.0,"height":20.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#;
        let doc = add_shape(&doc, &page_id, shape).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        if let ElementKind::Shape(s) = &parsed.pages[0].elements[0].kind {
            // El signo se conserva: el vector del extremo no se normaliza.
            assert_eq!((s.bounds.width, s.bounds.height), (-50.0, 20.0));
        } else {
            panic!("esperaba Shape");
        }
    }

    #[test]
    fn add_shape_rejects_degenerate_geometry() {
        let (doc, page_id) = doc_with_blank_page();
        let tiny_rect = r#"{"kind":"Ellipse","bounds":{"x":0.0,"y":0.0,"width":0.4,"height":0.3},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#;
        assert!(matches!(
            add_shape(&doc, &page_id, tiny_rect),
            Err(DocumentError::InvalidArgument(_))
        ));

        let short_line = r#"{"kind":"Arrow","bounds":{"x":5.0,"y":5.0,"width":0.2,"height":0.1},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#;
        assert!(matches!(
            add_shape(&doc, &page_id, short_line),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn add_shape_raises_min_stroke_width_and_accepts_fill() {
        let (doc, page_id) = doc_with_blank_page();
        let shape = r#"{"kind":"Rectangle","bounds":{"x":0.0,"y":0.0,"width":50.0,"height":40.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},
            "fill_color":{"r":200,"g":210,"b":220,"a":128},"stroke_width":0.0}"#;
        let doc = add_shape(&doc, &page_id, shape).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        if let ElementKind::Shape(s) = &parsed.pages[0].elements[0].kind {
            assert_eq!(s.stroke_width, MIN_STROKE_WIDTH);
            assert_eq!(s.fill_color.map(|c| c.a), Some(128));
        } else {
            panic!("esperaba Shape");
        }
    }

    #[test]
    fn add_shape_to_missing_page_errors() {
        let (doc, _) = doc_with_blank_page();
        let shape = r#"{"kind":"Rectangle","bounds":{"x":0.0,"y":0.0,"width":10.0,"height":10.0},
            "stroke_color":{"r":0,"g":0,"b":0,"a":255},"fill_color":null,"stroke_width":2.0}"#;
        let err = add_shape(&doc, "00000000000000000000000000000009", shape).unwrap_err();
        assert!(matches!(err, DocumentError::PageNotFound(_)));
    }

    #[test]
    fn sanitize_shape_rejects_non_finite_bounds() {
        use super::super::geometry::{Color, Rect};
        let shape = Shape {
            kind: ShapeKind::Rectangle,
            bounds: Rect::new(f32::NAN, 0.0, 10.0, 10.0),
            stroke_color: Color::BLACK,
            fill_color: None,
            stroke_width: 2.0,
        };
        assert!(matches!(
            sanitize_shape(shape),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn add_text_inserts_block_and_trims_content() {
        let (doc, page_id) = doc_with_blank_page();
        let text = r#"{"content":"  Hola mundo  ","position":{"x":12.0,"y":24.0},
            "style":{"font_size":20.0,"bold":true,"italic":false,"underline":false,
            "color":{"r":0,"g":0,"b":0,"a":255}},"max_width":null}"#;
        let doc = add_text(&doc, &page_id, text).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        match &parsed.pages[0].elements[0].kind {
            ElementKind::Text(t) => {
                assert_eq!(t.content, "Hola mundo");
                assert_eq!((t.position.x, t.position.y), (12.0, 24.0));
                assert_eq!(t.style.font_size, 20.0);
                assert!(t.style.bold);
            }
            other => panic!("esperaba Text, no {other:?}"),
        }
    }

    #[test]
    fn add_text_rejects_empty_content() {
        let (doc, page_id) = doc_with_blank_page();
        let text = r#"{"content":"   ","position":{"x":0.0,"y":0.0},
            "style":{"font_size":16.0,"bold":false,"italic":false,"underline":false,
            "color":{"r":0,"g":0,"b":0,"a":255}},"max_width":null}"#;
        assert!(matches!(
            add_text(&doc, &page_id, text),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn add_text_clamps_font_size_and_drops_bad_max_width() {
        let (doc, page_id) = doc_with_blank_page();
        let text = r#"{"content":"x","position":{"x":1.0,"y":2.0},
            "style":{"font_size":0.5,"bold":false,"italic":false,"underline":false,
            "color":{"r":0,"g":0,"b":0,"a":255}},"max_width":-3.0}"#;
        let doc = add_text(&doc, &page_id, text).unwrap();
        let parsed = Document::from_json(&doc).unwrap();
        if let ElementKind::Text(t) = &parsed.pages[0].elements[0].kind {
            assert_eq!(t.style.font_size, MIN_FONT_SIZE);
            assert_eq!(t.max_width, None);
        } else {
            panic!("esperaba Text");
        }
    }

    #[test]
    fn add_text_to_missing_page_errors() {
        let (doc, _) = doc_with_blank_page();
        let text = r#"{"content":"x","position":{"x":0.0,"y":0.0},
            "style":{"font_size":16.0,"bold":false,"italic":false,"underline":false,
            "color":{"r":0,"g":0,"b":0,"a":255}},"max_width":null}"#;
        let err = add_text(&doc, "00000000000000000000000000000009", text).unwrap_err();
        assert!(matches!(err, DocumentError::PageNotFound(_)));
    }

    #[test]
    fn add_formula_parses_expression_into_ast() {
        let (doc, page_id) = doc_with_blank_page();
        let spec = r#"{"expression":"\\frac{a}{b} + 1","position":{"x":10.0,"y":20.0}}"#;
        let doc = add_formula(&doc, &page_id, spec).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        match &parsed.pages[0].elements[0].kind {
            ElementKind::Formula(f) => {
                assert_eq!(f.latex, r"\frac{a}{b} + 1");
                assert_eq!((f.position.x, f.position.y), (10.0, 20.0));
                assert!(f.ast.is_some(), "el AST debe haberse construido");
            }
            other => panic!("esperaba Formula, no {other:?}"),
        }
    }

    #[test]
    fn add_formula_rejects_bad_syntax_without_panic() {
        let (doc, page_id) = doc_with_blank_page();
        let spec = r#"{"expression":"1 + + )","position":{"x":0.0,"y":0.0}}"#;
        assert!(matches!(
            add_formula(&doc, &page_id, spec),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn add_formula_rejects_empty_and_non_finite_position() {
        let (doc, page_id) = doc_with_blank_page();
        let empty = r#"{"expression":"   ","position":{"x":0.0,"y":0.0}}"#;
        assert!(matches!(
            add_formula(&doc, &page_id, empty),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn add_formula_ast_roundtrips_and_evaluates() {
        use std::collections::HashMap;
        let (doc, page_id) = doc_with_blank_page();
        let spec = r#"{"expression":"2^{10}","position":{"x":1.0,"y":2.0}}"#;
        let doc = add_formula(&doc, &page_id, spec).unwrap();
        let parsed = Document::from_json(&doc).unwrap();
        if let ElementKind::Formula(f) = &parsed.pages[0].elements[0].kind {
            let ast = f.ast.as_ref().unwrap();
            let value = math::evaluate(ast, &HashMap::new()).unwrap();
            assert_eq!(value, 1024.0);
        } else {
            panic!("esperaba Formula");
        }
    }

    #[test]
    fn add_formula_to_missing_page_errors() {
        let (doc, _) = doc_with_blank_page();
        let spec = r#"{"expression":"1+1","position":{"x":0.0,"y":0.0}}"#;
        let err = add_formula(&doc, "00000000000000000000000000000009", spec).unwrap_err();
        assert!(matches!(err, DocumentError::PageNotFound(_)));
    }

    #[test]
    fn add_graph_parses_function_and_clamps_samples() {
        let (doc, page_id) = doc_with_blank_page();
        let spec = r#"{"expression":"sin(x)","position":{"x":10.0,"y":20.0},
            "width":300.0,"height":180.0,"x_min":-3.14159,"x_max":3.14159,"samples":100000}"#;
        let doc = add_graph(&doc, &page_id, spec).unwrap();
        let parsed = Document::from_json(&doc).unwrap();
        match &parsed.pages[0].elements[0].kind {
            ElementKind::Graph(g) => {
                assert_eq!(g.expression, "sin(x)");
                assert_eq!(g.var, "x");
                assert_eq!(g.samples, MAX_GRAPH_SAMPLES);
                assert!(g.ast.is_some());
                assert_eq!((g.frame.width, g.frame.height), (300.0, 180.0));
            }
            other => panic!("esperaba Graph, no {other:?}"),
        }
    }

    #[test]
    fn add_graph_rejects_free_symbols_other_than_var() {
        let (doc, page_id) = doc_with_blank_page();
        let spec = r#"{"expression":"a*x + b","position":{"x":0.0,"y":0.0},
            "width":100.0,"height":100.0,"x_min":-1.0,"x_max":1.0}"#;
        assert!(matches!(
            add_graph(&doc, &page_id, spec),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn add_graph_accepts_custom_variable() {
        let (doc, page_id) = doc_with_blank_page();
        let spec = r#"{"expression":"t^2","position":{"x":0.0,"y":0.0},
            "width":100.0,"height":100.0,"x_min":0.0,"x_max":5.0,"var":"t"}"#;
        assert!(add_graph(&doc, &page_id, spec).is_ok());
    }

    #[test]
    fn add_graph_rejects_bad_domain_tiny_frame_and_syntax() {
        let (doc, page_id) = doc_with_blank_page();
        let bad_domain = r#"{"expression":"x","position":{"x":0.0,"y":0.0},
            "width":100.0,"height":100.0,"x_min":2.0,"x_max":2.0}"#;
        assert!(matches!(
            add_graph(&doc, &page_id, bad_domain),
            Err(DocumentError::InvalidArgument(_))
        ));
        let tiny = r#"{"expression":"x","position":{"x":0.0,"y":0.0},
            "width":4.0,"height":100.0,"x_min":-1.0,"x_max":1.0}"#;
        assert!(matches!(
            add_graph(&doc, &page_id, tiny),
            Err(DocumentError::InvalidArgument(_))
        ));
        let bad_syntax = r#"{"expression":"x +* 2","position":{"x":0.0,"y":0.0},
            "width":100.0,"height":100.0,"x_min":-1.0,"x_max":1.0}"#;
        assert!(matches!(
            add_graph(&doc, &page_id, bad_syntax),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn add_graph_to_missing_page_errors() {
        let (doc, _) = doc_with_blank_page();
        let spec = r#"{"expression":"x","position":{"x":0.0,"y":0.0},
            "width":100.0,"height":100.0,"x_min":-1.0,"x_max":1.0}"#;
        let err = add_graph(&doc, "00000000000000000000000000000009", spec).unwrap_err();
        assert!(matches!(err, DocumentError::PageNotFound(_)));
    }

    #[test]
    fn add_image_inserts_and_keeps_natural_dimensions() {
        let (doc, page_id) = doc_with_blank_page();
        let spec = r#"{"source":"images/photo.png","position":{"x":15.0,"y":25.0},
            "width":200.0,"height":150.0,"natural_width":1600.0,"natural_height":1200.0}"#;
        let doc = add_image(&doc, &page_id, spec).unwrap();

        let parsed = Document::from_json(&doc).unwrap();
        match &parsed.pages[0].elements[0].kind {
            ElementKind::Image(im) => {
                assert_eq!(im.source, "images/photo.png");
                assert_eq!((im.frame.x, im.frame.y), (15.0, 25.0));
                assert_eq!((im.frame.width, im.frame.height), (200.0, 150.0));
                assert_eq!((im.natural_width, im.natural_height), (1600.0, 1200.0));
            }
            other => panic!("esperaba Image, no {other:?}"),
        }
    }

    #[test]
    fn add_image_rejects_absolute_and_traversing_paths() {
        let (doc, page_id) = doc_with_blank_page();
        for bad in [
            r#"{"source":"/sdcard/x.png","position":{"x":0.0,"y":0.0},"width":50.0,"height":50.0,"natural_width":10.0,"natural_height":10.0}"#,
            r#"{"source":"images/../../secret.png","position":{"x":0.0,"y":0.0},"width":50.0,"height":50.0,"natural_width":10.0,"natural_height":10.0}"#,
            r#"{"source":"C:\\pics\\x.png","position":{"x":0.0,"y":0.0},"width":50.0,"height":50.0,"natural_width":10.0,"natural_height":10.0}"#,
        ] {
            assert!(matches!(
                add_image(&doc, &page_id, bad),
                Err(DocumentError::InvalidArgument(_))
            ));
        }
    }

    #[test]
    fn add_image_rejects_tiny_frame_and_bad_natural_dimensions() {
        let (doc, page_id) = doc_with_blank_page();
        let tiny = r#"{"source":"images/x.png","position":{"x":0.0,"y":0.0},
            "width":4.0,"height":4.0,"natural_width":10.0,"natural_height":10.0}"#;
        assert!(matches!(
            add_image(&doc, &page_id, tiny),
            Err(DocumentError::InvalidArgument(_))
        ));
        let bad_natural = r#"{"source":"images/x.png","position":{"x":0.0,"y":0.0},
            "width":40.0,"height":40.0,"natural_width":0.0,"natural_height":10.0}"#;
        assert!(matches!(
            add_image(&doc, &page_id, bad_natural),
            Err(DocumentError::InvalidArgument(_))
        ));
    }

    #[test]
    fn add_image_to_missing_page_errors() {
        let (doc, _) = doc_with_blank_page();
        let spec = r#"{"source":"images/x.png","position":{"x":0.0,"y":0.0},
            "width":40.0,"height":40.0,"natural_width":10.0,"natural_height":10.0}"#;
        let err = add_image(&doc, "00000000000000000000000000000009", spec).unwrap_err();
        assert!(matches!(err, DocumentError::PageNotFound(_)));
    }

    #[test]
    fn malformed_document_json_errors() {
        assert!(matches!(
            add_page("{not json}", "{}"),
            Err(DocumentError::Serialization(_))
        ));
    }
}
