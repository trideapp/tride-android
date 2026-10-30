/**
 * The platform half of an app picture: bytes or a `Bitmap` → `HostPixels` — at most 2048 px
 * on the long side, turned upright by its EXIF `Orientation`, straight-alpha RGBA8 in sRGB (the web decodes
 * with `premultiplyAlpha: 'none'`). Runs off the main thread; a failure is a `HostImageDecodeException`
 * whose message is the reason the scene hears.
 *
 * Public API (internal): `HostImageDecoder.decode(bytes)`, `HostImageDecoder.fromBitmap(bitmap)`.
 *
 * Non-obvious constraints:
 *   - `BitmapFactory` reads the bounds first and decodes at the largest power-of-two `inSampleSize` that keeps
 *     the long side ≥ 2048; one `Matrix` then scales exactly and turns. `BitmapFactory` applies no EXIF turn
 *     (all eight orientations checked on API 35, `HostImageTest`), so it comes from `androidx.exifinterface`;
 *     `ImageDecoder` does turn, but exists from API 28 only and hands HARDWARE bitmaps by default.
 *   - Software `ARGB_8888` only: a HARDWARE bitmap from the app is copied first (its pixels cannot be read).
 *   - Pixels are read with `getPixels`, which un-premultiplies: an untouched picture decodes straight; a scaled
 *     or turned one with alpha is filtered premultiplied (correct edges), costing precision at very low alpha.
 *   - Wide-gamut pictures (Display P3) are converted to sRGB by the decoder (API 26+).
 */
package studio.tride.kit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** A picture the host could not decode; [message] is the reason `imageFailed` carries. */
internal class HostImageDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal object HostImageDecoder {
    /** Encoded bytes (whatever `BitmapFactory` reads: JPEG, PNG, WebP, HEIF on Android 8+) → upright pixels. */
    fun decode(bytes: ByteArray, maxSide: Int = HostImageSize.MAX_SIDE): HostPixels {
        if (bytes.isEmpty()) throw HostImageDecodeException("the picture is empty (0 bytes)")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw HostImageDecodeException("not a picture this device decodes (${bytes.size} bytes; JPEG, PNG and WebP are read)")
        }
        val turn = ExifTurn.of(exifOrientation(bytes))
        val sample = HostImageSize.sampleSize(max(bounds.outWidth, bounds.outHeight), maxSide)
        val untouched = turn == ExifTurn.NONE && sample == 1 && max(bounds.outWidth, bounds.outHeight) <= maxSide
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            // Straight alpha when nothing filters the pixels; a scale or a turn filters premultiplied.
            inPremultiplied = !untouched
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            throw HostImageDecodeException("the picture is too large to decode (${bounds.outWidth}x${bounds.outHeight})", e)
        } ?: throw HostImageDecodeException("the picture could not be decoded (${bounds.outWidth}x${bounds.outHeight}, ${bounds.outMimeType ?: "unknown type"})")
        return upright(decoded, turn, maxSide)
    }

    /** An app's own `Bitmap` (already upright) → pixels at most [maxSide] on the long side; [bitmap] is left as it is. */
    fun fromBitmap(bitmap: Bitmap, maxSide: Int = HostImageSize.MAX_SIDE): HostPixels {
        if (bitmap.isRecycled) throw HostImageDecodeException("the bitmap is recycled")
        if (bitmap.width <= 0 || bitmap.height <= 0) throw HostImageDecodeException("the bitmap is empty (${bitmap.width}x${bitmap.height})")
        var software = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw HostImageDecodeException("the bitmap (${bitmap.config}) could not be read")
        return try {
            val scales = max(software.width, software.height) > maxSide
            // A Canvas (the scale) refuses straight alpha: such a bitmap is premultiplied first.
            if (scales && !software.isPremultiplied && software.hasAlpha()) {
                val colors = IntArray(software.width * software.height)
                software.getPixels(colors, 0, software.width, 0, 0, software.width, software.height)
                val premultiplied = Bitmap.createBitmap(colors, software.width, software.height, Bitmap.Config.ARGB_8888)
                if (software !== bitmap) software.recycle()
                software = premultiplied
            }
            upright(software, ExifTurn.NONE, maxSide, recycle = false)
        } finally {
            if (software !== bitmap) software.recycle()
        }
    }

    /** EXIF `Orientation` of [bytes]; upright when the picture carries none or it cannot be read. */
    private fun exifOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** [source] scaled to fit [maxSide] and turned by [turn], read out as straight RGBA8. */
    private fun upright(source: Bitmap, turn: ExifTurn, maxSide: Int, recycle: Boolean = true): HostPixels {
        val shownW = if (turn.swapsSides) source.height else source.width
        val shownH = if (turn.swapsSides) source.width else source.height
        val (w, h) = HostImageSize.fitWithin(shownW, shownH, maxSide)
        var out = source
        try {
            if (turn != ExifTurn.NONE || w != shownW || h != shownH) {
                val m = Matrix()
                if (turn.mirror) m.postScale(-1f, 1f)
                if (turn.degrees != 0) m.postRotate(turn.degrees.toFloat())
                m.postScale(w.toFloat() / shownW, h.toFloat() / shownH)
                out = try {
                    Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
                } catch (e: OutOfMemoryError) {
                    throw HostImageDecodeException("the picture is too large to scale (${source.width}x${source.height})", e)
                }
            }
            return readStraight(out, w, h)
        } finally {
            if (out !== source) out.recycle()
            if (recycle) source.recycle()
        }
    }

    /** The bitmap's pixels as straight RGBA8 of exactly `w × h` (a rounding pixel more or less is cropped / repeated). */
    private fun readStraight(bitmap: Bitmap, w: Int, h: Int): HostPixels {
        val rgba = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        val row = IntArray(w)
        val bytes = ByteArray(w * 4)
        val bw = bitmap.width
        val bh = bitmap.height
        for (y in 0 until h) {
            val sy = minOf(y, bh - 1)
            val n = minOf(w, bw)
            // getPixels hands non-premultiplied 0xAARRGGBB whatever the bitmap stores.
            bitmap.getPixels(row, 0, w, 0, sy, n, 1)
            for (x in n until w) row[x] = row[n - 1]
            for (x in 0 until w) {
                val c = row[x]
                val o = x * 4
                bytes[o] = (c shr 16).toByte()
                bytes[o + 1] = (c shr 8).toByte()
                bytes[o + 2] = c.toByte()
                bytes[o + 3] = (c ushr 24).toByte()
            }
            rgba.put(bytes)
        }
        rgba.rewind()
        return HostPixels(w, h, rgba)
    }
}
