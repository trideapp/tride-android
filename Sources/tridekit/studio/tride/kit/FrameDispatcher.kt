/**
 * One runtime tick → the frame's streams. Pure: no Android, no Filament, no FFI calls — the caller feeds
 * `PointerMutation`s (pointer + decoded value) and applies the routed streams to the bridge.
 *
 * Public API:
 *   - `TypedValues.decode(mutation)` — the FFI's `TrideTypedValue` → the dispatcher's value shape.
 *   - `PointerDispatch.dispatch(pointer, value, resolver)` → `Dispatch.Ok(CanonicalMutation)` / `Dispatch.Fail`.
 *   - `OverrideAccumulator` — persistent per-node overrides (position / quat / Euler / scale / visible),
 *     morph weights and the environment writes of the tick; `resolveLeafRotation` (Euler wins).
 *   - `routeMutations(mutations, resolver)` → `RoutedMutations` (material, camera, light, render settings,
 *     generator geometry announcements with their `trunkEpoch`, pulse-table announcements, rebuilt
 *     datablocks, nodes bent anew (`/deformed/{uid}/geometry`), `/scene/active_camera`, the node stream,
 *     problems); a tag leaf (`/tags/{id}/…`) and a deformer leaf (`/deformers/{id}/…`) are the runtime's own.
 *   - Render settings: `/render_settings/{tone_mapping/saturation, fog/color, bloom/strength,
 *     pixelation/enabled, pixelation/size}` — the saturation, the bloom strength and the pixelation size
 *     are numbers (no lane), the pixelation switch true / false, the fog colour an RGB colour or one lane.
 *
 * Non-obvious constraints:
 *   - Values are `Double`, `DoubleArray`, `Boolean` or `String`; a `Json` payload is parsed by the caller.
 *   - A per-axis rotation write patches the EULER triple seeded from the authored baseline (no quaternion
 *     decompose); `/rotation/w` and `/rotation/{0..3}` are raw quaternion components; a whole `/rotation`
 *     is a quaternion (4) or Euler (3). First match wins; an unmatched pointer is a problem, never a
 *     silent drop.
 *   - `/scene/active_camera` is a hard cut of the view's camera.
 *   - `/materialOverrides/{slug}/…` lands in the material stream with its slug — the host recolours the
 *     look from the runtime (`materialParams(slug)`), a node material's inputs (`…/nodes/{node}/inputs/…`,
 *     `isGraphLeaf`: its uniforms only) included; `/materials/{i}/…` by index is a stream too (not applied on Android; warned once).
 *   - The look the runtime hands Android carries no glass (`GLASS_FIELDS`): a write to one is also a
 *     problem at `/materialOverrides/{slug}/{field}` — an imported glTF glass keeps its saved value.
 */
package studio.tride.kit

import studio.tride.core.TrideMutation
import studio.tride.core.TrideTypedValue

/** One mutation of the runtime's tick result, its value decoded. */
internal data class PointerMutation(val pointer: String, val value: Any?)

/** Resolves `/nodes/{gltfIndex}` and `/node/{uid}` to the node id the accumulator keys by. */
internal interface NodeResolver {
    fun nodeIdForGltfIndex(gltfIndex: Int): String?
    fun resolveManifestNode(nodeId: String): String?

    /**
     * `/node/{uid}/weights/{n}` → the node's glTF index (morph weights live on the glTF mesh node; the
     * runtime writes `/nodes/{i}/weights/{n}` in this uid form).
     * `null` makes the pointer a `NODE_NOT_FOUND` problem instead of a silent drop.
     */
    fun gltfIndexForNode(nodeId: String): Int? = null
}

internal data class LightMutation(val lightId: String, val property: String, val component: Int?, val value: Any)
internal data class CameraMutation(val cameraId: String, val property: String, val component: Int?, val value: Any)
internal data class EnvironmentMutation(val property: String, val component: Int?, val value: Any)
internal data class RenderSettingsMutation(val property: String, val component: Int?, val value: Any)
internal data class GeneratorParamMutation(val domain: String, val generatorId: String, val leaf: String, val value: Any)
internal data class MaterialMutation(val materialIndex: Int, val materialSlug: String?, val subPath: String, val value: Any?)

