/**
 * Kotlin over Filament-Android — the applier a frame lands on: engine / view / scene / cameras, the scene
 * built from the places of the core's scene record, the place → entity map, node TRS / visibility / morph
 * weights, lights and cameras on their carrier places, IBL + skybox + background, the view's render settings
 * and the render loop.
 *
 * Public API:
 *   - `attachSurface` / `detachSurface` / `resize`, `loadPlaces(places)`, `render(frameTimeNanos)`, `dispose`.
 *   - Nodes: `entityForPlace`, `setNodeTRS`, `setNodeVisible`, `morphTargetCount`, `setMorphWeights`,
 *     `worldTransform` (checks).
 *   - Lights: `addLights`, `setLight*`; cameras: `addCameras`, `setActiveCamera`, `setCamera*`,
 *     `defaultCameraPose`, `drawnCameraPose`, `useFreeCamera`, `setViewCameraOrbit`; `setShadowingEnabled`.
 *   - Environment: `setIndirectLight`, `setIndirectLightIntensity` / `Rotation`, `setBackgroundColor`.
 *   - Render settings: `applyRenderSettings` / `restoreInitialRenderSettings`, then live `setSaturation` /
 *     `setFogColor` / `setBloomStrength` / `setPixelation`; `renderSettingsShown` / `saturationShown` / `fogColorShown` / `bloomShown` / `msaaShown` /
 *     `depthOfFieldShown` / `pixelationGridShown` / `pixelationTargets` (what the view draws now, for tests).
 *
 * Non-obvious constraints:
 *   - No gltfio asset: one bridge-owned entity per place, addressed by its number (one lookup); the meshes
 *     hang under the places (`FilamentGenerators`). `addLights` / `addCameras` build the Light (as
 *     gltfio builds a glTF node's: a spot is FOCUSED_SPOT, at the origin looking down -Z, falloff 10 without a
 *     range) and Camera components on a carrier place, whose pose stays the place's. A node's visibility
 *     stops at the places under it: each takes its own value from the core.
 *   - Host-side choices made here: the default camera is the core's framing (`framingFor`), asked
 *     at load and when the buffer's aspect changes — until `useFreeCamera` takes the active scene
 *     camera's pose and perspective lens over (as iOS), which then stays until the next load;
 *     a camera's aperture (f-number) also sets shutter and ISO so exposure stays that of f/16, 1/125 s,
 *     ISO 100; a light without `castShadows` casts unless it is a point light; on the emulator's GL the
 *     scene is drawn without shadows (any shadow map blackens the frame there) — decided by the backend the
 *     engine was made on, not by the device: the emulator's Vulkan draws them; the background is a solid
 *     grey skybox until the scene sets one.
 *   - Backend: Vulkan or OpenGL as asked; an `Engine.Builder` that throws falls back to OpenGL (a Vulkan
 *     driver failure inside Filament is a native abort, not catchable). Shaders compile synchronously,
 *     so the first frame an on-demand host asks for is complete, not black.
 *   - The live render leaves (saturation, fog colour, bloom strength, pixelation) outlive `applyRenderSettings`:
 *     the settings are applied again on the same view when the host's render knobs change, and what the
 *     frames wrote last stays on top of them.
 *   - Pixelation: `FilamentPixelation` draws after the scene view, configured before each
 *     frame from the switch, the size, the buffer and its pixels per dp (`resize`) in the core's grid
 *     (`pixelationGrid`); its targets go while it is off, with the surface and in `dispose`. Its
 *     material is the library's `tride-pixelate` package (`materialPackage`, set by the scene).
 *   - Suns: the engine is made with `enableMultipleDirectionalLights` — up to five directional
 *     lights light, the brightest casts the shadow, as on iOS and the web.
 *   - Colour: the engine turns on Filament's 1D colour grading LUT (byte-exact Standard); the
 *     tone mapper is the core's (`toneCurve`), and the colour buffer stays Filament's default (HIGH).
 */
package studio.tride.kit

import android.util.Log
import android.view.Surface
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.ToneMapper
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import studio.tride.core.TrideAntiAliasing
import studio.tride.core.TrideBloomOptions
import studio.tride.core.TrideDefaultFraming
import studio.tride.core.TrideDithering
import studio.tride.core.TrideDofFilter
import studio.tride.core.TridePixelationOptions
import studio.tride.core.TrideQualityLevel
import studio.tride.core.TrideRenderSettings
import studio.tride.core.TrideShadowType
import studio.tride.core.TrideToneMapper
import studio.tride.core.toneCurve as coreToneCurve
import studio.tride.kit.record.RecordPlace
import studio.tride.core.pixelationGrid as corePixelationGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "tride.bridge"
/** Filament's 1D colour grading LUT — Standard comes out byte for byte (the 3D LUT loses the top). */
private const val ONE_DIMENSIONAL_LUT = "engine.color_grading.use_1d_lut"

/** Up to five suns light the scene, the brightest casts the shadow (Filament's rule). */
private fun engineConfig() = Engine.Config().apply { enableMultipleDirectionalLights = true }

/** The f-number of a camera without an aperture — the core's (`CameraDefaultsTest` checks it). */
internal const val DEFAULT_CAMERA_APERTURE = 16.0
/** gltfio's falloff of a glTF light without a range (`AssetLoader::createLight`), metres. */
private const val GLTF_DEFAULT_FALLOFF = 10f
/**
 * The vertical fov of a perspective scene camera without `yfov` (glTF requires one): the core's, 60°, so a tap
 * ray and a Look-at-Camera tag see what is drawn (`CameraDefaultsTest` checks it).
 */
internal const val DEFAULT_CAMERA_YFOV = Math.PI / 3
/** The default camera's lens before the core framed the scene: 45°, as the core's framing and iOS. */
private const val DEFAULT_VIEW_YFOV = Math.PI / 4

/**
 * The half width and half height an orthographic camera draws on a view of [aspect] (width / height): glTF
 * xmag / ymag are the half-extents of the authored frame (2.0.1 §3.10.3.4: no crop, no non-uniform scaling), so
 * the view is filled at a uniform scale with that frame whole inside — half height max(ymag, xmag / aspect), half
 * width that times aspect. The web player's `orthoHalfExtents`, the Swift bridge and the core's tap ray take
 * the same rule (E6-O; `OrthoHalfExtentsTest`).
 */
internal fun orthoHalfExtents(xmag: Double, ymag: Double, aspect: Double): Pair<Double, Double> {
    val halfH = max(ymag, xmag / aspect)
    return Pair(halfH * aspect, halfH)
}

/** A manifest light for [FilamentSceneBridge.addLights] — Euler already resolved to a `-Z` direction. */
internal class LightSpec(
    val id: String,
    val type: String,
    val color: DoubleArray,
    val intensity: Double,
    val range: Double?,
    val innerConeAngle: Double?,
    val outerConeAngle: Double?,
    val castShadows: Boolean?,
    val position: DoubleArray?,
    val direction: DoubleArray,
    val carrierPlace: Int?,
)

/** A manifest camera for [FilamentSceneBridge.addCameras]; its pose lives on the carrier node. */
internal class CameraSpec(
    val id: String,
    val perspective: Boolean,
    val yfov: Double?,
    val xmag: Double?,
    val ymag: Double?,
    val znear: Double,
    val zfar: Double?,
    val focusDistance: Double?,
    val aperture: Double?,
    val carrierPlace: Int?,
    val focusTargetPlace: Int?,
)

/** The bridge's default camera, as the runtime's host camera for picking. */
internal class HostCameraPose(val position: DoubleArray, val rotation: DoubleArray, val yfov: Double)

