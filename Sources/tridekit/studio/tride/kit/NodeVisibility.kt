/**
 * Effective node visibility from each node's own flag and its ancestors'.
 *
 * The bridge applies a visibility flag to a node's WHOLE subtree, so flags are applied parents first: a
 * child hidden on its own is then re-hidden by its own turn, whatever its parent did. Re-applying one
 * node's flag alone would un-hide such a child.
 */
package studio.tride.kit

internal class NodeVisibility(
    private val own: Map<Int, Boolean>,
    private val parent: Map<Int, Int?>,
) {
    /** Hidden when the node or any ancestor is. */
    fun effective(index: Int): Boolean {
        var i: Int? = index
        var guard = 0
        while (i != null && guard++ < 256) {
            if (own[i] == false) return false
            i = parent[i]
        }
        return true
    }

    /** Every node with an own flag, ancestors before descendants. */
    fun parentsFirst(): List<Int> {
        val depth = HashMap<Int, Int>()
        fun depthOf(i: Int): Int = depth.getOrPut(i) { parent[i]?.let { depthOf(it) + 1 } ?: 0 }
        return own.keys.sortedBy { depthOf(it) }
    }
}
