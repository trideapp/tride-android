/**
 * Euler XYZ ↔ unit quaternion through the core (`eulerToQuat` / `quatToEuler`: the runtime's one composition
 * `Qx · Qy · Qz` and its decomposition) — a node's per-axis rotation lanes seeded from its rest pose and
 * composed back, a light record's direction. Never composed here: on a gimbal pole (Y = ±90°, where only
 * X ± Z is defined) and next to it only the core's split makes the hosts agree.
 *
 * Public API (internal): `CoreRotation.quatOfEulerXYZ(euler)` / `CoreRotation.eulerXYZOf(quat)` — radians by
 * axis `[x, y, z]`, quaternions `[x, y, z, w]`.
 *
 * Non-obvious constraints:
 *   - The core answers empty only for an order or a length it does not know — a TrideKit built against
 *     another core: said once, the identity / zero angles drawn.
 */
package studio.tride.kit

import android.util.Log
import studio.tride.core.eulerToQuat
import studio.tride.core.quatToEuler

internal object CoreRotation {
    private const val XYZ = "xyz"
    @Volatile private var warned = false

    private fun refused(what: String) {
        if (warned) return
        warned = true
        Log.e("tride.kit", "the core refused $what in the xyz order — this TrideKit was built against another core")
    }

    fun quatOfEulerXYZ(euler: DoubleArray): DoubleArray {
        val q = eulerToQuat(euler.toList(), XYZ)
        if (q.size == 4) return q.toDoubleArray()
        refused("eulerToQuat")
        return doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    }

    fun eulerXYZOf(quat: DoubleArray): DoubleArray {
        val e = quatToEuler(quat.toList(), XYZ)
        if (e.size == 3) return e.toDoubleArray()
        refused("quatToEuler")
        return doubleArrayOf(0.0, 0.0, 0.0)
    }
}
