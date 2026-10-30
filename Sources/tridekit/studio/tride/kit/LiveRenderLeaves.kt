/**
 * The animatable render settings over the scene's authored ones — saturation, fog colour, bloom strength
 * and pixelation's switch and size as the frames wrote them. Pure: folds one frame's render-settings
 * writes and says which leaf the view must take again; `FilamentSceneBridge` draws them.
 *
 * Public API (internal): `LiveRenderLeaves(authoredFog)` — `fold(mutations, complete)` → `Moved`;
 * `saturation` / `fogColor` / `bloomStrength` / `pixelationEnabled` / `pixelationSize` (`null` = the
 * authored value); `liveBloom(authored, live)`.
 *
 * Non-obvious constraints:
 *   - A leaf is taken again only when its value moved: a held clip repeats its value every tick, and a
 *     ColorGrading rebuild is not free.
 *   - A fog channel lane patches the live colour, else the authored one.
 *   - `complete` = a whole frame (the first after `reset`): a leaf the frame does not carry returns to
 *     the authored value, as nothing drives it.
 *   - The bloom strength rides on the authored bloom: it shows only while the authored bloom is enabled,
 *     and a strength below 0 draws as 0.
 *   - A new view starts from the authored settings: it gets a new instance (nothing live yet).
 */
package studio.tride.kit

import studio.tride.core.TrideBloomOptions
import kotlin.math.max

internal class LiveRenderLeaves(private val authoredFog: DoubleArray) {
    var saturation: Double? = null
        private set
    var fogColor: DoubleArray? = null
        private set
    var bloomStrength: Double? = null
        private set
    var pixelationEnabled: Boolean? = null
        private set
    var pixelationSize: Double? = null
        private set

    /** Which leaves moved in the last [fold] (`pixelation`: the switch or the size). */
    data class Moved(val saturation: Boolean, val fog: Boolean, val bloom: Boolean, val pixelation: Boolean = false) {
        val any: Boolean get() = saturation || fog || bloom || pixelation
    }

    /** The fog colour the view shows now: live, else authored. */
    val fogShown: DoubleArray get() = fogColor ?: authoredFog

    fun fold(mutations: List<RenderSettingsMutation>, complete: Boolean): Moved {
        var sat = if (complete) null else saturation
        var fog = if (complete) null else fogColor
        var bloom = if (complete) null else bloomStrength
        var pixelOn = if (complete) null else pixelationEnabled
        var pixelSize = if (complete) null else pixelationSize
        for (m in mutations) when (m.property) {
            "saturation" -> (m.value as? Double)?.let { sat = it }
            "bloom_strength" -> (m.value as? Double)?.let { bloom = it }
            "fog_color" -> patch(fog ?: authoredFog, m.component, m.value)?.let { fog = it }
            "pixelation_enabled" -> (m.value as? Boolean)?.let { pixelOn = it }
            "pixelation_size" -> (m.value as? Double)?.let { pixelSize = it }
        }
        val moved = Moved(
            sat != saturation, !sameColour(fog, fogColor), bloom != bloomStrength,
            pixelOn != pixelationEnabled || pixelSize != pixelationSize,
        )
        saturation = sat
        fogColor = fog
        bloomStrength = bloom
        pixelationEnabled = pixelOn
        pixelationSize = pixelSize
        return moved
    }

    private fun patch(base: DoubleArray, component: Int?, value: Any): DoubleArray? = when {
        component != null && value is Double -> base.copyOf().also { it[component] = value }
        value is DoubleArray && value.size >= 3 -> doubleArrayOf(value[0], value[1], value[2])
        else -> null
    }

    private fun sameColour(a: DoubleArray?, b: DoubleArray?): Boolean =
        if (a == null || b == null) a === b else a.contentEquals(b)

    companion object {
        /** The authored bloom with the live strength on it (`null` = authored); below 0 draws as 0. */
        fun liveBloom(authored: TrideBloomOptions, live: Double?): TrideBloomOptions =
            authored.copy(strength = max(0f, live?.toFloat() ?: authored.strength))
    }
}
