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

## Phase 1: Compute features (offline first)

- Compute embeddings on a PC from a copy of the library (Python), not on the phone. Fast to iterate, and lets several models be compared before committing one to Android.
- Candidates: Essentia Discogs-EffNet (genre/style + mood/arousal-valence heads), MusiCNN, YAMNet, CLAP. Licensing is fine for personal use; revisit (Essentia is non-commercial) if the app is ever distributed.
- Per song: analyze 2-3 excerpts (e.g. 30 s at 25%, 50%, 75%), average the embeddings. Key results on file hash, same as the rest of the app.

## Phase 2: Offline analysis (notebook)

Questions to answer from the exported log + features:

- Does similarity predict channel fit? Leave-one-out: hide a song's labels, predict its channel fit from its neighbors, score against the hidden labels (AUC per channel). Compare embeddings and the mood-head block vs the raw embedding.
- Which representation to ship: pick a small block (target 16-32 dims; mood/arousal-valence outputs plus PCA'd genre, or CLAP similarity to each channel's description).
- Do implicit signals (channel change mid-song) agree with explicit ones? If not, they stay out of the model.
- Channel discovery: cluster the neighbor graph and see where thumbs evidence splits cleanly. Does it support five mood channels, fewer, or different ones?
- Calibrate: τ (kernel width), k (neighbors), priors α₀/β₀, and how much plain thumbs down should count toward channel fit vs Wrong channel.

## Phase 3: Ship the propagation model on-device

- Feature layout v2 = v1 thumbs block + the chosen audio block. Layout bump rebuilds every vector (already the rule).
- Port the chosen model to TFLite; extract in a WorkManager job while charging and idle. New songs are unanalyzed until the job reaches them; they rely on the All songs channel and genre placement meanwhile.
- Precompute each song's top-k neighbor list (k ≈ 20); rebuild on layout bump or library change.
- Per (song, channel), evidence from neighbor j: seasonal-weighted counts from the log, with Wrong channel weighted heavily and plain thumbs down lightly (plain thumbs down feeds mostly the preference model).

  ```
  w_ij = exp(−d(i,j)² / τ²), capped per neighbor so near-duplicates can't dominate
  α = α₀ + Σ w_ij · fit_j     β = β₀ + Σ w_ij · misfit_j
  P(fit) = α / (α + β); confidence = α + β
  ```

- Membership changes need both a probability threshold and a confidence threshold, with hysteresis (move out below one level, back in above a higher one) so songs don't flap between channels.
- Coverage rule still holds: a move never leaves a song on no channel.

## Phase 4: Learn the metric

Once there's enough labeled data, replace the fixed distance with learned per-dimension weights (diagonal Mahalanobis) fitted to which songs agree on channels. Generic embeddings measure timbre/genre; this teaches the distance what the user's channels actually mean.

## Open questions

- Should knob turns mid-song ever count as evidence? (Phase 2 decides.)
- If the channel set changes, how are old logs mapped onto new channels: by explicit merge mapping, or left as history only?
- How long until a new song's neighbor evidence is trusted enough to move it off its initial channel?
