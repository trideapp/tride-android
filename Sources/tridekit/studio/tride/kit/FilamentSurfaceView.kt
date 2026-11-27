/**
 * `FilamentSurfaceView` — the view a scene is drawn in: a `FrameLayout` holding a `TextureView` (the default) or
 * a `SurfaceView` (`SurfaceKind`), that owns a `FilamentSceneBridge`, hands the surface to Filament through
 * `UiHelper`, drives the scene's tick and the frame from one `Choreographer` callback, and forwards one finger
 * to the scene (`OneFinger`: down / move, and on the lift up then cancel — the scene hears the finger leave).
 *
 * Public API:
 *   - `FilamentSurfaceView(context, backend, surface)`; `surfaceKind`; `scene` — the `TrideKitScene` to show
 *     (attached when set; another scene later — or the same one again — gets a new Filament view and scene on
 *     the process's shared engine, drawn on the same swap chain).
 *   - `renderPolicy` — `ON_DEMAND` (frames only when the bridge `needsRender`, the default) or `CONTINUOUS`
 *     (every frame while the scene plays or temporal anti-aliasing is on; a paused scene draws what changes).
 *   - `backend` — the one in use (`VULKAN` / `OPENGL`); `frameCount` / `onFrameDrawn`; `dispose` (a View host
 *     calls it; `TrideSceneView` does it itself).
 *   - `pixelCopy(bitmap, listener, handler)` — the scene's last frame (`PixelCopy` of either surface).
 *   - `pixelScale` — drawable pixels per dp (`null` = the screen's): a soft scene at 2 on a 3× phone draws
 *     44 % of the pixels; the compositor scales the buffer up.
 *   - `orbitFreeCamera` / `useSceneCamera` — look around with the view's own camera, and back.
 *   - `preferredFps` — frames drawn a second, and the most the scene's logic is woken (it still steps 60 Hz);
 *     `null` = every vsync, as before; changes live (`FramePacer`); Filament's dynamic resolution aims at the
 *     rate's frame time on this display (`RenderRate.filamentPace`; no rate = 16.7 ms, as before).
 *   - `renderOverrides` — the host's render knobs over the scene's render settings, handed to every scene the
 *     view shows (`TrideKitScene.setRenderOverrides`); changes live, on the same view and scene.
 *   - Every scene is built from the core's scene record; one the core hands no record of is not shown
 *     (`scene = …` throws `IllegalStateException("scene not built: …")`, the scene shown before is gone;
 *     `TrideSceneView` hands what the setter throws to its `onLoadError` instead).
 *
 * Non-obvious constraints:
 *   - Surface: a `TextureView` composes with the views around it — a scene with a transparent
 *     background shows the app under it, and the app's views over it; a `SurfaceView` is its own layer, a
 *     transparent one is put on top of the window (`setZOrderOnTop`, Filament's `UiHelper`): what is under it
 *     shows, nothing of the app lies over it. The surface is opaque unless the scene's frame is transparent
 *     (`UiHelper.setOpaque` before `attachTo`, the swap chain `CONFIG_TRANSPARENT`); a scene with the other
 *     flag gets a new surface (a new `UiHelper` and child view) before it is attached.
 *   - Backend: `Backend.AUTO` = Vulkan on a device that declares Vulkan 1.1 hardware on Android 10+,
 *     OpenGL ES otherwise and on the emulator. A Vulkan driver that fails inside Filament aborts the
 *     process (a native check, not an exception), so the choice is made before the engine exists;
 *     `Backend.VULKAN` asks for Vulkan without that check.
 *   - The scene hears its size and the finger in dp (view px ÷ `displayMetrics.density`): the core's
 *     tap tolerance is in points, the unit iOS and the browser send — in px a finger's jitter on a 3×
 *     screen turned a tap into a drag. Pixelation's block side is in dp too: the bridge hears the
 *     buffer's pixels per dp with every resize (the density, or the pixel scale).
 *   - One engine per process and backend (`SharedEngine`): every view draws with it, through its own
 *     Renderer and swap chain (`SurfaceTarget`). One scene per bridge: setting a scene on a view whose bridge
 *     already took one disposes that bridge and builds the next on the same target (`replaceBridge`) — the
 *     swap chain stays, nothing waits for the GPU.
 *   - The window going: a TextureView's waits for nothing — its SurfaceTexture is released once
 *     the driver has destroyed the swap chain on it (`onSurfaceTextureDestroyed` returns false); a SurfaceView's
 *     waits for the engine's queue, as Android asks of `surfaceDestroyed` (a neighbour's compile too).
 *   - A resize to another size makes the swap chain again on the same surface (`SwapChainSize`): Vulkan on
 *     Android reads the window's size from what the surface cached before the resize, so the frame after a
 *     turn was drawn at the size of the orientation before. Both backends take the one path.
 *   - Choreographer, touch and every Filament call run on the main thread; the scene's tick runs on TrideKit's
 *     preparation thread and lands one frame later (`TrideKitScene.tickMode`).
 *   - A render rate skips vsyncs by elapsed time: a skipped vsync neither ticks nor draws (it only polls the
 *     drawn-frame fence). Above the screen's rate = every vsync; 0 or less = the default and one diagnostic.
 *   - The frame loop (the scene's tick and the frame) runs while the view is attached to a window that is
 *     shown: a stopped activity — the app in the background — takes no steps and draws nothing (iOS's display
 *     link stops with the app the same way); back on screen the scene goes on from where it stood.
 *   - By the view's distance from the screen (`ScreenDistance`), taken at every wake of the loop, whatever the
 *     policy: only a view showing on screen draws; one within a screen of it draws its first frame and stands; one
 *     further gives back its swap chain, Renderer and pixelation targets, and its surface keeps the last frame.
 *     The scene ticks on. Memory trims (`MemoryTrim`): the app hidden, every view gives them back; in the
 *     background, a view out of its window also unloads its scene, built again from the core's when it is back.
 */
