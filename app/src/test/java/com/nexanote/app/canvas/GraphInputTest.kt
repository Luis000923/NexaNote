package com.nexanote.app.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entrada de una gráfica de función: validación de la función y del dominio, y
 * serialización al formato `GraphSpec` del núcleo Rust. Pura, sin Android.
 */
class GraphInputTest {

    @Test
    fun blankExpressionIsInvalid() {
        val v = GraphInput.validate("", "-1", "1")
        assertTrue(v is GraphInput.Validation.Invalid)
    }

    @Test
    fun nonNumericDomainIsInvalid() {
        assertTrue(GraphInput.validate("x", "abc", "1") is GraphInput.Validation.Invalid)
        assertTrue(GraphInput.validate("x", "-1", "") is GraphInput.Validation.Invalid)
    }

    @Test
    fun invertedOrEmptyDomainIsInvalid() {
        assertTrue(GraphInput.validate("x", "5", "5") is GraphInput.Validation.Invalid)
        assertTrue(GraphInput.validate("x", "3", "-3") is GraphInput.Validation.Invalid)
    }

    @Test
    fun wellFormedInputIsValidAndTrimmed() {
        val v = GraphInput.validate("  sin(x)  ", " -6.28 ", "6.28")
        assertTrue(v is GraphInput.Validation.Valid)
        v as GraphInput.Validation.Valid
        assertEquals("sin(x)", v.expression)
        assertEquals(-6.28, v.xMin, 1e-9)
        assertEquals(6.28, v.xMax, 1e-9)
    }

    @Test
    fun graphJsonMatchesCoreSchema() {
        val json = GraphInput.toGraphJson("x^2", -2.0, 2.0, 10f, 20f, 300f, 200f, "x")
        assertEquals(
            """{"expression":"x^2","position":{"x":10.0,"y":20.0},"width":300.0,"height":200.0,"x_min":-2.0,"x_max":2.0,"var":"x"}""",
            json,
        )
    }

    @Test
    fun latexBackslashesAreEscapedInJson() {
        val json = GraphInput.toGraphJson("\\sin(x)", -1.0, 1.0, 0f, 0f)
        assertTrue(json.contains(""""expression":"\\sin(x)""""))
    }
}
