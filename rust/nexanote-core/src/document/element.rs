//! Elementos gráficos/documentales que viven en una página.
//!
//! Cada [`Element`] tiene un [`ElementId`] propio y un `z_index` para el orden de
//! pintado. El contenido concreto es un [`ElementKind`] fuertemente tipado.

use serde::{Deserialize, Serialize};

use super::geometry::{Color, Point, Rect, Transformable};
use super::id::ElementId;

/// Muestra individual de un trazo: posición, presión del stylus y tiempo.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct StrokePoint {
    pub position: Point,
    /// Presión normalizada `[0.0, 1.0]`.
    pub pressure: f32,
    /// Milisegundos relativos al inicio del trazo.
    pub timestamp_ms: u64,
}

/// Trazo a mano alzada.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Stroke {
    pub points: Vec<StrokePoint>,
    pub color: Color,
    /// Grosor base en unidades lógicas.
    pub width: f32,
}

/// Estilo tipográfico de un bloque de texto.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct TextStyle {
    pub font_size: f32,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub color: Color,
}

impl Default for TextStyle {
    fn default() -> Self {
        TextStyle {
            font_size: 16.0,
            bold: false,
            italic: false,
            underline: false,
            color: Color::BLACK,
        }
    }
}

/// Bloque de texto posicionado.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TextBox {
    pub content: String,
    pub position: Point,
    pub style: TextStyle,
    /// Ancho máximo para el ajuste de línea; `None` = sin límite.
    pub max_width: Option<f32>,
}

/// Nodo del **Árbol Sintáctico Abstracto** de una expresión matemática.
///
/// Lo produce el parser del motor matemático ([`crate::document::math`]) y es
/// evaluable y (de)serializable. Es la representación formal: nunca se manipula la
/// fórmula como texto una vez parseada.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "node", content = "value")]
pub enum FormulaNode {
    /// Literal numérico.
    Number(f64),
    /// Símbolo: variable (`x`) o constante conocida (`pi`, `e`).
    Symbol(String),
    /// Operación binaria `op` sobre dos subárboles.
    Binary {
        op: BinaryOp,
        lhs: Box<FormulaNode>,
        rhs: Box<FormulaNode>,
    },
    /// Fracción `numerator / denominator` (equivalente a `Binary { Div, .. }`,
    /// preservada como nodo propio por su semántica de presentación).
    Fraction {
        numerator: Box<FormulaNode>,
        denominator: Box<FormulaNode>,
    },
    /// Negación unaria `-operand`.
    Neg(Box<FormulaNode>),
    /// Raíz cuadrada `sqrt(radicand)`.
    Sqrt(Box<FormulaNode>),
    /// Raíz de índice arbitrario: `radicand ^ (1 / degree)`.
    Root {
        degree: Box<FormulaNode>,
        radicand: Box<FormulaNode>,
    },
    /// Sumatoria `sum_{var = from}^{to} body`.
    Sum {
        var: String,
        from: Box<FormulaNode>,
        to: Box<FormulaNode>,
        body: Box<FormulaNode>,
    },
    /// Llamada a función de una variable (`sin(x)`, `ln(x)`, ...).
    Call {
        func: MathFunc,
        arg: Box<FormulaNode>,
    },
}

/// Operadores binarios soportados por el AST.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum BinaryOp {
    Add,
    Sub,
    Mul,
    Div,
    Pow,
}

/// Funciones matemáticas de una variable reconocidas por el parser y el evaluador.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MathFunc {
    Sin,
    Cos,
    Tan,
    /// Logaritmo natural.
    Ln,
    /// Logaritmo en base 10.
    Log,
    /// Exponencial `e^x`.
    Exp,
    /// Valor absoluto.
    Abs,
}

/// Fórmula matemática: fuente LaTeX + AST preliminar opcional.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Formula {
    pub latex: String,
    pub position: Point,
    /// AST estructurado; `None` mientras sólo se tenga la fuente LaTeX.
    pub ast: Option<FormulaNode>,
}

