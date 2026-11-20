# Changelog

## v0.19.0 — 2026-11-20

- Tride 0.19.0 writes the same format as 0.18 (0.78), and scenes saved by earlier Tride open unchanged. A scene where the author set a Noise's Compute to Per vertex or First octaves per vertex opens in the 0.18 editor without that look, with a warning; players 0.18 draw it correctly.
- Writing an input, firing a trigger or sending a touch from any thread no longer waits for a frame in progress: the write is checked at once (errors as before), read back at once, and taken by the next frame. The low-level core scene gains sealInputs(), which marks what the next frame takes when frames run on another thread.
- A Curl cloud no longer flickers when a value its paths are built from changes: the older paths stay on screen while the new ones build.
- A scene's background colour no longer turns white on devices that draw with Vulkan: the environment's colour cubes are filled in their own half-float format, as on iOS.
- Noise is computed faster: the core reads four lattice points at once with SIMD, natively and in WebAssembly, and gives the same values bit for bit.
- The screen's refresh rate now reaches Filament, so a view's `preferredFps` paces its frames by the same rule as on iOS. A view with no rate set draws as before.
- A hidden object that nothing else uses (as a cloner's source, for example) costs nothing while it is hidden: its mesh, bends, text, effectors and generators are not computed. Shown again, it looks exactly as if it had never been hidden.
- A playing scene now prepares its frames off the main thread, on one background thread the whole app shares: scrolling and your own animations no longer wait for a heavy scene. The picture is one frame behind a touch (17 ms at 60 frames a second); events still arrive on the main thread, in the frame that shows what they belong to. For this release, `TrideKitScene.defaultTickMode = TrideKitTickMode.MAIN_THREAD` brings back the old behaviour. A view's `preferredFps` now also sets the frame time Filament's dynamic resolution aims at (33 ms at 30 frames a second).
- An object whose material makes it fully transparent at that moment (faded to zero opacity, for example) is not drawn and costs no GPU time. A touch still picks it as before, and its children are drawn.
