package com.nexanote.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nexanote.app.canvas.ImageInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Imagen ya importada al almacén local del documento, lista para insertarse. */
data class ImportedImage(
    /** Ruta relativa al almacén de activos de la app (`images/…`). */
    val source: String,
    val naturalWidth: Int,
    val naturalHeight: Int,
)

/**
 * Importación y carga de imágenes con el almacén local de la app.
 *
 * Reglas (ver el prompt de la Fase 10):
 *  - Toda imagen elegida por el usuario se **copia** al almacenamiento privado
 *    (`filesDir/images/`) para garantizar portabilidad offline-first: el
 *    documento sólo referencia rutas relativas, nunca `content://` efímeros.
 *  - Nada de bitmaps gigantes: al importar y al pintar se aplica submuestreo
 *    (`inSampleSize`) para no cargar más píxeles de los necesarios, y siempre
 *    fuera del hilo principal ([Dispatchers.IO]).
 */
object ImageImporter {

    private const val DIR_NAME = "images"

    /** Lado máximo (px) al que se reduce un bitmap importado o cargado para pintar. */
    const val MAX_DIMENSION = 2048

    fun imagesDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /**
     * Copia el contenido de [uri] al almacén local (re-comprimido a PNG y
     * submuestreado si excede [MAX_DIMENSION]) y devuelve su descriptor, o `null`
     * si la imagen no se puede decodificar.
     */
    suspend fun importFromUri(context: Context, uri: Uri): ImportedImage? =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
                }
                val bitmap = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@runCatching null

                val dir = imagesDir(context).apply { mkdirs() }
                val file = File(dir, "${UUID.randomUUID()}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val descriptor = ImportedImage(
                    source = "$DIR_NAME/${file.name}",
                    naturalWidth = bitmap.width,
                    naturalHeight = bitmap.height,
                )
                bitmap.recycle()
                descriptor
            }.getOrNull()
        }

    /**
     * Decodifica (submuestreada) una imagen del almacén local para pintarla en el
     * lienzo. `null` si la ruta no es válida, el archivo no existe o falla la
     * decodificación.
     */
    suspend fun loadBitmap(
        context: Context,
        source: String,
        maxDimension: Int = MAX_DIMENSION,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (!ImageInput.isCommittable(source)) return@runCatching null
            val file = File(context.filesDir, source)
            if (!file.isFile) return@runCatching null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
        }.getOrNull()
    }

    /** Mayor potencia de 2 que mantiene ambos lados por debajo de [maxDimension]. */
    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxDimension || h / 2 >= maxDimension) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }
}
