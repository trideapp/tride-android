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
 *     `moduleShadowBatches(id)` / `generatedMeshCastsShadows(id)` (tests).
 *   - `updatePaletteMaterial(slug, params)` — recolour every built-in instance wearing the slug;
 *     `wearsBuiltInLook(slug)` — whether one does.
 *   - `generatedMaterialForSlug(slug, look)` — a built-in instance carrying the look, else the bark.
 *   - `setStandardMaterials(forms)` (the record's standard forms), `setGltfParameter` (the core's live
 *     parameters), `setInstanceState(slug, name, value)` (a cut-off or double side, on every instance of the
 *     slug but a node material's), `rekeyStandard(slug, key)` (the slug's key changed), `standardMaterialGaps()`,
 *     `standardInstanceCount()` / `standardMaterialNames(slug)` / `standardKeysOf(slug)`; a host with
 *     `fileMesh` is drawn as gltfio draws that mesh's primitives.
 *   - `registerMaterialGraph` / `updateMaterialGraph` — node materials: the scene's packages per family and
 *     the runtime's uniforms. A file mesh draws its graph if the package loaded, else the record's standard
 *     form; generated meshes and batches fall back to the built-in look.
 *   - Photo slots: `refreshPictures(slug, family, groups)`, `photoSlotSlugs`, `boundRevisions`.
 *
 * Non-obvious constraints:
 *   - A file mesh: gltfio's layout (SHORT4 frames by `SurfaceOrientation` per primitive, UV0 /
 *     COLOR FLOAT4 / UV1, gltfio's dummy 1 for a missing stream, the core's bounds) and an ubershader instance
 *     per slug and key (gltfio's provider, the record's parameters, maps decoded by gltfio's stb provider in
 *     their slot's colour space with GPU mipmaps); live writes reach them by slug and are kept, so an instance
 *     made later takes every one of them after the record's values. A changed key (`gltf/materialKey`) makes
 *     each instance of the slug again for it (each group's own vertex colours), re-points the renderables and
 *     destroys the old one; one Filament does not make keeps the old one, said once. Its quantized streams (the
 *     core's `stored`) go to the GPU in their stored type, `normalized` and stride, as gltfio
 *     uploads them; the float streams stay for the frames.
 *   - ONE vertex layout for trunks and module meshes: POSITION / TANGENTS (quaternion frames from the
 *     normals, or from the runtime's MikkTSpace tangents of a normal-mapped mesh) / UV0 / COLOR (HALF4,
 *     UV1 aliases it) / CUSTOM0 (flow, trunks only). FFI byte streams are little-endian and wrapped as-is.
 *   - Built-in materials come from the AAR's `assets/materials/<name>.filamat` (opengl + vulkan in one
 *     package); a missing package degrades with one log line, never a crash.
 *   - Every scene picture goes through gltfio's stb: palette maps (one stb cache per picture
 *     and colour space), node-material array layers through `scenePictureRgba` (`StbImage`, the same
 *     `stbi_load_from_memory`; `BitmapFactory` differed on JPEG by up to 3 levels). A
 *     masked look (`alphaMode: mask`; `blend` and `add` without a node-material package → masked at 0.5,
 *     said once) selects the `-masked` family, a thin look (the core's `thin` field) the `-thin` family.
 *   - A node material's instance never takes a built-in parameter (an unknown sampler aborts in
 *     Filament).
 *   - `instanceColors` is bound only on a batch whose node material samples it (`TrideMaterialGraph.
 *     instanceColors`); its 1 × capacity texture is re-uploaded with the transforms on every sync.
 *   - A renderable wearing an Add package (`TrideMaterialGraph.castShadows` false) neither
 *     casts nor receives shadows (under VSM and PCSS Filament makes every receiver a caster): trunks, file
 *     meshes and batches when built.
 *   - A photo slot is a one-layer array: its placeholder (1 × 1 white without one) or the app's picture —
 *     one texture per `(input, revision, srgb)`, destroyed after the last rebind; later instances bind it too.
 */
package studio.tride.kit

import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MorphTargetBuffer
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.StbImage
import com.google.android.filament.gltfio.TextureProvider
import com.google.android.filament.gltfio.UbershaderProvider
import studio.tride.core.TrideBounds
import studio.tride.core.TrideDatablockMesh
import studio.tride.core.TrideDatablockPart
import studio.tride.core.TrideGeneratedGeometry
import studio.tride.core.TrideHostPicture
import studio.tride.core.TrideMaterialGraph
import studio.tride.core.TrideMaterialGraphGroup
import studio.tride.core.TrideMaterialGraphValue
import studio.tride.core.TrideMaterialKey
import studio.tride.core.TrideMaterialParams
import studio.tride.core.TrideMorphTarget
import studio.tride.core.TridePulseLook
import studio.tride.core.TridePulseTable
import studio.tride.core.TrideRecordMap
import studio.tride.core.TrideRecordStandard
import studio.tride.core.TrideSkinPart
import studio.tride.core.TrideStoredStream
import studio.tride.core.TrideStoredStreams
import studio.tride.core.TrideTextureRef
import studio.tride.core.TrideVertexAttributes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "tride.gen"
private const val PULSE_ROW_FLOATS = 8
/** Most pulse rows a table holds: the core's `PULSE_CAPACITY_MAX`, as iOS (`PulseCapacityTest` checks it). */
internal const val PULSE_CAPACITY_MAX = 256
private const val MAX_INSTANCES_PER_BATCH = 16384
private const val TRANSFORM_STRIDE_BYTES = 64

/** Where a generated mesh hangs and how it looks. */
internal class GeneratorHost(
    val parentPlace: Int?,
    val materialSlug: String?,
    val materialParams: TrideMaterialParams?,
    /** `null` = no pulses; `Some` = draw the trunk with the flow material blending this look. */
    val pulse: TridePulseLook?,
    /** The scene node the mesh is, and its `objectSeed` — a node material's per-object random colour. */
    val objectUid: String? = null,
    val objectSeed: Float = 0f,
    /**
     * A file mesh (it names what its primitives carry in the file): drawn as gltfio draws its
     * primitives — its keys, attributes, `uvs1` and bounds come from here; `null` = the built-in layout.
     */
    val fileMesh: TrideDatablockMesh? = null,
    /** A file mesh with morph targets starts at these weights (the last the stream set, else the node's at rest). */
    val morphWeights: FloatArray? = null,
    /** A file mesh its node's skin bends: the skin's joint count (at most 256); 0 = no skin. */
    val skinBones: Int = 0,
)

/** A palette look with the slug it recolours under. */
internal class PaletteLook(val slug: String?, val params: TrideMaterialParams?)

/** A skin renderable of a generated mesh (test introspection): its palette part, bones, whether it is its own entity, primitives and box. */
internal class SkinDrawInfo(val part: Int, val bones: Int, val own: Boolean, val primitives: Int, val center: FloatArray, val halfExtent: FloatArray)

internal class FilamentGenerators(
    private val engine: Engine,
    private val scene: Scene,
    /** `assets/materials/<name>.filamat` bytes; `null` = the package is missing. */
    private val materialPackage: (String) -> ByteArray?,
    /** Archive bytes behind a palette texture uri (`TrideScene.assetBytes`). */
    private val assetBytes: (String) -> ByteArray?,
    private val entityForPlace: (Int) -> Int?,
    private val onSceneMutated: () -> Unit,
    /** A loading problem the host should hear about (subject, message); logged by the receiver. */
    private val report: (String, String) -> Unit = { _, message -> Log.w(TAG, message) },
    /** The app's picture `(input, revision)` from the host's store; `null` = not there. */
    private val hostPixels: (String, Long) -> HostPixels? = { _, _ -> null },
    /** An app picture that could not be uploaded (input, reason): the host marks the input failed. */
    private val pictureFailed: (String, String) -> Unit = { _, _ -> },
    /** gltfio's ubershader provider (the bridge's): the record's standard-form instances; `null` = none. */
    private val ubershaders: UbershaderProvider? = null,
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

    /** The palette image behind `ref` as an sRGB texture with mipmaps: the scene's one picture decoder (`picture`). */
    private fun textureFor(ref: TrideTextureRef): Texture? {
        val tex = picture(ref, srgb = true) { why ->
            warnOnce("texture:${ref.uri}", "texture '${ref.uri}' $why — colour factor only")
        } ?: return null
        if (ref.texCoord != 0u) warnOnce("uv:${ref.uri}", "texture '${ref.uri}' uses TEXCOORD_${ref.texCoord} — built-in materials sample uv0 only")
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

    /** A built-in instance carrying the core's look for `slug`, else the bark. */
    fun generatedMaterialForSlug(slug: String?, params: TrideMaterialParams?): MaterialInstance? {
        if (!slug.isNullOrEmpty()) {
            val thin = params?.thin == true
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
            warnOnce("slug:$slug", "generated mesh material '$slug' has no look in the palette — lit default")
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

    /**
     * Recolour every built-in instance drawn with `slug`: the generated meshes' (every family), the module
     * batches, the pulses. A scene mesh's instance takes [setGltfParameter] alone: the look's fields are not
     * what its glTF form draws (a field under a wired map, a Multiply factor).
     */
    fun updatePaletteMaterial(slug: String, params: TrideMaterialParams): Int {
        var touched = 0
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

    /** Whether a built-in instance wears [slug] — a generated mesh's, a clone batch's or a pulsed trunk's (the ones `updatePaletteMaterial` recolours). */
    fun wearsBuiltInLook(slug: String): Boolean =
        paletteInstances.keys.any { it == slug || it.startsWith("$slug#") } ||
            batches.values.any { it.materialSlug == slug && it.graphKey == null } ||
            meshes.values.any { it.flowMi != null && it.baseSlug == slug }

    /**
     * Sets gltfio parameter [name] on the standard instances of [slug] whose material declares it (the core's
     * resolved value) — the one write to a scene mesh's instance — 1 value a float, 3 a float3, 4 a float4, 9 a mat3 (column-major, as gltfio's
     * `matrixFromUvTransform`). Returns the instances that took it.
     */
    fun setGltfParameter(slug: String, name: String, values: FloatArray): Int {
        // Kept for every instance made later — a changed key's, a primitive built after the write.
        standardLive.getOrPut(slug) { LinkedHashMap() }[name] = values.copyOf()
        var took = 0
        for (mi in standardBySlug[slug].orEmpty()) if (setNamedParameter(mi, name, values)) took++
        if (took > 0) onSceneMutated()
        return took
    }

    /**
     * The cut-off or double side [name] ([MASK_THRESHOLD] / [DOUBLE_SIDED]) on every instance wearing [slug] whose
     * material declares it — the standard instances (kept, so one made later takes it too, as a live parameter) and
     * the built-in ones; a node material's comes from the runtime's table. A material without it draws as glTF
     * says: a cut-off cuts only a masked one, and a key's double side remakes the instance (`rekeyStandard`).
     * Returns the instances that took it.
     */
    fun setInstanceState(slug: String, name: String, value: Float): Int {
        val values = floatArrayOf(value)
        standardLive.getOrPut(slug) { LinkedHashMap() }[name] = values
        var took = 0
        for (mi in standardBySlug[slug].orEmpty()) if (setNamedParameter(mi, name, values)) took++
        for ((key, mi) in paletteInstances) {
            if ((key == slug || key.startsWith("$slug#")) && setNamedParameter(mi, name, values)) took++
        }
        for (batch in batches.values) {
            if (batch.materialSlug == slug && batch.graphKey == null && setNamedParameter(batch.mi, name, values)) took++
        }
        if (took > 0) onSceneMutated()
        return took
    }

    /**
     * One value onto `mi` by gltfio's parameter name (1 float, 3, 4, 9 a mat3), or Filament's instance state
     * (`_maskThreshold`, `_doubleSided` with the culling and transparency gltfio pairs with it); one the material
     * does not declare is skipped (Filament aborts).
     */
    private fun setNamedParameter(mi: MaterialInstance, name: String, values: FloatArray): Boolean {
        if (!mi.material.hasParameter(name)) return false
        if (name == MASK_THRESHOLD) { mi.setMaskThreshold(values[0]); return true }
        if (name == DOUBLE_SIDED) {
            val on = values[0] != 0f
            mi.setDoubleSided(on)
            mi.setCullingMode(if (on) Material.CullingMode.NONE else Material.CullingMode.BACK)
            mi.setTransparencyMode(if (on) Material.TransparencyMode.TWO_PASSES_TWO_SIDES else Material.TransparencyMode.DEFAULT)
            return true
        }
        when (values.size) {
            1 -> mi.setParameter(name, values[0])
            3 -> mi.setParameter(name, values[0], values[1], values[2])
            4 -> mi.setParameter(name, values[0], values[1], values[2], values[3])
            9 -> mi.setParameter(name, MaterialInstance.FloatElement.MAT3, values, 0, 1)
            else -> return false
        }
        return true
    }

    // ---- standard-form materials as gltfio makes them, without its loader -------------------------
    /** The record's standard forms by slug (`setStandardMaterials`). */
    private var standardForms: Map<String, TrideRecordStandard> = emptyMap()
    /** An ubershader instance and the slug and key it was made for. */
    private class StandardHeld(val slug: String, val key: TrideMaterialKey, val mi: MaterialInstance)
    /** `slug\u0000key` → the ubershader instance made for it: one per slug and key. */
    private val standardInstances = HashMap<String, StandardHeld>()
    /** A slug's key since the core changed it (`rekeyStandard`), `hasVertexColors` aside: later instances ask by it. */
    private val standardKeys = HashMap<String, TrideMaterialKey>()
    /** The core's live parameters per slug, by name, as last written (`setGltfParameter`). */
    private val standardLive = HashMap<String, LinkedHashMap<String, FloatArray>>()
    /** Every standard instance of a slug: live writes reach them all, as gltfio's instances of a material by name. */
    private val standardBySlug = HashMap<String, MutableList<MaterialInstance>>()
    /** Per slug, what its instances draw without (`standardMaterialGaps`). */
    private val standardGaps = LinkedHashMap<String, List<String>>()

    /**
     * The record's standard-form materials, before any file mesh wears them. Every map's picture is
     * pushed to gltfio's stb provider at once and the call returns once they are all decoded and uploaded.
     */
    fun setStandardMaterials(forms: Map<String, TrideRecordStandard>) {
        standardForms = forms
        var pushed = 0
        for ((slug, form) in forms) for (map in form.maps) if (pushStandardPicture(slug, map)) pushed++
        if (pushed > 0) flushPictures()
    }

    /** Waits for stb's decoding (the engine's job system), uploads the pictures with GPU mipmaps, pops them, flushes. */
    private fun flushPictures() {
        val provider = stb ?: return
        provider.waitForCompletion()
        provider.updateQueue()
        while (provider.popTexture() != null) {
            provider.popMessage?.takeIf { it.isNotEmpty() }?.let { warnOnce("stb-pop:$it", "a picture of the scene: $it — drawn incomplete") }
        }
        // The uploads and their mips go to the driver thread now, while the meshes are still being built, not
        // queued behind the first frame.
        engine.flush()
        onSceneMutated()
    }

    /** Per slug, what a key asks for that its instances draw without: values the record does not carry, pictures. */
    fun standardMaterialGaps(): Map<String, List<String>> = LinkedHashMap(standardGaps)

    fun standardInstanceCount(): Int = standardInstances.size

    /** The materials of `slug`'s standard instances (test introspection: a changed key picks another ubershader). */
    fun standardMaterialNames(slug: String): List<String> = standardBySlug[slug].orEmpty().map { it.material.name }

    /** The keys `slug`'s standard instances were made for (test introspection). */
    fun standardKeysOf(slug: String): List<TrideMaterialKey> = standardInstances.values.filter { it.slug == slug }.map { it.key }

    /**
     * `slug`'s `MaterialKey` changed to [key] (its `hasVertexColors` is ignored): each instance of
     * the slug made for another key is made again by [standardInstance] — the new key with the instance's own
     * vertex colours, the record's values, then every live parameter — the renderables wearing the old one are
     * re-pointed and it is destroyed. One Filament does not make keeps the old instance, worn and owned. Returns
     * how many changed, `null` when the record draws `slug` in no glTF form here.
     */
    fun rekeyStandard(slug: String, key: TrideMaterialKey): Int? {
        if (standardForms[slug] == null) return null
        standardKeys[slug] = key
        val stale = standardInstances.entries.filter { (_, h) -> h.slug == slug && h.key != key.copy(hasVertexColors = h.key.hasVertexColors) }
            .map { it.key to it.value }
        var changed = 0
        var kept = 0
        for ((id, held) in stale) {
            standardInstances.remove(id)
            val next = standardInstance(slug, held.key, null)
            if (next == null) {
                standardInstances[id] = held
                kept++
                continue
            }
            standardBySlug[slug]?.remove(held.mi)
            swapMaterial(held.mi, next)
            engine.destroyMaterialInstance(held.mi)
            changed++
        }
        if (kept > 0) warnOnce("standard-rekey:$slug", "standard material '$slug': no ubershader for its new key — $kept instance(s) keep the old one")
        if (changed > 0) onSceneMutated()
        return changed
    }

    /** Every renderable primitive wearing [old] wears [next]: the file meshes' groups and parts, their skin part renderables. */
    private fun swapMaterial(old: MaterialInstance, next: MaterialInstance) {
        val rm = engine.renderableManager
        for (s in meshes.values) {
            if (s.mi === old) s.mi = next
            for (p in s.primitives) if (p.mi === old) p.mi = next
            for (p in s.fileParts) if (p.mi === old) p.mi = next
            val entities = listOf(s.entity) + s.skinDraws.filter { it.own }.map { it.entity }
            for (e in entities) {
                if (!rm.hasComponent(e)) continue
                val ri = rm.getInstance(e)
                for (i in 0 until rm.getPrimitiveCount(ri)) {
                    if (rm.getMaterialInstanceAt(ri, i).nativeObject == old.nativeObject) rm.setMaterialInstanceAt(ri, i, next)
                }
            }
        }
    }

    /** gltfio's Java `MaterialKey` from the core's, field by field. */
    private fun javaKey(k: TrideMaterialKey) = MaterialProvider.MaterialKey().also { j ->
        j.doubleSided = k.doubleSided; j.unlit = k.unlit; j.hasVertexColors = k.hasVertexColors
        j.hasBaseColorTexture = k.hasBaseColorTexture; j.hasNormalTexture = k.hasNormalTexture
        j.hasOcclusionTexture = k.hasOcclusionTexture; j.hasEmissiveTexture = k.hasEmissiveTexture
        j.useSpecularGlossiness = k.useSpecularGlossiness
        j.alphaMode = when (k.alphaMode) { "mask" -> 1; "blend" -> 2; else -> 0 }
        j.enableDiagnostics = k.enableDiagnostics
        j.hasMetallicRoughnessTexture = k.hasMetallicRoughnessTexture; j.metallicRoughnessUV = k.metallicRoughnessUv.toInt()
        j.baseColorUV = k.baseColorUv.toInt()
        j.hasClearCoatTexture = k.hasClearCoatTexture; j.clearCoatUV = k.clearCoatUv.toInt()
        j.hasClearCoatRoughnessTexture = k.hasClearCoatRoughnessTexture; j.clearCoatRoughnessUV = k.clearCoatRoughnessUv.toInt()
        j.hasClearCoatNormalTexture = k.hasClearCoatNormalTexture; j.clearCoatNormalUV = k.clearCoatNormalUv.toInt()
        j.hasClearCoat = k.hasClearCoat; j.hasTransmission = k.hasTransmission; j.hasTextureTransforms = k.hasTextureTransforms
        j.emissiveUV = k.emissiveUv.toInt(); j.aoUV = k.aoUv.toInt(); j.normalUV = k.normalUv.toInt()
        j.hasTransmissionTexture = k.hasTransmissionTexture; j.transmissionUV = k.transmissionUv.toInt()
        j.hasSheenColorTexture = k.hasSheenColorTexture; j.sheenColorUV = k.sheenColorUv.toInt()
        j.hasSheenRoughnessTexture = k.hasSheenRoughnessTexture; j.sheenRoughnessUV = k.sheenRoughnessUv.toInt()
        j.hasVolumeThicknessTexture = k.hasVolumeThicknessTexture; j.volumeThicknessUV = k.volumeThicknessUv.toInt()
        j.hasSheen = k.hasSheen; j.hasIOR = k.hasIor
        j.hasVolume = k.hasVolume; j.hasDispersion = k.hasDispersion
        j.hasSpecular = k.hasSpecular; j.hasSpecularTexture = k.hasSpecularTexture
        j.hasSpecularColorTexture = k.hasSpecularColorTexture
        j.specularTextureUV = k.specularTextureUv.toInt(); j.specularColorTextureUV = k.specularColorTextureUv.toInt()
    }

    /** What a (constrained) key asks for whose values the record's form does not carry (gltfio's parameter names). */
    private fun uncarried(key: MaterialProvider.MaterialKey, form: TrideRecordStandard?): List<String> {
        val names = form?.params?.mapTo(HashSet()) { it.name } ?: emptySet()
        val out = ArrayList<String>()
        fun need(asks: Boolean, what: String, param: String) { if (asks && param !in names) out += what }
        need(key.hasTransmission, "transmission", "transmissionFactor")
        need(key.hasIOR, "IOR", "ior")
        need(key.hasVolume, "volume", "volumeThicknessFactor")
        need(key.hasDispersion, "dispersion", "dispersion")
        need(key.hasSheen, "sheen", "sheenColorFactor")
        need(key.hasClearCoat, "clear coat", "clearCoatFactor")
        need(key.hasSpecular, "specular", "specularFactor")
        need(key.useSpecularGlossiness, "specular-glossiness", "glossinessFactor")
        return out
    }

    private fun addGaps(slug: String, gaps: List<String>) {
        if (gaps.isEmpty()) return
        val before = standardGaps[slug].orEmpty()
        val now = (before + gaps).distinct()
        if (now.size == before.size) return
        standardGaps[slug] = now
        Log.w(TAG, "standard material '$slug' draws without its ${now.joinToString(", ")}")
    }

    /** gltfio's stb provider: the decoder its `ResourceLoader` uses, made on the first picture. */
    private var stb: TextureProvider? = null
    /**
     * `uri\u0000srgb` → the texture of a scene picture in that colour space; `null` = not drawable. One cache for
     * the standard maps and the built-in looks' palette maps: a picture both wear is decoded once.
     */
    private val pictures = HashMap<String, Texture?>()

    private fun pictureKey(uri: String, srgb: Boolean) = "$uri\u0000$srgb"

    /**
     * Pushes the scene's picture `ref` to stb once per colour space (SRGB8_A8, else RGBA8, a full mip chain):
     * `true` = newly pushed. A picture the scene lacks or stb cannot read stays `null`; [left] says why.
     */
    private fun pushPicture(ref: TrideTextureRef, srgb: Boolean, left: (String) -> Unit): Boolean {
        val key = pictureKey(ref.uri, srgb)
        if (pictures.containsKey(key)) return false
        val bytes = assetBytes(ref.uri)
        if (bytes == null || bytes.isEmpty()) {
            pictures[key] = null
            left("is not in the scene")
            return false
        }
        val provider = stb ?: TextureProvider.createStb(engine).also { stb = it }
        val tex = provider.pushTexture(direct(bytes), ref.mimeType.orEmpty(), if (srgb) TextureProvider.FLAG_SRGB else 0)
        pictures[key] = tex
        if (tex == null) {
            left("could not be decoded (${provider.pushMessage})")
            return false
        }
        return true
    }

    /** The texture of picture `ref` in that colour space, decoded and uploaded now if it was not; `null` = left out ([left] said why). */
    private fun picture(ref: TrideTextureRef, srgb: Boolean, left: (String) -> Unit): Texture? {
        val key = pictureKey(ref.uri, srgb)
        if (!pictures.containsKey(key) && pushPicture(ref, srgb, left)) flushPictures()
        return pictures[key]
    }

    /** [pushPicture] for a standard map: one that is left out is said, and the slug draws without its pictures. */
    private fun pushStandardPicture(slug: String, map: TrideRecordMap): Boolean =
        pushPicture(map.texture, map.srgb) { why ->
            warnOnce("standard-picture:${map.texture.uri}", "standard material '$slug': its ${map.slot} picture '${map.texture.uri}' $why — the map is left out")
            addGaps(slug, listOf("pictures"))
        }

    /** The texture of a map's picture (pushed by `setStandardMaterials`); `null` = left out (said there). */
    private fun standardPicture(slug: String, map: TrideRecordMap): Texture? {
        val key = pictureKey(map.texture.uri, map.srgb)
        if (!pictures.containsKey(key) && pushStandardPicture(slug, map)) flushPictures()
        return pictures[key]
    }

    /**
     * The standard instance of `slug` for a primitive of key `k` — made once per slug and key through gltfio's
     * ubershader provider and filled as gltfio's `createMaterialInstance` fills it: the record's parameters by
     * gltfio's names (the emission already carries its strength, so `emissiveStrength` is 1), the cut-off of a
     * masked material, each map with its sampler. The buffers carry `TEXCOORD_n` in `UVn`, so a map's UV index
     * is its set. A primitive without a material (`slug` empty) takes the core's default look on its key.
     * `null` when the record has no standard form for `slug` or the provider has no material for the key.
     */
    private fun standardInstance(slug: String?, record: TrideMaterialKey, look: TrideMaterialParams?): MaterialInstance? {
        val provider = ubershaders ?: return null
        val named = !slug.isNullOrEmpty()
        val form = if (named) standardForms[slug] ?: run {
            warnOnce("standard-form:$slug", "material '$slug' has no glTF form in the scene record — its file mesh is not drawn")
            return null
        } else null
        // The key in force: the core's since it changed (the primitive keeps its vertex colours), else the record's.
        val inForce = if (named) standardKeys[slug] else null
        val k = inForce?.copy(hasVertexColors = record.hasVertexColors) ?: record
        val cacheKey = "${slug.orEmpty()}\u0000$k"
        standardInstances[cacheKey]?.let { return it.mi }
        val key = javaKey(k)
        val mi = provider.createMaterialInstance(key, IntArray(8), if (named) slug else "tride_default_look", null) ?: run {
            warnOnce("standard:${slug.orEmpty()}", "standard material '${slug.orEmpty()}': no ubershader for its key — not drawn")
            return null
        }
        val material = mi.material
        // `key` is the constrained one now (a third UV set drops its maps).
        val indices = listOf(
            Triple(key.hasBaseColorTexture, key.baseColorUV, "baseColorIndex"),
            Triple(key.hasMetallicRoughnessTexture, key.metallicRoughnessUV, "metallicRoughnessIndex"),
            Triple(key.hasNormalTexture, key.normalUV, "normalIndex"),
            Triple(key.hasOcclusionTexture, key.aoUV, "aoIndex"),
            Triple(key.hasEmissiveTexture, key.emissiveUV, "emissiveIndex"),
            Triple(key.hasTransmission && key.hasTransmissionTexture, key.transmissionUV, "transmissionIndex"),
        )
        for ((has, set, index) in indices) if (has && set < 2 && material.hasParameter(index)) mi.setParameter(index, set)
        if (form != null) {
            for (p in form.params) {
                if (p.value.size > 9) continue
                setNamedParameter(mi, p.name, FloatArray(p.value.size) { p.value[it].toFloat() })
            }
        } else {
            // No glTF form: the look's values, else white, metallic 0, roughness 1, no emission. Neither
            // gltfio's default material (metallic 1) nor the core's DEFAULT_LOOK (roughness 0.5); not
            // reached today, a slug-less mesh gets the core's DEFAULT_LOOK.
            val bc = look?.baseColor?.takeIf { it.size >= 4 }
            setNamedParameter(mi, "baseColorFactor", floatArrayOf(
                (bc?.get(0) ?: 1.0).toFloat(), (bc?.get(1) ?: 1.0).toFloat(), (bc?.get(2) ?: 1.0).toFloat(), (bc?.get(3) ?: 1.0).toFloat()))
            setNamedParameter(mi, "metallicFactor", floatArrayOf((look?.metallic ?: 0.0).toFloat()))
            setNamedParameter(mi, "roughnessFactor", floatArrayOf((look?.roughness ?: 1.0).toFloat()))
            val e = look?.emissive?.takeIf { it.size >= 3 }
            setNamedParameter(mi, "emissiveFactor", floatArrayOf((e?.get(0) ?: 0.0).toFloat(), (e?.get(1) ?: 0.0).toFloat(), (e?.get(2) ?: 0.0).toFloat()))
        }
        if (material.blendingMode == Material.BlendingMode.MASKED) mi.setMaskThreshold((form?.alphaCutoff ?: 0.5).toFloat())
        for (map in form?.maps.orEmpty()) {
            // A slot the key dropped (or never had) stays unbound, as gltfio leaves it.
            val keyed = when (map.slot) {
                "baseColor" -> key.hasBaseColorTexture
                "metallicRoughness" -> key.hasMetallicRoughnessTexture
                "normal" -> key.hasNormalTexture
                "occlusion" -> key.hasOcclusionTexture
                "emissive" -> key.hasEmissiveTexture
                else -> true
            }
            if (!keyed) continue
            val sampler = map.slot + "Map"
            if (!material.hasParameter(sampler)) {
                warnOnce("standard-slot:$slug/$sampler", "standard material '$slug': its material draws no $sampler — left out")
                continue
            }
            val tex = standardPicture(slug!!, map) ?: continue
            mi.setParameter(sampler, tex, samplerFor(map.texture))
        }
        if (material.hasParameter("emissiveStrength")) mi.setParameter("emissiveStrength", 1f)
        standardInstances[cacheKey] = StandardHeld(slug.orEmpty(), k, mi)
        if (named) {
            // Every live write the slug took, over the record's values (gltfio's names, declared ones only).
            for ((name, values) in standardLive[slug].orEmpty()) setNamedParameter(mi, name, values)
            standardBySlug.getOrPut(slug!!) { ArrayList() } += mi
            // A key the core handed out comes with every parameter of it: nothing it asks for is uncarried.
            if (inForce == null) addGaps(slug, uncarried(key, form))
        }
        return mi
    }

    /**
     * A file mesh's look: its slug's node material on the mesh family when the package
     * loaded (the object is the node's place), else the record's standard form — the glTF form the core
     * decides on and writes (`/materialOverrides/{slug}/gltf/{name}`), a graph's fallback included; never the built-in look. No key: the
     * built-in look.
     */
    private fun meshLook(slug: String?, key: TrideMaterialKey?, look: TrideMaterialParams?, host: GeneratorHost): MaterialInstance? {
        if (key == null) return generatedMaterialForSlug(slug, look)
        graphInstance(slug, "mesh", host.parentPlace?.toString(), host.objectSeed)?.let { return it }
        return standardInstance(slug, key, look)
    }

    /** A file mesh's primitives: one per non-empty group in its own look; no groups = one in the mesh's. Empty = a look is missing. */
    private fun fileMeshPrimitives(mesh: TrideDatablockMesh, host: GeneratorHost, indexCount: Int): List<Primitive> {
        if (mesh.groups.isEmpty()) {
            val mi = meshLook(mesh.material, mesh.materialKey, mesh.materialParams, host) ?: return emptyList()
            return listOf(Primitive(0, indexCount, mi))
        }
        val out = ArrayList<Primitive>()
        for ((gi, g) in mesh.groups.withIndex()) {
            val start = g.indexStart.toInt(); val n = g.indexCount.toInt()
            if (n == 0 || start + n > indexCount) continue
            val mi = meshLook(g.material, g.materialKey ?: mesh.materialKey, g.materialParams, host) ?: return emptyList()
            out += Primitive(start, n, mi, gi)
        }
        return out
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
     * mismatch between the scene and this host — said once, drawn plainly meanwhile (a file mesh: the
     * record's glTF form; the other families: the built-in look).
     */
    private fun drawGraph(slug: String?, family: String): GraphEntry? {
        graphFor(slug, family)?.let { return it }
        if (slug != null && graphs.keys.any { it.startsWith("$slug\u0000") }) {
            warnOnce("family:$slug/$family", "'$slug' has no $family package — drawn plainly")
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
            val pixels = ref?.let { scenePictureRgba(assetBytes(it.uri), w, h) } ?: run {
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
     * foreign package aborts in `Material.Builder`. `false` = not loaded: a file mesh draws the record's
     * glTF form, the other families the built-in look; the caller tells `material.package-refused`.
     */
    fun registerMaterialGraph(slug: String, family: String, packageBytes: ByteArray, graph: TrideMaterialGraph): Boolean {
        val key = graphKeyOf(slug, family)
        if (graphs.containsKey(key)) return true
        val material = try {
            Material.Builder().payload(direct(packageBytes), packageBytes.size).build(engine)
        } catch (e: Throwable) {
            Log.w(TAG, "node material '$slug' ($family): Filament refused the package: ${e.message}")
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

    // ---- gltfio's vertex layout (a file mesh) --------------------------------------------------
    /**
     * gltfio's vertex buffer of a file primitive; `skinned`: `JOINTS_0` / `WEIGHTS_0` in the bone slots as gltfio puts
     * them (`USHORT4` integers, `FLOAT4`).
     */
    private fun buildGltfioVertexBuffer(vertexCapacity: Int, skinned: Boolean = false, stored: TrideStoredStreams? = null): VertexBuffer =
        VertexBuffer.Builder()
            .vertexCount(vertexCapacity)
            .bufferCount(if (skinned) 7 else 5)
            .storedOr(VertexBuffer.VertexAttribute.POSITION, 0, stored?.positions, VertexBuffer.AttributeType.FLOAT3, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.SHORT4, 0, 8)
            .normalized(VertexBuffer.VertexAttribute.TANGENTS)
            .storedOr(VertexBuffer.VertexAttribute.UV0, 2, stored?.uvs, VertexBuffer.AttributeType.FLOAT2, 8)
            .storedOr(VertexBuffer.VertexAttribute.COLOR, 3, stored?.colors, VertexBuffer.AttributeType.FLOAT4, 16)
            .storedOr(VertexBuffer.VertexAttribute.UV1, 4, stored?.uvs1, VertexBuffer.AttributeType.FLOAT2, 8)
            .apply {
                if (skinned) {
                    attribute(VertexBuffer.VertexAttribute.BONE_INDICES, 5, VertexBuffer.AttributeType.USHORT4, 0, 8)
                    storedOr(VertexBuffer.VertexAttribute.BONE_WEIGHTS, 6, stored?.weights, VertexBuffer.AttributeType.FLOAT4, 16)
                }
            }
            .build(engine)

    /** Filament's attribute type of a stored stream: its component × its count; `null` = none. */
    private fun storedType(s: TrideStoredStream): VertexBuffer.AttributeType? {
        val t = VertexBuffer.AttributeType.entries
        val first = when (s.component) {
            "i8" -> VertexBuffer.AttributeType.BYTE
            "u8" -> VertexBuffer.AttributeType.UBYTE
            "i16" -> VertexBuffer.AttributeType.SHORT
            "u16" -> VertexBuffer.AttributeType.USHORT
            else -> return null
        }
        val n = s.components.toInt()
        // Filament orders each family X, X2, X3, X4.
        return if (n in 1..4) t[first.ordinal + n - 1] else null
    }

    /** Attribute `attr` in `slot`: the stored stream's type, `normalized` and stride, else `fallback` / `stride`. */
    private fun VertexBuffer.Builder.storedOr(
        attr: VertexBuffer.VertexAttribute, slot: Int, stream: TrideStoredStream?, fallback: VertexBuffer.AttributeType, stride: Int,
    ): VertexBuffer.Builder {
        val type = stream?.let { storedType(it) }
        if (stream == null || type == null) return attribute(attr, slot, fallback, 0, stride)
        attribute(attr, slot, type, 0, stream.stride.toInt())
        return normalized(attr, stream.normalized)
    }

    /**
     * The stored streams holding one row per vertex of `vertexCount` and a type Filament takes — what
     * the layout and the upload both read; any other stream goes as float. `null` = none.
     */
    private fun storedFor(stored: TrideStoredStreams?, vertexCount: Int): TrideStoredStreams? {
        if (stored == null) return null
        fun rows(s: TrideStoredStream?) = s?.takeIf { storedType(it) != null && it.bytes.size == vertexCount * it.stride.toInt() }
        val out = TrideStoredStreams(rows(stored.positions), rows(stored.uvs), rows(stored.uvs1), rows(stored.colors), rows(stored.weights))
        return out.takeIf { listOf(it.positions, it.uvs, it.uvs1, it.colors, it.weights).any { s -> s != null } }
    }

    /** The layout key of stored streams: equal keys build equal vertex buffers. */
    private fun storedLayoutOf(stored: TrideStoredStreams?): String =
        if (stored == null) "" else listOf(stored.positions, stored.uvs, stored.uvs1, stored.colors, stored.weights)
            .joinToString(",") { s -> s?.let { "${it.component}${it.components}${if (it.normalized) "n" else ""}" } ?: "f" }

    /** The primitive carries the core's influences for each of its vertices. */
    private fun hasSkinStreams(p: FileStreams): Boolean =
        p.vertexCount > 0 && p.joints.size == p.vertexCount * 8 && p.weights.size == p.vertexCount * 16

    private fun directFloats(src: FloatArray, from: Int, count: Int): ByteBuffer {
        val out = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        out.asFloatBuffer().put(src, from, count)
        return out
    }

    /** A vertex range one `SurfaceOrientation` frames (a primitive of the file), with what it carries in the file. */
    private class FrameRange(val start: Int, val count: Int, val attributes: TrideVertexAttributes?)

    /**
     * One primitive of a file mesh as gltfio holds it — the mesh (its groups are its triangle primitives) or one of
     * its parts: its streams, the ranges framed, its topology, its box and its morph targets' deltas.
     */
    private class FileStreams(
        val uid: String,
        val positions: ByteArray,
        val normals: ByteArray,
        val tangents: ByteArray,
        val uvs: ByteArray,
        val uvs1: ByteArray?,
        val colors: ByteArray,
        val indices: ByteArray,
        val ranges: List<FrameRange>,
        val topology: String,
        val bounds: TrideBounds?,
        val targets: List<TrideMorphTarget>,
        /** The core's influences, set 0 (`u16 × 4` joints, `f32 × 4` weights normalized as gltfio does); empty without. */
        val joints: ByteArray,
        val weights: ByteArray,
        /** A skin cut past 256 joints: its parts' index ranges in this primitive. */
        val skinParts: List<TrideSkinPart>,
        /** Its quantized streams as stored, one row per vertex; `null` = all float. */
        val stored: TrideStoredStreams?,
    ) {
        val vertexCount get() = positions.size / 12
        val indexCount get() = indices.size / 4
    }

    private fun streamsOf(mesh: TrideDatablockMesh): FileStreams {
        val indexCount = mesh.indices.size / 4
        val ranges = if (mesh.groups.isEmpty()) listOf(FrameRange(0, indexCount, mesh.attributes))
            else mesh.groups.map { FrameRange(it.indexStart.toInt(), it.indexCount.toInt(), it.attributes ?: mesh.attributes) }
        return FileStreams(mesh.uid, mesh.positions, mesh.normals, mesh.tangents, mesh.uvs, mesh.uvs1, mesh.colors, mesh.indices,
            ranges, "triangles", mesh.bounds, mesh.morphTargets, mesh.joints ?: ByteArray(0), mesh.weights ?: ByteArray(0), mesh.skinParts,
            storedFor(mesh.stored, mesh.positions.size / 12))
    }

    private fun streamsOf(uid: String, part: TrideDatablockPart): FileStreams =
        FileStreams(uid, part.positions, part.normals, part.tangents, part.uvs, part.uvs1, part.colors, part.indices,
            listOf(FrameRange(0, part.indices.size / 4, part.attributes)), part.topology, part.bounds, part.morphTargets,
            part.joints, part.weights, part.skinParts, storedFor(part.stored, part.positions.size / 12))

    /** `width` floats per vertex of `base` moved by a target's `xyz` deltas (a tangent keeps its `w`); `null` = `base` is not that size. */
    private fun morphed(base: ByteArray, delta: ByteArray?, width: Int, vertexCount: Int): FloatArray? {
        if (base.size != vertexCount * width * 4) return null
        val out = floatsOf(base, vertexCount * width)
        if (delta == null || delta.size != vertexCount * 12) return out
        val d = floatsOf(delta, vertexCount * 3)
        for (i in 0 until vertexCount) for (c in 0..2) out[i * width + c] += d[i * 3 + c]
        return out
    }

    /**
     * gltfio's tangent frames of a file primitive (`TangentsJob`): per range — a group's vertex range, the core giving
     * each primitive its own vertices; the whole primitive without groups — Filament's `SurfaceOrientation` from what
     * that range carries in its file (`attributes`): normals, its own tangents, `TEXCOORD_0`, positions and triangles
     * always; a stream the file lacks is padding and never framed from (no normals → flat normals from the
     * triangles). `target`: that morph target's frames — positions, normals and tangents moved by its deltas, the
     * same rule. Only a triangle list is framed; a point / line / strip keeps identity frames (gltfio frames none).
     * SHORT4, as gltfio packs them.
     */
    private fun gltfioFrames(p: FileStreams, target: TrideMorphTarget? = null): ShortArray {
        val vertexCount = p.vertexCount
        val indexCount = p.indexCount
        val frames = ShortArray(vertexCount * 4)
        for (i in 0 until vertexCount) frames[i * 4 + 3] = Short.MAX_VALUE
        if (p.topology != "triangles") return frames
        val pos = morphed(p.positions, target?.positions, 3, vertexCount) ?: return frames
        val normals = morphed(p.normals, target?.normals, 3, vertexCount)
        val tangents = morphed(p.tangents, target?.tangents, 4, vertexCount)
        val uvs = if (p.uvs.size == vertexCount * 8) floatsOf(p.uvs, vertexCount * 2) else null
        val idx = IntArray(indexCount)
        ByteBuffer.wrap(p.indices).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(idx)
        for (r in p.ranges) {
            if (r.count < 3 || r.start + r.count > indexCount) continue
            var first = Int.MAX_VALUE; var last = -1
            for (k in r.start until r.start + r.count) { first = min(first, idx[k]); last = max(last, idx[k]) }
            if (last < first || last >= vertexCount) continue
            val n = last - first + 1
            val triangles = r.count / 3
            val local = ByteBuffer.allocateDirect(triangles * 12).order(ByteOrder.nativeOrder())
            val localInts = local.asIntBuffer()
            for (k in 0 until triangles * 3) localInts.put(idx[r.start + k] - first)
            val a = r.attributes
            // Without the core's word, a stream of the right size is the file's.
            fun inFile(flag: (TrideVertexAttributes) -> Boolean) = a == null || flag(a)
            val b = SurfaceOrientation.Builder().vertexCount(n)
            if (normals != null && inFile { it.normals }) b.normals(directFloats(normals, first * 3, n * 3))
            if (tangents != null && inFile { it.tangents }) b.tangents(directFloats(tangents, first * 4, n * 4))
            b.positions(directFloats(pos, first * 3, n * 3))
            b.triangleCount(triangles)
            b.triangles_uint32(local)
            if (uvs != null && inFile { it.uv0 }) b.uvs(directFloats(uvs, first * 2, n * 2))
            val built = runCatching { b.build() }
            val orientation = built.getOrNull()
            if (orientation == null) {
                warnOnce("frames:${p.uid}", "mesh '${p.uid}': SurfaceOrientation refused a primitive (${built.exceptionOrNull()?.message}) — its frames stay identity")
                continue
            }
            val out = ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder())
            try { orientation.getQuatsAsShort(out) } finally { orientation.destroy() }
            out.rewind()
            out.asShortBuffer().get(frames, first * 4, n * 4)
        }
        return frames
    }

    private fun directShorts(values: ShortArray): ByteBuffer {
        val out = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder())
        out.asShortBuffer().put(values)
        return out
    }

    /** A stream of `width` floats per vertex, or gltfio's dummy (every lane 1: UV (1, 1), white) when the primitive lacks it. */
    private fun streamOr1(bytes: ByteArray?, width: Int, vertexCount: Int): ByteBuffer =
        if (bytes != null && bytes.size == vertexCount * width * 4) direct(bytes)
        else directOf(FloatArray(vertexCount * width) { 1f })

    /** Fills `vb` / `ib` (gltfio's layout) from a file primitive: positions, frames, UV0, colours, UV1, indices, and the bone slots of a skinned buffer. */
    private fun uploadGltfioStreams(vb: VertexBuffer, ib: IndexBuffer, p: FileStreams, skinned: Boolean = false) {
        val vertexCount = p.vertexCount
        // A stored stream as the file stores it (its slot was laid out for it).
        val own = p.stored
        vb.setBufferAt(engine, 0, own?.positions?.let { direct(it.bytes) } ?: direct(p.positions))
        vb.setBufferAt(engine, 1, directShorts(gltfioFrames(p)))
        vb.setBufferAt(engine, 2, own?.uvs?.let { direct(it.bytes) } ?: streamOr1(p.uvs, 2, vertexCount))
        vb.setBufferAt(engine, 3, own?.colors?.let { direct(it.bytes) } ?: streamOr1(p.colors, 4, vertexCount))
        vb.setBufferAt(engine, 4, own?.uvs1?.let { direct(it.bytes) } ?: streamOr1(p.uvs1, 2, vertexCount))
        if (skinned) {
            vb.setBufferAt(engine, 5, direct(p.joints))
            vb.setBufferAt(engine, 6, own?.weights?.let { direct(it.bytes) } ?: direct(p.weights))
        }
        ib.setBuffer(engine, direct(p.indices))
    }

    /**
     * A renderable box as gltfio makes it: `Box().set(min, max)` of the union of its primitives' boxes — each the
     * core's bounds, else of its positions; a flat mesh keeps its zero extent.
     */
    private fun gltfioBox(primitives: List<FileStreams>): Box {
        val lo = FloatArray(3) { Float.POSITIVE_INFINITY }; val hi = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (p in primitives) {
            val b = p.bounds
            if (b != null && b.min.size == 3 && b.max.size == 3) {
                for (k in 0..2) { lo[k] = min(lo[k], b.min[k]); hi[k] = max(hi[k], b.max[k]) }
            } else {
                val src = floatsOf(p.positions, p.vertexCount * 3)
                for (i in 0 until p.vertexCount) for (k in 0..2) { lo[k] = min(lo[k], src[i * 3 + k]); hi[k] = max(hi[k], src[i * 3 + k]) }
            }
        }
        return Box(FloatArray(3) { (hi[it] + lo[it]) * 0.5f }, FloatArray(3) { (hi[it] - lo[it]) * 0.5f })
    }

    /** A file mesh's part (points, lines, strips, a triangle list of its own) in its own buffers, gltfio's layout. */
    private class FilePart(
        val vb: VertexBuffer,
        val ib: IndexBuffer,
        val type: RenderableManager.PrimitiveType,
        var mi: MaterialInstance,
        val streams: FileStreams,
        /** Its position in the mesh's `parts`. */
        val source: Int,
    )

    /** Filament's primitive type of a part's `topology`: the one gltfio draws that glTF mode with; `null` = not drawn. */
    private fun primitiveTypeOf(topology: String): RenderableManager.PrimitiveType? = when (topology) {
        "points" -> RenderableManager.PrimitiveType.POINTS
        "lines" -> RenderableManager.PrimitiveType.LINES
        "line_strip" -> RenderableManager.PrimitiveType.LINE_STRIP
        "triangles" -> RenderableManager.PrimitiveType.TRIANGLES
        "triangle_strip" -> RenderableManager.PrimitiveType.TRIANGLE_STRIP
        else -> null
    }

    /**
     * A file mesh's parts as gltfio draws a mesh's other primitives: each in its own buffers in gltfio's layout, its
     * own primitive type, in its slug's look on its key, as a group of the mesh. One Filament does not draw, or
     * whose look is missing, is left out and said.
     */
    private fun filePartsOf(mesh: TrideDatablockMesh, host: GeneratorHost, id: String, skinned: Boolean): List<FilePart> {
        val out = ArrayList<FilePart>()
        for ((i, part) in mesh.parts.withIndex()) {
            val streams = streamsOf(mesh.uid, part)
            if (part.positions.size % 12 != 0 || part.indices.size % 4 != 0 || streams.vertexCount == 0 || streams.indexCount == 0) continue
            val type = primitiveTypeOf(part.topology) ?: run {
                warnOnce("part-type:$id/${part.topology}", "file mesh '$id': part $i of topology '${part.topology}' is not drawn"); null
            } ?: continue
            val mi = meshLook(part.material, part.materialKey, part.materialParams, host) ?: run {
                warnOnce("part-look:$id/$i", "file mesh '$id': part $i has no material instance — not drawn"); null
            } ?: continue
            val bones = skinned && hasSkinStreams(streams)
            val vb = buildGltfioVertexBuffer(streams.vertexCount, bones, streams.stored)
            val ib = IndexBuffer.Builder().indexCount(streams.indexCount).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
            uploadGltfioStreams(vb, ib, streams, bones)
            out += FilePart(vb, ib, type, mi, streams, i)
        }
        return out
    }

    /**
     * A file mesh's morph targets: one buffer over every primitive's vertices — the mesh's (its groups share them),
     * then each drawn part's — with each target's position deltas (zeros where it moves none, `w` 1 as Filament
     * writes a `float3`) and its tangent frames, always (gltfio frames a target only on a mesh with `weights`). Its
     * count is the mesh's, at most Filament's 256. `null` without targets.
     */
    private fun fileMorphOf(mesh: TrideDatablockMesh, main: FileStreams?, parts: List<FilePart>, id: String): MorphTargetBuffer? {
        val count = min(mesh.morphTargets.size, 256)
        if (count == 0) return null
        if (mesh.morphTargets.size > 256) warnOnce("morph-count:$id", "file mesh '$id': ${mesh.morphTargets.size} morph targets, Filament draws the first 256")
        val primitives = listOfNotNull(main) + parts.map { it.streams }
        val total = primitives.sumOf { it.vertexCount }
        if (total == 0) return null
        for (p in parts) if (p.streams.targets.size != mesh.morphTargets.size) {
            warnOnce("morph-parts:$id", "file mesh '$id': a part has ${p.streams.targets.size} morph targets, the mesh ${mesh.morphTargets.size} — the rest stay still")
        }
        val buffer = MorphTargetBuffer.Builder().count(count).vertexCount(total).build(engine)
        for (t in 0 until count) {
            val positions = FloatArray(total * 4)
            val tangents = ShortArray(total * 4)
            var offset = 0
            for (p in primitives) {
                val target = p.targets.getOrNull(t)
                val deltas = target?.positions?.takeIf { it.size == p.vertexCount * 12 }?.let { floatsOf(it, p.vertexCount * 3) }
                for (v in 0 until p.vertexCount) {
                    val o = (offset + v) * 4
                    if (deltas != null) { positions[o] = deltas[v * 3]; positions[o + 1] = deltas[v * 3 + 1]; positions[o + 2] = deltas[v * 3 + 2] }
                    positions[o + 3] = 1f
                }
                gltfioFrames(p, target).copyInto(tangents, offset * 4)
                offset += p.vertexCount
            }
            buffer.setPositionsAt(engine, t, positions, total)
            buffer.setTangentsAt(engine, t, tangents, total)
        }
        return buffer
    }

    /** A skinned file mesh's own skin part renderables (the mesh's entity keeps its components). */
    private fun destroySkinDraws(s: MeshState) {
        val rm = engine.renderableManager
        val tm = engine.transformManager
        for (d in s.skinDraws) {
            if (!d.own) continue
            scene.removeEntity(d.entity)
            if (rm.hasComponent(d.entity)) rm.destroy(d.entity)
            if (tm.hasComponent(d.entity)) tm.destroy(tm.getInstance(d.entity))
            EntityManager.get().destroy(d.entity)
        }
        s.skinDraws = emptyList()
    }

    /** A file mesh's parts and morph buffer, once no renderable draws them (the skin parts' go first). */
    private fun destroyFilePartsAndMorph(s: MeshState) {
        destroySkinDraws(s)
        for (p in s.fileParts) { engine.destroyVertexBuffer(p.vb); engine.destroyIndexBuffer(p.ib) }
        s.fileParts = emptyList()
        s.morph?.let { engine.destroyMorphTargetBuffer(it) }; s.morph = null
        s.morphTargetCount = 0
    }

    private fun parentUnder(entity: Int, parentPlace: Int?) {
        val tm = engine.transformManager
        if (!tm.hasComponent(entity)) tm.create(entity)
        val parent = parentPlace?.let { entityForPlace(it) } ?: return
        if (tm.hasComponent(parent)) tm.setParent(tm.getInstance(entity), tm.getInstance(parent))
    }

    // ---- skins: the core's bone palettes -------------------------------------------------------
    /**
     * One renderable of a skinned file mesh and the palette that bends it — the core's `bone_palettes` entry of its
     * node's place and `part`, at most `bones` matrices (Filament keeps the bones per renderable). `own`: a skin
     * part's renderable, a child entity of the mesh's.
     */
    private class SkinDraw(val entity: Int, val part: Int, val bones: Int, val own: Boolean)

    /** The last palette the core handed for a place and part: its bones (`f32 × 16` each) and the part's box, when it has one. */
    private class BonePalette(val bones: ByteBuffer, val count: Int, val box: Box?)
    private val bonePalettes = HashMap<Long, BonePalette>()
    private fun paletteKey(place: Int, part: Int) = (place.toLong() shl 32) or (part.toLong() and 0xffffffffL)

    /**
     * One part of a skin cut past 256 joints (the core's `skinParts`): an index range of the mesh's group `group`
     * (`source` < 0) or of its drawn part `source` (a position in `fileParts`), bent by `bones` matrices of palette
     * `part`.
     */
    private class SkinRange(val part: Int, val source: Int, val group: Int, val start: Int, val count: Int, val bones: Int)

    /** The skin parts of a file mesh and of its drawn parts, in the core's order; empty for a skin drawn whole. */
    private fun skinRangesOf(main: FileStreams?, parts: List<FilePart>, id: String): List<SkinRange> {
        val out = ArrayList<SkinRange>()
        fun take(list: List<TrideSkinPart>, source: Int) {
            for (p in list) {
                val r = SkinRange(p.part.toInt(), source, p.group.toInt(), p.indexStart.toInt(), p.indexCount.toInt(), p.joints.size)
                if (r.count == 0 || r.bones == 0 || r.bones > 256) {
                    warnOnce("skin-part:$id/${r.part}", "file mesh '$id': skin part ${r.part} with ${r.count} indices and ${r.bones} bones — not drawn")
                    continue
                }
                out += r
            }
        }
        main?.let { take(it.skinParts, -1) }
        for ((k, part) in parts.withIndex()) take(part.streams.skinParts, k)
        return out
    }

    /**
     * A skin cut into parts: one renderable per part, a child entity of the mesh's at identity — the part's range of
     * its group's (or its drawn part's) buffers in that primitive's look, `skinning` by the part's palette, the mesh's
     * morph targets at that primitive's offset. Each starts at `box` until its first palette.
     */
    private fun buildSkinDraws(s: MeshState, ranges: List<SkinRange>, mainVertices: Int, box: Box, id: String) {
        val rm = engine.renderableManager
        val tm = engine.transformManager
        val morphOffsets = ArrayList<Int>()
        var offset = mainVertices
        for (p in s.fileParts) { morphOffsets += offset; offset += p.streams.vertexCount }
        if (!tm.hasComponent(s.entity)) tm.create(s.entity)
        val draws = ArrayList<SkinDraw>()
        for (r in ranges) {
            val vb: VertexBuffer?; val ib: IndexBuffer?; val type: RenderableManager.PrimitiveType; val mi: MaterialInstance?; val morphOffset: Int
            if (r.source < 0) {
                vb = s.vb; ib = s.ib; type = RenderableManager.PrimitiveType.TRIANGLES
                mi = s.primitives.firstOrNull { it.group == r.group }?.mi; morphOffset = 0
            } else {
                val fp = s.fileParts.getOrNull(r.source)
                vb = fp?.vb; ib = fp?.ib; type = fp?.type ?: RenderableManager.PrimitiveType.TRIANGLES; mi = fp?.mi
                morphOffset = morphOffsets.getOrElse(r.source) { 0 }
            }
            if (vb == null || ib == null || mi == null) {
                warnOnce("skin-draw:$id/${r.part}", "file mesh '$id': skin part ${r.part} has no buffers or look — not drawn")
                continue
            }
            val entity = EntityManager.get().create()
            val builder = RenderableManager.Builder(1).boundingBox(box)
                .material(0, mi)
                .geometry(0, type, vb, ib, r.start, r.count)
                .skinning(r.bones)
            s.morph?.let { builder.morphing(it).morphing(0, 0, morphOffset) }
            val casts = castsShadows(mi)
            builder.culling(true).castShadows(casts).receiveShadows(casts).build(engine, entity)
            tm.create(entity)
            tm.setParent(tm.getInstance(entity), tm.getInstance(s.entity))
            scene.addEntity(entity)
            if (s.morph != null) rm.setMorphWeights(rm.getInstance(entity), s.morphWeights, 0)
            draws += SkinDraw(entity, r.part, r.bones, true)
        }
        s.skinDraws = s.skinDraws + draws
    }

    /** Palette `p` on `draw`'s renderable: its bones (at most the renderable's), its box when it has one. */
    private fun putPalette(p: BonePalette, draw: SkinDraw) {
        val rm = engine.renderableManager
        if (!rm.hasComponent(draw.entity)) return
        val ri = rm.getInstance(draw.entity)
        val n = min(p.count, draw.bones)
        if (n > 0) rm.setBonesAsMatrices(ri, p.bones.duplicate().apply { rewind() }, n, 0)
        p.box?.let { rm.setAxisAlignedBoundingBox(ri, it) }
    }

    /** Every palette the core handed for `s`'s place, on its skin renderables. */
    private fun applyStoredPalettes(s: MeshState) {
        if (s.skinPlace < 0) return
        for (d in s.skinDraws) bonePalettes[paletteKey(s.skinPlace, d.part)]?.let { putPalette(it, d) }
    }

    /**
     * One palette of the core's `TickResult.bonePalettes` (`inv(W_mesh) × W_joint × IBM` per joint, `f32 × 16` LE)
     * and its part's box (`[minX, minY, minZ, maxX, maxY, maxZ]`, `null` = none): `setBones` and the box on the skin
     * renderable of the mesh drawn under `place` for `part`, kept so a renderable built later starts at it.
     * `false` = no renderable took it (yet).
     */
    fun setBonePalette(place: Int, part: Int, bones: ByteArray, bounds: FloatArray?): Boolean {
        if (place < 0 || part < 0 || bones.size % 64 != 0) return false
        val box = bounds?.takeIf { it.size == 6 }?.let { b ->
            Box(FloatArray(3) { (b[it + 3] + b[it]) * 0.5f }, FloatArray(3) { (b[it + 3] - b[it]) * 0.5f })
        }
        val p = BonePalette(direct(bones), bones.size / 64, box)
        bonePalettes[paletteKey(place, part)] = p
        var took = false
        for (s in meshes.values) {
            if (s.skinPlace != place) continue
            for (d in s.skinDraws) if (d.part == part) { putPalette(p, d); took = true }
        }
        if (took) onSceneMutated()
        return took
    }

    /** The skin renderables of generated mesh `id`: part, bones, own, primitives, box min / max (test introspection). */
    fun generatedMeshSkinDraws(id: String): List<SkinDrawInfo> {
        val s = meshes[id] ?: return emptyList()
        val rm = engine.renderableManager
        return s.skinDraws.map { d ->
            val has = rm.hasComponent(d.entity)
            val ri = if (has) rm.getInstance(d.entity) else 0
            val box = if (has) rm.getAxisAlignedBoundingBox(ri, null) else Box()
            SkinDrawInfo(d.part, d.bones, d.own, if (has) rm.getPrimitiveCount(ri) else 0, box.center.copyOf(), box.halfExtent.copyOf())
        }
    }

    // ---- generated trunks ---------------------------------------------------------------------
    /** One draw of a renderable; `group` = the file mesh's draw group it is (a skin part names it). */
    private class Primitive(val start: Int, val count: Int, var mi: MaterialInstance, val group: Int = 0)

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
        /** The buffers sit in gltfio's vertex layout (a file mesh). */
        var gltfioLayout = false
        /** The file mesh's parts, drawn after its groups. */
        var fileParts: List<FilePart> = emptyList()
        /** The file mesh's morph targets over every primitive's vertices, their count and the place whose weights drive them. */
        var morph: MorphTargetBuffer? = null
        var morphTargetCount = 0
        var morphPlace = -1
        /** The weights last set (kept across rebuilds). */
        var morphWeights = FloatArray(0)
        /** A skinned file mesh — the place whose palettes bend it, whether its own buffers carry the bone slots, its skin renderables. */
        var skinPlace = -1
        var skinLayout = false
        /** The stored streams' layout its buffers were built for; `""` = all float. */
        var storedLayout = ""
        var skinDraws: List<SkinDraw> = emptyList()
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
        destroyFilePartsAndMorph(s)
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

    /**
     * Create or update the trunk renderable of generator `id` from the runtime's buffers (in place when they fit). A
     * file mesh (`host.fileMesh`) is drawn as gltfio draws a mesh: its groups, then its parts as more
     * primitives, its morph targets; one with parts or targets (now or before) is rebuilt whole.
     */
    fun upsertGeneratedMesh(id: String, g: TrideGeneratedGeometry, host: GeneratorHost): Boolean {
        val positions = g.positions; val indices = g.indices
        if (positions.size % 12 != 0 || indices.size % 4 != 0) return false
        val vertexCount = positions.size / 12
        val indexCount = indices.size / 4
        val lines = g.topology == "lines"
        val type = if (lines) RenderableManager.PrimitiveType.LINES else RenderableManager.PrimitiveType.TRIANGLES
        val rm = engine.renderableManager
        // A file mesh drawn as gltfio draws its primitives (its own looks and layout).
        val fileMesh = host.fileMesh?.takeIf { !lines }
        val hasMain = vertexCount > 0 && indexCount > 0
        val hasParts = fileMesh?.parts?.isNotEmpty() == true
        if (!hasMain && !hasParts) {
            meshes.remove(id)?.let { destroyMeshState(it); onSceneMutated() }
            return true
        }
        val wantFlow = fileMesh == null && !lines && host.pulse != null && g.flow.size == vertexCount * 16
        val state = meshes.getOrPut(id) { MeshState(EntityManager.get().create()) }
        var mi: MaterialInstance? = if (wantFlow) flowInstanceFor(state, id, host.materialParams, host.pulse!!) else null
        val drawFlow = mi != null
        if (drawFlow && maskCutoff(host.materialParams, id) != null) warnOnce("flow-mask:$id", "generated mesh '$id': pulsed trunk with a masked look — drawn opaque")
        if (drawFlow && host.materialParams?.thin == true) warnOnce("flow-thin:$id", "generated mesh '$id': pulsed trunk with a thin look — the pulses win")
        val familyPrimitives = fileMesh?.let { if (hasMain) fileMeshPrimitives(it, host, indexCount) else emptyList() }
        if (familyPrimitives != null) mi = familyPrimitives.firstOrNull()?.mi
        else if (mi == null) mi = lookFor(host.materialSlug, host.materialParams, host)
        // A file mesh of parts alone has no main look; its parts carry their own.
        if (mi == null && !(fileMesh != null && !hasMain)) {
            warnOnce("mesh:$id", "generated mesh '$id': no material available — not drawn")
            if (!rm.hasComponent(state.entity)) { destroyMeshState(state); meshes.remove(id) }
            return false
        }
        state.baseSlug = host.materialSlug
        val primitives = familyPrimitives ?: primitivesOf(g, mi!!, host, indexCount, id)
        val gltfioLayout = fileMesh != null
        val hasMorph = fileMesh?.morphTargets?.isNotEmpty() == true
        // A file mesh its node bends: the skin's joints when the skin is whole; its buffers carry the bone slots where it has influences.
        val skinBones = if (fileMesh != null) min(host.skinBones, 256) else 0
        val skinMesh = skinBones > 0
        val mainStreams = if (fileMesh != null && hasMain) streamsOf(fileMesh) else null
        val mainSkinned = skinMesh && mainStreams != null && hasSkinStreams(mainStreams)
        // Skin part renderables hang under the mesh's entity: they go before it.
        destroySkinDraws(state)
        if (fileMesh != null && (hasParts || hasMorph || skinMesh || state.fileParts.isNotEmpty() || state.morph != null || !hasMain)) {
            // The parts' buffers and the morph buffer go only once no renderable draws them.
            if (rm.hasComponent(state.entity)) rm.destroy(state.entity)
            destroyFilePartsAndMorph(state)
            if (!hasMain) {
                state.vb?.let { engine.destroyVertexBuffer(it) }; state.vb = null
                state.ib?.let { engine.destroyIndexBuffer(it) }; state.ib = null
            }
        }
        var needRenderable = true
        if (state.vb != null && state.ib != null) {
            // The vertex layout differs with the flow stream and gltfio's layout → a switch rebuilds the buffers.
            val fits = vertexCount <= state.vertexCapacity && indexCount <= state.indexCapacity && state.flow == drawFlow &&
                state.gltfioLayout == gltfioLayout && state.skinLayout == mainSkinned &&
                state.storedLayout == storedLayoutOf(mainStreams?.stored)
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
        if ((state.vb == null || state.ib == null) && hasMain) {
            state.vertexCapacity = max((vertexCount * 3 + 1) / 2, 1)
            state.indexCapacity = max((indexCount * 3 + 1) / 2, 1)
            state.flow = drawFlow
            state.gltfioLayout = gltfioLayout
            state.skinLayout = mainSkinned
            state.storedLayout = storedLayoutOf(mainStreams?.stored)
            if (gltfioLayout) {
                state.vb = buildGltfioVertexBuffer(state.vertexCapacity, mainSkinned, mainStreams?.stored)
            } else {
                state.vb = buildVertexBuffer(state.vertexCapacity, drawFlow)
                state.vb!!.setBufferAt(engine, 3, packColors(ByteArray(0), state.vertexCapacity))
                if (drawFlow) state.vb!!.setBufferAt(engine, 4, ByteBuffer.allocateDirect(state.vertexCapacity * 16))
            }
            state.ib = IndexBuffer.Builder().indexCount(state.indexCapacity).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        }
        val box: Box
        if (fileMesh != null) {
            val main = mainStreams
            main?.let { uploadGltfioStreams(state.vb!!, state.ib!!, it, mainSkinned) }
            state.fileParts = filePartsOf(fileMesh, host, id, skinMesh)
            state.morph = fileMorphOf(fileMesh, main, state.fileParts, id)
            state.morphTargetCount = state.morph?.count ?: 0
            if (primitives.isEmpty() && state.fileParts.isEmpty()) {
                destroyMeshState(state); meshes.remove(id); onSceneMutated()
                return true
            }
            box = gltfioBox(listOfNotNull(main) + state.fileParts.map { it.streams })
        } else {
            uploadStreams(state.vb!!, state.ib!!, positions, g.normals, g.uvs, g.colors, g.tangents, indices, if (state.flow) g.flow else null, lines)
            box = boundsOf(positions, vertexCount)
        }
        if (!state.flow && state.flowMi != null) {
            // Pulses left the recipe: the renderable points at the plain material again.
            engine.destroyMaterialInstance(state.flowMi!!); state.flowMi = null
            state.pulseTexture?.let { engine.destroyTexture(it) }; state.pulseTexture = null
            state.pulseCapacity = 0; state.pulseCount = 0
        }
        state.lines = lines; state.mi = mi; state.primitives = primitives; state.epoch = g.epoch
        state.morphPlace = host.parentPlace ?: -1
        state.skinPlace = if (skinMesh) state.morphPlace else -1
        if (state.morph != null && needRenderable) {
            // gltfio's weights at build: the node's (else the mesh's) — here the last ones set.
            host.morphWeights?.let { state.morphWeights = it.copyOf() }
            state.morphWeights = state.morphWeights.copyOf(state.morphTargetCount)
        }
        // A skin cut into parts draws one renderable per part (under the mesh's entity); the mesh's entity then draws nothing itself.
        val skinRanges = if (skinMesh) skinRangesOf(mainStreams, state.fileParts, id) else emptyList()
        val skinSplit = skinRanges.isNotEmpty()
        if (needRenderable && skinSplit) {
            buildSkinDraws(state, skinRanges, if (hasMain) vertexCount else 0, box, id)
        } else if (needRenderable) {
            val parts = state.fileParts
            val builder = RenderableManager.Builder(primitives.size + parts.size).boundingBox(box)
            for ((i, p) in primitives.withIndex()) builder.material(i, p.mi).geometry(i, type, state.vb!!, state.ib!!, p.start, p.count)
            for ((k, part) in parts.withIndex()) {
                val i = primitives.size + k
                builder.material(i, part.mi).geometry(i, part.type, part.vb, part.ib, 0, part.streams.indexCount)
            }
            state.morph?.let { morph ->
                // The groups share the mesh's vertices (offset 0); each part's follow, in order.
                builder.morphing(morph)
                for (i in primitives.indices) builder.morphing(0, i, 0)
                var offset = if (hasMain) vertexCount else 0
                for ((k, part) in parts.withIndex()) {
                    builder.morphing(0, primitives.size + k, offset)
                    offset += part.streams.vertexCount
                }
            }
            // gltfio: `skinning(joints)` on the mesh's renderable; the palette comes from the core.
            if (skinMesh) builder.skinning(skinBones)
            // A pen in an Add package turns the whole renderable's shadows off (receiving too).
            val shaded = !lines && primitives.all { castsShadows(it.mi) } && parts.all { castsShadows(it.mi) }
            builder.culling(true).castShadows(shaded).receiveShadows(shaded).build(engine, state.entity)
            scene.addEntity(state.entity)
            if (state.morph != null) rm.setMorphWeights(rm.getInstance(state.entity), state.morphWeights, 0)
            if (skinMesh) state.skinDraws = listOf(SkinDraw(state.entity, 0, skinBones, false))
        } else {
            val ri = rm.getInstance(state.entity)
            for ((i, p) in primitives.withIndex()) rm.setGeometryAt(ri, i, type, state.vb!!, state.ib!!, p.start, p.count)
            rm.setAxisAlignedBoundingBox(ri, box)
        }
        parentUnder(state.entity, host.parentPlace)
        // The palettes the core handed already (they arrive per tick, a rebuild comes between them).
        applyStoredPalettes(state)
        onSceneMutated()
        return true
    }

    /** How many morph targets the file mesh drawn under place [place] has; 0 without one. */
    fun morphTargetCountAt(place: Int): Int = meshes.values.filter { it.morph != null && it.morphPlace == place }.maxOfOrNull { it.morphTargetCount } ?: 0

    /** [weights] onto the morph targets of every file mesh drawn under place [place]; `false` = none there. */
    fun setMorphWeightsAt(place: Int, weights: FloatArray): Boolean {
        val rm = engine.renderableManager
        var any = false
        for (s in meshes.values) {
            if (s.morph == null || s.morphPlace != place) continue
            s.morphWeights = s.morphWeights.copyOf(s.morphTargetCount)
            weights.copyInto(s.morphWeights, 0, 0, min(weights.size, s.morphTargetCount))
            if (rm.hasComponent(s.entity)) rm.setMorphWeights(rm.getInstance(s.entity), s.morphWeights, 0)
            // A skin cut into parts: each part's renderable draws the same targets.
            for (d in s.skinDraws) if (d.own && rm.hasComponent(d.entity)) rm.setMorphWeights(rm.getInstance(d.entity), s.morphWeights, 0)
            any = true
        }
        if (any) onSceneMutated()
        return any
    }

    /** The primitive types of generated mesh `id`, its groups then its parts (test introspection). */
    fun generatedMeshPrimitiveTypes(id: String): List<String> {
        val s = meshes[id] ?: return emptyList()
        val main = if (s.lines) "lines" else "triangles"
        return s.primitives.map { main } + s.fileParts.map { it.type.name.lowercase() }
    }

    /** Filament's morph target count of generated mesh `id`'s renderable (test introspection). */
    fun generatedMeshMorphTargetCount(id: String): Int {
        val s = meshes[id] ?: return 0
        val rm = engine.renderableManager
        // A skin cut into parts: the mesh's entity draws nothing, each part's renderable the targets.
        val e = if (rm.hasComponent(s.entity)) s.entity else s.skinDraws.firstOrNull { it.own && rm.hasComponent(it.entity) }?.entity ?: return 0
        return rm.getMorphTargetCount(rm.getInstance(e))
    }

    /** The morph weights generated mesh `id` holds (test introspection). */
    fun generatedMeshMorphWeights(id: String): FloatArray = meshes[id]?.morphWeights?.copyOf() ?: FloatArray(0)

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
        parentPlace: Int?, look: PaletteLook?,
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
            if (!syncModuleChunk(chunkKey(key, chunk), uid, mesh, transforms, colors, drawn, count, parentPlace, look)) break
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
        parentPlace: Int?, look: PaletteLook?,
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
        val thin = params?.thin == true
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
        parentUnder(batch.entity, parentPlace)
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

    fun moduleInstanceCount(id: String): Int {
        val prefix = "$id\u0000"
        return batches.entries.filter { it.key.startsWith(prefix) && it.value.visible }.sumOf { it.value.count }
    }

    fun dispose() {
        for (s in meshes.values) destroyMeshState(s); meshes.clear()
        for (b in batches.values) destroyBatch(b); batches.clear()
        for (m in moduleMeshes.values) { engine.destroyVertexBuffer(m.vb); engine.destroyIndexBuffer(m.ib) }; moduleMeshes.clear()
        for (mi in paletteInstances.values) engine.destroyMaterialInstance(mi); paletteInstances.clear()
        // The standard instances, after the renderables wearing them, before the provider's materials go.
        bonePalettes.clear()
        for (h in standardInstances.values) engine.destroyMaterialInstance(h.mi); standardInstances.clear(); standardBySlug.clear()
        standardKeys.clear(); standardLive.clear()
        for (t in pictures.values) t?.let { engine.destroyTexture(it) }; pictures.clear()
        stb?.destroy(); stb = null
        barkInstance?.let { engine.destroyMaterialInstance(it) }; barkInstance = null
        destroyMaterialGraphs()
        whiteTexture?.let { engine.destroyTexture(it) }; whiteTexture = null
        for (m in materials.values) m?.let { engine.destroyMaterial(it) }; materials.clear()
    }
}

/**
 * A scene picture as straight-alpha RGBA8 of exactly `w` × `h`, decoded by gltfio's stb (`StbImage`: the
 * `stbi_load_from_memory` the standard maps, iOS and the web read pictures with); `null` = undecodable or another size.
 * Needs gltfio's native library (`Gltfio.init`, done before the bridge's engine).
 */
internal fun scenePictureRgba(bytes: ByteArray?, w: Int, h: Int): ByteBuffer? {
    if (bytes == null || bytes.isEmpty()) return null
    val size = IntArray(2)
    val encoded = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
    val pixels = StbImage.decodeRgba(encoded, size) ?: return null
    return if (size[0] == w && size[1] == h) pixels else null
}
