package com.nexanote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexanote.app.ai.AiViewModel
import com.nexanote.app.ai.SecureSettings
import com.nexanote.app.canvas.SampleDocument

class MainActivity : ComponentActivity() {

    /** Explorador de cuadernos locales: la fuente de la pantalla de inicio. */
    private val library: NotebookLibrary by lazy { NotebookLibrary(applicationContext) }

    /**
     * ViewModel de edición. Se construye por cuaderno (ver [openNotebook]), para
     * inyectarle su [DocumentStore] y el lienzo con el que se creó; se conserva
     * aquí para poder forzar un guardado al pausar la Activity.
     */
    private var documentViewModel: DocumentViewModel? = null

    /** ViewModel de IA con almacenamiento cifrado (`EncryptedSharedPreferences`). */
    private val aiViewModel: AiViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                @Suppress("UNCHECKED_CAST")
                return AiViewModel(SecureSettings.create(applicationContext)) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Navegación mínima entre las dos pantallas de la app: el
                    // cuaderno abierto (o `null` = explorador). No hace falta una
                    // librería de navegación para dos destinos.
                    var open by remember { mutableStateOf<Notebook?>(null) }

                    val current = open
                    if (current == null) {
                        SideEffect { documentViewModel = null }
                        HomeScreen(library = library, onOpen = { open = it })
                    } else {
                        // Un ViewModel por cuaderno, con su propio almacén: cambiar
                        // de cuaderno crea uno nuevo y descarta el anterior.
                        val viewModel: DocumentViewModel = viewModel(
                            key = current.id,
                            factory = notebookFactory(current),
                        )
                        // El flush de `onPause` necesita el ViewModel vivo; se
                        // publica como efecto, nunca durante la composición.
                        SideEffect { documentViewModel = viewModel }
                        BackHandler { open = null }
                        DocumentScreen(
                            viewModel = viewModel,
                            aiViewModel = aiViewModel,
                            title = current.title,
                            onBack = {
                                viewModel.flush()
                                open = null
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Factoría del ViewModel de un cuaderno: persiste en su propio fichero y, la
     * primera vez que se abre, parte de un documento **vacío** con el lienzo
     * elegido al crearlo (A4 o infinito), no del documento de ejemplo.
     */
    private fun notebookFactory(notebook: Notebook) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return DocumentViewModel(
                store = library.storeFor(notebook.id),
                loadDocument = { core ->
                    SampleDocument.buildBlank(core, notebook.title, notebook.canvas.pageSpecJson)
                },
                newPageSpecJson = notebook.canvas.pageSpecJson,
            ) as T
        }
    }

    override fun onPause() {
        super.onPause()
        // Cualquier pérdida de foco (cambio de app, apagado de pantalla) fija el
        // documento activo en disco para recuperarlo al reiniciar.
        documentViewModel?.flush()
    }
}
