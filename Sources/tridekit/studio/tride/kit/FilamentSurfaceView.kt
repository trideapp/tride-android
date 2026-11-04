/**
 * `FilamentSurfaceView` — a `SurfaceView` that owns a `FilamentSceneBridge`, hands the surface to Filament
 * through `UiHelper`, drives the scene's tick and the frame from one `Choreographer` callback, and forwards
 * one finger to the scene (`OneFinger`: down / move, and on the lift up then cancel — the scene hears the
 * finger leave).
 *
 * Public API:
 *   - `FilamentSurfaceView(context, backend)`; `scene` — the `TrideKitScene` to show (attached when set; another
 *     scene later — or the same one again — is drawn by a fresh Filament engine on the same surface).
 *   - `renderPolicy` — `ON_DEMAND` (frames only when the bridge `needsRender`, the default) or `CONTINUOUS`.
 *   - `backend` — the one in use (`VULKAN` / `OPENGL`); `frameCount` / `onFrameDrawn`; `dispose` (a View host
 *     calls it; `TrideSceneView` does it itself).
 *   - `pixelScale` — drawable pixels per dp (`null` = the screen's): a soft scene at 2 on a 3× phone draws
 *     44 % of the pixels; the compositor scales the buffer up.
 *   - `orbitFreeCamera` / `useSceneCamera` — look around with the view's own camera, and back.
 *   - `preferredFps` — frames drawn a second, and the most the scene's logic is woken (it still steps 60 Hz);
 *     `null` = every vsync, as before; changes live (`FramePacer`).
 *   - `renderOverrides` — the host's render knobs over the scene's render settings, handed to every scene the
 *     view shows (`TrideKitScene.setRenderOverrides`); changes live, on the same view and scene.
 *   - Every scene is built from the core's scene record; one the core hands no record of is not shown
 *     (`scene = …` throws `IllegalStateException("scene not built: …")`, the scene shown before is gone;
 *     `TrideSceneView` hands what the setter throws to its `onLoadError` instead).
 *
 * Non-obvious constraints:
 *   - Backend: `Backend.AUTO` = Vulkan on a device that declares Vulkan 1.1 hardware on Android 10+,
 *     OpenGL ES otherwise and on the emulator. A Vulkan driver that fails inside Filament aborts the
 *     process (a native check, not an exception), so the choice is made before the engine exists;
 *     `Backend.VULKAN` asks for Vulkan without that check.
 *   - The scene hears its size and the finger in dp (view px ÷ `displayMetrics.density`): the core's
 *     tap tolerance is in points, the unit iOS and the browser send — in px a finger's jitter on a 3×
 *     screen turned a tap into a drag. Pixelation's block side is in dp too: the bridge hears the
 *     buffer's pixels per dp with every resize (the density, or the pixel scale).
 *   - One scene per engine (a bridge builds one scene for its lifetime): setting a scene on a view whose engine
 *     already took one disposes that engine and builds the next on the surface it drew to (`replaceBridge`).
 *   - Everything runs on the main thread (Choreographer, touch, Filament calls).
 *   - A render rate skips vsyncs by elapsed time: a skipped vsync neither ticks nor draws (it only polls the
 *     drawn-frame fence). Above the screen's rate = every vsync; 0 or less = the default and one diagnostic.
 *   - The frame loop (the scene's tick and the frame) runs while the view is attached to a window that is
 *     shown: a stopped activity — the app in the background — takes no steps and draws nothing (iOS's display
 *     link stops with the app the same way); back on screen the scene goes on from where it stood.
 */
package studio.tride.kit

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Engine
import com.google.android.filament.Fence
import com.google.android.filament.android.UiHelper
import studio.tride.core.TridePointerPhase
import studio.tride.core.TrideRenderOverrides

private const val TAG = "tride.view"

