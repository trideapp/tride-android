/**
 * The scene's frame prepared off the main thread: the tick, the routing of its changes, everything the dispatch
 * would read from the core for them and the re-grown trunks' packed streams — made on one thread per process,
 * applied to the bridge on the main thread one wake later.
 *
 * Public API:
 *   - `TrideKitTickMode` — `BACKGROUND` (the default: the tick on TrideKit's preparation thread) or `MAIN_THREAD`
 *     (the tick on the main thread in the same wake, as before 0.19.0 — kept for one release).
 *
 * Internal API:
 *   - `ScenePreparation.post(job)` — the one preparation thread (`HandlerThread`, `THREAD_PRIORITY_DISPLAY`) every
 *     scene of the process prepares on, one frame at a time.
 *   - `PreparedFrame` — one tick's self-contained frame: the result, its routed changes, the signals the tick
 *     raised, the core reads (`CorePulls`); `await()` blocks until it is done.
 *   - `PullPlan` — what the dispatch draws, copied on the main thread when the tick is queued.
 *   - `CorePulls` — the reads, answered from the frame; one the plan did not foresee goes to the core (`misses`).
 *   - `SceneSignals` — the core's listener calls, collected on whichever thread ticks.
 *
 * Non-obvious constraints:
 *   - The thread touches the core and pure code only (routing, `MeshPacking`) — never a Filament object, the view
 *     or the dispatch state. The routing reads the scene's node tables, which only an attach writes, and an attach
 *     waits for the frame in flight first.
 *   - The main thread applies a frame before it queues the next tick, so while a frame is applied no tick runs: a
 *     read the plan missed sees the very tick being shown.
 *   - The same `dt` and inputs give the same changes on either thread (the scene's fixed step); `sealInputs` on the
 *     main thread decides the tick each input lands in.
 */
package studio.tride.kit

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import studio.tride.core.TickResult
import studio.tride.core.TrideDatablockMesh
import studio.tride.core.TrideEnvironmentFrame
import studio.tride.core.TrideGeneratedGeometry
import studio.tride.core.TrideMaterialGraph
import studio.tride.core.TrideMaterialGraphValue
import studio.tride.core.TrideMaterialParams
import studio.tride.core.TrideModuleBindingDescriptor
import studio.tride.core.TrideMutation
import studio.tride.core.TrideParametricNode
import studio.tride.core.TridePulseTable
import studio.tride.core.TrideScene
import java.util.concurrent.CountDownLatch

/** Which thread ticks a scene. */
public enum class TrideKitTickMode {
    /**
     * The tick, its routing and its reads from the runtime on TrideKit's preparation thread; the frame reaches the
     * view one display frame after the wake that queued it. The default.
     */
    BACKGROUND,

    /** The tick on the main thread, shown in the same wake — TrideKit before 0.19.0. Kept for one release as a way back. */
    MAIN_THREAD,
}

/** The process's one preparation thread. */
internal object ScenePreparation {
    const val THREAD_NAME: String = "tride-scene-prepare"

    private val handler: Handler by lazy {
        val thread = HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        Handler(thread.looper)
    }

    fun post(job: Runnable) { handler.post(job) }
}

/** A scene event, a layer's transition or a problem, as the tick (or the kit) raised it. */
internal sealed interface SceneSignal {
    class Event(val event: TrideKitEvent) : SceneSignal
    class State(val change: TrideKitStateChange) : SceneSignal
    class Problem(val diagnostic: TrideKitDiagnostic) : SceneSignal
}

/** The core's listener calls, on the ticking thread — kept in order until their frame is applied. */
internal class SceneSignals {
    private val lock = Any()
    private val raised = ArrayList<SceneSignal>()

    fun add(signal: SceneSignal) { synchronized(lock) { raised += signal } }

    fun drain(): List<SceneSignal> = synchronized(lock) {
        if (raised.isEmpty()) emptyList() else ArrayList(raised).also { raised.clear() }
    }
}