internal sealed interface CanonicalMutation {
    data class NodePosition(val nodeId: String, val value: DoubleArray) : CanonicalMutation
    data class NodePositionAxis(val nodeId: String, val axis: Int, val value: Double) : CanonicalMutation
    data class NodeScale(val nodeId: String, val value: DoubleArray) : CanonicalMutation
    data class NodeScaleAxis(val nodeId: String, val axis: Int, val value: Double) : CanonicalMutation
    data class NodeRotationQuat(val nodeId: String, val value: DoubleArray) : CanonicalMutation
    data class NodeRotationQuatComp(val nodeId: String, val comp: Int, val value: Double) : CanonicalMutation
    data class NodeRotationEuler(val nodeId: String, val value: DoubleArray) : CanonicalMutation
    data class NodeRotationEulerAxis(val nodeId: String, val axis: Int, val value: Double) : CanonicalMutation
    data class NodeVisible(val nodeId: String, val value: Boolean) : CanonicalMutation
    data class MorphWeight(val gltfIndex: Int, val weightIndex: Int, val value: Double) : CanonicalMutation
    data class Light(val value: LightMutation) : CanonicalMutation
    data class Camera(val value: CameraMutation) : CanonicalMutation
    data class Environment(val value: EnvironmentMutation) : CanonicalMutation
    data class RenderSettings(val value: RenderSettingsMutation) : CanonicalMutation
    data class Generator(val value: GeneratorParamMutation) : CanonicalMutation
}

internal enum class FailReason { UNMATCHED, NODE_NOT_FOUND, VALUE_SHAPE, AXIS_RANGE }

internal sealed interface Dispatch {
    data class Ok(val mutation: CanonicalMutation) : Dispatch
    data class Fail(val reason: FailReason, val pointer: String, val message: String) : Dispatch
}

/** The FFI's typed payload → the dispatcher's value shape. `Json` stays a `RawJson` for the caller. */
internal object TypedValues {
    data class RawJson(val raw: String)

    fun decode(m: TrideMutation): Any? = when (val v = m.typedValue) {
        is TrideTypedValue.Scalar -> v.value
        is TrideTypedValue.Vec3 -> doubleArrayOf(v.x, v.y, v.z)
        is TrideTypedValue.Quat -> doubleArrayOf(v.x, v.y, v.z, v.w)
        is TrideTypedValue.Color -> doubleArrayOf(v.r, v.g, v.b, v.a)
        is TrideTypedValue.BoolValue -> v.value
        is TrideTypedValue.StringValue -> v.value
        is TrideTypedValue.Json -> RawJson(v.raw)
    }
}

