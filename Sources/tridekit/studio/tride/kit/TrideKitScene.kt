/**
 * `TrideKitScene` — a `.trd` scene for an Android host: the runtime (`studio.tride.core.TrideScene`) ticks,
 * `FrameDispatcher` routes the frame to a `FilamentSceneBridge`; the host writes facts and hears events /
 * state changes AFTER the frame reached Filament.
 *
 * Public API:
 *   - `TrideKitScene(trdBytes)`, `buildInfo`, `play` / `pause` / `isPlaying` / `reset` / `tickOnce(dtMs)`, `advance` → `TrideKitAdvance`, `close`.
 *   - `setBinding` / `getBinding` / `trigger` / `readProperty` / `availableBindings` / `validateBindings`
 *     (`TrideValue`), `inputsJson()` / `setInputs(json, strict)` → `TrideKitInputsReport` (the scene's inputs for a
 *     settings screen and a checked batch write; kind `STRING` is text), `currentState`, `layerCount` / `layerId` / `layerName` / `currentState(layerId)`,
 *     `setViewport` / `pointer` / `pick`, `bindInput` / `unbindInput` / `boundInputs` (the app
 *     binds a source it measures — its scrolling container's speed or the scene's pass — to a number input of the
 *     scene), `scroll` / `dragAxes` (the app's scrolling container, which the app reports), `onEvent` /
 *     `onStateChange` / `onDiagnostic`.
 *   - Sound: `attachAudioTrack` / `attachAudioTap` / `detachAudio` / `audioInputs`, timed before every tick.
 *   - Pictures: `setImage(name, bytes | bitmap | uri)` → `TrideImageResult`, `imageLoading`, `clearImage`.
 *   - Render knobs: `setRenderOverrides(TrideRenderOverrides?)` / `renderOverrides` (MSAA, bloom, shadows, depth
 *     of field over the scene's render settings; `null` = none), `sceneRenderSettings` (what the scene sets).
 *
 *   - `partialDrawNotice` — why the scene is drawn only in part (`null` = whole); the same text goes once to
 *     [onDiagnostic] (`RUNTIME`, subject `partialDraw`).
 *   - Checks (opt-in `TrideKitChecks`): `nodeWorlds()` — the world matrix of every node, light and camera the view
 *     draws, by node uid and light / camera id; `bonePaletteCheck()` — the core's palettes against gltfio's rule.
 *
 * Non-obvious constraints:
 *   - The manifest is never parsed here. A view builds the scene from the core's scene record (`RecordScene`):
 *     one place per node row and per light / camera carrier, the rest pose as a quaternion; the core says each
 *     node's visibility to the renderer (`/node/{uid}/drawVisible`), and every node with a datablock draws the
 *     core's mesh: a file mesh as gltfio draws its primitives, in the record's materials (one ubershader
 *     instance per slug and key), a procedural one in the core's look. A scene the core hands no record of is
 *     not built: the attach throws `IllegalStateException("scene not built: …")`.
 *   - Lights / cameras hold their pose between changed-only frames (`PoseHold`); one on a carrier node is
 *     posed on the carrier's TRS (the node path meshes use), its component at identity.
 *   - Generators (`listGenerators`) and the record's datablock nodes are listed at attach and drawn from the
 *     runtime's meshes; a node a deformer bends (`listDeformedNodes`, `/deformed/`) draws the runtime's bent
 *     mesh the same way; a trunk is pulled again only when its `trunkEpoch` moved. Module
 *     instances' rows (`generatorModuleColors`) come with their transforms where the node material samples them.
 *   - The runtime holds the only copy of the archive (`assetBytes`) and hands out only changed values: a new
 *     bridge (`attach`) asks for a full frame before its first tick.
 *   - Node materials load at attach, before any mesh; a package missing or foreign to this engine
 *     (`materialPackageInfo`) leaves the built-in look; the tick that lays an app picture rebinds photo slots.
 *     Only uniforms whose lanes moved go to the bridge (`SentUniforms`); a node-graph input never moves the
 *     look, so a tick writing only those does not pull it.
 *   - The render knobs are laid over the scene's settings by the runtime (`renderSettingsWith`), never here; no
 *     knob = exactly the scene's settings. A change is applied on the same view at once (no reload, the scene
 *     plays on), the live render leaves stay on top, and an on-demand view draws one frame. Cleared over a
 *     scene without render settings, the view draws as a new one (`restoreInitialRenderSettings`).
 *   - A tick after a move of the bridge's own camera sends it with `setViewport` first (Look at Camera tags turn toward it on that tick).
 *   - Threads: the constructor and the runtime-only calls (`setBinding` / `getBinding` / `trigger` / `setInputs` /
 *     `inputsJson` / `readProperty` / `currentState` / layers / `bindInput` / `availableBindings` / `validateBindings` /
 *     `dragAxes`) may run on any thread (the runtime locks itself); every call that steps, draws, touches, subscribes or closes is the
 *     main thread's and throws `IllegalStateException` elsewhere — the frame on the main thread would otherwise
 *     meet the half-written dispatch state far from the call (`checkMain`).
 */
package studio.tride.kit

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.google.android.filament.Engine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import studio.tride.core.EvalDiagnostic
import studio.tride.core.TrideCameraDescriptor
import studio.tride.core.TrideBonePalette
import studio.tride.core.TrideDatablockMesh
import studio.tride.core.TrideBoundInput
import studio.tride.core.TrideDragAxes
import studio.tride.core.TrideGeneratedGeometry
import studio.tride.core.TrideHostCamera
import studio.tride.core.TrideInputMapping
import studio.tride.core.TrideInputSource
import studio.tride.core.TrideLayerStateChangeListener
import studio.tride.core.TrideMaterialGraphProblem
import studio.tride.core.TrideLightDescriptor
import studio.tride.core.TrideMaterialParams
import studio.tride.core.TrideModuleBindingDescriptor
import studio.tride.core.TrideMutation
import studio.tride.core.TridePulseLook
import studio.tride.core.TrideOutgoingEventListener
import studio.tride.core.TrideRenderOverrides
import studio.tride.core.TrideRenderSettings
import studio.tride.core.TrideParametricNode
import studio.tride.core.TridePointerPhase
import studio.tride.core.TrideScene
import studio.tride.core.TrideSceneRecord
import studio.tride.core.TrideStateChange
import studio.tride.core.TrideValue
import studio.tride.core.eulerToQuat
import studio.tride.core.materialObjectSeed
import studio.tride.core.materialPackageInfo
import studio.tride.kit.record.RecordScene
import kotlin.math.min

private const val TAG = "tride.kit"
private const val MAX_DT_MS = 33.4f

/** The runtime's binding kinds as the kit names them. */
private val BINDING_KINDS = mapOf(
    "number" to TrideKitBindingKind.NUMBER, "bool" to TrideKitBindingKind.BOOL, "enum" to TrideKitBindingKind.ENUM,
    "vec3" to TrideKitBindingKind.VEC3, "quat" to TrideKitBindingKind.QUAT, "color" to TrideKitBindingKind.COLOR,
    "trigger" to TrideKitBindingKind.TRIGGER, "image" to TrideKitBindingKind.IMAGE, "string" to TrideKitBindingKind.STRING,
)

/** A scene event (one of the scene's declared events) as the host hears it, after the frame. */
public data class TrideKitEvent(val name: String, val payload: Map<String, TrideValue>)

/** One state-machine transition with its layer. */
public data class TrideKitStateChange(
    val layerIndex: Int,
    val layerId: String,
    val from: String,
    val to: String,
    val transition: String,
)

/**
 * What kind of value a binding holds. A kind this kit does not know yet is [OTHER] (and said once). An
 * [IMAGE] binding takes the app's pictures through `setImage`; `getBinding` reads its status
 * (`"empty"` / `"loading"` / `"loaded"` / `"failed"`), `setBinding` refuses it. A [STRING] binding is free
 * text: `setBinding(name, TrideValue.String(…))` or a [TrideKitScene.setInputs] batch; the scene checks its
 * length and line breaks on every write.
 */
public enum class TrideKitBindingKind { NUMBER, BOOL, ENUM, VEC3, QUAT, COLOR, TRIGGER, IMAGE, STRING, OTHER }

/** An input a batch refused: the scene never cuts or clamps a value. [code]: `kind`, `variant`, `range`, `length`, `newline`, `computed`, `bound`, `image`, `trigger`. */
public data class TrideKitInputRefusal(val name: String, val code: String, val message: String)

/**
 * What [TrideKitScene.setInputs] did: [applied] inputs written together for the next tick, [rejected] ones (each
 * keeps its previous value), [unknown] names the scene has no input of, and [untouched] inputs a batch can write
 * that this one did not mention (or, in a strict batch that stopped, did not write).
 */
public data class TrideKitInputsReport(
    val applied: List<String>,
    val rejected: List<TrideKitInputRefusal>,
    val unknown: List<String>,
    val untouched: List<String>,
)

/** How a [TrideKitScene.setImage] call ended. */
public sealed interface TrideImageResult {
    /**
     * The picture is in place as [revision] at [width] × [height] px (after the 2048 px fit and the EXIF
     * turn); the next tick lays it over every image field its input is bound to.
     */
    public data class Loaded(val revision: Long, val width: Int, val height: Int) : TrideImageResult

    /** The picture could not be read or decoded: the scene sees `failed` and shows its placeholder. */
    public data class Failed(val reason: String) : TrideImageResult

    /** A newer `setImage` / `imageLoading` / `clearImage` of the same input overtook this call (or the scene closed): nothing changed. */
    public data object Superseded : TrideImageResult
}

/**
 * A binding the scene declares. [options] are an enum's values (empty otherwise); a [computed] binding
 * is derived by the scene from others — read it with `getBinding`, writing it is refused.
 */
public data class TrideKitBinding(
    val name: String,
    val kind: TrideKitBindingKind,
    val options: List<String>,
    val computed: Boolean,
)

/** A node under a screen point; [instanceKey] / [instanceIndex] name a clone of an instanced node. */
public data class TrideKitHit(val node: String, val instanceKey: String?, val instanceIndex: Int?, val distance: Float)

/** Which Tride runtime this app carries: release, commit (empty in a local build), `.trd` format it reads. */
public data class TrideKitBuildInfo(val version: String, val commit: String, val formatVersion: String)

/** Where a [TrideKitDiagnostic] comes from. */
public enum class TrideKitDiagnosticKind {
    /** An expression or binding the scene could not evaluate this tick; subject = its address. */
    EVALUATION,

    /** Something the scene needs and the device could not load (environment, material, mesh, texture); subject = it. */
    LOAD,

    /** A change the scene made that this kit cannot show; subject = its address. */
    POINTER,

    /** A runtime call that failed; subject = the call. */
    RUNTIME,
}

/** A problem the scene met. Each (kind, subject, message) is reported once, after the frame it happened in. */
public data class TrideKitDiagnostic(
    val kind: TrideKitDiagnosticKind,
    val subject: String,
    val message: String,
    /**
     * The problem's name where every host shares one: `material.package-missing`, `material.package-stale`,
     * `material.package-version`, `material.package-refused` (a node material drawn plainly); `null` otherwise.
     */
    val code: String? = null,
)

/** Where a host script ([TrideKitScene.advance]) ended. */
public sealed interface TrideKitAdvance {
    /** Ticks the runtime ran. */
    public val ticks: Long

    /** Every step ran. */
    public data class Completed(override val ticks: Long) : TrideKitAdvance

    /** The script stopped at [step] (zero-based) because of [reason], e.g. a wait that ran out of time. */
    public data class Stopped(val step: Int, val reason: String, override val ticks: Long) : TrideKitAdvance
}

public class MissingBindingsException(public val missing: List<String>, public val declared: List<String>) :
    Exception("scene does not declare bindings: ${missing.joinToString()} (declared: ${declared.joinToString()})")

public fun interface Subscription { public fun cancel() }

