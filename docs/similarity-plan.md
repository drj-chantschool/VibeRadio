# Similarity plan (songs)

Goal: decide channel membership for songs with little or no direct feedback by borrowing evidence from acoustically similar songs. If a song is similar to one that clearly doesn't belong on channel X, it probably doesn't belong on X either; the more similar, the stronger the inference. Extends [learning.md](learning.md); scope is songs only.

Channels are still in flux, so nothing here assumes the current five moods. Everything is keyed on stable channel IDs, and the same similarity graph is used to check whether the channel set itself is right.

## Phase 0: Collect data (start now)

Nothing below works without data, and seasonal effects need months, so logging starts before any modeling.

What to log, on-device (SQLite, local only):

- Thumbs log (already specified): song hash, channel ID, up/down, timestamp.
- Wrong channel events: song hash, from-channel ID, the channel it moved to, timestamp. The strongest channel-fit signal; keep it separate from plain thumbs down.
- Play log: song hash, channel ID, start time, end time, how it ended (finished, channel changed, app stopped/paused, Bluetooth disconnect). Implicit signals are logged but NOT used by any model until Phase 2 shows they mean something. A knob turn mid-song might be a fit signal or just a change of mood.
- Tune log: channel tuned to, timestamp, source (knob, Bluetooth resume, app start). Feeds default-channel learning later.
- Channel registry: stable ID, display name history, created/retired dates. Old log entries must stay interpretable after channels are renamed, merged or removed.
- Library snapshot: hash, duration, path/metadata (artist, album, title) at scan time. Used to spot near-duplicates and to sanity-check clusters during analysis.

Export: Settings → Debug → "Export data" writes a zip (SQLite + feature cache) to share to a PC. Personal use only, so no upload path. (Superseded by the Diagnostics bundle in [features.md](features.md).)

Rough data needs before Phase 2 is meaningful: a few hundred thumbs spread over every channel, and some Wrong channel events. Seasonal weighting can't be validated until the log spans at least two seasons; until then, weight by recency only.

## Phase 1: Compute features

- On the phone (built): the `features` package fills a per-song audio feature vector, one versioned block per extractor, keyed on content hash. A built-in signal block (`dsp`: loudness, dynamics, spectral shape, MFCCs, chroma, tempo) needs no model; every ONNX model installed from Settings → Debug adds a block (an embedding, or a classifier head's genre, mood, instrument, voice or language outputs). Details, library survey and model recipes: [audio-features.md](audio-features.md).
- Comparing models is still easier on a PC (Python, same models, same excerpts): try several there, then install the winner. The on-device pipeline is what ships.
- Candidates: Essentia Discogs-EffNet (genre/style + mood/arousal-valence heads), MusiCNN, YAMNet, PANNs, CLAP. Licensing is fine for personal use; revisit (Essentia's models are CC BY-NC-SA) if the app is ever distributed. No model is bundled with the app.
- Per song: analyze 3 excerpts (30 s centred at 25%, 50%, 75%), average the per-window outputs. Key results on file hash, same as the rest of the app.

## Phase 2: Offline analysis (notebook)

Questions to answer from the exported log + features:

- Does similarity predict channel fit? Leave-one-out: hide a song's labels, predict its channel fit from its neighbors, score against the hidden labels (AUC per channel). Compare embeddings and the mood-head block vs the raw embedding.
- Which representation to ship: pick a small block (target 16-32 dims; mood/arousal-valence outputs plus PCA'd genre, or CLAP similarity to each channel's description).
- Do implicit signals (channel change mid-song) agree with explicit ones? If not, they stay out of the model.
- Channel discovery: cluster the neighbor graph and see where thumbs evidence splits cleanly. Does it support five mood channels, fewer, or different ones?
- Calibrate: τ (kernel width), k (neighbors), priors α₀/β₀, and how much plain thumbs down should count toward channel fit vs Wrong channel.

## Phase 3: Ship the propagation model on-device

- Built, ahead of Phase 2's calibration, with conservative defaults (below). The audio blocks are kept apart from the thumbs vector (they're versioned per block, so a model swap recomputes only its block); the learner reads both.
- Models run with ONNX Runtime; extraction runs in a JobScheduler job while charging and idle (or Settings → Debug → Audio features). New songs are unanalyzed until the job reaches them; they have no neighbours and are placed by thumbs alone meanwhile.
- Neighbours (k = 20) are looked up at placement among the analysed songs that have mood-channel thumbs, in one combined space: each block standardised over the library, blocks over 64 dimensions randomly projected to 64, each scaled to unit length and weighted (equal by default), cosine distance.
- Per (song, channel), evidence from neighbor j: seasonal-weighted counts from the log, with Wrong channel weighted heavily and plain thumbs down lightly (plain thumbs down feeds mostly the preference model).

  ```
  w_ij = exp(−d(i,j)² / τ²), capped per neighbor so near-duplicates can't dominate
  α = α₀ + Σ w_ij · fit_j     β = β₀ + Σ w_ij · misfit_j
  P(fit) = α / (α + β); confidence = α + β
  ```

- Membership changes need both a probability threshold and a confidence threshold, with hysteresis (move out below one level, back in above a higher one) so songs don't flap between channels. Built so far (`PropagatedChannelFit`): τ = 0.35, cap 0.8, plain thumbs down at 0.5, α₀ = β₀ = 1, on at P ≥ 0.5 once confidence ≥ 2; below that confidence the thumb rule decides, and a song's own thumbs up on a channel always keeps it there. No hysteresis yet; Wrong channel (#16) isn't logged yet, so only thumbs count.
- Coverage rule still holds: a move never leaves a song on no channel.

## Phase 4: Learn the metric

Once there's enough labeled data, replace the fixed distance with learned per-dimension weights (diagonal Mahalanobis) fitted to which songs agree on channels. Generic embeddings measure timbre/genre; this teaches the distance what the user's channels actually mean.

## Open questions

- Should knob turns mid-song ever count as evidence? (Phase 2 decides.)
- If the channel set changes, how are old logs mapped onto new channels: by explicit merge mapping, or left as history only?
- How long until a new song's neighbor evidence is trusted enough to move it off its initial channel?