internal object PointerDispatch {
    private fun ok(m: CanonicalMutation): Dispatch = Dispatch.Ok(m)
    /** An index from the pointer, or `Fail(AXIS_RANGE)`: a scene can author any digits, `dispatch` never throws. */
    private inline fun withIndex(text: String, pointer: String, then: (Int) -> Dispatch): Dispatch =
        text.toIntOrNull()?.let(then) ?: fail(FailReason.AXIS_RANGE, pointer, "index $text is out of range")
    private fun fail(reason: FailReason, pointer: String, message: String): Dispatch = Dispatch.Fail(reason, pointer, message)

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is DoubleArray -> "array[${v.size}]"
        else -> v::class.simpleName ?: "value"
    }

    private fun vec3(v: Any?): DoubleArray? = (v as? DoubleArray)?.takeIf { it.size == 3 }
    private fun vec4(v: Any?): DoubleArray? = (v as? DoubleArray)?.takeIf { it.size == 4 }
    private fun number(v: Any?): Double? = (v as? Double) ?: (v as? Number)?.toDouble()

    private fun normalizeSlot(raw: String) = if (raw == "translation") "position" else raw
    private fun axisIndex(c: String) = when (c) { "x" -> 0; "y" -> 1; else -> 2 }

    private fun buildWhole(nodeId: String, slotRaw: String, value: Any?, pointer: String): Dispatch {
        if (slotRaw == "visible") {
            return if (value is Boolean) ok(CanonicalMutation.NodeVisible(nodeId, value))
            else fail(FailReason.VALUE_SHAPE, pointer, "visible slot expects boolean, got ${describe(value)}")
        }
        val slot = normalizeSlot(slotRaw)
        if (slot == "rotation") {
            vec4(value)?.let { return ok(CanonicalMutation.NodeRotationQuat(nodeId, it)) }
            vec3(value)?.let { return ok(CanonicalMutation.NodeRotationEuler(nodeId, it)) }
            return fail(FailReason.VALUE_SHAPE, pointer, "rotation slot expects quat vec4 or Euler vec3, got ${describe(value)}")
        }
        val v = vec3(value) ?: return fail(FailReason.VALUE_SHAPE, pointer, "$slot slot expects vec3, got ${describe(value)}")
        return if (slot == "scale") ok(CanonicalMutation.NodeScale(nodeId, v)) else ok(CanonicalMutation.NodePosition(nodeId, v))
    }

    private fun buildRotationAxisSlash(nodeId: String, axis: String, value: Any?, pointer: String): Dispatch {
        val n = number(value) ?: return fail(FailReason.VALUE_SHAPE, pointer, "rotation axis expects number, got ${describe(value)}")
        if (axis == "w") return ok(CanonicalMutation.NodeRotationQuatComp(nodeId, 3, n))
        return ok(CanonicalMutation.NodeRotationEulerAxis(nodeId, axisIndex(axis), n))
    }

    private fun buildNumericComp(nodeId: String, slotRaw: String, idx: Int, value: Any?, pointer: String): Dispatch {
        val n = number(value) ?: return fail(FailReason.VALUE_SHAPE, pointer, "component slot expects number, got ${describe(value)}")
        val slot = normalizeSlot(slotRaw)
        if (slot == "rotation") {
            if (idx !in 0..3) return fail(FailReason.AXIS_RANGE, pointer, "rotation component $idx out of range (0..3)")
            return ok(CanonicalMutation.NodeRotationQuatComp(nodeId, idx, n))
        }
        if (idx !in 0..2) return fail(FailReason.AXIS_RANGE, pointer, "$slot component $idx out of range (0..2)")
        return if (slot == "scale") ok(CanonicalMutation.NodeScaleAxis(nodeId, idx, n)) else ok(CanonicalMutation.NodePositionAxis(nodeId, idx, n))
    }

    private fun buildAxisDot(nodeId: String, slotRaw: String, axis: String, value: Any?, pointer: String): Dispatch {
        val n = number(value) ?: return fail(FailReason.VALUE_SHAPE, pointer, "axis slot expects number, got ${describe(value)}")
        val slot = normalizeSlot(slotRaw)
        val a = axisIndex(axis)
        return when (slot) {
            "rotation" -> ok(CanonicalMutation.NodeRotationEulerAxis(nodeId, a, n))
            "scale" -> ok(CanonicalMutation.NodeScaleAxis(nodeId, a, n))
            else -> ok(CanonicalMutation.NodePositionAxis(nodeId, a, n))
        }
    }

    private val AXIS_LANE = mapOf("x" to 0, "y" to 1, "z" to 2, "0" to 0, "1" to 1, "2" to 2)
    private val COLOR_LANE = mapOf("r" to 0, "g" to 1, "b" to 2, "0" to 0, "1" to 1, "2" to 2)

    private fun scalarOrArray(v: Any?): Any? = when (v) { is Double -> v; is DoubleArray -> v; is Number -> v.toDouble(); else -> null }

    private fun buildLight(lightId: String, property: String, component: String?, value: Any?): Dispatch {
        if (component != null) {
            val lane = COLOR_LANE[component] ?: return fail(FailReason.UNMATCHED, lightId, "light color lane '$component'")
            val n = number(value) ?: return fail(FailReason.VALUE_SHAPE, lightId, "light component expects number")
            return ok(CanonicalMutation.Light(LightMutation(lightId, property, lane, n)))
        }
        val v = scalarOrArray(value) ?: return fail(FailReason.VALUE_SHAPE, lightId, "light value expects number or array")
        return ok(CanonicalMutation.Light(LightMutation(lightId, property, null, v)))
    }

    private fun buildLightTransform(lightId: String, channel: String, component: String?, value: Any?): Dispatch {
        val property = if (channel == "rotation") "rotation" else "position"
        if (component != null) {
            val lane = AXIS_LANE[component] ?: return fail(FailReason.UNMATCHED, lightId, "light transform lane '$component'")
            val n = number(value) ?: return fail(FailReason.VALUE_SHAPE, lightId, "light transform component expects number")
            return ok(CanonicalMutation.Light(LightMutation(lightId, property, lane, n)))
        }
        val v = scalarOrArray(value) ?: return fail(FailReason.VALUE_SHAPE, lightId, "light transform value expects number or array")
        if (property == "rotation" && v is DoubleArray && v.size != 3) return fail(FailReason.VALUE_SHAPE, lightId, STORED_ANGLES)
        return ok(CanonicalMutation.Light(LightMutation(lightId, property, null, v)))
    }

    private fun buildCamera(cameraId: String, property: String, component: String?, value: Any?, pointer: String): Dispatch {
        val prop = if (property == "translation") "position" else property
        if (component != null) {
            if (prop != "position" && prop != "rotation") return fail(FailReason.UNMATCHED, pointer, "camera '$prop' has no per-axis lane")
            val lane = AXIS_LANE[component] ?: return fail(FailReason.UNMATCHED, pointer, "camera lane '$component'")
            val n = number(value) ?: return fail(FailReason.VALUE_SHAPE, pointer, "camera component expects number")
            return ok(CanonicalMutation.Camera(CameraMutation(cameraId, prop, lane, n)))
        }
        val v = scalarOrArray(value) ?: return fail(FailReason.VALUE_SHAPE, pointer, "camera value expects number or array")
        if (prop == "rotation" && v is DoubleArray && v.size != 3) return fail(FailReason.VALUE_SHAPE, pointer, STORED_ANGLES)
        return ok(CanonicalMutation.Camera(CameraMutation(cameraId, prop, null, v)))
    }

    /**
     * A light's / camera's whole rotation is the record's Euler angles `[x, y, z]` (radians, the record's
     * order): the core turns a quaternion into them. Any other length is refused — the pose hold would read
     * a quaternion's first three numbers as angles.
     */
    private const val STORED_ANGLES =
        "whole rotation expects the stored Euler angles [x, y, z] (radians); a quaternion is converted by the core"

    /**
     * An RGB write: `[r, g, b(, a)]` whole (alpha dropped) or one lane `r|g|b|0..2`. [then] gets the lane
     * (`null` = whole) and its value — a `Double` for a lane, a 3-element `DoubleArray` whole.
     */
    private inline fun rgbWrite(what: String, component: String?, value: Any?, pointer: String, then: (Int?, Any) -> Dispatch): Dispatch {
        if (component != null) {
            val lane = COLOR_LANE[component] ?: return fail(FailReason.UNMATCHED, pointer, "$what lane '$component'")
            val n = number(value) ?: return fail(FailReason.VALUE_SHAPE, pointer, "$what component expects number")
            return then(lane, n)
        }
        val arr = value as? DoubleArray
        if (arr != null && (arr.size == 3 || arr.size == 4) && arr.all { it.isFinite() }) {
            return then(null, doubleArrayOf(arr[0], arr[1], arr[2]))
        }
        return fail(FailReason.VALUE_SHAPE, pointer, "$what expects rgb(a), got ${describe(value)}")
    }

    private fun buildEnvironment(property: String, component: String?, value: Any?, pointer: String): Dispatch {
        when (property) {
            "background_color" -> return rgbWrite("background colour", component, value, pointer) { lane, v ->
                ok(CanonicalMutation.Environment(EnvironmentMutation("background_color", lane, v)))
            }
            "intensity" -> {
                if (component != null) return fail(FailReason.UNMATCHED, pointer, "environment 'intensity' has no per-axis lane")
                val n = number(value) ?: return fail(FailReason.VALUE_SHAPE, pointer, "environment intensity expects number")
                return ok(CanonicalMutation.Environment(EnvironmentMutation("intensity", null, n)))
            }
            else -> {
                if (component != null) {
                    val lane = AXIS_LANE[component] ?: return fail(FailReason.UNMATCHED, pointer, "environment lane '$component'")
                    val n = number(value) ?: return fail(FailReason.VALUE_SHAPE, pointer, "environment rotation component expects number")
                    return ok(CanonicalMutation.Environment(EnvironmentMutation("rotation", lane, n)))
                }
                val v = vec3(value) ?: return fail(FailReason.VALUE_SHAPE, pointer, "environment rotation expects vec3, got ${describe(value)}")
                return ok(CanonicalMutation.Environment(EnvironmentMutation("rotation", null, v)))
            }
        }
    }

    /** The render-settings leaves without a lane: leaf → (mutation property, what to call it). */
    private val SCALAR_RENDER_LEAVES = mapOf(
        "tone_mapping/saturation" to ("saturation" to "saturation"),
        "bloom/strength" to ("bloom_strength" to "bloom strength"),
        "pixelation/size" to ("pixelation_size" to "pixelation size"),
    )
    private val SWITCH_RENDER_LEAVES = mapOf(
        "pixelation/enabled" to ("pixelation_enabled" to "pixelation on / off"),
    )

    private fun buildRenderSettings(leaf: String, component: String?, value: Any?, pointer: String): Dispatch {
        if (leaf == "fog/color") {
            return rgbWrite("fog colour", component, value, pointer) { lane, v ->
                ok(CanonicalMutation.RenderSettings(RenderSettingsMutation("fog_color", lane, v)))
            }
        }
        SWITCH_RENDER_LEAVES[leaf]?.let { (property, label) ->
            if (component != null) return fail(FailReason.UNMATCHED, pointer, "$label has no per-channel lane")
            val on = value as? Boolean
                ?: return fail(FailReason.VALUE_SHAPE, pointer, "$label expects true or false, got ${describe(value)}")
            return ok(CanonicalMutation.RenderSettings(RenderSettingsMutation(property, null, on)))
        }
        val (property, label) = SCALAR_RENDER_LEAVES[leaf]
            ?: return fail(FailReason.UNMATCHED, pointer, "unknown render setting '$leaf'")
        if (component != null) return fail(FailReason.UNMATCHED, pointer, "$label has no per-channel lane")
        val n = number(value)?.takeIf { it.isFinite() }
            ?: return fail(FailReason.VALUE_SHAPE, pointer, "$label expects a number, got ${describe(value)}")
        return ok(CanonicalMutation.RenderSettings(RenderSettingsMutation(property, null, n)))
    }

    private class Route(val re: Regex, val build: (MatchResult, Any?, NodeResolver, String) -> Dispatch)

    private fun resolveGltf(m: MatchResult, resolver: NodeResolver, pointer: String, then: (String) -> Dispatch): Dispatch {
        return withIndex(m.groupValues[1], pointer) { index ->
            val id = resolver.nodeIdForGltfIndex(index)
                ?: return fail(FailReason.NODE_NOT_FOUND, pointer, "no node with gltfIndex=${m.groupValues[1]}")
            then(id)
        }
    }

    private fun resolveNode(m: MatchResult, resolver: NodeResolver, pointer: String, then: (String) -> Dispatch): Dispatch {
        val id = resolver.resolveManifestNode(m.groupValues[1])
            ?: return fail(FailReason.NODE_NOT_FOUND, pointer, "no manifest node '${m.groupValues[1]}'")
        return then(id)
    }

    private fun MatchResult.opt(i: Int): String? = groups[i]?.value

    private val ROUTES: List<Route> = listOf(
        Route(Regex("^/lights/([^/]+)/(intensity|range|innerConeAngle|outerConeAngle)$")) { m, v, _, _ ->
            buildLight(m.groupValues[1], m.groupValues[2], null, v)
        },
        Route(Regex("^/lights/([^/]+)/color(?:[./]([0-2rgb]))?$")) { m, v, _, _ ->
            buildLight(m.groupValues[1], "color", m.opt(2), v)
        },
        Route(Regex("^/lights/([^/]+)/(translation|position|rotation)(?:[./]([0-2xyz]))?$")) { m, v, _, _ ->
            buildLightTransform(m.groupValues[1], m.groupValues[2], m.opt(3), v)
        },
        Route(Regex("^/cameras/([^/]+)/(position|translation|rotation|yfov|znear|zfar|focusDistance|aperture)(?:[./]([0-2xyz]))?$")) { m, v, _, p ->
            buildCamera(m.groupValues[1], m.groupValues[2], m.opt(3), v, p)
        },
        Route(Regex("^/environment_settings/(rotation|intensity|background_color)(?:[./]([0-2xyzrgb]))?$")) { m, v, _, p ->
            buildEnvironment(m.groupValues[1], m.opt(2), v, p)
        },
        Route(Regex("^/render_settings/(tone_mapping/saturation|fog/color|bloom/strength|pixelation/enabled|pixelation/size)(?:[./]([0-2rgb]))?$")) { m, v, _, p ->
            buildRenderSettings(m.groupValues[1], m.opt(2), v, p)
        },
        Route(Regex("^/(generators|effectors|fields)/([^/]+)/((?!geometry$|flow$)[A-Za-z][A-Za-z0-9]*(?:/(?:[A-Za-z][A-Za-z0-9_]*|\\d+))*)$")) { m, v, _, p ->
            val value: Any = when (v) { is Double -> v; is Boolean -> v; is Number -> v.toDouble(); else -> return@Route fail(FailReason.VALUE_SHAPE, p, "recipe leaf expects number or boolean") }
            ok(CanonicalMutation.Generator(GeneratorParamMutation(m.groupValues[1], m.groupValues[2], m.groupValues[3], value)))
        },
        Route(Regex("^/nodes/(\\d+)/(translation|position|rotation|scale|visible)$")) { m, v, r, p ->
            resolveGltf(m, r, p) { id -> buildWhole(id, m.groupValues[2], v, p) }
        },
        Route(Regex("^/nodes/(\\d+)/rotation/(x|y|z|w)$")) { m, v, r, p ->
            resolveGltf(m, r, p) { id -> buildRotationAxisSlash(id, m.groupValues[2], v, p) }
        },
        Route(Regex("^/nodes/(\\d+)/(translation|position|rotation|scale)/(\\d)$")) { m, v, r, p ->
            resolveGltf(m, r, p) { id -> buildNumericComp(id, m.groupValues[2], m.groupValues[3].toInt(), v, p) }
        },
        Route(Regex("^/nodes/(\\d+)/(translation|position|rotation|scale)\\.(x|y|z)$")) { m, v, r, p ->
            resolveGltf(m, r, p) { id -> buildAxisDot(id, m.groupValues[2], m.groupValues[3], v, p) }
        },
        Route(Regex("^/nodes/(\\d+)/weights/(\\d+)$")) { m, v, _, p ->
            val n = number(v) ?: return@Route fail(FailReason.VALUE_SHAPE, p, "weight expects number, got ${describe(v)}")
            withIndex(m.groupValues[1], p) { node -> withIndex(m.groupValues[2], p) { ok(CanonicalMutation.MorphWeight(node, it, n)) } }
        },
        Route(Regex("^/node/([^/]+)/weights/(\\d+)$")) { m, v, r, p ->
            val n = number(v) ?: return@Route fail(FailReason.VALUE_SHAPE, p, "weight expects number, got ${describe(v)}")
            resolveNode(m, r, p) { id ->
                val gltfIndex = r.gltfIndexForNode(id)
                    ?: return@resolveNode fail(FailReason.NODE_NOT_FOUND, p, "no glTF index for manifest node '${m.groupValues[1]}'")
                withIndex(m.groupValues[2], p) { ok(CanonicalMutation.MorphWeight(gltfIndex, it, n)) }
            }
        },
        Route(Regex("^/node/([^/]+)/(translation|position|rotation|scale|visible)$")) { m, v, r, p ->
            resolveNode(m, r, p) { id -> buildWhole(id, m.groupValues[2], v, p) }
        },
        Route(Regex("^/node/([^/]+)/rotation/(x|y|z|w)$")) { m, v, r, p ->
            resolveNode(m, r, p) { id -> buildRotationAxisSlash(id, m.groupValues[2], v, p) }
        },
        Route(Regex("^/node/([^/]+)/(translation|position|rotation|scale)/(\\d)$")) { m, v, r, p ->
            resolveNode(m, r, p) { id -> buildNumericComp(id, m.groupValues[2], m.groupValues[3].toInt(), v, p) }
        },
        Route(Regex("^/node/([^/]+)/(translation|position|rotation|scale)\\.(x|y|z)$")) { m, v, r, p ->
            resolveNode(m, r, p) { id -> buildAxisDot(id, m.groupValues[2], m.groupValues[3], v, p) }
        },
    )

    /** Route one pointer + value; first match wins; never throws, never drops silently. */
    fun dispatch(pointer: String, value: Any?, resolver: NodeResolver): Dispatch {
        for (route in ROUTES) {
            val m = route.re.find(pointer) ?: continue
            return route.build(m, value, resolver, pointer)
        }
        return fail(FailReason.UNMATCHED, pointer, "pointer '$pointer' is not in the runtime's pointer grammar")
    }
}

