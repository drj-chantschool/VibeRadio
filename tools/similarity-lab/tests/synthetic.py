"""A made-up diagnostics bundle: songs in five clear clusters, each cluster liked on one mood."""

import csv
import json
import os

import numpy as np

from vrlab.bundle import MOODS


def write_bundle(root: str, songs: int = 150, rated: int = 100, dims: int = 12, seed: int = 0, noise: float = 0.3) -> str:
    rng = np.random.default_rng(seed)
    path = os.path.join(root, "2026-10-20_080000")
    os.makedirs(path)
    centres = rng.normal(size=(len(MOODS), dims)) * 3
    hashes = [f"sha256:{i:064x}" for i in range(songs)]
    cluster = np.arange(songs) % len(MOODS)
    values = centres[cluster] + rng.normal(size=(songs, dims)) * noise * 3
    with open(os.path.join(path, "summary.json"), "w") as f:
        json.dump({"app_version": "test", "exported_at": "2026-10-20T08:00:00Z",
                   "audio_feature_blocks": [{"id": "dsp", "version": 1, "dims": dims, "similarity_weight": 1.0}]}, f)
    with open(os.path.join(path, "settings.json"), "w") as f:
        json.dump({"channels": [{"id": m, "default_name": m.title(), "name": m.title()} for m in MOODS]}, f)
    with open(os.path.join(path, "audio_features_dsp.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["track_hash"] + [f"d{i}" for i in range(dims)])
        for h, v in zip(hashes, values):
            w.writerow([h] + [f"{x:.6f}" for x in v])
    with open(os.path.join(path, "feedback.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["track_hash", "thumb", "at_ms", "at_local", "utc_offset_minutes", "channel_id", "separation_eq"])
        t = 1_700_000_000_000
        for i in range(rated):
            home = MOODS[cluster[i]]
            away = MOODS[(cluster[i] + 1 + i % 4) % len(MOODS)]
            w.writerow([hashes[i], "UP", t, "", 0, home, ""])
            w.writerow([hashes[i], "DOWN", t + 1, "", 0, away, ""])
            t += 1000
    with open(os.path.join(path, "channels.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["channel_id", "track_hash", "track_id"])
        for i, h in enumerate(hashes):
            for m in MOODS:
                w.writerow([m, h, i])
            w.writerow(["all-songs", h, i])
    with open(os.path.join(path, "library.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["track_id", "track_hash", "title", "artist", "album", "genre", "duration_ms", "kind", "size_bytes", "modified_sec", "folder", "uri"])
        for i, h in enumerate(hashes):
            w.writerow([i, h, f"Song {i}", f"Artist {cluster[i]}", "", f"Genre {cluster[i]}", 200000, "MUSIC", 1, 1, "Music", f"content://{i}"])
    return path
