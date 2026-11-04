/**
 * `RecordScene` — a scene laid out from the core's scene record: one place per node row, in the record's order,
 * then one carrier place per light and per camera. It is the one table the scene and the bridge address nodes,
 * lights and cameras through: a place's number is the node index, so there is no second lookup.
 *
 * Public API (inside TrideKit):
 *   - `RecordScene(record)` — `places` (`RecordPlace`: parent place, local translation, rotation `[x, y, z, w]`,
 *     scale), `rowOf` (node uid → row), `uidAt` (row → node uid), `carrierPlace` (light / camera id → its
 *     place), `visibleAt` (row → the core's visibility to the renderer at load), `datablockNodes` (every node
 *     that draws a datablock), `materials` (the record's palette: how the core draws each slug), `restWeights`
 *     (node uid → morph weights at rest), `skins` / `skinOf` (the record's skins, node uid → its skin), `notices`
 *     (what was laid out otherwise than the record says).
 *   - `RecordScene.worlds(places)` — each place's world matrix composed in `Double`, column-major (checks).
 *   - `RecordScene.DRAWN_REQUIRES`, `requiresNotice(requires)` — what of a record's `requires` Android draws, and
 *     the one notice for what it does not (the same rule as iOS and the web).
 *   - `RecordScene.gapsNotice(gaps)` — the one notice for materials drawn without values the record does not carry
 *     yet (the same text as iOS's `recordGapsNotice`).
 *
 * Non-obvious constraints:
 *   - Pure Kotlin over the core's record types, no Android and no Filament: the host-JVM tests compile this
 *     file as it is.
 *   - A parent that names no place, or a chain of parents that loops, hangs the place at the root; said once
 *     in `notices`.
 *   - A light / camera carrier holds the record's local pose (the core turned the angles into the quaternion)
 *     under its parent row at scale 1; the Light / Camera component sits on it at identity, as gltfio puts a
 *     glTF light or camera on its node.
 */
package studio.tride.kit.record

import studio.tride.core.TrideParametricNode
import studio.tride.core.TrideRecordMaterial
import studio.tride.core.TrideRecordSkin
import studio.tride.core.TrideSceneRecord

/** One place: its parent place (`null` = the root) and its local pose. */
internal class RecordPlace(
    val parent: Int?,
    val translation: DoubleArray,
    val rotation: DoubleArray,
    val scale: DoubleArray,
)

internal class RecordScene(record: TrideSceneRecord) {
    val places: List<RecordPlace>
    val rowOf: Map<String, Int>
    val uidAt: List<String>
    val carrierPlace: Map<String, Int>
    val visibleAt: List<Boolean>
    val datablockNodes: List<TrideParametricNode>
    val materials: List<TrideRecordMaterial> = record.materials
    /** Node uid → its morph weights at rest (the core's, the node's else its mesh's); absent without any. */
    val restWeights: Map<String, List<Double>> = record.nodes.filter { it.weights.isNotEmpty() }.associate { it.uid to it.weights }
    /** The record's skins (`joints` as rows, `inverseBindMatrices`), in palette `skin` order. */
    val skins: List<TrideRecordSkin> = record.skins
    /** Node uid → its skin (a position in [skins]); absent for a node no skin bends. */
    val skinOf: Map<String, Int> = record.nodes.mapNotNull { n -> n.skin?.toInt()?.takeIf { it < record.skins.size }?.let { n.uid to it } }.toMap()
    val notices: List<String>