/** Manifest baseline per node id (glTF node TRS); the rotation in both forms. */
internal interface BaselineLookup {
    fun position(nodeId: String): DoubleArray
    fun scale(nodeId: String): DoubleArray
    fun rotationQuat(nodeId: String): DoubleArray
    fun eulerBaseline(nodeId: String): DoubleArray
}

internal class NodeOverride {
    var position: DoubleArray? = null
    var rotation: DoubleArray? = null
    var eulerRotation: DoubleArray? = null
    var scale: DoubleArray? = null
    var visible: Boolean? = null
}

/**
 * A node's rotation: per-axis Euler wins over the whole quaternion; else the baseline. [compose] turns the
 * Euler XYZ lanes into a quaternion — the core's (`CoreRotation.quatOfEulerXYZ`); this file stays pure.
 */
internal fun resolveLeafRotation(ov: NodeOverride?, baselineQuat: DoubleArray, compose: (DoubleArray) -> DoubleArray): DoubleArray {
    ov?.eulerRotation?.let { return compose(it) }
    return ov?.rotation ?: baselineQuat
}

/**
 * Persistent override map: a leaf the runtime wrote stays until it writes again. The last rotation write
 * wins: a quaternion write clears the Euler lanes an earlier tick wrote, else the Euler would beat it at
 * [resolveLeafRotation] for the rest of the scene.
 */
