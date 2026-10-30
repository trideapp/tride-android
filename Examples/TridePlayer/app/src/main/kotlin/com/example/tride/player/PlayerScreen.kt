/**
 * The player's one screen: the scene under everything, a top bar ("Open .trd…", bindings), the problem
 * line and the last events.
 *
 * Lifecycle: a scene is closed only after the view that drew it is released. Compose disposes a group's
 * effects and views in reverse order of declaration, so `DisposableEffect(scene)` stands BEFORE
 * `TrideSceneView`: the view goes first, then the effect closes the scene.
 *
 * The scene's names are listed once per scene (`remember(scene)`), not on every recomposition; the app
 * writes only names the scene declares; the events line keeps the last [EVENT_LOG] events; the scene's
 * diagnostics (`onDiagnostic`) show in the problem line.
 */
package com.example.tride.player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.tride.core.TrideValue
import studio.tride.kit.TrideKitScene
import studio.tride.kit.compose.TrideSceneView

/** How many of the scene's last events the player keeps. */
private const val EVENT_LOG = 50

@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    var scene by remember { mutableStateOf<TrideKitScene?>(null) }
    var title by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val events = remember { mutableStateListOf<String>() }
    var bindingsOpen by remember { mutableStateOf(false) }

    // One call into the runtime per scene, not one per recomposition.
    val bindings = remember(scene) { scene?.availableBindings().orEmpty() }
    val declared = remember(bindings) { bindings.map { it.name }.toSet() }

    /** Writes a binding the scene declares; a name it does not declare is skipped, not thrown. */
    fun set(name: String, value: TrideValue) {
        if (name !in declared) return
        try {
            scene?.setBinding(name, value)
        } catch (e: Exception) {
            problem = "$name: ${e.message}"
        }
    }
    fun fire(name: String) {
        if (name !in declared) return
        try { scene?.trigger(name) } catch (e: Exception) { problem = "$name: ${e.message}" }
    }
    fun load(bytes: ByteArray, name: String) {
        try {
            val loaded = TrideKitScene(bytes)
            loaded.onEvent {
                events.add(it.name)
                if (events.size > EVENT_LOG) events.removeRange(0, events.size - EVENT_LOG)
            }
            loaded.onDiagnostic { problem = "${it.kind.name.lowercase()}: ${it.message}" }
            scene?.pause()
            scene = loaded
            title = name
            problem = null
            events.clear()
        } catch (e: Exception) {
            problem = "Could not open: ${e.message}"
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) load(bytes, uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".trd") ?: "scene")
        } catch (e: Exception) {
            problem = "Could not read: ${e.message}"
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        scene?.let { s ->
            // Declared before the view, so it is disposed after it (see the header).
            DisposableEffect(s) { onDispose { s.pause(); s.close() } }
            TrideSceneView(
                scene = s,
                modifier = Modifier.fillMaxSize(),
                // 2 drawable pixels per dp: plenty for a phone and half the GPU of a 3× screen.
                pixelScale = 2f,
                onViewCreated = { s.play() },
            )
        } ?: Text(
            "Open a .trd exported from Tride Editor",
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.Center),
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text(title.ifEmpty { "Open .trd…" }) }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = { bindingsOpen = true }, enabled = scene != null) { Text("Bindings") }
            }
            Spacer(Modifier.weight(1f))
            problem?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
            if (events.isNotEmpty()) {
                Text(events.takeLast(3).joinToString(", "), color = Color.White.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }

    if (bindingsOpen && scene != null) {
        BindingsPanel(
            bindings = bindings,
            current = { name -> runCatching { scene?.getBinding(name) }.getOrNull() },
            write = { name, value ->
                if (value == null) fire(name) else set(name, value)
            },
            onDismiss = { bindingsOpen = false },
        )
    }
}