public class FilamentSurfaceView @JvmOverloads constructor(
    context: Context,
    backend: Backend = Backend.AUTO,
) : SurfaceView(context) {

    public enum class Backend { AUTO, VULKAN, OPENGL }
    public enum class RenderPolicy { ON_DEMAND, CONTINUOUS }

    internal var bridge: FilamentSceneBridge
        private set
    /** The engine the view asked for (`AUTO` resolved): every bridge of this view is built on it. */
    private val requestedBackend: Engine.Backend
    /** The current bridge took a scene: the next one needs a fresh bridge. */
    private var bridgeUsed = false
    /** The swap chain's buffer in pixels (the last `onResized`); 0 before the first. */
    private var bufferWidth = 0
    private var bufferHeight = 0

    /** The backend the engine runs on: `VULKAN` or `OPENGL` (never `AUTO`). */
    public val backend: Backend
        get() = if (bridge.backend == Engine.Backend.VULKAN) Backend.VULKAN else Backend.OPENGL
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val choreographer = Choreographer.getInstance()
    private var attachedToWindow = false
    private var disposed = false
    /** The frame callback is posted ([startLoop] … [stopLoop]). */
    private var looping = false
    /** The loop ran and stood since: the scene hears it resume ([TrideKitScene.loopResumed]). */
    private var loopStood = false

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
                scene?.takeUnless { it.isClosed }?.reportFromView("preferredFps", message) ?: Log.w(TAG, message)
            }
            pacer.setRate(rate.fps)
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
            field = null
            if (value == null) return
            if (bridgeUsed) replaceBridge()
            bridgeUsed = true
            // The view's knobs before the attach: the first frame is drawn with them.
            renderOverrides?.let { value.setRenderOverrides(it) }
            try {
                value.attach(bridge, context.assets)
            } catch (e: Exception) {
                // A scene the bridge refused is not shown: nothing it set up reaches this view's frames.
                value.detach(bridge)
                throw e
            }
            field = value
            reportSize()
        }

    /**
     * A fresh engine for the next scene, on the surface and buffer size the old one drew to. The old bridge goes
     * first: its swap chain must leave the surface before another engine's can be made on it.
     */
    private fun replaceBridge() {
        frameFence?.let { bridge.engine.destroyFence(it) }
        frameFence = null
        // The old engine's frames are not followed any more.
        fencedFrame = frameCount
        sceneCamera = null
        bridge.dispose()
        bridge = FilamentSceneBridge(requestedBackend)
        bridgeUsed = false
        if (uiHelper.isReadyToRender) bridge.attachSurface(holder.surface, uiHelper.swapChainFlags)
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
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                bridge.attachSurface(surface, uiHelper.swapChainFlags)
            }
            override fun onDetachedFromSurface() {
                bridge.detachSurface()
            }
            override fun onResized(width: Int, height: Int) {
                bufferWidth = width
                bufferHeight = height
                bridge.resize(width, height, pixelsPerDp(width))
                reportSize()
            }
        }
        uiHelper.attachTo(this)
        Log.i(TAG, "FilamentSurfaceView: backend=${bridge.backend} (requested $requested) hardware=${Build.HARDWARE}")
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (disposed || !looping) return
            vsyncCount++
            if (pacer.admit(frameTimeNanos)) {
                wakeCount++
                val shown = scene
                shown?.onFrame(frameTimeNanos, pacer.maxStepMs)
                // Nothing is drawn before a scene is: an empty frame reported "on screen" let a host drop its
                // placeholder seconds before the scene's first frame (its shaders still compiling).
                val due = renderPolicy == RenderPolicy.CONTINUOUS || bridge.needsRender
                if (shown != null && uiHelper.isReadyToRender && due && bridge.render(frameTimeNanos)) frameCount++
            }
            frameFence?.let { fence ->
                if (fence.wait(Fence.Mode.DONT_FLUSH, 0) == Fence.FenceStatus.CONDITION_SATISFIED) {
                    bridge.engine.destroyFence(fence)
                    frameFence = null
                    onFrameDrawn?.invoke(fencedFrame)
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
        }
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        if (!looping) return
        looping = false
        loopStood = true
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
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
            if (uiHelper.desiredWidth > 0) {
                uiHelper.setDesiredSize(0, 0)
                holder.setSizeFromLayout()
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

    /**
     * Look through the view's own camera, orbited around what it framed: [yaw] / [pitch] radians from the
     * framed pose (0, 0, 1 = as framed, eye above the centre included), [distanceScale] × its eye → centre
     * distance. A scene camera that drew steps aside until [useSceneCamera], and the view's camera takes its
     * pose and lens over first — (0, 0, 1) is then where that camera stood, as iOS `useFreeCamera`. The
     * scene's next tick sees this camera (Look at Camera tags turn toward it).
     */
    public fun orbitFreeCamera(yaw: Float, pitch: Float, distanceScale: Float = 1f) {
        if (disposed) return
        bridge.activeCameraId?.let { id ->
            sceneCamera = id
            bridge.useFreeCamera()
        }
        bridge.setViewCameraOrbit(yaw.toDouble(), pitch.toDouble(), distanceScale.toDouble())
    }

    /** Back to the scene camera [orbitFreeCamera] stepped aside from; nothing when none did. */
    public fun useSceneCamera() {
        if (disposed) return
        val id = sceneCamera ?: return
        sceneCamera = null
        bridge.setActiveCamera(id)
    }

    /** Release Filament; the view is dead afterwards. A scene it showed stays usable and draws nothing. */
    public fun dispose() {
        if (disposed) return
        disposed = true
        stopLoop()
        frameFence?.let { bridge.engine.destroyFence(it) }
        frameFence = null
        // The scene may outlive the view (its owner keeps it): it must not reach this engine again.
        scene?.detach(bridge)
        uiHelper.detach()
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
