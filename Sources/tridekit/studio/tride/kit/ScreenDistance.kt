/**
 * `ScreenDistance` — how far a scene view stands from the screen, which decides what it draws and what GPU memory it
 * keeps. Decided from the view's geometry alone, as the web's `IntersectionObserver` margin or a RecyclerView's
 * prefetch decide: the kit knows nothing of feeds or pages (the iOS bridge takes the same rule).
 *
 * Public API (internal):
 *   - `ON_SCREEN` — draws; `NEAR` — draws until its first frame on its swap chain, then stands and keeps everything;
 *     `FAR` — gives back its swap chain, Renderer and pixelation targets (a TextureView or SurfaceView keeps showing
 *     the last frame it was handed).
 *   - `ScreenDistance.next(previous, visible, gapX, gapY, screenWidth, screenHeight)` — the distance now from the one
 *     before (`null` = none yet) and the gaps in px between the view and the window on each axis (0 = touching or
 *     overlapping).
 *
 * Non-obvious constraints:
 *   - On screen = shown with a pixel left after its parents' clips (`isShown`, `getGlobalVisibleRect`): a carousel
 *     narrower than the screen or a nested scroll hides a view the window still holds; such a view is near.
 *   - Near = closer than one screen to the window's edge on both axes (a feed's next page, one screen aside). A view
 *     goes far only once it stands [FAR_SCREENS] screens away: one on the boundary keeps its swap chain instead of
 *     making and dropping it every frame. The first decision takes the near rule alone.
 *   - An empty window (before the first layout) counts as on screen: the view behaves as it always did.
 */
package studio.tride.kit

internal enum class ScreenDistance {
    ON_SCREEN,
    NEAR,
    FAR;

    companion object {
        /** Closer than this many screens to the window's edge, a view is near. */
        const val NEAR_SCREENS = 1.0
        /** A near view goes far from this many screens away (the hysteresis band lies between the two). */
        const val FAR_SCREENS = 1.25

        fun next(previous: ScreenDistance?, visible: Boolean, gapX: Int, gapY: Int, screenWidth: Int, screenHeight: Int): ScreenDistance {
            if (screenWidth <= 0 || screenHeight <= 0 || visible) return ON_SCREEN
            if (gapX < screenWidth * NEAR_SCREENS && gapY < screenHeight * NEAR_SCREENS) return NEAR
            val beyond = gapX >= screenWidth * FAR_SCREENS || gapY >= screenHeight * FAR_SCREENS
            return if (beyond || previous == null || previous == FAR) FAR else NEAR
        }

        /** The gap in px between the span [start, end) and the window's [0, extent) on one axis; 0 when they meet. */
        fun gap(start: Int, end: Int, extent: Int): Int = maxOf(0, -end, start - extent)
    }
}