/** What the dispatch draws — the reads a frame will need, copied on the main thread. */
internal class PullPlan(
    /** Generator id → its module bindings. */
    val modules: Map<String, List<TrideModuleBindingDescriptor>>,
    /** `id\u0000symbol` of the modules one of whose parts samples the instance rows. */
    val rowSymbols: Set<String>,
    /** Datablocks registered as module meshes. */
    val registered: Set<String>,
    /** Trunk epoch on screen per generator. */
    val uploadedTrunk: Map<String, Long>,
    /** Generators drawn with pulses. */
    val pulsed: Set<String>,
    /** Nodes drawing a datablock. */
    val parametric: List<TrideParametricNode>,
    /** Palette slug → its node-material families. */
    val graphFamilies: Map<String, List<String>>,
    /** Slugs with photo slots. */
    val slotSlugs: Set<String>,
)

/** The core's reads for one frame, made after its tick on the preparation thread. */
internal class CorePulls {
    private val geometry = HashMap<String, TrideGeneratedGeometry?>()
    /** The trunks' streams packed for the bridge (`MeshPacking`). */
    val packed = HashMap<String, PackedStreams>()
    private val transforms = HashMap<String, ByteArray>()
    private val colors = HashMap<String, ByteArray>()
    private val flows = HashMap<String, TridePulseTable?>()
    private val datablocks = HashMap<String, TrideDatablockMesh?>()
    private val deformed = HashMap<String, TrideDatablockMesh?>()
    private var environmentRead = false
    private var environment: TrideEnvironmentFrame? = null
    private val materialParams = HashMap<String, TrideMaterialParams?>()
    private val graphs = HashMap<String, TrideMaterialGraph?>()
    private val graphValues = HashMap<String, List<TrideMaterialGraphValue>>()

    /** Reads the frame did not carry, made on the core while applying (main thread). */
    var misses: Int = 0
        private set

    private inline fun <K, V> HashMap<K, V>.hitOr(key: K, read: () -> V): V {
        if (containsKey(key)) return getValue(key)
        misses++
        return read()
    }

    fun generatedGeometry(id: String, core: TrideScene) = geometry.hitOr(id) { core.generatedGeometry(id) }
    fun moduleTransforms(id: String, symbol: String, core: TrideScene) = transforms.hitOr(key(id, symbol)) { core.generatorModuleTransforms(id, symbol) }
    fun moduleColors(id: String, symbol: String, core: TrideScene) = colors.hitOr(key(id, symbol)) { core.generatorModuleColors(id, symbol) }
    fun flow(id: String, core: TrideScene) = flows.hitOr(id) { core.generatorFlow(id) }
    fun datablockMesh(uid: String, core: TrideScene) = datablocks.hitOr(uid) { core.datablockMesh(uid) }
    fun deformedMesh(uid: String, core: TrideScene) = deformed.hitOr(uid) { core.deformedMesh(uid) }
    fun params(slug: String, core: TrideScene) = materialParams.hitOr(slug) { core.materialParams(slug) }
    fun graph(slug: String, family: String, core: TrideScene) = graphs.hitOr(key(slug, family)) { core.materialGraph(slug, family) }
    fun values(slug: String, family: String, core: TrideScene) = graphValues.hitOr(key(slug, family)) { core.materialGraphValues(slug, family) }

    fun environmentFrame(core: TrideScene): TrideEnvironmentFrame? {
        if (environmentRead) return environment
        misses++
        return core.environmentFrame()
    }

