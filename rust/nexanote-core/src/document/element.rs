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

/// Contenido concreto de un elemento.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum ElementKind {
    Stroke(Stroke),
    Text(TextBox),
    Formula(Formula),
    Shape(Shape),
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

    /// Nombre estable del tipo, para diagnóstico y resúmenes.
    pub fn kind_name(&self) -> &'static str {
        match self.kind {
            ElementKind::Stroke(_) => "Stroke",
            ElementKind::Text(_) => "Text",
            ElementKind::Formula(_) => "Formula",
            ElementKind::Shape(_) => "Shape",
        }
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

impl Transformable for Shape {
    fn translate(&mut self, dx: f32, dy: f32) {
        self.bounds.translate(dx, dy);
    }

    fn scale(&mut self, factor: f32, origin: Point) {
        self.bounds.scale(factor, origin);
        self.stroke_width *= factor;
    }
}

impl Transformable for Element {
    fn translate(&mut self, dx: f32, dy: f32) {
        match &mut self.kind {
            ElementKind::Stroke(s) => s.translate(dx, dy),
            ElementKind::Text(t) => t.translate(dx, dy),
            ElementKind::Formula(fm) => fm.translate(dx, dy),
            ElementKind::Shape(sh) => sh.translate(dx, dy),
        }
    }

    fn scale(&mut self, factor: f32, origin: Point) {
        match &mut self.kind {
            ElementKind::Stroke(s) => s.scale(factor, origin),
            ElementKind::Text(t) => t.scale(factor, origin),
            ElementKind::Formula(fm) => fm.scale(factor, origin),
            ElementKind::Shape(sh) => sh.scale(factor, origin),
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