internal class OverrideAccumulator(private val baseline: BaselineLookup) {
    val overrides = HashMap<String, NodeOverride>()
    val dirty = LinkedHashSet<String>()
    val morphWeights = HashMap<Int, HashMap<Int, Double>>()
    val environmentMutations = ArrayList<EnvironmentMutation>()

    private fun entry(id: String) = overrides.getOrPut(id) { NodeOverride() }

    private fun readPosition(id: String) = overrides[id]?.position ?: baseline.position(id)
    private fun readScale(id: String) = overrides[id]?.scale ?: baseline.scale(id)
    private fun readQuat(id: String) = overrides[id]?.rotation ?: baseline.rotationQuat(id)
    private fun readEuler(id: String) = overrides[id]?.eulerRotation ?: baseline.eulerBaseline(id)

    private fun patched(cur: DoubleArray, i: Int, v: Double): DoubleArray = cur.copyOf().also { it[i] = v }

    /** The single owner of node transform semantics. */
    fun apply(m: CanonicalMutation) {
        when (m) {
            is CanonicalMutation.NodePosition -> { entry(m.nodeId).position = m.value; dirty += m.nodeId }
            is CanonicalMutation.NodePositionAxis -> { entry(m.nodeId).position = patched(readPosition(m.nodeId), m.axis, m.value); dirty += m.nodeId }
            is CanonicalMutation.NodeScale -> { entry(m.nodeId).scale = m.value; dirty += m.nodeId }
            is CanonicalMutation.NodeScaleAxis -> { entry(m.nodeId).scale = patched(readScale(m.nodeId), m.axis, m.value); dirty += m.nodeId }
            is CanonicalMutation.NodeRotationQuat -> { entry(m.nodeId).apply { rotation = m.value; eulerRotation = null }; dirty += m.nodeId }
            is CanonicalMutation.NodeRotationQuatComp -> { entry(m.nodeId).apply { rotation = patched(readQuat(m.nodeId), m.comp, m.value); eulerRotation = null }; dirty += m.nodeId }
            is CanonicalMutation.NodeRotationEuler -> { entry(m.nodeId).eulerRotation = m.value; dirty += m.nodeId }
            is CanonicalMutation.NodeRotationEulerAxis -> { entry(m.nodeId).eulerRotation = patched(readEuler(m.nodeId), m.axis, m.value); dirty += m.nodeId }
            is CanonicalMutation.NodeVisible -> { entry(m.nodeId).visible = m.value; dirty += m.nodeId }
            is CanonicalMutation.MorphWeight -> morphWeights.getOrPut(m.gltfIndex) { HashMap() }[m.weightIndex] = m.value
            is CanonicalMutation.Environment -> environmentMutations += m.value
            // INTENTIONALLY_DROPPED: lights, cameras and render settings never reach the node stream —
            // routeMutations hands them to their own streams first.
            is CanonicalMutation.Light, is CanonicalMutation.Camera, is CanonicalMutation.RenderSettings -> Unit
            // INTENTIONALLY_DROPPED: an effector / field recipe leaf is evaluated by the runtime itself,
            // which announces the rebuilt geometry (`/generators/{id}/geometry`) the host then pulls.
            is CanonicalMutation.Generator -> Unit
        }
    }

