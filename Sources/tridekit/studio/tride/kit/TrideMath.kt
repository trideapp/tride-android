/**
 * The bridge's 3D math (internal): quaternion rotation of a vector, the decomposition of Filament's column-major
 * matrices. Euler ↔ quaternion is the core's (`CoreRotation`), a local matrix gltfio's (`LocalMatrix`), never
 * composed here.
 *
 * Contents: `rotateByQuat`, `normalize3`, `decomposeTRS`, `eulerXYZToMat3` (IBL rotation), `isEmptyBox`.
 *
 * Non-obvious constraints:
 *   - Quaternions are `[x, y, z, w]` everywhere; Filament's `float[16]` is column-major, and so is the
 *     `float[9]` for `IndirectLight.setRotation`.
 *   - `decomposeTRS` assumes no shear (glTF node transforms have none).
 */
package studio.tride.kit

import kotlin.math.sqrt

internal object TrideMath {
    /** `q · v · q⁻¹` for a unit quaternion `[x, y, z, w]`. */
    fun rotateByQuat(q: DoubleArray, v: DoubleArray): DoubleArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val vx = v[0]; val vy = v[1]; val vz = v[2]
        // t = 2 * cross(q.xyz, v)
        val tx = 2 * (qy * vz - qz * vy)
        val ty = 2 * (qz * vx - qx * vz)
        val tz = 2 * (qx * vy - qy * vx)
        return doubleArrayOf(
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx),
        )
    }

    fun normalize3(v: DoubleArray): DoubleArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-12) return doubleArrayOf(0.0, 0.0, -1.0)
        return doubleArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    /** Column-major 3×3 rotation of an XYZ Euler triple (for `IndirectLight.setRotation`). */
    fun eulerXYZToMat3(e: DoubleArray): FloatArray {
        val q = CoreRotation.quatOfEulerXYZ(e)
        val m = LocalMatrix.of(doubleArrayOf(0.0, 0.0, 0.0), q, doubleArrayOf(1.0, 1.0, 1.0))
        return floatArrayOf(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10])
    }

    /** Translation / rotation `[x, y, z, w]` / scale of a column-major TRS matrix (no shear assumed). */
    fun decomposeTRS(m: FloatArray): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val sx = sqrt((m[0] * m[0] + m[1] * m[1] + m[2] * m[2]).toDouble())
        val sy = sqrt((m[4] * m[4] + m[5] * m[5] + m[6] * m[6]).toDouble())
        val sz = sqrt((m[8] * m[8] + m[9] * m[9] + m[10] * m[10]).toDouble())
        val t = doubleArrayOf(m[12].toDouble(), m[13].toDouble(), m[14].toDouble())
        val s = doubleArrayOf(sx, sy, sz)
        val r00 = m[0] / sx; val r10 = m[1] / sx; val r20 = m[2] / sx
        val r01 = m[4] / sy; val r11 = m[5] / sy; val r21 = m[6] / sy
        val r02 = m[8] / sz; val r12 = m[9] / sz; val r22 = m[10] / sz
        val trace = r00 + r11 + r22
        val q = DoubleArray(4)
        if (trace > 0) {
            val sq = sqrt((trace + 1.0)) * 2
            q[3] = 0.25 * sq
            q[0] = (r21 - r12) / sq
            q[1] = (r02 - r20) / sq
            q[2] = (r10 - r01) / sq
        } else if (r00 > r11 && r00 > r22) {
            val sq = sqrt(1.0 + r00 - r11 - r22) * 2
            q[3] = (r21 - r12) / sq
            q[0] = 0.25 * sq
            q[1] = (r01 + r10) / sq
            q[2] = (r02 + r20) / sq
        } else if (r11 > r22) {
            val sq = sqrt(1.0 + r11 - r00 - r22) * 2
            q[3] = (r02 - r20) / sq
            q[0] = (r01 + r10) / sq
            q[1] = 0.25 * sq
            q[2] = (r12 + r21) / sq
        } else {
            val sq = sqrt(1.0 + r22 - r00 - r11) * 2
            q[3] = (r10 - r01) / sq
            q[0] = (r02 + r20) / sq
            q[1] = (r12 + r21) / sq
            q[2] = 0.25 * sq
        }
        return Triple(t, q, s)
    }

    /**
     * An empty `Aabb` (Filament's default: min = FLT_MAX, max = lowest) reaches Java as a negative
     * half-extent; a NaN / infinite one is no box either.
     */
    fun isEmptyBox(center: FloatArray, halfExtent: FloatArray): Boolean =
        !(center + halfExtent).all { it.isFinite() } || halfExtent.any { it < 0f }
}
