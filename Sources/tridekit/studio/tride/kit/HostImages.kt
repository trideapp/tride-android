/**
 * The app's pictures on the Android host: the one store of decoded pixels
 * by `(input, revision)`, the "latest request wins" rule per input, which revisions the store keeps, and the
 * size arithmetic of the decode (sample size, the exact fit, the EXIF turn). Pure Kotlin, no Android types —
 * the JVM tests cover it; `HostImageDecoder` does the platform half.
 *
 * Public API (internal):
 *   - `HostPixels` — straight-alpha RGBA8 in sRGB, rows from the top; `HostPictureStore` — `put` / `get` /
 *     `retain` / `names` / `byteCount`; `keptRevisions(status, revision, shownWidth, bound)`.
 *   - `HostImageRequests` — `begin(name)` → token, `isLatest(name, token)`.
 *   - `HostImageSize` — `MAX_SIDE`, `sampleSize`, `fitWithin`; `ExifTurn.of(orientation)`.
 *
 * Non-obvious constraints:
 *   - The core issues the revision when `setImage` is called and cannot tell a stale decode: the host drops
 *     a decode a newer request of the same input overtook (`HostImageRequests`).
 *   - Pixels stay while the input shows them (a new view builds its slot textures from the store) and while
 *     a slot texture still binds them; the next refresh after a replacement or `empty` / `failed` frees them.
 */
package studio.tride.kit

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** A decoded picture: `width × height` straight-alpha RGBA8 pixels in sRGB, rows from the top (a direct buffer). */
internal class HostPixels(val width: Int, val height: Int, val rgba: ByteBuffer) {
    init {
        require(width > 0 && height > 0) { "a picture has a size: ${width}x$height" }
        require(rgba.capacity() >= width * height * 4) { "${rgba.capacity()} bytes for ${width}x$height" }
    }
}

/** The one store of the app's pictures, by image input and revision (R-OneLookupPath). Main thread. */
internal class HostPictureStore {
    private val byInput = HashMap<String, HashMap<Long, HostPixels>>()

    fun put(name: String, revision: Long, pixels: HostPixels) {
        byInput.getOrPut(name) { HashMap() }[revision] = pixels
    }

    fun get(name: String, revision: Long): HostPixels? = byInput[name]?.get(revision)

    /** Keep only [keep] of input [name]'s revisions; the count freed. */
    fun retain(name: String, keep: Set<Long>): Int {
        val revisions = byInput[name] ?: return 0
        val before = revisions.size
        revisions.keys.retainAll(keep)
        if (revisions.isEmpty()) byInput.remove(name)
        return before - revisions.size
    }

    fun names(): Set<String> = byInput.keys.toSet()

    fun revisions(name: String): Set<Long> = byInput[name]?.keys?.toSet() ?: emptySet()

    /** Bytes of pixels held now. */
    fun byteCount(): Long = byInput.values.sumOf { r -> r.values.sumOf { it.width.toLong() * it.height * 4 } }

    fun clear() = byInput.clear()

    companion object {
        /**
         * The revisions of one input the store keeps: the input's last picture while it shows one (`loaded`, or
         * `loading` a replacement — [shownWidth] > 0) and every revision a slot texture still binds ([bound]).
         */
        fun keptRevisions(status: String?, revision: Long, shownWidth: Int, bound: Set<Long>): Set<Long> {
            val shows = (status == "loaded" || status == "loading") && shownWidth > 0 && revision > 0
            return if (shows) bound + revision else bound
        }
    }
}

/** Per image input, which request is the latest: an older decode that lands after a newer request is dropped. */
internal class HostImageRequests {
    private val latest = HashMap<String, Long>()
    private var next = 0L

    fun begin(name: String): Long = (++next).also { latest[name] = it }

    fun isLatest(name: String, token: Long): Boolean = latest[name] == token
}

/** The size arithmetic of the decode: at most [MAX_SIDE] px on the long side, proportions kept. */
internal object HostImageSize {
    /** The host contract's ceiling, the core's limit for `setImage`. */
    const val MAX_SIDE = 2048

    /**
     * The decoder's `inSampleSize` for a picture whose long side is [longSide]: the largest power of two that
     * keeps the decoded long side at [max] or above — the exact scale to [max] follows on the decoded pixels.
     */
    fun sampleSize(longSide: Int, max: Int = MAX_SIDE): Int {
        var sample = 1
        while (longSide / (sample * 2) >= max) sample *= 2
        return sample
    }

    /** `width × height` scaled to fit [max] on the long side (unchanged when it fits), each side at least 1. */
    fun fitWithin(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= max) return width to height
        val scale = max.toDouble() / long
        val w = if (width >= height) max else max(1, (width * scale).roundToInt())
        val h = if (height > width) max else max(1, (height * scale).roundToInt())
        return w to h
    }
}

/**
 * The turn EXIF `Orientation` asks for to show the stored pixels upright: rotate clockwise by [degrees], after
 * mirroring left–right when [mirror]. [swapsSides] — the shown picture is the stored one on its side.
 */
internal data class ExifTurn(val degrees: Int, val mirror: Boolean) {
    val swapsSides: Boolean get() = degrees == 90 || degrees == 270

    companion object {
        val NONE = ExifTurn(0, false)

        /** EXIF `Orientation` 1–8 (TIFF 6.0 / CIPA DC-008); anything else is upright. */
        fun of(orientation: Int): ExifTurn = when (orientation) {
            2 -> ExifTurn(0, true)
            3 -> ExifTurn(180, false)
            4 -> ExifTurn(180, true)
            5 -> ExifTurn(270, true)
            6 -> ExifTurn(90, false)
            7 -> ExifTurn(90, true)
            8 -> ExifTurn(270, false)
            else -> NONE
        }
    }
}
