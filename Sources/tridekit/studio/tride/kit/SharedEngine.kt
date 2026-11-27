/**
 * `SharedEngine` — the one Filament engine of the process per backend, and what its views share:
 * gltfio's ubershader provider, the kit's built-in material packages, the scenes' node material packages and
 * the lighting maps' cube with its spherical harmonics.
 *
 * Public API (internal):
 *   - `SharedEngine.acquire(backend)` — the engine of that backend (`AUTO` resolved by the caller), made on
 *     first use; a backend whose builder throws falls back to OpenGL once and for good. `release()` — the view
 *     let go; `views` counts them.
 *   - `builtIn(name, bytes)` — a kit package by name, built once and kept while the engine lives.
 *   - `acquirePackage(bytes)` / `releasePackage(material)` — a scene's package by the SHA-256 of its bytes,
 *     destroyed when its last user lets go (its instances are gone by then: Filament refuses otherwise).
 *   - `acquireIblMap(ktx)` / `releaseIblMap(map)` — a lighting map's cube and SH, the same way. Each view builds
 *     its own `IndirectLight` over them: its intensity and rotation are the view's.
 *   - `watch(queue)` / `unwatch(queue)` — a view's "own frame queued" probe; `retire(renderer)` — a Renderer let
 *     go of, destroyed later (below); `afterQueue(action)` — [action] on the main thread once what is queued now has
 *     executed (a TextureView's SurfaceTexture released behind its swap chain).
 *   - `SharedEngine.destroyUnused()` — every engine without views destroyed (a background memory trim, `MemoryTrim`);
 *     the next view makes a new one.
 *   - Checks: `retiredRenderers`, `renderers` (made and not destroyed, retired ones included), `swapChains`,
 *     `isDestroyed`.
 *
 * Non-obvious constraints:
 *   - The engine lives on the thread that made it (Filament: `FEngine::shutdown` asserts it; the engine is not
 *     thread safe) — the main thread, where every Filament call of the kit runs; another thread is refused.
 *   - With no views the engine stays, with its provider and built-ins, until a background memory trim; scene
 *     packages and maps do not pile up — each goes with its last user.
 *   - Destroying it: the queue is waited for (the app is in the background), what waited behind it runs, then
 *     the retired Renderers, the built-ins and the provider go before the engine.
 *   - Filament's `destroyRenderer` waits for the engine's whole queue (`FRenderer::terminate`), a neighbour's
 *     compile included. A retired Renderer is destroyed on the main thread once a fence put behind the queue has
 *     signalled and no watched view has a frame queued: what is left to wait for then is no frame.
 *   - Vulkan frees what was destroyed only when the driver ticks: after a swap chain or Renderer goes, an idle
 *     Renderer of the engine skips a few frames so that nothing given back waits for the next drawn frame.
 *   - A lost GPU context is not seen: Filament 1.77.1 on Android gives no signal.
 */
package studio.tride.kit

import android.os.Handler
import android.os.Looper
import com.google.android.filament.Engine
import com.google.android.filament.Fence
import com.google.android.filament.Filament
import com.google.android.filament.Material
import com.google.android.filament.Renderer
import com.google.android.filament.Texture
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.IdentityHashMap

private const val TAG = TrideLog.BRIDGE
/** Filament's 1D colour grading LUT — Standard comes out byte for byte (the 3D LUT loses the top). */
private const val ONE_DIMENSIONAL_LUT = "engine.color_grading.use_1d_lut"

internal class SharedEngine private constructor(val engine: Engine, val backend: Engine.Backend) {
    /** A lighting map's prefiltered cube and its 9 × 3 spherical harmonics. */
    class IblMap(val cube: Texture, val sh: FloatArray)

    private class Counted<T>(val key: String, val value: T, var users: Int)

    private val owner: Thread = Thread.currentThread()

    /** Destroyed by a memory trim: nothing of it may be used again. */
    var isDestroyed = false
        private set

