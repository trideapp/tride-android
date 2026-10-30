/**
 * A music track the scene hears whole while the app plays it — made by `TrideKitScene.attachAudioTrack`.
 * The platform decodes the file (`MediaExtractor` + `MediaCodec`) on one thread while another hands it
 * to the runtime, and the scene gets all of it, so seeking is free; Media3 ExoPlayer plays it.
 *
 * Public API: `play` / `pause` / `seekTo(positionMs)`, `positionMs`, `durationMs`, `isPlaying`, `loop`,
 * `volume`, `muted`, `isAnalysed` / `onAnalysed`, `uri`. Internal: the scene's `SceneAudioSource`.
 *
 * Non-obvious constraints:
 *   - What the scene hears now = ExoPlayer's position, which is already the position at the speaker (it
 *     reads the platform's output timestamp, the output latency included); the gain = the volume, 0
 *     while muted, paused, buffering or ended.
 *   - The file's declared encoder delay is trimmed from the decoded start, as the player trims it, so
 *     the scene's audio time 0 is the player's position 0. At most two channels reach the scene.
 *   - The runtime takes a track in order only: `play()` need not wait for the whole track, but until the
 *     decoding reaches the playback position the scene hears silence there (never a stale frame).
 *   - Audio focus and "becoming noisy" (headphones out) are the player's: another app's sound pauses it.
 *   - Main thread, except the decoding and feeding threads; released with `detachAudio` / the scene's `close`.
 */
package studio.tride.kit

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import studio.tride.core.TrideAudioKind
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.max

