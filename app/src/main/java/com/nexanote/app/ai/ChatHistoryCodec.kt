package com.nexanote.app.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialización del [ChatHistory] a/desde JSON. Defensiva: cualquier entrada
 * corrupta o inesperada devuelve un historial **vacío**, nunca lanza. Usa
 * `org.json` como el resto de la capa de almacenamiento (`NotebookLibrary`,
 * `SceneParser`), que ya se ejercita en tests de JVM.
 */
object ChatHistoryCodec {

    private const val KEY_MESSAGES = "messages"
    private const val KEY_ROLE = "role"
    private const val KEY_TEXT = "text"
    private const val KEY_TS = "ts"

    fun encode(history: ChatHistory): String {
        val array = JSONArray()
        for (m in history.messages) {
            array.put(
                JSONObject()
                    .put(KEY_ROLE, if (m.role == ChatRole.User) "user" else "assistant")
                    .put(KEY_TEXT, m.text)
                    .put(KEY_TS, m.timestampMs),
            )
        }
        return JSONObject().put(KEY_MESSAGES, array).toString()
    }

    fun decode(json: String?): ChatHistory {
        if (json.isNullOrBlank()) return ChatHistory()
        return runCatching {
            val array = JSONObject(json).optJSONArray(KEY_MESSAGES) ?: return ChatHistory()
            val messages = ArrayList<ChatMessage>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val text = obj.optString(KEY_TEXT)
                if (text.isBlank()) continue
                val role = if (obj.optString(KEY_ROLE) == "user") ChatRole.User else ChatRole.Assistant
                messages += ChatMessage(role, text, obj.optLong(KEY_TS, 0L))
            }
            ChatHistory(messages)
        }.getOrDefault(ChatHistory())
    }
}
