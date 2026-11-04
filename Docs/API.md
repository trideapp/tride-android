# TrideKit for Android — API

A `.trd` scene is a glTF scene plus its logic: a state machine (layers, states, transitions), timelines,
bindings and events. The Tride runtime evaluates that logic on a fixed 60 Hz step. Your app does not
repeat it: it tells the scene facts and listens to what the scene answers. The API matches TrideKit on iOS.

## `TrideKitScene` — the scene (`studio.tride.kit`)

```kotlin
val scene = TrideKitScene(bytes)   // throws on a broken or newer file (see "Errors"); AutoCloseable
```

| | |
|---|---|
| `play()` / `pause()` / `isPlaying` | the scene's clock: while it runs, the view steps the scene on every display frame; paused, no steps |
| `tickOnce(dtMs)` | one step by hand — e.g. `tickOnce(0f)` while paused to apply new values |
| `availableBindings()` | what the scene declares: `TrideKitBinding(name, kind, options, computed)`; `kind` is `NUMBER`, `BOOL`, `ENUM`, `VEC3`, `QUAT`, `COLOR`, `TRIGGER`, `IMAGE` (or `OTHER` for a kind this TrideKit does not know); `options` are an enum's values; `computed` = the scene derives it from others |
| `validateBindings(names)` | check that the scene declares the names your app needs (`MissingBindingsException`) |
| `setBinding(name, value)` | write a value: `TrideValue.Number(…)`, `TrideValue.Bool(…)`, `TrideValue.String(…)` for an enum. A `VEC3`, `QUAT` or `COLOR` binding cannot be written by the host yet — `TrideValue.Vec3` is refused with `TrideException.InvalidBinding` |
| `getBinding(name)` | a binding's current value (`TrideValue`), a computed one as the scene derived it; an image input's status as `TrideValue.String` |
| `trigger(name)` | fire a trigger (a one-shot signal) |
| `setImage(name, …)`, `imageLoading(name)`, `clearImage(name)` | the app's picture in an image input — see "Pictures from the app" |
| `bindInput(input, source, mapping)`, `unbindInput(input)`, `boundInputs()` | bind a scroll source to one of the scene's number inputs, let go of it, list the bindings — see "The scene follows a scrolling container" |
| `scroll(offset, extent, sceneTop, sceneHeight)`, `dragAxes()` | report the scrolling container as it moves; ask which swipes the scene's own drags claim — same section |
| `onEvent { … }` | the scene's events (`TrideKitEvent`: `name`, `payload`); returns a `Subscription` (`cancel()`) |
| `onStateChange { … }` | state transitions per layer (`TrideKitStateChange`: `layerIndex`, `layerId`, `from`, `to`, `transition`) |
| `onDiagnostic { … }` | problems the scene meets (see "Errors") |
| `currentState()` | the current state of the first layer |
| `layerCount`, `layerId(i)`, `layerName(i)`, `currentState(layerId)` | every layer and its current state |
| `reset()` | back to the scene's start: every layer to its initial state (entered again), every binding to its declared default, every node to its authored pose — then one step `0` |
| `advance(scriptJson)` | run a host script (like "Fast-forward" in the editor) and stop at its end — see below |
| `readProperty(pointer)` | read a value from the scene's glTF document — see below |
| `close()` | release the runtime — after the scene's view is gone (see "Lifecycle") |
| `TrideKitScene.buildInfo` | which Tride the app carries: `version` (release), `commit`, `formatVersion` (the `.trd` format) — no scene needed; attach it to bug reports |

A written value reaches the scene on its next step. A name the scene does not declare, a value of the
wrong kind and a write to a `computed` binding throw — check the names your app needs once with
`validateBindings`.

`reset()` is the runtime's reset. A scene may also declare its own trigger named `reset`: that one is an ordinary trigger — fire it with
`trigger("reset")`.

Events, state changes and diagnostics arrive on the main thread after the frame they happened in has
reached the view.

### Reading: `readProperty`

