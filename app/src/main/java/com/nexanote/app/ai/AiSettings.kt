package com.nexanote.app.ai

/**
 * Proveedores de IA soportados. Cada uno declara su nombre visible, su modelo por
 * defecto y el *endpoint* de completado de chat. La clave la aporta el usuario y
 * **nunca** vive aquí.
 */
enum class AiProviderId(
    val displayName: String,
    val defaultModel: String,
    val endpoint: String,
) {
    OpenAi(
        displayName = "OpenAI",
        defaultModel = "gpt-4o-mini",
        endpoint = "https://api.openai.com/v1/chat/completions",
    ),
    Anthropic(
        displayName = "Anthropic",
        defaultModel = "claude-3-5-haiku-latest",
        endpoint = "https://api.anthropic.com/v1/messages",
    ),
    ;

    companion object {
        /** Búsqueda tolerante por nombre almacenado; `OpenAi` si no coincide. */
        fun fromKey(key: String?): AiProviderId =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: OpenAi
    }
}

/**
 * Configuración de IA del usuario. Es un objeto de datos plano: la persistencia
 * cifrada la hace [SecureSettingsRepository].
 *
 * @param apiKey clave del usuario. Se trata como secreto: no se registra en logs
 *   ni se incluye en `toString`.
 * @param model modelo a usar; vacío = el modelo por defecto del proveedor.
 */
class AiSettings(
    val provider: AiProviderId = AiProviderId.OpenAi,
    apiKey: String = "",
    model: String = "",
) {
    val apiKey: String = apiKey.trim()
    val model: String = model.trim()

    /** Modelo efectivo: el elegido por el usuario o el del proveedor. */
    val effectiveModel: String
        get() = model.ifBlank { provider.defaultModel }

    /** ¿Hay credenciales suficientes para hablar con el proveedor? */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    fun copy(
        provider: AiProviderId = this.provider,
        apiKey: String = this.apiKey,
        model: String = this.model,
    ): AiSettings = AiSettings(provider, apiKey, model)

    /** Sin la clave: seguro para logs y mensajes de diagnóstico. */
    override fun toString(): String =
        "AiSettings(provider=$provider, model='$effectiveModel', apiKey=${if (apiKey.isBlank()) "<vacía>" else "<oculta>"})"

    override fun equals(other: Any?): Boolean =
        other is AiSettings && other.provider == provider && other.apiKey == apiKey && other.model == model

    override fun hashCode(): Int = (provider.hashCode() * 31 + apiKey.hashCode()) * 31 + model.hashCode()
}