package studio.tride.kit

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.view.Choreographer
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.google.android.filament.Engine
import com.google.android.filament.Fence
import com.google.android.filament.android.UiHelper
import studio.tride.core.TridePointerPhase
import studio.tride.core.TrideRenderOverrides

private const val TAG = TrideLog.VIEW

public class FilamentSurfaceView @JvmOverloads constructor(
    context: Context,
    backend: Backend = Backend.AUTO,
    /** What the scene is drawn into: a `TextureView` (default) or a `SurfaceView` — see the file's notes. */
    public val surfaceKind: SurfaceKind = SurfaceKind.TEXTURE_VIEW,
) : FrameLayout(context) {

    public enum class Backend { AUTO, VULKAN, OPENGL }
    public enum class RenderPolicy { ON_DEMAND, CONTINUOUS }
    /** `TEXTURE_VIEW` composes with the app's views; `SURFACE_VIEW` is its own layer (a transparent one on top). */
    public enum class SurfaceKind { TEXTURE_VIEW, SURFACE_VIEW }

    internal var bridge: FilamentSceneBridge
        private set
    /** The engine the view asked for (`AUTO` resolved): every bridge of this view is built on it. */
    private val requestedBackend: Engine.Backend
    /** The current bridge took a scene: the next one needs a fresh bridge. */
    private var bridgeUsed = false
    /** The swap chain's buffer in pixels (the last `onResized`); 0 before the first. */
    private var bufferWidth = 0
    private var bufferHeight = 0
    /** The window size the swap chain was made for: a resize to another makes it again. */
    private val swapChainSize = SwapChainSize()

    /** The backend the engine runs on: `VULKAN` or `OPENGL` (never `AUTO`). */
    public val backend: Backend
        get() = if (bridge.backend == Engine.Backend.VULKAN) Backend.VULKAN else Backend.OPENGL
    private var uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    /** The child the scene is drawn into ([surfaceKind]); made again for a scene of the other opacity. */
    private lateinit var target: View
    /** The surface's opacity — what `uiHelper` was attached with. */
    private var opaque = true
    /** The window `UiHelper` handed over last; `null` while there is none. */
    private var currentSurface: Surface? = null
    private val choreographer = Choreographer.getInstance()
    private var attachedToWindow = false
    private var disposed = false
    /** The frame callback is posted ([startLoop] … [stopLoop]). */
    private var looping = false
    /** The loop ran and stood since: the scene hears it resume ([TrideKitScene.loopResumed]). */
    private var loopStood = false
    /** Where the view stood from the screen when last asked; `null` before the first time. */
    internal var screenDistance: ScreenDistance? = null
        private set
    /** A frame was drawn on the current swap chain: a near view stands after it. */
    private var drewOnSwapChain = false
    /** A background trim unloaded the scene while the view was out of its window: built again on its return. */
    internal var sceneUnloaded = false
        private set
    private val visibleRect = android.graphics.Rect()
    private val windowLocation = IntArray(2)

    public var renderPolicy: RenderPolicy = RenderPolicy.ON_DEMAND

    public var pixelScale: Float? = null
        set(value) {
            if (field == value) return
            field = value
            applyPixelScale()
        }

    /**
     * Frames drawn a second, and the most the scene's logic is woken — it still runs 60 steps a second, so
     * animations and timings stay the same. `null` = every vsync (as before); above the screen's rate = the
     * screen's; 0 or less = the default and one diagnostic. Changes at once, on the same view and scene.
     */
    public var preferredFps: Int? = null
        set(value) {
            if (field == value) return
            field = value
            val rate = RenderRate.resolve(value)
            if (rate.refused && value != null && !refusedRateReported) {
                refusedRateReported = true
                val message = RenderRate.refusalMessage(value)
                scene?.takeUnless { it.isClosed }?.reportFromView("preferredFps", message) ?: TrideLog.warning(TAG) { message }
            }
            pacer.setRate(rate.fps)
            handPace()
        }
    private val pacer = FramePacer()
    private var refusedRateReported = false

    /**
     * The host's render knobs (MSAA, bloom, shadows, depth of field) over the scene's render settings; a field
     * left `null` keeps the scene's value. Set, they go to the shown scene at once and to every scene set
     * later; left `null` (never set), a scene keeps the knobs given to it directly. See
     * `TrideKitScene.setRenderOverrides`.
     */
    public var renderOverrides: TrideRenderOverrides? = null
        set(value) {
            val next = value?.copy()
            if (field == next) return
            field = next
            scene?.takeUnless { it.isClosed }?.setRenderOverrides(next)
        }

    /** The rate the frame loop wakes at; `null` = every vsync. */
    internal val wakeRate: Int? get() = pacer.fps

    /** Vsyncs the loop heard, and the ones that woke it (instrumentation: the render-rate tests count them). */
    internal var vsyncCount: Long = 0L
        private set
    internal var wakeCount: Long = 0L
        private set

    /** Frames Filament has drawn in this view so far (none before a scene is set; counting goes on across scenes). */
    public var frameCount: Long = 0L
        private set

    /**
     * Called once Filament's driver has executed a drawn frame, with the running count — the host's
     * "on screen" signal. Not at the end of the frame call: the driver works behind it, and a first frame
     * that compiles many materials can take seconds there (the view shows nothing until then). One frame
     * is followed at a time, so the counts reported can skip, but the newest frame is always reported in
     * the end; the first scene's first frame is reported as 1. After another scene is set, its frames are
     * the ones past the count at that moment.
     */
    public var onFrameDrawn: ((Long) -> Unit)? = null

    /** A fence behind the oldest unreported frame; one at a time, polled on each vsync. */
    private var frameFence: Fence? = null
    private var fencedFrame = 0L
    /** Frames up to this count were the scenes' before the shown one: followed (the swap chain's), not reported. */
    private var reportFloor = 0L

    /** The engine asks it before destroying a retired Renderer (`SharedEngine.retire`). */
    private val frameQueue = SharedEngine.FrameQueue { !disposed && ownFrameQueued() }

    /**
     * This view's newest frame may not be executed yet: its fence has not signalled, or it has none yet. A loop
     * that stood (out of the window, the app hidden) with frames past its fence fences the newest here: no later
     * frame of its own would, and the engine's retired Renderers waited for it for good.
     */
    private fun ownFrameQueued(): Boolean {
        val fence = frameFence
        if (fence != null && fence.wait(Fence.Mode.DONT_FLUSH, 0) != Fence.FenceStatus.CONDITION_SATISFIED) return true
        if (fencedFrame >= frameCount) return false
        if (looping) return true
        // The older fence signalled (its report skipped: the newest is reported once the loop runs again).
        fence?.let { bridge.engine.destroyFence(it) }
        frameFence = bridge.engine.createFence()
        bridge.engine.flush()
        fencedFrame = frameCount
        return true
    }

    public var scene: TrideKitScene? = null
        set(value) {
            val old = field
            if (old === value) return
            // Refused before anything moves: the shown scene stays.
            if (value != null) {
                check(!disposed) { "FilamentSurfaceView is disposed: a new view shows the scene" }
                check(!value.isClosed) { "TrideKitScene is closed" }
            }
            if (old != null) {
                if (finger.isDown && !old.isClosed) old.pointer(TridePointerPhase.CANCEL, lastX, lastY)
                finger.reset()
                old.detach(bridge)
            }
            sceneUnloaded = false
            freeOrbit = null
            field = null
            if (value == null) return
            if (bridgeUsed) replaceBridge()
            bridgeUsed = true
            // The surface's opacity is the scene's (static per scene): another one gets a new surface first.
            val wantOpaque = !value.drawsTransparent()
            if (wantOpaque != opaque) makeSurface(wantOpaque)
            // The view's knobs before the attach: the first frame is drawn with them.
            renderOverrides?.let { value.setRenderOverrides(it) }
            try {
                value.attach(bridge, context.assets)
            } catch (e: Exception) {
                // A scene the bridge refused is not shown: nothing it set up reaches this view's frames.
                value.detach(bridge)
                TrideLog.error(TAG) { "the scene is not shown: ${e.message}" }
                throw e
            }
            field = value
            reportSize()
        }

    /**
     * A fresh bridge for the next scene on the view's swap chain and Renderer (they stay, nothing waits) and the
     * buffer size the old one drew at. The old scene's frames in flight stay followed — the window may go while
     * one is queued — but are not reported as the new scene's.
     */
    private fun replaceBridge() {
        reportFloor = frameCount
        sceneCamera = null
        val target = bridge.takeTarget()
        bridge.dispose()
        bridge = FilamentSceneBridge(requestedBackend, target)
        handPace()
        bridgeUsed = false
        if (bufferWidth > 0 && bufferHeight > 0) bridge.resize(bufferWidth, bufferHeight, pixelsPerDp(bufferWidth))
    }

    private val finger = OneFinger()
    private var lastX = 0f
    private var lastY = 0f

    init {
        val requested = when (backend) {
            Backend.VULKAN -> Engine.Backend.VULKAN
            Backend.OPENGL -> Engine.Backend.OPENGL
            Backend.AUTO -> if (!isEmulator() && supportsVulkan(context)) Engine.Backend.VULKAN else Engine.Backend.OPENGL
        }
        requestedBackend = requested
        bridge = FilamentSceneBridge(requested)
        bridge.shared.watch(frameQueue)
        MemoryTrim.register(this)
        TrideLog.debug(TAG) { "FilamentSurfaceView: backend=${bridge.backend} (requested $requested) surface=$surfaceKind hardware=${Build.HARDWARE}" }
    }

    private val surfaceCallback = object : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            currentSurface = surface
            // Far from the screen, the window waits for the view to come near.
            if (measureDistance() == ScreenDistance.FAR) giveBackSurface() else makeSwapChain(surface)
        }
        override fun onDetachedFromSurface() {
            swapChainSize.gone()
            currentSurface = null
            bridge.detachSurface(waitForDriver = surfaceKind == SurfaceKind.SURFACE_VIEW)
        }
        override fun onResized(width: Int, height: Int) {
            bufferWidth = width
            bufferHeight = height
            // The swap chain made for the size before would draw this frame at that size (`SwapChainSize`). A
            // TextureView's `UiHelper` hands the window over again after a resize itself.
            val stale = swapChainSize.resized(width, height)
            val surface = currentSurface
            if (stale && surfaceKind == SurfaceKind.SURFACE_VIEW && surface != null) bridge.attachSurface(surface, uiHelper.swapChainFlags)
            bridge.resize(width, height, pixelsPerDp(width))
            reportSize()
        }
    }

    private fun newTarget(): View = when (surfaceKind) {
        SurfaceKind.TEXTURE_VIEW -> TextureView(context)
        SurfaceKind.SURFACE_VIEW -> SurfaceView(context)
    }

    /**
     * `uiHelper` on [view] with the view's opacity, then [view] into the layout: a `SurfaceView`'s z-order
     * (`setZOrderOnTop`, set by `attachTo`) holds only when set before the view reaches the window.
     */
    private fun attachSurface(view: View) {
        uiHelper.isOpaque = opaque
        uiHelper.renderCallback = surfaceCallback
        when (view) {
            is TextureView -> {
                uiHelper.attachTo(view)
                view.surfaceTextureListener = ReleaseBehindDriver(view.surfaceTextureListener)
            }
            is SurfaceView -> uiHelper.attachTo(view)
        }
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** A new surface of the given opacity: the old one leaves Filament (its swap chain first) and the layout. */
    private fun makeSurface(opaque: Boolean) {
        uiHelper.detach()
        keepTextureUntilDriverDone()
        removeView(target)
        this.opaque = opaque
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        target = newTarget()
        attachSurface(target)
        applyPixelScale()
        TrideLog.debug(TAG) { "FilamentSurfaceView: ${if (opaque) "opaque" else "transparent"} $surfaceKind for the scene" }
    }

    /**
     * `UiHelper`'s TextureView listener, but the SurfaceTexture outlives the swap chain on it: released once the
     * driver has destroyed that swap chain, so the window going waits for nothing and the driver never touches
     * an abandoned queue.
     */
    private inner class ReleaseBehindDriver(private val inner: TextureView.SurfaceTextureListener?) : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) { inner?.onSurfaceTextureAvailable(st, width, height) }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) { inner?.onSurfaceTextureSizeChanged(st, width, height) }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) { inner?.onSurfaceTextureUpdated(st) }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            inner?.onSurfaceTextureDestroyed(st)
            bridge.shared.afterQueue { st.release() }
            return false
        }
    }

    /** `UiHelper` let go of the TextureView (its listener cleared): the texture still goes behind the driver. */
    private fun keepTextureUntilDriverDone() {
        (target as? TextureView)?.surfaceTextureListener = ReleaseBehindDriver(null)
    }

    /** The swap chain's buffer in pixels, as `UiHelper` last reported it (checks). */
    internal val bufferSize: Pair<Int, Int> get() = bufferWidth to bufferHeight

    /**
     * The scene's last frame into [bitmap] (the view's size; the app's views around it are not in it): `PixelCopy`
     * from a `SurfaceView`, the `TextureView`'s own copy (`getBitmap`, on [handler]'s thread) — [listener] hears
     * `PixelCopy.SUCCESS` or why not. The view is no `SurfaceView` itself: a host reads its frames here.
     */
    public fun pixelCopy(bitmap: Bitmap, listener: PixelCopy.OnPixelCopyFinishedListener, handler: Handler) {
        when (val v = target) {
            is SurfaceView -> PixelCopy.request(v, bitmap, listener, handler)
            is TextureView -> handler.post {
                if (!v.isAvailable) listener.onPixelCopyFinished(PixelCopy.ERROR_SOURCE_NO_DATA)
                else { v.getBitmap(bitmap); listener.onPixelCopyFinished(PixelCopy.SUCCESS) }
            }
        }
    }

    /** The surface is opaque (the shown scene's frame has no alpha); checks. */
    internal val surfaceOpaque: Boolean get() = opaque

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (disposed || !looping) return
            vsyncCount++
            if (pacer.admit(frameTimeNanos)) {
                wakeCount++
                val distance = measureDistance()
                if (distance == ScreenDistance.FAR) giveBackSurface() else currentSurface?.let { if (!bridge.hasSwapChain) makeSwapChain(it, sized = true) }
                val shown = scene
                shown?.onFrame(frameTimeNanos, pacer.maxStepMs)
                // Nothing is drawn before a scene is: an empty frame reported "on screen" let a host drop its
                // placeholder seconds before the scene's first frame (its shaders still compiling).
                // A paused scene draws the same picture again: CONTINUOUS draws every frame only while the scene plays
                // or TAA is on (its jitter and history make still frames differ). A near view draws one frame on its
                // swap chain (its compile off the swipe), then stands.
                val due = when (distance) {
                    ScreenDistance.ON_SCREEN -> bridge.needsRender || renderPolicy == RenderPolicy.CONTINUOUS &&
                        (shown?.isPlaying == true || bridge.temporalAntiAliasing)
                    ScreenDistance.NEAR -> !drewOnSwapChain
                    ScreenDistance.FAR -> false
                }
                if (shown != null && uiHelper.isReadyToRender && due && bridge.render(frameTimeNanos)) {
                    frameCount++
                    drewOnSwapChain = true
                }
            }
            frameFence?.let { fence ->
                if (fence.wait(Fence.Mode.DONT_FLUSH, 0) == Fence.FenceStatus.CONDITION_SATISFIED) {
                    bridge.engine.destroyFence(fence)
                    frameFence = null
                    if (fencedFrame > reportFloor) onFrameDrawn?.invoke(fencedFrame)
                }
            }
            // One fence at a time, always behind the newest frame: frames drawn while an older fence was
            // pending get theirs once it signals — an on-demand view's last frame is reported too.
            if (frameFence == null && fencedFrame < frameCount) {
                // The fence's command sits behind the frame; flush it, or a held frame (no next frame to
                // flush the stream) never reaches the driver's signal.
                frameFence = bridge.engine.createFence()
                bridge.engine.flush()
                fencedFrame = frameCount
            }
            if (looping) choreographer.postFrameCallback(this)
        }
    }

    /**
     * The view's distance from the screen now ([ScreenDistance]): shown with a pixel left after its parents' clips,
     * else the gaps between its rectangle and its window's; kept for the next time (the far boundary's hysteresis).
     */
    private fun measureDistance(): ScreenDistance {
        val root = rootView
        // Not laid out yet: on screen, as before, but not kept — the first real decision takes the near rule alone.
        if (width <= 0 || height <= 0 || root.width <= 0 || root.height <= 0) return ScreenDistance.ON_SCREEN
        val visible = isShown && getGlobalVisibleRect(visibleRect)
        getLocationInWindow(windowLocation)
        val (x, y) = windowLocation[0] to windowLocation[1]
        val distance = ScreenDistance.next(
            screenDistance, visible,
            ScreenDistance.gap(x, x + width, root.width), ScreenDistance.gap(y, y + height, root.height),
            root.width, root.height,
        )
        screenDistance = distance
        return distance
    }

    /**
     * The swap chain on [surface] (and a Renderer, when the view gave its own back); [sized] = the window keeps the
     * size it last reported (the view back near), else `onResized` follows (a new window).
     */
    private fun makeSwapChain(surface: Surface, sized: Boolean = false) {
        bridge.attachSurface(surface, uiHelper.swapChainFlags)
        swapChainSize.made()
        if (sized && bufferWidth > 0 && bufferHeight > 0) swapChainSize.resized(bufferWidth, bufferHeight)
        drewOnSwapChain = false
    }

    /** Swap chain, Renderer and pixelation targets go; the surface stays and shows the last frame. */
    private fun giveBackSurface() {
        if (!bridge.holdsGpu) return
        bridge.giveBackSurface()
        swapChainSize.gone()
        drewOnSwapChain = false
    }

    /**
     * A memory trim ([MemoryTrim]): the view gives back its swap chain, Renderer and pixelation targets; in the
     * [background], one out of its window also unloads its scene — the scene's runtime stays, and the view builds
     * the scene again from it when it is back in a window.
     */
    internal fun trimMemory(background: Boolean) {
        if (disposed) return
        giveBackSurface()
        if (background && !attachedToWindow) unloadScene()
    }

    private fun unloadScene() {
        val shown = scene ?: return
        if (sceneUnloaded || !bridgeUsed) return
        shown.detach(bridge)
        replaceBridge()
        sceneUnloaded = true
        TrideLog.debug(TAG) { "FilamentSurfaceView: the scene unloaded (background, out of the window)" }
    }

    /** The scene unloaded in the background, built again from its runtime: it goes on from where it stood. */
    private fun reloadScene() {
        if (!sceneUnloaded) return
        sceneUnloaded = false
        val shown = scene?.takeUnless { it.isClosed } ?: return
        renderOverrides?.let { shown.setRenderOverrides(it) }
        try {
            shown.attach(bridge, context.assets)
            bridgeUsed = true
        } catch (e: Exception) {
            // Built once already: a refusal now leaves the view empty, the scene and its runtime usable.
            shown.detach(bridge)
            TrideLog.error(TAG) { "the scene is not shown again: ${e.message}" }
            return
        }
        freeOrbit?.let { (yaw, pitch, scale) -> orbitFreeCamera(yaw, pitch, scale) }
        reportSize()
    }

    /**
     * Tick and draw from the next vsync — while attached to a shown window only. Choreographer keeps
     * delivering vsyncs to a stopped activity: without this a playing scene stepped at 60 Hz in the background.
     */
    private fun startLoop() {
        if (looping || disposed || !attachedToWindow || windowVisibility != VISIBLE) return
        looping = true
        pacer.reset()
        if (loopStood) {
            loopStood = false
            scene?.takeUnless { it.isClosed }?.loopResumed()
            // Back on screen, one frame at least: a SurfaceView's new surface asked for it by itself, a TextureView
            // keeps its surface across a stop and would show the old frame until the scene next moves.
            bridge.needsRender = true
        }
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        if (!looping) return
        looping = false
        loopStood = true
        choreographer.removeFrameCallback(frameCallback)
    }

    /** The render rate and this display's refresh rate to Filament (`RenderRate.filamentPace`). */
    private fun handPace() {
        val (hz, interval) = RenderRate.filamentPace(pacer.fps, display?.refreshRate ?: 60f)
        bridge.setFrameRate(hz, interval)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The display is known from here: its refresh rate reaches Filament.
        handPace()
        attachedToWindow = true
        reloadScene()
        startLoop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startLoop() else stopLoop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyPixelScale()
    }

    /**
     * The surface's buffer at [pixelScale] px per dp; pointer and viewport stay in dp (`reportSize`).
     * No scale (or one at the screen's density or above) hands the buffer back to the layout's size.
     */
    private fun applyPixelScale() {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val scale = pixelScale?.takeIf { it < density }
        if (scale == null) {
            if (uiHelper.desiredWidth > 0) when (val v = target) {
                is SurfaceView -> { uiHelper.setDesiredSize(0, 0); v.holder.setSizeFromLayout() }
                // A TextureView's buffer is set, not followed: the layout's size, kept at each size change.
                else -> uiHelper.setDesiredSize(width, height)
            }
            return
        }
        uiHelper.setDesiredSize((width * scale / density).toInt(), (height * scale / density).toInt())
    }

    override fun onDetachedFromWindow() {
        attachedToWindow = false
        stopLoop()
        super.onDetachedFromWindow()
    }

    /** The scene camera [orbitFreeCamera] stepped aside from — [useSceneCamera] brings it back. */
    private var sceneCamera: String? = null
    /** The view's own camera's last orbit (yaw, pitch, distance scale): a scene built again takes it. */
    private var freeOrbit: Triple<Float, Float, Float>? = null

    /**
     * Look through the view's own camera, orbited around what it framed: [yaw] / [pitch] radians from the
     * framed pose (0, 0, 1 = as framed, eye above the centre included), [distanceScale] × its eye → centre
     * distance. A scene camera that drew steps aside until [useSceneCamera], and the view's camera takes its
     * pose and lens over first — (0, 0, 1) is then where that camera stood, as iOS `useFreeCamera`. The
     * scene's next tick sees this camera (Look at Camera tags turn toward it).
     */
    public fun orbitFreeCamera(yaw: Float, pitch: Float, distanceScale: Float = 1f) {
        if (disposed) return
        freeOrbit = Triple(yaw, pitch, distanceScale)
        bridge.activeCameraId?.let { id ->
            sceneCamera = id
            bridge.useFreeCamera()
        }
        bridge.setViewCameraOrbit(yaw.toDouble(), pitch.toDouble(), distanceScale.toDouble())
    }

    /** Back to the scene camera [orbitFreeCamera] stepped aside from; nothing when none did. */
    public fun useSceneCamera() {
        if (disposed) return
        freeOrbit = null
        val id = sceneCamera ?: return
        sceneCamera = null
        bridge.setActiveCamera(id)
    }

    /** Release Filament; the view is dead afterwards. A scene it showed stays usable and draws nothing. */
    public fun dispose() {
        if (disposed) return
        disposed = true
        stopLoop()
        // The scene may outlive the view (its owner keeps it): it must not reach this engine again.
        scene?.detach(bridge)
        uiHelper.detach()
        keepTextureUntilDriverDone()
        frameFence?.let { bridge.engine.destroyFence(it) }
        frameFence = null
        bridge.shared.unwatch(frameQueue)
        MemoryTrim.unregister(this)
        bridge.dispose()
    }

    /** View px → dp, the scene's unit. */
    private val density: Float get() = resources.displayMetrics.density

    /**
     * Buffer pixels a dp spans on screen for a buffer [bufferWidth] wide: the density at the layout's
     * size, the pixel scale when the buffer is smaller — what a point of pixelation is in this frame.
     */
    private fun pixelsPerDp(bufferWidth: Int): Float = if (width > 0) bufferWidth * density / width else density

    private fun reportSize() {
        val s = scene?.takeUnless { it.isClosed } ?: return
        if (width > 0 && height > 0) s.setViewport(width / density, height / density)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = scene?.takeUnless { it.isClosed } ?: return super.onTouchEvent(event)
        val steps = finger.feed(event)
        if (steps.isEmpty()) return super.onTouchEvent(event)
        val d = density
        for (step in steps) {
            lastX = step.x / d; lastY = step.y / d
            s.pointer(step.phase, lastX, lastY)
        }
        return true
    }

    // The first surface, last: the callbacks above must exist before `UiHelper` can call them.
    init {
        target = newTarget()
        attachSurface(target)
    }

    private companion object {
        /** Filament's Vulkan backend needs 1.1; older drivers on Android 7–9 are where it aborts. */
        private const val VULKAN_1_1 = (1 shl 22) or (1 shl 12)

        internal fun supportsVulkan(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1)
    }
}

/** The process runs on an Android emulator (the backend choice, and the bridge's shadows on the emulator's GL). */
internal fun isEmulator(): Boolean {
    val hw = Build.HARDWARE.lowercase()
    return hw.contains("ranchu") || hw.contains("goldfish") || hw.contains("cutf") ||
        Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") ||
        Build.MODEL.contains("Android SDK built for")
}
