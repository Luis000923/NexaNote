package com.nexanote.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexanote.app.canvas.SampleDocument
import com.nexanote.app.canvas.ScenePage
import com.nexanote.core.NativeBridge
import com.nexanote.core.NativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado de la pantalla de visualización. */
sealed interface SceneUiState {
    data object Loading : SceneUiState
    data class Ready(val scene: ScenePage) : SceneUiState
    data class Error(val message: String) : SceneUiState
}

/**
 * ViewModel de la pantalla de documento. No contiene lógica de dominio: pide al
 * núcleo Rust (a través de [NativeCore]) que construya el documento de ejemplo y
 * devuelva su escena de render, y expone el resultado como estado de UI.
 *
 * El trabajo del puente se hace en [Dispatchers.Default], nunca en el hilo
 * principal.
 */
class DocumentViewModel(
    private val core: NativeCore = NativeBridge,
    private val bridgeAvailable: Boolean = NativeBridge.isLoaded,
) : ViewModel() {

    /** Constructor sin argumentos requerido por la factoría por defecto de `viewModel()`. */
    constructor() : this(NativeBridge, NativeBridge.isLoaded)

    private val _state = MutableStateFlow<SceneUiState>(SceneUiState.Loading)
    val state: StateFlow<SceneUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _state.value = SceneUiState.Loading
        viewModelScope.launch { _state.value = buildState() }
    }

    /** Construye el estado de forma síncrona-suspendida; reutilizable en tests. */
    suspend fun buildState(): SceneUiState = withContext(Dispatchers.Default) {
        if (!bridgeAvailable) {
            return@withContext SceneUiState.Error("El núcleo nativo no está disponible")
        }
        runCatching { SampleDocument.buildScene(core) }
            .fold(
                onSuccess = { SceneUiState.Ready(it) },
                onFailure = { SceneUiState.Error(it.message ?: it.javaClass.simpleName) },
            )
    }
}
