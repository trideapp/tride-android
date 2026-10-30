/**
 * The host's render rate on the frame loop: which vsyncs wake the scene and the view.
 *
 * Internal API:
 *   - `RenderRate.resolve(requested)` — `null` keeps the default; 0 or less is refused (the default stays,
 *     the view reports it once); a rate above the screen's is the screen's (a vsync wakes at most once).
 *   - `FramePacer(fps)` — `admit(frameTimeNanos)`: a wake is due once the time since the last one reaches the
 *     rate's interval less half a display frame, so 30 on a 60 Hz or a 120 Hz screen wakes every second or
 *     fourth vsync, steadily, through jitter; the leftover is kept within half an interval (a rate that does
 *     not divide the screen's averages out, a stall is not caught up with a burst). `maxStepMs` — the step
 *     cap: two frames of the rate, two 60 Hz frames without one.
 *
 * Non-obvious constraints:
 *   - No rate = every vsync, exactly the loop before the render rate existed.
 *   - The scene's logic stays 60 Hz: the core steps over the time a wake hands it (the scene's fixed step). Touches
 *     are queued in the core (`pointer`) and resolve on the next wake.
 *   - The same rule as TrideKit iOS (`FramePacer` in `TickDriver.swift`) and the web player.
 */
package studio.tride.kit

internal object RenderRate {
    data class Resolved(val fps: Int?, val refused: Boolean)

    fun resolve(requested: Int?): Resolved = when {
        requested == null -> Resolved(null, false)
        requested > 0 -> Resolved(requested, false)
        else -> Resolved(null, true)
    }

    fun refusalMessage(requested: Int): String =
        "preferredFps $requested is not a frame rate (it must be above 0) — the view keeps its default rate"
}

internal class FramePacer(fps: Int? = null) {
    /** Wakes a second; `null` = every vsync. */
    var fps: Int? = fps
        private set
    private var lastNanos = 0L
    private var started = false
    private var budgetNanos = 0L

    private val intervalNanos: Long? get() = fps?.let { 1_000_000_000L / it }

    /** The longest step a wake hands the core, in ms. */
    val maxStepMs: Float get() = fps?.let { maxOf(DEFAULT_MAX_STEP_MS, 2000f / it) } ?: DEFAULT_MAX_STEP_MS

    fun setRate(fps: Int?) {
        this.fps = fps
        budgetNanos = 0L
    }

    fun reset() {
        started = false
        budgetNanos = 0L
    }

    /** `true` when the vsync at [frameTimeNanos] wakes the scene. */
    fun admit(frameTimeNanos: Long): Boolean {
        val interval = intervalNanos
        if (interval == null || !started) {
            started = true
            lastNanos = frameTimeNanos
            budgetNanos = 0L
            return true
        }
        val frame = frameTimeNanos - lastNanos
        if (frame <= 0L) return false
        lastNanos = frameTimeNanos
        budgetNanos += frame
        if (budgetNanos < interval - frame / 2) return false
        budgetNanos = (budgetNanos - interval).coerceIn(-interval / 2, interval / 2)
        return true
    }

    companion object {
        /** Two 60 Hz frames (the loop's cap without a render rate). */
        const val DEFAULT_MAX_STEP_MS = 33.4f
    }
}
