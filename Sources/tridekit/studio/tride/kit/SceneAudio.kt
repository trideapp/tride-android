/**
 * The sound a scene hears: the host's audio sources by input name, fed to the runtime through its audio
 * contract (`beginAudio` / `appendAudio` / `endAudio` / `resetAudio` / `setAudioTime`) and timed before
 * every tick. The analysis is the runtime's — nothing here looks at the samples.
 *
 * Public API (internal): `SceneAudioSource` (what a host plays: it feeds an `AudioPcmSink`, says what it
 * sounds now), `AudioPcmSink`, `AudioRuntime` (the runtime calls; `coreAudioRuntime(scene)`),
 * `SceneAudioInputs` — `attach` / `detach` / `hear` / `names` / `close`.
 *
 * Non-obvious constraints:
 *   - A source feeds its sink from its own thread (a decoder, a player's playback thread): the sink calls
 *     the runtime there, under the input's lock, so a detached input never receives PCM after its reset.
 *   - `hear` (main thread, before each tick) gives every begun input its audible time and gain; an input
 *     not begun yet stays silent (the runtime's probes read 0).
 *   - A failing runtime call is a problem said once per input and call, never a throw into the tick.
 */
package studio.tride.kit

import studio.tride.core.TrideAudioKind
import studio.tride.core.TrideScene
import kotlin.math.max

/** What sounds now: the audible time in the source's audio time (seconds) and the gain (0 = silent). */
internal data class Heard(val timeS: Double, val gain: Float)

/** Where a source's PCM goes: one runtime input. Callable from any thread. */
internal interface AudioPcmSink {
    /** The format of what follows; again only when it changes (the input starts over). */
    fun begin(sampleRate: Int, channels: Int, kind: TrideAudioKind)
    /**
     * Interleaved PCM whose first frame sounds at [startTimeS] of the source's audio time — as the list the
     * runtime's binding takes, so a source can box the samples on its own thread (see `boxed`).
     */
    fun append(startTimeS: Double, pcm: List<Float>)
    /** A track is complete: silence follows its tail. */
    fun end()
    /** Say something about this input once per [key]. */
    fun problem(key: String, message: String)
}

/** A sound a host plays and the scene hears. */
internal interface SceneAudioSource {
    /** Start feeding [sink]: a track decodes now, a stream as it plays. Main thread. */
    fun connect(sink: AudioPcmSink)
    /** Stop feeding [sink]. Main thread. */
    fun disconnect(sink: AudioPcmSink)
    /** What sounds now. Main thread. */
    fun heard(): Heard
    /** Release what the source holds (a track the scene made stops). Main thread. */
    fun dispose()
}

/** The runtime's audio contract. */
internal interface AudioRuntime {
    fun beginAudio(input: String, sampleRate: Int, channels: Int, kind: TrideAudioKind)
    fun appendAudio(input: String, startTimeS: Double, pcm: List<Float>)
    fun endAudio(input: String)
    fun resetAudio(input: String)
    fun setAudioTime(input: String, audibleTimeS: Double, gain: Float)
}

internal fun coreAudioRuntime(scene: TrideScene): AudioRuntime = object : AudioRuntime {
    override fun beginAudio(input: String, sampleRate: Int, channels: Int, kind: TrideAudioKind) =
        scene.beginAudio(input, sampleRate.toUInt(), channels.toUShort(), kind)
    override fun appendAudio(input: String, startTimeS: Double, pcm: List<Float>) = scene.appendAudio(input, startTimeS, pcm)
    override fun endAudio(input: String) = scene.endAudio(input)
    override fun resetAudio(input: String) = scene.resetAudio(input)
    override fun setAudioTime(input: String, audibleTimeS: Double, gain: Float) = scene.setAudioTime(input, audibleTimeS, gain)
}

