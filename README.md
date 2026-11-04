# TrideKit for Android — Tride 0.14.0

Plays Tride scenes (`.trd`) in an Android app: the Tride runtime, Filament rendering (Vulkan on a phone,
OpenGL ES on the emulator) and a Compose view over them. A scene is built in Tride Editor ([tride-editor](https://github.com/trideapp/tride-editor)) and exported
as `.trd`; the app only writes values into it (bindings) and fires triggers, and the scene decides what to
show. It is the same scene and the same runtime as on the iPhone and in the browser.

This repository is built from the Tride source repository: one commit is one release; changes are not
accepted here — questions and bugs go to Issues. Licence — `LICENSE` (Tride Players Licence 1.1); third-party
components and their licences — `THIRD_PARTY_NOTICES.md`; which build the release comes from — `SNAPSHOT.md`;
what is new — `CHANGELOG.md`.

## What is inside

| | |
|---|---|
| GitHub Packages | `studio.tride:tride-core`, `tridekit`, `tridekit-compose` 0.14.0 and the Filament they are built on, `studio.tride.filament:filament-android`, `gltfio-android`, `filament-utils-android` — this repository's Maven packages |
| Releases | `TridePlayer.apk` — the example player, ready to install |
| `maven/` | the same packages as a folder — a Maven repository for building without a token |
| `Sources/` | the libraries' Kotlin sources: `tridekit` (the Filament bridge, `TrideKitScene`), `tridekit-compose` (`TrideSceneView`), `tride-core` (the uniffi binding over the prebuilt runtime `.so`). They also sit beside each AAR as a `-sources.jar` — Android Studio shows them when you jump into the library's code |
| `Examples/TridePlayer` | the example's source — a Gradle project that takes TrideKit from `maven/` |
| `Docs/` | [API](Docs/API.md) |

## Installing the APK

`TridePlayer.apk` is under Releases. A phone with Android 7.0+ (arm64) or an x86_64 / arm64 emulator:
`adb install -r TridePlayer.apk`, or copy the file to the phone and open it. The APK is signed with a debug
key: it will not install over a version from another source — remove the old one first.

TridePlayer: "Open .trd…" at the top opens a scene exported from Tride Editor (a file on the phone). Touches
reach the scene's own interactions. "Bindings" lists every value and trigger
of the scene with its current value.

## Your own app

Requirements: Kotlin 2.4, `compileSdk 37`, `minSdk 24`; ABIs `arm64-v8a` and `x86_64`. Filament comes
from the TrideKit repository with TrideKit (`studio.tride.filament`, Tride's build of Filament); JNA and
the rest come from Google and Maven Central by themselves.

- The example builds with Android Gradle Plugin 8.11.1 + Gradle 8.14.3 + JDK 22 (Gradle 8.14 runs on JDK
  17–24). AGP 8.11 then warns that it was tested up to `compileSdk 36` and, with R8 (`isMinifyEnabled =
  true`), that its R8 cannot read Kotlin 2.4 metadata. The build still passes; for a release with R8 use a
  newer AGP.
- Without those warnings, with R8, it is verified on AGP 9.2.1 + Gradle 9.7.0 (Kotlin is built into AGP 9 —
  the `org.jetbrains.kotlin.android` plugin is not needed, `org.jetbrains.kotlin.plugin.compose` stays).
- `abiFilters` is required (step 2): the Tride runtime is built only for `arm64-v8a` and `x86_64`, while
  Filament and JNA also bring 32-bit libraries. Without the filter the APK installs on a 32-bit phone, and
  there `TrideKitScene(…)` throws an `IllegalStateException` naming the ABIs; with the filter such a phone
  simply does not install the app. On 64-bit devices the filter also drops the unused 32-bit libraries.

1. Add the TrideKit repository in `settings.gradle.kts`. The packages are on GitHub Packages
   (`trideapp/tride-android`): you need a GitHub token (classic) with `read:packages`, and in
   `~/.gradle/gradle.properties` — `gprUsername=<login>` and `gprPassword=<token>`:

```kotlin
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "gpr"   // credentials from the gprUsername / gprPassword properties
                    url = uri("https://maven.pkg.github.com/trideapp/tride-android")
                    credentials(PasswordCredentials::class)
                }
            }
            filter {
                includeGroup("studio.tride")             // TrideKit
                includeGroup("studio.tride.filament")    // the Filament TrideKit is built on
            }
        }
        google()
        mavenCentral()
    }
}
```

   Without a token the project still configures; the error comes only when Gradle fetches the
   `studio.tride` and `studio.tride.filament` packages, and it names the missing properties (`The
   following Gradle properties are missing for 'gpr' credentials: gprUsername, gprPassword`).

   Without a token — the `maven/` folder of this repository: copy it into the project (e.g. as
   `tride-maven`) and use it instead of GitHub Packages:

```kotlin
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(file("tride-maven")) } }
            filter {
                includeGroup("studio.tride")             // TrideKit
                includeGroup("studio.tride.filament")    // the Filament TrideKit is built on
            }
        }
        google()
        mavenCentral()
    }
}
```

2. The app module's dependency (the Compose view brings `tridekit` and `tride-core` with it):

```kotlin
android { defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } } }   // required, see the requirements
dependencies { implementation("studio.tride:tridekit-compose:0.14.0") }
```

3. Put a `.trd` exported from Tride Editor into `src/main/assets/` and show it:

```kotlin
@Composable
fun SceneView() {
    val context = LocalContext.current
    val scene = remember { TrideKitScene(context.assets.open("scene.trd").use { it.readBytes() }) }
    // Before the view: Compose disposes in reverse order — the view goes first, then the scene closes.
    DisposableEffect(scene) { onDispose { scene.pause(); scene.close() } }
    TrideSceneView(scene = scene, modifier = Modifier.fillMaxSize(), pixelScale = 2f, onViewCreated = { scene.play() })
}
```

Then `scene.setBinding("<name>", TrideValue.Number(value))` for the scene's inputs, `scene.trigger("<name>")`
for its triggers, `scene.onEvent { … }` for the scene's events and `scene.onDiagnostic { … }` for problems it
meets. The whole API is in [Docs/API.md](Docs/API.md). Which Tride the app carries —
`TrideKitScene.buildInfo` (release, commit, `.trd` format); attach it to bug reports.

## Sound the scene hears

A scene can move to the music your app plays: its Sound effectors and Sound fields, and any property it
binds to `audio.<input>.<probe>` (bloom strength, a scale, a colour), follow the sound. No microphone, no
permission. `attachAudioTrack(context, uri)` plays a file the scene hears; `attachAudioTap(tap, input)` lets
it hear your own ExoPlayer, built with the tap in its audio sink, internet radio included;
`detachAudio(input)` stops. See [Docs/API.md](Docs/API.md).

```kotlin
val track = scene.attachAudioTrack(context, Uri.parse("asset:///music/focus.mp3"))   // input "music"
track.play()   // at once: the scene hears the music as soon as decoding reaches the playback position
```

## Pictures from the app

A scene can show your app's picture — the user's photo, an avatar, a cover — in the places its author
marked. In Tride Editor the author adds an **image input** (a binding of kind `image`, e.g. `photo`) and
binds it to the picture of an Image Texture; the picture already standing there is the **placeholder**.
The scene shows the placeholder until the app hands a picture over, and again when the picture fails. The
picture is the app's: it is never written into a `.trd`.

```kotlin
// From a coroutine (lifecycleScope, viewModelScope); the photo picker gives a content:// uri.
scene.imageLoading("photo")                         // the scene sees "loading"; the picture shown stays
when (val r = scene.setImage("photo", context, uri)) {
    is TrideImageResult.Loaded -> Log.i("app", "photo ${r.width}x${r.height}")   // from the next step
    is TrideImageResult.Failed -> Log.w("app", "not shown: ${r.reason}")         // the placeholder is back
    TrideImageResult.Superseded -> Unit             // a later call for "photo" came first
}
scene.clearImage("photo")                           // no picture: the placeholder shows
```

- `setImage(name, bytes)` takes JPEG, PNG or WebP bytes (HEIF from Android 8); `setImage(name, context,
  uri)` reads a `content://`, `file://` or `android.resource://` uri; `setImage(name, bitmap)` takes your
  own upright `Bitmap`. Each ends in `Loaded`, `Failed(reason)` or `Superseded` — a later `setImage` /
  `imageLoading` / `clearImage` of the same input came first, so the scene never saw this picture.
- `imageLoading(name)` says a picture is on its way before you have its bytes (your own download).
- A name that is no image input of the scene throws `TrideException.InvalidImage` and changes nothing; a
  picture the device cannot decode or a uri it cannot open is `Failed`, never a throw.
- The scene's state machine reads the input's status — `empty`, `loading`, `loaded`, `failed` — which is
  also what `getBinding("photo")` returns; every change comes to `onEvent` as the event `image.<input>`
  (`status`, `revision`, `width`, `height`, `reason`).
- Fill or fit is the author's: the Image Texture stretches the picture, fills the placeholder's
  proportions (cropped around the centre) or fits it whole with margins. The app passes any size.
- TrideKit decodes off the main thread, turns the photo upright by its EXIF orientation and scales it
  down to 2048 px on the long side. While a new picture loads the previous one stays: no flash of the
  placeholder. A paused scene shows a new picture after its next `tickOnce`.

More in [Docs/API.md](Docs/API.md).

## The scene follows a scrolling container

A scene can answer the container it sits in — it declares a plain number input (say `boost`) and the app
binds what it measures to it: `TrideInputSource.SCROLL_SPEED` (how fast the container moves, up or down
alike, in screen heights per second) or `SCROLL_POSITION` (the scene's pass through the container, 0 as its
top comes in at the bottom, 1 as its bottom leaves past the top). Nothing watches your container for you:
report it with `scroll(offset, extent, sceneTop, sceneHeight)` — the four numbers in one unit — once after
the scene is created and whenever it moves.

```kotlin
scene.bindInput("boost", TrideInputSource.SCROLL_SPEED)   // unbindInput("boost") lets go

// A NestedScrollView whose content holds the scene's view directly:
scroller.setOnScrollChangeListener { _, _, scrollY, _, _ ->
    scene.scroll(
        offset = scrollY.toDouble(), extent = scroller.height.toDouble(),
        sceneTop = sceneView.top.toDouble(), sceneHeight = sceneView.height.toDouble(),
    )
}
```

`bindInput(input, source, mapping)` takes a `TrideInputMapping` to pick the range, `boundInputs()` lists the
bindings, `dragAxes()` says whether the scene's drags also claim vertical swipes. A bound input belongs to
its source: `setBinding` on it throws until `unbindInput`. See [Docs/API.md](Docs/API.md).

## The example from source

```bash
cd Examples/TridePlayer
./gradlew :app:assembleRelease      # JAVA_HOME = JDK 17–24, ANDROID_HOME = an Android SDK with Platform 37
adb install -r app/build/outputs/apk/release/app-release.apk
```

Use `release` for speed: a debuggable build (`assembleDebug`) uploads a scene's meshes several times slower,
and a scene with growing geometry stutters on it.

## Versions

A scene opens in the TrideKit of the same release and newer; an older TrideKit does not open a scene from a
newer editor ("newer format"). Take the editor from the release with the same number in `tride-editor`.