`readProperty` reads the scene's glTF document — the one the runtime updates on every step — by JSON
pointer, and the scene's state under `/scene/…`. The answer is the value as JSON text, or `null` when
nothing is there:

```kotlin
scene.readProperty("/nodes/0/translation")   // "[0.0,0.0,0.0]"
scene.readProperty("/nodes/0/name")          // "\"Body\"" — the node's name
scene.readProperty("/node/$uid/rotation")    // the node's quaternion now; uid e.g. from pick()
scene.readProperty("/scene/active_camera")   // "\"camera\"" — the active camera
```

Bindings are read with `getBinding`, not `readProperty` (`/bindings/…` is always `null`).

### Fast-forward: `advance`

`scriptJson` is a JSON array of steps: `{"deltaMs": 16, "inputs": {"bindings": {…}, "triggers": […]}}`
(write, then step) or `{"until": "<event>", "maxMs": 5000}` (step at 60 Hz until the scene reports the
event). Events along the way arrive in `onEvent`. Returns `TrideKitAdvance.Completed(ticks)` when the
script ran to its end, or `TrideKitAdvance.Stopped(step, reason, ticks)` when it stopped early (e.g. an
`until` that never saw its event). Text that is not a host script stops at once:
`Stopped(0, "not a host script: …", 0)`. Only a failure of the runtime itself throws `TrideException`.

## `TrideSceneView` — the view (`studio.tride.kit.compose`)

A composable over `FilamentSurfaceView` (a `SurfaceView`) that draws the scene with Filament.

| | |
|---|---|
| `scene` | the scene; a new scene = a new view and a new Filament engine |
| `renderPolicy` | `ON_DEMAND` (default) — a frame is drawn only when a step changed something; `CONTINUOUS` — every display frame |
| `pixelScale` | drawable pixels per dp; below the screen's (e.g. `2f` on a 3× phone) is cheaper for the GPU; `null` — the screen's (also after another value) |
| `preferredFps` | frames drawn a second, e.g. `30` on a slow or hot phone; the scene's logic is woken no more often but still runs 60 steps a second (animations and timings stay the same). `null` (default) — every display frame; above the screen's rate — the screen's; 0 or less — the default and one diagnostic. Change it at any time: the scene is not reloaded (also `FilamentSurfaceView.preferredFps`) |
| `renderOverrides` | render switches over the scene's own settings, see "Render knobs"; `null` (default) — the scene's settings. Change it at any time: the scene is not reloaded (also `FilamentSurfaceView.renderOverrides`) |
| `backend` | `AUTO` — Vulkan on a device that declares Vulkan 1.1 (Android 10+), OpenGL ES on the emulator and elsewhere; `VULKAN`, `OPENGL` — explicitly |
| `onViewCreated` | the view exists — call `scene.play()` here; the `FilamentSurfaceView` is passed in |

### Render knobs

The scene's render settings are the designer's. The app may change four switches over them, for example
on a hot phone: `TrideRenderOverrides(msaa, msaaSamples, bloom, shadows, depthOfField)`, every field
`null` by default. MSAA goes both ways (`msaaSamples` 2, 4 or 8; another count keeps the scene's and
reports one diagnostic); bloom, shadows and depth of field can only be switched off (`true` keeps the
scene's). The scene's other settings (bloom strength, fog, tone mapping) stay as the designer set them,
and animated values go on. Set the knobs on the view (`renderOverrides`) or on the scene
(`scene.setRenderOverrides(knobs)`, read back with `scene.renderOverrides`); `null` returns the scene's
settings, and `scene.sceneRenderSettings` is what the scene itself sets. A change applies live, on the
same scene. The player never changes them on its own:

```kotlin
val power = context.getSystemService(PowerManager::class.java)
power.addThermalStatusListener(context.mainExecutor) { status ->
    val hot = status >= PowerManager.THERMAL_STATUS_SEVERE
    scene.setRenderOverrides(if (hot) TrideRenderOverrides(msaa = false, bloom = false) else null)
}
```

Pause and the GPU: a paused scene takes no steps; with `ON_DEMAND` the view then draws no new frames and
the GPU rests, with `CONTINUOUS` it redraws the same frame every display frame.

`FilamentSurfaceView.onFrameDrawn` reports a frame of the scene once the GPU driver has executed it — the
moment the scene is actually on screen. The view draws nothing before it has a scene, so the first report
(`1`) is the scene's first frame, also when the scene is set after the view is on screen (the usual async
load). That frame can take a while for a scene with many materials (its shaders compile); show your own
placeholder until it arrives. Counts may be skipped, but the newest frame is always reported in the end.
`backend` on the view says which backend is in use.

