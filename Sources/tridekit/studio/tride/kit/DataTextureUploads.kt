/**
 * Data-texture uploads paced by drawn frames: a tick stages the newest bytes of a texture, the next frame
 * Filament draws hands them to its upload; a frame it skips (`Renderer.beginFrame` false) uploads nothing,
 * and the next tick's bytes overwrite the staged ones (the newest wins). A buffer comes back through its
 * descriptor's callback and is filled again.
 *
 * Public API (internal):
 *   - `DataTextureUploads(upload)` — `upload(key, buffer, release)` issues the upload; `release` runs once
 *     Filament no longer needs `buffer`.
 *   - `stage(key, bytes, fill)` — `fill` writes `bytes` bytes into a buffer (position 0, limit `bytes`).
 *   - `flush()` — every staged upload, once, in staging order; `forget(key)` — before its texture is destroyed.
 *   - `staged` / `inFlight(key)` / `inFlightMax` / `buffersMade` / `stages` — tests.
 *
 * Non-obvious constraints:
 *   - Filament holds a descriptor's buffer (a JNI global ref; ART keeps a direct buffer in the Java heap)
 *     until its driver thread has run the upload. The command is a few bytes, so the command stream's own
 *     backpressure never trips: a stalled driver (SwiftShader drawing a first frame) kept one buffer per tick
 *     per texture before, until the heap ran out.
 *   - `release` runs where the engine runs its callbacks (`purge`, inside `Engine.flush` / `beginFrame`).
 */
package studio.tride.kit

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class DataTextureUploads<K : Any>(private val upload: (K, ByteBuffer, Runnable) -> Unit) {
    private class Slot {
        var staged: ByteBuffer? = null
        val free = ArrayDeque<ByteBuffer>()
        var inFlight = 0
        var forgotten = false
    }

    private val slots = LinkedHashMap<K, Slot>()
    private val lock = Any()

    /** Textures with bytes waiting for a drawn frame. */
    val staged: Int get() = synchronized(lock) { slots.values.count { it.staged != null } }

    /** Buffers of [key] Filament still holds. */
    fun inFlight(key: K): Int = synchronized(lock) { slots[key]?.inFlight ?: 0 }

    /** The most buffers one key has in Filament's hands now. */
    val inFlightMax: Int get() = synchronized(lock) { slots.values.maxOfOrNull { it.inFlight } ?: 0 }

    /** Buffers allocated so far; the rest were reused. */
    var buffersMade = 0
        private set

    /** `stage` calls so far. */
    var stages = 0
        private set

    fun stage(key: K, bytes: Int, fill: (ByteBuffer) -> Unit) {
        val buffer = synchronized(lock) {
            stages += 1
            val slot = slots.getOrPut(key) { Slot() }
            val reused = slot.staged ?: slot.free.removeFirstOrNull()
            slot.staged = null
            (reused?.takeIf { it.capacity() == bytes } ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { buffersMade += 1 })
                .also { slot.staged = it }
        }
        buffer.clear()
        fill(buffer)
        buffer.position(0).limit(bytes)
    }

    fun flush() {
        val due = synchronized(lock) {
            slots.entries.mapNotNull { (key, slot) ->
                slot.staged?.let { buffer -> slot.staged = null; slot.inFlight += 1; Triple(key, slot, buffer) }
            }
        }
        for ((key, slot, buffer) in due) upload(key, buffer, Runnable { release(slot, buffer) })
    }

    fun forget(key: K) {
        synchronized(lock) { slots.remove(key)?.let { it.forgotten = true; it.staged = null; it.free.clear() } }
    }

    private fun release(slot: Slot, buffer: ByteBuffer) {
        synchronized(lock) {
            slot.inFlight -= 1
            // Two spare buffers cover a driver one frame behind; more would only hold memory.
            if (!slot.forgotten && slot.free.size < 2) slot.free.addLast(buffer)
        }
    }
}

private val ZEROS = ByteArray(4096)

/** Zeros from the buffer's position to its limit. */
internal fun ByteBuffer.zeroRest() {
    while (hasRemaining()) put(ZEROS, 0, minOf(remaining(), ZEROS.size))
}
