//! Motor matemático: **parser** de expresiones a un **AST** tipado
//! ([`FormulaNode`]) y **evaluador** del AST.
//!
//! El parser es de descenso recursivo con precedencia clásica
//! (`+ -` < `* /` < unario < `^`) y admite dos dialectos entremezclados:
//!
//!  - ASCII: `a/b`, `2*x^2`, `sqrt(x)`, `sin(x)`, `sum(i, 1, n, i^2)`.
//!  - LaTeX (subconjunto): `\frac{a}{b}`, `\sqrt{x}`, `\sqrt[3]{x}`,
//!    `\sum_{i=1}^{n} i^2`, `\cdot`, `\times`, `\pi`, `\left(`, `\right)`.
//!
//! Reglas de diseño del crate: sin `panic!`/`unwrap()`; todo fallo (léxico,
//! sintáctico o de dominio) se devuelve como [`MathError`]. La profundidad de
//! recursión y el número de iteraciones de una sumatoria están acotados para que
//! una entrada patológica no agote la pila ni bloquee el hilo.

use std::collections::HashMap;
use std::fmt;

use serde::{Deserialize, Serialize};

use super::element::{BinaryOp, FormulaNode, MathFunc};

/// Profundidad máxima de anidamiento de la expresión (paréntesis, funciones...).
const MAX_DEPTH: u32 = 128;

/// Número máximo de términos que se evaluarán en una sola sumatoria.
const MAX_SUM_ITERS: i64 = 100_000;

/// Error del motor matemático. Serializable para cruzar el FFI como dato si se
/// quisiera; la capa `api` lo traduce a [`DocumentError`](super::error::DocumentError).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "detail")]
pub enum MathError {
    /// La expresión está vacía o es sólo espacios.
    Empty,
    /// Fallo léxico o sintáctico, con una descripción legible.
    Syntax(String),
    /// Se superó [`MAX_DEPTH`] al parsear.
    TooDeep,
    /// Un símbolo sin valor asignado ni constante conocida.
    UnknownSymbol(String),
    /// División (o fracción) por cero.
    DivisionByZero,
    /// Argumento fuera del dominio de una operación (`sqrt(-1)`, `ln(0)`, ...).
    Domain(String),
    /// La sumatoria pedía más de [`MAX_SUM_ITERS`] términos, o sus límites no son
    /// enteros finitos.
    SumRange(String),
    /// El resultado no es finito (desbordamiento, `0/0`, ...).
    NotFinite,
}

impl fmt::Display for MathError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Empty => write!(f, "expresión vacía"),
            Self::Syntax(s) => write!(f, "sintaxis: {s}"),
            Self::TooDeep => write!(f, "expresión demasiado anidada"),
            Self::UnknownSymbol(s) => write!(f, "símbolo sin valor: {s}"),
            Self::DivisionByZero => write!(f, "división por cero"),
            Self::Domain(s) => write!(f, "fuera de dominio: {s}"),
            Self::SumRange(s) => write!(f, "límites de sumatoria inválidos: {s}"),
            Self::NotFinite => write!(f, "el resultado no es un número finito"),
        }
    }
}

impl std::error::Error for MathError {}

/// Alias corto para los resultados del módulo.
pub type MathResult<T> = Result<T, MathError>;

// ---------------------------------------------------------------------------
// Léxico
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
enum Tok {
    Num(f64),
    Ident(String),
    Plus,
    Minus,
    Star,
    Slash,
    Caret,
    LParen,
    RParen,
    LBrace,
    RBrace,
    LBrack,
    RBrack,
    Comma,
    Eq,
    Underscore,
    /// `\frac`
    Frac,
    /// `\sqrt`
    Sqrt,
    /// `\sum`
    Sum,
}

