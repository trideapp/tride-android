/**
 * The bone palette check (the parity gate's palette line): each palette the core handed (`TickResult.bonePalettes`)
 * against the one gltfio's `AnimatorImpl::updateBoneMatrices` makes of the host's own worlds — `inv(W_mesh)` by
 * Gauss–Jordan in `Double`, `× W_joint` in `Double`, narrowed to `Float`, `× IBM` in `Float` (Filament 1.77.1
 * `Animator.cpp`). The same arithmetic and bound as TrideKit iOS's `BonePaletteCheck`.
 *
^ * Public API (for checks; `TrideKitScene.bonePaletteCheck()` is opt-in `TrideKitChecks`): `BonePaletteCheck.Result` (palettes and bones compared, the worst
 * difference relative to the largest element of the last product's factors, palettes whose mesh the view has no world
 * for, `passes`), `BonePaletteCheck.TOLERANCE` (1.9e-6: the core's host measure × 2).
 * Inside TrideKit: `key(mesh, part)`, `gltfioBone(meshWorld, jointWorld, ibm)`, `measure(palettes, skins, partJoints, world)`.
 *
 * Non-obvious constraints:
 *   - Filament hands no palette back: the host's side is recomputed from its TransformManager worlds, the mesh's and
 *     each joint's place.
 *   - A joint the record lacks (`null` row) is the identity bone, as the core makes it; an unsplit palette's bone `j`
 *     is the skin's joint `j`, a skin part's the part's `joints[j]`.
 *   - Pure Kotlin over the core's record types: the host-JVM unit tests run it as it is.
 *   - Cross-refs: `crates/tride-runtime-api/tests/skin_palette_gltfio.rs` (the same arithmetic and bound).
 */
package studio.tride.kit

import studio.tride.core.TrideBonePalette
import studio.tride.core.TrideRecordSkin
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

public object BonePaletteCheck {
    /** The worst difference allowed, relative to the largest element of the last product's factors. */
    public const val TOLERANCE: Double = 1.9e-6

    public class Result(
        public val palettes: Int,
        public val bones: Int,
        /** The worst `|core − host| / max|factors|` over every element of every bone. */
        public val worst: Double,
        /** Palettes whose mesh place the view has no world for (not compared). */
        public val unplaced: Int,
    ) {
        public val passes: Boolean get() = palettes > 0 && unplaced == 0 && worst <= TOLERANCE
    }

    /** A palette's key: the mesh node's row and the skin part. */
    internal fun key(mesh: Int, part: Int): Long = (mesh.toLong() shl 32) or (part.toLong() and 0xffffffffL)

    /** gltfio's bone of `jointWorld` for a mesh at `meshWorld` (column-major), with the largest element of the last product's two factors. */
    internal fun gltfioBone(meshWorld: FloatArray, jointWorld: FloatArray, ibm: FloatArray): Pair<FloatArray, Double> {
        val inverse = gaussJordan(DoubleArray(16) { meshWorld[it].toDouble() })
        val product = multiply(inverse, DoubleArray(16) { jointWorld[it].toDouble() })
        val global = FloatArray(16) { product[it].toFloat() }
        var factor = 0.0
        for (v in global) factor = max(factor, abs(v.toDouble()))
        for (v in ibm) factor = max(factor, abs(v.toDouble()))
        return multiplyF(global, ibm) to factor
    }

    /**
     * Every palette against the host's: `skins` the record's, `partJoints` a cut part's joints by [key], `world` a
     * place's world matrix as the view composed it.
     */
    internal fun measure(
        palettes: Collection<TrideBonePalette>,
        skins: List<TrideRecordSkin>,
        partJoints: Map<Long, List<Int>>,
        world: (Int) -> FloatArray?,
    ): Result {
        var compared = 0; var bones = 0; var worst = 0.0; var unplaced = 0
        for (palette in palettes) {
            val skin = skins.getOrNull(palette.skin.toInt())
            val meshWorld = world(palette.mesh.toInt())
            if (skin == null || meshWorld == null) { unplaced++; continue }
            val core = floats(palette.bones)
            val ibms = floats(skin.inverseBindMatrices)
            val count = core.size / 16
            val joints = partJoints[key(palette.mesh.toInt(), palette.part.toInt())] ?: List(count) { it }
            compared++
            for (j in 0 until min(count, joints.size)) {
                val joint = joints[j]
                var host = IDENTITY
                var factor = 1.0
                val row = skin.joints.getOrNull(joint)
                val jointWorld = row?.let { world(it.toInt()) }
                if (jointWorld != null && (joint + 1) * 16 <= ibms.size) {
                    val (bone, f) = gltfioBone(meshWorld, jointWorld, ibms.copyOfRange(joint * 16, (joint + 1) * 16))
                    host = bone; factor = f
                }
                for (e in 0 until 16) worst = max(worst, abs(core[j * 16 + e].toDouble() - host[e].toDouble()) / factor)
                bones++
            }
        }
        return Result(compared, bones, worst, unplaced)
    }

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private fun floats(bytes: ByteArray): FloatArray {
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    /** `TMat44 * TMat44` as Filament's `matrix::multiply`: each column `lhs * rhs[col]`, the sum taken row by row over `c`. */
    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(16)
        for (col in 0 until 4) {
            val result = DoubleArray(4)
            for (c in 0 until 4) for (row in 0 until 4) result[row] += a[c * 4 + row] * b[col * 4 + c]
            for (row in 0 until 4) out[col * 4 + row] = result[row]
        }
        return out
    }

    private fun multiplyF(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) {
            val result = FloatArray(4)
            for (c in 0 until 4) for (row in 0 until 4) result[row] += a[c * 4 + row] * b[col * 4 + c]
            for (row in 0 until 4) out[col * 4 + row] = result[row]
        }
        return out
    }

    /** `matrix::gaussJordanInverse` (`TMatHelpers.h`), `m[col][row]`. */
    private fun gaussJordan(m: DoubleArray): DoubleArray {
        val src = Array(4) { c -> DoubleArray(4) { m[c * 4 + it] } }
        val inverted = Array(4) { c -> DoubleArray(4) { if (it == c) 1.0 else 0.0 } }
        for (i in 0 until 4) {
            var swap = i
            var t = abs(src[i][i])
            for (j in i + 1 until 4) if (abs(src[j][i]) > t) { swap = j; t = abs(src[j][i]) }
            if (swap != i) {
                val s = src[i]; src[i] = src[swap]; src[swap] = s
                val v = inverted[i]; inverted[i] = inverted[swap]; inverted[swap] = v
            }
            val denom = src[i][i]
            for (k in 0 until 4) { src[i][k] /= denom; inverted[i][k] /= denom }
            for (j in 0 until 4) {
                if (j == i) continue
                val f = src[j][i]
                for (k in 0 until 4) { src[j][k] -= f * src[i][k]; inverted[j][k] -= f * inverted[i][k] }
            }
        }
        return DoubleArray(16) { inverted[it / 4][it % 4] }
    }
}
