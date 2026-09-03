package com.nexanote.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTest {

    private fun msg(text: String, role: ChatRole = ChatRole.User) = ChatMessage(role, text, 0L)

    @Test
    fun appendedIsImmutableAndKeepsOrder() {
        val base = ChatHistory()
        val one = base.appended(msg("hola"))
        val two = one.appended(msg("buenas", ChatRole.Assistant))

        assertTrue(base.isEmpty)
        assertNotSame(one, two)
        assertEquals(listOf("hola", "buenas"), two.messages.map { it.text })
        assertEquals(ChatRole.Assistant, two.messages[1].role)
    }

    @Test
    fun historyIsCappedFromTheFront() {
        var history = ChatHistory()
        repeat(ChatHistory.MAX_MESSAGES + 15) { history = history.appended(msg("m$it")) }

        assertEquals(ChatHistory.MAX_MESSAGES, history.messages.size)
        // Los más antiguos se descartaron; el último es el más reciente.
        assertEquals("m${ChatHistory.MAX_MESSAGES + 14}", history.messages.last().text)
    }

    @Test
    fun oversizedTextIsTruncated() {
        val history = ChatHistory().appended(msg("x".repeat(ChatHistory.MAX_MESSAGE_LEN + 500)))
        assertEquals(ChatHistory.MAX_MESSAGE_LEN, history.messages.single().text.length)
    }
}
