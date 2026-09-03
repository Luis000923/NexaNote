package com.nexanote.app.ai

import android.content.SharedPreferences

/**
 * Persistencia de la [AiSettings] del usuario. La implementación de producción
 * ([securePreferences]) respalda esto con `EncryptedSharedPreferences`, de modo
 * que la API key **nunca** queda en texto plano en disco.
 *
 * La interfaz se define sobre [SharedPreferences] (inyectable) para poder
 * probarla en la JVM con un doble en memoria: el contrato de lectura/escritura es
 * el mismo, sólo cambia si el almacén subyacente cifra o no.
 */
interface SecureSettingsRepository {
    fun load(): AiSettings
    fun save(settings: AiSettings)
    fun clear()
    fun hasCredentials(): Boolean
}

/**
 * Implementación sobre un [SharedPreferences] arbitrario. En producción se le pasa
 * el `EncryptedSharedPreferences`; en tests, un mapa en memoria.
 */
class SharedPreferencesSettingsRepository(
    private val prefs: SharedPreferences,
) : SecureSettingsRepository {

    override fun load(): AiSettings = AiSettings(
        provider = AiProviderId.fromKey(prefs.getString(KEY_PROVIDER, null)),
        apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
        model = prefs.getString(KEY_MODEL, null).orEmpty(),
    )

    override fun save(settings: AiSettings) {
        prefs.edit()
            .putString(KEY_PROVIDER, settings.provider.name)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .apply()
    }

    override fun clear() {
        prefs.edit()
            .remove(KEY_PROVIDER)
            .remove(KEY_API_KEY)
            .remove(KEY_MODEL)
            .apply()
    }

    override fun hasCredentials(): Boolean = !prefs.getString(KEY_API_KEY, null).isNullOrBlank()

    private companion object {
        const val KEY_PROVIDER = "ai_provider"
        const val KEY_API_KEY = "ai_api_key"
        const val KEY_MODEL = "ai_model"
    }
}
