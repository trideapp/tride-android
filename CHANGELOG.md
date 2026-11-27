# Changelog

## v0.20.0 — 2026-11-27

- TrideKit is quiet by default: `TrideKitLog.level` (`OFF`, `ERROR`, `WARNING` by default, `INFO`, `DEBUG`) sets how much it writes to Logcat, `TrideKitLog.handler` takes the lines instead, and `adb shell setprop log.tag.tride DEBUG` raises the level without changing code. A playing scene writes no line at any level (the pixelation and per-step lines are gone). The runtime's notices are written once, under the tag `tride`, and reach `onDiagnostic` with code `core.notice`.
- All scene views in an app share one Filament engine, and a view draws only while it is on screen: on a Galaxy S21 six full-screen views take about 240 MB instead of 580 MB, and a second view shows its first full frame in about 0.1 s instead of 0.25 s. A view far from the screen gives back its GPU memory and keeps its last frame; when Android asks for memory, hidden views give theirs back, and in the background views out of a window unload their scene and the unused engine goes. Removing a `TextureView` (the default) no longer waits for the GPU; the API is unchanged.
- A paused scene in a view with `renderPolicy = CONTINUOUS` no longer draws the same picture again on every screen frame: it draws only when something changes, as under the default `ON_DEMAND`. A playing scene, or a view with temporal anti-aliasing on, still draws every frame.