public class TrideKitScene(trdBytes: ByteArray) : AutoCloseable {
    public companion object {
        /** The input a scene hears when the app names none: its probes are `audio.music.<probe>`. */
        public const val DEFAULT_AUDIO_INPUT: String = "music"

        /**
         * Which Tride runtime this app carries: its release version, the commit it was built from (empty
         * in a local build) and the `.trd` format it reads. No scene needed — the runtime library answers.
         */
        @JvmStatic
        public val buildInfo: TrideKitBuildInfo
            get() = native { studio.tride.core.buildInfo() }.let { TrideKitBuildInfo(it.version, it.commit, it.formatVersion) }

        /** The ABIs `libtride_ffi.so` is built for. */
        private val SUPPORTED_ABIS = listOf("arm64-v8a", "x86_64")

        /**
         * The core's material problems a scene tells (`materialGraphProblems`: stale / version records, a
         * package the archive does not hold): once per material and code, in the core's words.
         */
        internal fun materialProblemsToTell(problems: List<TrideMaterialGraphProblem>): List<TrideMaterialGraphProblem> =
            problems.distinctBy { it.slug to it.code }

        /** The core's code of a package that did not load on this device. */
        internal const val PACKAGE_REFUSED: String = "material.package-refused"

        /**
         * A package the core accepted and this device refused (its version or backend, or `Material.Builder`):
         * drawn plainly, told as [PACKAGE_REFUSED] in the core's words. The text is the core's
         * `MaterialPackageProblem::Refused` (`material_graphs.rs`), which no FFI call hands out for a package
         * the core itself accepted; `MaterialPackageWordsTest` holds the two equal.
         */
        internal fun hostRefused(slug: String, family: String): TrideMaterialGraphProblem = TrideMaterialGraphProblem(
            slug, family, PACKAGE_REFUSED,
            "This scene's material '$slug' could not be loaded on this device and is drawn plainly. Open the scene in the Tride editor of this version and save it again.",
        )

        private fun loadCore(trdBytes: ByteArray): TrideScene = native { TrideScene.load(trdBytes) }

        /**
         * The first call into the runtime loads `libtride_ffi.so` through JNA. On a device whose ABI the
         * runtime is not built for (a 32-bit phone, an app without `abiFilters`) that is a `LinkageError`
         * — say so instead of letting a bare `UnsatisfiedLinkError` reach the app.
         */
        private inline fun <T> native(call: () -> T): T = try {
            call()
        } catch (e: LinkageError) {
            throw IllegalStateException(
                "Tride runtime is not available for this device (ABIs ${Build.SUPPORTED_ABIS.orEmpty().joinToString()}): " +
                    "TrideKit ships ${SUPPORTED_ABIS.joinToString()} — set ndk.abiFilters to these in the app module",
                e,
            )
        }
    }

    private val core: TrideScene = loadCore(trdBytes).also { it.setChangedOnly(true) }
    private var bridge: FilamentSceneBridge? = null
        // A bridge disposed behind the view's back (a host calling `dispose` itself) is no bridge.
        get() = field?.takeUnless { it.isDisposed }
    /** The bridge this scene draws through now (`null` while no live view shows it). */
    internal val attachedBridge: FilamentSceneBridge? get() = bridge

    // Signals of the running tick, drained after dispatch.
    private sealed interface Signal {
        class Event(val event: TrideKitEvent) : Signal
        class State(val change: TrideKitStateChange) : Signal
        class Problem(val diagnostic: TrideKitDiagnostic) : Signal
    }
    private val inbox = ArrayList<Signal>()
    private val eventHandlers = LinkedHashMap<Long, (TrideKitEvent) -> Unit>()
    private val stateHandlers = LinkedHashMap<Long, (TrideKitStateChange) -> Unit>()
    private val diagnosticHandlers = LinkedHashMap<Long, (TrideKitDiagnostic) -> Unit>()
    private var nextSubscription = 1L

    private var pendingAdvance: MutableList<TrideMutation> = ArrayList()
    private val mainHandler = Handler(Looper.getMainLooper())
    /** The sound the scene hears; its sources feed the runtime from their own threads. */
    private val audio = SceneAudioInputs(coreAudioRuntime(core)) { input, key, message ->
        val kind = if (key == "decode" || key == "format") TrideKitDiagnosticKind.LOAD else TrideKitDiagnosticKind.RUNTIME
        mainHandler.post { if (!closed) reportNow(kind, "audio.$input", message) }
    }
    /** The app's decoded pictures by `(input, revision)` — the one store slot textures are built from. */
    private val pictures = HostPictureStore()
    private val imageRequests = HostImageRequests()
    /** Inputs whose kept pictures may change at the next tick (a new picture, a failure, a clear). */
    private val picturesToPrune = LinkedHashSet<String>()
    @Volatile private var closed = false
    /** The view asks before it forwards a touch or a size: a closed scene on screen draws nothing more. */
    internal val isClosed: Boolean get() = closed
    public var isPlaying: Boolean = false
        private set

    /**
     * Why the scene is drawn only in part — a `requires` Android does not draw yet, a material without values the
     * record does not carry yet, the reasons joined by `; `; `null` = drawn whole. Set when a view builds the scene. The same text went to [onDiagnostic] (`partialDraw`).
     */
    public var partialDrawNotice: String? = null
        private set

    /** The scene as built from the core's record (the table nodes, lights and cameras go through); `null` before a view built it. */
    private var recordScene: RecordScene? = null
    /** Each row's visibility to the renderer, as the core said it last. */
    private val drawVisible = HashMap<Int, Boolean>()
    private var lastFrameNanos = 0L

    private var viewportWidth = 0f
    private var viewportHeight = 0f
    /** The size the view last reported, in dp (tests). */
    internal val viewportSize: Pair<Float, Float> get() = viewportWidth to viewportHeight
    /** What the last `setViewport` carried: a tick sends it again only when the view or its camera moved. */
    private var sentViewport: Triple<Float, Float, TrideHostCamera?>? = null

    // ---- dispatcher state (bound at attach) ----
    private class Baseline(val position: DoubleArray, val rotation: DoubleArray, val scale: DoubleArray)
    private val nodeIdToIndex = HashMap<String, Int>()
    private val baselines = HashMap<String, Baseline>()
    private val carrierIndexById = HashMap<String, Int>()
    private val lightDescriptors = HashMap<String, TrideLightDescriptor>()
    private val cameraDescriptors = HashMap<String, TrideCameraDescriptor>()
    private var environmentBaseEuler = doubleArrayOf(0.0, 0.0, 0.0)
    private var environmentBaseBackground = doubleArrayOf(0.07, 0.07, 0.07)
    /** The host's render knobs (a copy: the record's fields are mutable); `null` = none. */
    private var knobs: TrideRenderOverrides? = null
    /** Saturation, fog colour and bloom strength as the frames wrote them over the authored settings. */
    private var liveRender = LiveRenderLeaves(doubleArrayOf(0.0, 0.0, 0.0))
    /** The pose every light and camera holds between the runtime's changed-only frames. */
    private var poses = PoseHold(emptyMap())

    // ---- generators ----
    private val generatorHostIndex = HashMap<String, Int>()
    /** Generator id → the uid of the node that hosts it (a node material's object). */
    private val generatorHostUid = HashMap<String, String>()
    private val generatorMaterialSlug = HashMap<String, String>()
    private val generatorMaterialParams = HashMap<String, TrideMaterialParams>()
    private val generatorPulseLook = HashMap<String, TridePulseLook>()
    private val generatorModules = HashMap<String, List<TrideModuleBindingDescriptor>>()
    private val registeredModuleMeshes = HashMap<String, String>()
    /**
     * Datablock uid → the draws its module mesh makes: one part per draw group of an Extrude cloned
     * as a module — a derived uid, the group's slice of the indices, its own look — or the mesh itself. Every
     * per-datablock cache here is keyed by the PART's uid.
     */
    private val moduleParts = HashMap<String, List<ModulePart>>()
    /** Parametric nodes of the scene, listed once at attach. */
    private var parametricNodes: List<TrideParametricNode> = emptyList()
    /** The `trunkEpoch` each generator's uploaded trunk came from. */
    private val uploadedTrunkEpoch = HashMap<String, Long>()
    private val moduleMaterialParams = HashMap<String, TrideMaterialParams?>()
    private val moduleMaterialSlug = HashMap<String, String>()
    private val paletteSent = HashMap<String, TrideMaterialParams>()
    /** Node material slug → the families its packages loaded for on the bridge. */
    private val materialGraphFamilies = HashMap<String, MutableList<String>>()
    /** The uniforms last handed to the bridge per node material and family. */
    private val sentUniforms = SentUniforms()
    private val assetBytesCache = HashMap<String, ByteArray?>()
    private fun cachedAssetBytes(uri: String): ByteArray? = assetBytesCache.getOrPut(uri) { core.assetBytes(uri) }

    private val resolver = object : NodeResolver {
        override fun resolveManifestNode(nodeId: String): String? = if (baselines.containsKey(nodeId)) nodeId else null
        override fun gltfIndexForNode(nodeId: String): Int? = if (baselines.containsKey(nodeId)) nodeIdToIndex[nodeId] else null
    }
    private val morphWeights = MorphWeightHolder()
    private val accumulator = OverrideAccumulator(object : BaselineLookup {
        override fun position(nodeId: String) = baselines[nodeId]?.position ?: doubleArrayOf(0.0, 0.0, 0.0)
        override fun scale(nodeId: String) = baselines[nodeId]?.scale ?: doubleArrayOf(1.0, 1.0, 1.0)
        override fun rotationQuat(nodeId: String) = baselines[nodeId]?.rotation ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    })

    init {
        core.subscribeEvents(object : TrideOutgoingEventListener {
            override fun onOutgoingEvent(name: String, payload: Map<String, TrideValue>) {
                inbox += Signal.Event(TrideKitEvent(name, payload))
            }
        })
        core.subscribeLayerStateChanges(object : TrideLayerStateChangeListener {
            override fun onLayerStateChange(change: TrideStateChange) {
                inbox += Signal.State(TrideKitStateChange(change.layerIndex.toInt(), change.layerId, change.from, change.to, change.transition))
            }
        })
        Log.i(TAG, "loaded TrideScene from ${trdBytes.size} bytes")
    }

    // ---------------------------------------------------------------------------------------------
    // Host API
    // ---------------------------------------------------------------------------------------------

    private fun checkOpen() = check(!closed) { "TrideKitScene is closed" }

    /**
     * A call that steps the scene, draws, touches, subscribes or closes: the main thread's. The frame runs on it
     * (`FilamentSurfaceView`'s Choreographer), and the dispatch state, the handlers and Filament are unguarded —
     * from another thread such a call crashed the next frame with a `ConcurrentModificationException`.
     */
    private fun checkMain(): Unit = check(Looper.myLooper() === Looper.getMainLooper()) {
        "TrideKitScene: this call belongs to the main thread, it ran on '${Thread.currentThread().name}' — " +
            "switch with withContext(Dispatchers.Main); only the runtime calls (setBinding, trigger, setInputs, …) take any thread"
    }

    private fun checkOpenOnMain() { checkMain(); checkOpen() }

    /**
     * The bindings the scene declares. Any thread: it only asks the runtime. A kind this TrideKit does not know
     * comes back as `OTHER`, and a `LOAD` diagnostic says so on the main thread, after the call.
     */
    public fun availableBindings(): List<TrideKitBinding> {
        checkOpen()
        val unknown = ArrayList<Pair<String, String>>()
        val bindings = core.listBindings().map { d ->
            val kind = BINDING_KINDS[d.kind] ?: TrideKitBindingKind.OTHER.also { unknown += d.name to d.kind }
            TrideKitBinding(d.name, kind, d.options, d.computed != null)
        }
        if (unknown.isNotEmpty()) mainHandler.post {
            if (closed) return@post
            for ((name, kind) in unknown) {
                report(TrideKitDiagnosticKind.LOAD, name, "binding '$name' has a kind this TrideKit does not know: '$kind'")
            }
            deliverSignals()
        }
        return bindings
    }