    init {
        val raw = ArrayList<RecordPlace>()
        val rows = HashMap<String, Int>()
        val datablocks = ArrayList<TrideParametricNode>()
        for ((row, node) in record.nodes.withIndex()) {
            rows[node.uid] = row
            node.datablock?.let { datablocks += TrideParametricNode(node.uid, it) }
            raw += RecordPlace(node.parent?.toInt(), vec(node.translation, 3, 0.0), quat(node.rotation), vec(node.scale, 3, 1.0))
        }
        val carriers = LinkedHashMap<String, Int>()
        for (light in record.lights) {
            carriers[light.id] = raw.size
            raw += RecordPlace(light.parent?.toInt(), vec(light.translation, 3, 0.0), quat(light.rotation), UNIT)
        }
        for (camera in record.cameras) {
            carriers[camera.id] = raw.size
            raw += RecordPlace(camera.parent?.toInt(), vec(camera.translation, 3, 0.0), quat(camera.rotation), UNIT)
        }
        val said = ArrayList<String>()
        places = raw.mapIndexed { i, place ->
            val parent = place.parent ?: return@mapIndexed place
            if (reachesRoot(raw, i)) return@mapIndexed place
            val name = record.nodes.getOrNull(i)?.uid ?: carriers.entries.firstOrNull { it.value == i }?.key ?: "$i"
            said += "'$name': parent $parent is no place of the scene or loops back, drawn at the root"
            RecordPlace(null, place.translation, place.rotation, place.scale)
        }
        rowOf = rows
        uidAt = record.nodes.map { it.uid }
        carrierPlace = carriers
        visibleAt = record.nodes.map { it.visible }
        datablockNodes = datablocks
        notices = said
    }

    companion object {
        private val UNIT = doubleArrayOf(1.0, 1.0, 1.0)

        /** What of a record's `requires` Android draws: skins by the core's palettes, morphs, lines, points, strips. */
        val DRAWN_REQUIRES: Set<String> = setOf("skins", "morphs", "lines", "points", "strips")

        /** The scene drawn without what its `requires` names and Android does not draw ([DRAWN_REQUIRES]); `null` when it draws all of it. */
        fun requiresNotice(requires: List<String>): String? {
            val missing = requires.filter { it !in DRAWN_REQUIRES }
            if (missing.isEmpty()) return null
            return "the scene requires ${missing.joinToString(", ")}, which Android does not draw yet: drawn without them"
        }

        /** The materials drawn without values the record does not carry yet ([gaps]: slug → their names), by slug; `null` without any. */
        fun gapsNotice(gaps: Map<String, List<String>>): String? {
            if (gaps.isEmpty()) return null
            return "the scene draws " + gaps.keys.sorted().joinToString("; ") { "'$it' without its ${gaps.getValue(it).joinToString(", ")}" }
        }

        private fun vec(v: List<Double>, n: Int, fill: Double) = DoubleArray(n) { v.getOrNull(it) ?: fill }

        private fun quat(v: List<Double>) =
            if (v.size == 4) v.toDoubleArray() else doubleArrayOf(0.0, 0.0, 0.0, 1.0)

        /** Place [i]'s parents end at a root, without coming back to it or looping. */
        private fun reachesRoot(places: List<RecordPlace>, i: Int): Boolean {
            var cursor = places[i].parent
            var steps = 0
            while (cursor != null) {
                if (cursor !in places.indices || cursor == i || steps++ > places.size) return false
                cursor = places[cursor].parent
            }
            return true
        }

        /**
         * Each place's world matrix (16 numbers, column-major) composed in `Double`: `parent world × T × R × S`.
         * The places' parents reach a root (the constructor saw to it).
         */
        fun worlds(places: List<RecordPlace>): List<DoubleArray> {
            val known = arrayOfNulls<DoubleArray>(places.size)
            fun world(i: Int): DoubleArray {
                known[i]?.let { return it }
                val place = places[i]
                val local = local(place)
                val out = place.parent?.let { multiply(world(it), local) } ?: local
                known[i] = out
                return out
            }
            return places.indices.map { world(it) }
        }

        private fun local(p: RecordPlace): DoubleArray {
            val (qx, qy, qz, qw) = p.rotation.toList()
            val s = p.scale
            val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
            val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
            val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
            return doubleArrayOf(
                (1 - 2 * (yy + zz)) * s[0], 2 * (xy + wz) * s[0], 2 * (xz - wy) * s[0], 0.0,
                2 * (xy - wz) * s[1], (1 - 2 * (xx + zz)) * s[1], 2 * (yz + wx) * s[1], 0.0,
                2 * (xz + wy) * s[2], 2 * (yz - wx) * s[2], (1 - 2 * (xx + yy)) * s[2], 0.0,
                p.translation[0], p.translation[1], p.translation[2], 1.0,
            )
        }

        /** `a × b`, both column-major. */
        private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(16) { k ->
            val c = k / 4
            val r = k % 4
            (0 until 4).sumOf { a[it * 4 + r] * b[c * 4 + it] }
        }
    }
}