Without Compose: `FilamentSurfaceView(context, backend)` and `view.scene = scene`; `view.dispose()`
releases Filament — call it yourself when the view goes away. Another scene may be set on the same view
later (or `null`, then a scene): the old Filament engine is released and the new scene is drawn by a fresh
one on the same surface; the old scene is not closed. Frame counts go on across scenes. A closed scene or a
disposed view is refused with `IllegalStateException`, and the scene shown stays.

## Lifecycle

The view never closes the scene. Close it once the view is gone. Compose disposes a group's effects and
views in reverse order of declaration, so `DisposableEffect(scene)` goes **before** `TrideSceneView` —
then the view goes first and the effect closes the scene:

```kotlin
DisposableEffect(scene) { onDispose { scene.pause(); scene.close() } }
TrideSceneView(scene = scene, modifier = Modifier.fillMaxSize(), onViewCreated = { scene.play() })
```

- A scene whose view is gone stays alive: bindings, triggers and `advance` work, nothing is drawn until
  a new view shows the scene.
- The view steps and draws only while its window is shown. With the app in the background (the activity
  stopped) a playing scene takes no steps: its clock, timelines and triggers stand still, as on iOS. Back
  on screen it goes on from where it stood — the time away is not caught up, and a value written meanwhile
  lands on the first step. A view hidden with `GONE` inside a shown window still steps.
- After `close()` every call on the scene except `pause()` and another `close()` throws
  `IllegalStateException`.

## Camera and touch

The scene sets the camera (the active camera from the editor). The view's touches reach the scene's own
interactions by themselves (the editor's Interactions panel: a tap on a node, a drag that writes a
binding). By hand: `scene.pointer(TridePointerPhase.DOWN
/ MOVE / UP / CANCEL, x, y)` in dp from the view's top-left corner (a `MotionEvent`'s pixels divided by
`resources.displayMetrics.density`) — the unit the view reports its size in, and the points of iOS: a tap
is a press that travels at most 10 dp. The view follows one finger: the first one down.