/// Gráfica de una función `y = f(var)` sobre un dominio `[x_min, x_max]`.
///
/// El muestreo numérico de la curva **no** se guarda en el modelo: lo calcula la
/// capa de *render* ([`crate::document::render`]) a partir del AST, de forma
/// eficiente y fuera del hilo de UI. Aquí sólo vive la definición.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Graph {
    /// Fuente de la función, en el dialecto ASCII/LaTeX del motor matemático.
    pub expression: String,
    /// AST tipado de la función; `None` mientras sólo se tenga la fuente.
    pub ast: Option<FormulaNode>,
    /// Nombre de la variable independiente (normalmente `"x"`).
    pub var: String,
    /// Marco de la gráfica en la página (px lógicos @1x): esquina + tamaño.
    pub frame: Rect,
    /// Extremo inferior del dominio.
    pub x_min: f64,
    /// Extremo superior del dominio.
    pub x_max: f64,
    /// Número de puntos a muestrear en `[x_min, x_max]`.
    pub samples: u32,
}

/// Tipo de forma geométrica.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ShapeKind {
    Rectangle,
    Ellipse,
    Line,
    Arrow,
}

/// Forma geométrica delimitada por un rectángulo.
///
/// Para `Line`/`Arrow`, `bounds` define los extremos (de la esquina origen a la
/// opuesta).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Shape {
    pub kind: ShapeKind,
    pub bounds: Rect,
    pub stroke_color: Color,
    pub fill_color: Option<Color>,
    pub stroke_width: f32,
}

/// Imagen rasterizada importada por el usuario y **copiada al almacenamiento local
/// del documento** para portabilidad offline-first (nunca se depende de una ruta
/// externa efímera).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ImageRef {
    /// Ruta del recurso **relativa** al directorio de activos de la app
    /// (p. ej. `images/9f3c….png`). Nunca una ruta absoluta ni externa.
    pub source: String,
    /// Marco de la imagen en la página (esquina superior-izquierda + tamaño), en
    /// px lógicos @1x.
    pub frame: Rect,
    /// Anchura intrínseca del bitmap de origen en px; conserva la proporción al
    /// re-encuadrar o escalar.
    pub natural_width: f32,
    /// Altura intrínseca del bitmap de origen en px.
    pub natural_height: f32,
}

/// Contenido concreto de un elemento.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum ElementKind {
    Stroke(Stroke),
    Text(TextBox),
    Formula(Formula),
    Shape(Shape),
    Graph(Graph),
    Image(ImageRef),
}

/// Elemento identificable dentro de una página.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Element {
    pub id: ElementId,
    pub z_index: i32,
    pub kind: ElementKind,
}

impl Element {
    /// Crea un elemento nuevo con id generado y `z_index` dado.
    pub fn new(kind: ElementKind, z_index: i32) -> Self {
        Element {
            id: ElementId::generate(),
            z_index,
            kind,
        }
    }

    /// **Caja envolvente** del elemento en coordenadas de página (px lógicos @1x).
    ///
    /// Es la geometría con la que se resuelven la selección por área, el impacto
    /// de un toque y el crecimiento del lienzo infinito, de modo que esa decisión
    /// vive en el núcleo y no se duplica en cada capa de UI.
    ///
    /// Para el texto y las fórmulas no hay motor tipográfico en el núcleo: se
    /// estima la caja a partir del tamaño de fuente y del número de caracteres,
    /// con la misma métrica aproximada que usa el render.
    pub fn bounds(&self) -> Rect {
        match &self.kind {
            ElementKind::Stroke(s) => stroke_bounds(s),
            ElementKind::Text(t) => text_bounds(
                &t.content,
                t.position,
                t.style.font_size,
                t.max_width,
            ),
            // El origen de una fórmula es su línea base, igual que el del texto.
            ElementKind::Formula(f) => {
                text_bounds(&f.latex, f.position, FORMULA_FONT_SIZE, None)
            }
            ElementKind::Shape(sh) => sh.bounds.normalized().inflated(sh.stroke_width * 0.5),
            ElementKind::Graph(g) => g.frame.normalized(),
            ElementKind::Image(im) => im.frame.normalized(),
        }
    }

    /// Cambia el color de trazo/tinta del elemento. Devuelve `false` si el tipo
    /// no tiene color de trazo propio (una imagen, por ejemplo).
    pub fn set_stroke_color(&mut self, color: Color) -> bool {
        match &mut self.kind {
            ElementKind::Stroke(s) => {
                s.color = color;
                true
            }
            ElementKind::Text(t) => {
                t.style.color = color;
                true
            }
            ElementKind::Shape(sh) => {
                sh.stroke_color = color;
                true
            }
            ElementKind::Formula(_) | ElementKind::Graph(_) | ElementKind::Image(_) => false,
        }
    }