/**
 * [count] floats of [pcm] from [from] as the list the runtime's binding takes, each boxed once here. The
 * binding reads a list twice (its size, then its values): a view over a `FloatArray` boxes every float
 * on each read, so a 4-minute stereo track cost ~25 % more on the thread that calls the runtime.
 */
internal fun boxed(pcm: FloatArray, from: Int = 0, count: Int = pcm.size - from): List<Float> {
    val out = ArrayList<Float>(count)
    for (i in from until from + count) out.add(pcm[i])
    return out
}

/** A problem of [input]: [key] dedupes (per input), [message] is said. Any thread. */
internal typealias AudioProblem = (input: String, key: String, message: String) -> Unit

internal class SceneAudioInputs(private val runtime: AudioRuntime, private val report: AudioProblem) {
    private class Entry(val input: String, val source: SceneAudioSource, val owned: Boolean) {
        /** Guarded by `this`: no runtime call of this entry after `live` turned false. */
        var live = true
        /** The runtime began the input (an input never begun is not timed). */
        @Volatile var begun = false
        lateinit var sink: AudioPcmSink
    }

    private val inputs = LinkedHashMap<String, Entry>()
    /** What each input was told last (`hear`), for a test to replay. */
    private val lastHeard = HashMap<String, Heard>()

    /** The inputs attached now. */
    fun names(): List<String> = inputs.keys.toList()

    /** The audible time and gain [input] got before the last tick, or `null`. */
    fun lastHeard(input: String): Heard? = lastHeard[input]

    /** The scene hears [source] as [input]; a source already there is detached first. */
    fun attach(input: String, source: SceneAudioSource, owned: Boolean) {
        requireAudioInput(input)
        detach(input)
        val entry = Entry(input, source, owned)
        entry.sink = sinkOf(entry)
        inputs[input] = entry
        source.connect(entry.sink)
    }

    /** The scene stops hearing [input]: it reads silence; a source the scene made is disposed. */
    fun detach(input: String) {
        val entry = inputs.remove(input) ?: return
        lastHeard.remove(input)
        entry.source.disconnect(entry.sink)
        synchronized(entry) {
            entry.live = false
            if (entry.begun) call(entry, "reset") { runtime.resetAudio(input) }
        }
        if (entry.owned) entry.source.dispose()
    }

    /** Before each tick: what each input sounds like now. */
    fun hear() {
        for (entry in inputs.values) {
            if (!entry.begun) continue
            val h = entry.source.heard()
            val time = if (h.timeS.isFinite()) max(0.0, h.timeS) else 0.0
            val gain = if (h.gain.isFinite()) max(0f, h.gain) else 0f
            call(entry, "time") { runtime.setAudioTime(entry.input, time, gain) }
            lastHeard[entry.input] = Heard(time, gain)
        }
    }

    /** Detach everything (the scene closes). */
    fun close() {
        for (input in inputs.keys.toList()) detach(input)
    }

    private fun sinkOf(entry: Entry): AudioPcmSink = object : AudioPcmSink {
        override fun begin(sampleRate: Int, channels: Int, kind: TrideAudioKind) {
            synchronized(entry) {
                if (entry.live) entry.begun = call(entry, "begin") { runtime.beginAudio(entry.input, sampleRate, channels, kind) }
            }
        }

        override fun append(startTimeS: Double, pcm: List<Float>) {
            synchronized(entry) {
                if (entry.live && entry.begun) call(entry, "append") { runtime.appendAudio(entry.input, startTimeS, pcm) }
            }
        }

        override fun end() {
            synchronized(entry) {
                if (entry.live && entry.begun) call(entry, "end") { runtime.endAudio(entry.input) }
            }
        }

        override fun problem(key: String, message: String) {
            report(entry.input, key, message)
        }
    }

    /** One runtime call; a throw is a problem said once per input and call. */
    private inline fun call(entry: Entry, what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Exception) {
        report(entry.input, what, "$what failed: ${e.message ?: e}")
        false
    }
}
