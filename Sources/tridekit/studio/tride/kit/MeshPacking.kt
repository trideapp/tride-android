/**
 * The one packing rule of a mesh in the built-in vertex layout (positions, tangent frames, UV0, the HALF4 colour
 * slot, the pulse stream, indices, the box): pure — no Filament engine, no view, no bridge state — so the scene's
 * preparation thread packs a re-grown trunk and the main thread only uploads it (`setBufferAt`).
 *
 * Internal API:
 *   - `MeshPacking.pack(positions, normals, uvs, colors, tangents, flow, indices, lines)` → `PackedStreams`
 *     (`null` when the streams are not whole vertices / indices); `pack(geometry)` for a runtime trunk.
 *   - `direct`, `directOf`, `floatsOf`, `toHalf`, `packColors`, `tangentFrames`, `boundsOf` — the pieces, used by
 *     `FilamentGenerators` for the other layouts too.
 *
 * Non-obvious constraints:
 *   - Any thread. `SurfaceOrientation` (supplied tangents) is Filament's CPU utility, not an engine object.
 *   - A refused `SurfaceOrientation` falls back to the normals and says why in `tangentsRefused`; the upload
 *     reports it (the packing has no diagnostics of its own).
 *   - Every buffer is a fresh direct buffer: Filament keeps it until the driver consumed it.
 */
package studio.tride.kit

import com.google.android.filament.Box
import com.google.android.filament.SurfaceOrientation
import studio.tride.core.TrideGeneratedGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** One mesh's streams in the built-in layout, ready for `setBufferAt`. */
internal class PackedStreams(
    val vertexCount: Int,
    val indexBytes: Int,
    val lines: Boolean,
    val positions: ByteBuffer,
    val frames: ByteBuffer,
    val uvs: ByteBuffer,
    val colors: ByteBuffer,
    /** The pulse stream when the mesh carries one per vertex; `null` = none (a pulsed layout uploads zeros). */
    val flow: ByteBuffer?,
    val indices: ByteBuffer,
    val box: Box,
    /** Why `SurfaceOrientation` refused the supplied tangents (the frames then come from the normals); `null` = it did not. */
    val tangentsRefused: String?,
)

internal object MeshPacking {
    fun pack(g: TrideGeneratedGeometry): PackedStreams? =
        pack(g.positions, g.normals, g.uvs, g.colors, g.tangents, g.flow, g.indices, g.topology == "lines")

    fun pack(
        positions: ByteArray, normals: ByteArray, uvs: ByteArray, colors: ByteArray, tangents: ByteArray,
        flow: ByteArray, indices: ByteArray, lines: Boolean,
    ): PackedStreams? {
        if (positions.size % 12 != 0 || indices.size % 4 != 0) return null
        val vertexCount = positions.size / 12
        var refused: String? = null
        val frames = when {
            lines -> tangentFrames(ByteArray(0), vertexCount)
            normals.size == vertexCount * 12 && tangents.size == vertexCount * 16 ->
                tangentFramesFromTangents(normals, tangents, vertexCount) { refused = it }
            else -> tangentFrames(normals, vertexCount)
        }
        return PackedStreams(
            vertexCount = vertexCount,
            indexBytes = indices.size,
            lines = lines,
            positions = direct(positions),
            frames = frames,
            uvs = if (uvs.size == vertexCount * 8) direct(uvs) else ByteBuffer.allocateDirect(vertexCount * 8),
            colors = packColors(colors, vertexCount),
            flow = if (flow.size == vertexCount * 16) direct(flow) else null,
            indices = direct(indices),
            box = boundsOf(positions, vertexCount),
            tangentsRefused = refused,
        )
    }

    fun direct(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }

    /** IEEE 754 binary32 → binary16 (round to nearest even), clamped to the finite range. */
    fun toHalf(value: Float): Short {
        val f = if (value.isFinite()) value else 0f
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exponent = ((bits ushr 23) and 0xff) - 127 + 15
        val mantissa = bits and 0x7fffff
        if (exponent >= 0x1f) return (sign or 0x7bff).toShort()
        if (exponent <= 0) {
            if (exponent < -10) return sign.toShort()
            val m = (mantissa or 0x800000) ushr (1 - exponent)
            return (sign or ((m + 0x1000 + ((m ushr 13) and 1) - 1) ushr 13)).toShort()
        }
        val rounded = mantissa + 0xfff + ((mantissa ushr 13) and 1)
        if (rounded and 0x800000 != 0) return (if (exponent + 1 >= 0x1f) sign or 0x7bff else sign or ((exponent + 1) shl 10)).toShort()
        return (sign or (exponent shl 10) or (rounded ushr 13)).toShort()
    }