    companion object {
        fun key(a: String, b: String): String = "$a\u0000$b"

        /**
         * Everything the dispatch will read for [routed] under [plan] — the reads it makes, in the same order of
         * need. On the preparation thread.
         */
        fun prefetch(routed: RoutedMutations, plan: PullPlan, core: TrideScene): CorePulls {
            val pulls = CorePulls()
            for (a in routed.generatorGeometry) {
                val id = a.id
                if ((a.trunkEpoch == null || plan.uploadedTrunk[id] != a.trunkEpoch) && !pulls.geometry.containsKey(id)) {
                    val g = core.generatedGeometry(id)
                    pulls.geometry[id] = g
                    if (g != null) MeshPacking.pack(g)?.let { pulls.packed[id] = it }
                }
                for (m in plan.modules[id].orEmpty()) {
                    val k = key(id, m.symbol)
                    if (!pulls.transforms.containsKey(k)) pulls.transforms[k] = core.generatorModuleTransforms(id, m.symbol)
                    if (k in plan.rowSymbols && !pulls.colors.containsKey(k)) pulls.colors[k] = core.generatorModuleColors(id, m.symbol)
                    if (m.datablock !in plan.registered && !pulls.datablocks.containsKey(m.datablock)) {
                        pulls.datablocks[m.datablock] = core.datablockMesh(m.datablock)
                    }
                }
            }
            for (id in routed.generatorFlow) {
                if (id in plan.pulsed && !pulls.flows.containsKey(id)) pulls.flows[id] = core.generatorFlow(id)
            }
            for (uid in routed.datablockGeometry) {
                if (!pulls.datablocks.containsKey(uid)) pulls.datablocks[uid] = core.datablockMesh(uid)
                for (n in plan.parametric) {
                    if (n.datablockUid == uid && !pulls.deformed.containsKey(n.nodeUid)) pulls.deformed[n.nodeUid] = core.deformedMesh(n.nodeUid)
                }
            }
            for (uid in routed.deformedGeometry) {
                if (!pulls.deformed.containsKey(uid)) pulls.deformed[uid] = core.deformedMesh(uid)
                for (n in plan.parametric) {
                    if (n.nodeUid == uid && !pulls.datablocks.containsKey(n.datablockUid)) pulls.datablocks[n.datablockUid] = core.datablockMesh(n.datablockUid)
                }
            }
            if (routed.environment.isNotEmpty()) {
                pulls.environment = core.environmentFrame()
                pulls.environmentRead = true
            }
            for (mm in routed.material) {
                val slug = mm.materialSlug ?: continue
                if (!pulls.materialParams.containsKey(slug)) pulls.materialParams[slug] = core.materialParams(slug)
                for (family in plan.graphFamilies[slug].orEmpty()) {
                    val k = key(slug, family)
                    if (!pulls.graphValues.containsKey(k)) pulls.graphValues[k] = core.materialGraphValues(slug, family)
                    if (slug in plan.slotSlugs && !pulls.graphs.containsKey(k)) pulls.graphs[k] = core.materialGraph(slug, family)
                }
            }
            return pulls
        }
    }
}

/** One tick prepared on the preparation thread — applying it on the main thread asks the core nothing it foresaw. */
internal class PreparedFrame(val dtMs: Float) {
    private val done = CountDownLatch(1)
    @Volatile private var finished = false

    // Written on the preparation thread before `finished`, read on the main thread after it.
    var result: TickResult? = null; private set
    var failure: Exception? = null; private set
    var routed: RoutedMutations? = null; private set
    var signals: List<SceneSignal> = emptyList(); private set
    var pulls: CorePulls = CorePulls(); private set
    /** Thread time of the tick and of the routing and reads, ns. */
    var tickNs: Long = 0L; private set
    var pullNs: Long = 0L; private set

    val isDone: Boolean get() = finished

    /** Block until the preparation thread finished it. */
    fun await() { done.await() }

    /**
     * The tick, its signals, its routing ([route]: `null` = nothing to draw into) and the reads [plan] foresees, on
     * the preparation thread.
     */
    fun prepare(core: TrideScene, inbox: SceneSignals, plan: PullPlan?, route: ((List<TrideMutation>) -> RoutedMutations)?) {
        try {
            val t0 = System.nanoTime()
            val tick = core.tick(dtMs)
            result = tick
            val t1 = System.nanoTime()
            tickNs = t1 - t0
            // The listeners fired during the tick, on this thread: they are this frame's.
            signals = inbox.drain()
            if (route != null && tick.mutations.isNotEmpty()) {
                val r = route(tick.mutations)
                routed = r
                if (plan != null) pulls = CorePulls.prefetch(r, plan, core)
            }
            pullNs = System.nanoTime() - t1
        } catch (e: Exception) {
            failure = e
            signals = inbox.drain()
        } finally {
            finished = true
            done.countDown()
        }
    }
}

/** What the background tick did (`TrideKitScene.preparationReport`). */
internal class PreparationCounters {
    var wakes = 0L
    var applied = 0L
    var unfinished = 0L
    var misses = 0L
    var tickNs = 0L
    var pullNs = 0L
    var applyNs = 0L
    var settleWaitNs = 0L
}
