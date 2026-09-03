package com.nexanote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.nexanote.app.ai.AiViewModel
import com.nexanote.app.ai.SecureSettings

class MainActivity : ComponentActivity() {

    /**
     * ViewModel con autosave respaldado por fichero. Se construye aquí (y no con
     * `viewModel()` en el composable) para poder inyectarle el [DocumentStore] y
     * forzar un guardado al pausar la Activity.
     */
    private val viewModel: DocumentViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                @Suppress("UNCHECKED_CAST")
                return DocumentViewModel(store = FileDocumentStore(applicationContext)) as T
            }
        }
    }

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
                    DocumentScreen(viewModel, aiViewModel)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Cualquier pérdida de foco (cambio de app, apagado de pantalla) fija el
        // documento activo en disco para recuperarlo al reiniciar.
        viewModel.flush()
    }
}
