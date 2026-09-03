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
    fn rect_scales_about_origin() {
        let mut r = Rect::new(10.0, 10.0, 4.0, 6.0);
        r.scale(3.0, Point::ORIGIN);
        assert_eq!(r, Rect::new(30.0, 30.0, 12.0, 18.0));
    }
}
