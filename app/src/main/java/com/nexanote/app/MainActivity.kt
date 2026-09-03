package com.nexanote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexanote.core.NativeBridge
import com.nexanote.core.NativeCore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BridgeStatus(core = NativeBridge, loaded = NativeBridge.isLoaded)
                }
            }
        }
    }
}

@Composable
private fun BridgeStatus(core: NativeCore, loaded: Boolean) {
    val message = if (loaded) core.greeting("NexaNote") else "Núcleo nativo no disponible"
    val version = if (loaded) core.coreVersion() else "-"

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = message, style = MaterialTheme.typography.headlineSmall)
        Text(text = "core v$version", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Implementación ficticia de [NativeCore] para las previews de Compose. */
private object PreviewCore : NativeCore {
    override fun greeting(name: String) = "Hola, $name (preview)"
    override fun coreVersion() = "0.1.0"
    override fun documentCreate(title: String) = "{}"
    override fun documentAddPage(documentJson: String, pageSpecJson: String) = documentJson
    override fun documentRemovePage(documentJson: String, pageId: String) = documentJson
    override fun documentAddElement(documentJson: String, pageId: String, elementJson: String) =
        documentJson
    override fun documentRemoveElement(documentJson: String, pageId: String, elementId: String) =
        documentJson
    override fun documentTranslatePageElements(
        documentJson: String,
        pageId: String,
        dx: Float,
        dy: Float,
    ) = documentJson
    override fun documentSummary(documentJson: String) =
        "{\"page_count\":0,\"element_count\":0}"
}

@Preview(showBackground = true)
@Composable
private fun BridgeStatusPreview() {
    MaterialTheme {
        BridgeStatus(
            core = PreviewCore,
            loaded = true,
        )
    }
}