    /**
     * The engine draws with the emulator's GL (ANGLE over SwiftShader, or the host-GPU translator), decided by the
     * backend the engine was made on (the OpenGL fallback included), not by the device: the emulator's Vulkan draws
     * shadow maps, its GL draws any frame with one black.
     */
    val emulatorGl: Boolean = backend == Engine.Backend.OPENGL && isEmulator()

    /** gltfio's ubershader provider: the record's standard-form instances are made through it. */
    val ubershaders: UbershaderProvider = UbershaderProvider(engine)

    /** The views on this engine now. */
    var views = 0
        private set

    private val builtIns = HashMap<String, Material>()
    private val packages = HashMap<String, Counted<Material>>()
    private val packageOf = IdentityHashMap<Material, Counted<Material>>()
    private val maps = HashMap<String, Counted<IblMap>>()
    private val mapOf = IdentityHashMap<IblMap, Counted<IblMap>>()

    /** Renderers made and not destroyed yet (retired ones included), swap chains alive (checks). */
    var renderers = 0
        private set
    var swapChains = 0
        private set

    fun rendererMade() { onOwner(); renderers++ }
    fun swapChainMade() { onOwner(); swapChains++ }
    fun swapChainGone() { onOwner(); swapChains--; collectGarbage() }

    /** The view let go of the engine. */
    fun release() {
        onOwner()
        check(views > 0) { "SharedEngine released more often than acquired" }
        views--
    }

    /**
     * Kit package [name] (`tride/materials/<name>.filamat` of the library, one per process), built on first ask;
     * `null` when [bytes] has none. A package Filament refuses throws, and is asked again next time.
     */
    fun builtIn(name: String, bytes: () -> ByteArray?): Material? {
        onOwner()
        builtIns[name]?.let { return it }
        val payload = bytes()?.takeIf { it.isNotEmpty() } ?: return null
        return Material.Builder().payload(direct(payload), payload.size).build(engine).also { builtIns[name] = it }
    }

    /** A scene's package, shared by its bytes; throws when Filament refuses it. Each call is one user. */
    fun acquirePackage(bytes: ByteArray): Material {
        onOwner()
        val key = sha256(bytes)
        packages[key]?.let { it.users++; return it.value }
        val material = Material.Builder().payload(direct(bytes), bytes.size).build(engine)
        val entry = Counted(key, material, 1)
        packages[key] = entry
        packageOf[material] = entry
        return material
    }

    /** One user of [material] let go — its instances destroyed already; the last one destroys it. */
    fun releasePackage(material: Material) {
        onOwner()
        val entry = packageOf[material] ?: return
        if (--entry.users > 0) return
        packages.remove(entry.key)
        packageOf.remove(material)
        engine.destroyMaterial(material)
    }

    /** A lighting map's cube and SH, shared by its KTX bytes; `null` when the KTX carries no SH. Each call is one user. */
    fun acquireIblMap(ktx: ByteArray): IblMap? {
        onOwner()
        val key = sha256(ktx)
        maps[key]?.let { it.users++; return it.value }
        val sh = KTX1Loader.getSphericalHarmonics(direct(ktx)) ?: return null
        val map = IblMap(KTX1Loader.createTexture(engine, direct(ktx)), sh)
        val entry = Counted(key, map, 1)
        maps[key] = entry
        mapOf[map] = entry
        return map
    }

    /** One user of [map] let go — its `IndirectLight` destroyed already; the last one destroys the cube. */
    fun releaseIblMap(map: IblMap) {
        onOwner()
        val entry = mapOf[map] ?: return
        if (--entry.users > 0) return
        maps.remove(entry.key)
        mapOf.remove(map)
        engine.destroyTexture(map.cube)
    }

    /** A view's probe: its own newest frame may not be executed yet. */
    fun interface FrameQueue { fun ownFrameQueued(): Boolean }

