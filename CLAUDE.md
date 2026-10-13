# VibeRadio

Android audio player that behaves like a radio: a few generated channels, one knob, no skip/seek/track list. See README.md for the concept and issues #1–#6 for planned audio features.

## Build

Requires JDK 17+ (CI uses 21) and an Android SDK (`ANDROID_HOME` or `local.properties`).

```sh
./gradlew assembleDebug          # debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew installDebug           # install on a connected device
```

CI (`.github/workflows/android.yml`) runs `assembleDebug testDebugUnitTest` on every push and PR.

The app version comes from the release tag, never from source: `-Pviberadio.versionName=1.2.3` (or `1.2.3-alpha|beta|rc.N`) sets `versionName`, and `versionCodeOf` in `app/build.gradle.kts` derives a `versionCode` that sorts the same way. Without the property the build is `0.0.0-dev`, versionCode 1.

Dependency versions live only in `gradle/libs.versions.toml`. AGP 9 compiles Kotlin itself (built-in Kotlin): do **not** apply `org.jetbrains.kotlin.android`. The `kotlin` version in the catalog pins the Kotlin compiler via the Compose compiler plugin.

minSdk is 26. Nothing in #1–#6 needs higher: Media3 `AudioProcessor`s, `LoudnessEnhancer` and on-device classification all run on 26. API 26 also guarantees notification channels and adaptive icons. The one API split is genre lookup (see below).

## Architecture

Single `:app` module, package `app.viberadio`. Manual DI in `AppContainer` (one instance on `VibeRadioApp`), shared by the UI and the playback service (same process).

| Package | Role | Extension points |
|---|---|---|
| `library` | `MediaStoreLibraryScanner` → `List<Track>`. Genre from `GENRE` column on API 30+, from the `Genres`/`Members` tables on 26–29. `ContentKind` from MediaStore podcast/audiobook flags. | #6: refine `ContentKind` by analysing audio |
| `channels` | `ChannelGenerator` (pure) groups tracks by normalised genre into ≤6 channels + catch-all, or a single "All". `ChannelRepository` holds the result as a `StateFlow`. | #3: replace `ChannelTypeResolver.Untyped` and route by `ContentKind` |
| `playback` | `PlaybackService` (`MediaSessionService`) owns ExoPlayer + `MediaSession`. `RadioSessionCallback` expands a channel request into a queue and strips skip/seek/shuffle/repeat commands from **every** controller (notification, headset, our UI). | `StreamPlanner` for #2 (mixing) and #4 (fallback via `onExhausted`, called at track boundaries) |
| `playback.audio` | `ProcessingRenderersFactory` installs `AudioProcessorProvider`'s chain into `DefaultAudioSink`. | #1 loudness, #5 voice emphasis/ducking, #6 music suppression: add `AudioProcessor`s here |
| `ui` | `MainActivity` → `RadioScreen` → `ChannelKnob`. `RadioViewModel` talks to the service only via `MediaController`. `KnobMath` is the dial geometry. | |
| `ui.theme` | E-ink theme. | |

Tuning flow: knob release → `RadioViewModel.tune` → `controller.setMediaItem(ChannelRequest.mediaItem(id))` → `RadioSessionCallback.onSetMediaItems` → `StreamPlanner.plan` → queue. Each queued item carries the channel ID in `MediaMetadata.extras` (`ChannelRequest.EXTRA_CHANNEL_ID`) so the UI follows service-side channel changes.

## Conventions

- **The product rule is the spec.** No skip, seek, track list, or second control. If a feature seems to need one, raise it instead of adding it.
- **Look:** only the colours in `ui/theme/Color.kt`. No accent hues, no dynamic colour, no album-art colour. Dark mode stays below full white. Keep `res/values/colors.xml` in step for pre-Compose surfaces.
- Keep channel and queue logic in pure Kotlin (no Android types; `Track.uri` is a `String`) and cover it with JVM tests in `app/src/test`. Android-dependent code stays thin.
- Media3 unstable APIs: annotate the narrowest scope with `@OptIn(UnstableApi::class)` (or `@UnstableApi` on classes that expose them).
- One logical change per commit. Reference the issue (`#n`) a change works toward.
