# Audio features (songs)

Every song gets an audio feature vector computed from its sound, so songs with little or no feedback can borrow thumbs from songs that sound like them ([similarity-plan.md](similarity-plan.md), Phase 3). Songs only; podcasts are never analysed.

## Shape of the vector

The vector is a set of **blocks**, one per extractor, each with a stable ID, a version and a name per dimension. Blocks are stored and versioned separately (`files/features/<id>@<version>.tsv`, keyed on content hash), so:

- adding a model computes only its block; the others stay
- a new version of a model (new weights, new frontend) gets a new block key and is recomputed; old numbers are never mixed with new ones
- removing a model drops its block from similarity; its stored numbers stay in case it comes back

Built in, always present:

| Block | Dims | What |
|---|---|---|
| `dsp` | 52 | Loudness and dynamics (level, spread, dynamic range, low-energy ratio), spectral shape (centroid, bandwidth, roll-off, flatness, flux, zero-crossing rate), 13 MFCC means and spreads (timbre), 12-bin chroma (harmony), tempo and pulse clarity. Pure Kotlin, no model |

Each installed model adds one block: an embedding (hundreds of anonymous dimensions) or a classifier's outputs (one named dimension per label: genres, moods, instruments, voice/instrumental, language).

## Analysis

- Three 30 s excerpts per song, centred at 25 %, 50 % and 75 % (a song under 90 s is analysed whole), decoded with the platform codecs, mixed to mono, resampled per extractor (band-limited sinc).
- Model outputs are per window or patch; the stored block is their mean. Classifier heads read their embedding model's per-patch outputs, so an embedding plus its heads cost one pass.
- Runs in a JobScheduler job while charging and idle, or now from Settings → Debug → Audio features. It stops between songs when the window ends and carries on next time.

## Similarity

All blocks with a similarity weight above 0 form one space: each block standardised over the library, blocks over 64 dimensions randomly projected to 64 (distances survive), each scaled to unit length, then weighted. So a block counts by its weight, not its size, and a 1280-dim embedding doesn't drown a 12-dim chroma. Distance is cosine (0 = same, 2 = opposite). Only songs with every weighted block are indexed. The learner looks up each song's 20 nearest songs that have mood-channel thumbs ([similarity-plan.md](similarity-plan.md), Phase 3, for the propagation rule).

## Swapping parts

| To change | Where |
|---|---|
| Add a model | Drop an ONNX file plus manifest in via Settings → Debug → Add feature models (below), or implement `FeatureExtractor` and list it in `AppContainer.audioFeatures` |
| Change a block's weight | `similarityWeight` in its manifest, or `SimilaritySpace(weights = …)` |
| Change how evidence propagates | `ChannelFitModel` in `LearningModels` (`PropagatedChannelFit` today) |
| Change the runtime | Implement `FeatureExtractor` (e.g. on LiteRT); `ModelFrontend` holds the runtime-free plumbing |
| Change the excerpts | `ExcerptPlan` |

## Model manifests

A model is an `.onnx` file plus a `.properties` manifest (and a labels file, one label per line, for classifiers). Pick all of them together in Settings → Debug → Add feature models. Manifests that can't be used are listed there with the reason. Keys:

| Key | Meaning |
|---|---|
| `id`, `version` | Block ID (no `@`, `/` or spaces) and version. Bump `version` whenever the model file or any frontend key changes |
| `model` | The `.onnx` file name |
| `dims` / `labels` / `labelsFile` | Output size with anonymous names, or `\|`-separated labels, or a labels file |
| `input` | `waveform`, `mel` or `upstream` |
| `sampleRate`, `window`, `hop` | `waveform`: rate and window/hop in samples |
| `sampleRate`, `nFft`, `hop`, `nMels`, `fMin`, `fMax`, `scale` (`slaney`/`htk`), `areaNormalize`, `power` (1/2), `log` (`none`, `ln`, `db`, `log10_1p_10000`), `patchFrames`, `patchHop` | `mel`: the log-mel frontend, computed in Kotlin, and the patches fed to the model |
| `upstream` | `upstream`: the block ID whose per-patch outputs this model reads |
| `inputShape` | e.g. `-1,128,96`; `-1` is the batch dimension, filled with the number of patches (32 at a time). Without `-1`, one window per call |
| `inputName`, `outputName` | Which tensors; default the first. Check a model's names with Netron or its published metadata |
| `activation` | `none`, `sigmoid` or `softmax`, applied per window before averaging (leave `none` when the model already outputs probabilities) |
| `similarityWeight` | Weight in similarity, default 1; 0 stores (and exports) the block without using it |

