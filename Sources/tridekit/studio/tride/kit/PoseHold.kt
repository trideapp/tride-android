/**
 * The pose every light and camera holds between frames: LOCAL position and the record's angles (radians
 * by axis) with the order they compose in, seeded from the authored record and patched lane by lane as
 * frames write them. Pure (no Filament, no composition); the scene writes the poses a frame moved — on
 * the carrier node (its quaternion composed by the core in the pose's order), or on the light itself.
 *
 * Public API (internal): `PoseHold(records)` — `fold(lanes)` → `PoseFold` (ids written, lanes refused),
 * `pose(id)`, `reset()` (back to authored; the ids that had moved); `PoseLane`, `Pose`.
 *
 * Non-obvious constraints:
 *   - HELD: the runtime hands out changed lanes only, and the lanes of one pose may arrive under two
 *     channels (`translation/0` next to `position/1`), so an axis a frame does not mention keeps its
 *     last value — never the authored one.
 *   - A lane patches one axis (rotation in Euler space — multi-turn faithful); a whole value takes its
 *     first three numbers; any other shape is refused for the caller to report.
 *   - `translation` is `position` (the runtime's alias). Only the records handed in are held.
 */
package studio.tride.kit

/** The order a light record's angles compose in (`TrideLightDescriptor.rotation`: Euler XYZ). */
internal const val LIGHT_ROTATION_ORDER = "xyz"

/**
 * A light's or camera's LOCAL pose: position and the record's angles (radians by axis), composed in
 * [order] — a camera's `TrideCameraDescriptor.rotationOrder`, [LIGHT_ROTATION_ORDER] for a light.
 */
internal class Pose(val position: DoubleArray, val euler: DoubleArray, val order: String = LIGHT_ROTATION_ORDER)

/** One spatial lane of a light or camera mutation: `position` / `rotation`, an axis or the whole value. */
internal data class PoseLane(val id: String, val property: String, val component: Int?, val value: Any)

/** What one frame did to the held poses. */
internal class PoseFold(val wrote: Set<String>, val refused: List<PoseLane>)

internal class PoseHold(records: Map<String, Pose>) {
    private val authored = HashMap(records)
    private val held = HashMap(records)
    private val moved = HashSet<String>()

    fun pose(id: String): Pose? = held[id]

    fun fold(lanes: List<PoseLane>): PoseFold {
        val wrote = LinkedHashSet<String>()
        val refused = ArrayList<PoseLane>()
        for (lane in lanes) {
            val pose = held[lane.id] ?: continue
            val rotation = when (lane.property) {
                "rotation" -> true
                "position", "translation" -> false
                else -> continue
            }
            val next = patched(if (rotation) pose.euler else pose.position, lane)
            if (next == null) {
                refused += lane
                continue
            }
            held[lane.id] = if (rotation) Pose(pose.position, next, pose.order) else Pose(next, pose.euler, pose.order)
            wrote += lane.id
            moved += lane.id
        }
        return PoseFold(wrote, refused)
    }

    /** Every pose back to the authored one; the ids that had moved. */
    fun reset(): Set<String> {
        val was = HashSet(moved)
        for (id in was) authored[id]?.let { held[id] = it }
        moved.clear()
        return was
    }

    private fun patched(current: DoubleArray, lane: PoseLane): DoubleArray? {
        val c = lane.component
        if (c != null) {
            val n = (lane.value as? Double)?.takeIf { it.isFinite() } ?: return null
            return if (c in 0..2) current.copyOf().also { it[c] = n } else null
        }
        val v = lane.value as? DoubleArray ?: return null
        if (v.size < 3 || !v[0].isFinite() || !v[1].isFinite() || !v[2].isFinite()) return null
        return doubleArrayOf(v[0], v[1], v[2])
    }
}
