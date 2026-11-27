/**
 * How much TrideKit writes to the log, and where: one level and one handler per process.
 *
 * Public API:
 *   - `TrideKitLog.level` (`TrideKitLogLevel`: `OFF`, `ERROR`, `WARNING`, `INFO`, `DEBUG`; default `WARNING`) — set
 *     from any thread at any time. `adb shell setprop log.tag.tride DEBUG` wins over the code while it is set: a
 *     switch a developer flips without editing the app (read once per process; the property lives until the
 *     device restarts).
 *   - `TrideKitLog.handler` — `(TrideKitLogEntry) -> Unit` gets every line that passes the level instead of
 *     Logcat; `null` = Logcat (the entry's category is the tag: `tride.kit`, `tride.bridge`, `tride.gen`,
 *     `tride.view`, `tride.eval`; the core's notices are category `core`, tag `tride`).
 *
 * Non-obvious constraints:
 *   - `TrideLog` (internal) is the only way TrideKit writes: inline lambdas, the text built only when the level
 *     passes. No other source of the library calls `android.util.Log` (`TrideKitLogTest` scans them).
 *   - The handler runs on the writing thread (main, the preparation thread, a decoder), never under the
 *     runtime's lock and never from an audio callback; it must be quick and must not call the scene.
 *   - The scene's tick writes no line at any level. The core's notices are written as `WARNING`, category
 *     `core`, once each, and reach `onDiagnostic` whatever the level (`CoreNotices`).
 */
package studio.tride.kit

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** How much TrideKit writes: each level includes the ones before it. */
public enum class TrideKitLogLevel {
    /** Nothing. */
    OFF,

    /** What was asked could not be done: a scene that did not load, no engine. */
    ERROR,

    /** Degraded output, the scenes' diagnostics, the core's notices. The default. */
    WARNING,

    /** A line per scene event: loaded, engine made, camera switched. */
    INFO,

    /** Details of a load: lights, module meshes, render settings. */
    DEBUG,
}

/** One line TrideKit writes: its level, its category (the Logcat tag, or `core`) and its text. */
public data class TrideKitLogEntry(val level: TrideKitLogLevel, val category: String, val message: String)

/** TrideKit's log settings for the process. */
public object TrideKitLog {
    @Volatile private var chosen: TrideKitLogLevel? = null

    /** `setprop log.tag.tride DEBUG` pins `DEBUG` over the code; read on the first line, once. */
    private val fromProperty: TrideKitLogLevel? by lazy {
        val debug = try {
            Log.isLoggable(CORE_TAG, Log.DEBUG)
        } catch (_: RuntimeException) {
            false // a JVM without Android's Log (unit tests)
        }
        if (debug) TrideKitLogLevel.DEBUG else null
    }

    /** How much TrideKit writes: `DEBUG` while `setprop log.tag.tride DEBUG` is set, else what the code set, else `WARNING`. Any thread. */
    @JvmStatic
    public var level: TrideKitLogLevel
        get() = fromProperty ?: chosen ?: TrideKitLogLevel.WARNING
        set(value) { chosen = value }

    /**
     * Where the lines that pass [level] go; `null` = Logcat. Called on the writing thread: keep it quick and do
     * not call the scene from it. Any thread.
     */
    @JvmStatic
    @Volatile
    public var handler: ((TrideKitLogEntry) -> Unit)? = null

    internal const val CORE_TAG: String = "tride"
}

/** TrideKit's lines, through one writer: the text is built only when the level passes. */
internal object TrideLog {
    const val KIT = "tride.kit"
    const val BRIDGE = "tride.bridge"
    const val GEN = "tride.gen"
    const val VIEW = "tride.view"
    const val EVAL = "tride.eval"
    const val CORE = "core"

    private val once: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun enabled(level: TrideKitLogLevel): Boolean =
        level != TrideKitLogLevel.OFF && level.ordinal <= TrideKitLog.level.ordinal

    inline fun error(category: String, message: () -> String) {
        if (enabled(TrideKitLogLevel.ERROR)) write(TrideKitLogLevel.ERROR, category, message())
    }

    inline fun warning(category: String, message: () -> String) {
        if (enabled(TrideKitLogLevel.WARNING)) write(TrideKitLogLevel.WARNING, category, message())
    }

    inline fun info(category: String, message: () -> String) {
        if (enabled(TrideKitLogLevel.INFO)) write(TrideKitLogLevel.INFO, category, message())
    }

    inline fun debug(category: String, message: () -> String) {
        if (enabled(TrideKitLogLevel.DEBUG)) write(TrideKitLogLevel.DEBUG, category, message())
    }

    /** A warning once per process for each distinct text: a line on a path a tick may take again. */
    inline fun warningOnce(category: String, message: () -> String) {
        if (enabled(TrideKitLogLevel.WARNING)) writeOnce(TrideKitLogLevel.WARNING, category, message())
    }

    fun writeOnce(level: TrideKitLogLevel, category: String, message: String) {
        if (once.add("$category\u0000$message")) write(level, category, message)
    }

    fun write(level: TrideKitLogLevel, category: String, message: String) {
        val handler = TrideKitLog.handler
        if (handler != null) { handler(TrideKitLogEntry(level, category, message)); return }
        val priority = when (level) {
            TrideKitLogLevel.OFF -> return
            TrideKitLogLevel.ERROR -> Log.ERROR
            TrideKitLogLevel.WARNING -> Log.WARN
            TrideKitLogLevel.INFO -> Log.INFO
            TrideKitLogLevel.DEBUG -> Log.DEBUG
        }
        Log.println(priority, if (category == CORE) TrideKitLog.CORE_TAG else category, message)
    }
}
