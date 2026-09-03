package com.nexanote.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConversationTest {

    @Test
    fun systemPromptNamesEveryToolAndBansEmojis() {
        val prompt = AiConversation.system()
        assertTrue(prompt.contains("insert_text"))
        assertTrue(prompt.contains("insert_formula"))
        assertTrue(prompt.contains("insert_graph"))
        assertTrue(prompt.contains("pmatrix"))
        // Sin emojis: ningún punto de código fuera del plano multilingüe básico ni
        // símbolos de dingbats/emoticonos habituales.
        assertFalse(prompt.any { it.code in 0x1F000..0x1FAFF || it.code in 0x2600..0x27BF })
    }

    @Test
    fun toMessagesPreservesOrderAndRoles() {
        val history = ChatHistory(
            listOf(
                ChatMessage(ChatRole.User, "hola", 1L),
                ChatMessage(ChatRole.Assistant, "buenas", 2L),
                ChatMessage(ChatRole.User, "grafica x^2", 3L),
            ),
        )
        val messages = AiConversation.toMessages(history)
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.role })
        assertEquals(listOf("hola", "buenas", "grafica x^2"), messages.map { it.content })
    }
}