    /** Throws [MissingBindingsException] when the scene does not declare every name in [required]. */
    public fun validateBindings(required: List<String>) {
        checkOpen()
        val declared = core.listBindings().map { it.name }.toSet()
        val missing = required.filter { it !in declared }
        if (missing.isNotEmpty()) throw MissingBindingsException(missing, declared.sorted())
    }

    /**
     * Every input of the scene with its limits and labels, and the values [setInputs] takes, as JSON:
     * `{"inputs":[{name, kind, value, default, options?, min?, max?, maxLength?, multiline?, computed?, doc?,
     * settings?, missingGlyphs?}], "values":{name: value}}` — the bytes every host gets. An input with
     * `settings` is the one a settings screen shows; `kind` `"string"` is text.
     */
    public fun inputsJson(): String { checkOpen(); return core.inputsJson() }

    /**
     * Write a batch of input values at once. [json] is an object of input names and values
     * (`{"greeting":"Hi","amount":7500}`); the scene checks them all and the good ones land together for the next
     * tick. A value outside its limits is refused with a code, never cut or clamped (the input keeps its text),
     * and listed in the report. With [strict] nothing is written unless every value is good. A picture for an
     * image input goes through `setImage`, not here. Throws `TrideException.InvalidBinding` when [json] is not a
     * JSON object (nothing is written).
     */
    public fun setInputs(json: String, strict: Boolean = false): TrideKitInputsReport {
        checkOpen()
        val report = JSONObject(core.setInputsJson(json, strict))
        fun names(key: String): List<String> = report.optJSONArray(key).let { a -> List(a?.length() ?: 0) { a!!.getString(it) } }
        val rejected = report.optJSONArray("rejected").let { a ->
            List(a?.length() ?: 0) {
                val r = a!!.getJSONObject(it)
                TrideKitInputRefusal(r.getString("name"), r.getString("code"), r.getString("message"))
            }
        }
        return TrideKitInputsReport(names("applied"), rejected, names("unknown"), names("untouched"))
    }

    /** Write a binding; the next tick reads it. Throws `TrideException` for an undeclared name or a wrong type. */
    public fun setBinding(name: String, value: TrideValue) { checkOpen(); core.setBinding(name, value) }

    /** A binding's current value (a computed one as the scene derived it). Throws `TrideException` for an undeclared name. */
    public fun getBinding(name: String): TrideValue { checkOpen(); return core.getBinding(name) }

    /** Fire a trigger; the next tick reads it. Throws `TrideException` for an undeclared name. */
    public fun trigger(name: String) { checkOpen(); core.trigger(name) }

    /** A glTF-document or scene-domain property as JSON text (`/nodes/0/translation`, `/scene/active_camera`), or `null`. */
    public fun readProperty(pointer: String): String? { checkOpen(); return core.readProperty(pointer) }

    /** The current state of the first state-machine layer. */
    public fun currentState(): String { checkOpen(); return core.currentState() }

    /**
     * The render settings the scene sets, before any knob (`null` = the scene sets none: Filament's defaults).
     * Any thread: it only asks the runtime.
     */
    public val sceneRenderSettings: TrideRenderSettings?
        get() { checkOpen(); return core.renderSettings() }

    /** The host's render knobs now (a copy); `null` = none, the scene's settings as they are. */
    public val renderOverrides: TrideRenderOverrides? get() = knobs?.copy()

    /**
     * Lays the host's render knobs over the scene's render settings: MSAA on or off and its samples (2, 4 or
     * 8), bloom / shadows / depth of field off (`true` keeps the scene's — a knob never turns on what the scene
     * has off). A field left `null` keeps the scene's value; `null` clears every knob. Applied at once on the
     * view showing the scene, without a reload: the scene plays on and its animated bloom strength, saturation,
     * fog colour and pixelation stay as they are. A bad sample count keeps the scene's, with one warning.
     */
    public fun setRenderOverrides(overrides: TrideRenderOverrides?) {
        checkOpenOnMain()
        val next = overrides?.copy()
        if (next == knobs) return
        knobs = next
        bridge?.let { applyRenderSettings(it, fresh = false) }
    }

    /** How many state-machine layers the scene has. */
    public val layerCount: Int get() { checkOpen(); return core.layerCount().toInt() }

    /** The id of layer [index] (`0 until layerCount`), or `null` out of range. */
    public fun layerId(index: Int): String? { checkOpen(); return if (index < 0) null else core.layerId(index.toUInt()) }

    /** The display name of layer [index], or `null` out of range. */
    public fun layerName(index: Int): String? { checkOpen(); return if (index < 0) null else core.layerName(index.toUInt()) }

    /** The current state of the layer with id [layerId], or `null` when the scene has no such layer. */
    public fun currentState(layerId: String): String? {
        checkOpen()
        val n = core.layerCount().toInt()
        val index = (0 until n).firstOrNull { core.layerId(it.toUInt()) == layerId } ?: return null
        return core.layerCurrentState(index.toUInt())
    }

    /** Every scene event, after the frame it happened in. */
    public fun onEvent(handler: (TrideKitEvent) -> Unit): Subscription {
        checkOpenOnMain()
        val id = nextSubscription++
        eventHandlers[id] = handler
        return Subscription { checkMain(); eventHandlers.remove(id) }
    }

    /** Every state-machine transition, after the frame it happened in. */
    public fun onStateChange(handler: (TrideKitStateChange) -> Unit): Subscription {
        checkOpenOnMain()
        val id = nextSubscription++
        stateHandlers[id] = handler
        return Subscription { checkMain(); stateHandlers.remove(id) }
    }

    /** Problems the scene meets (load, evaluation, changes this kit cannot show, failed calls); logcat has them too. */
    public fun onDiagnostic(handler: (TrideKitDiagnostic) -> Unit): Subscription {
        checkOpenOnMain()
        val id = nextSubscription++
        diagnosticHandlers[id] = handler
        return Subscription { checkMain(); diagnosticHandlers.remove(id) }
    }

    /**
     * The view's size in dp (px ÷ `displayMetrics.density`) — the space [pointer] and [pick] speak, the points
     * of iOS and the browser's CSS px: a tap is a press that travels at most 10 of them. `FilamentSurfaceView`
     * reports it itself.
     */
    public fun setViewport(width: Float, height: Float) {
        checkOpenOnMain()
        viewportWidth = width
        viewportHeight = height
        pushViewport()
    }

    /** A finger in dp from the view's top-left corner; `FilamentSurfaceView` forwards its own touches. */
    public fun pointer(phase: TridePointerPhase, x: Float, y: Float) {
        checkOpenOnMain()
        pushViewport()
        try { core.pointer(phase, x, y) } catch (e: Exception) { reportNow(TrideKitDiagnosticKind.RUNTIME, "pointer", e.message ?: e.toString()) }
    }

    /**
     * Bind [source] to the scene's number input [input]: from the next tick the runtime writes it from the source,
     * mapped by [mapping] (`null`: the speed 0…2 screens / s, the pass 0…1, onto 0…1), and `setBinding` on it is refused
     * until [unbindInput]. Binding again replaces; `reset()` keeps it, a new scene starts with none. A refusal (an
     * input the scene lacks, not a number, computed or driven by the scene's pointer interactions, an empty or
     * non-finite range) throws.
     */
    public fun bindInput(input: String, source: TrideInputSource, mapping: TrideInputMapping? = null) {
        checkOpen()
        core.bindInput(input, source, mapping)
    }

    /** The app writes [input] again; it keeps its value. `true` when it was bound. */
    public fun unbindInput(input: String): Boolean {
        checkOpen()
        return core.unbindInput(input)
    }

    /** The app's bindings, in the order they were made. */
    public fun boundInputs(): List<TrideBoundInput> {
        checkOpen()
        return core.boundInputs()
    }

    /**
     * Your scrolling container: how far it is scrolled ([offset]), its visible height ([extent]) and where the scene
     * sits in it ([sceneTop], [sceneHeight]), in one unit (px or dp). Call it whenever any of them changes while a
     * scroll source is bound ([bindInput]); the bound inputs read the speed and the scene's pass on the next tick. A
     * value that is not finite, an [extent] of 0 or less or a negative [sceneHeight] is refused with a diagnostic; the
     * last report stays.
     */
    public fun scroll(offset: Double, extent: Double, sceneTop: Double, sceneHeight: Double) {
        checkOpenOnMain()
        try { core.scroll(offset, extent, sceneTop, sceneHeight) } catch (e: Exception) { reportNow(TrideKitDiagnosticKind.RUNTIME, "scroll", e.message ?: e.toString()) }
    }

    /** The directions the scene's drag interactions claim — `y`: it wants vertical swipes a scroll would take. */
    public fun dragAxes(): TrideDragAxes {
        checkOpen()
        return core.dragAxes()
    }

    /** The nodes under a point (dp from the view's top-left) that a scene listener targets (empty when no listener does). */
    public fun pick(x: Float, y: Float): List<TrideKitHit> {
        checkOpenOnMain()
        pushViewport()
        return core.pick(x, y).map { TrideKitHit(it.node, it.instanceKey, it.instanceIndex?.toInt(), it.distance) }
    }

    /**
     * The scene hears a track the app plays: [uri] is decoded by the platform and handed to the scene
     * whole (seeking is free), and played through Media3 ExoPlayer — control it with the returned track.
     * Its probes are `audio.<input>.<probe>`; a track already on [input] is replaced. Released with
     * [detachAudio] or [close]. Throws `IllegalArgumentException` when [input] is not a scene input name.
     */
    @MainThread
    public fun attachAudioTrack(context: Context, uri: Uri, input: String = DEFAULT_AUDIO_INPUT, loop: Boolean = false): TrideKitAudioTrack {
        checkOpenOnMain()
        requireAudioInput(input)
        val track = TrideKitAudioTrack(context.applicationContext, uri, loop)
        audio.attach(input, track.source, owned = true)
        return track
    }

    /**
     * The scene hears what an ExoPlayer plays — a file or an endless stream such as internet radio —
     * through [tap], which the player's audio sink carries (`TrideKitAudioTap.renderersFactory`). The app
     * keeps the player; [detachAudio] stops listening. Throws `IllegalArgumentException` for a bad [input].
     */
    @MainThread
    public fun attachAudioTap(tap: TrideKitAudioTap, input: String = DEFAULT_AUDIO_INPUT) {
        checkOpenOnMain()
        audio.attach(input, tap.source, owned = false)
    }

    /** The scene stops hearing [input] (its probes read 0); a track attached here stops and is released. */
    @MainThread
    public fun detachAudio(input: String) {
        checkOpenOnMain()
        audio.detach(input)
    }

    /** The inputs the scene hears now. */
    public val audioInputs: List<String>
        @MainThread get() { checkMain(); return audio.names() }

    /** The audible time and gain [input] got before the last tick (a test replays them). */
    internal fun audioHeard(input: String): Heard? = audio.lastHeard(input)

    /**
     * Hand the scene a picture for image input [name] as encoded [bytes] (JPEG, PNG, WebP; HEIF from
     * Android 8). The scene sees `loading` at once — a picture it shows already stays until this one lands —
     * and TrideKit decodes it off the main thread: at most 2048 px on the long side, turned upright by its
     * EXIF orientation, sRGB. The next tick lays it over every image field the input is bound to (a paused
     * scene shows it after its next `tickOnce`). The scene hears `image.<name>` through [onEvent]; a picture
     * that cannot be decoded is [TrideImageResult.Failed] (the scene sees `failed`, its placeholder shows,
     * [onDiagnostic] hears it once). The latest call per input wins. Throws `TrideException.InvalidImage`
     * when [name] is not an image input. Runs its scene calls on the main thread whatever the caller's.
     */
    public suspend fun setImage(name: String, bytes: ByteArray): TrideImageResult = loadImage(name) { HostImageDecoder.decode(bytes) }

