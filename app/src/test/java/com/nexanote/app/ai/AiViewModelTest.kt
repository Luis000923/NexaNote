package com.nexanote.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flujo de la integración de IA a nivel de ViewModel: carga de configuración,
 * guardado/borrado y asistencia (éxito, error del proveedor, sin credenciales).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeProvider(
        private val result: AiResult,
        override val id: AiProviderId = AiProviderId.OpenAi,
    ) : AiProvider {
        var lastRequest: AiRequest? = null
        override suspend fun complete(request: AiRequest): AiResult {
            lastRequest = request
            return result
        }
    }

    private fun repo(initial: AiSettings = AiSettings()) =
        SharedPreferencesSettingsRepository(FakeSharedPreferences()).apply { save(initial) }

    private fun waitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(10)
        }
        if (!cond()) throw AssertionError("condición no cumplida")
    }

    @Test
    fun loadsPersistedSettingsOnInit() {
        val vm = AiViewModel(repo(AiSettings(AiProviderId.Anthropic, "sk-1", "m")))
        waitUntil { vm.settings.value.provider == AiProviderId.Anthropic }
        assertTrue(vm.isConfigured)
    }

    @Test
    fun saveAndClearPersistThroughTheRepository() {
        val repository = SharedPreferencesSettingsRepository(FakeSharedPreferences())
        val vm = AiViewModel(repository)

        vm.saveSettings(AiProviderId.OpenAi, "  sk-abc  ", "gpt-4o")
        waitUntil { repository.hasCredentials() }
        assertEquals("sk-abc", repository.load().apiKey)
        assertEquals(AiProviderId.OpenAi, vm.settings.value.provider)

        vm.clearSettings()
        waitUntil { !repository.hasCredentials() }
        assertEquals(AiSettings(), vm.settings.value)
    }

    @Test
    fun explainReturnsAnswerFromProvider() {
        val provider = FakeProvider(AiResult.Success("Es la equivalencia masa-energía."))
        val vm = AiViewModel(repo(AiSettings(AiProviderId.OpenAi, "sk-1")), providerFactory = { provider })
        waitUntil { vm.settings.value.isConfigured }

        vm.explain("E = mc^2", AssistKind.Formula)

        waitUntil { vm.assist.value is AssistUiState.Answer }
        val answer = vm.assist.value as AssistUiState.Answer
        assertEquals(AssistKind.Formula, answer.kind)
        assertTrue(answer.text.contains("equivalencia"))
        // El prompt del sistema corresponde al tipo elegido.
        assertTrue(provider.lastRequest!!.systemPrompt.contains("matemáticas"))
        assertEquals("gpt-4o-mini", provider.lastRequest!!.model)
    }

    @Test
    fun explainSurfacesProviderFailure() {
        val vm = AiViewModel(
            repo(AiSettings(AiProviderId.OpenAi, "sk-1")),
            providerFactory = { FakeProvider(AiResult.Failure("HTTP 401")) },
        )
        waitUntil { vm.settings.value.isConfigured }

        vm.explain("hola", AssistKind.Text)

        waitUntil { vm.assist.value is AssistUiState.Error }
        assertEquals("HTTP 401", (vm.assist.value as AssistUiState.Error).message)
    }

    @Test
    fun explainWithoutCredentialsDoesNotCallProvider() {
        var built = false
        val vm = AiViewModel(repo(AiSettings()), providerFactory = { built = true; null })
        waitUntil { true }

        vm.explain("algo", AssistKind.Text)

        assertTrue(vm.assist.value is AssistUiState.Error)
        assertTrue(built) // se intentó construir, devolvió null por falta de clave
    }

    @Test
    fun explainRejectsEmptyContentBeforeAnyProvider() {
        val vm = AiViewModel(
            repo(AiSettings(AiProviderId.OpenAi, "sk-1")),
            providerFactory = { throw AssertionError("no debería construirse") },
        )
        vm.explain("   ", AssistKind.Text)
        assertTrue(vm.assist.value is AssistUiState.Error)
    }
}
