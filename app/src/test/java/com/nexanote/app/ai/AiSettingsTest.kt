package com.nexanote.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSettingsTest {

    @Test
    fun effectiveModelFallsBackToProviderDefault() {
        assertEquals("gpt-4o-mini", AiSettings(AiProviderId.OpenAi, "k", "").effectiveModel)
        assertEquals("claude-3-5-haiku-latest", AiSettings(AiProviderId.Anthropic, "k", "  ").effectiveModel)
        assertEquals("my-model", AiSettings(AiProviderId.OpenAi, "k", "my-model").effectiveModel)
    }

    @Test
    fun isConfiguredNeedsANonBlankKey() {
        assertFalse(AiSettings(AiProviderId.OpenAi, "").isConfigured)
        assertFalse(AiSettings(AiProviderId.OpenAi, "   ").isConfigured)
        assertTrue(AiSettings(AiProviderId.OpenAi, "sk-1").isConfigured)
    }

    @Test
    fun toStringNeverLeaksTheKey() {
        val text = AiSettings(AiProviderId.OpenAi, "sk-supersecret", "gpt-4o").toString()
        assertFalse(text.contains("supersecret"))
        assertTrue(text.contains("oculta"))
    }

    @Test
    fun fromKeyIsTolerantAndDefaultsToOpenAi() {
        assertEquals(AiProviderId.Anthropic, AiProviderId.fromKey("anthropic"))
        assertEquals(AiProviderId.Anthropic, AiProviderId.fromKey("Anthropic"))
        assertEquals(AiProviderId.OpenAi, AiProviderId.fromKey(null))
        assertEquals(AiProviderId.OpenAi, AiProviderId.fromKey("desconocido"))
    }

    @Test
    fun providerFactoryHonoursConfiguration() {
        assertTrue(AiProviders.forSettings(AiSettings(AiProviderId.OpenAi, "sk-1")) is OpenAiProvider)
        assertTrue(AiProviders.forSettings(AiSettings(AiProviderId.Anthropic, "sk-1")) is AnthropicProvider)
        assertEquals(null, AiProviders.forSettings(AiSettings(AiProviderId.OpenAi, "")))
    }
}
