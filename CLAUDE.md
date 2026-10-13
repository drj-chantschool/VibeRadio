# VibeRadio

Android audio player that behaves like a radio: a few generated channels, one knob, no skip/seek/track list on the song side. Podcasts are a separate, hand-curated pipeline with their own page. See README.md for the concept and the issues for planned features.

## Build

Requires JDK 17+ (CI uses 21) and an Android SDK (`ANDROID_HOME` or `local.properties`).

```sh
./gradlew assembleDebug          # debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew installDebug           # install on a connected device
```

CI (`.github/workflows/ci.yml`, job `check`, required by the "Protect main" ruleset) runs actionlint, then `assembleDebug assembleRelease lint testDebugUnitTest` on every PR and push to `main`.

The app version comes from the release tag, never from source: `-Pviberadio.versionName=1.2.3` (or `1.2.3-alpha|beta|rc.N`) sets `versionName`, and `versionCodeOf` in `app/build.gradle.kts` derives a `versionCode` that sorts the same way. Without the property the build is `0.0.0-dev`, versionCode 1.

Release signing reads `VIBERADIO_KEYSTORE_PATH`, `VIBERADIO_KEYSTORE_PASSWORD`, `VIBERADIO_KEY_ALIAS` and `VIBERADIO_KEY_PASSWORD` from the environment; without them `assembleRelease` produces an unsigned APK. Debug builds use the `app.viberadio.debug` ID and the label "VibeRadio dev", so they install next to the release app.

## Releases

Distribution is GitHub Releases, installed and updated on-device with Obtainium. `.github/workflows/release.yml` validates the version, runs unit tests, builds a signed release APK with that version, verifies the signature and publishes `viberadio-<version>.apk` (plus a `.sha256`) as a GitHub Release. Versions with `-alpha.N`, `-beta.N` or `-rc.N` publish as pre-releases.

Start a release either way:

- **Run workflow** (works from a phone browser): Actions → Release → Run workflow on `main`, version e.g. `0.2.0` or `0.2.0-beta.1`. The workflow creates the `v` tag on the `main` commit it built. This is the route for Claude sessions, which cannot push tags.
- **Push a tag:** `git tag v0.2.0 && git push origin v0.2.0`.

Repo secrets the workflow needs: `VIBERADIO_KEYSTORE_BASE64` (the `.jks`, base64-encoded), `VIBERADIO_KEYSTORE_PASSWORD`, `VIBERADIO_KEY_ALIAS`, `VIBERADIO_KEY_PASSWORD`. The keystore is permanent: every update must be signed with it, so it lives offline as well as in secrets and never in the repo.

## Dependencies

Dependency versions live only in `gradle/libs.versions.toml`. AGP 9 compiles Kotlin itself (built-in Kotlin): do **not** apply `org.jetbrains.kotlin.android`. The `kotlin` version in the catalog pins the Kotlin compiler via the Compose compiler plugin.

minSdk is 26. Nothing in #1–#6 needs higher: Media3 `AudioProcessor`s, `LoudnessEnhancer` and on-device classification all run on 26. API 26 also guarantees notification channels and adaptive icons. The one API split is genre lookup (see below).

## Architecture

Single `:app` module, package `app.viberadio`. Manual DI in `AppContainer` (one instance on `VibeRadioApp`), shared by the UI and the playback service (same process).

