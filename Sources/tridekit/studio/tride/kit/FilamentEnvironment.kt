/**
 * The scene's environment as the core reads it on one Filament scene: the light
 * (`IndirectLight`), the shown stack (the library's `tride-sky` material) and, over a transparent frame, the
 * background eraser (`tride-sky-erase`) — from `TrideScene.environmentFrame()`; every number is the core's.
 *
 * Internal API:
 *   - `FilamentEnvironment(shared, scene, view, materialPackage, problem)`; `apply(frame, maps)` — a scene's
 *     maps, values and blend mode; `update(frame)` — the same scene's values after a write; `dispose()`.
 *   - `EnvironmentMaps(light, sky)` — the KTX bytes of the frame's maps (`<map>_ibl.ktx`, `<map>_skybox.ktx`).
 *
 * Non-obvious constraints:
 *   - Light: a map → the engine's cube and SH of its KTX (`SharedEngine.acquireIblMap`, shared by the views
 *     that light with it) under this view's own `IndirectLight` (as `KTX1Loader.createIndirectLight` builds
 *     it), so intensity and rotation stay the view's; a colour → `irradiance(1,
 *     rgb)` + a 1×1 cube of it (research `rt414-environment-model.md` §1); none → no `IndirectLight`. Intensity
 *     and rotation are the frame's (`lux`, column-major `rotation`); Filament turns the frame by the light's
 *     rotation and the core gives the shown map's turn in that world.
 *   - The sky: one full-screen triangle on the far plane, priority 7 (Filament's `Skybox` is not used); no shown
 *     map → a 1×1 black cube. Transparent frame (static per scene): the view blends `TRANSLUCENT`, the sky draws
 *     `frame.glass`, the eraser (blended pass, priority 0, before the scene's transparent objects) replaces it by
 *     `frame.sky` where the depth is the far plane (§5). Otherwise `OPAQUE` and the sky draws `frame.sky`.
 *   - A package missing from the library or a map without its file is a load problem, never a silent black.
 *   - The sky and eraser packages are the engine's (`SharedEngine.builtIn`); their instances are this view's.
 *   - Maps are a scene's: `update` keeps the textures `apply` made. The web player's twin is
 *     `packages/player/src/engine/filament-environment.ts`.
 */
package studio.tride.kit

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.utils.KTX1Loader
import studio.tride.core.TrideEnvironmentFrame
import studio.tride.core.TrideSkyStack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The KTX bytes of a frame's maps; `null` where the frame has none or its file could not be read. */
internal class EnvironmentMaps(val light: ByteArray?, val sky: ByteArray?)

/** Filament's Skybox priority: the sky draws last in the opaque pass. */
private const val SKY_PRIORITY = 7
/** First in the blended pass: after the refraction pass, before the scene's transparent objects. */
private const val ERASER_PRIORITY = 0
private val IDENTITY3 = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

