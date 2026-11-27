/**
 * `MemoryTrim` — the process's one `ComponentCallbacks2`: the system's memory trims reach every live scene view
 * and the shared engines.
 *
 * Public API (internal):
 *   - `register(view)` (the first one registers the callbacks with the application) / `unregister(view)`.
 *   - `onTrimMemory(level)` — what the system calls; checks may call it through `Application.onTrimMemory`.
 *
 * Non-obvious constraints:
 *   - Android 14 and later send only `TRIM_MEMORY_UI_HIDDEN` and `TRIM_MEMORY_BACKGROUND`; a higher level counts as
 *     the one under it (older systems send them). The levels sent while the app runs in front do nothing.
 *   - UI hidden: every view gives back its swap chain, Renderer and pixelation targets; engine and scenes stay. A
 *     view back on screen makes them again at its next frame.
 *   - Background: views out of their window also unload their scene (meshes, textures, materials), rebuilt from the
 *     core's scene when the view is back in a window; then an engine with no views is destroyed. With no views
 *     while the app is in front, the engine lives on: its next view compiles nothing.
 *   - Views are held weakly: one dropped without `dispose` does not keep the list.
 *   - The callbacks arrive on the main thread, where the engine lives.
 */
package studio.tride.kit

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import java.lang.ref.WeakReference

private const val TAG = TrideLog.VIEW

internal object MemoryTrim : ComponentCallbacks2 {
    private val views = ArrayList<WeakReference<FilamentSurfaceView>>()
    private var registered = false

    fun register(view: FilamentSurfaceView) {
        if (!registered) {
            view.context.applicationContext.registerComponentCallbacks(this)
            registered = true
        }
        views.removeAll { it.get() == null }
        views += WeakReference(view)
    }

    fun unregister(view: FilamentSurfaceView) {
        views.removeAll { it.get().let { v -> v == null || v === view } }
    }

    override fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        val background = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        val live = views.mapNotNull { it.get() }
        for (view in live) view.trimMemory(background)
        val engines = if (background) SharedEngine.destroyUnused() else 0
        TrideLog.info(TAG) { "memory trim $level: ${live.size} views gave back their swap chains, ${live.count { it.sceneUnloaded }} scenes unloaded, $engines engines destroyed" }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("The system no longer calls it from Android 14; the trims carry the levels.")
    override fun onLowMemory() = Unit
}
