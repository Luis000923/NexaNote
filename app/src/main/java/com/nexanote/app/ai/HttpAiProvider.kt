package com.nexanote.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Base HTTP para proveedores tipo "chat": arma la conexión, envía el cuerpo JSON
 * en [Dispatchers.IO] y normaliza errores. Usa `HttpURLConnection` para no
 * arrastrar un cliente HTTP de terceros (regla de minimalismo de dependencias).
 *
 * Seguridad: la API key sólo se usa para construir cabeceras vía
 * [authHeaders]; **jamás** se registra en logs ni se incluye en los mensajes de
 * error que se propagan a la UI.
 */
abstract class HttpAiProvider(private val apiKey: String) : AiProvider {

    protected abstract val endpoint: String

    /** Cabeceras de autenticación/versión propias del proveedor. */
    protected abstract fun authHeaders(key: String): Map<String, String>

    /** Serializa el cuerpo de la petición. */
    protected abstract fun body(request: AiRequest): String

    /** Extrae el texto de la respuesta del proveedor (JSON ya parseado). */
    protected abstract fun extractText(json: JSONObject): String?

    final override suspend fun complete(request: AiRequest): AiResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext AiResult.Failure("Falta la API key. Configúrala en Ajustes de IA.")
        }
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                authHeaders(apiKey).forEach { (name, value) -> setRequestProperty(name, value) }
            }
            connection.outputStream.use { it.write(body(request).toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val payload = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (status !in 200..299) {
                return@withContext AiResult.Failure(describeHttpError(status, payload))
            }
            val text = runCatching { extractText(JSONObject(payload)) }.getOrNull()
            if (text.isNullOrBlank()) {
                AiResult.Failure("El proveedor respondió sin contenido utilizable.")
            } else {
                AiResult.Success(text.trim())
            }
        } catch (e: Exception) {
            AiResult.Failure("No se pudo contactar con el proveedor: ${e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun describeHttpError(status: Int, payload: String): String {
        val detail = runCatching {
            val obj = JSONObject(payload)
            obj.optJSONObject("error")?.optString("message").takeUnless { it.isNullOrBlank() }
                ?: obj.optString("message").takeUnless { it.isBlank() }
        }.getOrNull()
        val base = when (status) {
            401, 403 -> "Credenciales rechazadas por el proveedor (HTTP $status)."
            429 -> "Límite de uso alcanzado en el proveedor (HTTP 429)."
            in 500..599 -> "El proveedor tuvo un error interno (HTTP $status)."
            else -> "El proveedor devolvió HTTP $status."
        }
        return if (detail != null) "$base $detail" else base
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 45_000
    }
}

/** OpenAI (`/v1/chat/completions`). Autenticación `Bearer`. */
class OpenAiProvider(apiKey: String) : HttpAiProvider(apiKey) {
    override val id = AiProviderId.OpenAi
    override val endpoint = AiProviderId.OpenAi.endpoint
    override fun authHeaders(key: String) = mapOf("Authorization" to "Bearer $key")
    override fun body(request: AiRequest) = AiRequestBodies.openAi(request)
    override fun extractText(json: JSONObject): String? =
        json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
}

/** Anthropic (`/v1/messages`). Autenticación `x-api-key` + versión de API. */
class AnthropicProvider(apiKey: String) : HttpAiProvider(apiKey) {
    override val id = AiProviderId.Anthropic
    override val endpoint = AiProviderId.Anthropic.endpoint
    override fun authHeaders(key: String) = mapOf(
        "x-api-key" to key,
        "anthropic-version" to "2023-06-01",
    )
    override fun body(request: AiRequest) = AiRequestBodies.anthropic(request)
    override fun extractText(json: JSONObject): String? {
        val blocks = json.optJSONArray("content") ?: return null
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                val text = block.optString("text")
                if (text.isNotBlank()) return text
            }
        }
        return null
    }
}

/** Crea el proveedor concreto para unas credenciales, o `null` si faltan. */
object AiProviders {
    fun forSettings(settings: AiSettings): AiProvider? {
        if (!settings.isConfigured) return null
        return when (settings.provider) {
            AiProviderId.OpenAi -> OpenAiProvider(settings.apiKey)
            AiProviderId.Anthropic -> AnthropicProvider(settings.apiKey)
        }
    }
}