    /**
     * [setImage] from the app's own [bitmap], already upright (any config; a HARDWARE one is copied):
     * scaled to fit 2048 px. The bitmap is read off the main thread — do not recycle it before this returns.
     */
    public suspend fun setImage(name: String, bitmap: Bitmap): TrideImageResult = loadImage(name) { HostImageDecoder.fromBitmap(bitmap) }

    /**
     * [setImage] from [uri] — `content://` (the photo picker, MediaStore), `file://`, `android.resource://` —
     * read and decoded off the main thread; a uri that cannot be opened is [TrideImageResult.Failed].
     */
    public suspend fun setImage(name: String, context: Context, uri: Uri): TrideImageResult {
        val resolver = context.applicationContext.contentResolver
        return loadImage(name) {
            val bytes = try {
                resolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                throw HostImageDecodeException("could not read $uri: ${e.message ?: e}", e)
            } ?: throw HostImageDecodeException("could not open $uri")
            HostImageDecoder.decode(bytes)
        }
    }

    /**
     * The app started getting a picture for image input [name] itself (a download): the scene sees
     * `loading` (a picture it shows stays). Hand the bytes to [setImage] when they arrive; a call of
     * [setImage] still under way for [name] is overtaken ([TrideImageResult.Superseded]). Throws
     * `TrideException.InvalidImage` when [name] is not an image input.
     */
    @MainThread
    public fun imageLoading(name: String) {
        checkOpenOnMain()
        core.imageLoading(name)
        imageRequests.begin(name)
    }

    /**
     * Image input [name] back to no picture (`empty`): the next tick shows the placeholders, a [setImage]
     * still under way is overtaken, the pixels are let go. Throws `TrideException.InvalidImage` when [name]
     * is not an image input.
     */
    @MainThread
    public fun clearImage(name: String) {
        checkOpenOnMain()
        core.clearImage(name)
        imageRequests.begin(name)
        picturesToPrune += name
    }

    /** The revisions of [name] the host keeps pixels of (tests). */
    internal fun keptPictures(name: String): Set<Long> = pictures.revisions(name)

    private suspend fun loadImage(name: String, decode: () -> HostPixels): TrideImageResult = withContext(Dispatchers.Main.immediate) {
        checkOpen()
        core.imageLoading(name)
        val token = imageRequests.begin(name)
        val decoded = try {
            withContext(Dispatchers.Default) {
                try {
                    Result.success(decode())
                } catch (e: HostImageDecodeException) {
                    Result.failure(e)
                } catch (e: OutOfMemoryError) {
                    Result.failure(HostImageDecodeException("the picture is too large to decode", e))
                }
            }
        } catch (e: CancellationException) {
            // The caller gave up: the scene must not wait on `loading` for ever.
            if (!closed && imageRequests.isLatest(name, token)) failImage(name, "the app cancelled the picture")
            throw e
        }
        if (closed || !imageRequests.isLatest(name, token)) return@withContext TrideImageResult.Superseded
        val pixels = decoded.getOrElse { return@withContext failImage(name, it.message ?: "the picture could not be decoded") }
        val revision = try {
            core.setImage(name, pixels.width.toUInt(), pixels.height.toUInt()).toLong()
        } catch (e: Exception) {
            return@withContext failImage(name, e.message ?: e.toString())
        }
        pictures.put(name, revision, pixels)
        picturesToPrune += name
        TrideImageResult.Loaded(revision, pixels.width, pixels.height)
    }

    /** `failed` with [reason] — the placeholder shows — said once to logcat and [onDiagnostic]. */
    private fun failImage(name: String, reason: String): TrideImageResult {
        core.imageFailed(name, reason)
        picturesToPrune += name
        reportNow(TrideKitDiagnosticKind.LOAD, "image.$name", "image input '$name': $reason — the placeholder shows")
        return TrideImageResult.Failed(reason)
    }

    /**
     * After a tick laid or took off pictures: each touched input keeps only the picture it shows (or loads a
     * replacement over) and what a slot texture still binds.
     */
    private fun prunePictures() {
        if (picturesToPrune.isEmpty()) return
        val gen = bridge?.generators
        for (name in picturesToPrune) {
            val input = core.imageInput(name)
            val keep = HostPictureStore.keptRevisions(
                input?.status, input?.revision?.toLong() ?: 0L, input?.width?.toInt() ?: 0, gen?.boundRevisions(name) ?: emptySet(),
            )
            val freed = pictures.retain(name, keep)
            if (freed > 0) debugLog { "image input '$name': $freed picture(s) let go, kept $keep" }
        }
        picturesToPrune.clear()
    }