    /// Cambia el relleno del elemento (`None` = sin relleno). Sólo las formas
    /// cerradas (rectángulo y elipse) admiten relleno; devuelve `false` en el
    /// resto de casos.
    pub fn set_fill_color(&mut self, fill: Option<Color>) -> bool {
        match &mut self.kind {
            ElementKind::Shape(sh) if sh.kind.is_closed() => {
                sh.fill_color = fill;
                true
            }
            _ => false,
        }
    }

    /// Nombre estable del tipo, para diagnóstico y resúmenes.
    pub fn kind_name(&self) -> &'static str {
        match self.kind {
            ElementKind::Stroke(_) => "Stroke",
            ElementKind::Text(_) => "Text",
            ElementKind::Formula(_) => "Formula",
            ElementKind::Shape(_) => "Shape",
            ElementKind::Graph(_) => "Graph",
            ElementKind::Image(_) => "Image",
        }
    }
}

/// Tamaño de fuente con el que el render pinta una fórmula (ver `render.rs`).
const FORMULA_FONT_SIZE: f32 = 18.0;

/// Anchura media de un glifo como fracción del tamaño de fuente. Aproximación
/// deliberada: el núcleo no mide texto, sólo necesita una caja de impacto útil.
const GLYPH_ASPECT: f32 = 0.55;

/// Caja de un trazo: extremos de sus puntos, ensanchada medio grosor por lado.
fn stroke_bounds(s: &Stroke) -> Rect {
    let mut iter = s.points.iter();
    let first = match iter.next() {
        Some(p) => p.position,
        None => return Rect::new(0.0, 0.0, 0.0, 0.0),
    };
    let (mut min_x, mut min_y, mut max_x, mut max_y) = (first.x, first.y, first.x, first.y);
    for p in iter {
        min_x = min_x.min(p.position.x);
        min_y = min_y.min(p.position.y);
        max_x = max_x.max(p.position.x);
        max_y = max_y.max(p.position.y);
    }
    Rect::new(min_x, min_y, max_x - min_x, max_y - min_y).inflated(s.width.max(0.0) * 0.5)
}

/// Caja estimada de un bloque de texto. `position` es la **línea base** de la
/// primera línea, así que la caja se levanta un tamaño de fuente por encima.
fn text_bounds(content: &str, position: Point, font_size: f32, max_width: Option<f32>) -> Rect {
    let size = font_size.max(1.0);
    let chars = content.chars().count().max(1) as f32;
    let natural = chars * size * GLYPH_ASPECT;
    let (width, lines) = match max_width {
        Some(limit) if limit > 0.0 && natural > limit => {
            (limit, (natural / limit).ceil().max(1.0))
        }
        _ => (natural, 1.0),
    };
    Rect::new(
        position.x,
        position.y - size,
        width,
        size * 1.25 * lines,
    )
}

impl ShapeKind {
    /// `true` si la forma delimita un área y por tanto admite relleno.
    pub fn is_closed(self) -> bool {
        matches!(self, ShapeKind::Rectangle | ShapeKind::Ellipse)
    }
}

impl Transformable for Stroke {
    fn translate(&mut self, dx: f32, dy: f32) {
        for p in &mut self.points {
            p.position.translate(dx, dy);
        }
    }

    fn scale(&mut self, factor: f32, origin: Point) {
        for p in &mut self.points {
            p.position.scale(factor, origin);
        }
        self.width *= factor;
    }
}

impl Transformable for TextBox {
    fn translate(&mut self, dx: f32, dy: f32) {
        self.position.translate(dx, dy);
    }

    fn scale(&mut self, factor: f32, origin: Point) {
        self.position.scale(factor, origin);
        self.style.font_size *= factor;
        self.max_width = self.max_width.map(|w| w * factor);
    }
}

impl Transformable for Formula {
    fn translate(&mut self, dx: f32, dy: f32) {
        self.position.translate(dx, dy);
    }

    fn scale(&mut self, factor: f32, origin: Point) {
        self.position.scale(factor, origin);
    }
}

impl Transformable for Graph {
    fn translate(&mut self, dx: f32, dy: f32) {
        self.frame.translate(dx, dy);
    }

    fn scale(&mut self, factor: f32, origin: Point) {
        self.frame.scale(factor, origin);
    }
}

impl Transformable for Shape {
    fn translate(&mut self, dx: f32, dy: f32) {
        self.bounds.translate(dx, dy);
    }

    fn scale(&mut self, factor: f32, origin: Point) {
        self.bounds.scale(factor, origin);
        self.stroke_width *= factor;
    }
}