`scene.pick(x, y)` (dp too) — the visible nodes with geometry under the point, nearest first (`TrideKitHit`: `node`
— the node's uid, `instanceKey` / `instanceIndex` for instances, `distance`). It answers only when at least
one of the scene's interactions targets a node; otherwise — and before the view has reported its size — the
list is empty. A scene whose one interaction is a drag over the whole frame, with no node, always answers
`pick` with an empty list.

## The scene follows a scrolling container

A scene can answer the container it sits in — a hero that speeds up while the page is flung, a plant that
grows as it comes into view — without knowing about scrolling containers: it declares a plain **number
input** (say `boost`), and the app binds what it measures to it. The scene file holds no scroll interaction.

| `TrideInputSource` | What the input reads | Default range |
|---|---|---|
| `SCROLL_SPEED` | how fast the container moves, up or down alike, in screen heights per second; back to 0 about a tenth of a second after it stops | 0…2 onto 0…1 |
| `SCROLL_POSITION` | the scene's pass through the container: 0 as its top comes in at the bottom, 1 as its bottom leaves past the top | 0…1 onto 0…1 |

```kotlin
// Once, after the scene is loaded: the input "boost" now follows the scroll speed.
scene.bindInput("boost", TrideInputSource.SCROLL_SPEED)
// A different range: the scene's pass from 20 % to 80 % drives "grow" from 0 to 1.
scene.bindInput("grow", TrideInputSource.SCROLL_POSITION, TrideInputMapping(0.2, 0.8, 0.0, 1.0))

// A NestedScrollView whose content holds the scene's view directly:
scroller.setOnScrollChangeListener { _, _, scrollY, _, _ ->
    scene.scroll(
        offset = scrollY.toDouble(),             // how far it is scrolled
        extent = scroller.height.toDouble(),     // its visible height
        sceneTop = sceneView.top.toDouble(),     // where the scene lies in the content
        sceneHeight = sceneView.height.toDouble(),
    )
}
```

| | |
|---|---|
| `bindInput(input, source, mapping)` | from the next step the scene writes the input from the source, mapped by `TrideInputMapping(fromLow, fromHigh, toLow, toHigh)` (clamped; `null` — the default range above). Binding an input again replaces its binding. Throws `TrideException.InvalidBinding` for an input the scene lacks, one that is not a number, one the scene computes or drives by its own pointer interactions, and an empty or non-finite range |
| `unbindInput(input)` | the app may write the input again; it keeps its value. `true` when it was bound |
| `boundInputs()` | the bindings, in the order they were made: `TrideBoundInput` — `input`, `source`, `mapping` |
| `scroll(offset, extent, sceneTop, sceneHeight)` | the container, the four numbers in one unit (pixels do), all from the start of its content: how far it is scrolled, its visible height, and where the scene's top and its height lie |
| `dragAxes()` | `TrideDragAxes` (`x`, `y`): the directions the scene's own drag interactions claim. `y` means it wants vertical swipes too, which a scroll container around it would otherwise take |

- **A bound input belongs to its source.** `setBinding` on it throws until `unbindInput`. `reset()` keeps
  the bindings; a new scene starts with none, so bind again after creating another scene.
- **Nothing observes your container for you.** Call `scroll` once after the scene is created and whenever
  any of its four numbers changes (a scroll, a rotation, a layout) while a scroll source is bound. TrideKit
  refuses a value that is not finite, an `extent` of 0 or less and a negative `sceneHeight` with a
  diagnostic (`RUNTIME`, subject `scroll`) and keeps the last report.
- **The speed is measured in time, not in frames.** It is the distance over the last tenth of a second,
  divided by `extent`, so the same scrolling reads the same on a 60 and a 120 Hz screen. After `pause()` and
  `play()` the measure starts afresh: what the container moved meanwhile is not a burst of speed.
- **The values are raw.** The scene's author smooths them with a Spring on the input's data binding.

## Sound the scene hears

A scene can move to the music your app plays: its Sound effectors and Sound fields, and any property it
binds to `audio.<input>.<probe>` (bloom strength, a scale, a colour), follow what the listener hears. The
app hands the sound over and the Tride runtime analyses it — the same analysis on every platform. No
microphone, no permission.

```kotlin
// A track: TrideKit decodes it in the background and plays it with Media3 ExoPlayer (input "music").
val track = scene.attachAudioTrack(context, Uri.parse("asset:///music/focus.mp3"), loop = true)
track.play()   // at once is fine: the scene hears the music as soon as decoding reaches the playback position
track.onAnalysed = { error -> error?.let { Log.w("app", "the track cannot be decoded", it) } }
track.volume = 0.8f   // the scene hears it as loud as the listener does

// Your own ExoPlayer, here a radio stream: build it with the tap in its audio sink.
val tap = TrideKitAudioTap()
val player = ExoPlayer.Builder(context, tap.renderersFactory(context)).build()
scene.attachAudioTap(tap, input = "radio")
player.setMediaItem(MediaItem.fromUri("https://radio.example/live"))
player.prepare(); player.play()

scene.detachAudio("radio")   // the scene stops hearing the radio; the player plays on
```

| | |
|---|---|
| `attachAudioTrack(context, uri, input, loop)` | a file the app knows whole (whatever the platform decodes: mp3, AAC, Ogg, WAV, FLAC); `uri` is `asset:///…`, `file://…`, `content://…`, `android.resource://…` or `https://…`. The scene hears all of it from the start, so seeking is free. Returns a `TrideKitAudioTrack` |
| `attachAudioTap(tap, input)` | the scene hears what the player carrying `tap` plays, an endless stream such as internet radio included; the app keeps and drives the player |
| `detachAudio(input)` | the scene stops hearing `input` (its probes read 0); a track attached there stops and is released, a player plays on |
| `audioInputs` | the inputs the scene hears now |
| `TrideKitAudioTrack` | `play()`, `pause()`, `seekTo(ms)`, `positionMs`, `durationMs`, `isPlaying`, `loop`, `volume` (0…1), `muted`; `isAnalysed` — the scene has the whole file; `onAnalysed` — called once it does (`null`) or with the error when the file cannot be decoded |
| `TrideKitAudioTap` | `renderersFactory(context)` — a `RenderersFactory` whose audio sink carries the tap; `audioSink(DefaultAudioSink.Builder(context), myProcessor)` — the sink to return from your own `RenderersFactory.buildAudioSink` when the player has audio processors of its own |

- **Inputs.** `input` is the name the scene's probes listen to: `audio.music.kick` is probe `kick` of input
  `music`, the default (`TrideKitScene.DEFAULT_AUDIO_INPUT`). A scene may listen to several inputs; a sound
  attached to a busy input replaces the one there. A name is lowercase letters, digits and `_`, starting
  with a letter; another throws `IllegalArgumentException`.
- **In time with the speaker.** Before every step the scene hears the moment the listener hears: the
  player's position, which Android reports at the output (the output latency taken off). Seeks, pauses and
  new items stay in step.
- **Volume.** The scene hears what the listener hears: the volume scales its reaction, and a muted, paused,
  buffering or ended player is silence.
- **A track is decoded whole** into the scene (about 3.7 MB of analysis per stereo minute, not the
  samples), from its start, in the background. `play()` at once is fine, from any position: until decoding
  reaches the playback position the scene hears silence there (a 4-minute mp3 takes a second or two).
  `onAnalysed` says when the scene has all of it. At most two channels (left, right) are heard.
- **Your own player.** TrideKit brings `androidx.media3:media3-exoplayer` with it. `renderersFactory` and
  `audioSink` are Media3 unstable API (`@OptIn(UnstableApi::class)`); processors before the tap must not
  change the timing. One tap per player.
- **Audio focus.** A track TrideKit plays asks for audio focus and pauses when headphones are unplugged,
  like any music player; your own player behaves as you build it.
- **Threads and problems.** Attach, detach and drive tracks from the main thread; decoding and the tap work
  on their own threads. Problems arrive in `onDiagnostic` with the subject `audio.<input>`: `LOAD` for a
  file the platform cannot decode or a format the tap cannot read, `RUNTIME` for the rest.

## Pictures from the app

A scene can show your app's picture — the user's photo, an avatar, a cover — in the places its author
marked. In Tride Editor the author adds an **image input** (a binding of kind `IMAGE`, e.g. `photo`) and
binds it to the picture of an Image Texture; the picture standing there in the editor is the
**placeholder**. The scene shows the placeholder until the app hands a picture over, and again when the
picture fails. The picture is the app's: it is never written into a `.trd`, and it stays through `reset()`.

```kotlin
// A photo the user picked, from a coroutine on the main thread (lifecycleScope, viewModelScope).
val pick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri == null) return@registerForActivityResult
    lifecycleScope.launch {
        when (val r = scene.setImage("photo", this@MainActivity, uri)) {
            is TrideImageResult.Loaded -> Log.i("app", "photo ${r.width}x${r.height}, revision ${r.revision}")
            is TrideImageResult.Failed -> Log.w("app", "not shown: ${r.reason}")   // the placeholder is back
            TrideImageResult.Superseded -> Unit   // a later call for "photo" came first
        }
    }
}

// A picture the app downloads itself (in a coroutine): say "loading" first, then hand the bytes over.
scene.imageLoading("photo")
val bytes = withContext(Dispatchers.IO) { URL(photoUrl).readBytes() }
scene.setImage("photo", bytes)

// What the scene shows, on every change of the input.
scene.onEvent { e ->
    if (e.name == "image.photo") {
        val status = (e.payload["status"] as? TrideValue.String)?.value   // empty, loading, loaded, failed
        spinner.isVisible = status == "loading"
    }
}

scene.clearImage("photo")   // no picture: the placeholder shows
```

| | |
|---|---|
| `setImage(name, bytes)` | JPEG, PNG or WebP bytes (HEIF from Android 8). The input turns `loading` at once, the bytes are decoded off the main thread, then `Loaded(revision, width, height)` (the next step shows the picture), `Failed(reason)` (the input turns `failed`, the placeholder shows) or `Superseded` (see below). A `suspend` function; its scene calls run on the main thread whatever the caller's |
| `setImage(name, context, uri)` | the same from a `content://` (the photo picker, MediaStore), `file://` or `android.resource://` uri; a uri that cannot be opened is `Failed` |
| `setImage(name, bitmap)` | the same from your own `Bitmap`, already upright; do not recycle it before the call returns |
| `imageLoading(name)` | a picture is on its way and the app has no bytes yet (its own download): the scene sees `loading`, the picture shown stays |
| `clearImage(name)` | no picture: the placeholder shows, the pixels are let go |

- **Four states.** The scene reads the input's status — `empty`, `loading`, `loaded`, `failed` — in its
  state machine (a transition on `bindings.photo == 'loaded'`, say), and `getBinding("photo")` returns
  it. Every change comes to `onEvent` as the event `image.<input>` with `status`, `revision`, `width`,
  `height` (the picture shown; `0 × 0` when none) and, for a failure, `reason`; a failure also comes to
  `onDiagnostic` once (`LOAD`, subject `image.<input>`).
- **When it shows.** The scene lays the picture in on its next step; a paused scene shows it after its
  next `tickOnce`.
- **The newest call wins.** A decode that a later `setImage` / `imageLoading` / `clearImage` of the same
  input overtook ends `Superseded`: the scene never saw that picture, there is nothing to show.
- **No flash on a replacement.** While the next picture loads, the previous one stays on screen.
- **Fill or fit is the author's.** The Image Texture frames the picture as set in the editor: `stretch`
  (the default), `fill` — the placeholder's proportions, filled whole, the picture cropped around its
  centre — or `fit` — the whole picture, the margins in the Image Texture's edge mode (black, say). The
  app passes a picture of any size and proportions.
- **Size.** TrideKit turns the photo upright by its EXIF orientation and scales it down to 2048 px on the
  long side, the proportions kept; colours are sRGB (a Display P3 photo is converted).
- **One picture, several places.** An input bound to several fields is decoded and uploaded once.
- **Memory.** The decoded picture is kept while the scene shows it (a new view of the same scene starts
  with it) and let go once a newer picture replaced it or the input was cleared or failed.

## Threads

- **The main thread's:** everything that steps, draws, touches, subscribes or closes — `play`, `pause`,
  `tickOnce`, `reset`, `advance`, `close`, `onEvent` / `onStateChange` / `onDiagnostic` and their
  `cancel()`, `pointer`, `pick`, `scroll`, `imageLoading`, `clearImage`, the audio
  calls, and the view. From another thread such a call throws `IllegalStateException` naming the thread,
  at the call. `onEvent`, `onStateChange` and `onDiagnostic` are called on the main thread too.
- **Any thread:** the `TrideKitScene(bytes)` constructor (reading the file is real work — e.g.
  `withContext(Dispatchers.Default) { TrideKitScene(bytes) }`) and the calls that only talk to the runtime:
  `setBinding`, `getBinding`, `trigger`, `setInputs`, `inputsJson`, `readProperty`, `currentState`,
  `layerCount` / `layerId` / `layerName`, `availableBindings`, `validateBindings`, `bindInput` / `unbindInput` / `boundInputs`,
  `dragAxes`. The runtime locks itself; a value written from another thread lands on one of the next steps,
  and the state change it causes still arrives on the main thread. Write from the main thread when the
  exact frame matters.
- `setImage` is a `suspend` function: call it from any coroutine, its scene calls run on the main thread.

## Errors

These throw:

| Call | Exception | When |
|---|---|---|
| `TrideKitScene(bytes)` | `TrideException.LoadFailed` | not a `.trd`; a file that unpacks to more than 1 GiB; a broken manifest or schema; damaged 3D content (an index pointing nowhere, data past the end of its buffer, an index past its mesh's vertices — what the renderer would refuse); the scene's logic does not compile (a formula, the state machine, the bindings graph). The message says which |
| | `TrideException.NewerFormat` | the scene comes from a newer editor than the TrideKit in the app |
| | `IllegalStateException` | the device's ABI has no runtime build (a 32-bit phone); the message names the device's ABIs and the supported `arm64-v8a`, `x86_64` |
| `setBinding`, `getBinding` | `TrideException.InvalidBinding` | the name is not declared, the value is of the wrong kind (a `TrideValue.Vec3` always: the host cannot write a vector yet), a value not among an enum's `options`, a write to a `computed` binding or to an input bound to a scroll source |
| `trigger` | `TrideException.InvalidTrigger` | the name is not declared or is not a trigger |
| `advance` | `TrideException` | the runtime itself failed (not a bad script — that is `Stopped(0, …)`) |
| `validateBindings` | `MissingBindingsException` | `missing` — what is absent, `declared` — what there is |
| `attachAudioTrack`, `attachAudioTap` | `IllegalArgumentException` | `input` is not a scene input name (lowercase letters, digits and `_`, starting with a letter) |
| `setImage`, `imageLoading`, `clearImage` | `TrideException.InvalidImage` | the name is not an image input (a picture that does not decode is `Failed`, not an exception) |
| `bindInput` | `TrideException.InvalidBinding` | the scene lacks the input, it is not a number, the scene computes it or drives it with its own pointer interactions, or the range is empty or not finite |
| any after `close()` (except `pause` / `close`) | `IllegalStateException` | the scene is closed |
| a main-thread call (see "Threads") from another thread | `IllegalStateException` | the message names the thread |
| `view.scene = scene` | `IllegalStateException` | the scene is closed or the view is disposed; the scene shown stays |
| | `IllegalStateException` | `scene not built: …` — the runtime could not describe the scene to draw; nothing is shown, the scene itself stays usable |

`TrideException.Internal` is an internal runtime failure; it does not occur in normal work.

Everything else the scene meets is a `TrideKitDiagnostic(kind, subject, message)` in `onDiagnostic` (and
a line in logcat, tags `tride.kit`, `tride.eval`), each one once, while the scene carries on:

| `kind` | What | `subject` |
|---|---|---|
| `EVALUATION` | an expression or binding the scene could not evaluate | its address |
| `LOAD` | an environment, material package, mesh or texture that could not be loaded — the built-in look is used instead | the asset |
| `POINTER` | a change the scene made that this TrideKit cannot show (a light, camera, morph target or material it does not support) | its address |
| `RUNTIME` | a step, touch or view-size call that failed | the call |
| `RUNTIME` | the scene is drawn only in part (a kind of content or a material value this TrideKit does not draw yet); the same text is in `scene.partialDrawNotice` | `partialDraw` |

`readProperty` answers `null` and `pick` an empty list when there is nothing to report. TrideKit's debug
lines are off by default; turn them on with `adb shell setprop log.tag.tride.kit DEBUG`.
