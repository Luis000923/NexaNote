package com.nexanote.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCommandParserTest {

    @Test
    fun plainTextHasNoCommands() {
        val parsed = AiCommandParser.parse("La derivada de x^2 es 2x.")
        assertTrue(parsed.commands.isEmpty())
        assertEquals("La derivada de x^2 es 2x.", parsed.reply)
    }

    @Test
    fun singleInsertTextCommand() {
        val parsed = AiCommandParser.parse("""{"tool":"insert_text","text":"Ley de Ohm: V = I R"}""")
        val command = parsed.commands.single()
        assertTrue(command is AiCommand.InsertText)
        assertEquals("Ley de Ohm: V = I R", (command as AiCommand.InsertText).text)
    }

    @Test
    fun severalCommandsInOrder() {
        val text = """
            Añado ambas cosas.
            {"tool":"insert_text","text":"Teorema de Pitágoras"}
            {"tool":"insert_formula","latex":"a^2 + b^2 = c^2"}
        """.trimIndent()
        val parsed = AiCommandParser.parse(text)
        assertEquals(2, parsed.commands.size)
        assertTrue(parsed.commands[0] is AiCommand.InsertText)
        assertTrue(parsed.commands[1] is AiCommand.InsertFormula)
        assertEquals("Añado ambas cosas.", parsed.reply)
    }

    @Test
    fun matrixFormulaWithEscapedBackslashes() {
        val parsed = AiCommandParser.parse(
            """{"tool":"insert_formula","latex":"\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}"}""",
        )
        val command = parsed.commands.single() as AiCommand.InsertFormula
        assertEquals("""\begin{pmatrix}a&b\\c&d\end{pmatrix}""", command.latex)
    }

    @Test
    fun insertMatrixIsAnAliasOfInsertFormula() {
        val parsed = AiCommandParser.parse("""{"tool":"insert_matrix","latex":"\\begin{pmatrix}1&0\\\\0&1\\end{pmatrix}"}""")
        assertTrue(parsed.commands.single() is AiCommand.InsertFormula)
    }

    @Test
    fun graphUsesDefaultsWhenDomainIsMissingOrBackwards() {
        val a = AiCommandParser.parse("""{"tool":"insert_graph","expression":"sin(x)"}""")
            .commands.single() as AiCommand.InsertGraph
        assertEquals(-10.0, a.xMin, 0.0)
        assertEquals(10.0, a.xMax, 0.0)

        val b = AiCommandParser.parse("""{"tool":"insert_graph","expression":"x","x_min":5,"x_max":1}""")
            .commands.single() as AiCommand.InsertGraph
        assertTrue(b.xMin < b.xMax)
    }

    @Test
    fun unknownToolsAndHallucinationsAreIgnored() {
        val parsed = AiCommandParser.parse(
            """Mira: {"tool":"delete_everything"} y {"nota":"sin tool"} pero {"tool":"insert_text","text":"ok"}""",
        )
        assertEquals(1, parsed.commands.size)
        assertTrue(parsed.commands.single() is AiCommand.InsertText)
    }

    @Test
    fun brokenJsonNeverThrows() {
        val parsed = AiCommandParser.parse("""{"tool":"insert_text","text": "sin cerrar""")
        assertTrue(parsed.commands.isEmpty())
        assertFalse(parsed.reply.isEmpty())
    }

    @Test
    fun emptyPayloadFieldsAreRejected() {
        assertTrue(AiCommandParser.parse("""{"tool":"insert_text","text":"   "}""").commands.isEmpty())
        assertTrue(AiCommandParser.parse("""{"tool":"insert_graph"}""").commands.isEmpty())
    }

    @Test
    fun replyFallsBackToASummaryWhenOnlyCommands() {
        val parsed = AiCommandParser.parse("""{"tool":"insert_text","text":"a"}{"tool":"insert_text","text":"b"}""")
        assertEquals(2, parsed.commands.size)
        assertTrue(parsed.reply.contains("2"))
    }

    @Test
    fun nullInputIsSafe() {
        val parsed = AiCommandParser.parse(null)
        assertTrue(parsed.commands.isEmpty())
        assertEquals("", parsed.reply)
    }
}
