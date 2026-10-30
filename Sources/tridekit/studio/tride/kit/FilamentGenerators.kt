/**
 * Procedural generators on Filament-Android: trunk renderables from the runtime's `TrideGeneratedGeometry`
 * (one primitive per pen group), the flow material with its pulse table, GPU-instanced module batches
 * reading one `mat4` per instance from an RGBA32F data texture (and, under a node material with a Clone
 * Field, one `R G B value` row from an RGBA16F one), and the built-in materials with palette looks.
 *
 * Public API:
 *   - `upsertGeneratedMesh(id, geometry, host)` / `removeGeneratedMesh(id)`, `setGeneratedMeshFlow(id, table)`.
 *   - `registerModuleMesh(uid, mesh)`, `syncModuleInstances(id, symbol, uid, transforms, colors, parent, look)`,
 *     `samplesInstanceColors(slug)`, `removeModuleInstances(id)`, `moduleInstanceCount(id)`,
 *     `moduleShadowBatches(id)` / `generatedMeshCastsShadows(id)` / `nodeCastsShadows(index)` (tests).
 *   - `updatePaletteMaterial(slug, params)` — recolour every instance wearing the slug.
 *   - `generatedMaterialForSlug(slug, look)` — a baked gltfio instance by name, else a built-in one.
 *   - `registerMaterialGraph` / `updateMaterialGraph` / `dressAssetWithMaterialGraphs` — node materials:
 *     the scene's packages per family, the runtime's uniforms, the asset's slots that wear them.
 *   - Photo slots: `refreshPictures(slug, family, groups)`, `photoSlotSlugs`, `boundRevisions`.
 *
 * Non-obvious constraints:
 *   - ONE vertex layout for trunks and module meshes: POSITION / TANGENTS (quaternion frames from the
 *     normals, or from the runtime's MikkTSpace tangents of a normal-mapped mesh) / UV0 / COLOR (HALF4,
 *     UV1 aliases it) / CUSTOM0 (flow, trunks only). FFI byte streams are little-endian and wrapped as-is.
 *   - Built-in materials come from the AAR's `assets/materials/<name>.filamat` (opengl + vulkan in one
 *     package); a missing package degrades with one log line, never a crash.
 *   - Palette images decode through `BitmapFactory` into sRGB textures with mipmaps, cached per uri; a
 *     masked look (`alphaMode: mask`; `blend` and `add` without a node-material package → masked at 0.5,
 *     said once) selects the `-masked` family, a thin look (diffuse transmission / rim) the `-thin` family.
 *   - A node material's instance never takes a built-in parameter (an unknown sampler aborts in
 *     Filament); the asset's slots go back to their own instances before the asset is destroyed.
 *   - `instanceColors` is bound only on a batch whose node material samples it (`TrideMaterialGraph.
 *     instanceColors`); its 1 × capacity texture is re-uploaded with the transforms on every sync.
 *   - A renderable wearing an Add package (`TrideMaterialGraph.castShadows` false) neither
 *     casts nor receives shadows (under VSM and PCSS Filament makes every receiver a caster): trunks and
 *     batches when built, the asset's renderables when dressed (gltfio's flags back on undress).
 *   - A photo slot is a one-layer array: its placeholder (1 × 1 white without one) or the app's picture —
 *     one texture per `(input, revision, srgb)`, destroyed after the last rebind; later instances bind it too.
 */
package studio.tride.kit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.gltfio.FilamentAsset
import studio.tride.core.TrideDatablockMesh
import studio.tride.core.TrideGeneratedGeometry
import studio.tride.core.TrideHostPicture
import studio.tride.core.TrideMaterialGraph
import studio.tride.core.TrideMaterialGraphGroup
import studio.tride.core.TrideMaterialGraphValue
import studio.tride.core.TrideMaterialParams
import studio.tride.core.TridePulseLook
import studio.tride.core.TridePulseTable
import studio.tride.core.TrideTextureRef
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "tride.gen"
private const val PULSE_ROW_FLOATS = 8
private const val PULSE_CAPACITY_MAX = 4096
private const val MAX_INSTANCES_PER_BATCH = 16384
private const val TRANSFORM_STRIDE_BYTES = 64

/** Where a generated mesh hangs and how it looks. */
internal class GeneratorHost(
    val parentGltfIndex: Int?,
    val materialSlug: String?,
    val materialParams: TrideMaterialParams?,
    /** `null` = no pulses; `Some` = draw the trunk with the flow material blending this look. */
    val pulse: TridePulseLook?,
    /** The scene node the mesh is, and its `objectSeed` — a node material's per-object random colour. */
    val objectUid: String? = null,
    val objectSeed: Float = 0f,
)

/** A palette look with the slug it recolours under. */
internal class PaletteLook(val slug: String?, val params: TrideMaterialParams?)