    fun clearBuckets() {
        environmentMutations.clear()
    }
}

internal data class RouteProblem(val pointer: String, val code: String, val message: String)

internal class RoutedMutations {
    val material = ArrayList<MaterialMutation>()
    val camera = ArrayList<CameraMutation>()
    val light = ArrayList<LightMutation>()
    /** Generators whose geometry the runtime re-evaluated this tick (the bridge pulls what moved). */
    val generatorGeometry = ArrayList<GeneratorAnnouncement>()
    /** Generators whose pulse table changed (`/generators/{id}/flow`). */
    val generatorFlow = ArrayList<String>()
    /** Parametric datablocks rebuilt at their effective recipe (`/datablocks/{uid}/geometry`). */
    val datablockGeometry = ArrayList<String>()
    /** Nodes bent anew by their deformers, or out of their reach (`/deformed/{uid}/geometry`). */
    val deformedGeometry = ArrayList<String>()
    val renderSettings = ArrayList<RenderSettingsMutation>()
    /** `/scene/active_camera` — the last one wins; `""` = back to the host camera. */
    var activeCamera: String? = null
    val node = ArrayList<PointerMutation>()
    val problems = ArrayList<RouteProblem>()
}

/**
 * `/generators/{id}/geometry` announced: `trunkEpoch` moves only when the trunk buffers change — a
 * cloner's is always 0; `null` = a runtime that does not send it (pull everything).
 */
