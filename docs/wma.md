# WMA playback

Android has no decoder for WMA (ASF), and Media3 has no ASF reader, so VibeRadio reads the container itself and decodes with FFmpeg. Files that fail anyway are skipped, see [features.md](features.md) (Songs).

## Pieces

- **`playback.wma.Asf`** (pure Kotlin): `AsfHeaders` reads the header (the audio stream's WAVEFORMATEX, preroll, play duration, fixed packet size, packet count) and leaves the input at the first data packet. `AsfPacketReader` reads fixed-size data packets (error-correction data, single and multiple payloads, 1/2/4-byte length fields, padding) and reassembles each media object of the audio stream, including ones fragmented across payloads and packets. For WMA v1 and v2 a media object is one `block_align`-sized block. Other streams are ignored. Variable-size packets are refused.
- **`tools/ffmpeg`**: `vrwma.c` wraps libavcodec (`wmav1`, `wmav2`, `wmapro`, `wmalossless`; the ASF `wFormatTag` picks one) behind four calls: open, decode one block to interleaved float PCM, flush, close, plus the JNI glue (compiled only with `-DVRWMA_JNI`). `build.sh` builds FFmpeg (tag in the script, LGPL-only, static, no assembly, nothing but those four decoders) for `arm64-v8a`, `armeabi-v7a` and `x86_64` with the NDK and links each into `app/src/main/jniLibs/<abi>/libvrwma.so` (16 KB page aligned). `build.sh host` makes a Linux test program instead (`vrwma_test.c`), to check the C core against `ffmpeg`.
- **`WmaCodec` / `WmaNative`**: the Kotlin side of the JNI. `System.loadLibrary("vrwma")` is allowed to fail: then `WmaCodec.available` is false and everything below declines, so a build without the library (local, no NDK) still works and WMA files are skipped as before.
- **`WmaExtractor`** (Media3, first in `HighResFlacExtractorsFactory` with the FLAC one, in both players): claims ASF files when the library is there, decodes each media object and hands the player 32-bit float PCM (so Media3's own decoder is bypassed, like high-res FLAC). Timestamps come from the first object's presentation time minus the preroll, then from the samples decoded. Seeking maps the time linearly onto the data packets (WMA is constant bit rate), two packets early so the decoder warms up and a late estimate still lands before the target, flushes the decoder and drops PCM before the target. That serves live-radio resume.
- **`WmaReader`**: the same parse and decode front to back for the analysers, which read through `ContentResolver` streams: `WmaLevel` (loudness, `LoudnessAnalyzer`) and `WmaExcerpts` (the feature excerpts, `ExcerptDecoder`). It decodes only the stretches asked for, starting a second early.

## Build and CI

Gradle never builds the library (the `jniLibs` folder is gitignored and Gradle packs whatever is in it). The composite action `.github/actions/wma-decoder` runs `tools/ffmpeg/build.sh` in `ci.yml` and `release.yml` before the Gradle build, cached on `build.sh` and `vrwma.c`. Cloud sessions have no NDK either, so CI is the first place the Android build of it runs. `app/src/test/resources/wma` holds small ffmpeg-made files (WMA v1 and v2, one with objects fragmented across packets) with their media objects as `.packets`; `AsfTest` checks the reassembly byte for byte.

## Licence

FFmpeg is LGPL v2.1 or later; this build enables no GPL or nonfree parts. It is linked statically into `libvrwma.so`, a separate library the MIT-licensed app loads at run time, and `tools/ffmpeg/build.sh` with the FFmpeg tag named in it rebuilds that library from the unmodified FFmpeg source. `vrwma.c` is LGPL v2.1 or later like the library it wraps.

## Limits

- Seeking is an estimate (no use of the file's index object), so a resume point can land a little off on a file whose packets are not evenly filled.
- Lengths and timing are from the container; a damaged block is skipped.
- Video ASF (WMV) and variable-size packet files aren't played.
