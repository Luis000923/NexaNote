package com.nexanote.app.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gestión de la entrada de un bloque de texto: validación del contenido y
 * serialización al formato `TextBox` del núcleo Rust. Pura, sin Android.
 */
class TextInputTest {

    @Test
    fun blankContentIsNotCommittable() {
        assertFalse(TextInput.isCommittable(""))
        assertFalse(TextInput.isCommittable("   \n\t"))
        assertTrue(TextInput.isCommittable("Hola"))
    }

    @Test
    fun textJsonMatchesCoreSchema() {
        val json = TextInput.toTextJson(
            content = "Hola",
            x = 10f,
            y = 20f,
            fontSize = 20f,
            color = StrokeColor(1, 2, 3, 255),
        )
        assertEquals(
            """{"content":"Hola","position":{"x":10.0,"y":20.0},""" +
                """"style":{"font_size":20.0,"bold":false,"italic":false,"underline":false,""" +
                """"color":{"r":1,"g":2,"b":3,"a":255}},"max_width":null}""",
            json,
        )
    }

    @Test
    fun textJsonEscapesSpecialCharacters() {
        val json = TextInput.toTextJson("a\"b\\c\nd", 0f, 0f)
        assertTrue(json.contains(""""content":"a\"b\\c\nd""""))
    }

    @Test
    fun fontSizeIsClampedToCoreRange() {
        assertTrue(TextInput.toTextJson("x", 0f, 0f, fontSize = 0.1f).contains(""""font_size":6.0"""))
        assertTrue(TextInput.toTextJson("x", 0f, 0f, fontSize = 9999f).contains(""""font_size":512.0"""))
    }
}
