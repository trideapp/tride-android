/**
 * `LocalMatrix` — the one way the bridge turns a translation, a rotation `[x, y, z, w]` and a scale into the
 * local matrix it hands Filament's TransformManager: gltfio's own composition (`TrsTransform.compose`, the code
 * that composes every glTF node of an asset, compiled with gltfio's flags).
 *
 * Public API (inside TrideKit): `LocalMatrix.of(t, r, s)` → column-major `FloatArray(16)`, `T · R · S`.
 *
 * Non-obvious constraints:
 *   - Every place and every later pose is composed by this: the same float matrices gltfio composes for a glTF
 *     node, bit for bit, on every ABI (a Kotlin port would round or fuse otherwise).
 *   - The values go over as `f32`, as gltfio reads a glTF node's.
 *   - Needs gltfio's native library (loaded with Filament's, `Gltfio.init`): not for host-JVM tests.
 */
package studio.tride.kit

import com.google.android.filament.gltfio.TrsTransform

internal object LocalMatrix {
    /** `T · R · S` of `t`, the unit quaternion `r` (`[x, y, z, w]`) and `s`, column-major. */
    fun of(t: DoubleArray, r: DoubleArray, s: DoubleArray): FloatArray {
        val out = FloatArray(16)
        TrsTransform.compose(
            floatArrayOf(t[0].toFloat(), t[1].toFloat(), t[2].toFloat()),
            floatArrayOf(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat()),
            floatArrayOf(s[0].toFloat(), s[1].toFloat(), s[2].toFloat()),
            out,
        )
        return out
    }
}
