//! Tipos geométricos básicos y la operación de transformación afín soportada en
//! esta fase (traslación y escalado uniforme respecto a un origen).

use serde::{Deserialize, Serialize};

/// Punto en el espacio del documento (unidades lógicas, px @1x).
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Point {
    pub x: f32,
    pub y: f32,
}

impl Point {
    pub const ORIGIN: Point = Point { x: 0.0, y: 0.0 };

    pub fn new(x: f32, y: f32) -> Self {
        Point { x, y }
    }

    /// Escala el punto respecto a `origin` por un factor uniforme.
    pub fn scaled_about(self, factor: f32, origin: Point) -> Point {
        Point {
            x: origin.x + (self.x - origin.x) * factor,
            y: origin.y + (self.y - origin.y) * factor,
        }
    }
}

/// Rectángulo alineado a los ejes.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Rect {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

impl Rect {
    pub fn new(x: f32, y: f32, width: f32, height: f32) -> Self {
        Rect { x, y, width, height }
    }

    pub fn origin(self) -> Point {
        Point::new(self.x, self.y)
    }

    /// Borde derecho (`x + width`) del rectángulo ya normalizado.
    pub fn right(self) -> f32 {
        self.normalized().x + self.normalized().width
    }

    /// Borde inferior (`y + height`) del rectángulo ya normalizado.
    pub fn bottom(self) -> f32 {
        self.normalized().y + self.normalized().height
    }

    /// Equivalente con `width`/`height` no negativos: `Line`/`Arrow` guardan el
    /// vector con signo, y para las pruebas geométricas hace falta la caja.
    pub fn normalized(self) -> Rect {
        let (x, width) = if self.width < 0.0 {
            (self.x + self.width, -self.width)
        } else {
            (self.x, self.width)
        };
        let (y, height) = if self.height < 0.0 {
            (self.y + self.height, -self.height)
        } else {
            (self.y, self.height)
        };
        Rect { x, y, width, height }
    }

    /// Crece el rectángulo `margin` unidades en las cuatro direcciones.
    pub fn inflated(self, margin: f32) -> Rect {
        let r = self.normalized();
        Rect {
            x: r.x - margin,
            y: r.y - margin,
            width: r.width + margin * 2.0,
            height: r.height + margin * 2.0,
        }
    }

    /// Rectángulo mínimo que contiene a ambos (ambos normalizados).
    pub fn union(self, other: Rect) -> Rect {
        let a = self.normalized();
        let b = other.normalized();
        let x = a.x.min(b.x);
        let y = a.y.min(b.y);
        Rect {
            x,
            y,
            width: a.right().max(b.right()) - x,
            height: a.bottom().max(b.bottom()) - y,
        }
    }

    /// `true` si el punto cae dentro (bordes incluidos).
    pub fn contains_point(self, p: Point) -> bool {
        let r = self.normalized();
        p.x >= r.x && p.x <= r.right() && p.y >= r.y && p.y <= r.bottom()
    }

    /// `true` si `other` queda **completamente** dentro de `self`.
    pub fn contains_rect(self, other: Rect) -> bool {
        let a = self.normalized();
        let b = other.normalized();
        b.x >= a.x && b.y >= a.y && b.right() <= a.right() && b.bottom() <= a.bottom()
    }

    /// `true` si ambos rectángulos se solapan (contacto de bordes incluido).
    pub fn intersects(self, other: Rect) -> bool {
        let a = self.normalized();
        let b = other.normalized();
        a.x <= b.right() && b.x <= a.right() && a.y <= b.bottom() && b.y <= a.bottom()
    }
}

/// Color RGBA de 8 bits por canal.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Color {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}

impl Color {
    pub const BLACK: Color = Color { r: 0, g: 0, b: 0, a: 255 };

    pub fn rgb(r: u8, g: u8, b: u8) -> Self {
        Color { r, g, b, a: 255 }
    }
}

/// Elementos cuya geometría puede trasladarse y escalarse.
///
/// El escalado es uniforme y se hace respecto a un `origin` explícito, de modo
/// que las llamadas son deterministas y no dependen del estado del elemento.
pub trait Transformable {
    fn translate(&mut self, dx: f32, dy: f32);
    fn scale(&mut self, factor: f32, origin: Point);
}

impl Transformable for Point {
    fn translate(&mut self, dx: f32, dy: f32) {
        self.x += dx;
        self.y += dy;
    }

    fn scale(&mut self, factor: f32, origin: Point) {
        *self = self.scaled_about(factor, origin);
    }
}

impl Transformable for Rect {
    fn translate(&mut self, dx: f32, dy: f32) {
        self.x += dx;
        self.y += dy;
    }

    fn scale(&mut self, factor: f32, origin: Point) {
        let new_origin = self.origin().scaled_about(factor, origin);
        self.x = new_origin.x;
        self.y = new_origin.y;
        self.width *= factor;
        self.height *= factor;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn point_translate_and_scale() {
        let mut p = Point::new(10.0, 20.0);
        p.translate(5.0, -5.0);
        assert_eq!(p, Point::new(15.0, 15.0));
        p.scale(2.0, Point::ORIGIN);
        assert_eq!(p, Point::new(30.0, 30.0));
    }

    #[test]
    fn rect_normalizes_negative_extents() {
        let r = Rect::new(10.0, 10.0, -4.0, -6.0).normalized();
        assert_eq!(r, Rect::new(6.0, 4.0, 4.0, 6.0));
    }

    #[test]
    fn rect_containment_and_intersection() {
        let outer = Rect::new(0.0, 0.0, 100.0, 100.0);
        let inner = Rect::new(10.0, 10.0, 20.0, 20.0);
        let overlapping = Rect::new(90.0, 90.0, 40.0, 40.0);
        let away = Rect::new(200.0, 200.0, 10.0, 10.0);
        assert!(outer.contains_rect(inner));
        assert!(!outer.contains_rect(overlapping));
        assert!(outer.intersects(overlapping));
        assert!(!outer.intersects(away));
        assert!(outer.contains_point(Point::new(50.0, 50.0)));
        assert!(!outer.contains_point(Point::new(-1.0, 50.0)));
    }

    #[test]
    fn rect_union_covers_both() {
        let u = Rect::new(0.0, 0.0, 10.0, 10.0).union(Rect::new(20.0, 5.0, 10.0, 20.0));
        assert_eq!(u, Rect::new(0.0, 0.0, 30.0, 25.0));
    }

    #[test]
    fn rect_scales_about_origin() {
        let mut r = Rect::new(10.0, 10.0, 4.0, 6.0);
        r.scale(3.0, Point::ORIGIN);
        assert_eq!(r, Rect::new(30.0, 30.0, 12.0, 18.0));
    }
}