    /**
     * `count` little-endian `f32` of a runtime buffer as one bulk copy — per-element `ByteBuffer`
     * reads cost ~100 ms on a 41 k-vertex mesh on a 2021 flagship phone, far over a frame.
     */
    fun floatsOf(bytes: ByteArray, count: Int): FloatArray {
        val out = FloatArray(count)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out, 0, count)
        return out
    }

    fun directOf(values: FloatArray): ByteBuffer {
        val out = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        out.asFloatBuffer().put(values)
        return out
    }

    /** RGBA `f32` per vertex → the HALF4 slot; white when the mesh carries no colours. */
    fun packColors(colors: ByteArray, vertexCount: Int): ByteBuffer {
        val halves = ShortArray(vertexCount * 4)
        if (colors.size == vertexCount * 16) {
            val src = floatsOf(colors, vertexCount * 4)
            for (i in halves.indices) halves[i] = toHalf(src[i].coerceIn(0f, 1f))
        } else {
            halves.fill(toHalf(1f))
        }
        val out = ByteBuffer.allocateDirect(vertexCount * 8).order(ByteOrder.nativeOrder())
        out.asShortBuffer().put(halves)
        return out
    }

    /**
     * Tangent frames from normals, packed like Filament's `packTangentFrame` (`mat3f{t, cross(n, t), n}`):
     * the lit materials need the quaternion attribute, not normals; `lines` and normal-less meshes
     * carry identity frames.
     */
    fun tangentFrames(normals: ByteArray, vertexCount: Int): ByteBuffer {
        val frames = FloatArray(vertexCount * 4)
        val src = if (normals.size == vertexCount * 12) floatsOf(normals, vertexCount * 3) else null
        for (i in 0 until vertexCount) {
            if (src == null) { frames[i * 4 + 3] = 1f; continue }
            var nx = src[i * 3]; var ny = src[i * 3 + 1]; var nz = src[i * 3 + 2]
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-12f) { nx /= len; ny /= len; nz /= len } else { nx = 0f; ny = 1f; nz = 0f }
            // t = normalize(cross(helper, n)); b = cross(n, t)
            val hx: Float; val hy: Float; val hz: Float
            if (abs(ny) < 0.9f) { hx = 0f; hy = 1f; hz = 0f } else { hx = 1f; hy = 0f; hz = 0f }
            var tx = hy * nz - hz * ny; var ty = hz * nx - hx * nz; var tz = hx * ny - hy * nx
            val tl = sqrt(tx * tx + ty * ty + tz * tz).takeIf { it > 1e-12f } ?: 1f
            tx /= tl; ty /= tl; tz /= tl
            val bx = ny * tz - nz * ty; val by = nz * tx - nx * tz; val bz = nx * ty - ny * tx
            // Rotation matrix with columns (t, b, n) → quaternion, w ≥ 0, then negated: Filament's
            // packing flags `dot(cross(t, n), b) < 0` with the sign (our b = cross(n, t) always does).
            val m00 = tx; val m01 = bx; val m02 = nx
            val m10 = ty; val m11 = by; val m12 = ny
            val m20 = tz; val m21 = bz; val m22 = nz
            val trace = m00 + m11 + m22
            var qx: Float; var qy: Float; var qz: Float; var qw: Float
            if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                qw = 0.25f * s; qx = (m21 - m12) / s; qy = (m02 - m20) / s; qz = (m10 - m01) / s
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                qw = (m21 - m12) / s; qx = 0.25f * s; qy = (m01 + m10) / s; qz = (m02 + m20) / s
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                qw = (m02 - m20) / s; qx = (m01 + m10) / s; qy = 0.25f * s; qz = (m12 + m21) / s
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                qw = (m10 - m01) / s; qx = (m02 + m20) / s; qy = (m12 + m21) / s; qz = 0.25f * s
            }
            val ql = sqrt(qx * qx + qy * qy + qz * qz + qw * qw).takeIf { it > 1e-12f } ?: 1f
            qx /= ql; qy /= ql; qz /= ql; qw /= ql
            if (qw < 0f) { qx = -qx; qy = -qy; qz = -qz; qw = -qw }
            val bias = 1f / 32767f
            if (qw < bias) { qw = bias; val k = sqrt(1f - bias * bias); qx *= k; qy *= k; qz *= k }
            frames[i * 4] = -qx; frames[i * 4 + 1] = -qy; frames[i * 4 + 2] = -qz; frames[i * 4 + 3] = -qw
        }
        return directOf(frames)
    }

    fun boundsOf(positions: ByteArray, vertexCount: Int): Box {
        if (vertexCount == 0) return Box(0f, 0f, 0f, 0.5f, 0.5f, 0.5f)
        val src = floatsOf(positions, vertexCount * 3)
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until vertexCount) for (k in 0..2) {
            val v = src[i * 3 + k]
            if (v < mn[k]) mn[k] = v
            if (v > mx[k]) mx[k] = v
        }
        return Box((mn[0] + mx[0]) * 0.5f, (mn[1] + mx[1]) * 0.5f, (mn[2] + mx[2]) * 0.5f,
            max((mx[0] - mn[0]) * 0.5f, 1e-4f), max((mx[1] - mn[1]) * 0.5f, 1e-4f), max((mx[2] - mn[2]) * 0.5f, 1e-4f))
    }

    /**
     * The runtime's MikkTSpace frame of a normal-mapped mesh, packed by Filament's own
     * `SurfaceOrientation` (supplied tangents) — the shader's bitangent is glTF's `cross(n, t) * w`, as
     * gltfio and the other hosts frame theirs. A refused build falls back to the normals ([refused] hears why).
     */
    private fun tangentFramesFromTangents(normals: ByteArray, tangents: ByteArray, vertexCount: Int, refused: (String) -> Unit): ByteBuffer {
        val orientation = runCatching {
            SurfaceOrientation.Builder()
                .vertexCount(vertexCount)
                .normals(direct(normals))
                .tangents(direct(tangents))
                .build()
        }.getOrElse { e ->
            refused("a mesh's tangent frame was refused by SurfaceOrientation (${e.message}) — its normal map is lit from the normals alone")
            return tangentFrames(normals, vertexCount)
        }
        val out = ByteBuffer.allocateDirect(vertexCount * 16).order(ByteOrder.nativeOrder())
        try {
            orientation.getQuatsAsFloat(out)
        } finally {
            orientation.destroy()
        }
        return out
    }
}