    /** The view size and, while the bridge draws through its own camera, that camera; [onlyIfMoved]: the tick's check. */
    private fun pushViewport(onlyIfMoved: Boolean = false) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        var camera: TrideHostCamera? = null
        val b = bridge
        if (b != null && b.activeCameraId == null) {
            val pose = b.defaultCameraPose()
            camera = TrideHostCamera(
                pose.position[0].toFloat(), pose.position[1].toFloat(), pose.position[2].toFloat(),
                pose.rotation[0].toFloat(), pose.rotation[1].toFloat(), pose.rotation[2].toFloat(), pose.rotation[3].toFloat(),
                pose.yfov.toFloat(),
            )
        }
        val sent = Triple(viewportWidth, viewportHeight, camera)
        if (onlyIfMoved && sent == sentViewport) return
        try {
            core.setViewport(viewportWidth, viewportHeight, camera)
            sentViewport = sent
        } catch (e: Exception) { reportNow(TrideKitDiagnosticKind.RUNTIME, "setViewport", e.message ?: e.toString()) }
    }

    /** Tick with the view's frames; the runtime is told its loop resumes (the scroll speed is measured afresh). */
    public fun play() {
        checkOpenOnMain()
        if (isPlaying) return
        // The loop stood (or never ran): the scrolling container's move since then is no speed.
        try {
            core.resume()
        } catch (e: Exception) { reportNow(TrideKitDiagnosticKind.RUNTIME, "resume", e.message ?: e.toString()) }
        isPlaying = true
        lastFrameNanos = 0L
    }

    /** Stop ticking; the last frame stays. Allowed after [close]. */
    public fun pause() {
        checkMain()
        isPlaying = false
        lastFrameNanos = 0L
    }

    /** The view's frame: a tick while playing (dt from the frame timestamps, clamped to two 60 Hz frames). */
    internal fun onFrame(frameTimeNanos: Long, maxDtMs: Float = MAX_DT_MS) {
        if (!isPlaying || closed) return
        val dtMs = if (lastFrameNanos == 0L) 1000f / 60f else min((frameTimeNanos - lastFrameNanos) / 1_000_000f, maxDtMs)
        lastFrameNanos = frameTimeNanos
        tickAndDispatch(dtMs)
    }

    /**
     * The view's frame loop stood (its window was hidden: the app in the background) and runs again: the next
     * frame's step starts afresh, and a playing scene's runtime hears its loop resume, as after [play].
     */
    internal fun loopResumed() {
        lastFrameNanos = 0L
        if (!isPlaying) return
        try {
            core.resume()
        } catch (e: Exception) { reportNow(TrideKitDiagnosticKind.RUNTIME, "resume", e.message ?: e.toString()) }
    }

    /** One tick of [dtMs] milliseconds, shown as a frame (playing or not). */
    public fun tickOnce(dtMs: Float) { checkOpenOnMain(); tickAndDispatch(dtMs) }

    /**
     * Back to the scene's start: every state machine to its initial state (entered again), every binding
     * to its declared default, every node to its authored pose — then one zero tick, shown as a frame.
     * A scene's own trigger named `reset` is a different thing: fire it with `trigger("reset")`.
     */
    public fun reset() {
        checkOpenOnMain()
        core.reset()
        // Every light and camera back to its authored pose; the whole frame then moves what is driven.
        val moved = poses.reset()
        bridge?.let { b -> for (key in moved) writePose(key, b) }
        // The runtime forgot what it handed out: this frame is whole.
        tickAndDispatch(0f, complete = true)
    }

    /**
     * Run a host script (the JSON steps of `Docs/API.md`) and show its final state as one frame. Text that
     * is not a host script is `Stopped(0, "not a host script: …")`; a runtime failure throws `TrideException`.
     */
    public fun advance(scriptJson: String): TrideKitAdvance {
        checkOpenOnMain()
        audio.hear()
        pushViewport(onlyIfMoved = true)
        val result = core.advance(scriptJson)
        publishDiagnostics(result.tick.diagnostics)
        val b = bridge
        if (b != null) {
            if (result.tick.mutations.isNotEmpty()) { dispatch(result.tick.mutations, b); b.needsRender = true }
            applyBonePalettes(result.tick.bonePalettes, b)
        } else {
            // Without a view the palettes go: `attach` asks the core for its whole frame, every palette again.
            pendingAdvance += result.tick.mutations
        }
        prunePictures()
        deliverSignals()
        debugLog { "advance: ticks=${result.ticks} mutations=${result.tick.mutations.size}" }
        val ticks = result.ticks.toLong()
        val step = result.stoppedStep ?: return TrideKitAdvance.Completed(ticks)
        return TrideKitAdvance.Stopped(step.toInt(), result.stoppedReason ?: "stopped", ticks)
    }

    /** Idempotent; afterwards every call but [pause] / [close] throws `IllegalStateException`. */
    public override fun close() {
        checkMain()
        if (closed) return
        closed = true
        pause()
        bridge = null
        audio.close()
        pictures.clear()
        core.close()
    }

    /** The view that drew this scene is gone: keep the runtime, draw nothing until the next [attach]. */
    internal fun detach(from: FilamentSceneBridge) {
        if (bridge === from) bridge = null
    }

    // ---------------------------------------------------------------------------------------------
    // Attach: load into Filament and materialise the scene
    // ---------------------------------------------------------------------------------------------

    /** Load the scene into `bridge` and apply the manifest: lights, cameras, visibility, env, settings. */
    internal fun attach(bridge: FilamentSceneBridge, assets: AssetManager?) {
        checkOpenOnMain()
        this.bridge = bridge
        bridge.onLoadProblem = ::reportLoad
        // The new renderables hold no weights yet: forget the old vectors before this attach's frames fill them.
        morphWeights.reset()
        val materialPackage = { name: String -> assets?.let { readAsset(it, "tride/materials/$name.filamat") } }
        // The pixelation pass's material comes from the same library assets.
        bridge.materialPackage = materialPackage
        bridge.generators = FilamentGenerators(
            engine = bridge.engine,
            scene = bridge.filamentScene,
            materialPackage = materialPackage,
            assetBytes = ::cachedAssetBytes,
            entityForPlace = bridge::entityForPlace,
            onSceneMutated = { bridge.needsRender = true },
            report = ::reportLoad,
            hostPixels = pictures::get,
            // Inside the frame's dispatch: queued like the tick's other problems, delivered after the frame.
            pictureFailed = { input, reason ->
                core.imageFailed(input, reason)
                picturesToPrune += input
                report(TrideKitDiagnosticKind.LOAD, "image.$input", "image input '$input': $reason — the placeholder shows")
            },
            ubershaders = bridge.ubershaders,
        )
        // The default camera is the core's framing for the view's aspect — no rule of the bridge's own.
        bridge.framingFor = { aspect -> core.defaultFraming(aspect) }
        val record = core.sceneRecord() ?: run {
            // The view rethrows: the scene is not shown, its state, bindings and signals stay usable.
            val message = "scene not built: the runtime handed no scene record"
            reportNow(TrideKitDiagnosticKind.LOAD, "scene", message)
            throw IllegalStateException(message)
        }
        loadByRecord(record, bridge)
        materializeLights(bridge)
        materializeCameras(bridge)
        // A new view's carriers show the authored poses; a full frame brings the held ones back.
        holdPoses()
        bridge.setActiveCamera(core.activeCamera())
        // Node materials load before any mesh wears them.
        materializeMaterialGraphs(bridge, assets)
        materializeGenerators(bridge)
        materializeParametricNodes(bridge)
        applyNodeVisibility(bridge)
        applyRenderSettings(bridge, fresh = true)
        applyEnvironment(bridge, assets)
        val bg = core.backgroundColor()
        if (bg.size >= 3) environmentBaseBackground = doubleArrayOf(bg[0], bg[1], bg[2])
        bridge.setBackgroundColor(environmentBaseBackground[0], environmentBaseBackground[1], environmentBaseBackground[2])
        if (pendingAdvance.isNotEmpty()) { dispatch(pendingAdvance, bridge); pendingAdvance = ArrayList() }
        // A new bridge holds nothing the runtime already handed out: the next tick is whole.
        core.requestFullFrame()
        pushViewport()
        // The first frame: whatever the first tick moved, environment and settings included.
        tickAndDispatch(0f)
        bridge.needsRender = true
        Log.i(TAG, "attached: nodes=${nodeIdToIndex.size} lights=${lightDescriptors.size} cameras=${cameraDescriptors.size} carriers=${carrierIndexById.size} activeCamera=${core.activeCamera() ?: "-"}")
    }

    /**
     * The scene built from the core's [record] — one place per node row and per light / camera carrier
     * ([RecordScene]); a row's number is its node index. The core says each node's visibility to the renderer.
     * A `requires` Android does not draw is said once (`partialDraw`).
     */
    private fun loadByRecord(record: TrideSceneRecord, bridge: FilamentSceneBridge) {
        val built = RecordScene(record)
        lastBonePalettes.clear()
        partialDrawNotice = null
        bridge.loadPlaces(built.places)
        nodeIdToIndex.clear(); baselines.clear(); carrierIndexById.clear(); drawVisible.clear()
        for ((row, uid) in built.uidAt.withIndex()) {
            nodeIdToIndex[uid] = row
            val place = built.places[row]
            baselines[uid] = Baseline(place.translation, place.rotation, place.scale)
            drawVisible[row] = built.visibleAt[row]
        }
        for ((id, place) in built.carrierPlace) {
            carrierIndexById[id] = place
            nodeIdToIndex[id] = place
        }
        recordScene = built
        for (notice in built.notices) reportLoad("record", notice)
        RecordScene.requiresNotice(record.requires)?.let(::notePartialDraw)
        try {
            core.setDrawVisibility(true)
        } catch (e: Exception) {
            reportLoad("drawVisibility", "the runtime does not say what is drawn: ${e.message}")
        }
    }

    /**
     * The world matrix (16 numbers, column-major) of every node, light and camera the attached view draws, as its
     * TransformManager composes it, by node uid and light / camera id; one the view has no entity for is left out.
     * Empty without a view. For checks: the drawn worlds against the core's node table (the parity gate).
     */
    @TrideKitChecks
    public fun nodeWorlds(): Map<String, FloatArray> {
        checkOpenOnMain()
        val b = bridge ?: return emptyMap()
        val out = java.util.TreeMap<String, FloatArray>()
        for ((id, index) in nodeIdToIndex) b.worldTransform(index)?.let { out[id] = it }
        return out
    }

    /** The last bone palette the core handed per mesh row and part (`BonePaletteCheck.key`). */
    private val lastBonePalettes = HashMap<Long, TrideBonePalette>()

    /**
     * The core's bone palettes of a tick: each on the skin renderable of its mesh's row and part, the part's
     * box beside — the generators keep it for a renderable built later.
     */
    private fun applyBonePalettes(palettes: List<TrideBonePalette>, bridge: FilamentSceneBridge) {
        if (recordScene == null || palettes.isEmpty()) return
        val gen = bridge.generators ?: return
        for (p in palettes) {
            lastBonePalettes[BonePaletteCheck.key(p.mesh.toInt(), p.part.toInt())] = p
            val bounds = p.bounds?.takeIf { it.min.size == 3 && it.max.size == 3 }?.let { (it.min + it.max).toFloatArray() }
            gen.setBonePalette(p.mesh.toInt(), p.part.toInt(), p.bones, bounds)
        }
        bridge.needsRender = true
    }

    /**
     * The palette check (the parity gate): every palette the core handed last against the one gltfio's
     * `updateBoneMatrices` makes of the worlds the attached view's TransformManager composed ([BonePaletteCheck]);
     * `null` without a view.
     */
    @TrideKitChecks
    public fun bonePaletteCheck(): BonePaletteCheck.Result? {
        checkOpenOnMain()
        val b = bridge ?: return null
        val built = recordScene ?: return null
        // A skin cut into parts: each part's palette names its own joints (the decoder's cut).
        val partJoints = HashMap<Long, List<Int>>()
        for (node in built.datablockNodes) {
            if (built.skinOf[node.nodeUid] == null) continue
            val row = built.rowOf[node.nodeUid] ?: continue
            val mesh = core.datablockMesh(node.datablockUid) ?: continue
            for (part in mesh.skinParts + mesh.parts.flatMap { it.skinParts }) {
                partJoints[BonePaletteCheck.key(row, part.part.toInt())] = part.joints.map { it.toInt() }
            }
        }
        return BonePaletteCheck.measure(lastBonePalettes.values, built.skins, partJoints) { b.worldTransform(it) }
    }

    /** The scene's light ids, as listed at the last attach (checks). */
    internal fun lightIds(): Set<String> = lightDescriptors.keys.toSet()

    /** The place the view drew light / camera [id]'s carrier at (checks). */
    internal fun carrierIndex(id: String): Int? = carrierIndexById[id]

    private fun lightForwardDirection(euler: List<Double>?): DoubleArray {
        val forward = doubleArrayOf(0.0, 0.0, -1.0)
        if (euler == null || euler.size < 3) return forward
        return TrideMath.rotateByQuat(CoreRotation.quatOfEulerXYZ(doubleArrayOf(euler[0], euler[1], euler[2])), forward)
    }

    private fun materializeLights(bridge: FilamentSceneBridge) {
        val lights = core.listLights()
        lightDescriptors.clear()
        for (l in lights) lightDescriptors.putIfAbsent(l.id, l)
        bridge.addLights(lights.map { l ->
            LightSpec(
                id = l.id, type = l.lightType,
                color = doubleArrayOf(l.color.getOrElse(0) { 1.0 }, l.color.getOrElse(1) { 1.0 }, l.color.getOrElse(2) { 1.0 }),
                intensity = l.intensity, range = l.range, innerConeAngle = l.innerConeAngle, outerConeAngle = l.outerConeAngle,
                castShadows = l.castShadows, position = l.position?.toDoubleArray(), direction = lightForwardDirection(l.rotation),
                carrierPlace = carrierIndexById[l.id],
            )
        })
    }

    private fun materializeCameras(bridge: FilamentSceneBridge) {
        val cams = core.listCameras()
        cameraDescriptors.clear()
        for (c in cams) cameraDescriptors.putIfAbsent(c.id, c)
        bridge.addCameras(cams.map { c ->
            CameraSpec(
                id = c.id, perspective = c.kind != "orthographic", yfov = c.yfov, xmag = c.xmag, ymag = c.ymag,
                znear = c.znear, zfar = c.zfar, focusDistance = c.focusDistance, aperture = c.aperture,
                carrierPlace = carrierIndexById[c.id], focusTargetPlace = c.focusTarget?.let { nodeIdToIndex[it] },
            )
        })
    }

    // ---------------------------------------------------------------------------------------------
    // Generators, parametric nodes, node materials — pulled from the runtime, drawn by FilamentGenerators
    // ---------------------------------------------------------------------------------------------

    /**
     * Load every node material's packages — checked against this engine's
     * Filament and backend first (a foreign package aborts in `Material.Builder`) — with their current
     * uniforms; the meshes built after them wear them. Without a loaded package a file mesh draws the
     * record's glTF form (written by the core), generated meshes and batches the built-in look. The core's problems (stale / version records, a package the archive does not hold or the
     * core refused) and the packages this device refused ([hostRefused]) are told once per material and
     * code ([materialProblemsToTell]).
     */
    private fun materializeMaterialGraphs(bridge: FilamentSceneBridge, assets: AssetManager?) {
        materialGraphFamilies.clear()
        sentUniforms.clear()
        val refused = loadMaterialGraphs(bridge, assets)
        for (p in materialProblemsToTell(core.materialGraphProblems() + refused)) reportLoad(p.slug, p.message, p.code)
    }

    /** Loads the packages this device takes; returns the ones the core accepted and this device refused ([hostRefused]). */
    private fun loadMaterialGraphs(bridge: FilamentSceneBridge, assets: AssetManager?): List<TrideMaterialGraphProblem> {
        val gen = bridge.generators ?: return emptyList()
        val slugs = core.materialGraphSlugs()
        if (slugs.isEmpty()) return emptyList()
        val refused = ArrayList<TrideMaterialGraphProblem>()
        // The engine's own version is the version of its built-in package.
        val engineVersion = assets?.let { readAsset(it, "tride/materials/tride-generated.filamat") }?.let { materialPackageInfo(it) }?.version
        val backend = if (bridge.engine.backend == Engine.Backend.VULKAN) "vulkan" else "opengl"
        for (slug in slugs) for (family in listOf("mesh", "generated", "instanced")) {
            val graph = core.materialGraph(slug, family) ?: continue
            // A package the archive does not hold, or one the core refused, is the core's problem.
            val bytes = core.assetBytes(graph.packageUri) ?: continue
            val info = materialPackageInfo(bytes)
            if (info == null || info.version != engineVersion || backend !in info.backends) {
                Log.w(TAG, "node material '$slug' ($family): package version ${info?.version} for engine $engineVersion, backends ${info?.backends} without $backend")
                refused += hostRefused(slug, family); continue
            }
            if (!gen.registerMaterialGraph(slug, family, bytes, graph)) { refused += hostRefused(slug, family); continue }
            materialGraphFamilies.getOrPut(slug) { ArrayList() } += family
            pushMaterialGraphValues(slug, family, gen)
        }
        Log.i(TAG, "node materials: ${materialGraphFamilies.size} of ${slugs.size} loaded")
        return refused
    }

    /** The runtime's uniforms of `slug` on `family` at the scene's current values that moved since the last push, to every instance. */
    private fun pushMaterialGraphValues(slug: String, family: String, gen: FilamentGenerators) {
        val moved = sentUniforms.changes(slug, family, core.materialGraphValues(slug, family))
        if (moved.isNotEmpty()) gen.updateMaterialGraph(slug, family, moved)
    }

    private fun materializeGenerators(bridge: FilamentSceneBridge) {
        generatorHostIndex.clear(); generatorHostUid.clear(); generatorMaterialSlug.clear(); generatorMaterialParams.clear(); generatorPulseLook.clear()
        generatorModules.clear(); registeredModuleMeshes.clear(); moduleParts.clear(); moduleMaterialParams.clear(); moduleMaterialSlug.clear()
        uploadedTrunkEpoch.clear()
        paletteSent.clear(); assetBytesCache.clear()
        val generators = core.listGenerators()
        for (d in generators) {
            d.nodeUid?.let { uid -> nodeIdToIndex[uid]?.let { generatorHostIndex[d.id] = it; generatorHostUid[d.id] = uid } }
            d.material?.let { generatorMaterialSlug[d.id] = it }
            d.materialParams?.let { generatorMaterialParams[d.id] = it }
            d.pulse?.let { generatorPulseLook[d.id] = it }
            if (d.modules.isNotEmpty()) generatorModules[d.id] = d.modules
            uploadGeneratedMesh(d.id, bridge)
        }
        if (generators.isNotEmpty()) Log.i(TAG, "materialized ${generators.size} generator mesh(es)")
    }

    /**
     * Every parametric node draws its datablock's mesh — built by the runtime
     * from the recipe — as a generated mesh under the node, in the runtime's look (palette entry, else
     * the core's default look, white plastic).
     */
    private fun materializeParametricNodes(bridge: FilamentSceneBridge) {
        val gen = bridge.generators ?: return
        val built = recordScene ?: return
        // Every node with a datablock is drawn this way, an imported mesh included; a file mesh wears the
        // record's materials, handed over before the meshes.
        gen.setStandardMaterials(built.materials.mapNotNull { m -> m.standard?.let { m.slug to it } }.toMap())
        parametricNodes = built.datablockNodes
        for (n in parametricNodes) uploadParametricNode(n, gen)
        if (parametricNodes.isNotEmpty()) Log.i(TAG, "materialized ${parametricNodes.size} parametric node(s)")
        // A key asking for what the record does not carry (or a picture this build cannot decode): drawn in part.
        RecordScene.gapsNotice(gen.standardMaterialGaps())?.let(::notePartialDraw)
        // The nodes the runtime bent at load (a parametric one drew bent above).
        val bent = core.listDeformedNodes()
        for (uid in bent) refreshDeformed(uid, bridge)
        if (bent.isNotEmpty()) Log.i(TAG, "materialized ${bent.size} bent node(s)")
    }

    /**
     * Node `uid` was bent anew, or left every deformer's reach: its datablock's node draws its bent mesh, else its
     * recipe's. Every mesh of the scene record is a datablock's, so a bent node without one draws nothing.
     */
    private fun refreshDeformed(uid: String, bridge: FilamentSceneBridge) {
        val gen = bridge.generators ?: return
        val node = parametricNodes.firstOrNull { it.nodeUid == uid } ?: run {
            warnOnce(TrideKitDiagnosticKind.LOAD, uid, "bent node $uid draws no datablock of the scene record — not drawn"); return
        }
        uploadParametricNode(node, gen)
    }

    /**
     * Datablock `uid` was rebuilt at its effective recipe — its nodes draw the new mesh, and a module
     * mesh registered under it (clones, L-system modules) is registered again (the bridge moves its
     * batches to the new buffers).
     */
    private fun refreshParametric(uid: String, bridge: FilamentSceneBridge) {
        val gen = bridge.generators ?: return
        for (n in parametricNodes) if (n.datablockUid == uid) uploadParametricNode(n, gen)
        val before = moduleParts[uid] ?: return
        val mesh = core.datablockMesh(uid) ?: return
        val parts = registerModuleMesh(uid, mesh, gen)
        if (parts == null) { warnOnce(TrideKitDiagnosticKind.LOAD, "$uid", "bridge rejected the rebuilt module mesh $uid"); return }
        // A group that went (a fillet switched off): its batches hide.
        val kept = parts.map { it.uid }.toSet()
        for (gone in before) {
            if (gone.uid in kept) continue
            for ((id, modules) in generatorModules) for (binding in modules) {
                if (binding.datablock == uid) gen.syncModuleInstances(id, binding.symbol + gone.suffix, gone.uid, ByteArray(0), null, null, PaletteLook(null, null))
            }
        }
    }

    /** One draw of a module mesh ([moduleParts]): `suffix` joins the batch's symbol, `uid` is the mesh's key. */
    private class ModulePart(val suffix: String, val uid: String)

    /**
     * Register a module mesh with the bridge — whole, or one sub-mesh per draw group (the whole vertex streams, the
     * group's slice of the indices, content key `<hash>#<i>`) — and cache each part's look. `null` when the bridge
     * rejects one.
     */
    private fun registerModuleMesh(uid: String, mesh: TrideDatablockMesh, gen: FilamentGenerators): List<ModulePart>? {
        val parts = ArrayList<ModulePart>()
        if (mesh.groups.isEmpty()) {
            if (!gen.registerModuleMesh(uid, mesh)) return null
            registeredModuleMeshes[uid] = mesh.contentHash
            moduleMaterialParams[uid] = mesh.materialParams
            mesh.material?.let { moduleMaterialSlug[uid] = it } ?: moduleMaterialSlug.remove(uid)
            parts += ModulePart("", uid)
        } else {
            for ((i, group) in mesh.groups.withIndex()) {
                val part = ModulePart("#$i", "$uid#$i")
                val start = group.indexStart.toInt() * 4
                val sub = mesh.copy(
                    material = group.material,
                    materialParams = group.materialParams ?: mesh.materialParams,
                    indices = mesh.indices.copyOfRange(start, start + group.indexCount.toInt() * 4),
                    contentHash = "${mesh.contentHash}#$i",
                    groups = emptyList(),
                )
                if (!gen.registerModuleMesh(part.uid, sub)) return null
                registeredModuleMeshes[part.uid] = sub.contentHash
                moduleMaterialParams[part.uid] = sub.materialParams
                group.material?.let { moduleMaterialSlug[part.uid] = it } ?: moduleMaterialSlug.remove(part.uid)
                parts += part
            }
        }
        moduleParts[uid] = parts
        return parts
    }

    private fun uploadParametricNode(n: TrideParametricNode, gen: FilamentGenerators) {
        val parent = nodeIdToIndex[n.nodeUid] ?: run {
            warnOnce(TrideKitDiagnosticKind.LOAD, "${n.nodeUid}", "parametric node ${n.nodeUid} is no place of the scene — not drawn"); return
        }
        // A node a deformer reaches draws its bent mesh, else its recipe's.
        val mesh = core.deformedMesh(n.nodeUid) ?: core.datablockMesh(n.datablockUid) ?: run {
            warnOnce(TrideKitDiagnosticKind.LOAD, "${n.datablockUid}", "parametric node ${n.nodeUid}: datablock ${n.datablockUid} could not be built — not drawn"); return
        }
        val id = "parametric:${n.nodeUid}"
        if (!upload(mesh, id, parent, n.nodeUid, gen)) {
            reportLoad(id, "parametric mesh '$id' could not be drawn")
        }
    }

    /**
     * One runtime mesh drawn as generated mesh `id` under node `parent`, in the mesh's look — or, a file mesh
     * ([isFileMesh]), as gltfio draws its primitives.
     */
    private fun upload(mesh: TrideDatablockMesh, id: String, parent: Int, objectUid: String, gen: FilamentGenerators): Boolean {
        // A multi-material import keeps a draw group per primitive, each in its look.
        val geometry = TrideGeneratedGeometry(id, 0uL, "triangles", mesh.positions, mesh.normals, mesh.uvs, ByteArray(0), mesh.colors, mesh.indices, mesh.groups, mesh.tangents)
        val fileMesh = if (isFileMesh(mesh)) mesh else null
        // A mesh with targets starts at the weights the stream last set for its row, else the node's at rest.
        val weights = if (fileMesh != null && fileMesh.morphTargets.isNotEmpty()) {
            morphWeights.held(parent) ?: recordScene?.restWeights?.get(objectUid)?.let { w -> FloatArray(w.size) { w[it].toFloat() } }
        } else null
        // A node with a skin: its joints (at most Filament's 256) bend the mesh as a whole skin; a skin cut into parts
        // names its own (`skinParts`). The palettes come per tick.
        val skinBones = if (fileMesh != null) recordScene?.let { r -> r.skinOf[objectUid]?.let { min(r.skins[it].joints.size, 256) } } ?: 0 else 0
        val host = GeneratorHost(parent, mesh.material, mesh.materialParams, null, objectUid, materialObjectSeed(objectUid).toFloat(), fileMesh, weights, skinBones)
        return gen.upsertGeneratedMesh(id, geometry, host)
    }

    /**
     * A file mesh names what its primitives carry in the file — on the mesh without groups, else on every group, and
     * on every part; a mesh of parts alone, on its parts. A mesh the core built names nothing.
     */
    private fun isFileMesh(mesh: TrideDatablockMesh): Boolean {
        if (!mesh.parts.all { it.attributes != null }) return false
        if (mesh.positions.isEmpty() && mesh.attributes == null) return mesh.parts.isNotEmpty()
        return mesh.attributes != null && mesh.groups.all { it.attributes != null }
    }

    private fun hostFor(id: String): GeneratorHost {
        val uid = generatorHostUid[id]
        return GeneratorHost(generatorHostIndex[id], generatorMaterialSlug[id], generatorMaterialParams[id], generatorPulseLook[id],
            uid, uid?.let { materialObjectSeed(it).toFloat() } ?: 0f)
    }

    /**
     * Pull the trunk buffers of `id` and upload them (create or update in place), then its modules. A
     * `trunkEpoch` the uploaded trunk already came from skips the trunk: only the instances moved.
     */
    private fun uploadGeneratedMesh(id: String, bridge: FilamentSceneBridge, trunkEpoch: Long? = null) {
        val gen = bridge.generators ?: return
        if (trunkEpoch != null && uploadedTrunkEpoch[id] == trunkEpoch) { syncModuleInstances(id, bridge); return }
        val debug = Log.isLoggable(TAG, Log.DEBUG)
        val t0 = if (debug) System.nanoTime() else 0L
        val geometry = core.generatedGeometry(id) ?: run { reportLoad(id, "generator '$id' has no geometry (its evaluation failed?)"); return }
        val t1 = if (debug) System.nanoTime() else 0L
        if (gen.upsertGeneratedMesh(id, geometry, hostFor(id))) {
            if (trunkEpoch != null) uploadedTrunkEpoch[id] = trunkEpoch else uploadedTrunkEpoch.remove(id)
        } else {
            reportLoad(id, "generated mesh '$id' could not be drawn")
            uploadedTrunkEpoch.remove(id)
        }
        if (debug) Log.d(TAG, "generated mesh '$id' verts=${geometry.positions.size / 12} ffi=${(t1 - t0) / 1e6}ms upload=${(System.nanoTime() - t1) / 1e6}ms")
        syncModuleInstances(id, bridge)
    }

    private fun syncModuleInstances(id: String, bridge: FilamentSceneBridge) {
        val gen = bridge.generators ?: return
        val modules = generatorModules[id] ?: return
        for (binding in modules) {
            if (moduleParts[binding.datablock] == null) {
                val mesh = core.datablockMesh(binding.datablock) ?: run {
                    warnOnce(TrideKitDiagnosticKind.LOAD, "$id:${binding.symbol}", "generator '$id' module '${binding.symbol}': datablock ${binding.datablock} could not be decoded — not rendered"); continue
                }
                if (registerModuleMesh(binding.datablock, mesh, gen) == null) { warnOnce(TrideKitDiagnosticKind.LOAD, "${binding.datablock}", "bridge rejected module mesh ${binding.datablock}"); continue }
            }
            val transforms = core.generatorModuleTransforms(id, binding.symbol)
            // One batch per draw part over the same transforms and rows ([moduleParts]).
            for (part in moduleParts[binding.datablock].orEmpty()) {
                val slug = moduleMaterialSlug[part.uid]
                // The instance rows travel with the transforms, pulled only where the material samples them.
                val colors = if (gen.samplesInstanceColors(slug)) core.generatorModuleColors(id, binding.symbol) else null
                val placed = gen.syncModuleInstances(id, binding.symbol + part.suffix, part.uid, transforms, colors, generatorHostIndex[id],
                    PaletteLook(slug, moduleMaterialParams[part.uid]))
                debugLog { "generator '$id' module '${binding.symbol + part.suffix}': $placed/${transforms.size / 64} instances" }
            }
        }
    }

    private fun uploadGeneratorFlow(id: String, bridge: FilamentSceneBridge) {
        val gen = bridge.generators ?: return
        if (generatorPulseLook[id] == null) return
        val table = core.generatorFlow(id)
        if (!gen.setGeneratedMeshFlow(id, table) && table != null) warnOnce(TrideKitDiagnosticKind.LOAD, "$id", "bridge rejected the pulse table of '$id' (trunk not pulsed?)")
    }

    /**
     * A palette look was written — pull it as the scene draws it now and recolour every built-in instance.
     * [lookWritten] = the tick wrote a leaf other than a node-graph input ([isGraphLeaf]): without it the look
     * cannot have moved and is not pulled, unless the slug was never recoloured yet.
     */
    private fun recolourPalette(slug: String, lookWritten: Boolean, bridge: FilamentSceneBridge) {
        val gen = bridge.generators ?: return
        // A node material's uniforms come from the runtime's table.
        materialGraphFamilies[slug]?.forEach { pushMaterialGraphValues(slug, it, gen) }
        if (!lookWritten && slug in paletteSent) return
        val params = core.materialParams(slug) ?: run { warnOnce(TrideKitDiagnosticKind.LOAD, "$slug", "palette material '$slug' is animated but not in the scene — write ignored"); return }
        if (paletteSent[slug] == params) return
        paletteSent[slug] = params
        for ((id, material) in generatorMaterialSlug) if (material == slug) generatorMaterialParams[id] = params
        for ((datablock, material) in moduleMaterialSlug) if (material == slug) moduleMaterialParams[datablock] = params
        for ((id, look) in generatorPulseLook) if (look.material == slug) generatorPulseLook[id] = TridePulseLook(slug, params)
        val touched = gen.updatePaletteMaterial(slug, params)
        debugLog { "palette '$slug': $touched instance(s) recoloured" }
    }

    /**
     * The app's pictures the photo slots of [slugs] (`null` = every slug with slots) show now, from the
     * runtime's groups (the host contract): the tick laid or took one off.
     */
    private fun refreshPhotoSlots(slugs: Set<String>?, bridge: FilamentSceneBridge) {
        val gen = bridge.generators ?: return
        val withSlots = gen.photoSlotSlugs()
        if (withSlots.isEmpty()) return
        for (slug in slugs?.filter { it in withSlots } ?: withSlots) {
            for (family in materialGraphFamilies[slug].orEmpty()) {
                val groups = core.materialGraph(slug, family)?.groups ?: continue
                // A slot that moved may leave a picture nothing binds: its input is pruned after the tick.
                if (gen.refreshPictures(slug, family, groups)) groups.mapNotNullTo(picturesToPrune) { it.hostImage }
            }
        }
    }

    /** Every node's visibility: each row the core's value (the bridge stops at the rows under it). */
    private fun applyNodeVisibility(bridge: FilamentSceneBridge) {
        for ((row, visible) in drawVisible) bridge.setNodeVisible(row, visible)
    }

    /**
     * The scene's render settings with the host's knobs, as the runtime lays them. [fresh] = a new view: it
     * starts from the authored leaves (a full frame brings the live ones back); otherwise the same view takes
     * the settings again and keeps what the frames wrote over them. `null` (a scene without settings, no knob):
     * the view draws as a new view does — one that took knobbed settings goes back to the options it was made
     * with (`restoreInitialRenderSettings`); the schema's defaults are not what a new view draws.
     */
    private fun applyRenderSettings(bridge: FilamentSceneBridge, fresh: Boolean) {
        val settings = core.renderSettingsWith(knobs ?: TrideRenderOverrides())
        if (fresh) {
            liveRender = LiveRenderLeaves(settings?.fog?.let { doubleArrayOf(it.colorR.toDouble(), it.colorG.toDouble(), it.colorB.toDouble()) } ?: doubleArrayOf(0.0, 0.0, 0.0))
        }
        if (settings == null) {
            if (bridge.renderSettingsShown != null) bridge.restoreInitialRenderSettings()
            Log.i(TAG, "renderSettings: not authored — Filament defaults")
            return
        }
        bridge.applyRenderSettings(settings)
    }

    private fun applyEnvironment(bridge: FilamentSceneBridge, assets: AssetManager?) {
        val env = core.environment() ?: return
        if (env.rotation.size >= 3) environmentBaseEuler = doubleArrayOf(env.rotation[0], env.rotation[1], env.rotation[2])
        val ibl: ByteArray?
        val skybox: ByteArray?
        when (env.kind) {
            "preset" -> {
                if (assets == null) { reportLoad(env.name, "environment preset '${env.name}' needs the app's AssetManager — default lighting"); return }
                ibl = readAsset(assets, "tride/ibl/${env.name}_ibl.ktx")
                skybox = if (env.backgroundVisible) readAsset(assets, "tride/ibl/${env.name}_skybox.ktx") else null
            }
            "custom" -> {
                ibl = core.assetBytes("assets/ibl/${env.name}_ibl.ktx")
                skybox = if (env.backgroundVisible) core.assetBytes("assets/ibl/${env.name}_skybox.ktx") else null
            }
            else -> { reportLoad(env.name, "environment kind '${env.kind}' is unknown to this TrideKit — default lighting"); return }
        }
        if (ibl == null) { reportLoad(env.name, "environment '${env.name}' (${env.kind}) has no lighting file — default lighting"); return }
        bridge.setIndirectLight(ibl, skybox, env.intensity, environmentBaseEuler)
        Log.i(TAG, "environment applied: ${env.kind} '${env.name}' (skybox=${skybox != null}, intensity=${env.intensity}, rotation=${env.rotation})")
    }

    private fun readAsset(assets: AssetManager, path: String): ByteArray? =
        try { assets.open(path).use { it.readBytes() } } catch (e: Exception) { null }

    // ---------------------------------------------------------------------------------------------
    // Tick → frame → Filament
    // ---------------------------------------------------------------------------------------------

    /** One tick shown as a frame; [complete] = the runtime hands out the whole frame (after its reset). */
    private fun tickAndDispatch(dtMs: Float, complete: Boolean = false) {
        tickCount++
        // What each input sounds now — the tick reads it.
        audio.hear()
        // The camera the view draws through, before the tick: tagged objects turn toward it this frame.
        pushViewport(onlyIfMoved = true)
        val result = try { core.tick(dtMs) } catch (e: Exception) {
            reportNow(TrideKitDiagnosticKind.RUNTIME, "tick", e.message ?: e.toString()); return
        }
        publishDiagnostics(result.diagnostics)
        val b = bridge
        if (b != null && (result.mutations.isNotEmpty() || complete)) {
            dispatch(result.mutations, b, complete)
            b.needsRender = true
        }
        // The skins' palettes this tick moved, after the poses they were made from.
        if (b != null) applyBonePalettes(result.bonePalettes, b)
        prunePictures()
        deliverSignals()
    }

    private fun decode(m: TrideMutation): Any? {
        val v = TypedValues.decode(m)
        if (v !is TypedValues.RawJson) return v
        return try {
            val raw = v.raw.trim()
            when {
                raw.startsWith("[") -> {
                    val arr = JSONArray(raw)
                    if ((0 until arr.length()).all { arr.opt(it) is Number }) DoubleArray(arr.length()) { arr.getDouble(it) } else raw
                }
                raw == "true" -> true
                raw == "false" -> false
                raw.startsWith("\"") -> JSONArray("[$raw]").getString(0)
                else -> raw.toDoubleOrNull() ?: raw
            }
        } catch (e: Exception) { v.raw }
    }

    private class LightAcc {
        var color: DoubleArray? = null; var intensity: Double? = null; var range: Double? = null
        var inner: Double? = null; var outer: Double? = null
    }
    private class Lens { var yfov: Double? = null; var znear: Double? = null; var zfar: Double? = null; var focus: Double? = null; var aperture: Double? = null }

    private fun patch(base: DoubleArray, component: Int?, value: Any): DoubleArray? = when {
        component != null && value is Double -> base.copyOf().also { it[component] = value }
        value is DoubleArray && value.size >= 3 -> doubleArrayOf(value[0], value[1], value[2])
        else -> null
    }

    private fun lightPose(id: String) = "/lights/$id"
    private fun cameraPose(id: String) = "/cameras/$id"

    /** The held pose of every light and camera, from the authored records — what a new view's carriers show. */
    private fun holdPoses() {
        val records = HashMap<String, Pose>()
        fun pose(position: List<Double>?, rotation: List<Double>?, order: String) = Pose(
            doubleArrayOf(position?.getOrNull(0) ?: 0.0, position?.getOrNull(1) ?: 0.0, position?.getOrNull(2) ?: 0.0),
            doubleArrayOf(rotation?.getOrNull(0) ?: 0.0, rotation?.getOrNull(1) ?: 0.0, rotation?.getOrNull(2) ?: 0.0),
            order,
        )
        for ((id, d) in lightDescriptors) records[lightPose(id)] = pose(d.position, d.rotation, LIGHT_ROTATION_ORDER)
        for ((id, d) in cameraDescriptors) records[cameraPose(id)] = pose(d.position, d.rotation, d.rotationOrder)
        poses = PoseHold(records)
    }

    /** A held pose to the view: on the carrier node, else on a light itself (a camera without one cannot move). */
    private fun writePose(key: String, bridge: FilamentSceneBridge) {
        val pose = poses.pose(key) ?: return
        val light = key.startsWith("/lights/")
        val id = key.substringAfter(if (light) "/lights/" else "/cameras/")
        val carrier = carrierIndexById[id]
        when {
            carrier != null -> bridge.setNodeTRS(carrier, pose.position, carrierRotation(id, pose), null)
            light -> {
                bridge.setLightPosition(id, pose.position)
                bridge.setLightDirection(id, lightForwardDirection(pose.euler.toList()))
            }
            else -> warnOnce(TrideKitDiagnosticKind.POINTER, id, "camera '$id' has no carrier place in the scene record — its pose cannot move")
        }
    }

    /**
     * The quaternion `[x, y, z, w]` of a held pose's angles in its record's order — the core's one
     * composition (`eulerToQuat`, the same its own camera pose and `pick` use), so any order the core
     * knows places the carrier alike on every host. `null` keeps the carrier's rotation: an order this
     * core does not know (a host built against another core).
     */
    private fun carrierRotation(id: String, pose: Pose): DoubleArray? {
        val q = eulerToQuat(pose.euler.toList(), pose.order)
        if (q.size == 4) return q.toDoubleArray()
        warnOnce(TrideKitDiagnosticKind.POINTER, "$id/rotationOrder", "'$id': rotation order '${pose.order}' is unknown to the core — the rotation is not applied")
        return null
    }

    private fun dispatch(mutations: List<TrideMutation>, bridge: FilamentSceneBridge, complete: Boolean = false) {
        val routed = routeMutations(mutations.map { PointerMutation(it.pointer, decode(it)) }, resolver)
        for (p in routed.problems) warnOnce(TrideKitDiagnosticKind.POINTER, "${p.pointer}", "${p.pointer}: ${p.message} [${p.code}]")

        // Nodes through the pointer grammar into the persistent accumulator.
        for (m in routed.node) {
            when (val d = PointerDispatch.dispatch(m.pointer, m.value, resolver)) {
                is Dispatch.Ok -> accumulator.apply(d.mutation)
                is Dispatch.Fail -> warnOnce(TrideKitDiagnosticKind.POINTER, "${m.pointer}", "${m.pointer}: ${d.message}")
            }
        }
        for (id in accumulator.dirty) {
            val ov = accumulator.overrides[id] ?: continue
            val base = baselines[id] ?: continue
            val index = nodeIdToIndex[id] ?: continue
            bridge.setNodeTRS(index, ov.position ?: base.position, resolveLeafRotation(ov, base.rotation, CoreRotation::quatOfEulerXYZ), ov.scale ?: base.scale)
            // The node's own flag is not drawn: the core says each row's visibility (`drawVisible`, below).
        }
        accumulator.dirty.clear()
        for (w in routed.drawVisible) {
            val row = nodeIdToIndex[w.uid]
            if (row == null) {
                warnOnce(TrideKitDiagnosticKind.POINTER, "/node/${w.uid}/drawVisible", "/node/${w.uid}/drawVisible: no node of the scene")
                continue
            }
            if (drawVisible[row] == w.visible) continue
            drawVisible[row] = w.visible
            bridge.setNodeVisible(row, w.visible)
        }
        for ((gltfIndex, lanes) in accumulator.morphWeights) {
            val count = bridge.morphTargetCount(gltfIndex)
            if (count <= 0) { warnOnce(TrideKitDiagnosticKind.POINTER, "$gltfIndex", "node $gltfIndex has no morph targets — its weights are ignored"); continue }
            val fold = morphWeights.fold(gltfIndex, count, lanes)
            for (lane in fold.rejected) warnOnce(TrideKitDiagnosticKind.POINTER, "$gltfIndex/$lane", "node $gltfIndex has no morph target $lane")
            bridge.setMorphWeights(gltfIndex, fold.vector)
        }
        accumulator.morphWeights.clear()

        // Light and camera poses: their position / rotation lanes on the held pose (one per light and
        // camera), written to the carrier node — or to the light itself when it has none.
        val poseLanes = ArrayList<PoseLane>()
        // Lights: the other channels accumulate per slug, seeded from the descriptor.
        if (routed.light.isNotEmpty()) {
            val accs = LinkedHashMap<String, LightAcc>()
            for (lm in routed.light) {
                val d = lightDescriptors[lm.lightId]
                if (d == null && !bridge.hasLight(lm.lightId)) { warnOnce(TrideKitDiagnosticKind.POINTER, "${lm.lightId}", "light '${lm.lightId}' is not in the manifest"); continue }
                if (lm.property == "position" || lm.property == "rotation") {
                    poseLanes += PoseLane(lightPose(lm.lightId), lm.property, lm.component, lm.value)
                    continue
                }
                val acc = accs.getOrPut(lm.lightId) { LightAcc() }
                when (lm.property) {
                    "color" -> acc.color = patch(acc.color ?: d?.color?.toDoubleArray() ?: doubleArrayOf(1.0, 1.0, 1.0), lm.component, lm.value)
                    "intensity" -> (lm.value as? Double)?.let { acc.intensity = it }
                    "range" -> (lm.value as? Double)?.let { acc.range = it }
                    "innerConeAngle" -> (lm.value as? Double)?.let { acc.inner = it }
                    "outerConeAngle" -> (lm.value as? Double)?.let { acc.outer = it }
                }
            }
            for ((id, acc) in accs) {
                acc.color?.let { bridge.setLightColor(id, it[0], it[1], it[2]) }
                acc.intensity?.let { bridge.setLightIntensity(id, it) }
                acc.range?.let { bridge.setLightRange(id, it) }
                if (acc.inner != null || acc.outer != null) {
                    val d = lightDescriptors[id]
                    bridge.setLightCone(id, acc.inner ?: d?.innerConeAngle ?: 0.0, acc.outer ?: d?.outerConeAngle ?: (Math.PI / 4))
                }
            }
        }

        // Cameras: the pose on the held pose (above), the lens on the component.
        if (routed.camera.isNotEmpty()) {
            val lenses = LinkedHashMap<String, Lens>()
            for (cm in routed.camera) {
                if (cameraDescriptors[cm.cameraId] == null) { warnOnce(TrideKitDiagnosticKind.POINTER, "${cm.cameraId}", "camera '${cm.cameraId}' is not in the manifest"); continue }
                when (cm.property) {
                    "position", "rotation" -> poseLanes += PoseLane(cameraPose(cm.cameraId), cm.property, cm.component, cm.value)
                    "yfov" -> (cm.value as? Double)?.let { lenses.getOrPut(cm.cameraId) { Lens() }.yfov = it }
                    "znear" -> (cm.value as? Double)?.let { lenses.getOrPut(cm.cameraId) { Lens() }.znear = it }
                    "zfar" -> (cm.value as? Double)?.let { lenses.getOrPut(cm.cameraId) { Lens() }.zfar = it }
                    "focusDistance" -> (cm.value as? Double)?.let { lenses.getOrPut(cm.cameraId) { Lens() }.focus = it }
                    "aperture" -> (cm.value as? Double)?.let { lenses.getOrPut(cm.cameraId) { Lens() }.aperture = it }
                }
            }
            for ((id, lens) in lenses) {
                if (lens.yfov != null || lens.znear != null || lens.zfar != null) bridge.setCameraProjection(id, lens.yfov, lens.znear, lens.zfar)
                lens.focus?.let { bridge.setCameraFocusDistance(id, it) }
                lens.aperture?.let { bridge.setCameraAperture(id, it) }
            }
        }
        if (poseLanes.isNotEmpty()) {
            val fold = poses.fold(poseLanes)
            for (lane in fold.refused) warnOnce(TrideKitDiagnosticKind.POINTER, "${lane.id}/${lane.property}", "${lane.id}/${lane.property}: expects an axis number or a 3-vector")
            for (key in fold.wrote) writePose(key, bridge)
        }
        routed.activeCamera?.let { bridge.setActiveCamera(it) }

        // Environment, then the animatable render settings (saturation, fog colour, bloom strength, pixelation).
        if (accumulator.environmentMutations.isNotEmpty()) {
            var euler: DoubleArray? = null
            var intensity: Double? = null
            var background: DoubleArray? = null
            for (em in accumulator.environmentMutations) when (em.property) {
                "rotation" -> euler = patch(euler ?: environmentBaseEuler, em.component, em.value)
                "intensity" -> (em.value as? Double)?.let { intensity = it }
                "background_color" -> background = patch(background ?: environmentBaseBackground, em.component, em.value)
            }
            euler?.let { environmentBaseEuler = it; bridge.setIndirectLightRotation(it) }
            intensity?.let { bridge.setIndirectLightIntensity(it) }
            background?.let { environmentBaseBackground = it; bridge.setBackgroundColor(it[0], it[1], it[2]) }
        }
        if (routed.renderSettings.isNotEmpty() || complete) {
            val moved = liveRender.fold(routed.renderSettings, complete)
            if (moved.saturation) bridge.setSaturation(liveRender.saturation)
            if (moved.fog) liveRender.fogShown.let { bridge.setFogColor(it[0], it[1], it[2]) }
            if (moved.bloom) bridge.setBloomStrength(liveRender.bloomStrength)
            if (moved.pixelation) bridge.setPixelation(liveRender.pixelationEnabled, liveRender.pixelationSize)
        }
        // Palette looks written this tick (`/materialOverrides/{slug}/…`) → recolour from the runtime.
        val dirtyPalettes = LinkedHashSet<String>()
        val writtenLooks = HashSet<String>()
        for (mm in routed.material) {
            val slug = mm.materialSlug
            if (slug != null) {
                dirtyPalettes += slug
                if (complete || !isGraphLeaf(mm.subPath)) writtenLooks += slug
            }
            else warnOnce(TrideKitDiagnosticKind.POINTER, "material-index", "/materials/{i}/… writes by index are not applied on Android yet (${mm.subPath})")
        }
        // Generators: trunks announced this tick, then their pulse tables, then the palette recolours.
        val announced = LinkedHashMap<String, Long?>()
        for (a in routed.generatorGeometry) announced[a.id] = a.trunkEpoch
        val dirtyGenerators = announced.keys
        for ((id, trunk) in announced) uploadGeneratedMesh(id, bridge, trunk)
        for (id in LinkedHashSet(routed.generatorFlow)) uploadGeneratorFlow(id, bridge)
        // Rebuilt parametric datablocks — their nodes and their module meshes.
        val dirtyDatablocks = LinkedHashSet(routed.datablockGeometry)
        for (uid in dirtyDatablocks) refreshParametric(uid, bridge)
        // After the rebuilds: a bent node draws what its deformers make of the new base.
        val dirtyDeformed = LinkedHashSet(routed.deformedGeometry)
        for (uid in dirtyDeformed) refreshDeformed(uid, bridge)
        for (slug in dirtyPalettes) recolourPalette(slug, slug in writtenLooks, bridge)
        // The core's resolved glTF parameters, the scene meshes' instances, in batch order: a changed key makes
        // the slug's instances again (with every parameter taken so far), the parameters after it land on them.
        for (g in routed.gltfParams) when (g) {
            is GltfParamWrite -> if ((bridge.generators?.setGltfParameter(g.slug, g.name, g.values) ?: 0) == 0) {
                warnOnce(TrideKitDiagnosticKind.POINTER, "/materialOverrides/${g.slug}/gltf/${g.name}",
                    "material '${g.slug}': no glTF instance of it declares '${g.name}' — not drawn")
            }
            // A material that does not declare it draws as glTF says (`setInstanceState`): nothing to tell.
            is InstanceStateWrite -> bridge.generators?.setInstanceState(g.slug, g.name, g.value)
            is MaterialKeyWrite -> if (bridge.generators?.rekeyStandard(g.slug, g.key) == null) {
                warnOnce(TrideKitDiagnosticKind.POINTER, "/materialOverrides/${g.slug}/gltf/materialKey",
                    "material '${g.slug}': its key changed, but no scene mesh here draws it in its glTF form")
            }
        }
        // A glass write, once the meshes of the tick are made: a problem only where the built-in look draws it.
        bridge.generators?.let { gen ->
            for (p in glassProblems(routed.glass, gen::wearsBuiltInLook)) warnOnce(TrideKitDiagnosticKind.POINTER, p.pointer, "${p.pointer}: ${p.message} [${p.code}]")
        }
        refreshPhotoSlots(if (complete) null else dirtyPalettes, bridge)
        // A mesh re-uploaded under a hidden host must stay hidden.
        val anyHidden = drawVisible.values.any { !it }
        if ((dirtyGenerators.isNotEmpty() || dirtyDatablocks.isNotEmpty() || dirtyDeformed.isNotEmpty()) && anyHidden) applyNodeVisibility(bridge)
        accumulator.clearBuckets()
    }

    private fun deliverSignals() {
        if (inbox.isEmpty()) return
        val batch = ArrayList(inbox)
        inbox.clear()
        for (s in batch) when (s) {
            is Signal.Event -> for (h in eventHandlers.values.toList()) h(s.event)
            is Signal.State -> for (h in stateHandlers.values.toList()) h(s.change)
            is Signal.Problem -> for (h in diagnosticHandlers.values.toList()) h(s.diagnostic)
        }
    }

    private fun publishDiagnostics(found: List<EvalDiagnostic>) {
        for (d in found) {
            val message = d.message + (d.suggestion?.let { " — $it" } ?: "")
            report(TrideKitDiagnosticKind.EVALUATION, d.pointer, "${d.kind}: $message")
        }
    }

    private val reported = HashSet<TrideKitDiagnostic>()

    /** Steps the scene ran (instrumentation: the render-rate tests count the loop's wakes). */
    internal var tickCount: Long = 0L
        private set

    /** What the scene draws only in part: added to [partialDrawNotice] (each reason once per build) and said once to [onDiagnostic]. */
    private fun notePartialDraw(notice: String) {
        partialDrawNotice = partialDrawNotice?.let { "$it; $notice" } ?: notice
        report(TrideKitDiagnosticKind.RUNTIME, "partialDraw", notice)
    }

    /** A problem of the showing view (a refused render rate): [onDiagnostic] hears it once, at once. */
    internal fun reportFromView(subject: String, message: String) =
        reportNow(TrideKitDiagnosticKind.RUNTIME, subject, message)

    /** Logged and queued for [onDiagnostic] once per (kind, subject, message); delivered after the frame. */
    private fun report(kind: TrideKitDiagnosticKind, subject: String, message: String, code: String? = null) {
        val d = TrideKitDiagnostic(kind, subject, message, code)
        if (!reported.add(d)) return
        if (kind == TrideKitDiagnosticKind.EVALUATION) Log.e("tride.eval", "$subject: $message") else Log.w(TAG, message)
        inbox += Signal.Problem(d)
    }

    /** [report] outside a tick: delivered at once. */
    private fun reportNow(kind: TrideKitDiagnosticKind, subject: String, message: String) {
        report(kind, subject, message)
        deliverSignals()
    }

    /** Loading problems met by the bridge and the generators (at attach and on later uploads). */
    private fun reportLoad(subject: String, message: String, code: String? = null) =
        report(TrideKitDiagnosticKind.LOAD, subject, message, code)

    /** Per-tick detail, off in release logcat: `adb shell setprop log.tag.tride.kit DEBUG` turns it on. */
    private inline fun debugLog(message: () -> String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message())
    }

    private fun warnOnce(kind: TrideKitDiagnosticKind, subject: String, message: String) = report(kind, subject, message)
}
