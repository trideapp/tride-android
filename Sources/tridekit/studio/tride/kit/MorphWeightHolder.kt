/**
 * The morph weight vectors a loaded scene holds, one per node.
 *
 * A lane absent from a batch says nothing: the runtime sends only values that changed, and lanes written
 * by different sources at different times (an animation track, a binding) must survive each other — so
 * the vector is held, not rebuilt from the batch. A lane at or past the node's target count is rejected,
 * so the array handed to Filament is never longer than the renderable's target count (a longer one
 * throws inside Filament's frame callback).
 */
package studio.tride.kit

internal class MorphWeightHolder {
    private val held = HashMap<Int, FloatArray>()

    class Fold(val vector: FloatArray, val rejected: List<Int>)

    /** Merge [lanes] into the node's held vector of [count] lanes; returns the whole vector. */
    fun fold(gltfIndex: Int, count: Int, lanes: Map<Int, Double>): Fold {
        val vector = held[gltfIndex]?.takeIf { it.size == count } ?: FloatArray(count).also { held[gltfIndex] = it }
        val rejected = ArrayList<Int>()
        for ((lane, value) in lanes) {
            if (lane in 0 until count) vector[lane] = value.toFloat() else rejected.add(lane)
        }
        return Fold(vector.copyOf(), rejected)
    }

    /** The vector held for the node, `null` before any write. */
    fun held(gltfIndex: Int): FloatArray? = held[gltfIndex]?.copyOf()

    /** Forget every held vector (a scene attach). */
    fun reset() = held.clear()
}
