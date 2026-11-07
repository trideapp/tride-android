/**
 * The morph weight vectors a loaded scene holds, one per node place (its record row).
 *
 * A lane absent from a batch says nothing: the runtime sends only values that changed, and lanes written
 * by different sources at different times (an animation track, a binding) must survive each other — so
 * the vector is held, not rebuilt from the batch. A node's vector starts at its weights at rest (the record
 * row's `weights`, the core's `morph::rest_weights`), the same weights its mesh was uploaded with, so a lane
 * nothing animates keeps them. A lane at or past the node's target count is rejected, so the array handed to
 * Filament is never longer than the renderable's target count (a longer one throws inside Filament's frame
 * callback).
 */
package studio.tride.kit

internal class MorphWeightHolder {
    private val held = HashMap<Int, FloatArray>()

    class Fold(val vector: FloatArray, val rejected: List<Int>)

    /**
     * Merge [lanes] into the vector held for [place], [count] lanes long; returns the whole vector. A first
     * write starts from [rest] (cut or padded with zeros to [count]; `null` = all zeros); a new target count
     * keeps the lanes held and takes the added ones from [rest].
     */
    fun fold(place: Int, count: Int, rest: List<Double>?, lanes: Map<Int, Double>): Fold {
        val vector = held[place]?.takeIf { it.size == count } ?: seed(count, rest, held[place]).also { held[place] = it }
        val rejected = ArrayList<Int>()
        for ((lane, value) in lanes) {
            if (lane in 0 until count) vector[lane] = value.toFloat() else rejected.add(lane)
        }
        return Fold(vector.copyOf(), rejected)
    }

    /** The vector held for the node, `null` before any write. */
    fun held(place: Int): FloatArray? = held[place]?.copyOf()

    /** Forget every held vector (a scene attach): the next writes start from that record's rest. */
    fun reset() = held.clear()

    private fun seed(count: Int, rest: List<Double>?, before: FloatArray?): FloatArray {
        val vector = FloatArray(count) { rest?.getOrNull(it)?.toFloat() ?: 0f }
        before?.copyInto(vector, 0, 0, minOf(before.size, count))
        return vector
    }
}