internal class FilamentGenerators(
    private val engine: Engine,
    private val scene: Scene,
    /** `assets/materials/<name>.filamat` bytes; `null` = the package is missing. */
    private val materialPackage: (String) -> ByteArray?,
    /** Archive bytes behind a palette texture uri (`TrideScene.assetBytes`). */
    private val assetBytes: (String) -> ByteArray?,
    private val entityForGltfIndex: (Int) -> Int?,
    private val onSceneMutated: () -> Unit,
    /** A loading problem the host should hear about (subject, message); logged by the receiver. */
    private val report: (String, String) -> Unit = { _, message -> Log.w(TAG, message) },
    /** The app's picture `(input, revision)` from the host's store; `null` = not there. */
    private val hostPixels: (String, Long) -> HostPixels? = { _, _ -> null },
    /** An app picture that could not be uploaded (input, reason): the host marks the input failed. */
    private val pictureFailed: (String, String) -> Unit = { _, _ -> },
) {
    // ---- built-in materials -----------------------------------------------------------------
    private val materials = HashMap<String, Material?>()
    private fun material(name: String): Material? = materials.getOrPut(name) {
        val bytes = materialPackage(name)
        if (bytes == null || bytes.isEmpty()) {
            warnOnce("material:$name", "built-in material '$name' is missing from the library's assets — its meshes are skipped")
            return@getOrPut null
        }
        try {
            Material.Builder().payload(direct(bytes), bytes.size).build(engine)
        } catch (e: Throwable) {
            warnOnce("material:$name", "built-in material '$name' failed to build: ${e.message}"); null
        }
    }

    private fun isThin(p: TrideMaterialParams?): Boolean =
        p != null && ((p.diffuseTransmission ?: 0.0) > 0.0 || (p.rimColor != null && p.rimPower != null))

    private val warned = HashSet<String>()
    private fun warnOnce(key: String, message: String) { if (warned.add(key)) report(key.substringAfter(':'), message) }

    /**
     * Alpha-test threshold of a look on the built-in materials; `null` = opaque. They have no sorted
     * transparent and no additive path: `blend` → masked at 0.5; `add` — drawn by its node-material
     * package, here only without one — takes the same Blend fallback, never Opaque.
     */
    private fun maskCutoff(p: TrideMaterialParams?, who: String): Float? = when (p?.alphaMode) {
        "mask" -> (p.alphaCutoff ?: 0.5).toFloat()
        "blend" -> { warnOnce("blend:$who", "material '$who' is alphaMode blend — drawn masked at 0.5"); 0.5f }
        "add" -> {
            warnOnce("add:$who", "material '$who' is alphaMode add without its node-material package — drawn as the Blend fallback, masked at 0.5")
            0.5f
        }
        else -> null
    }

    private fun familyName(base: String, thin: Boolean, masked: Boolean) =
        base + (if (thin) "-thin" else "") + (if (masked) "-masked" else "")

    // ---- palette textures -------------------------------------------------------------------
    private val textures = HashMap<String, Texture?>()
    private var whiteTexture: Texture? = null
    private val nearestSampler = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)

    private fun white(): Texture {
        whiteTexture?.let { return it }
        val tex = Texture.Builder().width(1).height(1).levels(1).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8).build(engine)
        val px = ByteBuffer.allocateDirect(4).put(byteArrayOf(-1, -1, -1, -1)).apply { rewind() }
        tex.setImage(engine, 0, Texture.PixelBufferDescriptor(px, Texture.Format.RGBA, Texture.Type.UBYTE))
        whiteTexture = tex
        return tex
    }

    private fun samplerFor(ref: TrideTextureRef): TextureSampler {
        val min = when (ref.minFilter?.toInt() ?: 9987) {
            9728 -> TextureSampler.MinFilter.NEAREST
            9729 -> TextureSampler.MinFilter.LINEAR
            9984 -> TextureSampler.MinFilter.NEAREST_MIPMAP_NEAREST
            9985 -> TextureSampler.MinFilter.LINEAR_MIPMAP_NEAREST
            9986 -> TextureSampler.MinFilter.NEAREST_MIPMAP_LINEAR
            else -> TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR
        }
        val mag = if ((ref.magFilter?.toInt() ?: 9729) == 9728) TextureSampler.MagFilter.NEAREST else TextureSampler.MagFilter.LINEAR
        fun wrap(v: Int?) = when (v) { 33071 -> TextureSampler.WrapMode.CLAMP_TO_EDGE; 33648 -> TextureSampler.WrapMode.MIRRORED_REPEAT; else -> TextureSampler.WrapMode.REPEAT }
        return TextureSampler(min, mag, wrap(ref.wrapS?.toInt()), wrap(ref.wrapT?.toInt()), TextureSampler.WrapMode.CLAMP_TO_EDGE)
    }

    /** The palette image behind `ref`, decoded once per uri into an sRGB texture with mipmaps. */
    private fun textureFor(ref: TrideTextureRef): Texture? {
        if (textures.containsKey(ref.uri)) return textures[ref.uri]
        val bytes = assetBytes(ref.uri)
        if (bytes == null || bytes.isEmpty()) {
            warnOnce("texture:${ref.uri}", "texture '${ref.uri}' is not in the scene — colour factor only"); textures[ref.uri] = null; return null
        }
        val options = BitmapFactory.Options().apply { inPremultiplied = false; inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
            warnOnce("texture:${ref.uri}", "texture '${ref.uri}' could not be decoded — colour factor only"); textures[ref.uri] = null; return null
        }
        val w = bitmap.width; val h = bitmap.height
        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(pixels)
        pixels.rewind()
        bitmap.recycle()
        val tex = Texture.Builder().width(w).height(h).levels(0xff).sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8).usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
        tex.setImage(engine, 0, Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE))
        tex.generateMipmaps(engine)
        textures[ref.uri] = tex
        if (ref.texCoord != 0u) warnOnce("uv:${ref.uri}", "texture '${ref.uri}' uses TEXCOORD_${ref.texCoord} — built-in materials sample uv0 only")
        Log.i(TAG, "texture '${ref.uri}': ${w}x$h decoded for generated materials")
        return tex
    }

    private fun bindMap(mi: MaterialInstance, ref: TrideTextureRef?, map: String, weight: String) {
        val tex = ref?.let { textureFor(it) }
        if (tex != null && ref != null) { mi.setParameter(map, tex, samplerFor(ref)); mi.setParameter(weight, 1f) }
        else { mi.setParameter(map, white(), nearestSampler); mi.setParameter(weight, 0f) }
    }

    private fun applyThinParams(mi: MaterialInstance, p: TrideMaterialParams?) {
        mi.setParameter("diffuseTransmission", (p?.diffuseTransmission ?: 0.0).toFloat())
        val c = p?.diffuseTransmissionColor?.takeIf { it.size >= 3 }
        mi.setParameter("diffuseTransmissionColor", (c?.get(0) ?: 1.0).toFloat(), (c?.get(1) ?: 1.0).toFloat(), (c?.get(2) ?: 1.0).toFloat())
        val rim = p?.rimColor?.takeIf { it.size >= 3 }
        mi.setParameter("rimColor", (rim?.get(0) ?: 0.0).toFloat(), (rim?.get(1) ?: 0.0).toFloat(), (rim?.get(2) ?: 0.0).toFloat())
        mi.setParameter("rimPower", (p?.rimPower ?: 1.0).toFloat())
        bindMap(mi, p?.diffuseTransmissionColorTexture, "diffuseTransmissionColorMap", "diffuseTransmissionColorMapWeight")
    }

    /** The gltfio-named parameters of a look (defaults = the bark), maps included. */
    private fun applyGeneratedParams(mi: MaterialInstance, p: TrideMaterialParams?, thin: Boolean) {
        val bc = p?.baseColor?.takeIf { it.size >= 4 }
        if (bc != null) mi.setParameter("baseColorFactor", bc[0].toFloat(), bc[1].toFloat(), bc[2].toFloat(), bc[3].toFloat())
        else mi.setParameter("baseColorFactor", 0.42f, 0.29f, 0.17f, 1f)
        mi.setParameter("metallicFactor", (p?.metallic ?: 0.0).toFloat())
        mi.setParameter("roughnessFactor", (p?.roughness ?: 0.85).toFloat())
        val e = p?.emissive?.takeIf { it.size >= 3 }
        mi.setParameter("emissiveFactor", (e?.get(0) ?: 0.0).toFloat(), (e?.get(1) ?: 0.0).toFloat(), (e?.get(2) ?: 0.0).toFloat())
        bindMap(mi, p?.baseColorTexture, "baseColorMap", "baseColorMapWeight")
        bindMap(mi, p?.emissiveTexture, "emissiveMap", "emissiveMapWeight")
        if (thin) applyThinParams(mi, p)
    }

    /** The factor half of a look — what an animated palette entry changes. */
    private fun applyPaletteFactors(mi: MaterialInstance, p: TrideMaterialParams, thin: Boolean) {
        p.baseColor.takeIf { it.size >= 4 }?.let { mi.setParameter("baseColorFactor", it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }
        mi.setParameter("metallicFactor", p.metallic.toFloat())
        mi.setParameter("roughnessFactor", p.roughness.toFloat())
        p.emissive.takeIf { it.size >= 3 }?.let { mi.setParameter("emissiveFactor", it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
        if (thin) applyThinParams(mi, p)
    }

    // ---- palette instances of the built-in generated material ---------------------------------
    /** `slug[#thin][#mask]` → instance (one per slug and family). */
    private val paletteInstances = HashMap<String, MaterialInstance>()
    /** The host look of an L-system trunk (`fallback: "host"`): the bark. */
    private var barkInstance: MaterialInstance? = null
    var asset: FilamentAsset? = null

    /** A baked gltfio instance named `slug`, else a built-in instance carrying the core's look, else the bark. */
    fun generatedMaterialForSlug(slug: String?, params: TrideMaterialParams?): MaterialInstance? {
        val rm = engine.renderableManager
        if (!slug.isNullOrEmpty()) {
            val thin = isThin(params)
            val loaded = asset
            if (!thin && loaded != null) {
                for (e in loaded.renderableEntities) {
                    if (!rm.hasComponent(e)) continue
                    val ri = rm.getInstance(e)
                    for (p in 0 until rm.getPrimitiveCount(ri)) {
                        val mi = rm.getMaterialInstanceAt(ri, p)
                        if (mi != null && mi.name == slug) return mi
                    }
                }
            }
            if (params != null) {
                val cutoff = maskCutoff(params, slug)
                val masked = cutoff != null
                val material = material(familyName("tride-generated", thin, masked))
                    ?: (if (masked || thin) material("tride-generated") else null)
                    ?: return null
                val usedMasked = masked && material.name.contains("masked")
                val key = slug + (if (thin) "#thin" else "") + (if (usedMasked) "#mask" else "")
                val mi = paletteInstances.getOrPut(key) { material.createInstance(slug) }
                if (usedMasked) mi.setMaskThreshold(cutoff)
                applyGeneratedParams(mi, params, thin)
                return mi
            }
            warnOnce("slug:$slug", "generated mesh material '$slug' not in the loaded asset or palette — lit default")
        }
        // A look without a palette slug — the core's default look (white plastic) a
        // surface without a material wears — is factors only, one built-in instance per distinct
        // look. The bark below is the host look of an L-system trunk alone.
        if (slug.isNullOrEmpty() && params != null) {
            val material = material("tride-generated") ?: return null
            val key = "#look:" + (params.baseColor + params.metallic + params.roughness + params.emissive).joinToString(",")
            val mi = paletteInstances.getOrPut(key) { material.createInstance("tride-generated-look") }
            applyGeneratedParams(mi, params, false)
            return mi
        }
        barkInstance?.let { return it }
        val material = material("tride-generated") ?: return null
        return material.createInstance("tride-generated").also { applyGeneratedParams(it, null, false); barkInstance = it }
    }

    /** Recolour every instance drawn with `slug`: the asset's, the built-in palette ones, the module batches. */
    fun updatePaletteMaterial(slug: String, params: TrideMaterialParams): Int {
        var touched = 0
        val rm = engine.renderableManager
        asset?.let { loaded ->
            for (e in loaded.renderableEntities) {
                if (!rm.hasComponent(e)) continue
                val ri = rm.getInstance(e)
                for (p in 0 until rm.getPrimitiveCount(ri)) {
                    val mi = rm.getMaterialInstanceAt(ri, p)
                    if (mi != null && mi.name == slug) { applyPaletteFactors(mi, params, false); touched++ }
                }
            }
        }
        for ((key, mi) in paletteInstances) {
            if (key != slug && !key.startsWith("$slug#")) continue
            applyPaletteFactors(mi, params, key.contains("#thin")); touched++
        }
        for (batch in batches.values) {
            // A node material's batch takes the runtime's uniforms instead (`updateMaterialGraph`).
            if (batch.materialSlug != slug || batch.graphKey != null) continue
            applyPaletteFactors(batch.mi, params, batch.thin); touched++
        }
        for ((_, state) in meshes) {
            if (state.flowMi != null && state.baseSlug == slug) { applyGeneratedParams(state.flowMi!!, params, false); touched++ }
        }
        if (touched > 0) onSceneMutated()
        return touched
    }

    // ---- node materials ------------------------------------------------------------------------
    private class GraphArray(val name: String, val texture: Texture, val sampler: TextureSampler)

    /** A photo slot group: its placeholder array and the app's picture it binds now (`null` = the placeholder). */
    private class GraphSlot(val input: String, val srgb: Boolean, val placeholder: GraphArray) {
        var shown: HostTextureKey? = null
    }

    /** One texture per app picture and colour space, shared by every slot showing it. */
    private data class HostTextureKey(val input: String, val revision: Long, val srgb: Boolean)
    private class HostTexture(val texture: Texture, var users: Int)
    private val hostTextures = HashMap<HostTextureKey, HostTexture>()

    /**
     * A node material's package on one family: the material, its texture arrays, its photo slots, its
     * uniform table (name → lanes), the values last pushed and every live instance — the shared ones (one per
     * object only when the table reads `objectSeed`) and the batches' own.
     */
    private class GraphEntry(val material: Material) {
        val arrays = ArrayList<GraphArray>()
        val slots = ArrayList<GraphSlot>()
        val lanes = HashMap<String, Int>()
        var seeded = false
        /** It samples `instanceColors` — a Clone Field on instances. */
        var instanceColors = false
        /** Renderables wearing it cast shadows; `false` for an Add package. */
        var castShadows = true
        /** The table as last pushed, by uniform name (insertion order). */
        val values = LinkedHashMap<String, FloatArray>()
        val shared = HashMap<String, MaterialInstance>()
        val live = LinkedHashSet<MaterialInstance>()
    }

    /** `slug\u0000family` → its loaded package. */
    private val graphs = HashMap<String, GraphEntry>()
    /** The asset's slots a node material covers: entity → primitive → the instance it replaced. */
    private val graphSlots = HashMap<Int, HashMap<Int, MaterialInstance>>()
    /** Asset renderables an Add package turned shadowless → gltfio's own cast / receive. */
    private val graphShadows = HashMap<Int, Pair<Boolean, Boolean>>()

    /**
     * Whether a renderable wearing `mi` may cast shadows: `false` only for an instance of an Add package.
     * One that may not neither casts nor receives — under VSM / PCSS Filament makes receivers casters.
     */
    private fun castsShadows(mi: MaterialInstance): Boolean = graphs.values.none { !it.castShadows && mi in it.live }

    private fun graphKeyOf(slug: String, family: String) = "$slug\u0000$family"
    private fun graphFor(slug: String?, family: String): GraphEntry? =
        if (slug.isNullOrEmpty()) null else graphs[graphKeyOf(slug, family)]

    /**
     * The graph a renderable draws `slug` with on `family`. The scene keeps a slug's packages only for the
     * families its objects draw it with, so a miss here while the slug has another family's package is a
     * mismatch between the scene and this host — said once, the built-in look meanwhile.
     */
    private fun drawGraph(slug: String?, family: String): GraphEntry? {
        graphFor(slug, family)?.let { return it }
        if (slug != null && graphs.keys.any { it.startsWith("$slug\u0000") }) {
            warnOnce("family:$slug/$family", "'$slug' has no $family package — built-in look")
        }
        return null
    }

    /** One uniform by the table's lanes; `maskThreshold` is Filament's own cut-off. */
    private fun applyGraphValue(mi: MaterialInstance, g: GraphEntry, name: String, v: FloatArray) {
        if (name == "maskThreshold") { if (v.isNotEmpty()) mi.setMaskThreshold(v[0]); return }
        when (g.lanes[name]) {
            1 -> if (v.isNotEmpty()) mi.setParameter(name, v[0])
            3 -> if (v.size >= 3) mi.setParameter(name, v[0], v[1], v[2])
            4 -> if (v.size >= 4) mi.setParameter(name, v[0], v[1], v[2], v[3])
        }
    }

    /** The values last pushed. */
    private fun applyGraphValues(mi: MaterialInstance, g: GraphEntry) {
        for ((name, v) in g.values) applyGraphValue(mi, g, name, v)
    }

    /** A fresh instance of `g`: its arrays and what its slots show now bound, its last values and (on a single object) its seed. */
    private fun newGraphInstance(g: GraphEntry, seed: Float): MaterialInstance {
        val mi = g.material.createInstance()
        for (a in g.arrays) mi.setParameter(a.name, a.texture, a.sampler)
        for (s in g.slots) mi.setParameter(s.placeholder.name, slotTexture(s), s.placeholder.sampler)
        applyGraphValues(mi, g)
        if (g.seeded) mi.setParameter("objectSeed", seed)
        g.live += mi
        return mi
    }

    /** The shared instance node material `slug` wears on `family` for `objectKey`; `null` = the built-in path. */
    private fun graphInstance(slug: String?, family: String, objectKey: String?, seed: Float): MaterialInstance? {
        val g = drawGraph(slug, family) ?: return null
        return g.shared.getOrPut(if (g.seeded) objectKey.orEmpty() else "") { newGraphInstance(g, seed) }
    }

    /** A generated mesh's look: its node material (per scene node), else the built-in path. */
    private fun lookFor(slug: String?, params: TrideMaterialParams?, host: GeneratorHost): MaterialInstance? =
        graphInstance(slug, "generated", host.objectUid, host.objectSeed) ?: generatedMaterialForSlug(slug, params)

    /** A picture as straight-alpha RGBA8 of exactly `w` × `h`; `null` = undecodable or another size. */
    private fun decodeRgbaExact(bytes: ByteArray?, w: Int, h: Int): ByteBuffer? {
        if (bytes == null || bytes.isEmpty()) return null
        val options = BitmapFactory.Options().apply { inPremultiplied = false; inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            if (bitmap.width != w || bitmap.height != h || bitmap.config != Bitmap.Config.ARGB_8888) return null
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(pixels)
            pixels.rewind()
            return pixels
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * One texture group: a `SAMPLER_2D_ARRAY` of the group's size, a layer per picture (white when one
     * is missing or of another size, said once; one white layer for a slot without a placeholder), mips
     * generated by the GPU.
     */
    private fun graphArray(group: TrideMaterialGraphGroup, slug: String): GraphArray? {
        val w = group.width.toInt(); val h = group.height.toInt()
        if (w <= 0 || h <= 0 || (group.layers.isEmpty() && group.hostImage == null)) return null
        val depth = max(1, group.layers.size)
        val tex = Texture.Builder().width(w).height(h).depth(depth).levels(0xff)
            .sampler(Texture.Sampler.SAMPLER_2D_ARRAY)
            .format(if (group.srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
        for (layer in 0 until depth) {
            val ref = group.layers.getOrNull(layer)
            val pixels = ref?.let { decodeRgbaExact(assetBytes(it.uri), w, h) } ?: run {
                if (ref != null) warnOnce("graph-picture:${ref.uri}", "node material '$slug': picture '${ref.uri}' undecodable or not ${w}x$h — the layer stays white")
                direct(ByteArray(w * h * 4) { -1 })
            }
            tex.setImage(engine, 0, 0, 0, layer, w, h, 1, Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE))
        }
        tex.generateMipmaps(engine)
        val sampler = group.layers.firstNotNullOfOrNull { it }?.let { samplerFor(it) }
            ?: TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT)
        return GraphArray(group.sampler, tex, sampler)
    }

    /**
     * Load node material `slug`'s package for `family` (`mesh` / `generated` / `instanced`) with its
     * texture arrays, once per scene. The caller checked the package against this engine first — a
     * foreign package aborts in `Material.Builder`. `false` = not loaded: the slug stays built-in.
     */
    fun registerMaterialGraph(slug: String, family: String, packageBytes: ByteArray, graph: TrideMaterialGraph): Boolean {
        val key = graphKeyOf(slug, family)
        if (graphs.containsKey(key)) return true
        val material = try {
            Material.Builder().payload(direct(packageBytes), packageBytes.size).build(engine)
        } catch (e: Throwable) {
            warnOnce("graph:$slug", "node material '$slug' ($family): the package did not load — built-in look: ${e.message}")
            return false
        }
        val g = GraphEntry(material)
        g.instanceColors = graph.instanceColors
        g.castShadows = graph.castShadows
        for (p in graph.params) {
            if (p.name == "objectSeed") g.seeded = true
            g.lanes[p.name] = when (p.kind) { "float4" -> 4; "float3" -> 3; else -> 1 }
        }
        for (group in graph.groups) {
            val array = graphArray(group, slug) ?: continue
            val input = group.hostImage
            if (input == null) { g.arrays += array; continue }
            // A photo slot starts on what its input shows now (a picture set before this view existed).
            val slot = GraphSlot(input, group.srgb, array)
            g.slots += slot
            showPicture(g, slot, group.picture)
        }
        graphs[key] = g
        return true
    }

    // ---- photo slots ------------------------------------------------------------------

    private fun slotTexture(s: GraphSlot): Texture = s.shown?.let { hostTextures[it]?.texture } ?: s.placeholder.texture

    /**
     * The app's pictures node material `slug`'s `family` package shows now ([groups] = the runtime's
     * `materialGraph(slug, family).groups`), bound on every live instance; `true` when a slot moved.
     */
    fun refreshPictures(slug: String, family: String, groups: List<TrideMaterialGraphGroup>): Boolean {
        val g = graphFor(slug, family) ?: return false
        var moved = false
        for (s in g.slots) {
            val group = groups.firstOrNull { it.sampler == s.placeholder.name } ?: continue
            if (showPicture(g, s, group.picture)) moved = true
        }
        if (moved) onSceneMutated()
        return moved
    }

    /** Slugs whose loaded packages have photo slots. */
    fun photoSlotSlugs(): Set<String> =
        graphs.filterValues { it.slots.isNotEmpty() }.keys.mapTo(LinkedHashSet()) { it.substringBefore('\u0000') }

    /** The revisions of image input [input] a slot texture binds now. */
    fun boundRevisions(input: String): Set<Long> =
        hostTextures.keys.filter { it.input == input }.mapTo(HashSet()) { it.revision }

    /** Bind [picture] (or the placeholder) on slot `s` of `g` across its live instances; `true` when it moved. */
    private fun showPicture(g: GraphEntry, s: GraphSlot, picture: TrideHostPicture?): Boolean {
        val want = picture?.let { HostTextureKey(s.input, it.revision.toLong(), s.srgb) }
        if (want == s.shown) return false
        val texture = want?.let { acquireHostTexture(it) }
        val now = if (texture != null) want else null
        if (now == s.shown) return false
        for (mi in g.live) mi.setParameter(s.placeholder.name, texture ?: s.placeholder.texture, s.placeholder.sampler)
        val old = s.shown
        s.shown = now
        // Destroyed only after every instance let go of it.
        old?.let { releaseHostTexture(it) }
        return true
    }

    /**
     * The one-layer array of an app picture at its own size (sRGB per the group), with mips — shared; `null`
     * when the host's store has no such picture (a host script's `images` step: said once, the placeholder
     * shows) or the upload failed (the input is marked failed).
     */
    private fun acquireHostTexture(key: HostTextureKey): Texture? {
        hostTextures[key]?.let { it.users++; return it.texture }
        val px = hostPixels(key.input, key.revision) ?: run {
            warnOnce("host-picture:${key.input}#${key.revision}", "image input '${key.input}': picture ${key.revision} is not in the host's store — the placeholder shows")
            return null
        }
        val tex = try {
            Texture.Builder().width(px.width).height(px.height).depth(1).levels(0xff)
                .sampler(Texture.Sampler.SAMPLER_2D_ARRAY)
                .format(if (key.srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
                .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
                .also {
                    it.setImage(engine, 0, 0, 0, 0, px.width, px.height, 1, Texture.PixelBufferDescriptor(px.rgba.duplicate(), Texture.Format.RGBA, Texture.Type.UBYTE))
                    it.generateMipmaps(engine)
                }
        } catch (e: Exception) {
            pictureFailed(key.input, "the picture (${px.width}x${px.height}) could not be uploaded: ${e.message ?: e}")
            return null
        }
        hostTextures[key] = HostTexture(tex, 1)
        Log.i(TAG, "image input '${key.input}': picture ${key.revision} ${px.width}x${px.height} uploaded (${if (key.srgb) "sRGB" else "linear"})")
        return tex
    }

    private fun releaseHostTexture(key: HostTextureKey) {
        val t = hostTextures[key] ?: return
        if (--t.users > 0) return
        hostTextures.remove(key)
        engine.destroyTexture(t.texture)
    }

    /**
     * The runtime's uniforms of `slug` on `family` onto every live instance; the count touched. The ones
     * handed over replace theirs in the table, the others keep theirs (a caller sends only what moved).
     */
    fun updateMaterialGraph(slug: String, family: String, values: List<TrideMaterialGraphValue>): Int {
        val g = graphFor(slug, family) ?: return 0
        for (v in values) {
            val lanes = FloatArray(v.values.size) { v.values[it].toFloat() }
            g.values[v.name] = lanes
            for (mi in g.live) applyGraphValue(mi, g, v.name, lanes)
        }
        if (g.live.isNotEmpty() && values.isNotEmpty()) onSceneMutated()
        return g.live.size
    }

    /** The table `slug` on `family` holds now, by uniform name (`null` = no such package) — what a fresh instance starts from. */
    internal fun materialGraphTable(slug: String, family: String): Map<String, FloatArray>? = graphFor(slug, family)?.values

    /**
     * Dress the asset's slots whose own instance names a node material (gltfio names instances after
     * the glTF material, the palette slug) in its `mesh` package; one instance per glTF node when the
     * table reads `objectSeed`, seeded from `objectSeeds` (glTF index → seed). The count dressed.
     */
    fun dressAssetWithMaterialGraphs(objectSeeds: Map<Int, Float>): Int {
        val loaded = asset ?: return 0
        if (graphs.isEmpty()) return 0
        val nodeOf = HashMap<Int, Int>()
        for (index in objectSeeds.keys) entityForGltfIndex(index)?.let { nodeOf[it] = index }
        val rm = engine.renderableManager
        var dressed = 0
        for (e in loaded.renderableEntities) {
            if (!rm.hasComponent(e)) continue
            val ri = rm.getInstance(e)
            val node = nodeOf[e]
            val seed = node?.let { objectSeeds[it] } ?: 0f
            val slots = graphSlots.getOrPut(e) { HashMap() }
            var casts = true
            for (p in 0 until rm.getPrimitiveCount(ri)) {
                val worn = rm.getMaterialInstanceAt(ri, p)
                val own = slots[p] ?: worn
                val graph = graphInstance(own.name, "mesh", node?.toString(), seed) ?: continue
                if (!castsShadows(graph)) casts = false
                if (graph.nativeObject == worn.nativeObject) continue
                if (!slots.containsKey(p)) slots[p] = worn
                rm.setMaterialInstanceAt(ri, p, graph)
                dressed++
            }
            // An Add package on the renderable turns its shadows off (receiving too); gltfio's
            // flags wait for the undress.
            if (!casts && e !in graphShadows) {
                graphShadows[e] = rm.isShadowCaster(ri) to rm.isShadowReceiver(ri)
                rm.setCastShadows(ri, false)
                rm.setReceiveShadows(ri, false)
            }
            if (slots.isEmpty()) graphSlots.remove(e)
        }
        if (dressed > 0) onSceneMutated()
        return dressed
    }

    /** Every slot a node material covers back on its own instance, and its own shadow flags (the asset still alive). */
    private fun undressAssetMaterialGraphs() {
        val loaded = asset
        if (loaded != null && (graphSlots.isNotEmpty() || graphShadows.isNotEmpty())) {
            val rm = engine.renderableManager
            for (e in loaded.renderableEntities) {
                if (!rm.hasComponent(e)) continue
                val ri = rm.getInstance(e)
                graphShadows[e]?.let { (cast, receive) -> rm.setCastShadows(ri, cast); rm.setReceiveShadows(ri, receive) }
                val slots = graphSlots[e] ?: continue
                for ((p, mi) in slots) rm.setMaterialInstanceAt(ri, p, mi)
            }
        }
        graphShadows.clear()
        graphSlots.clear()
    }

    /** Every node material, its arrays and its instances — once no renderable wears them. */
    private fun destroyMaterialGraphs() {
        for (g in graphs.values) {
            for (mi in g.live) engine.destroyMaterialInstance(mi)
            for (a in g.arrays) engine.destroyTexture(a.texture)
            for (s in g.slots) engine.destroyTexture(s.placeholder.texture)
            engine.destroyMaterial(g.material)
        }
        graphs.clear()
        for (t in hostTextures.values) engine.destroyTexture(t.texture)
        hostTextures.clear()
    }

    // ---- shared vertex layout -----------------------------------------------------------------
    private fun direct(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }

    private fun buildVertexBuffer(vertexCapacity: Int, withFlow: Boolean): VertexBuffer {
        val b = VertexBuffer.Builder()
            .vertexCount(vertexCapacity)
            .bufferCount(if (withFlow) 5 else 4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.UV0, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            // COLOR as HALF4 (the colour ramp) with UV1 aliasing its first 4 bytes.
            .attribute(VertexBuffer.VertexAttribute.COLOR, 3, VertexBuffer.AttributeType.HALF4, 0, 8)
            .attribute(VertexBuffer.VertexAttribute.UV1, 3, VertexBuffer.AttributeType.USHORT2, 0, 8)
            .normalized(VertexBuffer.VertexAttribute.UV1)
        if (withFlow) b.attribute(VertexBuffer.VertexAttribute.CUSTOM0, 4, VertexBuffer.AttributeType.FLOAT4, 0, 16)
        return b.build(engine)
    }

    /** IEEE 754 binary32 → binary16 (round to nearest even), clamped to the finite range. */
    private fun toHalf(value: Float): Short {
        val f = if (value.isFinite()) value else 0f
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exponent = ((bits ushr 23) and 0xff) - 127 + 15
        val mantissa = bits and 0x7fffff
        if (exponent >= 0x1f) return (sign or 0x7bff).toShort()
        if (exponent <= 0) {
            if (exponent < -10) return sign.toShort()
            val m = (mantissa or 0x800000) ushr (1 - exponent)
            return (sign or ((m + 0x1000 + ((m ushr 13) and 1) - 1) ushr 13)).toShort()
        }
        val rounded = mantissa + 0xfff + ((mantissa ushr 13) and 1)
        if (rounded and 0x800000 != 0) return (if (exponent + 1 >= 0x1f) sign or 0x7bff else sign or ((exponent + 1) shl 10)).toShort()
        return (sign or (exponent shl 10) or (rounded ushr 13)).toShort()
    }

    /**
     * `count` little-endian `f32` of a runtime buffer as one bulk copy — per-element `ByteBuffer`
     * reads cost ~100 ms on a 41 k-vertex mesh on a 2021 flagship phone, far over a frame.
     */
    private fun floatsOf(bytes: ByteArray, count: Int): FloatArray {
        val out = FloatArray(count)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out, 0, count)
        return out
    }

    private fun directOf(values: FloatArray): ByteBuffer {
        val out = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        out.asFloatBuffer().put(values)
        return out
    }

    /** RGBA `f32` per vertex → the HALF4 slot; white when the mesh carries no colours. */
    private fun packColors(colors: ByteArray, vertexCount: Int): ByteBuffer {
        val halves = ShortArray(vertexCount * 4)
        if (colors.size == vertexCount * 16) {
            val src = floatsOf(colors, vertexCount * 4)
            for (i in halves.indices) halves[i] = toHalf(src[i].coerceIn(0f, 1f))
        } else {
            halves.fill(toHalf(1f))
        }
        val out = ByteBuffer.allocateDirect(vertexCount * 8).order(ByteOrder.nativeOrder())
        out.asShortBuffer().put(halves)
        return out
    }

    /**
     * Tangent frames from normals, packed like Filament's `packTangentFrame` (`mat3f{t, cross(n, t), n}`):
     * the lit materials need the quaternion attribute, not normals; `lines` and normal-less meshes
     * carry identity frames.
     */
    private fun tangentFrames(normals: ByteArray, vertexCount: Int): ByteBuffer {
        val frames = FloatArray(vertexCount * 4)
        val src = if (normals.size == vertexCount * 12) floatsOf(normals, vertexCount * 3) else null
        for (i in 0 until vertexCount) {
            if (src == null) { frames[i * 4 + 3] = 1f; continue }
            var nx = src[i * 3]; var ny = src[i * 3 + 1]; var nz = src[i * 3 + 2]
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-12f) { nx /= len; ny /= len; nz /= len } else { nx = 0f; ny = 1f; nz = 0f }
            // t = normalize(cross(helper, n)); b = cross(n, t)
            val hx: Float; val hy: Float; val hz: Float
            if (abs(ny) < 0.9f) { hx = 0f; hy = 1f; hz = 0f } else { hx = 1f; hy = 0f; hz = 0f }
            var tx = hy * nz - hz * ny; var ty = hz * nx - hx * nz; var tz = hx * ny - hy * nx
            val tl = sqrt(tx * tx + ty * ty + tz * tz).takeIf { it > 1e-12f } ?: 1f
            tx /= tl; ty /= tl; tz /= tl
            val bx = ny * tz - nz * ty; val by = nz * tx - nx * tz; val bz = nx * ty - ny * tx
            // Rotation matrix with columns (t, b, n) → quaternion, w ≥ 0, then negated: Filament's
            // packing flags `dot(cross(t, n), b) < 0` with the sign (our b = cross(n, t) always does).
            val m00 = tx; val m01 = bx; val m02 = nx
            val m10 = ty; val m11 = by; val m12 = ny
            val m20 = tz; val m21 = bz; val m22 = nz
            val trace = m00 + m11 + m22
            var qx: Float; var qy: Float; var qz: Float; var qw: Float
            if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                qw = 0.25f * s; qx = (m21 - m12) / s; qy = (m02 - m20) / s; qz = (m10 - m01) / s
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                qw = (m21 - m12) / s; qx = 0.25f * s; qy = (m01 + m10) / s; qz = (m02 + m20) / s
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                qw = (m02 - m20) / s; qx = (m01 + m10) / s; qy = 0.25f * s; qz = (m12 + m21) / s
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                qw = (m10 - m01) / s; qx = (m02 + m20) / s; qy = (m12 + m21) / s; qz = 0.25f * s
            }
            val ql = sqrt(qx * qx + qy * qy + qz * qz + qw * qw).takeIf { it > 1e-12f } ?: 1f
            qx /= ql; qy /= ql; qz /= ql; qw /= ql
            if (qw < 0f) { qx = -qx; qy = -qy; qz = -qz; qw = -qw }
            val bias = 1f / 32767f
            if (qw < bias) { qw = bias; val k = sqrt(1f - bias * bias); qx *= k; qy *= k; qz *= k }
            frames[i * 4] = -qx; frames[i * 4 + 1] = -qy; frames[i * 4 + 2] = -qz; frames[i * 4 + 3] = -qw
        }
        return directOf(frames)
    }

    private fun boundsOf(positions: ByteArray, vertexCount: Int): Box {
        if (vertexCount == 0) return Box(0f, 0f, 0f, 0.5f, 0.5f, 0.5f)
        val src = floatsOf(positions, vertexCount * 3)
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until vertexCount) for (k in 0..2) {
            val v = src[i * 3 + k]
            if (v < mn[k]) mn[k] = v
            if (v > mx[k]) mx[k] = v
        }
        return Box((mn[0] + mx[0]) * 0.5f, (mn[1] + mx[1]) * 0.5f, (mn[2] + mx[2]) * 0.5f,
            max((mx[0] - mn[0]) * 0.5f, 1e-4f), max((mx[1] - mn[1]) * 0.5f, 1e-4f), max((mx[2] - mn[2]) * 0.5f, 1e-4f))
    }

    /**
     * The runtime's MikkTSpace frame of a normal-mapped mesh, packed by Filament's own
     * `SurfaceOrientation` (supplied tangents) — the shader's bitangent is glTF's `cross(n, t) · w`, as
     * gltfio and the other hosts frame theirs. A refused build falls back to the normals.
     */
    private fun tangentFramesFromTangents(normals: ByteArray, tangents: ByteArray, vertexCount: Int): ByteBuffer {
        val orientation = runCatching {
            SurfaceOrientation.Builder()
                .vertexCount(vertexCount)
                .normals(direct(normals))
                .tangents(direct(tangents))
                .build()
        }.getOrElse { e ->
            warnOnce("tangents", "a mesh's tangent frame was refused by SurfaceOrientation (${e.message}) — its normal map is lit from the normals alone")
            return tangentFrames(normals, vertexCount)
        }
        val out = ByteBuffer.allocateDirect(vertexCount * 16).order(ByteOrder.nativeOrder())
        try {
            orientation.getQuatsAsFloat(out)
        } finally {
            orientation.destroy()
        }
        return out
    }

    private fun uploadStreams(vb: VertexBuffer, ib: IndexBuffer, positions: ByteArray, normals: ByteArray, uvs: ByteArray, colors: ByteArray, tangents: ByteArray, indices: ByteArray, flow: ByteArray?, lines: Boolean) {
        val vertexCount = positions.size / 12
        vb.setBufferAt(engine, 0, direct(positions))
        val frames = when {
            lines -> tangentFrames(ByteArray(0), vertexCount)
            normals.size == vertexCount * 12 && tangents.size == vertexCount * 16 ->
                tangentFramesFromTangents(normals, tangents, vertexCount)
            else -> tangentFrames(normals, vertexCount)
        }
        vb.setBufferAt(engine, 1, frames)
        vb.setBufferAt(engine, 2, if (uvs.size == vertexCount * 8) direct(uvs) else ByteBuffer.allocateDirect(vertexCount * 8))
        vb.setBufferAt(engine, 3, packColors(colors, vertexCount))
        if (flow != null) vb.setBufferAt(engine, 4, if (flow.size == vertexCount * 16) direct(flow) else ByteBuffer.allocateDirect(vertexCount * 16))
        ib.setBuffer(engine, direct(indices))
    }

    private fun parentUnder(entity: Int, parentGltfIndex: Int?) {
        val tm = engine.transformManager
        if (!tm.hasComponent(entity)) tm.create(entity)
        val parent = parentGltfIndex?.let { entityForGltfIndex(it) } ?: return
        if (tm.hasComponent(parent)) tm.setParent(tm.getInstance(entity), tm.getInstance(parent))
    }

    // ---- generated trunks ---------------------------------------------------------------------
    private class Primitive(val start: Int, val count: Int, val mi: MaterialInstance)

    private class MeshState(val entity: Int) {
        var vb: VertexBuffer? = null
        var ib: IndexBuffer? = null
        var vertexCapacity = 0
        var indexCapacity = 0
        var lines = false
        var flow = false
        var mi: MaterialInstance? = null
        var primitives: List<Primitive> = emptyList()
        var epoch = 0uL
        var flowMi: MaterialInstance? = null
        var pulseTexture: Texture? = null
        var pulseCapacity = 0
        var pulseCount = 0
        var baseSlug: String? = null
    }
    private val meshes = HashMap<String, MeshState>()

    fun generatedMeshCount() = meshes.size
    fun hasGeneratedMesh(id: String) = meshes.containsKey(id)

    private fun destroyMeshState(s: MeshState) {
        val rm = engine.renderableManager
        val tm = engine.transformManager
        scene.removeEntity(s.entity)
        if (rm.hasComponent(s.entity)) rm.destroy(s.entity)
        if (tm.hasComponent(s.entity)) tm.destroy(tm.getInstance(s.entity))
        EntityManager.get().destroy(s.entity)
        s.vb?.let { engine.destroyVertexBuffer(it) }; s.vb = null
        s.ib?.let { engine.destroyIndexBuffer(it) }; s.ib = null
        s.flowMi?.let { engine.destroyMaterialInstance(it) }; s.flowMi = null
        s.pulseTexture?.let { engine.destroyTexture(it) }; s.pulseTexture = null
        s.pulseCapacity = 0; s.pulseCount = 0
    }

    private fun allocatePulseTable(s: MeshState, capacity: Int) {
        s.pulseTexture?.let { engine.destroyTexture(it) }
        s.pulseCapacity = capacity.coerceIn(1, PULSE_CAPACITY_MAX)
        s.pulseTexture = Texture.Builder().width(2).height(s.pulseCapacity).levels(1).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA32F).build(engine)
        s.flowMi!!.setParameter("pulses", s.pulseTexture!!, nearestSampler)
    }

    private fun uploadPulseRows(s: MeshState, rows: ByteArray?, count: Int) {
        val bytes = s.pulseCapacity * PULSE_ROW_FLOATS * 4
        val staging = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        if (rows != null && count > 0) {
            staging.asFloatBuffer().put(floatsOf(rows, count * PULSE_ROW_FLOATS))
        }
        staging.rewind()
        s.pulseTexture!!.setImage(engine, 0, Texture.PixelBufferDescriptor(staging, Texture.Format.RGBA, Texture.Type.FLOAT))
        s.pulseCount = count
        s.flowMi!!.setParameter("pulseCount", count.toFloat())
    }

    /** The flow instance of a pulsed trunk: the trunk's look + the pulse look the mask blends over it. */
    private fun flowInstanceFor(s: MeshState, key: String, base: TrideMaterialParams?, pulse: TridePulseLook): MaterialInstance? {
        if (s.flowMi == null) {
            val material = material("tride-generated-flow") ?: return null
            s.flowMi = material.createInstance(key)
            allocatePulseTable(s, 1)
            uploadPulseRows(s, null, 0)
            s.flowMi!!.setParameter("pulseLength", 0f); s.flowMi!!.setParameter("pulseTail", 0f); s.flowMi!!.setParameter("pulseSpread", 0f)
        }
        val mi = s.flowMi!!
        applyGeneratedParams(mi, base, false)
        val look = pulse.materialParams
        val surface = look ?: base
        val bc = surface?.baseColor?.takeIf { it.size >= 4 }
        if (bc != null) mi.setParameter("pulseBaseColorFactor", bc[0].toFloat(), bc[1].toFloat(), bc[2].toFloat(), bc[3].toFloat())
        else mi.setParameter("pulseBaseColorFactor", 0.42f, 0.29f, 0.17f, 1f)
        mi.setParameter("pulseMetallicFactor", (surface?.metallic ?: 0.0).toFloat())
        mi.setParameter("pulseRoughnessFactor", (surface?.roughness ?: 0.85).toFloat())
        // The pulses' own light MULTIPLIES this: a look glows with its emissive, no look = white.
        val e = look?.emissive?.takeIf { it.size >= 3 }
        if (look != null) mi.setParameter("pulseEmissiveFactor", (e?.get(0) ?: 0.0).toFloat(), (e?.get(1) ?: 0.0).toFloat(), (e?.get(2) ?: 0.0).toFloat())
        else mi.setParameter("pulseEmissiveFactor", 1f, 1f, 1f)
        return mi
    }

    /** Primitives of the geometry: group 0 = the trunk material, pens their own palette look. */
    private fun primitivesOf(g: TrideGeneratedGeometry, trunk: MaterialInstance, host: GeneratorHost, indexCount: Int, key: String): List<Primitive> {
        if (g.groups.isEmpty()) return listOf(Primitive(0, indexCount, trunk))
        val out = ArrayList<Primitive>()
        var plain: MaterialInstance? = null
        for ((i, group) in g.groups.withIndex()) {
            val start = group.indexStart.toInt(); val n = group.indexCount.toInt()
            if (n == 0) continue
            if (start + n > indexCount) { Log.e(TAG, "generated mesh '$key': group $i [$start, +$n) past $indexCount indices — skipped"); continue }
            var mi = trunk
            if (i > 0) {
                val own = group.material?.takeIf { it.isNotEmpty() }?.let { lookFor(it, group.materialParams, host) }
                mi = own ?: (plain ?: lookFor(host.materialSlug, host.materialParams, host).also { plain = it }) ?: trunk
            }
            out += Primitive(start, n, mi)
        }
        if (out.isEmpty()) out += Primitive(0, indexCount, trunk)
        return out
    }

    private fun sameMaterials(a: List<Primitive>, b: List<Primitive>) = a.size == b.size && a.indices.all { a[it].mi === b[it].mi }

    /** Create or update the trunk renderable of generator `id` from the runtime's buffers (in place when they fit). */
    fun upsertGeneratedMesh(id: String, g: TrideGeneratedGeometry, host: GeneratorHost): Boolean {
        val positions = g.positions; val indices = g.indices
        if (positions.size % 12 != 0 || indices.size % 4 != 0) return false
        val vertexCount = positions.size / 12
        val indexCount = indices.size / 4
        val lines = g.topology == "lines"
        val type = if (lines) RenderableManager.PrimitiveType.LINES else RenderableManager.PrimitiveType.TRIANGLES
        val rm = engine.renderableManager
        if (vertexCount == 0 || indexCount == 0) {
            meshes.remove(id)?.let { destroyMeshState(it); onSceneMutated() }
            return true
        }
        val wantFlow = !lines && host.pulse != null && g.flow.size == vertexCount * 16
        val state = meshes.getOrPut(id) { MeshState(EntityManager.get().create()) }
        var mi: MaterialInstance? = if (wantFlow) flowInstanceFor(state, id, host.materialParams, host.pulse!!) else null
        val drawFlow = mi != null
        if (drawFlow && maskCutoff(host.materialParams, id) != null) warnOnce("flow-mask:$id", "generated mesh '$id': pulsed trunk with a masked look — drawn opaque")
        if (drawFlow && isThin(host.materialParams)) warnOnce("flow-thin:$id", "generated mesh '$id': pulsed trunk with a thin look — the pulses win")
        if (mi == null) mi = lookFor(host.materialSlug, host.materialParams, host)
        if (mi == null) {
            warnOnce("mesh:$id", "generated mesh '$id': no material available — not drawn")
            if (!rm.hasComponent(state.entity)) { destroyMeshState(state); meshes.remove(id) }
            return false
        }
        state.baseSlug = host.materialSlug
        val primitives = primitivesOf(g, mi, host, indexCount, id)
        var needRenderable = true
        if (state.vb != null && state.ib != null) {
            val fits = vertexCount <= state.vertexCapacity && indexCount <= state.indexCapacity && state.flow == drawFlow
            if (fits && state.lines == lines && state.mi === mi && sameMaterials(state.primitives, primitives) && rm.hasComponent(state.entity)) {
                needRenderable = false
            } else {
                if (rm.hasComponent(state.entity)) rm.destroy(state.entity)
                if (!fits) {
                    engine.destroyVertexBuffer(state.vb!!); state.vb = null
                    engine.destroyIndexBuffer(state.ib!!); state.ib = null
                }
            }
        }
        if (state.vb == null || state.ib == null) {
            state.vertexCapacity = max((vertexCount * 3 + 1) / 2, 1)
            state.indexCapacity = max((indexCount * 3 + 1) / 2, 1)
            state.flow = drawFlow
            state.vb = buildVertexBuffer(state.vertexCapacity, drawFlow)
            state.vb!!.setBufferAt(engine, 3, packColors(ByteArray(0), state.vertexCapacity))
            if (drawFlow) state.vb!!.setBufferAt(engine, 4, ByteBuffer.allocateDirect(state.vertexCapacity * 16))
            state.ib = IndexBuffer.Builder().indexCount(state.indexCapacity).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        }
        uploadStreams(state.vb!!, state.ib!!, positions, g.normals, g.uvs, g.colors, g.tangents, indices, if (state.flow) g.flow else null, lines)
        if (!state.flow && state.flowMi != null) {
            // Pulses left the recipe: the renderable points at the plain material again.
            engine.destroyMaterialInstance(state.flowMi!!); state.flowMi = null
            state.pulseTexture?.let { engine.destroyTexture(it) }; state.pulseTexture = null
            state.pulseCapacity = 0; state.pulseCount = 0
        }
        val box = boundsOf(positions, vertexCount)
        state.lines = lines; state.mi = mi; state.primitives = primitives; state.epoch = g.epoch
        if (needRenderable) {
            val builder = RenderableManager.Builder(primitives.size).boundingBox(box)
            for ((i, p) in primitives.withIndex()) builder.material(i, p.mi).geometry(i, type, state.vb!!, state.ib!!, p.start, p.count)
            // A pen in an Add package turns the whole renderable's shadows off (receiving too).
            val shaded = !lines && primitives.all { castsShadows(it.mi) }
            builder.culling(true).castShadows(shaded).receiveShadows(shaded).build(engine, state.entity)
            scene.addEntity(state.entity)
        } else {
            val ri = rm.getInstance(state.entity)
            for ((i, p) in primitives.withIndex()) rm.setGeometryAt(ri, i, type, state.vb!!, state.ib!!, p.start, p.count)
            rm.setAxisAlignedBoundingBox(ri, box)
        }
        parentUnder(state.entity, host.parentGltfIndex)
        onSceneMutated()
        return true
    }

    fun removeGeneratedMesh(id: String) {
        meshes.remove(id)?.let { destroyMeshState(it); onSceneMutated() }
    }

    /** The runtime's pulse table onto the trunk's flow instance; `null` = every pulse left. `false` = no pulsed trunk. */
    fun setGeneratedMeshFlow(id: String, table: TridePulseTable?): Boolean {
        val s = meshes[id] ?: return false
        if (s.flowMi == null) return false
        if (table != null && table.rows.size % (PULSE_ROW_FLOATS * 4) != 0) return false
        val wanted = table?.capacity?.toInt()?.coerceIn(1, PULSE_CAPACITY_MAX) ?: s.pulseCapacity
        if (wanted != s.pulseCapacity) allocatePulseTable(s, wanted)
        val live = table?.rows?.size?.div(PULSE_ROW_FLOATS * 4) ?: 0
        uploadPulseRows(s, table?.rows, min(live, s.pulseCapacity))
        s.flowMi!!.setParameter("pulseLength", table?.length ?: 0f)
        s.flowMi!!.setParameter("pulseTail", table?.tail ?: 0f)
        s.flowMi!!.setParameter("pulseSpread", table?.spread ?: 0f)
        onSceneMutated()
        return true
    }

    // ---- module instances (data-texture instancing) --------------------------------------------
    private class ModuleMesh(val vb: VertexBuffer, val ib: IndexBuffer, val indexCount: Int, val radius: Float, val contentHash: String)
    private class Batch(val entity: Int, var meshUid: String, var mi: MaterialInstance, var masked: Boolean, var thin: Boolean) {
        var materialSlug: String? = null
        /** `mi` is an instance of this node material (`slug\u0000instanced`); `null` = built-in. */
        var graphKey: String? = null
        var texture: Texture? = null
        /** The instance rows (1 × `capacity` RGBA16F), only while `mi` samples `instanceColors`. */
        var colorTexture: Texture? = null
        var capacity = 0
        var count = 0
        var visible = false
        var bounds: Box = Box(0f, 0f, 0f, 1e-4f, 1e-4f, 1e-4f)
    }
    private val moduleMeshes = HashMap<String, ModuleMesh>()
    private val batches = LinkedHashMap<String, Batch>()

    /** Upload (or replace) the datablock mesh module instances draw; existing batches move to the new buffers. */
    fun registerModuleMesh(uid: String, mesh: TrideDatablockMesh): Boolean {
        val existing = moduleMeshes[uid]
        if (existing != null && existing.contentHash == mesh.contentHash) return true
        val positions = mesh.positions; val indices = mesh.indices
        if (positions.size < 36 || positions.size % 12 != 0 || indices.size < 12 || indices.size % 4 != 0) return false
        val vertexCount = positions.size / 12
        val indexCount = indices.size / 4
        val vb = buildVertexBuffer(vertexCount, false)
        val ib = IndexBuffer.Builder().indexCount(indexCount).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        uploadStreams(vb, ib, positions, mesh.normals, mesh.uvs, mesh.colors, mesh.tangents, indices, null, false)
        val box = boundsOf(positions, vertexCount)
        val c = box.center; val h = box.halfExtent
        val radius = sqrt((abs(c[0]) + h[0]) * (abs(c[0]) + h[0]) + (abs(c[1]) + h[1]) * (abs(c[1]) + h[1]) + (abs(c[2]) + h[2]) * (abs(c[2]) + h[2]))
        val entry = ModuleMesh(vb, ib, indexCount, radius, mesh.contentHash)
        moduleMeshes[uid] = entry
        if (existing != null) {
            val rm = engine.renderableManager
            for (batch in batches.values) {
                if (batch.meshUid != uid || !rm.hasComponent(batch.entity)) continue
                rm.destroy(batch.entity)
                buildBatchRenderable(batch, entry)
            }
            engine.destroyVertexBuffer(existing.vb); engine.destroyIndexBuffer(existing.ib)
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "module mesh '$uid' replaced: $vertexCount verts, $indexCount indices")
        } else Log.i(TAG, "module mesh '$uid': $vertexCount verts, $indexCount indices")
        return true
    }

    private fun buildBatchRenderable(batch: Batch, mesh: ModuleMesh) {
        RenderableManager.Builder(1)
            .boundingBox(batch.bounds)
            .material(0, batch.mi)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vb, mesh.ib, 0, mesh.indexCount)
            .culling(true).castShadows(castsShadows(batch.mi)).receiveShadows(castsShadows(batch.mi))
            .instances(batch.count)
            .build(engine, batch.entity)
    }

    private fun instancesBounds(m: ByteBuffer, count: Int, meshRadius: Float): Box {
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until count) {
            val o = i * TRANSFORM_STRIDE_BYTES
            var scale = 0f
            for (c in 0..2) {
                val x = m.getFloat(o + c * 16); val y = m.getFloat(o + c * 16 + 4); val z = m.getFloat(o + c * 16 + 8)
                scale = max(scale, sqrt(x * x + y * y + z * z))
            }
            val r = meshRadius * scale
            for (k in 0..2) {
                val p = m.getFloat(o + 48 + k * 4)
                if (p - r < mn[k]) mn[k] = p - r
                if (p + r > mx[k]) mx[k] = p + r
            }
        }
        if (count == 0) return Box(0f, 0f, 0f, 1e-4f, 1e-4f, 1e-4f)
        return Box((mn[0] + mx[0]) * 0.5f, (mn[1] + mx[1]) * 0.5f, (mn[2] + mx[2]) * 0.5f,
            max((mx[0] - mn[0]) * 0.5f, 1e-4f), max((mx[1] - mn[1]) * 0.5f, 1e-4f), max((mx[2] - mn[2]) * 0.5f, 1e-4f))
    }

    /** A batch lets go of its instance: destroyed, and forgotten by its node material if it had one. */
    private fun releaseBatchInstance(b: Batch) {
        b.graphKey?.let { graphs[it]?.live?.remove(b.mi) }
        engine.destroyMaterialInstance(b.mi)
        b.graphKey = null
    }

    private fun destroyBatch(b: Batch) {
        val rm = engine.renderableManager
        val tm = engine.transformManager
        if (b.visible) scene.removeEntity(b.entity)
        b.visible = false
        if (rm.hasComponent(b.entity)) rm.destroy(b.entity)
        b.texture?.let { engine.destroyTexture(it) }; b.texture = null
        b.colorTexture?.let { engine.destroyTexture(it) }; b.colorTexture = null
        releaseBatchInstance(b)
        if (tm.hasComponent(b.entity)) tm.destroy(tm.getInstance(b.entity))
        EntityManager.get().destroy(b.entity)
    }

    /** Batch `index` of a `(id, symbol)` key; the first keeps the bare key. */
    private fun chunkKey(key: String, index: Int) = if (index == 0) key else "$key\u0000$index"

    /** Index of batch `batchKey` among the batches of `key`, or -1 when it is another symbol's. */
    private fun chunkIndex(key: String, batchKey: String): Int = when {
        batchKey == key -> 0
        batchKey.startsWith("$key\u0000") -> batchKey.substring(key.length + 1).toIntOrNull() ?: -1
        else -> -1
    }

    /** Hide the batches of `key` from batch `from` on (kept for reuse); `true` when one was visible. */
    private fun hideChunks(key: String, from: Int): Boolean {
        var mutated = false
        for ((batchKey, batch) in batches) {
            if (chunkIndex(key, batchKey) < from || !batch.visible) continue
            scene.removeEntity(batch.entity); batch.visible = false; mutated = true
        }
        return mutated
    }

    /** Rows of one batch's instance texture: 16 384, or fewer where the GPU's 2D textures are smaller. */
    private val instanceRows: Int by lazy {
        min(MAX_INSTANCES_PER_BATCH, Texture.getMaxTextureSize(engine, Texture.Sampler.SAMPLER_2D))
    }

    /** Whether node material `slug` samples the instance rows on module batches — the caller pulls them then. */
    fun samplesInstanceColors(slug: String?): Boolean = graphFor(slug, "instanced")?.instanceColors == true

    /**
     * Place `transforms.size / 64` instances of `uid` for `(id, symbol)` as ⌈n / rows⌉ batches:
     * each batch's share of the runtime's column-major `mat4` stream goes into its own
     * RGBA32F texture, its share of `colors` (the core's binary16 rows, `null` = white) into an
     * RGBA16F one where its material samples them; a count change rebuilds the renderable.
     * Returns the instances drawn.
     */
    fun syncModuleInstances(
        id: String, symbol: String, uid: String, transforms: ByteArray, colors: ByteArray?,
        parentGltfIndex: Int?, look: PaletteLook?,
    ): Int {
        val total = transforms.size / TRANSFORM_STRIDE_BYTES
        val key = "$id\u0000$symbol"
        if (total == 0) {
            if (hideChunks(key, 0)) onSceneMutated()
            return 0
        }
        val mesh = moduleMeshes[uid] ?: run { Log.e(TAG, "module '$id $symbol': mesh '$uid' not registered"); return 0 }
        var drawn = 0
        var chunk = 0
        while (drawn < total) {
            val count = min(instanceRows, total - drawn)
            if (!syncModuleChunk(chunkKey(key, chunk), uid, mesh, transforms, colors, drawn, count, parentGltfIndex, look)) break
            drawn += count
            chunk += 1
        }
        hideChunks(key, chunk)
        onSceneMutated()
        return drawn
    }

    /** Draw `count` instances from instance `first` of `transforms` as batch `key`; `false` = no material. */
    private fun syncModuleChunk(
        key: String, uid: String, mesh: ModuleMesh, transforms: ByteArray, colors: ByteArray?, first: Int, count: Int,
        parentGltfIndex: Int?, look: PaletteLook?,
    ): Boolean {
        var batch = batches[key]
        if (batch != null && batch.meshUid != uid) { destroyBatch(batch); batches.remove(key); batch = null }
        val params = look?.params
        // A node material's batch owns an instance of its package — one more family. It draws Blend / Add
        // itself: the built-in cut and its notice are only for a look without one.
        val graph = drawGraph(look?.slug, "instanced")
        val graphKey = look?.slug?.takeIf { graph != null }?.let { graphKeyOf(it, "instanced") }
        val cutoff = if (graph != null) null else maskCutoff(params, look?.slug ?: key)
        val masked = cutoff != null
        val thin = isThin(params)
        val material = graph?.material ?: material(familyName("tride-generated-instanced", thin, masked)) ?: material("tride-generated-instanced")
        fun freshInstance(m: Material) = if (graph != null) newGraphInstance(graph, 0f) else m.createInstance(key)
        val rm = engine.renderableManager
        if (batch == null) {
            if (material == null) return false
            batch = Batch(EntityManager.get().create(), uid, freshInstance(material), masked, thin)
            batch.graphKey = graphKey
            batches[key] = batch
        } else if ((batch.masked != masked || batch.thin != thin || batch.graphKey != graphKey) && material != null) {
            // The look changed family (opaque ↔ mask, lit ↔ thin, built-in ↔ node material): the
            // renderable goes first — it never references a destroyed instance — and is rebuilt below.
            if (rm.hasComponent(batch.entity)) rm.destroy(batch.entity)
            releaseBatchInstance(batch)
            batch.mi = freshInstance(material); batch.masked = masked; batch.thin = thin; batch.graphKey = graphKey
        }
        batch.materialSlug = look?.slug
        // A node material's uniforms come from the runtime instead (`updateMaterialGraph`).
        if (batch.graphKey == null) {
            applyGeneratedParams(batch.mi, params, batch.thin)
            if (batch.masked && cutoff != null) batch.mi.setMaskThreshold(cutoff)
        }
        if (batch.texture == null || count > batch.capacity) {
            batch.texture?.let { engine.destroyTexture(it) }
            batch.colorTexture?.let { engine.destroyTexture(it) }; batch.colorTexture = null
            batch.capacity = min(max(64, (count * 3 + 1) / 2), instanceRows)
            batch.texture = Texture.Builder().width(4).height(batch.capacity).levels(1).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA32F).build(engine)
        }
        val staging = ByteBuffer.allocateDirect(batch.capacity * TRANSFORM_STRIDE_BYTES).order(ByteOrder.nativeOrder())
        val src = ByteBuffer.wrap(transforms, first * TRANSFORM_STRIDE_BYTES, count * TRANSFORM_STRIDE_BYTES)
            .slice().order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(count * 16)
        src.asFloatBuffer().get(floats)
        staging.asFloatBuffer().put(floats)
        staging.rewind()
        batch.texture!!.setImage(engine, 0, Texture.PixelBufferDescriptor(staging, Texture.Format.RGBA, Texture.Type.FLOAT))
        batch.mi.setParameter("instanceTransforms", batch.texture!!, nearestSampler)
        uploadInstanceColors(batch, colors, first, count)
        batch.bounds = instancesBounds(src, count, mesh.radius)
        if (batch.count != count || !rm.hasComponent(batch.entity)) {
            if (rm.hasComponent(batch.entity)) rm.destroy(batch.entity)
            batch.count = count
            buildBatchRenderable(batch, mesh)
        } else {
            rm.setAxisAlignedBoundingBox(rm.getInstance(batch.entity), batch.bounds)
        }
        parentUnder(batch.entity, parentGltfIndex)
        if (!batch.visible) { scene.addEntity(batch.entity); batch.visible = true }
        return true
    }

    /**
     * The batch's slice of the instance rows into its own RGBA16F texture, bound as
     * `instanceColors` — only while its instance samples them (any other material would abort on the
     * unknown sampler); rows past `count` stay white. A batch that left such a material drops its texture.
     */
    private fun uploadInstanceColors(batch: Batch, colors: ByteArray?, first: Int, count: Int) {
        if (batch.graphKey?.let { graphs[it] }?.instanceColors != true) {
            batch.colorTexture?.let { engine.destroyTexture(it) }; batch.colorTexture = null
            return
        }
        val texture = batch.colorTexture ?: Texture.Builder().width(1).height(batch.capacity).levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA16F).build(engine)
            .also { batch.colorTexture = it }
        val rows = InstanceColorRows.slice(colors, first, count, batch.capacity)
        val staging = ByteBuffer.allocateDirect(rows.size * 2).order(ByteOrder.nativeOrder())
        staging.asShortBuffer().put(rows)
        texture.setImage(engine, 0, Texture.PixelBufferDescriptor(staging, Texture.Format.RGBA, Texture.Type.HALF))
        batch.mi.setParameter("instanceColors", texture, nearestSampler)
    }

    fun removeModuleInstances(id: String) {
        val prefix = "$id\u0000"
        val it = batches.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.startsWith(prefix)) { destroyBatch(e.value); it.remove() }
        }
    }

    /** Visible batches of `id` that cast shadows (an Add node material's cast none). Test introspection. */
    fun moduleShadowBatches(id: String): Int {
        val rm = engine.renderableManager
        return batches.entries.count { (k, b) -> k.startsWith("$id\u0000") && b.visible && rm.hasComponent(b.entity) && rm.isShadowCaster(rm.getInstance(b.entity)) }
    }

    /** Whether the trunk of `id` casts shadows; `false` when unknown. Test introspection. */
    fun generatedMeshCastsShadows(id: String): Boolean {
        val s = meshes[id] ?: return false
        val rm = engine.renderableManager
        return rm.hasComponent(s.entity) && rm.isShadowCaster(rm.getInstance(s.entity))
    }

    /** Whether the asset renderable of glTF node `index` casts shadows; `false` without one. Test introspection. */
    fun nodeCastsShadows(index: Int): Boolean {
        val e = entityForGltfIndex(index) ?: return false
        val rm = engine.renderableManager
        return rm.hasComponent(e) && rm.isShadowCaster(rm.getInstance(e))
    }

    fun moduleInstanceCount(id: String): Int {
        val prefix = "$id\u0000"
        return batches.entries.filter { it.key.startsWith(prefix) && it.value.visible }.sumOf { it.value.count }
    }

    fun dispose() {
        // The asset's slots go back to their own instances while it is alive.
        undressAssetMaterialGraphs()
        for (s in meshes.values) destroyMeshState(s); meshes.clear()
        for (b in batches.values) destroyBatch(b); batches.clear()
        for (m in moduleMeshes.values) { engine.destroyVertexBuffer(m.vb); engine.destroyIndexBuffer(m.ib) }; moduleMeshes.clear()
        for (mi in paletteInstances.values) engine.destroyMaterialInstance(mi); paletteInstances.clear()
        barkInstance?.let { engine.destroyMaterialInstance(it) }; barkInstance = null
        destroyMaterialGraphs()
        for (t in textures.values) t?.let { engine.destroyTexture(it) }; textures.clear()
        whiteTexture?.let { engine.destroyTexture(it) }; whiteTexture = null
        for (m in materials.values) m?.let { engine.destroyMaterial(it) }; materials.clear()
    }
}
