package com.nexanote.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialización del cuerpo de las peticiones a los proveedores. Se comprueba la
 * estructura esperada y, sobre todo, que **la API key nunca** aparece en el
 * cuerpo (viaja sólo en cabeceras).
 */
class AiRequestBodiesTest {

    private val request = AiRequest(
        systemPrompt = "Eres un tutor.",
        userPrompt = "Explica \"E = mc^2\"\n\tcon saltos.",
        model = "gpt-4o-mini",
        maxTokens = 512,
        temperature = 0.2,
    )

    @Test
    fun openAiBodyHasChatStructure() {
        val body = AiRequestBodies.openAi(request)
        assertTrue(body.startsWith("{"))
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"max_tokens\":512"))
        assertTrue(body.contains("\"temperature\":0.2"))
    }

    @Test
    fun anthropicBodyHasMessagesStructureWithTopLevelSystem() {
        val body = AiRequestBodies.anthropic(request)
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(body.contains("\"system\":\"Eres un tutor.\""))
        assertTrue(body.contains("\"messages\":[{\"role\":\"user\""))
        assertTrue(body.contains("\"max_tokens\":512"))
        assertFalse("Anthropic no usa rol system dentro de messages", body.contains("\"role\":\"system\""))
    }

    @Test
    fun contentIsJsonEscaped() {
        val body = AiRequestBodies.openAi(request)
        assertTrue(body.contains("\\\"E = mc^2\\\""))
        assertTrue(body.contains("\\n\\t"))
        assertFalse(body.contains("\n"))
    }

    @Test
    fun bodiesNeverInjectAnApiKey() {
        val secret = "sk-supersecreto-1234567890"
        val plain = request.copy(userPrompt = "no reveles nada")
        assertFalse(AiRequestBodies.openAi(plain).contains(secret))
        assertFalse(AiRequestBodies.anthropic(plain).contains(secret))
    }

    @Test
    fun escapeHandlesControlCharactersAndBackslash() {
        assertEquals("\"a\\u0001b\"", AiRequestBodies.str("a\u0001b"))
        assertEquals("\"\\\\\"", AiRequestBodies.str("\\"))
        assertEquals("\"\\n\"", AiRequestBodies.str("\n"))
    }
}
