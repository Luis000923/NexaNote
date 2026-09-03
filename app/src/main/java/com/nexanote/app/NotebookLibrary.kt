package com.nexanote.app

import android.content.Context
import com.nexanote.app.ai.ChatHistory
import com.nexanote.app.ai.ChatHistoryCodec
import com.nexanote.app.ai.ChatStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * Tipo de lienzo de un cuaderno, elegido al crearlo.
 *
 * Se traduce a la especificación de página que entiende el núcleo Rust; Kotlin no
 * inventa medidas: `A4` y `Infinite` son variantes de `PageSize` del núcleo.
 */
enum class CanvasKind {
    /** Páginas A4 delimitadas, con cuadrícula: el cuaderno clásico. */
    A4,

    /** Lienzo sin límites que crece con el contenido. */
    Infinite;

    /** `page_spec` para `documentAddPage`. */
    val pageSpecJson: String
        get() = when (this) {
            A4 -> """{"size":{"format":"A4"},"template":{"kind":"Grid","spacing":26.0}}"""
            Infinite -> """{"size":{"format":"Infinite"},"template":{"kind":"Dotted","spacing":32.0}}"""
        }

    /** Nombre legible para la interfaz. */
    val label: String
        get() = when (this) {
            A4 -> "Páginas A4"
            Infinite -> "Lienzo infinito"
        }

    companion object {
        /** Variante con ese nombre, o [A4] si el valor guardado no se reconoce. */
        fun fromName(name: String?): CanvasKind =
            entries.firstOrNull { it.name == name } ?: A4
    }
}

/** Un cuaderno del explorador: su identidad en disco y lo que la lista necesita mostrar. */
data class Notebook(
    /** Nombre de fichero sin extensión; identifica el cuaderno en [NotebookLibrary]. */
    val id: String,
    val title: String,
    val canvas: CanvasKind,
    /** Epoch ms de la última escritura del documento; `0` si aún no se ha guardado. */
    val modifiedMs: Long,
)

/**
 * Explorador de cuadernos locales: la capa de archivos sobre la que se apoya la
 * pantalla de inicio.
 *
 * Cada cuaderno son dos ficheros en `filesDir/notebooks/`:
 *  - `<id>.json`: el documento serializado por el núcleo (lo escribe el autosave);
 *  - `<id>.meta`: título y tipo de lienzo, un objeto diminuto que permite listar
 *    la biblioteca **sin parsear ningún documento completo**.
 *
 * No contiene lógica de dominio: no interpreta el documento, sólo lo guarda,
 * lo recupera y lo borra.
 */
class NotebookLibrary(context: Context) {

    private val dir = File(context.filesDir, DIR_NAME)

