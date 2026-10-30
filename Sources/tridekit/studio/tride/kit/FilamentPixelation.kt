/**
 * The pixelation pass over the finished frame — the web player's and iOS's three
 * passes on Filament-Android. The scene view draws its frame at full resolution, every effect included,
 * into a render target; three full-screen triangles of the built-in `tride_pixelate` material, each in
 * its own offscreen view, then average every block exactly and draw it as one colour on the swap chain.
 *
 * Public API (internal): `FilamentPixelation(engine, sceneView, material, floatTargets)` — `configure(grid,
 * width, height)` binds or frees the scene view's target (`true` = the frame shows something else now),
 * `render(renderer)` after the scene view, `off()`, `active`, `grid`, `targets`, `destroy()`.
 *
 * Non-obvious constraints:
 *   - Passes: rows (frame → columns × H, each texel the mean of its block column on its row), columns
 *     (→ columns × rows, the block's mean), out (→ the swap chain). The grid — where the blocks fall,
 *     whether the pass runs, the size of its block targets — is the core's (`pixelationGrid`), never
 *     laid here; the block targets are RGBA16F where the GPU renders to it, else RGBA8 (the caller says).
 *   - Lifetime: a target goes only once nothing points at it — the view drawing into it lets go and
 *     every instance samples a 1 × 1 placeholder first (Filament commits material instances at the next
 *     frame; an instance left on a destroyed texture aborts the renderer).
 *   - The views run without post-processing, shadows or culling; the frame target is RGBA8 (the swap
 *     chain's) with a depth attachment for a scene view whose post-processing is off.
 */