| Package | Role | Extension points |
|---|---|---|
| `library` | `MediaStoreLibraryScanner` → `List<Track>`. Genre from `GENRE` column on API 30+, from the `Genres`/`Members` tables on 26–29. `ContentKind` from MediaStore podcast/audiobook flags. MIDI (`MidiFiles`: MIME type or .mid/.midi/.kar/.rmi) is skipped, never a song. | #6: refine `ContentKind` by analysing audio |
| `channels` | `ChannelGenerator` (pure) groups tracks by normalised genre into ≤6 channels + catch-all, or a single "All", then appends "All songs" (music only, ≤20 min, never podcasts). `ChannelRepository` holds the result as a `StateFlow`. | #3: replace `ChannelTypeResolver.Untyped` and route by `ContentKind` |
| `playback` | `PlaybackService` (`MediaSessionService`) owns ExoPlayer + `MediaSession`. `RadioSessionCallback` expands a channel request into a queue and strips skip/seek/shuffle/repeat commands from **every** controller (notification, headset, our UI). For podcast-only channels it queues `PodcastLibrary.queue`, starts at the saved position, marks episodes played on finish, saves positions every 15 s and on retune, keeps the queued tail in step with the podcast page, sets the player to the current episode's show speed (pitch preserved; 1× for songs; controllers still can't change speed), and falls back to `Dial.fallback` when empty. | `StreamPlanner` for #2 (mixing) and #4 (fallback via `onExhausted`, called at track boundaries). Today `RecencyWeightedStreamPlanner`: a weighted random permutation where weight = `1 - 2^(-age/halfLife)` (3 days), so recently played songs tend to come later |
| `playback.audio` | `ProcessingRenderersFactory` installs `AudioProcessorProvider`'s chain into `DefaultAudioSink`. | #1 loudness, #5 voice emphasis/ducking, #6 music suppression: add `AudioProcessor`s here |
| `podcasts` | Independent of the song pipeline (#17): never imports `library` or `channels`. `RssParser` (JDK DOM) → `Feed`. `PodcastLibrary` (pure, immutable) holds shows, their hand-assigned channel IDs (#18) and playback speed (`PodcastSpeeds` ladder, 0.8–2×, default 1×), and the global played set and resume positions (#19); `queue(channelId)` = started episode first, then oldest unplayed. `PodcastRepository` persists it (`JsonPodcastStore`), fetches feeds (`HttpFeedFetcher`), and has `EpisodeDownloader` keep only `upNext(3)` on the device, so played episodes are never downloaded. | Mixed channels (#2): assign shows to song channel IDs |
| `feedback` | Songs only (#13). `FeedbackDatabase` (`feedback.db`, the only Android code here) holds three tables keyed on content hash (`ContentHash`, SHA-256 of the bytes, so the key survives moves, renames and rescans but not tag edits). `feedback`: `FeedbackRecorder` appends a `FeedbackEvent` (hash, thumb, time, UTC offset, channel ID) per press; triggers abort any UPDATE or DELETE, so it stays append-only; the old tab-separated `feedback.log` (`LegacyTsvLog`) is imported on first open. `play_history`: one row per song, `last_played` overwritten each time a song becomes current (`PlayHistory.markPlayed`, called from `RadioSessionCallback`). `track_hashes`: URI → hash, valid while MediaStore size and mtime match, so `PlayHistory.lastPlayedMs` answers from memory without reading files. | #14/#15 read `events()`; #16 adds "Wrong channel" events |
| `dial` | `Dial.stations` = song channels, then podcast-only channels that have a show on them (today just "Podcasts", `PodcastChannels.PODCASTS_ID`, `VOCAL_ONLY`). `Dial.fallback` is the #4 hand-over target. With `playback`, the only code that sees both pipelines. | |
| `ui` | `MainActivity` → `HorizontalPager`: song page (`RadioScreen` → `ChannelKnob`, `RadioViewModel`) and podcast page (`PodcastScreen`, `PodcastViewModel`). `RadioViewModel` talks to the service only via `MediaController`. `KnobMath` is the dial geometry. | |
| `ui.theme` | E-ink theme. | |

Tuning flow: knob release → `RadioViewModel.tune` → `controller.setMediaItem(ChannelRequest.mediaItem(id))` → `RadioSessionCallback.onSetMediaItems` → `StreamPlanner.plan` (song channel) or `PodcastLibrary.queue` (podcast-only channel) → queue. Each queued item carries the channel ID in `MediaMetadata.extras` (`ChannelRequest.EXTRA_CHANNEL_ID`) so the UI follows service-side channel changes. Song items also carry their content URI (`ChannelRequest.EXTRA_TRACK_URI`), which is how the song page knows what a thumbs press refers to; episodes don't, so the thumbs hide for them.

## Conventions

- **The product rule is the spec.** You still have control, but you shouldn't need it, and the app doesn't encourage using it in an unhealthy way; defaults should be right the first time.
  - **Song side (strict):** one knob. No skip, seek, track list, or second control. If a feature seems to need one, raise it instead of adding it. The thumbs (#13) are feedback, not control: they never skip or change the queue.
  - **Podcast page:** more control, because podcasts are curated, long-form and limited: add/remove feeds, assign shows to channels, mark episodes played/unplayed, see what's up next. Still no play, skip or seek; listening happens by tuning the dial.
- **Look:** only the colours in `ui/theme/Color.kt`. No accent hues, no dynamic colour, no album-art colour. Dark mode stays below full white. Keep `res/values/colors.xml` in step for pre-Compose surfaces.
- Keep channel and queue logic in pure Kotlin (no Android types; `Track.uri` is a `String`) and cover it with JVM tests in `app/src/test`. Android-dependent code stays thin. The same goes for `podcasts` (only `JsonPodcastStore` touches Android) and `feedback` (only `FeedbackDatabase`).
- Media3 unstable APIs: annotate the narrowest scope with `@OptIn(UnstableApi::class)` (or `@UnstableApi` on classes that expose them).
- One logical change per commit. Reference the issue (`#n`) a change works toward.
