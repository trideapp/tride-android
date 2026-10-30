/**
 * The bridge's own framing camera — the pose it draws from when the scene has no active camera — taken
 * from the core's one rule (`TrideScene.defaultFraming(aspect)`): eye, centre, lens,
 * focus. Here only the shape changes, plus the host's orbit about the framed centre.
 *
 * Public API (internal): `DefaultFraming.of(TrideDefaultFraming)` → `Framing` (pose + lens + `OrbitStart`),
 * `DefaultFraming.orbit(framing, yaw, pitch, distanceScale)` → `FramingPose`; `FramingPose.focusDistance`.
 *
 * Non-obvious constraints:
 *   - No framing constants here: the same scene frames alike on iOS, Android and the web.
 *   - The framing camera focuses where it looks: eye → the framing centre; a scene camera
 *     keeps its authored focus. Pure — no Filament.
 *   - Orbit (0, 0, ×1) is the framed pose itself: the core lifts the eye above the centre, so the orbit's
 *     angles and distance come from eye → centre, not from the core's level `orbitDistance`.
 */
package studio.tride.kit

import studio.tride.core.TrideDefaultFraming
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the framing camera stands and the centre it looks at. */
internal class FramingPose(val eye: DoubleArray, val center: DoubleArray) {
    /** Eye → the framing centre: where the framing camera focuses. */
    val focusDistance: Double
        get() {
            val dx = eye[0] - center[0]; val dy = eye[1] - center[1]; val dz = eye[2] - center[2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
}

/** Where an orbit's (0, 0, ×1) stands: yaw / pitch (radians) and eye → centre distance of a pose. */
internal class OrbitStart(val yaw: Double, val pitch: Double, val distance: Double) {
    companion object {
        fun of(pose: FramingPose): OrbitStart {
            val dx = pose.eye[0] - pose.center[0]; val dy = pose.eye[1] - pose.center[1]; val dz = pose.eye[2] - pose.center[2]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d <= 1e-9) return OrbitStart(0.0, 0.0, 0.0)
            return OrbitStart(atan2(dx, dz), asin((dy / d).coerceIn(-1.0, 1.0)), d)
        }
    }
}

/**
 * A framed pose (or a scene camera's, taken over by the bridge's `useFreeCamera`), its lens (vertical fov
 * radians, clip planes) and where an orbit about it starts.
 */
internal class Framing(
    val pose: FramingPose,
    val yfov: Double,
    val near: Double,
    val far: Double,
) {
    val orbitStart: OrbitStart = OrbitStart.of(pose)
}

internal object DefaultFraming {
    /** The core's framing in the bridge's shape. */
    fun of(f: TrideDefaultFraming): Framing = Framing(
        pose = FramingPose(doubleArrayOf(f.eyeX, f.eyeY, f.eyeZ), doubleArrayOf(f.targetX, f.targetY, f.targetZ)),
        yfov = f.yfov,
        near = f.near,
        far = f.far,
    )

    /**
     * The framing orbited by `yaw` / `pitch` (radians, added to the framed pose's; pitch held short of the
     * poles, where `lookAt` with world up flips) at `distanceScale` × its eye → centre distance.
     */
    fun orbit(framing: Framing, yaw: Double, pitch: Double, distanceScale: Double): FramingPose {
        val start = framing.orbitStart
        return orbit(framing.pose.center, start.distance * distanceScale, yaw + start.yaw,
            (pitch + start.pitch).coerceIn(-MAX_PITCH, MAX_PITCH))
    }

    /** The eye `distance` from `center` at `yaw` / `pitch` (radians), looking at the centre. */
    fun orbit(center: DoubleArray, distance: Double, yaw: Double, pitch: Double): FramingPose {
        val cp = cos(pitch)
        val eye = doubleArrayOf(
            center[0] + distance * cp * sin(yaw),
            center[1] + distance * sin(pitch),
            center[2] + distance * cp * cos(yaw),
        )
        return FramingPose(eye, center)
    }

    /** Short of the poles, as on iOS (1.55 rad). */
    private const val MAX_PITCH = 1.55
}
