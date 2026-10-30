/**
 * Lets a scene hear what a Media3 ExoPlayer plays — a file, a playlist or an endless stream such as
 * internet radio. The tap sits in the player's audio sink: it copies the sound on its way to the speaker
 * (before it sounds) with the moment it will be heard, and reads what is heard now from the same sink.
 * Attach it with `TrideKitScene.attachAudioTap`.
 *
 * Public API: `TrideKitAudioTap()`, `renderersFactory(context)` (a player without audio processors of
 * its own), `audioSink(builder, processors)` (a host's own `buildAudioSink`). Internal: the scene's source.
 *
 * Non-obvious constraints:
 *   - Time is the sink's: a piece is stamped with the media position the sink gives its first frame (the
 *     first buffer after a flush, counted on by frames, re-synced like the sink on a jump over 200 ms);
 *     heard = the sink's own position (the output latency included), moved on while it plays.
 *   - Gain = the volume the player hands the sink (its volume, mute, ducking); 0 while paused or stopped.
 *   - Processors before the tap must not change the timing (no resampling); it passes the sound through.
 *   - One tap per player, one scene input per tap. The sink's work runs on the player's playback thread.
 */
package studio.tride.kit

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import studio.tride.core.TrideAudioKind
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(markerClass = [UnstableApi::class])
public class TrideKitAudioTap {
    /** Where the copied sound goes: the scene input attached now. */
    @Volatile private var sink: AudioPcmSink? = null
    @Volatile private var volume = 1f
    private val clock = AudibleClock()

    // ---- playback thread ----
    /** The format the processor runs: what the scene input begins with. */
    @Volatile private var format: Pair<Int, Int>? = null
    private var anchorUs = 0L
    private var anchorPending = true
    /** Frames the processor saw since the anchor. */
    private var framesSinceAnchor = 0L
    /** The sink's input since the anchor (before trimming), for the 200 ms jump check. */
    private var inputFrameBytes = 0
    private var inputRate = 0
    private var encoderDelay = 0
    private var submittedFrames = 0L

    /** A RenderersFactory whose audio sink carries this tap — for a player with no audio processors of its own. */
    @UnstableApi
    public fun renderersFactory(context: Context): RenderersFactory = TapRenderersFactory(context, this)

    /**
     * The audio sink to return from your own `RenderersFactory.buildAudioSink`: [builder] built with your
     * [processors] and the tap after them.
     */
    @UnstableApi
    public fun audioSink(builder: DefaultAudioSink.Builder, vararg processors: AudioProcessor): AudioSink =
        TapSink(builder.setAudioProcessors(arrayOf(*processors, TapProcessor(this))).build(), this)

    // ---------------------------------------------------------------------------------------------
    // Playback thread: the sink and the processor report here
    // ---------------------------------------------------------------------------------------------

    internal fun sinkConfigured(inputFormat: Format) {
        val pcm = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType
        inputFrameBytes = if (pcm) Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount) else 0
        inputRate = inputFormat.sampleRate
        encoderDelay = inputFormat.encoderDelay
        if (!pcm) sink?.problem("format", "the player sends ${inputFormat.sampleMimeType} past its processors (passthrough or offload): the scene hears nothing")
    }

    /** A new input buffer of the sink, its first frame at [presentationTimeUs]. */
    internal fun inputBuffer(presentationTimeUs: Long, bytes: Int) {
        if (anchorPending) {
            anchorPending = false
            anchorUs = max(0L, presentationTimeUs)
            framesSinceAnchor = 0
            submittedFrames = 0
        } else if (inputRate > 0) {
            // The sink re-syncs its start on a jump over 200 ms; so does the tap.
            val expected = anchorUs + (submittedFrames - min(encoderDelay.toLong(), submittedFrames)) * 1_000_000L / inputRate
            if (abs(presentationTimeUs - expected) > RESYNC_US) anchorUs += presentationTimeUs - expected
        }
        if (inputFrameBytes > 0) submittedFrames += bytes / inputFrameBytes
    }

    /** The sink flushed or reset: the next buffer starts the timeline again. */
    internal fun restart() {
        anchorPending = true
        clock.forget()
    }

    internal fun playing(playing: Boolean) = clock.playing(playing, System.nanoTime())
    internal fun position(positionUs: Long) = clock.position(positionUs, System.nanoTime())
    internal fun speed(speed: Float) = clock.speed(speed)
    internal fun volume(volume: Float) { this.volume = volume }

    /** The processor runs this format now. */
    internal fun processorFormat(sampleRate: Int, channels: Int) {
        val next = sampleRate to channels
        if (format == next) return
        format = next
        sink?.begin(sampleRate, channels, TrideAudioKind.STREAM)
    }

    internal fun unsupported(encoding: Int) {
        sink?.problem("format", "the player's sound reaches the tap as encoding $encoding, not 16-bit or float PCM: the scene hears nothing")
    }

    /** The stream time (seconds) of the next frame the processor copies. */
    internal fun nextFrameTimeS(sampleRate: Int): Double = anchorUs / 1e6 + framesSinceAnchor.toDouble() / sampleRate

    internal fun framesCopied(frames: Int) {
        framesSinceAnchor += frames
    }

    /** Every piece the tap copies (start time, frames), on the playback thread — for a test. */
    @Volatile internal var onPiece: ((startTimeS: Double, frames: Int) -> Unit)? = null

    internal fun append(startTimeS: Double, pcm: List<Float>) {
        onPiece?.invoke(startTimeS, pcm.size / max(1, format?.second ?: 1))
        sink?.append(startTimeS, pcm)
    }

    // ---------------------------------------------------------------------------------------------
    // The scene's side
    // ---------------------------------------------------------------------------------------------

    internal val source: SceneAudioSource = object : SceneAudioSource {
        override fun connect(sink: AudioPcmSink) {
            this@TrideKitAudioTap.sink = sink
            format?.let { (rate, channels) -> sink.begin(rate, channels, TrideAudioKind.STREAM) }
        }

        override fun disconnect(sink: AudioPcmSink) {
            if (this@TrideKitAudioTap.sink === sink) this@TrideKitAudioTap.sink = null
        }

        override fun heard(): Heard {
            val now = System.nanoTime()
            val us = clock.heardUs(now) ?: return Heard(0.0, 0f)
            return Heard(us / 1e6, if (clock.isPlaying()) volume else 0f)
        }

        /** The host owns the player and the tap: nothing to release. */
        override fun dispose() = Unit
    }

    private companion object {
        const val RESYNC_US = 200_000L
    }
}

