package com.nexanote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexanote.core.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Valida que la librería nativa (`libnexanote_core`) se carga y que el puente
 * JNI responde correctamente.
 */
@RunWith(AndroidJUnit4::class)
class NativeBridgeTest {

    @Test
    fun nativeLibraryLoads() {
        assertTrue("libnexanote_core no se cargó", NativeBridge.isLoaded)
    }

    @Test
    fun greetingCrossesTheBridge() {
        assertEquals("Hola, NexaNote 👋", NativeBridge.greeting("NexaNote"))
    }

    @Test
    fun coreReportsVersion() {
        assertEquals("0.1.0", NativeBridge.coreVersion())
    }
}