    private val queues = ArrayList<FrameQueue>()
    private val retired = ArrayList<Renderer>()
    private var behindQueue: Fence? = null
    private val sweeper = Handler(Looper.getMainLooper())
    private val sweep = Runnable { sweepRetired() }
    /** What waits behind the queue ([afterQueue]): run at once if the engine goes first. */
    private class Behind(val fence: Fence, val action: () -> Unit, var poll: Runnable? = null)
    private val behind = ArrayList<Behind>()

    fun watch(queue: FrameQueue) { onOwner(); queues += queue }
    fun unwatch(queue: FrameQueue) { onOwner(); queues.remove(queue) }

    /** [renderer] draws no more; destroyed once destroying it waits for no frame (see the header). */
    fun retire(renderer: Renderer) {
        onOwner()
        retired += renderer
        sweeper.removeCallbacks(sweep)
        sweeper.post(sweep)
    }

    /** [action] on the main thread once everything queued so far has executed; nothing waits meanwhile. */
    fun afterQueue(action: () -> Unit) {
        onOwner()
        // The engine is gone, and its driver with it: nothing is queued.
        if (isDestroyed) { action(); return }
        val entry = Behind(engine.createFence(), action)
        engine.flush()
        val poll = object : Runnable {
            override fun run() {
                if (entry.fence.wait(Fence.Mode.DONT_FLUSH, 0) != Fence.FenceStatus.CONDITION_SATISFIED) {
                    sweeper.postDelayed(this, SWEEP_MS)
                    return
                }
                behind.remove(entry)
                engine.destroyFence(entry.fence)
                action()
            }
        }
        entry.poll = poll
        behind += entry
        sweeper.post(poll)
    }

    /** Renderers retired and not destroyed yet (checks). */
    internal val retiredRenderers: Int get() = retired.size

    private fun sweepRetired() {
        if (retired.isEmpty()) return
        val fence = behindQueue ?: engine.createFence().also { behindQueue = it; engine.flush() }
        if (fence.wait(Fence.Mode.DONT_FLUSH, 0) == Fence.FenceStatus.CONDITION_SATISFIED) {
            engine.destroyFence(fence)
            behindQueue = null
            if (queues.none { it.ownFrameQueued() }) {
                destroyRetired()
                return
            }
        }
        sweeper.postDelayed(sweep, SWEEP_MS)
    }

    private fun destroyRetired() {
        for (r in retired) engine.destroyRenderer(r)
        renderers -= retired.size
        retired.clear()
        collectGarbage()
    }

    /**
     * Ticks of the driver still owed after something was destroyed. Vulkan frees a destroyed texture or swap chain
     * only in its periodic garbage collection, run at a frame's end or every third tick of a Renderer: with no view
     * drawing (every view far, the app hidden) the memory a view gave back stayed taken. An idle Renderer of the
     * engine's own skips frames to tick it (no swap chain, nothing drawn); GL frees at once and the ticks cost it
     * nothing.
     */
    private var gcTicksLeft = 0
    private var janitor: Renderer? = null
    private val tick = object : Runnable {
        override fun run() {
            if (isDestroyed || gcTicksLeft <= 0) return
            // A Renderer waiting to be destroyed: its targets go then, the ticks after it.
            if (retired.isEmpty()) {
                (janitor ?: engine.createRenderer().also { janitor = it }).skipFrame(0)
                gcTicksLeft--
            }
            if (gcTicksLeft > 0) sweeper.postDelayed(this, SWEEP_MS)
        }
    }

    private fun collectGarbage() {
        if (gcTicksLeft == 0) sweeper.postDelayed(tick, SWEEP_MS)
        gcTicksLeft = GC_TICKS
    }

