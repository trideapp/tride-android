/**
 * TridePlayer for Android — the twin of TrideKit's iOS example: a Tride scene full screen ("Open .trd…", any
 * `.trd` on the device), the events the scene reports and a sheet with every binding and trigger it declares.
 *
 * TrideKit in three steps: `TrideKitScene(bytes)` loads the file, `TrideSceneView` draws it (a new view and
 * Filament engine per scene), `play()` starts the scene's clock. The app only writes bindings and fires
 * triggers; the scene decides what to show. Touches reach the scene's own interactions by themselves.
 */
package com.example.tride.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { PlayerScreen() } }
    }
}