impl Transformable for ImageRef {
    fn translate(&mut self, dx: f32, dy: f32) {
        self.frame.translate(dx, dy);
    }

    fn scale(&mut self, factor: f32, origin: Point) {
        self.frame.scale(factor, origin);
    }
}

impl Transformable for Element {
    fn translate(&mut self, dx: f32, dy: f32) {
        match &mut self.kind {
            ElementKind::Stroke(s) => s.translate(dx, dy),
            ElementKind::Text(t) => t.translate(dx, dy),
            ElementKind::Formula(fm) => fm.translate(dx, dy),
            ElementKind::Shape(sh) => sh.translate(dx, dy),
            ElementKind::Graph(g) => g.translate(dx, dy),
            ElementKind::Image(im) => im.translate(dx, dy),
        }
    }

    fn scale(&mut self, factor: f32, origin: Point) {
        match &mut self.kind {
            ElementKind::Stroke(s) => s.scale(factor, origin),
            ElementKind::Text(t) => t.scale(factor, origin),
            ElementKind::Formula(fm) => fm.scale(factor, origin),
            ElementKind::Shape(sh) => sh.scale(factor, origin),
            ElementKind::Graph(g) => g.scale(factor, origin),
            ElementKind::Image(im) => im.scale(factor, origin),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_stroke() -> Stroke {
        Stroke {
            points: vec![
                StrokePoint { position: Point::new(0.0, 0.0), pressure: 0.5, timestamp_ms: 0 },
                StrokePoint { position: Point::new(10.0, 0.0), pressure: 0.8, timestamp_ms: 16 },
            ],
            color: Color::BLACK,
            width: 2.0,
        }
    }

    #[test]
    fn stroke_translates_all_points() {
        let mut s = sample_stroke();
        s.translate(3.0, 4.0);
        assert_eq!(s.points[0].position, Point::new(3.0, 4.0));
        assert_eq!(s.points[1].position, Point::new(13.0, 4.0));
    }

    #[test]
    fn stroke_scales_points_and_width() {
        let mut s = sample_stroke();
        s.scale(2.0, Point::ORIGIN);
        assert_eq!(s.points[1].position, Point::new(20.0, 0.0));
        assert_eq!(s.width, 4.0);
    }

    #[test]
    fn element_dispatches_transform_by_kind() {
        let mut el = Element::new(
            ElementKind::Shape(Shape {
                kind: ShapeKind::Rectangle,
                bounds: Rect::new(0.0, 0.0, 10.0, 10.0),
                stroke_color: Color::BLACK,
                fill_color: None,
                stroke_width: 1.0,
            }),
            0,
        );
        el.translate(5.0, 5.0);
        if let ElementKind::Shape(sh) = &el.kind {
            assert_eq!(sh.bounds.origin(), Point::new(5.0, 5.0));
        } else {
            panic!("tipo inesperado");
        }
    }

    #[test]
    fn element_kind_roundtrips_json() {
        let el = Element::new(ElementKind::Stroke(sample_stroke()), 2);
        let json = serde_json::to_string(&el).unwrap();
        let back: Element = serde_json::from_str(&json).unwrap();
        assert_eq!(back, el);
    }

    #[test]
    fn image_roundtrips_json_and_transforms_frame() {
        let mut el = Element::new(
            ElementKind::Image(ImageRef {
                source: "images/foo.png".to_string(),
                frame: Rect::new(10.0, 20.0, 100.0, 80.0),
                natural_width: 640.0,
                natural_height: 512.0,
            }),
            3,
        );
        let json = serde_json::to_string(&el).unwrap();
        assert_eq!(serde_json::from_str::<Element>(&json).unwrap(), el);

        el.translate(5.0, 7.0);
        el.scale(2.0, Point::ORIGIN);
        if let ElementKind::Image(im) = &el.kind {
            assert_eq!(im.frame, Rect::new(30.0, 54.0, 200.0, 160.0));
            assert_eq!(im.source, "images/foo.png");
        } else {
            panic!("tipo inesperado");
        }
    }

    fn rect_shape(kind: ShapeKind, bounds: Rect, stroke_width: f32) -> Element {
        Element::new(
            ElementKind::Shape(Shape {
                kind,
                bounds,
                stroke_color: Color::BLACK,
                fill_color: None,
                stroke_width,
            }),
            0,
        )
    }

    #[test]
    fn empty_stroke_bounds_is_a_zero_rect() {
        let el = Element::new(
            ElementKind::Stroke(Stroke { points: vec![], color: Color::BLACK, width: 2.0 }),
            0,
        );
        assert_eq!(el.bounds(), Rect::new(0.0, 0.0, 0.0, 0.0));
    }

    #[test]
    fn stroke_bounds_span_points_and_inflate_by_half_width() {
        let el = Element::new(ElementKind::Stroke(sample_stroke()), 0);
        // Puntos (0,0)-(10,0), grosor 2.0 -> caja ensanchada 1.0 por lado.
        assert_eq!(el.bounds(), Rect::new(-1.0, -1.0, 12.0, 2.0));
    }

    #[test]
    fn shape_bounds_normalize_and_inflate_by_half_stroke() {
        let el = rect_shape(ShapeKind::Rectangle, Rect::new(10.0, 10.0, -20.0, -20.0), 4.0);
        // Normalizado a (−10,−10,20,20) e inflado 2.0 por lado.
        assert_eq!(el.bounds(), Rect::new(-12.0, -12.0, 24.0, 24.0));
    }

    #[test]
    fn graph_and_image_bounds_are_the_normalized_frame() {
        let graph = Element::new(
            ElementKind::Graph(Graph {
                expression: "x".to_string(),
                ast: None,
                var: "x".to_string(),
                frame: Rect::new(0.0, 0.0, 100.0, 50.0),
                x_min: -1.0,
                x_max: 1.0,
                samples: 32,
            }),
            0,
        );
        assert_eq!(graph.bounds(), Rect::new(0.0, 0.0, 100.0, 50.0));

        let image = Element::new(
            ElementKind::Image(ImageRef {
                source: "images/a.png".to_string(),
                frame: Rect::new(5.0, 6.0, 40.0, 30.0),
                natural_width: 400.0,
                natural_height: 300.0,
            }),
            0,
        );
        assert_eq!(image.bounds(), Rect::new(5.0, 6.0, 40.0, 30.0));
    }

    #[test]
    fn text_bounds_lift_the_box_above_the_baseline() {
        let el = Element::new(
            ElementKind::Text(TextBox {
                content: "abcd".to_string(),
                position: Point::new(10.0, 100.0),
                style: TextStyle { font_size: 16.0, ..TextStyle::default() },
                max_width: None,
            }),
            0,
        );
        let b = el.bounds();
        assert_eq!((b.x, b.y), (10.0, 84.0));
        assert!(b.width > 0.0 && b.height > 0.0);
    }

    #[test]
    fn set_stroke_color_only_succeeds_where_there_is_ink() {
        let mut stroke = Element::new(ElementKind::Stroke(sample_stroke()), 0);
        assert!(stroke.set_stroke_color(Color::rgb(1, 2, 3)));

        let mut graph = Element::new(
            ElementKind::Graph(Graph {
                expression: "x".to_string(),
                ast: None,
                var: "x".to_string(),
                frame: Rect::new(0.0, 0.0, 20.0, 20.0),
                x_min: 0.0,
                x_max: 1.0,
                samples: 8,
            }),
            0,
        );
        assert!(!graph.set_stroke_color(Color::rgb(1, 2, 3)));
    }

    #[test]
    fn set_fill_color_only_succeeds_on_closed_shapes() {
        let mut rect = rect_shape(ShapeKind::Rectangle, Rect::new(0.0, 0.0, 10.0, 10.0), 1.0);
        assert!(rect.set_fill_color(Some(Color::rgb(9, 9, 9))));
        assert!(rect.set_fill_color(None));

        let mut line = rect_shape(ShapeKind::Line, Rect::new(0.0, 0.0, 10.0, 10.0), 1.0);
        assert!(!line.set_fill_color(Some(Color::rgb(9, 9, 9))));

        let mut stroke = Element::new(ElementKind::Stroke(sample_stroke()), 0);
        assert!(!stroke.set_fill_color(Some(Color::rgb(9, 9, 9))));
    }

    #[test]
    fn formula_ast_roundtrips_json() {
        let f = Formula {
            latex: "a+b".to_string(),
            position: Point::ORIGIN,
            ast: Some(FormulaNode::Binary {
                op: BinaryOp::Add,
                lhs: Box::new(FormulaNode::Symbol("a".to_string())),
                rhs: Box::new(FormulaNode::Symbol("b".to_string())),
            }),
        };
        let json = serde_json::to_string(&f).unwrap();
        assert_eq!(serde_json::from_str::<Formula>(&json).unwrap(), f);
    }
}