internal data class GeneratorAnnouncement(val id: String, val trunkEpoch: Long?)

private val RE_GENERATOR_GEOMETRY = Regex("^/generators/([^/]+)/geometry$")
private val RE_TRUNK_EPOCH = Regex("\"trunkEpoch\"\\s*:\\s*(\\d+)")
private val RE_GENERATOR_FLOW = Regex("^/generators/([^/]+)/flow$")
private val RE_DATABLOCK_GEOMETRY = Regex("^/datablocks/([^/]+)/geometry$")
private val RE_DEFORMED_GEOMETRY = Regex("^/deformed/([^/]+)/geometry$")
private val RE_MATERIAL_POINTER = Regex("^/materials/(\\d+)/(.+)$")
private val RE_MATERIAL_OVERRIDE_POINTER = Regex("^/materialOverrides/([^/]+)/(.+)$")

/**
 * Look fields of glTF glass (transmission, IOR, volume, dispersion) that the look Android recolours from
 * (`materialParams`) does not carry: the web animates them, here they keep the value the scene was saved with.
 */
internal val GLASS_FIELDS = setOf("transmission", "ior", "thickness", "attenuationColor", "attenuationDistance", "dispersion")

private val RE_GRAPH_LEAF = Regex("^nodes/[^/]+/inputs/[^/]+(?:/.+)?$")

/**
 * Whether a palette material sub-path writes a node-graph input (`nodes/{node}/inputs/{input}[/…]`):
 * it moves the slug's uniforms, never its look — the rule of the web's `isMaterialGraphLeaf` and iOS's `isGraphLeaf`.
 */
internal fun isGraphLeaf(subPath: String): Boolean = RE_GRAPH_LEAF.matches(subPath)

/** The glass field a material sub-path (`{field}[/{lane}]`, `{field}.{lane}`) writes, `null` for any other. */
internal fun glassFieldOf(subPath: String): String? = subPath.takeWhile { it.isLetter() }.takeIf { it in GLASS_FIELDS }

