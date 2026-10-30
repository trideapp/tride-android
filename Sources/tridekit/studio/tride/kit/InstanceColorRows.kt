/**
 * The instance rows of one module batch: the core's `generatorModuleColors` — little-endian
 * binary16 `R G B value`, 8 bytes per instance in the order of the transforms — cut to the batch's
 * slice and padded to its texture's row capacity, ready for the 1 × capacity RGBA16F `instanceColors`
 * texture.
 *
 * Public API: `InstanceColorRows.slice(colors, first, count, capacity)`, `HALFS_PER_ROW`, `HALF_ONE`.
 *
 * Non-obvious constraints:
 *   - A row the core did not send (no bytes, or fewer than the batch's count) stays white with value 1
 *     (`0x3C00` ×4) — the look of a clone nothing coloured; rows past `count` are the same.
 *   - Only whole rows are copied; a trailing partial row is ignored.
 */
package studio.tride.kit

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object InstanceColorRows {
    /** Halves per instance: `R G B value`. */
    const val HALFS_PER_ROW = 4
    private const val BYTES_PER_ROW = HALFS_PER_ROW * 2
    /** binary16 `1.0`. */
    const val HALF_ONE: Short = 0x3C00

    /** Rows `first until first + count` of [colors] in `capacity` rows, the rest `HALF_ONE`. */
    fun slice(colors: ByteArray?, first: Int, count: Int, capacity: Int): ShortArray {
        val out = ShortArray(capacity * HALFS_PER_ROW).apply { fill(HALF_ONE) }
        if (colors == null || first < 0) return out
        val available = colors.size / BYTES_PER_ROW - first
        val rows = minOf(count, capacity, available)
        if (rows <= 0) return out
        ByteBuffer.wrap(colors, first * BYTES_PER_ROW, rows * BYTES_PER_ROW).slice()
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, rows * HALFS_PER_ROW)
        return out
    }
}
