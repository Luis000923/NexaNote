package com.nexanote.app.ai

/**
 * Persistencia del historial de chat de un cuaderno. Espejo de
 * `com.nexanote.app.DocumentStore`: la implementación de producción vive en
 * `NotebookLibrary` (fichero `<id>.chat` con escritura atómica, junto al
 * documento). [NoOp] cubre los tests y los contextos sin disco.
 */
interface ChatStore {
    /** Historial guardado, o uno vacío si no hay nada o está corrupto. */
    fun load(): ChatHistory

    /** Guarda [history] de forma duradera. No debe lanzar. */
    fun persist(history: ChatHistory)

    object NoOp : ChatStore {
        override fun load(): ChatHistory = ChatHistory()
        override fun persist(history: ChatHistory) = Unit
    }
}
