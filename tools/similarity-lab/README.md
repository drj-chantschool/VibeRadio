# Similarity lab (PC)

Phase 2 of [the similarity plan](../../docs/similarity-plan.md), on a PC: does audio similarity predict which mood channels a song belongs on, which feature blocks and weights to ship, how to set the propagation model's knobs, whether the thumbs support five mood channels, and where the calibrated model would put every song.

It reads the diagnostics bundle the app exports (Settings → Debug → Export now or Share export) and, optionally, feature blocks it computes itself by running the same ONNX models the phone uses over a copy of the music. Everything mirrors the app's code, so its numbers carry over:

| Here | In the app |
|---|---|
| `space.py` (z-score, seeded random projection to 64 dims via a `java.util.Random` port, unit length, weights) | `features/Similarity.kt` |
| `propagation.py` (kernel, Beta posterior, confidence fallback, thumb rule, coverage) | `learning/Models.kt` `PropagatedChannelFit`, `ThumbRuleChannelFit` |
| `models.py` (manifests, log-mel frontend, patches, batches, activations) | `features/ModelSpec.kt`, `Spectral.kt` |
| `audio.py` (SHA-256 content hash, 3 × 30 s excerpts, mono, windowed-sinc resampler) | `feedback/ContentHash.kt`, `features/AudioClip.kt` |
| `store.py` (`<id>@<version>.tsv`, base64 floats) | `features/AudioFeatureStore.kt` |

## Install

Python 3.10+ and, for `extract`, ffmpeg on PATH.

```sh
cd tools/similarity-lab
python -m venv .venv && . .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -e '.[models]'                       # drop [models] if you won't run models
```

## Use

```sh
# 1. Optional: compute model blocks on the PC. MUSIC is a copy of the phone's music, byte for
#    byte (hashes must match; a re-tagged file is a different song). MODELS holds the .onnx
#    files with their .properties manifests, exactly as installed on the phone.
vrlab extract MUSIC --models MODELS --work vrlab-work --bundle DIAGNOSTICS

# 2. Analyse. DIAGNOSTICS is a bundle folder or the diagnostics folder (latest bundle wins).
vrlab report DIAGNOSTICS --work vrlab-work
```

`extract` hashes every file once (cached in `vrlab-work/songs.csv`), analyses songs with thumbs first, and skips what's done, so it can be stopped and rerun. Blocks over 256 dimensions (embeddings) never leave the phone, so this is the way to test them.

`report` writes to `vrlab-work/report/` (or `--out`):

- `report.html`: the answers, with the settings to paste into the app
- `results.json`: the same, machine-readable
- `placement.csv`: every analysed song's proposed mood channels, P(fit) and confidence per channel, next to its current channels

Options: `--blocks dsp,effnet` compares only those blocks; `--weight effnet=0.5` starts the weight search from that weight.

## What the report answers

- **Does similarity predict channel fit?** Leave-one-out: each rated song's thumbs are hidden and predicted from its neighbours with the app's current model. AUC per mood channel (a pair is *fit* with more thumbs up than down there, *misfit* with more down), Brier score, balanced accuracy of the actual placement, and how often neighbours carry enough evidence to decide. Each block alone, all together, and the same data with audio shuffled as the no-signal baseline.
- **Which representation to ship:** coordinate search over block weights (0, ½, 1, 2) on mean AUC, printed as manifest `similarityWeight`s and as `SimilaritySpace(weights = …)`.
- **Calibration:** τ, k, weight cap, thumbs-down weight and priors on the Brier score, then the confidence floor and threshold on balanced accuracy, printed as a `PropagatedChannelFit(…)` call. Chosen and scored on the same songs, so live results run a little lower.
- **Channel discovery:** spherical k-means silhouette for 2–8 clusters, where each cluster's thumbs fell, and the mean distance between channels' liked songs.
- **Proposed placement:** what the calibrated model would do to every analysed song, against channels.csv.
- **Phone vs PC:** when a block is both exported and computed here, how closely they agree (the check [audio-features.md](../../docs/audio-features.md) asks for before trusting a model's frontend).

Not answered yet: implicit signals (no play or tune log is exported), Wrong channel (#16 isn't logged), seasonal weighting.

## Tests

```sh
python -m unittest discover -s tests -t .
```
