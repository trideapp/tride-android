/**
 * The bridge's 3D math (internal): quaternion rotation of a vector, the decomposition of Filament's column-major
 * matrices. Euler ↔ quaternion is the core's (`CoreRotation`), a local matrix gltfio's (`LocalMatrix`), never
 * composed here.
 *
 * Contents: `rotateByQuat`, `normalize3`, `decomposeTRS`, `rigid` (a world with its scaling ignored), `eulerXYZToMat3`
 * (IBL rotation).
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
     * Column-major world [m] with its scaling ignored (glTF 2.0 §3.10.2, a camera's view): the same origin, the view
     * axis along the node's -Z (its third column), up as near the node's +Y as a right angle to it allows, unit
     * length — a rigid matrix, as Filament wants a camera's. A matrix already rigid (within 1e-6) comes back as it
     * is; `null` for one whose axes are degenerate (a zero scale).
     */
    fun rigid(m: FloatArray): FloatArray? {
        val c0 = DoubleArray(3) { m[it].toDouble() }
        val c1 = DoubleArray(3) { m[4 + it].toDouble() }
        val c2 = DoubleArray(3) { m[8 + it].toDouble() }
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        val tol = 1e-6
        val unit = listOf(c0, c1, c2).all { kotlin.math.abs(dot(it, it) - 1) < tol }
        val square = kotlin.math.abs(dot(c0, c1)) < tol && kotlin.math.abs(dot(c1, c2)) < tol && kotlin.math.abs(dot(c0, c2)) < tol
        if (unit && square && dot(cross(c0, c1), c2) > 0) return m.copyOf()
        val zLen = sqrt(dot(c2, c2))
        if (zLen < 1e-12) return null
        val z = DoubleArray(3) { c2[it] / zLen }
        val x0 = cross(c1, z)
        val xLen = sqrt(dot(x0, x0))
        if (xLen < 1e-12) return null
        val x = DoubleArray(3) { x0[it] / xLen }
        val y = cross(z, x)
        return floatArrayOf(
            x[0].toFloat(), x[1].toFloat(), x[2].toFloat(), 0f,
            y[0].toFloat(), y[1].toFloat(), y[2].toFloat(), 0f,
            z[0].toFloat(), z[1].toFloat(), z[2].toFloat(), 0f,
            m[12], m[13], m[14], 1f,
        )
    }
}