package studio.tride.kit

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderTarget
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import studio.tride.core.TridePixelationGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class FilamentPixelation(
    private val engine: Engine,
    private val sceneView: View,
    private val material: Material,
    private val floatTargets: Boolean,
) {
    private class Target(val width: Int, val height: Int, val color: Texture, val depth: Texture?, val rt: RenderTarget)
    private class Pass(val scene: Scene, val view: View, val entity: Int, val instance: MaterialInstance)

    private val sampler = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private val placeholder: Texture = Texture.Builder()
        .width(1).height(1).levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.RGBA8)
        .build(engine)
        .also { it.setImage(engine, 0, Texture.PixelBufferDescriptor(ByteBuffer.allocateDirect(4), Texture.Format.RGBA, Texture.Type.UBYTE)) }
    private val vertices: VertexBuffer
    private val indices: IndexBuffer
    private val cameraEntity = EntityManager.get().create()
    private val passes: List<Pass>

    private var frame: Target? = null
    private var rows: Target? = null
    private var blocks: Target? = null
    private var width = 0
    private var height = 0

    /** The grid drawn now; `null` while off. */
    var grid: TridePixelationGrid? = null
        private set

    /** The scene view renders into the pass — [render] must follow it in the frame. */
    val active: Boolean get() = grid != null

    /** Render targets held now — 0 while off (leak checks). */
    val targets: Int get() = listOfNotNull(frame, rows, blocks).size

    init {
        // One triangle over the viewport, in clip space (the material's device vertex domain).
        val triangle = floatArrayOf(-1f, -1f, 0f, 1f, 3f, -1f, 0f, 1f, -1f, 3f, 0f, 1f)
        vertices = VertexBuffer.Builder()
            .vertexCount(3)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)
        vertices.setBufferAt(engine, 0, ByteBuffer.allocateDirect(triangle.size * 4).order(ByteOrder.nativeOrder()).apply { asFloatBuffer().put(triangle) })
        indices = IndexBuffer.Builder()
            .indexCount(3)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indices.setBuffer(engine, ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder()).apply { asShortBuffer().put(shortArrayOf(0, 1, 2)) })
        val camera = engine.createCamera(cameraEntity)
        passes = List(3) {
            val scene = engine.createScene()
            val view = engine.createView()
            view.scene = scene
            view.camera = camera
            view.isPostProcessingEnabled = false
            view.setShadowingEnabled(false)
            view.setFrustumCullingEnabled(false)
            view.setScreenSpaceRefractionEnabled(false)
            val instance = material.createInstance()
            instance.setParameter("source", placeholder, sampler)
            val entity = EntityManager.get().create()
            RenderableManager.Builder(1)
                .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
                .material(0, instance)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices, 0, 3)
                .culling(false)
                .castShadows(false)
                .receiveShadows(false)
                .build(engine, entity)
            scene.addEntity(entity)
            Pass(scene, view, entity, instance)
        }
    }

    /**
     * Draw [g] on a [w] × [h] frame from the next render; a grid that does not pixelate turns the pass
     * off. `true` when what the frame shows changed.
     */
    fun configure(g: TridePixelationGrid, w: Int, h: Int): Boolean {
        if (!g.pixelates || w <= 0 || h <= 0) return off()
        val now = grid
        if (now != null && w == width && h == height && g.block == now.block && g.shiftX == now.shiftX && g.shiftY == now.shiftY) return false
        if (frame.let { it == null || it.width != w || it.height != h }) {
            off()
            frame = target(w, h, withDepth = true, float16 = false)
        }
        // The block targets' texels come with the grid — never from the sizes drawn before.
        val texX = maxOf(g.targetColumns.toInt(), g.columns.toInt())
        val texY = maxOf(g.targetRows.toInt(), g.rows.toInt())
        val (rowsPass, columnsPass, outPass) = passes
        rows?.let {
            if (it.width != texX || it.height != h) {
                rowsPass.view.renderTarget = null
                columnsPass.instance.setParameter("source", placeholder, sampler)
                rows = free(it)
            }
        }
        val rowsTarget = rows ?: target(texX, h, withDepth = false, float16 = floatTargets).also { rows = it }
        blocks?.let {
            if (it.width != texX || it.height != texY) {
                columnsPass.view.renderTarget = null
                outPass.instance.setParameter("source", placeholder, sampler)
                blocks = free(it)
            }
        }
        val blocksTarget = blocks ?: target(texX, texY, withDepth = false, float16 = floatTargets).also { blocks = it }
        val frameTarget = frame ?: return false
        val columns = g.columns.toInt()
        val rowCount = g.rows.toInt()
        source(rowsPass, frameTarget, w, h, g, 0)
        rowsPass.view.renderTarget = rowsTarget.rt
        rowsPass.view.viewport = Viewport(0, 0, columns, h)
        source(columnsPass, rowsTarget, columns, h, g, 1)
        columnsPass.view.renderTarget = blocksTarget.rt
        columnsPass.view.viewport = Viewport(0, 0, columns, rowCount)
        source(outPass, blocksTarget, columns, rowCount, g, 2)
        outPass.view.viewport = Viewport(0, 0, w, h)
        sceneView.renderTarget = frameTarget.rt
        grid = g
        width = w
        height = h
        return true
    }

    /** The three passes onto the swap chain, after the scene view, inside beginFrame / endFrame. */
    fun render(renderer: Renderer) {
        if (!active) return
        for (p in passes) renderer.render(p.view)
    }

    /** Frees the targets (the scene view draws on the swap chain again); `true` when it was on. */
    fun off(): Boolean {
        val was = active
        if (frame != null) sceneView.renderTarget = null
        passes[0].view.renderTarget = null
        passes[1].view.renderTarget = null
        for (p in passes) p.instance.setParameter("source", placeholder, sampler)
        frame = frame?.let { free(it) }
        rows = rows?.let { free(it) }
        blocks = blocks?.let { free(it) }
        grid = null
        width = 0
        height = 0
        return was
    }

    /** Everything the pass made, its material included — before the scene view and the engine go. */
    fun destroy() {
        off()
        for (p in passes) {
            p.scene.removeEntity(p.entity)
            engine.destroyEntity(p.entity)
            EntityManager.get().destroy(p.entity)
            engine.destroyMaterialInstance(p.instance)
            engine.destroyView(p.view)
            engine.destroyScene(p.scene)
        }
        engine.destroyVertexBuffer(vertices)
        engine.destroyIndexBuffer(indices)
        engine.destroyTexture(placeholder)
        engine.destroyMaterial(material)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
    }

    private fun texture(w: Int, h: Int, format: Texture.InternalFormat, usage: Int): Texture =
        Texture.Builder()
            .width(w).height(h).levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(format)
            .usage(usage)
            .build(engine)

    private fun target(w: Int, h: Int, withDepth: Boolean, float16: Boolean): Target {
        val color = texture(w, h, if (float16) Texture.InternalFormat.RGBA16F else Texture.InternalFormat.RGBA8, Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.SAMPLEABLE)
        val depth = if (withDepth) texture(w, h, Texture.InternalFormat.DEPTH32F, Texture.Usage.DEPTH_ATTACHMENT) else null
        val builder = RenderTarget.Builder().texture(RenderTarget.AttachmentPoint.COLOR, color)
        if (depth != null) builder.texture(RenderTarget.AttachmentPoint.DEPTH, depth)
        return Target(w, h, color, depth, builder.build(engine))
    }

    private fun free(t: Target): Target? {
        engine.destroyRenderTarget(t.rt)
        engine.destroyTexture(t.color)
        t.depth?.let { engine.destroyTexture(it) }
        return null
    }

    /** [p] reads [from], whose bottom-left [contentW] × [contentH] the previous pass drew. */
    private fun source(p: Pass, from: Target, contentW: Int, contentH: Int, g: TridePixelationGrid, mode: Int) {
        p.instance.setParameter("source", from.color, sampler)
        p.instance.setParameter("sourceSize", from.width.toFloat(), from.height.toFloat(), contentW.toFloat(), contentH.toFloat())
        p.instance.setParameter("grid", g.block.toFloat(), g.shiftX.toFloat(), g.shiftY.toFloat(), mode.toFloat())
    }
}
