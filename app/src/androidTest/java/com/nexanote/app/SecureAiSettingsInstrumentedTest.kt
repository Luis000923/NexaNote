package com.nexanote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexanote.app.ai.AiProviderId
import com.nexanote.app.ai.AiSettings
import com.nexanote.app.ai.SecureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Fase 12: la configuración de IA se persiste **cifrada**. Se comprueba el
 * round-trip real sobre `EncryptedSharedPreferences` y que la API key no queda en
 * claro en el fichero de preferencias.
 */
@RunWith(AndroidJUnit4::class)
class SecureAiSettingsInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetStore() {
        SecureSettings.create(context).clear()
    }

    @Test
    fun credentialsRoundTripThroughEncryptedStore() {
        val repo = SecureSettings.create(context)
        repo.save(AiSettings(AiProviderId.Anthropic, "sk-instrumented-key-01", "claude-3-5-sonnet-latest"))

        val reloaded = SecureSettings.create(context).load()
        assertEquals(AiProviderId.Anthropic, reloaded.provider)
        assertEquals("sk-instrumented-key-01", reloaded.apiKey)
        assertEquals("claude-3-5-sonnet-latest", reloaded.model)
        assertTrue(reloaded.isConfigured)
    }

    @Test
    fun apiKeyIsNotStoredInPlaintext() {
        SecureSettings.create(context).save(AiSettings(AiProviderId.OpenAi, "sk-plaintext-canary-42", ""))

        val prefsFile = File(context.filesDir.parentFile, "shared_prefs/nexanote_ai_secure_prefs.xml")
        assertTrue("debe existir el fichero de preferencias", prefsFile.exists())
        val raw = prefsFile.readText()
        assertFalse("la clave no puede aparecer en claro", raw.contains("sk-plaintext-canary-42"))
        assertFalse("ni la clave lógica en claro", raw.contains("ai_api_key"))
    }

    @Test
    fun clearRemovesCredentials() {
        val repo = SecureSettings.create(context)
        repo.save(AiSettings(AiProviderId.OpenAi, "sk-to-be-cleared", ""))
        repo.clear()

        assertFalse(SecureSettings.create(context).hasCredentials())
        assertEquals(AiSettings(), SecureSettings.create(context).load())
    }
}