public class TrideKitAudioTrack internal constructor(
    private val context: Context,
    /** The file the track plays: `file://`, `content://`, `android.resource://`, `asset:///`, `https://`. */
    public val uri: Uri,
    loop: Boolean,
) {
    private val player: ExoPlayer = ExoPlayer.Builder(context).build().also { p ->
        val music = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        p.setAudioAttributes(music, true)
        p.setHandleAudioBecomingNoisy(true)
        p.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
    }
    private val main = Handler(Looper.getMainLooper())
    private var decoder: TrackDecoder? = null
    private var released = false
    private var volumeValue = 1f
    private var mutedValue = false
    private var analysis: Result<Unit>? = null
    /** How far the scene has the track, in seconds (+∞ once whole): beyond it the scene hears silence. */
    @Volatile private var analysedUntilS = 0.0

    /** Start (or resume) playback; an ended track starts again from the beginning. */
    @MainThread
    public fun play() {
        if (released) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    @MainThread
    public fun pause() {
        if (!released) player.pause()
    }

    /** Jump to [positionMs] (playing or not). */
    @MainThread
    public fun seekTo(positionMs: Long) {
        if (!released) player.seekTo(max(0L, positionMs))
    }

    /** Playback position in milliseconds. */
    public val positionMs: Long
        @MainThread get() = if (released) 0L else player.currentPosition

    /** Length in milliseconds, `null` until the player knows it. */
    public val durationMs: Long?
        @MainThread get() = if (released) null else player.duration.takeIf { it != C.TIME_UNSET }

    /** Sounding now (playing, not buffering, not ended). */
    public val isPlaying: Boolean
        @MainThread get() = !released && player.isPlaying

    /** Start again at the end. */
    public var loop: Boolean
        @MainThread get() = !released && player.repeatMode == Player.REPEAT_MODE_ONE
        @MainThread set(value) {
            if (!released) player.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }

    /** Playback volume, 0…1; the scene hears it too. */
    public var volume: Float
        @MainThread get() = volumeValue
        @MainThread set(value) {
            if (value.isFinite()) volumeValue = value.coerceIn(0f, 1f)
            applyVolume()
        }

    /** Silent for the listener and the scene; [volume] is kept. */
    public var muted: Boolean
        @MainThread get() = mutedValue
        @MainThread set(value) {
            mutedValue = value
            applyVolume()
        }

    /**
     * The scene has the whole track (decoded and handed over). `play()` need not wait for it: until the
     * decoding reaches the playback position, the scene hears silence there.
     */
    public val isAnalysed: Boolean
        @MainThread get() = analysis?.isSuccess == true

    /**
     * Called on the main thread once the scene has the whole track (`null`) or the track could not be
     * decoded (the error; the scene reports it as a diagnostic too). Set after that, it is called at once.
     */
    public var onAnalysed: ((error: Exception?) -> Unit)? = null
        @MainThread set(value) {
            field = value
            analysis?.let { value?.invoke(it.exceptionOrNull() as? Exception) }
        }

    private fun applyVolume() {
        if (!released) player.volume = if (mutedValue) 0f else volumeValue
    }

    /** The scene's side: decoded PCM in, the heard time and gain out. */
    internal val source: SceneAudioSource = object : SceneAudioSource {
        override fun connect(sink: AudioPcmSink) {
            decoder?.cancel()
            analysis = null
            analysedUntilS = 0.0
            decoder = TrackDecoder(context, uri, sink, progress = { analysedUntilS = it }) { error ->
                main.post {
                    if (released) return@post
                    analysis = if (error == null) Result.success(Unit) else Result.failure(error)
                    onAnalysed?.invoke(error)
                }
            }.also { it.start() }
        }

        override fun disconnect(sink: AudioPcmSink) {
            decoder?.cancel()
            decoder = null
        }

        override fun heard(): Heard {
            if (released) return Heard(0.0, 0f)
            val time = player.currentPosition / 1000.0
            // Past what the scene has of the track, it would read a stale frame: silence until it arrives.
            val sounding = player.isPlaying && !mutedValue && time <= analysedUntilS
            return Heard(time, if (sounding) volumeValue else 0f)
        }

        override fun dispose() {
            if (released) return
            released = true
            decoder?.cancel()
            decoder = null
            player.release()
        }
    }
}

/**
 * Decodes a track with the platform's decoder on one thread and hands it to the runtime on another, so
 * the two overlap: `begin` once the format is known, `append` in one-second pieces from time 0 (in order:
 * the runtime takes a track no other way), `end` at the end of the file. [progress] says how far the
 * scene has the track. The codec gets every input buffer it has free, so its pipeline stays full, and —
 * for formats whose frames delimit themselves (mp3, PCM) — as many frames per buffer as fit: the codec's
 * cost is per buffer, so one frame a buffer made a 4-minute mp3 take ~9 s on the emulator, 64 KiB ~0.5 s.
 */
internal class TrackDecoder(
    private val context: Context,
    private val uri: Uri,
    private val sink: AudioPcmSink,
    private val progress: (untilS: Double) -> Unit,
    private val done: (Exception?) -> Unit,
) {
    @Volatile private var cancelled = false
    /** Decoded pieces on their way to the runtime; bounded, so decoding never runs far ahead of it. */
    private val pieces = ArrayBlockingQueue<Piece>(QUEUE_PIECES)
    private val decoder = Thread({ decodeAll() }, "tride-audio-decode").apply { isDaemon = true }
    private val feeder = Thread({ feedAll() }, "tride-audio-feed").apply { isDaemon = true }

    private sealed interface Piece {
        class Begin(val sampleRate: Int, val channels: Int) : Piece
        class Pcm(val startS: Double, val pcm: List<Float>, val endS: Double) : Piece
        class End(val decodeMs: Long, val codecMs: Long) : Piece
        class Failed(val error: Exception) : Piece
    }

    fun start() {
        feeder.start()
        decoder.start()
    }

    fun cancel() {
        cancelled = true
        decoder.interrupt()
        feeder.interrupt()
    }

    private fun decodeAll() {
        val started = System.nanoTime()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            open(extractor)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("no MIME type")
            // The player trims the file's encoder delay; so does this decode — the codec must not trim it again.
            val delay = if (format.containsKey(KEY_ENCODER_DELAY)) format.getInteger(KEY_ENCODER_DELAY) else 0
            if (format.containsKey(KEY_ENCODER_DELAY)) format.setInteger(KEY_ENCODER_DELAY, 0)
            if (format.containsKey(KEY_ENCODER_PADDING)) format.setInteger(KEY_ENCODER_PADDING, 0)
            // Several frames a buffer only where frames carry their own sync (MediaCodec allows several
            // access units of audio per buffer; a raw AAC or Opus packet has no boundary of its own).
            val batch = mime in BATCHED_MIMES
            if (batch && (!format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) || format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) < BATCH_INPUT_BYTES)) {
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BATCH_INPUT_BYTES)
            }
            val c = MediaCodec.createDecoderByType(mime).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()
            val codecNs = decode(extractor, c, delay.toLong(), batch)
            if (!cancelled) pieces.put(Piece.End((System.nanoTime() - started) / 1_000_000, codecNs / 1_000_000))
        } catch (_: InterruptedException) {
            // Cancelled while the runtime's side was full: nothing more to hand over.
        } catch (e: Exception) {
            if (!cancelled) {
                try { pieces.put(Piece.Failed(e)) } catch (_: InterruptedException) { }
            }
        } finally {
            // A codec in its error state refuses stop(); release() frees it all the same.
            try { codec?.stop() } catch (_: IllegalStateException) { }
            codec?.release()
            extractor.release()
        }
    }

    /** The runtime's side, in order: the format, every piece, the end — or the failure. */
    private fun feedAll() {
        val started = System.nanoTime()
        var runtimeNs = 0L
        try {
            while (!cancelled) {
                when (val p = pieces.take()) {
                    is Piece.Begin -> sink.begin(p.sampleRate, p.channels, TrideAudioKind.TRACK)
                    is Piece.Pcm -> {
                        val t = System.nanoTime()
                        sink.append(p.startS, p.pcm)
                        runtimeNs += System.nanoTime() - t
                        // A frame is ready once its whole window arrived.
                        progress(p.endS - READY_MARGIN_S)
                    }
                    is Piece.End -> {
                        sink.end()
                        progress(Double.POSITIVE_INFINITY)
                        Log.i(
                            "tride.kit",
                            "track ${uri.lastPathSegment}: whole in ${(System.nanoTime() - started) / 1_000_000} ms " +
                                "(decoding ${p.decodeMs} ms, codec ${p.codecMs} ms; runtime ${runtimeNs / 1_000_000} ms, overlapped)",
                        )
                        done(null)
                        return
                    }
                    is Piece.Failed -> {
                        sink.problem("decode", "the track $uri could not be decoded: ${p.error.message ?: p.error}")
                        done(p.error)
                        return
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Cancelled: the input was detached.
        }
    }

    private fun open(extractor: MediaExtractor) {
        if (uri.scheme == "asset") {
            // ExoPlayer's `asset:///path`; MediaExtractor reads it through the asset's file descriptor.
            context.assets.openFd(uri.path.orEmpty().removePrefix("/")).use { extractor.setDataSource(it) }
        } else {
            extractor.setDataSource(context, uri, null)
        }
    }

    /** Decode the whole file into pieces ([batch]: several frames per input buffer); returns the time in codec calls (ns). */
    private fun decode(extractor: MediaExtractor, codec: MediaCodec, delayFrames: Long, batch: Boolean): Long {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var format: Format? = null
        var trim = delayFrames
        var chunk = FloatArray(0)
        var filled = 0
        var fed = 0L
        var codecNs = 0L
        var largestSample = 0
        while (!cancelled) {
            val t = System.nanoTime()
            // Keep the codec busy: every input buffer it has free takes the next sample.
            var queued = false
            while (!inputDone) {
                val i = codec.dequeueInputBuffer(0)
                if (i < 0) break
                val buffer = codec.getInputBuffer(i) ?: throw IOException("no codec input buffer")
                val time = extractor.sampleTime
                var size = 0
                while (true) {
                    val n = extractor.readSampleData(buffer, size)
                    if (n < 0) break
                    size += n
                    largestSample = max(largestSample, n)
                    extractor.advance()
                    // The next frame must fit whole: twice the largest seen so far (a VBR frame varies).
                    if (!batch || buffer.capacity() - size < 2 * largestSample) break
                }
                if (size == 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(i, 0, size, time, 0)
                }
                queued = true
            }
            // One decoded buffer; wait for it only when this round gave the codec nothing.
            val o = codec.dequeueOutputBuffer(info, if (queued) 0L else TIMEOUT_US)
            codecNs += System.nanoTime() - t
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                format = begin(codec.outputFormat, format)
                if (chunk.isEmpty()) chunk = FloatArray(format.chunkFrames * format.keep)
                continue
            }
            if (o < 0) continue
            val f = format ?: begin(codec.outputFormat, null).also {
                format = it
                chunk = FloatArray(it.chunkFrames * it.keep)
            }
            val out = codec.getOutputBuffer(o)
            if (out != null && info.size > 0) {
                val bytes: ByteBuffer = out.duplicate().apply { position(info.offset); limit(info.offset + info.size) }
                val frames = info.size / f.frameBytes
                val skip = minOf(trim, frames.toLong()).toInt()
                trim -= skip
                bytes.position(bytes.position() + skip * f.frameBytes)
                while (bytes.remaining() >= f.frameBytes) {
                    val wrote = AudioPcm.toFloats(bytes, f.encoding, f.channels, f.keep, chunk, filled)
                    filled += wrote
                    bytes.position(bytes.position() + wrote * f.frameBytes)
                    if (filled == f.chunkFrames) {
                        // Boxed here, while the runtime's thread hands over the piece before.
                        pieces.put(Piece.Pcm(fed.toDouble() / f.sampleRate, boxed(chunk), (fed + filled).toDouble() / f.sampleRate))
                        fed += filled
                        filled = 0
                    }
                }
            }
            val r = System.nanoTime()
            codec.releaseOutputBuffer(o, false)
            codecNs += System.nanoTime() - r
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        val f = format
        if (!cancelled && f != null && filled > 0) {
            pieces.put(Piece.Pcm(fed.toDouble() / f.sampleRate, boxed(chunk, 0, filled * f.keep), (fed + filled).toDouble() / f.sampleRate))
        }
        return codecNs
    }

    private class Format(val sampleRate: Int, val channels: Int, val encoding: Int) {
        val keep = AudioPcm.keptChannels(channels)
        val frameBytes = AudioPcm.bytesPerSample(encoding) * channels
        /** One second a piece: fewer runtime calls, each a short hold of the scene's lock. */
        val chunkFrames = sampleRate
    }

    /** The decoder's output format; the input begins with it. A later change of rate or channels is refused. */
    private fun begin(output: MediaFormat, previous: Format?): Format {
        val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioPcm.PCM_16BIT
        val f = Format(output.getInteger(MediaFormat.KEY_SAMPLE_RATE), output.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding)
        if (f.frameBytes == 0 || f.sampleRate <= 0) throw IOException("decoder output $output is not PCM this kit reads")
        if (previous == null) {
            pieces.put(Piece.Begin(f.sampleRate, f.keep))
            return f
        }
        if (previous.sampleRate != f.sampleRate || previous.channels != f.channels || previous.encoding != f.encoding) {
            throw IOException("the decoder changed its format mid-track ($output)")
        }
        return previous
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val KEY_ENCODER_DELAY = "encoder-delay"
        const val KEY_ENCODER_PADDING = "encoder-padding"
        /** Pieces waiting for the runtime: a few seconds of sound. */
        const val QUEUE_PIECES = 4
        /** A frame at time t needs its 1024-sample window after t: count a piece's last 50 ms as not ready yet. */
        const val READY_MARGIN_S = 0.05
        /** Formats whose frames delimit themselves, so a buffer may carry several. */
        val BATCHED_MIMES = setOf("audio/mpeg", "audio/raw")
        /** An input buffer of ~100 mp3 frames (the codec's default is 8 KiB, ~10). */
        const val BATCH_INPUT_BYTES = 64 * 1024
    }
}