internal class FilamentSceneBridge(
    preferredBackend: Engine.Backend,
) {
    companion object {
        private val initialised = lazy {
            Filament.init()
            Gltfio.init()
            Utils.init()
            true
        }
        /** The far plane of a camera without `zfar` (glTF: infinite): Filament needs a finite one, 1000 m. */
        private const val INFINITE_FAR = 1000.0
        private const val MAX_SATURATION = 2f
        /** Said once a process: the GPU's targets cannot hold half floats (R-DegradeNoticeWarnOnce). */
        @Volatile private var warnedNoFloatTargets = false
    }

    val engine: Engine
    val backend: Engine.Backend
    /**
     * The engine draws with the emulator's GL (ANGLE over SwiftShader, or the host-GPU translator), decided by the
     * backend the engine was made on (the OpenGL fallback included), not by the device: the emulator's Vulkan draws
     * shadow maps, its GL draws any frame with one black.
     */
    private val emulatorGl: Boolean
    private val renderer: Renderer
    /** The Filament scene — for the generators applier that lives beside the bridge. */
    val filamentScene: Scene
    /** Procedural generators (trunks, module batches, pulses) — attached by `TrideKitScene` with the runtime's assets. */
    var generators: FilamentGenerators? = null
        set(value) { field?.dispose(); field = value }
    private val view: View
    private val defaultCameraEntity: Int
    private val defaultCamera: Camera
    private val solidSkybox: Skybox
    private var swapChain: SwapChain? = null
    private var width = 1
    private var height = 1
    /** Buffer pixels per dp (the scene's point): the screen's density, or the host's pixel scale. */
    private var pixelsPerPoint = 1f
    private var disposed = false
    internal val isDisposed: Boolean get() = disposed

    /** The scene's diagnostics sink for loading problems (subject, message); logcat when none. */
    var onLoadProblem: ((String, String) -> Unit)? = null
    private fun loadProblem(subject: String, message: String) {
        onLoadProblem?.invoke(subject, message) ?: Log.w(TAG, message)
    }

    /** A frame is due: a tick moved the scene, a resize, an attached surface. */
    var needsRender = true

    private val materialProvider: UbershaderProvider
    /** gltfio's ubershader provider: the record's standard-form instances are made through it. */
    internal val ubershaders: UbershaderProvider get() = materialProvider

    private class NodeState(val entity: Int, var t: DoubleArray, var r: DoubleArray, var s: DoubleArray)
    private val nodeStates = ArrayList<NodeState>()
    private val indexToSlot = HashMap<Int, Int>()
    /** The entities [loadPlaces] made, one per place in order (bridge-owned, as are their components). */
    private val placeEntities = ArrayList<Int>()
    private val placeEntitySet = HashSet<Int>()

    private val lightEntities = HashMap<String, Int>()
    /** Lights this bridge created off any carrier place — `dispose` frees them. */
    private val builtLights = ArrayList<Int>()
    private val carrierOwnedLights = HashSet<String>()

    private class CameraRecord(
        val entity: Int, val perspective: Boolean, var yfov: Double, val xmag: Double, val ymag: Double,
        var znear: Double, var zfar: Double?, var focusDistance: Double, var aperture: Double, val focusTarget: Int?,
    )
    private val cameras = HashMap<String, CameraRecord>()
    var activeCameraId: String? = null
        private set

    private var indirectLight: IndirectLight? = null
    private var iblTexture: Texture? = null
    private var iblSkybox: Skybox? = null
    private var iblSkyboxTexture: Texture? = null

    /** The core's tone mapper for the scene's operator (`toneCurve`). */
    private var toneMapper: TrideToneMapper = TrideToneMapper.Aces
    private var exposure = 0f
    private var luminanceScaling = false
    private var gamutMapping = false
    private var saturation = 1f
    private var authoredSaturation = 1f
    /** The live saturation and fog colour the frames wrote last (`null` = authored). */
    private var liveSaturation: Double? = null
    private var liveFogColor: FloatArray? = null
    private var colorGrading: ColorGrading? = null
    private var fogOptions = View.FogOptions()
    /** The scene's authored bloom (`null` = not authored: Filament's, off) and the live strength on it. */
    private var authoredBloom: TrideBloomOptions? = null
    private var liveBloomStrength: Double? = null
    /** The scene's pixelation and the live switch / size over it (`null` = authored). */
    private var authoredPixelation = TridePixelationOptions(false, 8f)
    private var livePixelationEnabled: Boolean? = null
    private var livePixelationSize: Double? = null
    /** Built the first time a scene asks for pixelation; `null` before and after `dispose`. */
    private var pixelation: FilamentPixelation? = null
    private var pixelationUnavailable = false
    /** What the pass was last configured from — the grid is asked again only when one of them moved. */
    private var pixelationInputs: List<Any>? = null
    /** The View options this view was made with (`restoreInitialRenderSettings`); taken at the end of `init`. */
    private val initialRenderOptions: ViewRenderOptions

    /** `assets/tride/materials/<name>.filamat` bytes of the library (set by the scene that attaches). */
    var materialPackage: ((String) -> ByteArray?)? = null

    /** The core's default camera for a view aspect (`TrideScene.defaultFraming`); set by the scene before `loadPlaces`. */
    var framingFor: ((Double) -> TrideDefaultFraming)? = null
    /** The aspect the default camera was last framed for; 0 = not framed for this scene yet. */
    private var framedAspect = 0.0
    /** The last framing: an orbit turns about its centre, starting from its pose; null = not framed yet. */
    private var framed: Framing? = null
    /** `useFreeCamera` took a scene camera's pose over: `framed` is that pose, no reframing on resize. */
    private var poseTaken = false

    init {
        check(initialised.value)
        var created: Engine? = null
        var used = preferredBackend
        try {
            created = Engine.Builder().backend(preferredBackend).config(engineConfig()).feature("backend.disable_parallel_shader_compile", true).feature(ONE_DIMENSIONAL_LUT, true).build()
        } catch (e: Throwable) {
            Log.e(TAG, "Engine($preferredBackend) failed: ${e.message}")
        }
        if (created == null && preferredBackend != Engine.Backend.OPENGL) {
            Log.w(TAG, "Engine($preferredBackend) unavailable — falling back to OPENGL")
            used = Engine.Backend.OPENGL
            created = Engine.Builder().backend(Engine.Backend.OPENGL).config(engineConfig()).feature("backend.disable_parallel_shader_compile", true).feature(ONE_DIMENSIONAL_LUT, true).build()
        }
        engine = created ?: error("Filament Engine could not be created on any backend")
        backend = used
        emulatorGl = used == Engine.Backend.OPENGL && isEmulator()
        renderer = engine.createRenderer()
        filamentScene = engine.createScene()
        defaultCameraEntity = EntityManager.get().create()
        defaultCamera = engine.createCamera(defaultCameraEntity)
        defaultCamera.setExposure(16f, 1f / 125f, 100f)
        view = engine.createView()
        view.scene = filamentScene
        view.camera = defaultCamera
        view.isPostProcessingEnabled = true
        val shadowing = shadowingFor(true)
        view.setShadowingEnabled(shadowing)
        view.setScreenSpaceRefractionEnabled(true)
        initialRenderOptions = ViewRenderOptions(view, shadowing)
        // The scene's background: a solid skybox, so it goes through exposure and tone mapping.
        solidSkybox = Skybox.Builder().color(0.07f, 0.07f, 0.07f, 1f).build(engine)
        filamentScene.skybox = solidSkybox
        // Clear the colour buffer every frame (same grey):
        // Filament draws only the viewport into its 16-padded post-processing buffers, and the
        // padding left uncleared is undefined on tile-based GPUs — TAA / DOF / bloom / SSAO pull it
        // into the right / top edges as flickering blobs.
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clearColor = doubleArrayOf(0.07, 0.07, 0.07, 1.0)
            clear = true
            discard = true
        }

        materialProvider = UbershaderProvider(engine)
        resize(width, height)
        Log.i(TAG, "Filament ${BuildConfig.FILAMENT_VERSION} engine on ${engine.backend}")
    }

    // ---------------------------------------------------------------------------------------------
    // Surface / size / render loop
    // ---------------------------------------------------------------------------------------------

    fun attachSurface(surface: Surface, flags: Long) {
        if (disposed) return
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = engine.createSwapChain(surface, flags)
        needsRender = true
    }

    fun detachSurface() {
        if (disposed) return
        // The app went to the background (or the view off screen): the pixelation targets go with the
        // surface; the first frame on the next one builds them again.
        pixelation?.off()
        pixelationInputs = null
        releaseSwapChain()
    }

    private fun releaseSwapChain() {
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        engine.flushAndWait()
    }

    /** The buffer is [w] × [h] pixels, [pixelsPerPoint] of them a dp (pixelation's block side). */
    fun resize(w: Int, h: Int, pixelsPerPoint: Float = this.pixelsPerPoint) {
        if (disposed) return
        width = max(1, w)
        height = max(1, h)
        this.pixelsPerPoint = pixelsPerPoint
        view.viewport = Viewport(0, 0, width, height)
        val aspect = width.toDouble() / height.toDouble()
        // Another aspect frames the scene anew (a portrait view stands further back); the same one keeps an orbit,
        // and a pose taken over by `useFreeCamera` stays where it is with its lens on the new aspect.
        if (!poseTaken && aspect != framedAspect) frameDefaultCamera() else framed?.let { applyDefaultLens(it) }
        activeCameraId?.let { id -> cameras[id]?.let { applyProjection(it) } }
        needsRender = true
    }

    /**
     * GPU time of the frames Filament timed (`Renderer.getFrameInfoHistory`, at most its last 16): frame id →
     * milliseconds — frame-cost probes (the pixelation pass's cost on a phone).
     */
    internal fun gpuFrameMillis(): Map<Int, Double> {
        if (disposed) return emptyMap()
        val history = Array(renderer.maxFrameHistorySize) { Renderer.FrameInfo() }
        val n = renderer.getFrameInfoHistory(history)
        return (0 until n).map { history[it] }.filter { it.gpuFrameDuration > 0 }.associate { it.frameId to it.gpuFrameDuration / 1e6 }
    }

    /** One frame when a swap chain is attached; `false` when Filament skipped it (too soon after the last). */
    fun render(frameTimeNanos: Long): Boolean {
        val sc = swapChain ?: return false
        if (disposed) return false
        updateFocusTarget()
        syncPixelation()
        if (!renderer.beginFrame(sc, frameTimeNanos)) return false
        renderer.render(view)
        // The scene view drew into the pixelation pass's frame — its blocks onto the swap chain.
        pixelation?.render(renderer)
        renderer.endFrame()
        needsRender = false
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        releaseSwapChain()
        generators = null // the setter disposes it
        teardownIndirectLight()
        for (e in builtLights) {
            filamentScene.removeEntity(e)
            engine.lightManager.destroy(e)
            EntityManager.get().destroy(e)
        }
        builtLights.clear()
        // The places and the Light / Camera components built on carriers, after what hung under them.
        val tm = engine.transformManager
        for (e in placeEntities) {
            filamentScene.removeEntity(e)
            if (engine.lightManager.hasComponent(e)) engine.lightManager.destroy(e)
            if (engine.getCameraComponent(e) != null) engine.destroyCameraComponent(e)
            if (tm.hasComponent(e)) tm.destroy(e)
            EntityManager.get().destroy(e)
        }
        placeEntities.clear()
        placeEntitySet.clear()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        // The pixelation pass lets go of the scene view and frees its targets while the view is alive.
        pixelation?.destroy()
        pixelation = null
        // The View holds the colour grading and the skybox's scene: it goes first.
        engine.destroyView(view)
        colorGrading?.let { engine.destroyColorGrading(it) }
        colorGrading = null
        engine.destroySkybox(solidSkybox)
        engine.destroyScene(filamentScene)
        engine.destroyRenderer(renderer)
        engine.destroyCameraComponent(defaultCameraEntity)
        EntityManager.get().destroy(defaultCameraEntity)
        engine.destroy()
    }

    // ---------------------------------------------------------------------------------------------
    // The scene's places
    // ---------------------------------------------------------------------------------------------

    /**
     * The scene from the core's record — one entity per place, in order, under the
     * place its parent names, at the place's local pose (quaternion `[x, y, z, w]`). Place `i` is node index `i`.
     */
    fun loadPlaces(places: List<RecordPlace>) {
        check(!disposed) { "FilamentSceneBridge is disposed" }
        check(placeEntities.isEmpty()) { "re-load is not supported — create a new bridge" }
        nodeStates.clear(); indexToSlot.clear()
        val tm = engine.transformManager
        for ((i, place) in places.withIndex()) {
            val e = EntityManager.get().create()
            tm.create(e)
            placeEntities += e
            placeEntitySet += e
            indexToSlot[i] = nodeStates.size
            nodeStates += NodeState(e, place.translation.copyOf(), place.rotation.copyOf(), place.scale.copyOf())
        }
        for ((i, place) in places.withIndex()) {
            val ti = tm.getInstance(placeEntities[i])
            place.parent?.let { parent -> placeEntities.getOrNull(parent)?.let { tm.setParent(ti, tm.getInstance(it)) } }
            tm.setTransform(ti, LocalMatrix.of(place.translation, place.rotation, place.scale))
        }
        framedAspect = 0.0
        poseTaken = false
        frameDefaultCamera()
        needsRender = true
        Log.i(TAG, "scene built from ${places.size} places")
    }

    /** Pose and lens of the default camera from the core's framing at the buffer's aspect. */
    private fun frameDefaultCamera() {
        val ask = framingFor ?: return
        val aspect = width.toDouble() / height.toDouble()
        val next = try {
            DefaultFraming.of(ask(aspect))
        } catch (e: Exception) {
            loadProblem("defaultFraming", "the default camera could not be framed (${e.message}) — it keeps its last pose")
            return
        }
        framedAspect = aspect
        framed = next
        applyDefaultLens(next)
        poseDefaultCamera(next.pose)
        needsRender = true
    }

    /** The default camera's perspective lens at the buffer's aspect. */
    private fun applyDefaultLens(framing: Framing) {
        val aspect = width.toDouble() / height.toDouble()
        defaultCamera.setProjection(Math.toDegrees(framing.yfov), aspect, framing.near, framing.far, Camera.Fov.VERTICAL)
    }

    /** Place the framing camera and focus it where it looks (eye → the framing centre). */
    private fun poseDefaultCamera(pose: FramingPose) {
        val e = pose.eye
        val c = pose.center
        defaultCamera.lookAt(e[0], e[1], e[2], c[0], c[1], c[2], 0.0, 1.0, 0.0)
        defaultCamera.setFocusDistance(pose.focusDistance.toFloat())
    }

    /**
     * Orbit the default camera about the framed centre: (0, 0, 1) is the framed pose, eye above the centre
     * included (after `useFreeCamera`, the pose it took over); yaw / pitch radians from there, `distanceScale` ×
     * its eye → centre distance. Nothing while a scene camera is active.
     */
    fun setViewCameraOrbit(yaw: Double, pitch: Double, distanceScale: Double) {
        val framing = framed
        if (disposed || activeCameraId != null || framing == null || framing.orbitStart.distance <= 0) return
        poseDefaultCamera(DefaultFraming.orbit(framing, yaw, pitch, max(distanceScale, 0.05)))
        needsRender = true
    }

    /**
     * The host's free camera, starting where the active scene camera stands (iOS `useFreeCamera`): the default
     * camera takes its pose and perspective lens (an orthographic one keeps the default lens), the view switches
     * to it, and orbit (0, 0, 1) is that pose — about the point on its view axis nearest the framed centre (the
     * framing distance, at least 1, when that centre is behind it). No jump on the first drag. Without an active
     * scene camera nothing changes.
     */
    fun useFreeCamera() {
        if (disposed) return
        val id = activeCameraId ?: return
        val rec = cameras[id]
        val shown = rec?.let { engine.getCameraComponent(it.entity) }
        if (rec == null || shown == null) {
            setActiveCamera(null)
            return
        }
        // World model matrix, column-major: translation = column 3, up = +Y (column 1), view axis = -Z (column 2).
        val m = shown.getModelMatrix(FloatArray(16))
        val eye = DoubleArray(3) { m[12 + it].toDouble() }
        val forward = TrideMath.normalize3(DoubleArray(3) { -m[8 + it].toDouble() })
        val up = DoubleArray(3) { m[4 + it].toDouble() }
        val last = framed
        val centre = last?.pose?.center ?: DoubleArray(3)
        val along = (0 until 3).sumOf { (centre[it] - eye[it]) * forward[it] }
        val distance = if (along > 1e-3) along else max(last?.orbitStart?.distance ?: 0.0, 1.0)
        val taken = Framing(
            pose = FramingPose(eye, DoubleArray(3) { eye[it] + forward[it] * distance }),
            yfov = if (rec.perspective) rec.yfov else last?.yfov ?: DEFAULT_VIEW_YFOV,
            near = if (rec.perspective) rec.znear else last?.near ?: rec.znear,
            far = if (rec.perspective) rec.zfar?.takeIf { it > rec.znear } ?: INFINITE_FAR else last?.far ?: INFINITE_FAR,
        )
        framed = taken
        poseTaken = true
        defaultCamera.lookAt(
            eye[0], eye[1], eye[2],
            eye[0] + forward[0], eye[1] + forward[1], eye[2] + forward[2],
            up[0], up[1], up[2],
        )
        // Eye → the orbit centre it now turns about.
        defaultCamera.setFocusDistance(distance.toFloat())
        applyDefaultLens(taken)
        setActiveCamera(null)
    }

    fun defaultCameraPose(): HostCameraPose = cameraPose(defaultCamera)

    /** The camera the view draws through now — the active scene camera, or the default one. */
    fun drawnCameraPose(): HostCameraPose = cameraPose(view.camera ?: defaultCamera)

    private fun cameraPose(camera: Camera): HostCameraPose {
        check(!disposed) { "FilamentSceneBridge is disposed" }
        val m = camera.getModelMatrix(FloatArray(16))
        val (t, r, _) = TrideMath.decomposeTRS(m)
        val yfov = Math.toRadians(camera.getFieldOfViewInDegrees(Camera.Fov.VERTICAL))
        return HostCameraPose(t, r, yfov)
    }

    // ---------------------------------------------------------------------------------------------
    // Nodes
    // ---------------------------------------------------------------------------------------------

    fun entityForPlace(place: Int): Int? = indexToSlot[place]?.let { nodeStates[it].entity }

    /** The world matrix (column-major) of node index [index] as the TransformManager composes it; `null` without one. */
    fun worldTransform(index: Int): FloatArray? {
        if (disposed) return null
        val e = entityForPlace(index) ?: return null
        val tm = engine.transformManager
        if (!tm.hasComponent(e)) return null
        return tm.getWorldTransform(tm.getInstance(e), FloatArray(16))
    }

    /** Local TRS of a node; a `null` channel keeps the current one. */
    fun setNodeTRS(place: Int, t: DoubleArray?, r: DoubleArray?, s: DoubleArray?) {
        if (disposed) return
        val slot = indexToSlot[place] ?: return
        val state = nodeStates[slot]
        if (t != null) state.t = t
        if (r != null) state.r = r
        if (s != null) state.s = s
        val tm = engine.transformManager
        val ti = tm.getInstance(state.entity)
        if (ti == 0) return
        tm.setTransform(ti, LocalMatrix.of(state.t, state.r, state.s))
        needsRender = true
    }

    /** Visibility layer bit 0 on the node and every TransformManager descendant. */
    fun setNodeVisible(place: Int, visible: Boolean) {
        if (disposed) return
        val slot = indexToSlot[place] ?: return
        setLayerRecursive(nodeStates[slot].entity, visible)
        needsRender = true
    }

    private fun setLayerRecursive(entity: Int, visible: Boolean) {
        val rm = engine.renderableManager
        val tm = engine.transformManager
        if (rm.hasComponent(entity)) rm.setLayerMask(rm.getInstance(entity), 0x01, if (visible) 0x01 else 0x00)
        if (!tm.hasComponent(entity)) return
        val ti = tm.getInstance(entity)
        val count = tm.getChildCount(ti)
        if (count == 0) return
        val children = tm.getChildren(ti, IntArray(count))
        // A place under it is another node: it takes its own value.
        for (child in children) if (child !in placeEntitySet) setLayerRecursive(child, visible)
    }

    /** How many morph targets the file mesh drawn under [place] has; 0 when it has none (or is unknown). */
    fun morphTargetCount(place: Int): Int {
        if (disposed || !indexToSlot.containsKey(place)) return 0
        return generators?.morphTargetCountAt(place) ?: 0
    }

    fun setMorphWeights(place: Int, weights: FloatArray) {
        if (disposed || weights.isEmpty() || !indexToSlot.containsKey(place)) return
        if (generators?.setMorphWeightsAt(place, weights) == true) needsRender = true
    }

    // ---------------------------------------------------------------------------------------------
    // Lights
    // ---------------------------------------------------------------------------------------------

    private fun lightType(s: String): LightManager.Type = when (s) {
        "point" -> LightManager.Type.POINT
        "spot" -> LightManager.Type.SPOT
        else -> LightManager.Type.DIRECTIONAL
    }

    private fun isPunctual(t: LightManager.Type) =
        t == LightManager.Type.POINT || t == LightManager.Type.SPOT || t == LightManager.Type.FOCUSED_SPOT

    private fun castsShadows(spec: LightSpec, type: LightManager.Type): Boolean =
        spec.castShadows ?: (type != LightManager.Type.POINT)

    fun addLights(lights: List<LightSpec>) {
        if (disposed) return
        val lm = engine.lightManager
        val tm = engine.transformManager
        for (spec in lights) {
            val type = lightType(spec.type)
            var entity = 0
            var poseOnCarrier = false
            spec.carrierPlace?.let { idx ->
                val candidate = entityForPlace(idx)
                if (candidate != null && candidate in placeEntitySet && !lm.hasComponent(candidate)) {
                    // A carrier place: the light is built on it as gltfio builds a glTF node's; the pose
                    // is the place's, the place teardown frees it.
                    val built = if (type == LightManager.Type.SPOT) LightManager.Type.FOCUSED_SPOT else type
                    LightManager.Builder(built).direction(0f, 0f, -1f).falloff(GLTF_DEFAULT_FALLOFF)
                        .castShadows(castsShadows(spec, built)).build(engine, candidate)
                    entity = candidate; poseOnCarrier = true
                } else {
                    loadProblem(spec.id, "addLights: '${spec.id}' carrier place $idx is not a free place of the scene — the light keeps its authored pose")
                }
            }
            if (!poseOnCarrier) {
                entity = EntityManager.get().create()
                LightManager.Builder(type).castShadows(castsShadows(spec, type)).build(engine, entity)
                builtLights += entity
            }
            if (!lm.hasComponent(entity)) continue
            val li = lm.getInstance(entity)
            val actualType = lm.getType(li)
            lm.setColor(li, spec.color[0].toFloat(), spec.color[1].toFloat(), spec.color[2].toFloat())
            // Cone before intensity: FOCUSED_SPOT rescales its candela on every cone change.
            if (actualType == LightManager.Type.SPOT || actualType == LightManager.Type.FOCUSED_SPOT) {
                lm.setSpotLightCone(li, (spec.innerConeAngle ?: 0.0).toFloat(), (spec.outerConeAngle ?: (Math.PI / 4)).toFloat())
            }
            if (isPunctual(actualType)) lm.setIntensityCandela(li, spec.intensity.toFloat()) else lm.setIntensity(li, spec.intensity.toFloat())
            if (!poseOnCarrier) {
                val d = TrideMath.normalize3(spec.direction)
                lm.setDirection(li, d[0].toFloat(), d[1].toFloat(), d[2].toFloat())
                spec.position?.let { lm.setPosition(li, it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
            }
            spec.range?.takeIf { it > 0 }?.let { lm.setFalloff(li, it.toFloat()) }
            lm.setShadowCaster(li, castsShadows(spec, actualType))
            filamentScene.addEntity(entity)
            lightEntities[spec.id] = entity
            if (poseOnCarrier) carrierOwnedLights += spec.id else carrierOwnedLights -= spec.id
            if (tm.hasComponent(entity)) {
                val w = tm.getWorldTransform(tm.getInstance(entity), FloatArray(16))
                Log.i(TAG, "addLights: '${spec.id}' ${if (poseOnCarrier) "carrier-anchored" else "bridge-built"} world=(${w[12]}, ${w[13]}, ${w[14]})")
            }
        }
        needsRender = true
    }

    private fun lightInstance(id: String): Int? {
        val e = lightEntities[id] ?: return null
        val lm = engine.lightManager
        return if (lm.hasComponent(e)) lm.getInstance(e) else null
    }

    fun hasLight(id: String) = lightEntities.containsKey(id)

    /**
     * Where light [id] shines from and towards in the world, `[px, py, pz, dx, dy, dz]`: its own position and
     * direction through its entity's world transform (none: as set). For checks; `null` for no such light.
     */
    internal fun lightWorldPose(id: String): DoubleArray? {
        val li = lightInstance(id) ?: return null
        val e = lightEntities.getValue(id)
        val lm = engine.lightManager
        val p = lm.getPosition(li, FloatArray(3))
        val d = lm.getDirection(li, FloatArray(3))
        val tm = engine.transformManager
        val w = if (tm.hasComponent(e)) tm.getWorldTransform(tm.getInstance(e), FloatArray(16)) else floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val pos = DoubleArray(3) { r -> (w[r] * p[0] + w[4 + r] * p[1] + w[8 + r] * p[2] + w[12 + r]).toDouble() }
        val dir = TrideMath.normalize3(DoubleArray(3) { r -> (w[r] * d[0] + w[4 + r] * d[1] + w[8 + r] * d[2]).toDouble() })
        return pos + dir
    }
    fun lightPoseOwnedByCarrier(id: String) = id in carrierOwnedLights

    fun setLightColor(id: String, r: Double, g: Double, b: Double) {
        if (disposed) return
        val li = lightInstance(id) ?: return
        engine.lightManager.setColor(li, r.toFloat(), g.toFloat(), b.toFloat()); needsRender = true
    }

    fun setLightIntensity(id: String, intensity: Double) {
        if (disposed) return
        val li = lightInstance(id) ?: return
        val lm = engine.lightManager
        if (isPunctual(lm.getType(li))) lm.setIntensityCandela(li, intensity.toFloat()) else lm.setIntensity(li, intensity.toFloat())
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "light '$id' ${lm.getType(li)} intensity=$intensity → ${lm.getIntensity(li)}")
        needsRender = true
    }

    private var warnedShadows = false
    private var warnedDpcf = false

    /**
     * The emulator's GL (SwiftShader or the host-GPU translator) draws a frame with any shadow map — PCF
     * or VSM — pure black: there the scene renders without shadows, said once.
     */
    private fun shadowingFor(requested: Boolean): Boolean {
        if (!emulatorGl || !requested) return requested
        if (!warnedShadows) {
            warnedShadows = true
            Log.w(TAG, "emulator GL: shadow maps render the frame black here — drawing without shadows")
        }
        return false
    }

    /** A host knob: shadows off (on the emulator's GL they stay off whatever is asked). */
    fun setShadowingEnabled(enabled: Boolean) {
        if (disposed) return
        view.setShadowingEnabled(shadowingFor(enabled)); needsRender = true
    }

    fun setLightDirection(id: String, direction: DoubleArray) {
        if (disposed) return
        if (lightPoseOwnedByCarrier(id)) return
        val li = lightInstance(id) ?: return
        val d = TrideMath.normalize3(direction)
        engine.lightManager.setDirection(li, d[0].toFloat(), d[1].toFloat(), d[2].toFloat()); needsRender = true
    }

    fun setLightPosition(id: String, p: DoubleArray) {
        if (disposed) return
        if (lightPoseOwnedByCarrier(id)) return
        val li = lightInstance(id) ?: return
        engine.lightManager.setPosition(li, p[0].toFloat(), p[1].toFloat(), p[2].toFloat()); needsRender = true
    }

    fun setLightRange(id: String, range: Double) {
        if (disposed) return
        if (range <= 0) return
        val li = lightInstance(id) ?: return
        engine.lightManager.setFalloff(li, range.toFloat()); needsRender = true
    }

    fun setLightCone(id: String, inner: Double, outer: Double) {
        if (disposed) return
        val li = lightInstance(id) ?: return
        val lm = engine.lightManager
        val candela = lm.getIntensity(li)
        lm.setSpotLightCone(li, inner.toFloat(), outer.toFloat())
        if (lm.getType(li) == LightManager.Type.FOCUSED_SPOT) lm.setIntensityCandela(li, candela)
        needsRender = true
    }

    // ---------------------------------------------------------------------------------------------
    // Cameras
    // ---------------------------------------------------------------------------------------------

    private fun applyAperture(cam: Camera, fNumber: Double) {
        val n = fNumber.coerceIn(0.5, 64.0)
        val base = 1.0 / 125.0
        val k = (n * n) / (16.0 * 16.0)
        val shutter = (base * k).coerceIn(1.0 / 25000.0, 60.0)
        val iso = 100.0 * base * k / shutter
        cam.setExposure(n.toFloat(), shutter.toFloat(), iso.toFloat())
    }

    private fun applyProjection(rec: CameraRecord) {
        val cam = engine.getCameraComponent(rec.entity) ?: return
        val aspect = width.toDouble() / height.toDouble()
        val far = rec.zfar?.takeIf { it > rec.znear } ?: INFINITE_FAR
        if (rec.perspective) {
            cam.setProjection(Math.toDegrees(rec.yfov), aspect, rec.znear, far, Camera.Fov.VERTICAL)
        } else {
            // Resize re-applies it on the new aspect.
            val (halfW, halfH) = orthoHalfExtents(rec.xmag, rec.ymag, aspect)
            cam.setProjection(Camera.Projection.ORTHO, -halfW, halfW, -halfH, halfH, rec.znear, far)
        }
        // The view's aspect is the frame's: a camera scaled by 1 / the glTF aspect ratio (as gltfio scales its own)
        // would squeeze the picture — the scaling stays (1, 1).
        cam.setScaling(1.0, 1.0)
        applyAperture(cam, rec.aperture)
        cam.setFocusDistance(max(rec.focusDistance, 0.0).toFloat())
    }

    /** The active camera focuses on its focus target's depth along the view axis. */
    private fun updateFocusTarget() {
        val id = activeCameraId ?: return
        val rec = cameras[id] ?: return
        val target = rec.focusTarget ?: return
        val cam = engine.getCameraComponent(rec.entity) ?: return
        val tm = engine.transformManager
        if (!tm.hasComponent(rec.entity) || !tm.hasComponent(target)) return
        val eye = tm.getWorldTransform(tm.getInstance(rec.entity), FloatArray(16))
        val tw = tm.getWorldTransform(tm.getInstance(target), FloatArray(16))
        val fx = -eye[8]; val fy = -eye[9]; val fz = -eye[10]
        val len = sqrt(fx * fx + fy * fy + fz * fz).takeIf { it > 1e-9f } ?: return
        val depth = ((tw[12] - eye[12]) * fx + (tw[13] - eye[13]) * fy + (tw[14] - eye[14]) * fz) / len
        cam.setFocusDistance(max(depth.toDouble(), rec.znear).toFloat())
    }

    fun addCameras(specs: List<CameraSpec>) {
        if (disposed) return
        for (spec in specs) {
            val entity = spec.carrierPlace?.let { entityForPlace(it) }
            // A carrier place gets its Camera component here, as gltfio gives a glTF node its own.
            if (entity != null && entity in placeEntitySet && engine.getCameraComponent(entity) == null) engine.createCamera(entity)
            if (entity == null || engine.getCameraComponent(entity) == null) {
                loadProblem(spec.id, "addCameras: '${spec.id}' has no carrier place with a Camera component — skipped, the default camera stays")
                continue
            }
            val rec = CameraRecord(
                entity = entity,
                perspective = spec.perspective,
                yfov = spec.yfov?.takeIf { it > 0 } ?: DEFAULT_CAMERA_YFOV,
                xmag = spec.xmag?.takeIf { it > 0 } ?: 1.0,
                ymag = spec.ymag?.takeIf { it > 0 } ?: 1.0,
                znear = spec.znear.takeIf { it > 0 } ?: 0.1,
                zfar = spec.zfar?.takeIf { it > 0 },
                focusDistance = spec.focusDistance?.takeIf { it > 0 } ?: 0.0,
                aperture = spec.aperture?.takeIf { it > 0 } ?: DEFAULT_CAMERA_APERTURE,
                focusTarget = spec.focusTargetPlace?.let { entityForPlace(it) },
            )
            cameras[spec.id] = rec
            applyProjection(rec)
            val tm = engine.transformManager
            if (tm.hasComponent(entity)) {
                val w = tm.getWorldTransform(tm.getInstance(entity), FloatArray(16))
                Log.i(TAG, "addCameras: '${spec.id}' carrier-anchored world=(${w[12]}, ${w[13]}, ${w[14]}) yfov=${rec.yfov}")
            }
        }
    }

    /** The scaling of camera [id]'s component (`[x, y, …]`, checks); `null` without one. */
    internal fun cameraScaling(id: String): DoubleArray? =
        cameras[id]?.let { engine.getCameraComponent(it.entity) }?.getScaling(null)

    fun setActiveCamera(id: String?) {
        if (disposed) return
        if (!id.isNullOrEmpty()) {
            val rec = cameras[id]
            val cam = rec?.let { engine.getCameraComponent(it.entity) }
            if (rec != null && cam != null) {
                applyProjection(rec)
                view.camera = cam
                activeCameraId = id
                needsRender = true
                Log.i(TAG, "setActiveCamera: '$id'")
                return
            }
            Log.w(TAG, "setActiveCamera: '$id' not registered — default camera")
        }
        view.camera = defaultCamera
        activeCameraId = null
        needsRender = true
    }

    fun setCameraProjection(id: String, yfov: Double?, znear: Double?, zfar: Double?) {
        if (disposed) return
        val rec = cameras[id] ?: return
        yfov?.takeIf { it > 0 }?.let { rec.yfov = it }
        znear?.takeIf { it > 0 }?.let { rec.znear = it }
        if (zfar != null) rec.zfar = zfar.takeIf { it > 0 }
        applyProjection(rec); needsRender = true
    }

    fun setCameraFocusDistance(id: String, distance: Double) {
        if (disposed) return
        val rec = cameras[id] ?: return
        rec.focusDistance = max(distance, 0.0)
        applyProjection(rec); needsRender = true
    }

    fun setCameraAperture(id: String, fNumber: Double) {
        if (disposed) return
        if (fNumber <= 0) return
        val rec = cameras[id] ?: return
        rec.aperture = fNumber
        applyProjection(rec); needsRender = true
    }

    // ---------------------------------------------------------------------------------------------
    // Environment
    // ---------------------------------------------------------------------------------------------

    private fun teardownIndirectLight() {
        indirectLight?.let { filamentScene.indirectLight = null }
        iblSkybox?.let { filamentScene.skybox = solidSkybox; engine.destroySkybox(it) }
        iblSkybox = null
        iblSkyboxTexture?.let { engine.destroyTexture(it) }
        iblSkyboxTexture = null
        indirectLight?.let { engine.destroyIndirectLight(it) }
        indirectLight = null
        iblTexture?.let { engine.destroyTexture(it) }
        iblTexture = null
    }

    private fun direct(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }

    fun setIndirectLight(iblKtx: ByteArray, skyboxKtx: ByteArray?, intensity: Double, eulerXYZ: DoubleArray) {
        if (disposed || iblKtx.isEmpty()) return
        teardownIndirectLight()
        val ibl = KTX1Loader.createIndirectLight(engine, direct(iblKtx), KTX1Loader.Options())
        val light = ibl.indirectLight
        if (light == null) { loadProblem("environment", "setIndirectLight: KTX1Loader.createIndirectLight failed (${iblKtx.size} bytes)"); return }
        light.intensity = intensity.toFloat()
        light.setRotation(TrideMath.eulerXYZToMat3(eulerXYZ))
        indirectLight = light
        iblTexture = ibl.cubemap
        filamentScene.indirectLight = light
        if (skyboxKtx != null && skyboxKtx.isNotEmpty()) {
            val sky = KTX1Loader.createSkybox(engine, direct(skyboxKtx), KTX1Loader.Options())
            val skybox = sky.skybox
            if (skybox != null) {
                iblSkybox = skybox
                iblSkyboxTexture = sky.cubemap
                filamentScene.skybox = skybox
            } else loadProblem("environment", "setIndirectLight: createSkybox failed — IBL lit, solid background")
        }
        needsRender = true
        Log.i(TAG, "setIndirectLight: IBL applied (skybox=${iblSkybox != null}, intensity=$intensity)")
    }

    fun setIndirectLightIntensity(intensity: Double) {
        if (disposed) return
        indirectLight?.intensity = intensity.toFloat(); needsRender = true
    }

    fun setIndirectLightRotation(eulerXYZ: DoubleArray) {
        if (disposed) return
        indirectLight?.setRotation(TrideMath.eulerXYZToMat3(eulerXYZ)); needsRender = true
    }

    fun setBackgroundColor(r: Double, g: Double, b: Double) {
        if (disposed) return
        solidSkybox.setColor(r.toFloat(), g.toFloat(), b.toFloat(), 1f); needsRender = true
    }

    // ---------------------------------------------------------------------------------------------
    // Render settings
    // ---------------------------------------------------------------------------------------------

    private fun quality(q: TrideQualityLevel) = when (q) {
        TrideQualityLevel.LOW -> View.QualityLevel.LOW
        TrideQualityLevel.MEDIUM -> View.QualityLevel.MEDIUM
        TrideQualityLevel.HIGH -> View.QualityLevel.HIGH
        TrideQualityLevel.ULTRA -> View.QualityLevel.ULTRA
    }

    /** The Filament tone mapper of the core's [m]; `null` = Display Range, an enum only. */
    private fun filamentToneMapper(m: TrideToneMapper): ToneMapper? = when (m) {
        is TrideToneMapper.Linear -> ToneMapper.Linear()
        is TrideToneMapper.AcesLegacy -> ToneMapper.ACESLegacy()
        is TrideToneMapper.Aces -> ToneMapper.ACES()
        is TrideToneMapper.Filmic -> ToneMapper.Filmic()
        is TrideToneMapper.Generic -> ToneMapper.Generic(m.contrast, m.midGrayIn, m.midGrayOut, m.hdrMax)
        is TrideToneMapper.PbrNeutral -> ToneMapper.PBRNeutralToneMapper()
        is TrideToneMapper.AgxPunchy -> ToneMapper.Agx(ToneMapper.Agx.AgxLook.PUNCHY)
        is TrideToneMapper.DisplayRange -> null
    }

    private fun rebuildColorGrading() {
        if (disposed) return
        val builder = ColorGrading.Builder()
        // Display Range is the one tone mapper Filament's Java API has only as an enum.
        val mapper = filamentToneMapper(toneMapper)
        if (mapper != null) builder.toneMapper(mapper) else builder.toneMapping(ColorGrading.ToneMapping.DISPLAY_RANGE)
        val next = builder
            .exposure(exposure)
            .luminanceScaling(luminanceScaling)
            .gamutMapping(gamutMapping)
            .saturation(saturation)
            .build(engine)
        view.colorGrading = next
        colorGrading?.let { engine.destroyColorGrading(it) }
        colorGrading = next
        needsRender = true
    }

    /** The live saturation over the authored one; `null` = the authored saturation. */
    fun setSaturation(value: Double?) {
        if (disposed) return
        liveSaturation = value
        val s = value?.toFloat()?.coerceIn(0f, MAX_SATURATION) ?: authoredSaturation
        if (s == saturation && colorGrading != null) return
        saturation = s
        rebuildColorGrading()
    }

    /**
     * The live bloom strength over the authored bloom; `null` = the authored strength. Below 0 draws as 0;
     * seen only while the authored bloom is enabled (without authored settings Filament's bloom is off).
     */
    fun setBloomStrength(strength: Double?) {
        if (disposed || strength == liveBloomStrength) return
        liveBloomStrength = strength
        val authored = authoredBloom ?: return
        view.bloomOptions = bloomOptions(LiveRenderLeaves.liveBloom(authored, strength))
        needsRender = true
    }

    /** The bloom the view was given last — what a test reads back. */
    internal val bloomShown: View.BloomOptions get() = view.bloomOptions

    /** The live pixelation switch and size over the authored ones; `null` = the authored value. */
    fun setPixelation(enabled: Boolean?, size: Double?) {
        if (disposed || (enabled == livePixelationEnabled && size == livePixelationSize)) return
        livePixelationEnabled = enabled
        livePixelationSize = size
        needsRender = true
    }

    /** The switch and the size (points) the pass is asked for now: live over authored. */
    internal val pixelationShown: Pair<Boolean, Float>
        get() = (livePixelationEnabled ?: authoredPixelation.enabled) to (livePixelationSize?.toFloat() ?: authoredPixelation.size)

    /** The grid the pass was last configured with (each frame configures it first); `null` while off. */
    internal val pixelationGridShown get() = pixelation?.grid

    /** Render targets the pass holds now — 0 while it is off or released (leak checks). */
    internal val pixelationTargets: Int get() = pixelation?.targets ?: 0

    /**
     * The pass draws what the switch, the size and the buffer ask for now, in the core's grid; asked again
     * only when one of them moved. Runs before every frame (tests call it to configure without a surface).
     */
    internal fun syncPixelation() {
        if (disposed) return
        val (enabled, size) = pixelationShown
        val inputs = listOf(enabled, size, width, height, pixelsPerPoint)
        if (inputs == pixelationInputs) return
        pixelationInputs = inputs
        val grid = if (enabled) corePixelationGrid(width.toUInt(), height.toUInt(), size, pixelsPerPoint) else null
        if (grid == null || !grid.pixelates) {
            if (pixelation?.off() == true) needsRender = true
            return
        }
        val pass = pixelation ?: buildPixelation() ?: return
        if (pass.configure(grid, width, height)) needsRender = true
        Log.i(TAG, "pixelation: ${width}x$height size=$size ppp=$pixelsPerPoint -> block ${grid.block} shift ${grid.shiftX},${grid.shiftY} grid ${grid.columns}x${grid.rows}")
    }

    private fun buildPixelation(): FilamentPixelation? {
        if (pixelationUnavailable) return null
        val bytes = materialPackage?.invoke("tride-pixelate")
        val material = bytes?.takeIf { it.isNotEmpty() }?.let {
            runCatching { Material.Builder().payload(ByteBuffer.allocateDirect(it.size).order(ByteOrder.nativeOrder()).put(it).apply { rewind() }, it.size).build(engine) }
                .onFailure { e -> Log.w(TAG, "pixelation: the pass material failed to build: ${e.message}") }
                .getOrNull()
        }
        if (material == null) {
            pixelationUnavailable = true
            loadProblem("pixelation", "the pixelation material is missing from the library — the frame is drawn without blocks")
            return null
        }
        val floatTargets = Texture.isTextureFormatMipmappable(engine, Texture.InternalFormat.RGBA16F)
        if (!floatTargets && !warnedNoFloatTargets) {
            warnedNoFloatTargets = true
            Log.w(TAG, "pixelation: this GPU renders no RGBA16F targets — block means pass through 8-bit buffers (up to 1/255 off)")
        }
        return FilamentPixelation(engine, view, material, floatTargets).also { pixelation = it }
    }

    /** One bloom section for the authored and the live apply. */
    private fun bloomOptions(b: TrideBloomOptions) = View.BloomOptions().apply {
        enabled = b.enabled; strength = b.strength; resolution = b.resolution.toInt()
        levels = b.levels.toInt(); threshold = b.threshold; highlight = b.highlight
        lensFlare = b.lensFlare; quality = quality(b.quality)
    }

    fun setFogColor(r: Double, g: Double, b: Double) {
        if (disposed) return
        val colour = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
        liveFogColor = colour
        fogOptions.color = colour.copyOf()
        view.fogOptions = fogOptions
        needsRender = true
    }

    /** The settings the view was given last (`null` = none: what a new view draws) — what a test reads back. */
    internal var renderSettingsShown: TrideRenderSettings? = null
        private set

    /** The saturation and the fog colour the view draws with now — what a test reads back. */
    internal val saturationShown: Float get() = saturation
    internal val fogColorShown: FloatArray get() = fogOptions.color.copyOf()
    /** The MSAA and the depth of field the view was given last — what a test reads back. */
    internal val msaaShown: View.MultiSampleAntiAliasingOptions get() = view.multiSampleAntiAliasingOptions
    internal val depthOfFieldShown: View.DepthOfFieldOptions get() = view.depthOfFieldOptions

    /**
     * The runtime's normalised settings, projected 1:1 (no host defaults). Applied again on the same view,
     * the live leaves the frames wrote stay on top.
     */
    fun applyRenderSettings(s: TrideRenderSettings) {
        if (disposed) return
        renderSettingsShown = s
        view.antiAliasing = if (s.antiAliasing == TrideAntiAliasing.NONE) View.AntiAliasing.NONE else View.AntiAliasing.FXAA
        view.dithering = if (s.dithering == TrideDithering.NONE) View.Dithering.NONE else View.Dithering.TEMPORAL
        view.isPostProcessingEnabled = s.postProcessing
        view.setShadowingEnabled(shadowingFor(s.shadowingEnabled))
        view.setShadowType(when (s.shadowType) {
            TrideShadowType.PCF -> View.ShadowType.PCF
            TrideShadowType.VSM -> View.ShadowType.VSM
            TrideShadowType.DPCF -> {
                // Deprecated in Filament 1.76+: the engine draws PCSS; said once.
                if (!warnedDpcf) { warnedDpcf = true; Log.w(TAG, "shadowType DPCF is deprecated in Filament 1.76+ — drawn as PCSS") }
                View.ShadowType.DPCF
            }
            TrideShadowType.PCSS -> View.ShadowType.PCSS
            TrideShadowType.PCF_D -> View.ShadowType.PCFd
        })
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
            enabled = s.msaa.enabled; sampleCount = s.msaa.sampleCount.toInt(); customResolve = false
        }
        view.temporalAntiAliasingOptions = View.TemporalAntiAliasingOptions().apply {
            enabled = s.taa.enabled; feedback = s.taa.feedback; filterWidth = s.taa.filterWidth; sharpness = s.taa.sharpness
        }
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
            enabled = s.ssao.enabled; radius = s.ssao.radius; power = s.ssao.power; bias = s.ssao.bias
            intensity = s.ssao.intensity; resolution = s.ssao.resolution; quality = quality(s.ssao.quality)
        }
        authoredBloom = s.bloom
        view.bloomOptions = bloomOptions(LiveRenderLeaves.liveBloom(s.bloom, liveBloomStrength))
        // Drawn by the pass from the next frame (`syncPixelation`).
        authoredPixelation = s.pixelation
        needsRender = true
        fogOptions = View.FogOptions().apply {
            enabled = s.fog.enabled; distance = s.fog.distance; maximumOpacity = s.fog.maximumOpacity
            height = s.fog.height; heightFalloff = s.fog.heightFalloff; density = s.fog.density
            fogColorFromIbl = s.fog.fogColorFromIbl
            color = liveFogColor?.copyOf() ?: floatArrayOf(s.fog.colorR, s.fog.colorG, s.fog.colorB)
        }
        view.fogOptions = fogOptions
        view.vignetteOptions = View.VignetteOptions().apply {
            enabled = s.vignette.enabled; midPoint = s.vignette.midPoint; roundness = s.vignette.roundness
            feather = s.vignette.feather
            if (s.vignette.color.size >= 3) color = floatArrayOf(s.vignette.color[0], s.vignette.color[1], s.vignette.color[2], 1f)
        }
        view.dynamicResolutionOptions = View.DynamicResolutionOptions().apply {
            enabled = s.dynamicResolution.enabled; minScale = s.dynamicResolution.minScale
            maxScale = s.dynamicResolution.maxScale; quality = quality(s.dynamicResolution.quality)
        }
        view.depthOfFieldOptions = View.DepthOfFieldOptions().apply {
            enabled = s.depthOfField.enabled; cocScale = s.depthOfField.cocScale; cocAspectRatio = s.depthOfField.cocAspectRatio
            maxApertureDiameter = s.depthOfField.maxApertureDiameter
            filter = if (s.depthOfField.filter == TrideDofFilter.NONE) View.DepthOfFieldOptions.Filter.NONE else View.DepthOfFieldOptions.Filter.MEDIAN
            nativeResolution = s.depthOfField.nativeResolution
            maxForegroundCOC = minOf(s.depthOfField.maxForegroundCoc.toInt(), 32)
            maxBackgroundCOC = minOf(s.depthOfField.maxBackgroundCoc.toInt(), 32)
            foregroundRingCount = minOf(s.depthOfField.foregroundRingCount.toInt(), 17)
            backgroundRingCount = minOf(s.depthOfField.backgroundRingCount.toInt(), 17)
            fastGatherRingCount = minOf(s.depthOfField.fastGatherRingCount.toInt(), 17)
        }
        view.screenSpaceReflectionsOptions = View.ScreenSpaceReflectionsOptions().apply {
            enabled = s.screenSpaceReflections.enabled; thickness = s.screenSpaceReflections.thickness
            bias = s.screenSpaceReflections.bias; maxDistance = s.screenSpaceReflections.maxDistance
            stride = s.screenSpaceReflections.stride
        }
        view.vsmShadowOptions = View.VsmShadowOptions().apply {
            anisotropy = s.vsmShadowOptions.anisotropy.toInt(); mipmapping = s.vsmShadowOptions.mipmapping
            msaaSamples = s.vsmShadowOptions.msaaSamples.toInt(); highPrecision = s.vsmShadowOptions.highPrecision
            minVarianceScale = s.vsmShadowOptions.minVarianceScale; lightBleedReduction = s.vsmShadowOptions.lightBleedReduction
        }
        view.softShadowOptions = View.SoftShadowOptions().apply {
            penumbraScale = s.softShadowOptions.penumbraScale; penumbraRatioScale = s.softShadowOptions.penumbraRatioScale
        }
        toneMapper = coreToneCurve(s.toneMapping.`operator`)
        exposure = s.toneMapping.exposure
        luminanceScaling = s.toneMapping.luminanceScaling
        gamutMapping = s.toneMapping.gamutMapping
        authoredSaturation = s.toneMapping.saturation.coerceIn(0f, MAX_SATURATION)
        saturation = liveSaturation?.toFloat()?.coerceIn(0f, MAX_SATURATION) ?: authoredSaturation
        rebuildColorGrading()
        Log.i(TAG, "applyRenderSettings: aa=${s.antiAliasing} toneMapping=${s.toneMapping.`operator`} exposure=$exposure bloom=${s.bloom.enabled} ssao=${s.ssao.enabled} dof=${s.depthOfField.enabled}")
    }

    /** Whether the view draws a colour grading of the bridge's own (`null` = Filament's default) — what a test reads back. */
    internal val colorGradingShown: Boolean get() = colorGrading != null

    /**
     * Back to the render settings this view was made with — what it draws for a scene that sets none: every
     * option `applyRenderSettings` sets as the view had it (`initialRenderOptions`), no colour grading of the
     * bridge's own, the authored leaves as `init` has them. The live leaves the frames wrote are written again
     * through their setters, as a full frame writes them on a new view; so a scene without render settings
     * whose knobs are cleared draws as a new view of it.
     */
    fun restoreInitialRenderSettings() {
        if (disposed) return
        initialRenderOptions.applyTo(view)
        renderSettingsShown = null
        authoredBloom = null
        authoredPixelation = TridePixelationOptions(false, 8f)
        toneMapper = TrideToneMapper.Aces
        exposure = 0f
        luminanceScaling = false
        gamutMapping = false
        saturation = 1f
        authoredSaturation = 1f
        view.colorGrading = null
        colorGrading?.let { engine.destroyColorGrading(it) }
        colorGrading = null
        fogOptions = View.FogOptions()
        val saturationWritten = liveSaturation
        val fogWritten = liveFogColor
        liveSaturation = null
        liveFogColor = null
        saturationWritten?.let { setSaturation(it) }
        fogWritten?.let { setFogColor(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }
        needsRender = true
        Log.i(TAG, "restoreInitialRenderSettings: the view's own options again")
    }
}

