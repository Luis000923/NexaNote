package com.nexanote.app.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entrada de una fórmula matemática: validación mínima y serialización al formato
 * `FormulaSpec` del núcleo Rust. Pura, sin Android.
 */
class FormulaInputTest {

    @Test
    fun blankExpressionIsNotCommittable() {
        assertFalse(FormulaInput.isCommittable(""))
        assertFalse(FormulaInput.isCommittable("   \n"))
        assertTrue(FormulaInput.isCommittable("a + b"))
    }

    @Test
    fun formulaJsonMatchesCoreSchema() {
        val json = FormulaInput.toFormulaJson("a + b", 10f, 20f)
        assertEquals(
            """{"expression":"a + b","position":{"x":10.0,"y":20.0}}""",
            json,
        )
    }

    @Test
    fun latexBackslashesAreEscaped() {
        val json = FormulaInput.toFormulaJson("\\frac{a}{b}", 0f, 0f)
        assertTrue(json.contains(""""expression":"\\frac{a}{b}""""))
    }
}
