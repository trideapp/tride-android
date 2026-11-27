/**
 * The core's degrade notices in TrideKit: pulled from the runtime, written once to the log and
 * handed to every open scene's `onDiagnostic`.
 *
 * Internal API:
 *   - `CoreNotices.pull()` — take the runtime's new notices (`takeCoreNotices`), keep them in the process list
 *     and write each to the log (`WARNING`, category `core`, Logcat tag `tride`); any thread.
 *   - `CoreNotices.since(cursor)` — the process list from a scene's cursor on, and the cursor after it.
 *   - `CoreNotices.diagnostic(message)` — a notice as a scene diagnostic (`RUNTIME`, code `core.notice`).
 *   - `CoreNotices.open(scene)` — a scene alive now (held weakly): new notices reach it on the main thread.
 *
 * Non-obvious constraints:
 *   - Pull, never push: the runtime queues notices (it may raise one under a scene's lock) and the bridge takes
 *     them before and after each load and after each tick, on the main thread, outside any runtime call. The
 *     first pull happens before the first scene loads, so from then on the runtime writes no notice itself.
 *   - The core raises each notice once per process: one missed by the log at `OFF` is not written again later;
 *     the diagnostics get every one, and a scene opened later gets the list so far.
 *   - `source` is the runtime's queue; the JVM tests stand a list in for it (no native runtime there).
 */
package studio.tride.kit

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

internal object CoreNotices {
    const val CODE: String = "core.notice"

    /** The runtime's queue of notices since its last take. */
    @Volatile
    var source: () -> List<String> = { studio.tride.core.takeCoreNotices() }

    private val lock = Any()
    private val all = ArrayList<String>()
    private val scenes = ArrayList<WeakReference<TrideKitScene>>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The runtime's notices since the last pull, now in the process list and the log. When there are new ones,
     * every open scene hands them to its diagnostics on the main thread soon after (the scene that pulled after
     * its tick does so at once).
     */
    fun pull(): List<String> {
        val fresh = synchronized(lock) { source().also { all += it } }
        if (fresh.isEmpty()) return fresh
        for (message in fresh) TrideLog.warning(TrideLog.CORE) { message }
        val open = openScenes()
        if (open.isNotEmpty()) main.post { for (scene in open) scene.deliverCoreNotices() }
        return fresh
    }

    /** The process's notices from index [cursor] on, and the cursor after them. */
    fun since(cursor: Int): Pair<List<String>, Int> = synchronized(lock) {
        val from = cursor.coerceIn(0, all.size)
        all.subList(from, all.size).toList() to all.size
    }

    fun diagnostic(message: String): TrideKitDiagnostic =
        TrideKitDiagnostic(TrideKitDiagnosticKind.RUNTIME, "core", message, CODE)

    /** [scene] is open: it gets the notices pulled from now on (and the list so far, by its cursor). */
    fun open(scene: TrideKitScene) = synchronized(lock) {
        scenes.removeAll { it.get() == null }
        scenes += WeakReference(scene)
    }

    private fun openScenes(): List<TrideKitScene> = synchronized(lock) { scenes.mapNotNull { it.get() } }

    /** Forget the process list and the scenes (JVM tests only). */
    fun resetForTests() = synchronized(lock) { all.clear(); scenes.clear() }
}
