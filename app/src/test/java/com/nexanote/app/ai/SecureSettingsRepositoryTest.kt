package com.nexanote.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato de [SecureSettingsRepository]: guardar, recuperar y borrar la
 * configuración de IA. Se ejercita sobre [FakeSharedPreferences]; en producción
 * el mismo código corre sobre `EncryptedSharedPreferences`.
 */
class SecureSettingsRepositoryTest {

    private val prefs = FakeSharedPreferences()
    private val repo = SharedPreferencesSettingsRepository(prefs)

    @Test
    fun defaultsWhenEmpty() {
        val loaded = repo.load()
        assertEquals(AiProviderId.OpenAi, loaded.provider)
        assertEquals("", loaded.apiKey)
        assertFalse(loaded.isConfigured)
        assertFalse(repo.hasCredentials())
    }

    @Test
    fun savesAndReloadsRoundTrip() {
        repo.save(AiSettings(AiProviderId.Anthropic, "  sk-abc123  ", "claude-3-5-sonnet-latest"))

        val loaded = repo.load()
        assertEquals(AiProviderId.Anthropic, loaded.provider)
        assertEquals("sk-abc123", loaded.apiKey) // recortada por AiSettings
        assertEquals("claude-3-5-sonnet-latest", loaded.model)
        assertTrue(loaded.isConfigured)
        assertTrue(repo.hasCredentials())
    }

    @Test
    fun clearRemovesEveryCredentialKey() {
        repo.save(AiSettings(AiProviderId.OpenAi, "sk-xyz", "gpt-4o"))
        repo.clear()

        assertFalse(prefs.contains("ai_api_key"))
        assertFalse(prefs.contains("ai_provider"))
        assertFalse(prefs.contains("ai_model"))
        assertFalse(repo.hasCredentials())
        assertEquals(AiSettings(), repo.load())
    }

    @Test
    fun blankKeyIsNotConsideredCredentials() {
        repo.save(AiSettings(AiProviderId.OpenAi, "   ", ""))
        assertFalse(repo.hasCredentials())
    }
}