/** The sound on its way to the speaker, copied for the scene in pieces of [CHUNK_FRAMES]; passed through as is. */
@OptIn(markerClass = [UnstableApi::class])
internal class TapProcessor(private val tap: TrideKitAudioTap) : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var keep = 0
    private var sampleRate = 0
    private var chunk = FloatArray(0)
    private var filled = 0
    private var chunkStart = 0.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            tap.unsupported(inputAudioFormat.encoding)
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    /** A flush makes the configured format the running one. */
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        emit()
        val f = inputAudioFormat
        encoding = f.encoding
        channels = f.channelCount
        sampleRate = f.sampleRate
        keep = AudioPcm.keptChannels(channels)
        if (chunk.size != CHUNK_FRAMES * keep) chunk = FloatArray(CHUNK_FRAMES * keep)
        if (keep > 0 && sampleRate > 0) tap.processorFormat(sampleRate, keep)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val frameBytes = AudioPcm.bytesPerSample(encoding) * channels
        if (frameBytes > 0 && keep > 0) {
            val view = inputBuffer.duplicate()
            while (view.remaining() >= frameBytes) {
                if (filled == 0) chunkStart = tap.nextFrameTimeS(sampleRate)
                val wrote = AudioPcm.toFloats(view, encoding, channels, keep, chunk, filled)
                tap.framesCopied(wrote)
                filled += wrote
                view.position(view.position() + wrote * frameBytes)
                if (filled == CHUNK_FRAMES) emit()
            }
        }
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }

    override fun onQueueEndOfStream() = emit()

    override fun onReset() {
        filled = 0
        encoding = C.ENCODING_INVALID
    }

    private fun emit() {
        if (filled == 0) return
        tap.append(chunkStart, boxed(chunk, 0, filled * keep))
        filled = 0
    }

    private companion object {
        /** ≈ 21 ms at 48 kHz: the sink is a few hundred ms ahead of the speaker. */
        const val CHUNK_FRAMES = 1024
    }
}

/** The player's audio sink with the tap's eyes on it: buffer times, position, play state, volume. */
@OptIn(markerClass = [UnstableApi::class])
internal class TapSink(sink: AudioSink, private val tap: TrideKitAudioTap) : ForwardingAudioSink(sink) {
    /** The buffer being handed over (a partly taken buffer comes again). */
    private var current: ByteBuffer? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        tap.sinkConfigured(inputFormat)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (buffer !== current) {
            current = buffer
            tap.inputBuffer(presentationTimeUs, buffer.remaining())
        }
        val taken = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (taken) current = null
        return taken
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position != AudioSink.CURRENT_POSITION_NOT_SET) tap.position(position)
        return position
    }

    override fun play() {
        super.play()
        tap.playing(true)
    }

    override fun pause() {
        super.pause()
        tap.playing(false)
    }

    override fun flush() {
        super.flush()
        current = null
        tap.playing(false)
        tap.restart()
    }

    override fun reset() {
        super.reset()
        current = null
        tap.playing(false)
        tap.restart()
    }

    override fun setVolume(volume: Float) {
        super.setVolume(volume)
        tap.volume(volume)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        super.setPlaybackParameters(playbackParameters)
        tap.speed(playbackParameters.speed)
    }
}

@OptIn(markerClass = [UnstableApi::class])
private class TapRenderersFactory(context: Context, private val tap: TrideKitAudioTap) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink =
        tap.audioSink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters),
        )
}