fn lex(input: &str) -> MathResult<Vec<Tok>> {
    let chars: Vec<char> = input.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        match c {
            c if c.is_whitespace() => i += 1,
            '+' => {
                out.push(Tok::Plus);
                i += 1;
            }
            '-' => {
                out.push(Tok::Minus);
                i += 1;
            }
            '*' => {
                out.push(Tok::Star);
                i += 1;
            }
            '/' => {
                out.push(Tok::Slash);
                i += 1;
            }
            '^' => {
                out.push(Tok::Caret);
                i += 1;
            }
            '(' => {
                out.push(Tok::LParen);
                i += 1;
            }
            ')' => {
                out.push(Tok::RParen);
                i += 1;
            }
            '{' => {
                out.push(Tok::LBrace);
                i += 1;
            }
            '}' => {
                out.push(Tok::RBrace);
                i += 1;
            }
            '[' => {
                out.push(Tok::LBrack);
                i += 1;
            }
            ']' => {
                out.push(Tok::RBrack);
                i += 1;
            }
            ',' => {
                out.push(Tok::Comma);
                i += 1;
            }
            '=' => {
                out.push(Tok::Eq);
                i += 1;
            }
            '_' => {
                out.push(Tok::Underscore);
                i += 1;
            }
            '\\' => {
                i += 1;
                let start = i;
                while i < chars.len() && chars[i].is_ascii_alphabetic() {
                    i += 1;
                }
                let name: String = chars[start..i].iter().collect();
                match name.as_str() {
                    "frac" => out.push(Tok::Frac),
                    "sqrt" => out.push(Tok::Sqrt),
                    "sum" => out.push(Tok::Sum),
                    "cdot" | "times" => out.push(Tok::Star),
                    "left" | "right" => { /* puramente decorativos: se ignoran */ }
                    "" => {
                        return Err(MathError::Syntax(
                            "`\\` sin nombre de comando".to_string(),
                        ))
                    }
                    other => out.push(Tok::Ident(other.to_string())),
                }
            }
            c if c.is_ascii_digit() || c == '.' => {
                let start = i;
                let mut seen_dot = false;
                while i < chars.len() {
                    let d = chars[i];
                    if d.is_ascii_digit() {
                        i += 1;
                    } else if d == '.' && !seen_dot {
                        seen_dot = true;
                        i += 1;
                    } else {
                        break;
                    }
                }
                let lexeme: String = chars[start..i].iter().collect();
                let value: f64 = lexeme
                    .parse()
                    .map_err(|_| MathError::Syntax(format!("número inválido `{lexeme}`")))?;
                out.push(Tok::Num(value));
            }
            c if c.is_alphabetic() => {
                let start = i;
                while i < chars.len() && (chars[i].is_alphanumeric() || chars[i] == '_') {
                    i += 1;
                }
                let name: String = chars[start..i].iter().collect();
                out.push(Tok::Ident(name));
            }
            other => {
                return Err(MathError::Syntax(format!("carácter inesperado `{other}`")))
            }
        }
    }
    Ok(out)
}

