/**
 * The window size the view's swap chain was made for, and when a resize needs the swap chain made again.
 *
 * Internal API:
 *   - `made()` — a swap chain was just made on the window; the next `resized` is the size it saw.
 *   - `gone()` — the window left (no swap chain); resizes are not followed until the next `made`.
 *   - `resized(width, height)` — the window is now that size: `true` when the swap chain was made for
 *     another size and must be made again on the same window.
 *
 * Non-obvious constraints:
 *   - Why again: on Android, Vulkan reads the window's size (`vkGetPhysicalDeviceSurfaceCapabilitiesKHR`
 *     → `NATIVE_WINDOW_DEFAULT_WIDTH`) from what the app's `Surface` cached at its last connect or
 *     `queueBuffer`, not from the resized window; Filament's driver recreates its swap chain from that
 *     cache, so after a turn it took the size before the turn. A swap chain made anew connects to the
 *     window afresh and reads the new size (AOSP `Surface::connect`). Filament's `UiHelper` does the same
 *     for a resized `TextureView`.
 *   - Same size again (a format change, a 180° turn) keeps the swap chain.
 */
package studio.tride.kit

internal class SwapChainSize {
    private var shown = false
    private var width = 0
    private var height = 0

    fun made() {
        shown = true
        width = 0
        height = 0
    }

    fun gone() {
        shown = false
        width = 0
        height = 0
    }

    fun resized(width: Int, height: Int): Boolean {
        if (!shown) return false
        val stale = this.width > 0 && (width != this.width || height != this.height)
        this.width = width
        this.height = height
        return stale
    }
}
