/**
 * `TrideSceneView` — TrideKit's Compose surface: one composable over `AndroidView` hosting a
 * `FilamentSurfaceView`; the scene's touches, tick and frames stay in the view. Nothing else lives here —
 * a host draws its UI around it.
 *
 * Public API:
 *   - `TrideSceneView(scene, modifier, backend, renderPolicy, pixelScale, preferredFps, renderOverrides,
 *     onViewCreated, onLoadError)` — `pixelScale` = drawable px per dp (`null` = the screen's), see
 *     `FilamentSurfaceView.pixelScale`; `preferredFps` = frames drawn a second, the scene's logic woken no more
 *     often (`null` = every vsync), see `FilamentSurfaceView.preferredFps`; `renderOverrides` = the host's
 *     render knobs over the scene's render settings (`null` = none), see `FilamentSurfaceView.renderOverrides`.
 *     Both change on the same view and scene. `onLoadError` = the view could not show the scene (closed, or one the
 *     runtime hands no description of): the exception `FilamentSurfaceView.scene` threw, on the main thread after
 *     the view is made — the composable never throws it, as iOS's `TrideSceneView` hands it to `onError`.
 *
 * Non-obvious constraints:
 *   - The view is keyed by the scene: a new `TrideKitScene` recreates the view and its Filament engine (one
 *     engine per scene, as `FilamentSurfaceView.scene` does on one view), the old one released with it.
 *   - The composable never closes the scene — the owner does (`TrideKitScene.close()`).
 *   - A scene the view refused is not shown (the view draws nothing); without `onLoadError` it is a log line.
 */
package studio.tride.kit.compose

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import studio.tride.core.TrideRenderOverrides
import studio.tride.kit.FilamentSurfaceView
import studio.tride.kit.TrideKitScene

private const val TAG = "tride.compose"

/** A scene in Compose; the view is released with the composable, the scene stays the owner's to close. */
@Composable
public fun TrideSceneView(
    scene: TrideKitScene?,
    modifier: Modifier = Modifier,
    backend: FilamentSurfaceView.Backend = FilamentSurfaceView.Backend.AUTO,
    renderPolicy: FilamentSurfaceView.RenderPolicy = FilamentSurfaceView.RenderPolicy.ON_DEMAND,
    pixelScale: Float? = null,
    preferredFps: Int? = null,
    renderOverrides: TrideRenderOverrides? = null,
    onViewCreated: ((FilamentSurfaceView) -> Unit)? = null,
    onLoadError: ((Exception) -> Unit)? = null,
) {
    key(scene) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                FilamentSurfaceView(context, backend).also { view ->
                    view.renderPolicy = renderPolicy
                    view.pixelScale = pixelScale
                    // Before the scene: its first frame is drawn with the knobs.
                    view.renderOverrides = renderOverrides
                    try {
                        view.scene = scene
                    } catch (e: Exception) {
                        // Inside the composition's apply: the host hears it after, never as a crash.
                        Log.e(TAG, "the scene is not shown: ${e.message}")
                        if (onLoadError != null) Handler(Looper.getMainLooper()).post { onLoadError(e) }
                    }
                    // After the scene: a refused rate reaches its diagnostics.
                    view.preferredFps = preferredFps
                    onViewCreated?.invoke(view)
                }
            },
            update = { view ->
                view.renderPolicy = renderPolicy
                view.pixelScale = pixelScale
                view.preferredFps = preferredFps
                view.renderOverrides = renderOverrides
            },
            onRelease = { view -> view.dispose() },
        )
    }
}
