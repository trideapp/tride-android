/**
 * `RecordScene` — a scene laid out from the core's scene record: one place per node row, in the record's order.
 * It is the one table the scene and the bridge address nodes, lights and cameras through: a place's number is the
 * node index, and a light / camera is the node row that names it by slug, so there is no second lookup.
 *
 * Public API (inside TrideKit):
 *   - `RecordScene(record)` — `places` (`RecordPlace`: parent place, local translation, rotation `[x, y, z, w]`,
 *     scale), `rowOf` (node uid → row), `uidAt` (row → node uid), `lights` / `cameras` (each record with
 *     the row of the node holding it, `Held`), `visibleAt` (row → the core's visibility to the renderer at load), `datablockNodes` (every node
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
 *   - A light / camera is drawn only at the node row that names its slug (the first one); the record's own
 *     `parent` / `translation` / `rotation` are not read (the node's pose is the pose). A record no row names,
 *     or a row naming a slug the record lacks, is said once in `notices` and draws no light / camera.
 */
package studio.tride.kit.record

import studio.tride.core.TrideParametricNode
import studio.tride.core.TrideRecordCamera
import studio.tride.core.TrideRecordLight
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

/** A light / camera record and the row of the node that holds it. */
internal data class Held<T>(val record: T, val row: Int)

internal class RecordScene(record: TrideSceneRecord) {
    val places: List<RecordPlace>
    val rowOf: Map<String, Int>
    val uidAt: List<String>
    val lights: List<Held<TrideRecordLight>>
    val cameras: List<Held<TrideRecordCamera>>
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
        val said = ArrayList<String>()
        places = raw.mapIndexed { i, place ->
            val parent = place.parent ?: return@mapIndexed place
            if (reachesRoot(raw, i)) return@mapIndexed place
            val name = record.nodes.getOrNull(i)?.uid ?: "$i"
            said += "'$name': parent $parent is no place of the scene or loops back, drawn at the root"
            RecordPlace(null, place.translation, place.rotation, place.scale)
        }
        rowOf = rows
        uidAt = record.nodes.map { it.uid }
        lights = held("light", record.lights.map { it.id to it }, record.nodes.map { it.light }, record, said)
        cameras = held("camera", record.cameras.map { it.id to it }, record.nodes.map { it.camera }, record, said)
        visibleAt = record.nodes.map { it.visible }
        datablockNodes = datablocks
        notices = said
    }

    companion object {
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

        /** Each record of [records] (slug → record) at the first row of [named] (each row's slug) naming it; the rest said. */
        private fun <T> held(kind: String, records: List<Pair<String, T>>, named: List<String?>, record: TrideSceneRecord, said: MutableList<String>): List<Held<T>> {
            val rowOfSlug = LinkedHashMap<String, Int>()
            for ((row, slug) in named.withIndex()) if (slug != null) rowOfSlug.putIfAbsent(slug, row)
            val known = records.map { it.first }.toSet()
            for ((slug, row) in rowOfSlug) if (slug !in known) said += "'${record.nodes[row].uid}': holds $kind '$slug', which the scene has no record of: no $kind there"
            return records.mapNotNull { (slug, r) ->
                val row = rowOfSlug[slug]
                if (row == null) said += "$kind '$slug': no node of the scene holds it, not drawn"
                row?.let { Held(r, it) }
            }
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
