package com.nexanote.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flujo del chat de IA a nivel de ViewModel: configuración, conversación con
 * memoria, publicación de comandos para el lienzo y persistencia por cuaderno.
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

    private class FakeChatStore(var history: ChatHistory = ChatHistory()) : ChatStore {
        var persisted = 0
        override fun load(): ChatHistory = history
        override fun persist(history: ChatHistory) {
            this.history = history
            persisted++
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

        vm.clearSettings()
        waitUntil { !repository.hasCredentials() }
        assertEquals(AiSettings(), vm.settings.value)
    }

    @Test
    fun sendProducesUserAndAssistantTurnsAndPersists() {
        val provider = FakeProvider(AiResult.Success("La energía y la masa son equivalentes."))
        val store = FakeChatStore()
        val vm = AiViewModel(repo(AiSettings(AiProviderId.OpenAi, "sk-1")), providerFactory = { provider })
        vm.openConversation(store)
        waitUntil { vm.settings.value.isConfigured }

        vm.send("Que es E = mc^2")

        waitUntil { vm.chat.value.messages.size == 2 }
        val turns = vm.chat.value.messages
        assertEquals(ChatRole.User, turns[0].role)
        assertEquals(ChatRole.Assistant, turns[1].role)
        assertTrue(turns[1].text.contains("equivalentes"))
        assertTrue("se debe persistir cada turno", store.persisted >= 2)
        // El system prompt de la conversación viaja en la petición.
        assertTrue(provider.lastRequest!!.systemPrompt.contains("NexaNote"))
        // Y el turno del usuario forma parte del contexto enviado.
        assertTrue(provider.lastRequest!!.messages.any { it.role == "user" && it.content.contains("mc^2") })
    }

    @Test
    fun successfulCommandResponseIsPublishedForTheCanvas() {
        val provider = FakeProvider(
            AiResult.Success("""Hecho. {"tool":"insert_formula","latex":"\\begin{pmatrix}1&0\\\\0&1\\end{pmatrix}"}"""),
        )
        val vm = AiViewModel(repo(AiSettings(AiProviderId.OpenAi, "sk-1")), providerFactory = { provider })
        vm.openConversation(ChatStore.NoOp)
        waitUntil { vm.settings.value.isConfigured }

        vm.send("Escribe la matriz identidad 2x2")

        waitUntil { vm.pendingCommands.value.isNotEmpty() }
        val command = vm.pendingCommands.value.single()
        assertTrue(command is AiCommand.InsertFormula)

        vm.consumeCommands()
        assertTrue(vm.pendingCommands.value.isEmpty())
    }

    @Test
    fun providerFailureBecomesAnAssistantBubble() {
        val vm = AiViewModel(
            repo(AiSettings(AiProviderId.OpenAi, "sk-1")),
            providerFactory = { FakeProvider(AiResult.Failure("HTTP 401")) },
        )
        vm.openConversation(ChatStore.NoOp)
        waitUntil { vm.settings.value.isConfigured }

        vm.send("hola")

        waitUntil { vm.chat.value.messages.size == 2 }
        val last = vm.chat.value.messages.last()
        assertEquals(ChatRole.Assistant, last.role)
        assertTrue(last.text.contains("401"))
        assertTrue(vm.pendingCommands.value.isEmpty())
    }

    @Test
    fun sendWithoutCredentialsStillLeavesAHint() {
        val vm = AiViewModel(repo(AiSettings()), providerFactory = { null })
        vm.openConversation(ChatStore.NoOp)
        waitUntil { true }

        vm.send("algo")

        waitUntil { vm.chat.value.messages.size == 2 }
        assertTrue(vm.chat.value.messages.last().text.contains("Ajustes de IA"))
    }

    @Test
    fun openConversationLoadsThePreviousHistory() {
        val store = FakeChatStore(
            ChatHistory(listOf(ChatMessage(ChatRole.User, "hola", 1), ChatMessage(ChatRole.Assistant, "buenas", 2))),
        )
        val vm = AiViewModel(repo(), providerFactory = { null })
        vm.openConversation(store)
        waitUntil { vm.chat.value.messages.size == 2 }

        vm.clearChat()
        assertTrue(vm.chat.value.isEmpty)
        waitUntil { store.history.isEmpty }
    }

    @Test
    fun blankMessageIsIgnored() {
        val vm = AiViewModel(repo(AiSettings(AiProviderId.OpenAi, "sk-1")), providerFactory = {
            throw AssertionError("no debería contactar al proveedor")
        })
        vm.openConversation(ChatStore.NoOp)
        vm.send("   ")
        assertFalse(vm.chat.value.messages.isNotEmpty())
    }
}