    /** No view holds it: the engine and what it kept go (see the header); `false` while a view holds it. */
    private fun destroy(): Boolean {
        onOwner()
        if (isDestroyed || views > 0) return false
        val t0 = System.nanoTime()
        // The app is in the background: the wait costs no frame.
        engine.flushAndWait()
        for (b in behind) {
            b.poll?.let { sweeper.removeCallbacks(it) }
            engine.destroyFence(b.fence)
            b.action()
        }
        behind.clear()
        sweeper.removeCallbacks(sweep)
        behindQueue?.let { engine.destroyFence(it) }
        behindQueue = null
        destroyRetired()
        sweeper.removeCallbacks(tick)
        gcTicksLeft = 0
        janitor?.let { engine.destroyRenderer(it) }
        janitor = null
        for (m in builtIns.values) engine.destroyMaterial(m)
        builtIns.clear()
        // No view: no scene package or map is held (each goes with its last user).
        for (p in packages.values) engine.destroyMaterial(p.value)
        for (m in maps.values) engine.destroyTexture(m.value.cube)
        packages.clear(); packageOf.clear(); maps.clear(); mapOf.clear()
        ubershaders.destroyMaterials()
        ubershaders.destroy()
        engine.destroy()
        isDestroyed = true
        TrideLog.info(TAG) { "Filament engine on $backend destroyed, no view on it (%.1f ms)".format((System.nanoTime() - t0) / 1e6) }
        return true
    }

    /** Scene packages and maps held now (checks: they go with their last user). */
    internal val sceneResourceCount: Int get() = packages.size + maps.size

    private fun onOwner() =
        check(Thread.currentThread() === owner) { "the Filament engine is used off the thread that made it (${owner.name})" }

    companion object {
        /** How often a retired Renderer looks for the queue to be free of frames. */
        private const val SWEEP_MS = 16L
        /** Driver ticks after a destroy: Vulkan collects every third, and a destroyed object waits a few collections. */
        private const val GC_TICKS = 12
        private val initialised = lazy {
            Filament.init()
            Gltfio.init()
            Utils.init()
            true
        }
        /** By the backend in use. */
        private val engines = HashMap<Engine.Backend, SharedEngine>()
        /** The backend asked for → the one it gave (a Vulkan builder that threw is not tried again). */
        private val resolved = HashMap<Engine.Backend, Engine.Backend>()

        /** The process's engine on [preferred] (or its OpenGL fallback), one view more on it. */
        @Synchronized
        fun acquire(preferred: Engine.Backend): SharedEngine {
            check(initialised.value)
            val known = resolved[preferred]?.let { engines[it] }
            val shared = known ?: create(preferred).also {
                engines[it.backend] = it
                resolved[preferred] = it.backend
            }
            shared.onOwner()
            shared.views++
            return shared
        }

        /** The engines alive now (checks). */
        internal val live: Collection<SharedEngine> @Synchronized get() = engines.values.toList()

        /** Every engine no view holds is destroyed (how many); the next view on its backend makes a new one. */
        @Synchronized
        fun destroyUnused(): Int {
            val gone = engines.values.filter { it.destroy() }
            for (e in gone) engines.remove(e.backend)
            return gone.size
        }

        private fun build(backend: Engine.Backend): Engine =
            Engine.Builder().backend(backend)
                // Up to five suns light the scene, the brightest casts the shadow (Filament's rule).
                .config(Engine.Config().apply { enableMultipleDirectionalLights = true })
                .feature("backend.disable_parallel_shader_compile", true)
                .feature(ONE_DIMENSIONAL_LUT, true)
                .build()

        private fun create(preferred: Engine.Backend): SharedEngine {
            var created: Engine? = null
            var used = preferred
            try {
                created = build(preferred)
            } catch (e: Throwable) {
                TrideLog.warning(TAG) { "Engine($preferred) failed: ${e.message}" }
            }
            if (created == null && preferred != Engine.Backend.OPENGL) {
                TrideLog.warning(TAG) { "Engine($preferred) unavailable — falling back to OPENGL" }
                used = Engine.Backend.OPENGL
                engines[used]?.let { return it }
                created = build(used)
            }
            val engine = created ?: error("Filament Engine could not be created on any backend")
            TrideLog.info(TAG) { "Filament ${BuildConfig.FILAMENT_VERSION} engine on ${engine.backend}, shared by the process's views" }
            return SharedEngine(engine, used)
        }

        private fun direct(bytes: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