private fun failCode(reason: FailReason) = when (reason) {
    FailReason.NODE_NOT_FOUND -> "node-not-found"
    FailReason.VALUE_SHAPE -> "value-shape-mismatch"
    else -> "unsupported-target"
}

/**
 * Route by prefix, in this order: generators, datablocks, materials, active camera, cameras, lights,
 * render settings; everything else is the node stream (nodes, morph weights, environment, recipe leaves).
 */
internal fun routeMutations(mutations: List<PointerMutation>, resolver: NodeResolver): RoutedMutations {
    val out = RoutedMutations()
    for (m in mutations) {
        val p = m.pointer
        if (p.startsWith("/generators/")) {
            RE_GENERATOR_GEOMETRY.find(p)?.let {
                // The announcement object reaches the router as its JSON text (no org.json here: pure JVM).
                val trunk = (m.value as? String)?.let { v -> RE_TRUNK_EPOCH.find(v)?.groupValues?.get(1)?.toLongOrNull() }
                out.generatorGeometry += GeneratorAnnouncement(it.groupValues[1], trunk)
            }
            RE_GENERATOR_FLOW.find(p)?.let { out.generatorFlow += it.groupValues[1] }
            // INTENTIONALLY_DROPPED: any other generator leaf is a recipe value the runtime evaluates
            // itself; its effect arrives as the geometry announcement above.
            continue
        }
        if (p.startsWith("/datablocks/")) {
            // A rebuilt parametric datablock: the host pulls its mesh.
            RE_DATABLOCK_GEOMETRY.find(p)?.let { out.datablockGeometry += it.groupValues[1] }
            // INTENTIONALLY_DROPPED: a datablock recipe leaf is the runtime's own input; the rebuild above is its effect.
            continue
        }
        if (p.startsWith("/deformed/")) {
            // A node bent anew (or unbent): the host pulls its mesh (`deformedMesh`, `null` = its own again).
            RE_DEFORMED_GEOMETRY.find(p)?.let { out.deformedGeometry += it.groupValues[1] }
            continue
        }
        if (p.startsWith("/deformers/")) {
            // INTENTIONALLY_DROPPED: a deformer's leaves are the runtime's own input — it bends the reached
            // meshes, and they arrive as `/deformed/{uid}/geometry`.
            continue
        }
        if (p.startsWith("/tags/")) {
            // INTENTIONALLY_DROPPED: a tag's switch (`enabled`, `params/upright`) is the runtime's own
            // input — it re-poses the tagged node, and that pose arrives as node writes.
            continue
        }
        RE_MATERIAL_POINTER.find(p)?.let {
            val index = it.groupValues[1].toIntOrNull()
            if (index == null) out.problems += RouteProblem(p, failCode(FailReason.AXIS_RANGE), "index ${it.groupValues[1]} is out of range")
            else out.material += MaterialMutation(index, null, it.groupValues[2], m.value)
            continue
        }
        RE_MATERIAL_OVERRIDE_POINTER.find(p)?.let {
            val slug = it.groupValues[1]
            out.material += MaterialMutation(0, slug, it.groupValues[2], m.value)
            glassFieldOf(it.groupValues[2])?.let { field ->
                out.problems += RouteProblem(
                    "/materialOverrides/$slug/$field", "unsupported-target",
                    "material '$slug': its glass $field does not animate on Android yet — it keeps the value saved in the scene",
                )
            }
            continue
        }
        if (p == "/scene/active_camera") {
            out.activeCamera = (m.value as? String) ?: ""
            continue
        }
        if (p.startsWith("/cameras/")) {
            when (val d = PointerDispatch.dispatch(p, m.value, resolver)) {
                is Dispatch.Ok -> if (d.mutation is CanonicalMutation.Camera) out.camera += d.mutation.value
                is Dispatch.Fail -> if (d.reason != FailReason.NODE_NOT_FOUND) out.problems += RouteProblem(p, failCode(d.reason), d.message)
            }
            continue
        }
        if (p.startsWith("/lights/")) {
            when (val d = PointerDispatch.dispatch(p, m.value, resolver)) {
                is Dispatch.Ok -> if (d.mutation is CanonicalMutation.Light) out.light += d.mutation.value
                is Dispatch.Fail -> if (d.reason != FailReason.NODE_NOT_FOUND) out.problems += RouteProblem(p, failCode(d.reason), d.message)
            }
            continue
        }
        if (p.startsWith("/render_settings/")) {
            when (val d = PointerDispatch.dispatch(p, m.value, resolver)) {
                is Dispatch.Ok -> if (d.mutation is CanonicalMutation.RenderSettings) out.renderSettings += d.mutation.value
                is Dispatch.Fail -> out.problems += RouteProblem(p, failCode(d.reason), d.message)
            }
            continue
        }
        out.node += m
    }
    return out
}