/// Nombre de función reconocido -> variante del AST.
fn func_from_name(name: &str) -> Option<MathFunc> {
    match name {
        "sin" => Some(MathFunc::Sin),
        "cos" => Some(MathFunc::Cos),
        "tan" => Some(MathFunc::Tan),
        "ln" => Some(MathFunc::Ln),
        "log" => Some(MathFunc::Log),
        "exp" => Some(MathFunc::Exp),
        "abs" => Some(MathFunc::Abs),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Sintaxis (descenso recursivo)
// ---------------------------------------------------------------------------

struct Parser {
    toks: Vec<Tok>,
    pos: usize,
    depth: u32,
}

impl Parser {
    fn new(toks: Vec<Tok>) -> Self {
        Parser {
            toks,
            pos: 0,
            depth: 0,
        }
    }

    fn peek(&self) -> Option<&Tok> {
        self.toks.get(self.pos)
    }

    fn next(&mut self) -> Option<Tok> {
        let t = self.toks.get(self.pos).cloned();
        if t.is_some() {
            self.pos += 1;
        }
        t
    }

    fn eat(&mut self, want: &Tok) -> MathResult<()> {
        match self.peek() {
            Some(t) if t == want => {
                self.pos += 1;
                Ok(())
            }
            Some(t) => Err(MathError::Syntax(format!("se esperaba {want:?}, hay {t:?}"))),
            None => Err(MathError::Syntax(format!("se esperaba {want:?}, fin de entrada"))),
        }
    }

    fn enter(&mut self) -> MathResult<()> {
        self.depth += 1;
        if self.depth > MAX_DEPTH {
            Err(MathError::TooDeep)
        } else {
            Ok(())
        }
    }

    fn leave(&mut self) {
        self.depth -= 1;
    }

    /// `expr := term (('+' | '-') term)*`
    fn expr(&mut self) -> MathResult<FormulaNode> {
        self.enter()?;
        let mut lhs = self.term()?;
        while let Some(op) = match self.peek() {
            Some(Tok::Plus) => Some(BinaryOp::Add),
            Some(Tok::Minus) => Some(BinaryOp::Sub),
            _ => None,
        } {
            self.pos += 1;
            let rhs = self.term()?;
            lhs = FormulaNode::Binary {
                op,
                lhs: Box::new(lhs),
                rhs: Box::new(rhs),
            };
        }
        self.leave();
        Ok(lhs)
    }

    /// `term := unary (('*' | '/' | <implícito>) unary)*`
    fn term(&mut self) -> MathResult<FormulaNode> {
        self.enter()?;
        let mut lhs = self.unary()?;
        loop {
            let op = match self.peek() {
                Some(Tok::Star) => {
                    self.pos += 1;
                    BinaryOp::Mul
                }
                Some(Tok::Slash) => {
                    self.pos += 1;
                    BinaryOp::Div
                }
                // Multiplicación implícita: `2x`, `2(x+1)`, `x y`, `2\sqrt{x}`.
                Some(t) if starts_atom(t) => BinaryOp::Mul,
                _ => break,
            };
            let rhs = self.unary()?;
            lhs = FormulaNode::Binary {
                op,
                lhs: Box::new(lhs),
                rhs: Box::new(rhs),
            };
        }
        self.leave();
        Ok(lhs)
    }

    /// `unary := ('+' | '-') unary | power`
    fn unary(&mut self) -> MathResult<FormulaNode> {
        self.enter()?;
        let node = match self.peek() {
            Some(Tok::Minus) => {
                self.pos += 1;
                FormulaNode::Neg(Box::new(self.unary()?))
            }
            Some(Tok::Plus) => {
                self.pos += 1;
                self.unary()?
            }
            _ => self.power()?,
        };
        self.leave();
        Ok(node)
    }

    /// `power := postfix ('^' unary)?` (asociativo por la derecha).
    fn power(&mut self) -> MathResult<FormulaNode> {
        self.enter()?;
        let base = self.postfix()?;
        let node = if matches!(self.peek(), Some(Tok::Caret)) {
            self.pos += 1;
            let exp = self.unary()?;
            FormulaNode::Binary {
                op: BinaryOp::Pow,
                lhs: Box::new(base),
                rhs: Box::new(exp),
            }
        } else {
            base
        };
        self.leave();
        Ok(node)
    }

    /// `postfix := atom` (reservado para sufijos como `!` en el futuro).
    fn postfix(&mut self) -> MathResult<FormulaNode> {
        self.atom()
    }

    fn atom(&mut self) -> MathResult<FormulaNode> {
        self.enter()?;
        let tok = self
            .next()
            .ok_or_else(|| MathError::Syntax("expresión incompleta".to_string()))?;
        let node = match tok {
            Tok::Num(n) => FormulaNode::Number(n),
            Tok::LParen => {
                let inner = self.expr()?;
                self.eat(&Tok::RParen)?;
                inner
            }
            Tok::LBrace => {
                let inner = self.expr()?;
                self.eat(&Tok::RBrace)?;
                inner
            }
            Tok::Frac => {
                let num = self.braced()?;
                let den = self.braced()?;
                FormulaNode::Fraction {
                    numerator: Box::new(num),
                    denominator: Box::new(den),
                }
            }
            Tok::Sqrt => {
                // `\sqrt[n]{x}`, `\sqrt{x}` o `sqrt(x)`.
                if matches!(self.peek(), Some(Tok::LBrack)) {
                    self.pos += 1;
                    let degree = self.expr()?;
                    self.eat(&Tok::RBrack)?;
                    let radicand = self.braced()?;
                    FormulaNode::Root {
                        degree: Box::new(degree),
                        radicand: Box::new(radicand),
                    }
                } else {
                    let radicand = self.group()?;
                    FormulaNode::Sqrt(Box::new(radicand))
                }
            }
            Tok::Sum => self.sum()?,
            Tok::Ident(name) => {
                if name == "sqrt" && matches!(self.peek(), Some(Tok::LParen | Tok::LBrace)) {
                    FormulaNode::Sqrt(Box::new(self.group()?))
                } else if let Some(func) = func_from_name(&name) {
                    if matches!(self.peek(), Some(Tok::LParen | Tok::LBrace)) {
                        FormulaNode::Call {
                            func,
                            arg: Box::new(self.group()?),
                        }
                    } else {
                        // `sin` sin paréntesis es un símbolo libre, no una llamada.
                        FormulaNode::Symbol(name)
                    }
                } else if name == "sum" {
                    self.sum()?
                } else {
                    FormulaNode::Symbol(name)
                }
            }
            other => {
                return Err(MathError::Syntax(format!("token inesperado {other:?}")))
            }
        };
        self.leave();
        Ok(node)
    }

    /// Lee `{ expr }`.
    fn braced(&mut self) -> MathResult<FormulaNode> {
        self.eat(&Tok::LBrace)?;
        let inner = self.expr()?;
        self.eat(&Tok::RBrace)?;
        Ok(inner)
    }

    /// Lee `( expr )` o `{ expr }` -- el argumento de una función.
    fn group(&mut self) -> MathResult<FormulaNode> {
        match self.peek() {
            Some(Tok::LParen) => {
                self.pos += 1;
                let inner = self.expr()?;
                self.eat(&Tok::RParen)?;
                Ok(inner)
            }
            Some(Tok::LBrace) => self.braced(),
            _ => Err(MathError::Syntax(
                "se esperaba un argumento entre paréntesis o llaves".to_string(),
            )),
        }
    }

    /// `\sum_{i = a}^{b} body` o `sum(i, a, b, body)`.
    fn sum(&mut self) -> MathResult<FormulaNode> {
        if matches!(self.peek(), Some(Tok::LParen)) {
            self.pos += 1;
            let var = self.ident()?;
            self.eat(&Tok::Comma)?;
            let from = self.expr()?;
            self.eat(&Tok::Comma)?;
            let to = self.expr()?;
            self.eat(&Tok::Comma)?;
            let body = self.expr()?;
            self.eat(&Tok::RParen)?;
            return Ok(FormulaNode::Sum {
                var,
                from: Box::new(from),
                to: Box::new(to),
                body: Box::new(body),
            });
        }
        self.eat(&Tok::Underscore)?;
        self.eat(&Tok::LBrace)?;
        let var = self.ident()?;
        self.eat(&Tok::Eq)?;
        let from = self.expr()?;
        self.eat(&Tok::RBrace)?;
        self.eat(&Tok::Caret)?;
        let to = if matches!(self.peek(), Some(Tok::LBrace)) {
            self.braced()?
        } else {
            self.power()?
        };
        let body = self.term()?;
        Ok(FormulaNode::Sum {
            var,
            from: Box::new(from),
            to: Box::new(to),
            body: Box::new(body),
        })
    }

    fn ident(&mut self) -> MathResult<String> {
        match self.next() {
            Some(Tok::Ident(s)) => Ok(s),
            other => Err(MathError::Syntax(format!(
                "se esperaba un identificador, hay {other:?}"
            ))),
        }
    }
}

/// ¿Este token puede iniciar un átomo? (para detectar multiplicación implícita).
fn starts_atom(t: &Tok) -> bool {
    matches!(
        t,
        Tok::Num(_)
            | Tok::Ident(_)
            | Tok::LParen
            | Tok::LBrace
            | Tok::Frac
            | Tok::Sqrt
            | Tok::Sum
    )
}

/// Parsea `expression` a un [`FormulaNode`]. Es la puerta pública del parser.
pub fn parse(expression: &str) -> MathResult<FormulaNode> {
    if expression.trim().is_empty() {
        return Err(MathError::Empty);
    }
    let toks = lex(expression)?;
    if toks.is_empty() {
        return Err(MathError::Empty);
    }
    let mut parser = Parser::new(toks);
    let node = parser.expr()?;
    if parser.pos != parser.toks.len() {
        return Err(MathError::Syntax(format!(
            "sobran tokens desde la posición {}",
            parser.pos
        )));
    }
    Ok(node)
}

// ---------------------------------------------------------------------------
// Evaluación
// ---------------------------------------------------------------------------

/// Valor de una constante matemática reconocida como símbolo.
fn constant(name: &str) -> Option<f64> {
    match name {
        "pi" | "Pi" | "PI" => Some(std::f64::consts::PI),
        "e" | "E" => Some(std::f64::consts::E),
        "tau" => Some(std::f64::consts::TAU),
        _ => None,
    }
}

/// Evalúa `node` numéricamente. `vars` aporta el valor de los símbolos libres;
/// las constantes conocidas (`pi`, `e`, `tau`) no necesitan entrada.
pub fn evaluate(node: &FormulaNode, vars: &HashMap<String, f64>) -> MathResult<f64> {
    let value = eval_inner(node, vars)?;
    if value.is_finite() {
        Ok(value)
    } else {
        Err(MathError::NotFinite)
    }
}

fn eval_inner(node: &FormulaNode, vars: &HashMap<String, f64>) -> MathResult<f64> {
    match node {
        FormulaNode::Number(n) => Ok(*n),
        FormulaNode::Symbol(s) => vars
            .get(s)
            .copied()
            .or_else(|| constant(s))
            .ok_or_else(|| MathError::UnknownSymbol(s.clone())),
        FormulaNode::Neg(inner) => Ok(-eval_inner(inner, vars)?),
        FormulaNode::Binary { op, lhs, rhs } => {
            let a = eval_inner(lhs, vars)?;
            let b = eval_inner(rhs, vars)?;
            match op {
                BinaryOp::Add => Ok(a + b),
                BinaryOp::Sub => Ok(a - b),
                BinaryOp::Mul => Ok(a * b),
                BinaryOp::Div => {
                    if b == 0.0 {
                        Err(MathError::DivisionByZero)
                    } else {
                        Ok(a / b)
                    }
                }
                BinaryOp::Pow => Ok(a.powf(b)),
            }
        }
        FormulaNode::Fraction {
            numerator,
            denominator,
        } => {
            let n = eval_inner(numerator, vars)?;
            let d = eval_inner(denominator, vars)?;
            if d == 0.0 {
                Err(MathError::DivisionByZero)
            } else {
                Ok(n / d)
            }
        }
        FormulaNode::Sqrt(inner) => {
            let v = eval_inner(inner, vars)?;
            if v < 0.0 {
                Err(MathError::Domain(format!("sqrt de {v}")))
            } else {
                Ok(v.sqrt())
            }
        }
        FormulaNode::Root { degree, radicand } => {
            let d = eval_inner(degree, vars)?;
            let r = eval_inner(radicand, vars)?;
            if d == 0.0 {
                return Err(MathError::Domain("raíz de índice 0".to_string()));
            }
            if r < 0.0 {
                return Err(MathError::Domain(format!("raíz par de {r}")));
            }
            Ok(r.powf(1.0 / d))
        }
        FormulaNode::Call { func, arg } => {
            let x = eval_inner(arg, vars)?;
            match func {
                MathFunc::Sin => Ok(x.sin()),
                MathFunc::Cos => Ok(x.cos()),
                MathFunc::Tan => Ok(x.tan()),
                MathFunc::Exp => Ok(x.exp()),
                MathFunc::Abs => Ok(x.abs()),
                MathFunc::Ln => {
                    if x <= 0.0 {
                        Err(MathError::Domain(format!("ln de {x}")))
                    } else {
                        Ok(x.ln())
                    }
                }
                MathFunc::Log => {
                    if x <= 0.0 {
                        Err(MathError::Domain(format!("log de {x}")))
                    } else {
                        Ok(x.log10())
                    }
                }
            }
        }
        FormulaNode::Sum {
            var,
            from,
            to,
            body,
        } => {
            let lo = eval_inner(from, vars)?;
            let hi = eval_inner(to, vars)?;
            if !lo.is_finite() || !hi.is_finite() || lo.fract() != 0.0 || hi.fract() != 0.0 {
                return Err(MathError::SumRange(format!("[{lo}, {hi}]")));
            }
            let lo = lo as i64;
            let hi = hi as i64;
            if hi < lo {
                return Ok(0.0);
            }
            if hi - lo + 1 > MAX_SUM_ITERS {
                return Err(MathError::SumRange(format!(
                    "{} términos (máx {MAX_SUM_ITERS})",
                    hi - lo + 1
                )));
            }
            let mut scope = vars.clone();
            let mut acc = 0.0;
            for k in lo..=hi {
                scope.insert(var.clone(), k as f64);
                acc += eval_inner(body, &scope)?;
            }
            Ok(acc)
        }
    }
}

/// Parsea y evalúa en un paso. Útil para diagnósticos y para la UI.
pub fn parse_and_evaluate(expression: &str, vars: &HashMap<String, f64>) -> MathResult<f64> {
    evaluate(&parse(expression)?, vars)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn eval(expr: &str) -> f64 {
        parse_and_evaluate(expr, &HashMap::new()).unwrap()
    }

    fn eval_with(expr: &str, pairs: &[(&str, f64)]) -> f64 {
        let vars: HashMap<String, f64> =
            pairs.iter().map(|(k, v)| (k.to_string(), *v)).collect();
        parse_and_evaluate(expr, &vars).unwrap()
    }

    fn approx(a: f64, b: f64) {
        assert!((a - b).abs() < 1e-9, "{a} != {b}");
    }

    #[test]
    fn basic_arithmetic_and_precedence() {
        approx(eval("1 + 2 * 3"), 7.0);
        approx(eval("(1 + 2) * 3"), 9.0);
        approx(eval("10 - 2 - 3"), 5.0);
        approx(eval("2 * 3 + 4 * 5"), 26.0);
        approx(eval("7 / 2"), 3.5);
    }

    #[test]
    fn unary_minus_binds_below_power() {
        approx(eval("-2^2"), -4.0);
        approx(eval("(-2)^2"), 4.0);
        approx(eval("-3 + 5"), 2.0);
        approx(eval("--3"), 3.0);
    }

    #[test]
    fn power_is_right_associative() {
        approx(eval("2^3^2"), 512.0);
        approx(eval("2^2^3"), 256.0);
    }

    #[test]
    fn implicit_multiplication() {
        approx(eval_with("2x", &[("x", 5.0)]), 10.0);
        approx(eval_with("2(x + 1)", &[("x", 3.0)]), 8.0);
        approx(eval_with("x y", &[("x", 3.0), ("y", 4.0)]), 12.0);
        approx(eval("2 3"), 6.0);
    }

    #[test]
    fn fractions_ascii_and_latex() {
        approx(eval("a/b".replace('a', "6").replace('b', "3").as_str()), 2.0);
        approx(eval(r"\frac{1}{2}"), 0.5);
        approx(eval(r"\frac{2 + 4}{3}"), 2.0);
        approx(eval_with(r"\frac{x^2}{2}", &[("x", 4.0)]), 8.0);
    }

    #[test]
    fn roots() {
        approx(eval("sqrt(9)"), 3.0);
        approx(eval(r"\sqrt{16}"), 4.0);
        approx(eval(r"\sqrt[3]{27}"), 3.0);
        approx(eval("2 sqrt(4)"), 4.0);
    }

    #[test]
    fn functions_and_constants() {
        approx(eval("sin(0)"), 0.0);
        approx(eval("cos(0)"), 1.0);
        approx(eval(r"\cos(\pi)"), -1.0);
        approx(eval("ln(e)"), 1.0);
        approx(eval("log(1000)"), 3.0);
        approx(eval("exp(0)"), 1.0);
        approx(eval("abs(-5)"), 5.0);
    }

    #[test]
    fn summations() {
        approx(eval(r"\sum_{i=1}^{5} i"), 15.0);
        approx(eval(r"\sum_{k=1}^{4} k^2"), 30.0);
        approx(eval("sum(i, 1, 3, 2*i)"), 12.0);
        approx(eval_with(r"\sum_{i=1}^{n} i", &[("n", 10.0)]), 55.0);
        // Rango vacío -> 0.
        approx(eval(r"\sum_{i=5}^{1} i"), 0.0);
    }

    #[test]
    fn latex_operators_and_decorations() {
        approx(eval(r"2 \cdot 3 \times 4"), 24.0);
        approx(eval(r"\left(1 + 2\right) \cdot 3"), 9.0);
    }

    #[test]
    fn ast_shape_is_structured_not_string() {
        let ast = parse("a + b * c").unwrap();
        match ast {
            FormulaNode::Binary {
                op: BinaryOp::Add,
                lhs,
                rhs,
            } => {
                assert_eq!(*lhs, FormulaNode::Symbol("a".to_string()));
                assert!(matches!(
                    *rhs,
                    FormulaNode::Binary {
                        op: BinaryOp::Mul,
                        ..
                    }
                ));
            }
            other => panic!("AST inesperado: {other:?}"),
        }
    }

    #[test]
    fn ast_roundtrips_through_json() {
        let ast = parse(r"\frac{-b + \sqrt{b^2 - 4 a c}}{2 a}").unwrap();
        let json = serde_json::to_string(&ast).unwrap();
        let back: FormulaNode = serde_json::from_str(&json).unwrap();
        assert_eq!(ast, back);
    }

    #[test]
    fn quadratic_formula_evaluates() {
        // Raíces de x^2 - 5x + 6 = 0  ->  x = 3 y x = 2.
        let vars: HashMap<String, f64> =
            [("a", 1.0), ("b", -5.0), ("c", 6.0)]
                .iter()
                .map(|(k, v)| (k.to_string(), *v))
                .collect();
        let plus = parse_and_evaluate(r"\frac{-b + \sqrt{b^2 - 4 a c}}{2 a}", &vars).unwrap();
        let minus = parse_and_evaluate(r"\frac{-b - \sqrt{b^2 - 4 a c}}{2 a}", &vars).unwrap();
        approx(plus, 3.0);
        approx(minus, 2.0);
    }

    #[test]
    fn syntax_errors_are_reported_not_panics() {
        assert_eq!(parse(""), Err(MathError::Empty));
        assert_eq!(parse("   "), Err(MathError::Empty));
        assert!(matches!(parse("1 +"), Err(MathError::Syntax(_))));
        assert!(matches!(parse("(1 + 2"), Err(MathError::Syntax(_))));
        assert!(matches!(parse("1 + 2)"), Err(MathError::Syntax(_))));
        assert!(matches!(parse("@"), Err(MathError::Syntax(_))));
        assert!(matches!(parse(r"\frac{1}"), Err(MathError::Syntax(_))));
        assert!(matches!(parse(r"\unknowncmd{1}"), Err(MathError::Syntax(_)) | Ok(_)));
    }

    #[test]
    fn evaluation_errors_are_reported() {
        assert_eq!(
            parse_and_evaluate("1/0", &HashMap::new()),
            Err(MathError::DivisionByZero)
        );
        assert_eq!(
            parse_and_evaluate(r"\frac{1}{0}", &HashMap::new()),
            Err(MathError::DivisionByZero)
        );
        assert!(matches!(
            parse_and_evaluate("sqrt(-1)", &HashMap::new()),
            Err(MathError::Domain(_))
        ));
        assert!(matches!(
            parse_and_evaluate("ln(0)", &HashMap::new()),
            Err(MathError::Domain(_))
        ));
        assert!(matches!(
            parse_and_evaluate("x + 1", &HashMap::new()),
            Err(MathError::UnknownSymbol(_))
        ));
    }

    #[test]
    fn recursion_depth_is_bounded() {
        let deep = "(".repeat(500);
        assert_eq!(parse(&deep), Err(MathError::TooDeep));
    }

    #[test]
    fn summation_iteration_count_is_bounded() {
        assert!(matches!(
            parse_and_evaluate("sum(i, 1, 1000000, i)", &HashMap::new()),
            Err(MathError::SumRange(_))
        ));
    }
}