internal class FilamentEnvironment(
    private val shared: SharedEngine,
    private val scene: Scene,
    private val view: View,
    /** `tride/materials/<name>.filamat` bytes of the library. */
    private val materialPackage: (String) -> ByteArray?,
    /** A load problem (subject, message). */
    private val problem: (String, String) -> Unit,
) {
    /** [cube] is this view's (a colour's); [map] the engine's (a lighting map's). */
    private class Light(val kind: String, val ibl: IndirectLight?, val cube: Texture?, val rgb: List<Double>, val map: SharedEngine.IblMap? = null)
    private class FullScreen(val mi: MaterialInstance, val entity: Int)

    private val engine: Engine get() = shared.engine

    private var light: Light? = null
    private var skyTexture: Texture? = null
    private var blackCube: Texture? = null
    private var vb: VertexBuffer? = null
    private var ib: IndexBuffer? = null
    private var sky: FullScreen? = null
    private var eraser: FullScreen? = null
    private var eraserShown = false
    private var transparent = false
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT)

    /** Whether the view blends its frame over what is under it (the last `apply`). */
    val drawsTransparent: Boolean get() = transparent

    fun apply(frame: TrideEnvironmentFrame, maps: EnvironmentMaps) {
        makeLight(frame, if (frame.light.kind == "map") maps.light else null)
        val sky = sky ?: fullScreen("tride-sky", SKY_PRIORITY)?.also { sky = it }
        dropSkyTexture()
        if (frame.sky.map != null && maps.sky != null) skyTexture = KTX1Loader.createTexture(engine, direct(maps.sky))
        val cube = skyTexture ?: black()
        sky?.let {
            it.mi.setParameter("cube", cube, sampler)
            scene.addEntity(it.entity)
        }
        transparent = frame.transparent
        if (transparent) {
            val e = eraser ?: fullScreen("tride-sky-erase", ERASER_PRIORITY)?.also { eraser = it }
            e?.mi?.setParameter("cube", cube, sampler)
            if (e != null && !eraserShown) scene.addEntity(e.entity)
            eraserShown = e != null
        } else eraser?.let { if (eraserShown) scene.removeEntity(it.entity); eraserShown = false }
        view.blendMode = if (transparent) View.BlendMode.TRANSLUCENT else View.BlendMode.OPAQUE
        setValues(frame)
    }

    fun update(frame: TrideEnvironmentFrame) {
        moveLight(frame)
        setValues(frame)
    }

    fun dispose() {
        dropLight()
        for (full in listOfNotNull(sky, eraser)) {
            scene.removeEntity(full.entity)
            engine.destroyEntity(full.entity)
            EntityManager.get().destroy(full.entity)
            engine.destroyMaterialInstance(full.mi)
        }
        sky = null; eraser = null; eraserShown = false
        skyTexture?.let { engine.destroyTexture(it) }; skyTexture = null
        blackCube?.let { engine.destroyTexture(it) }; blackCube = null
        vb?.let { engine.destroyVertexBuffer(it) }; vb = null
        ib?.let { engine.destroyIndexBuffer(it) }; ib = null
    }

    // ---- light ----

    private fun makeLight(frame: TrideEnvironmentFrame, ktx: ByteArray?) {
        dropLight()
        val l = frame.light
        light = when (l.kind) {
            "color" -> {
                val cube = solidCube(l.rgb)
                Light("color", buildIbl(cube, l.rgb, frame), cube, l.rgb)
            }
            "map" -> {
                if (ktx == null) {
                    problem("environment", "the lighting map has no KTX: the scene is lit by its lights only")
                    Light("none", null, null, emptyList())
                } else {
                    val map = shared.acquireIblMap(ktx)
                    if (map == null) {
                        problem("environment", "the lighting map's KTX carries no SH (${ktx.size} bytes): the scene is lit by its lights only")
                        Light("none", null, null, emptyList())
                    } else {
                        val ibl = IndirectLight.Builder()
                            .reflections(map.cube)
                            .irradiance(3, map.sh)
                            .intensity(l.lux.toFloat())
                            .rotation(floats(l.rotation, IDENTITY3))
                            .build(engine)
                        Light("map", ibl, null, emptyList(), map)
                    }
                }
            }
            else -> Light("none", null, null, emptyList())
        }
        scene.indirectLight = light?.ibl
    }

    private fun buildIbl(cube: Texture, rgb: List<Double>, frame: TrideEnvironmentFrame): IndirectLight =
        IndirectLight.Builder()
            .reflections(cube)
            .irradiance(1, floats(rgb, listOf(0.0, 0.0, 0.0)))
            .intensity(frame.light.lux.toFloat())
            .rotation(floats(frame.light.rotation, IDENTITY3))
            .build(engine)

    /** The frame's light on the live one; a colour that moved builds its light again over the same cube. */
    private fun moveLight(frame: TrideEnvironmentFrame) {
        val live = light ?: return
        val ibl = live.ibl ?: return
        val l = frame.light
        if (l.kind != live.kind) {
            problem("environment", "the light changed kind (${live.kind} → ${l.kind}) within a scene: not followed")
            return
        }
        if (l.kind == "color" && live.cube != null && l.rgb != live.rgb) {
            fillCube(live.cube, l.rgb)
            val next = buildIbl(live.cube, l.rgb, frame)
            scene.indirectLight = next
            engine.destroyIndirectLight(ibl)
            light = Light("color", next, live.cube, l.rgb)
            return
        }
        ibl.intensity = l.lux.toFloat()
        ibl.setRotation(floats(l.rotation, IDENTITY3))
    }

    private fun dropLight() {
        val live = light ?: return
        scene.indirectLight = null
        live.ibl?.let { engine.destroyIndirectLight(it) }
        live.cube?.let { engine.destroyTexture(it) }
        live.map?.let { shared.releaseIblMap(it) }
        light = null
    }

    // ---- sky ----

    private fun setStack(mi: MaterialInstance, stack: TrideSkyStack) {
        val map = stack.map
        val r = map?.rotation?.takeIf { it.size >= 9 } ?: IDENTITY3
        mi.setParameter("rx", r[0].toFloat(), r[1].toFloat(), r[2].toFloat())
        mi.setParameter("ry", r[3].toFloat(), r[4].toFloat(), r[5].toFloat())
        mi.setParameter("rz", r[6].toFloat(), r[7].toFloat(), r[8].toFloat())
        mi.setParameter("mapScale", (map?.scale ?: 0.0).toFloat())
        mi.setParameter("mapAlpha", (map?.alpha ?: 0.0).toFloat())
        val o = floats(stack.over, listOf(0.0, 0.0, 0.0, 0.0))
        val u = floats(stack.under, listOf(0.0, 0.0, 0.0, 0.0))
        mi.setParameter("over", o[0], o[1], o[2], o[3])
        mi.setParameter("under", u[0], u[1], u[2], u[3])
    }

    private fun setValues(frame: TrideEnvironmentFrame) {
        sky?.let { setStack(it.mi, if (transparent) frame.glass else frame.sky) }
        eraser?.takeIf { eraserShown }?.let { setStack(it.mi, frame.sky) }
    }

    private fun dropSkyTexture() {
        val t = skyTexture ?: return
        // The instances leave the texture before it goes.
        val b = black()
        sky?.mi?.setParameter("cube", b, sampler)
        eraser?.mi?.setParameter("cube", b, sampler)
        engine.destroyTexture(t)
        skyTexture = null
    }

    private fun black(): Texture = blackCube ?: solidCube(listOf(0.0, 0.0, 0.0)).also { blackCube = it }

    private fun fullScreen(name: String, priority: Int): FullScreen? {
        val material = shared.builtIn(name) { materialPackage(name) } ?: run {
            problem("environment", "the library has no '$name' material: nothing is drawn behind the scene")
            return null
        }
        val mi = material.createInstance()
        val (vertices, indices) = triangle()
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1e4f, 1e4f, 1e4f))
            .material(0, mi)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .priority(priority)
            .build(engine, entity)
        return FullScreen(mi, entity)
    }

    /** Clip space, z = 1 (the far plane), as Filament draws its Skybox; shared by the sky and the eraser. */
    private fun triangle(): Pair<VertexBuffer, IndexBuffer> {
        val v = vb ?: VertexBuffer.Builder()
            .vertexCount(3)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)
            .also { buf ->
                val data = floatArrayOf(-1f, -1f, 1f, 1f, 3f, -1f, 1f, 1f, -1f, 3f, 1f, 1f)
                val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                bb.asFloatBuffer().put(data)
                buf.setBufferAt(engine, 0, bb)
                vb = buf
            }
        val i = ib ?: IndexBuffer.Builder()
            .indexCount(3)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
            .also { buf ->
                val bb = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder())
                bb.asShortBuffer().put(shortArrayOf(0, 1, 2))
                buf.setBuffer(engine, bb)
                ib = buf
            }
        return v to i
    }

    /** A 1×1 RGBA16F cubemap of one linear colour (six texels). */
    private fun solidCube(rgb: List<Double>): Texture {
        val tex = Texture.Builder()
            .width(1).height(1).levels(1)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.RGBA16F)
            .build(engine)
        fillCube(tex, rgb)
        return tex
    }

    /**
     * The six texels as HALF, the cube's own format (as iOS fills it): a FLOAT upload into RGBA16F takes
     * Filament 1.77.1's Vulkan blit path, which leaves the image in layout UNDEFINED — its texels may be
     * lost, and the sky reads the cube even when it shows none (0 × NaN), so the background drew white.
     */
    private fun fillCube(tex: Texture, rgb: List<Double>) {
        val bb = ByteBuffer.allocateDirect(6 * 4 * 2).order(ByteOrder.nativeOrder())
        val texel = shortArrayOf(
            halfBits(rgb.getOrElse(0) { 0.0 }.toFloat()),
            halfBits(rgb.getOrElse(1) { 0.0 }.toFloat()),
            halfBits(rgb.getOrElse(2) { 0.0 }.toFloat()),
            halfBits(1f),
        )
        repeat(6) { texel.forEach(bb::putShort) }
        bb.flip()
        tex.setImage(engine, 0, 0, 0, 0, 1, 1, 6, Texture.PixelBufferDescriptor(bb, Texture.Format.RGBA, Texture.Type.HALF))
    }

    private fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }

    private fun floats(values: List<Double>, fallback: List<Double>): FloatArray {
        val src = if (values.size >= fallback.size) values else fallback
        return FloatArray(fallback.size) { src[it].toFloat() }
    }
}