/**
 * Every View option `applyRenderSettings` sets, as a view had it when made (Filament Java keeps each options
 * block on the View; the bridge replaces blocks, never edits one it handed over). Java has no getter for the
 * shadow type — Filament's default, PCF, which `init` leaves — and only a package-private one for shadowing:
 * the value `init` set is passed in.
 */
private class ViewRenderOptions(view: View, private val shadowing: Boolean) {
    private val antiAliasing = view.antiAliasing
    private val dithering = view.dithering
    private val postProcessing = view.isPostProcessingEnabled
    private val msaa = view.multiSampleAntiAliasingOptions
    private val taa = view.temporalAntiAliasingOptions
    private val ssao = view.ambientOcclusionOptions
    private val bloom = view.bloomOptions
    private val fog = view.fogOptions
    private val vignette = view.vignetteOptions
    private val dynamicResolution = view.dynamicResolutionOptions
    private val depthOfField = view.depthOfFieldOptions
    private val ssr = view.screenSpaceReflectionsOptions
    private val vsm = view.vsmShadowOptions
    private val softShadows = view.softShadowOptions

    fun applyTo(view: View) {
        view.antiAliasing = antiAliasing
        view.dithering = dithering
        view.isPostProcessingEnabled = postProcessing
        view.setShadowingEnabled(shadowing)
        view.setShadowType(View.ShadowType.PCF)
        view.multiSampleAntiAliasingOptions = msaa
        view.temporalAntiAliasingOptions = taa
        view.ambientOcclusionOptions = ssao
        view.bloomOptions = bloom
        view.fogOptions = fog
        view.vignetteOptions = vignette
        view.dynamicResolutionOptions = dynamicResolution
        view.depthOfFieldOptions = depthOfField
        view.screenSpaceReflectionsOptions = ssr
        view.vsmShadowOptions = vsm
        view.softShadowOptions = softShadows
    }
}
