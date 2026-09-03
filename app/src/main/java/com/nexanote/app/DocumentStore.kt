package com.nexanote.app

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persistencia local del documento activo (autosave / recuperación de sesión).
 *
 * Es deliberadamente mínima: sólo guardar y recuperar el JSON del documento. No
 * contiene lógica de dominio (esa vive en el núcleo Rust); es un mero almacén.
 * La capa de UI decide *cuándo* guardar -- por eventos clave (cada edición
 * confirmada) y con un pequeño rebote para coalescer ráfagas -- nunca en cada
 * frame de dibujo.
 */
interface DocumentStore {

    /** Escribe el JSON del documento activo, sobrescribiendo el anterior. */
    fun persist(documentJson: String)

    /** Devuelve el último JSON guardado, o `null` si no hay nada que recuperar. */
    fun restore(): String?

    /** Borra el estado guardado (p. ej. tras un guardado explícito futuro). */
    fun clear()

    /** Implementación nula: usada en tests y cuando no hay contexto Android. */
    object NoOp : DocumentStore {
        override fun persist(documentJson: String) {}
        override fun restore(): String? = null
        override fun clear() {}
    }
}

/**
 * [DocumentStore] respaldado por un fichero en el almacenamiento privado de la
 * app. La escritura es atómica (fichero temporal + `move` con `ATOMIC_MOVE`),
 * de modo que un cierre inesperado a mitad de guardado nunca deja el fichero
 * corrupto: o está el estado nuevo completo, o el anterior.
 */
class FileDocumentStore(context: Context) : DocumentStore {

    private val dir = File(context.filesDir, DIR_NAME)
    private val file = File(dir, FILE_NAME)
    private val tmp = File(dir, "$FILE_NAME.tmp")

    override fun persist(documentJson: String) {
        if (documentJson.isBlank()) return
        dir.mkdirs()
        tmp.writeText(documentJson)
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    override fun restore(): String? =
        file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    override fun clear() {
        file.delete()
        tmp.delete()
    }

    private companion object {
        const val DIR_NAME = "autosave"
        const val FILE_NAME = "active-document.json"
    }
}
