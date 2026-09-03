package com.nexanote.app.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Fábrica del [SecureSettingsRepository] de producción, respaldado por
 * `EncryptedSharedPreferences` de AndroidX Security: los valores se cifran con
 * una clave maestra guardada en el *Android Keystore* respaldado por hardware
 * cuando está disponible. La API key del usuario nunca toca el disco en claro.
 */
object SecureSettings {

    private const val FILE_NAME = "nexanote_ai_secure_prefs"

    fun create(context: Context): SecureSettingsRepository =
        SharedPreferencesSettingsRepository(encryptedPreferences(context))

    private fun encryptedPreferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
