/**
 * The pure pieces of sound into the scene: decoded PCM bytes → the interleaved floats the runtime takes,
 * the input-name check, and the clock that says what a player sounds now. No Android, no Media3, no FFI.
 *
 * Public API (internal): `AudioPcm.toFloats(bytes, encoding, channels, keep, out, outFrame)`,
 * `AudioPcm.keptChannels(channels)`, the `PCM_*` encodings, `requireAudioInput(input)`, `AudibleClock`.
 *
 * Non-obvious constraints:
 *   - Encodings are Android's `AudioFormat` / Media3 `C.ENCODING_*` values (the two agree); bytes are
 *     little-endian. 16-bit is `v / 32768` — the same floats every host feeds for the same samples.
 *   - The scene hears at most two channels (left, right): the first two of the source are kept.
 *   - `AudibleClock` extrapolates the last position a player reported only while it plays, and never
 *     further than `MAX_EXTRAPOLATION_NS` (a stalled player does not run ahead).
 */
package studio.tride.kit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

internal object AudioPcm {
    const val PCM_16BIT = 2
    const val PCM_8BIT = 3
    const val PCM_FLOAT = 4
    const val PCM_24BIT_PACKED = 21
    const val PCM_32BIT = 22

    /** Bytes per sample of [encoding], or 0 for an encoding this kit does not read. */
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        PCM_8BIT -> 1
        PCM_16BIT -> 2
        PCM_24BIT_PACKED -> 3
        PCM_FLOAT, PCM_32BIT -> 4
        else -> 0
    }

    /** The channels the scene hears of a source with [channels]: left and right at most. */
    fun keptChannels(channels: Int): Int = min(max(channels, 0), 2)

    /**
     * The whole frames of [bytes] (from its position, not consumed) as floats into [out] from frame
     * [outFrame]: [keep] channels of each frame of [channels]. Returns the frames written (bounded by
     * [out]'s room).
     */
    fun toFloats(bytes: ByteBuffer, encoding: Int, channels: Int, keep: Int, out: FloatArray, outFrame: Int): Int {
        val size = bytesPerSample(encoding)
        if (size == 0 || channels <= 0 || keep <= 0) return 0
        val b = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = size * channels
        val frames = min(b.remaining() / frameBytes, out.size / keep - outFrame)
        var at = b.position()
        var o = outFrame * keep
        for (f in 0 until frames) {
            for (c in 0 until keep) out[o + c] = sample(b, at + c * size, encoding)
            o += keep
            at += frameBytes
        }
        return max(frames, 0)
    }

    private fun sample(b: ByteBuffer, at: Int, encoding: Int): Float = when (encoding) {
        PCM_16BIT -> b.getShort(at) / 32768f
        PCM_FLOAT -> b.getFloat(at)
        PCM_8BIT -> ((b.get(at).toInt() and 0xff) - 128) / 128f
        PCM_24BIT_PACKED -> {
            val v = (b.get(at).toInt() and 0xff) or ((b.get(at + 1).toInt() and 0xff) shl 8) or (b.get(at + 2).toInt() shl 16)
            v / 8388608f
        }
        PCM_32BIT -> (b.getInt(at) / 2147483648.0).toFloat()
        else -> 0f
    }
}

/** The input names a scene listens to are slugs: `audio.<input>.<probe>`. */
private val AUDIO_INPUT = Regex("^[a-z][a-z0-9_]*$")

internal fun requireAudioInput(input: String) {
    require(AUDIO_INPUT.matches(input)) { "audio input '$input' is not a scene input name (a-z, 0-9 and _, starting with a letter)" }
}

/**
 * What a player sounds now, from the positions it reports (any thread) and the time they were taken:
 * the last position, moved on by the time since while it plays.
 */
internal class AudibleClock {
    private var positionUs = Long.MIN_VALUE
    private var atNs = 0L
    private var speed = 1f
    private var playing = false

    @Synchronized fun position(positionUs: Long, nowNs: Long) { this.positionUs = positionUs; atNs = nowNs }
    @Synchronized fun playing(playing: Boolean, nowNs: Long) {
        if (this.playing && !playing && positionUs != Long.MIN_VALUE) positionUs = positionAt(nowNs)
        this.playing = playing
        atNs = nowNs
    }
    @Synchronized fun speed(speed: Float) { this.speed = speed }
    @Synchronized fun forget() { positionUs = Long.MIN_VALUE; playing = false }

    /** The position heard at [nowNs] in µs, or `null` before the player reported one. */
    @Synchronized fun heardUs(nowNs: Long): Long? = if (positionUs == Long.MIN_VALUE) null else positionAt(nowNs)

    @Synchronized fun isPlaying(): Boolean = playing && positionUs != Long.MIN_VALUE

    private fun positionAt(nowNs: Long): Long {
        if (!playing) return positionUs
        val elapsedNs = (nowNs - atNs).coerceIn(0L, MAX_EXTRAPOLATION_NS)
        return positionUs + (elapsedNs / 1000.0 * speed).toLong()
    }

    companion object {
        /** A player reports its position every ~10 ms while it plays; 100 ms without one is a stall. */
        const val MAX_EXTRAPOLATION_NS = 100_000_000L
    }
}
