/**
 * `SurfaceTarget` — a view's side of the frame on the shared engine: its `Renderer` and the swap chain on its
 * window, with the pace Filament's dynamic resolution aims at. A view keeps it across the scenes it shows: a new
 * scene draws on the same swap chain and Renderer, nothing waits for the GPU.
 *
 * Public API (internal):
 *   - `SurfaceTarget(shared)`; `renderer`, `swapChain` (`null` while given back); `holdsGpu`.
 *   - `attach(surface, flags)` — the swap chain on [surface], made anew (a new window, or the same one resized), and
 *     a Renderer when the view gave its own back (the first one is made with the target); `detach(waitForDriver)` — the window goes, the Renderer stays;
 *     `giveBack()` — swap chain and Renderer go, the window stays (a view far from the screen, a memory trim);
 *     `destroy()`.
 *   - `setFrameRate(refreshRate, interval)`, `displayRefreshRate` / `frameInterval`; `gpuFrameMillis`,
 *     `stallDriver`, `swapChainsMade` (checks).
 *
 * Non-obvious constraints:
 *   - Letting go of a swap chain waits for the driver only when asked ([waitForDriver]: a SurfaceView's window
 *     is going — Android wants the driver out of it before `surfaceDestroyed` returns; the wait covers the whole
 *     queue, a neighbour's compile too). A swap chain given back without waiting counts too: the window going
 *     after it still waits. The engine's one command stream orders the rest: the old swap chain goes before a
 *     new one on the same window is made.
 *   - A Renderer let go of is retired to the engine (`SharedEngine.retire`): Filament's destroy of it waits for
 *     the whole queue, a neighbour's compile included. A new one gets what the old one held: the clear options
 *     and the pace (its frame history starts empty).
 *   - Every frame clears the colour buffer to (0, 0, 0, 0): nothing behind the scene's environment, and the
 *     16-padded post-processing buffers never hold undefined texels (tile-based GPUs pulled them into the
 *     right / top edges as blobs under TAA / DOF / bloom / SSAO).
 */
package studio.tride.kit

import android.view.Surface
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain

internal class SurfaceTarget(private val shared: SharedEngine) {
    private val engine get() = shared.engine
    var renderer: Renderer? = null
        private set
    var swapChain: SwapChain? = null
        private set
    private var destroyed = false
    /** A swap chain went without a wait: the driver may still be in the window. */
    private var unsettled = false
    /** The pace was set once: every Renderer of this view gets it. */
    private var paced = false

    // The view's first Renderer from the start, as a view always had: it holds the pace before any window.
    init { renderer = newRenderer() }

    /** Swap chains made on this view so far (checks: a view on the far boundary must not make one each frame). */
    var swapChainsMade = 0
        private set

    /** It holds a swap chain or a Renderer. */
    val holdsGpu: Boolean get() = swapChain != null || renderer != null

    /** The swap chain on [surface], the one before let go first (in the stream's order: no wait). */
    fun attach(surface: Surface, flags: Long) {
        if (destroyed) return
        if (swapChain != null) detach(waitForDriver = false)
        if (renderer == null) renderer = newRenderer()
        swapChain = engine.createSwapChain(surface, flags)
        shared.swapChainMade()
        swapChainsMade++
    }

    fun detach(waitForDriver: Boolean) {
        val sc = swapChain
        if (sc != null) {
            swapChain = null
            engine.destroySwapChain(sc)
            shared.swapChainGone()
        } else if (!unsettled) return
        if (waitForDriver) {
            engine.flushAndWait()
            unsettled = false
        } else {
            engine.flush()
            unsettled = true
        }
    }

    /** Swap chain and Renderer go; the window stays the view's (nothing waits). */
    fun giveBack() {
        detach(waitForDriver = false)
        val r = renderer ?: return
        renderer = null
        // Destroying a Renderer waits for the engine's whole queue: the engine does it once no frame is queued.
        shared.retire(r)
    }

    fun destroy() {
        if (destroyed) return
        // A window still attached outlives this swap chain: nothing to wait for.
        giveBack()
        destroyed = true
    }

    private fun newRenderer(): Renderer = engine.createRenderer().apply {
        clearOptions = Renderer.ClearOptions().apply {
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            clear = true
            discard = true
        }
        shared.rendererMade()
        if (paced) applyPace(this)
    }

    /** The display refresh rate and frame interval Filament holds (`setFrameRate`); 60 and 1 = its own defaults. */
    var displayRefreshRate: Float = 60f
        private set
    var frameInterval: Int = 1
        private set

    /**
     * The view's pace (`RenderRate.filamentPace`): dynamic resolution aims at `1000 × interval / refreshRate` ms of
     * GPU time. Held here and handed to every Renderer the view gets.
     */
    fun setFrameRate(refreshRate: Float, interval: Int) {
        val hz = if (refreshRate > 0f) refreshRate else 60f
        val n = interval.coerceIn(1, 255)
        if ((hz == displayRefreshRate && n == frameInterval) || destroyed) return
        displayRefreshRate = hz
        frameInterval = n
        paced = true
        renderer?.let { applyPace(it) }
    }

    private fun applyPace(r: Renderer) {
        r.setDisplayInfo(Renderer.DisplayInfo().apply { this.refreshRate = displayRefreshRate })
        // Every field but the interval at the engine's own defaults (the Java class's differ from them).
        r.setFrameRateOptions(Renderer.FrameRateOptions().apply {
            this.interval = frameInterval.toFloat()
            headRoomRatio = 0f
            scaleRate = 1f / 8f
            history = 15
        })
    }

    /** GPU time of the frames Filament timed (at most its last 16): frame id → milliseconds. */
    fun gpuFrameMillis(): Map<Int, Double> {
        val r = renderer ?: return emptyMap()
        val history = Array(r.maxFrameHistorySize) { Renderer.FrameInfo() }
        val n = r.getFrameInfoHistory(history)
        return (0 until n).map { history[it] }.filter { it.gpuFrameDuration > 0 }.associate { it.frameId to it.gpuFrameDuration / 1e6 }
    }

    /** Holds Filament's driver thread for [nanos] — the engine's, so every view's (tests). */
    fun stallDriver(nanos: Long) { renderer?.pauseRenderThread(nanos) }
}
