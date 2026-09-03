package com.nexanote.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryCodecTest {

    @Test
    fun roundTripsMessages() {
        val history = ChatHistory(
            listOf(
                ChatMessage(ChatRole.User, "escribe \"hola\"\ncon salto", 10L),
                ChatMessage(ChatRole.Assistant, """{"tool":"insert_text","text":"hola"}""", 20L),
            ),
        )
        val decoded = ChatHistoryCodec.decode(ChatHistoryCodec.encode(history))
        assertEquals(history, decoded)
    }

    @Test
    fun corruptOrEmptyInputDecodesToEmptyHistory() {
        assertTrue(ChatHistoryCodec.decode(null).isEmpty)
        assertTrue(ChatHistoryCodec.decode("").isEmpty)
        assertTrue(ChatHistoryCodec.decode("{ not json").isEmpty)
        assertTrue(ChatHistoryCodec.decode("""{"messages":"nope"}""").isEmpty)
    }

    @Test
    fun blankMessagesAreDropped() {
        val json = """{"messages":[{"role":"user","text":"  ","ts":1},{"role":"assistant","text":"ok","ts":2}]}"""
        val decoded = ChatHistoryCodec.decode(json)
        assertEquals(1, decoded.messages.size)
        assertEquals("ok", decoded.messages.single().text)
    }
}