Only float inputs and outputs are supported. The mel frontend matches the usual definitions, but a model trained on one library's frontend (Essentia, librosa, torchaudio) will see small differences. Before trusting a model, run it on a few songs in Python with its own frontend and compare with the diagnostics export.

### Example: Discogs-EffNet embedding plus a mood head (Essentia)

```properties
# effnet.properties
id=effnet
version=1
model=discogs-effnet-bsdynamic-1.onnx
input=mel
sampleRate=16000
nFft=512
hop=256
nMels=96
scale=slaney
log=log10_1p_10000
patchFrames=128
patchHop=62
inputShape=-1,128,96
outputName=embeddings
dims=1280
```

```properties
# mood_happy.properties
id=mood_happy
version=1
model=mood_happy-discogs-effnet-1.onnx
input=upstream
upstream=effnet
inputShape=-1,1280
labelsFile=mood_happy.txt
```

The same pattern covers the other Essentia heads on Discogs-EffNet: `genre_discogs400` (400 styles), `mtg_jamendo_genre`, `mtg_jamendo_moodtheme`, `mtg_jamendo_instrument` (40 instruments), `voice_instrumental`, `gender`, `danceability`, `mood_*` (happy, sad, relaxed, aggressive, party, acoustic, electronic), `approachability`, `engagement`, and arousal/valence regressors. Their labels and tensor names are in the `.json` metadata Essentia publishes next to each model. The heads' outputs are already probabilities. Give the 1280-dim embedding a lower `similarityWeight` than the heads if the heads should lead.

## Library survey (October 2026)

What exists for the features asked for (genre, mood, instruments, vocals, language), and how it fits. Nothing here is bundled; the user installs what they want.

| Model | Gives | Input | Format, licence | Fit |
|---|---|---|---|---|
| [Essentia models](https://essentia.upf.edu/models.html) (MTG-UPF): Discogs-EffNet, MAEST, heads | 1280-d embedding; genre (400 Discogs styles, Jamendo genres), mood/theme, 40 instruments, voice/instrumental, vocal gender, danceability, arousal/valence | 16 kHz log-mel patches (96 bands) | TF `.pb`, some ONNX and TF.js; CC BY-NC-SA 4.0 (personal use fine) | Best single source: one embedding pass plus cheap heads covers genre, mood, instruments and vocals. Recommended first install |
| YAMNet (Google) | 521 AudioSet classes (singing, speech, many instruments, some genres), 1024-d embedding | 16 kHz waveform, ~1 s windows | TFLite, Apache 2.0 ([guide](https://www.tensorflow.org/lite/android/tutorials/audio_classification)) | Small and fast; coarse for music. Convert to ONNX (tf2onnx) to use here |
| PANNs CNN14 | 527 AudioSet classes | log-mel of 10 s clips (frontend on host) | PyTorch, MIT; [LiteRT conversion](https://huggingface.co/litert-community/PANNs-CNN14-AudioSet-LiteRT) (~160 MB) | Better tagging than YAMNet, much larger |
| LAION CLAP | 512-d joint audio/text embedding: zero-shot tags from any text ("calm acoustic", "late-night jazz") | 48 kHz, 10 s | PyTorch; ONNX at [Xenova/clap-htsat-unfused](https://huggingface.co/Xenova/clap-htsat-unfused) ([repo](https://github.com/LAION-AI/CLAP)) | Could score songs against each channel's description; the text side runs offline to make fixed prompt vectors |
| MusiCNN, MERT | Tags / large embeddings | 16 kHz / 24 kHz | ISC / CC BY-NC | MusiCNN is small but older; MERT too heavy for a phone |
| Whisper (multilingual), via [sherpa-onnx](https://pub-web-s1.flutter-io.cn/packages/sherpa_onnx) | Spoken language ID | 16 kHz | ONNX, MIT / Apache 2.0 | Language needs its own decode step (not a plain feed-forward model), so it needs its own `FeatureExtractor`. Trained on speech: over a full mix it guesses poorly; gate on `voice_instrumental` and treat it as a hint |
| SpeechBrain VoxLingua107 (ECAPA) | 107 languages | 16 kHz speech | PyTorch, Apache 2.0 | Same caveat as Whisper; exports to ONNX with its filterbank outside the graph |

Runtime: [ONNX Runtime Android](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android) (MIT), because Essentia and CLAP publish ONNX directly; LiteRT would need conversions. Its telemetry provider is removed from the app's manifest and its telemetry switched off, so nothing leaves the phone. It adds native libraries for each ABI to the APK.

## Open questions

- Which block weights predict channel fit best: Phase 2 of the similarity plan, on the exported `audio_features_*.csv`.
- Sung-language detection: needs vocals isolated (source separation) to be reliable; not attempted.
- Whether the `dsp` block alone is useful once a learned embedding is installed, or should drop to weight 0.
