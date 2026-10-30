/**
 * The one finger a scene hears out of Android's multi-touch stream: the first finger down owns the
 * gesture, followed by its pointer id (indices shift as other fingers come and go); when it lifts while
 * others stay down the gesture ends there, and the rest are ignored until all are up.
 *
 * A lift is UP then CANCEL: the tap or the drag ends first (a tap still clicks), then the scene hears
 * the finger leave — a finger does not hover (W3C Pointer Events), so its pointer-exit listeners run and
 * the scene's own rule decides what a follow does next. A drag is unchanged: a CANCEL after the release
 * throws nothing. The same rule as TrideKit for iOS and a browser's touch.
 */
package studio.tride.kit

import android.view.MotionEvent
import studio.tride.core.TridePointerPhase

internal class OneFinger {
    class Step(val phase: TridePointerPhase, val x: Float, val y: Float)

    private var id = MotionEvent.INVALID_POINTER_ID
    val isDown: Boolean get() = id != MotionEvent.INVALID_POINTER_ID

    /** Forget the finger (its scene was replaced; the old one got its CANCEL). */
    fun reset() { id = MotionEvent.INVALID_POINTER_ID }

    /** The scene's steps for [event], in order; empty when the event is not the tracked finger's. */
    fun feed(event: MotionEvent): List<Step> {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                id = event.getPointerId(0)
                return listOf(Step(TridePointerPhase.DOWN, event.getX(0), event.getY(0)))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = indexOrNull(event) ?: return emptyList()
                return listOf(Step(TridePointerPhase.MOVE, event.getX(i), event.getY(i)))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != id) return emptyList()
                return end(event, lifted = true)
            }
            MotionEvent.ACTION_UP -> return end(event, lifted = true)
            MotionEvent.ACTION_CANCEL -> return end(event, lifted = false)
        }
        return emptyList()
    }

    private fun end(event: MotionEvent, lifted: Boolean): List<Step> {
        val i = indexOrNull(event) ?: return emptyList()
        id = MotionEvent.INVALID_POINTER_ID
        val x = event.getX(i)
        val y = event.getY(i)
        val cancel = Step(TridePointerPhase.CANCEL, x, y)
        return if (lifted) listOf(Step(TridePointerPhase.UP, x, y), cancel) else listOf(cancel)
    }

    private fun indexOrNull(event: MotionEvent): Int? =
        if (!isDown) null else event.findPointerIndex(id).takeIf { it >= 0 }
}