    /** Cuadernos existentes, del editado más recientemente al más antiguo. */
    fun list(): List<Notebook> {
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(META_EXT) }
            ?: return emptyList()
        return files
            .mapNotNull { meta -> readMeta(meta.nameWithoutExtension) }
            .sortedByDescending { it.modifiedMs }
    }

    /**
     * Crea un cuaderno vacío con el título y el lienzo indicados. El documento en
     * sí lo construye el núcleo la primera vez que se abre; aquí sólo se reserva
     * su identidad.
     */
    fun create(title: String, canvas: CanvasKind): Notebook {
        dir.mkdirs()
        val id = "nb-${System.currentTimeMillis()}-${(0..0xFFFF).random().toString(16)}"
        val notebook = Notebook(id, sanitizeTitle(title), canvas, System.currentTimeMillis())
        writeMeta(notebook)
        return notebook
    }

    /** Borra el cuaderno, su documento y su conversación de IA. Es irreversible. */
    fun delete(id: String) {
        documentFile(id).delete()
        metaFile(id).delete()
        tempFile(id).delete()
        chatFile(id).delete()
        chatTempFile(id).delete()
    }

    /**
     * Convierte el autosave de una versión anterior (un único documento activo)
     * en un cuaderno de la biblioteca, y lo retira de su ubicación antigua para no
     * volver a importarlo. No hace nada si no hay nada que migrar.
     *
     * Toca disco: llamar fuera del hilo principal.
     */
    fun importLegacyAutosave(legacy: DocumentStore): Notebook? {
        val documentJson = runCatching { legacy.restore() }.getOrNull() ?: return null
        val notebook = create(LEGACY_TITLE, CanvasKind.A4)
        storeFor(notebook.id).persist(documentJson)
        legacy.clear()
        return notebook
    }

    /** [DocumentStore] que persiste el documento de este cuaderno (autosave). */
    fun storeFor(id: String): DocumentStore = NotebookDocumentStore(id)

    /** [ChatStore] que persiste la conversación de IA de este cuaderno (Fase 16). */
    fun chatStoreFor(id: String): ChatStore = NotebookChatStore(id)

    private fun documentFile(id: String) = File(dir, "${safeId(id)}$DOC_EXT")
    private fun metaFile(id: String) = File(dir, "${safeId(id)}$META_EXT")
    private fun tempFile(id: String) = File(dir, "${safeId(id)}$DOC_EXT.tmp")
    private fun chatFile(id: String) = File(dir, "${safeId(id)}$CHAT_EXT")
    private fun chatTempFile(id: String) = File(dir, "${safeId(id)}$CHAT_EXT.tmp")

    private fun writeMeta(notebook: Notebook) {
        dir.mkdirs()
        val json = JSONObject()
            .put(KEY_TITLE, notebook.title)
            .put(KEY_CANVAS, notebook.canvas.name)
            .toString()
        metaFile(notebook.id).writeText(json)
    }

    private fun readMeta(id: String): Notebook? {
        val meta = metaFile(id).takeIf { it.isFile } ?: return null
        val json = runCatching { JSONObject(meta.readText()) }.getOrNull() ?: return null
        val document = documentFile(id)
        return Notebook(
            id = id,
            title = json.optString(KEY_TITLE).ifBlank { UNTITLED },
            canvas = CanvasKind.fromName(json.optString(KEY_CANVAS)),
            // La marca de tiempo la da el sistema de ficheros: siempre refleja el
            // último autosave sin tener que abrir el documento.
            modifiedMs = if (document.isFile) document.lastModified() else meta.lastModified(),
        )
    }

    /**
     * Escritura atómica del documento de un cuaderno: o queda el estado nuevo
     * completo, o el anterior. Nunca un fichero a medias.
     */
    private inner class NotebookDocumentStore(private val id: String) : DocumentStore {

        override fun persist(documentJson: String) {
            if (documentJson.isBlank()) return
            dir.mkdirs()
            val tmp = tempFile(id)
            tmp.writeText(documentJson)
            Files.move(
                tmp.toPath(),
                documentFile(id).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }

        override fun restore(): String? =
            documentFile(id).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

        override fun clear() {
            documentFile(id).delete()
            tempFile(id).delete()
        }
    }

    /**
     * Historial de chat de un cuaderno en `<id>.chat`, con la misma escritura
     * atómica que el documento. Un fichero corrupto o ausente devuelve una
     * conversación vacía; nunca lanza.
     */
    private inner class NotebookChatStore(private val id: String) : ChatStore {

        override fun load(): ChatHistory {
            val text = chatFile(id).takeIf { it.isFile }?.runCatching { readText() }?.getOrNull()
            return ChatHistoryCodec.decode(text)
        }

        override fun persist(history: ChatHistory) {
            runCatching {
                dir.mkdirs()
                val tmp = chatTempFile(id)
                tmp.writeText(ChatHistoryCodec.encode(history))
                Files.move(
                    tmp.toPath(),
                    chatFile(id).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }
        }
    }

    companion object {
        const val UNTITLED = "Cuaderno sin título"

        /** Título del cuaderno creado al migrar el autosave de una versión previa. */
        const val LEGACY_TITLE = "Cuaderno recuperado"

        /** Longitud máxima de un título; evita entradas ingobernables en la lista. */
        const val MAX_TITLE_LEN = 80

        private const val DIR_NAME = "notebooks"
        private const val DOC_EXT = ".json"
        private const val META_EXT = ".meta"
        private const val CHAT_EXT = ".chat"
        private const val KEY_TITLE = "title"
        private const val KEY_CANVAS = "canvas"

        /** Recorta el título y cae a [UNTITLED] si queda vacío. */
        fun sanitizeTitle(raw: String): String =
            raw.trim().take(MAX_TITLE_LEN).ifBlank { UNTITLED }

        /**
         * Sólo caracteres seguros pueden formar un nombre de fichero: impide que
         * un id manipulado se salga del directorio de la biblioteca.
         */
        fun safeId(id: String): String =
            id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
    }
}