/**
 * IEEE 754 half-precision bits of [f] (round to nearest even; NaN stays NaN, past 65504 is infinity,
 * below the half range is zero): `android.util.Half` needs API 26, the kit's floor is 24.
 */
internal fun halfBits(f: Float): Short {
    val bits = java.lang.Float.floatToRawIntBits(f)
    val sign = (bits ushr 16) and 0x8000
    val exp = (bits ushr 23) and 0xff
    val mant = bits and 0x7fffff
    if (exp == 0xff) return (sign or 0x7c00 or (if (mant != 0) 0x200 else 0)).toShort()
    val e = exp - 127 + 15
    if (e >= 0x1f) return (sign or 0x7c00).toShort()
    if (e <= 0) {
        if (e < -10) return sign.toShort()
        val m = mant or 0x800000
        val shift = 14 - e
        var half = m ushr shift
        val rest = m and ((1 shl shift) - 1)
        val mid = 1 shl (shift - 1)
        if (rest > mid || (rest == mid && (half and 1) != 0)) half += 1
        return (sign or half).toShort()
    }
    var half = (e shl 10) or (mant ushr 13)
    val rest = mant and 0x1fff
    if (rest > 0x1000 || (rest == 0x1000 && (half and 1) != 0)) half += 1
    return (sign or half).toShort()
}
