"""Channel discovery and proposed placement: does the similarity graph support five mood
channels, and where would the calibrated model put every analysed song."""

from collections import Counter

import numpy as np

from vrlab.bundle import MOODS, Bundle
from vrlab.propagation import Params, evidence, nearest, place, thumb_rule
from vrlab.space import Space


def kmeans(x: np.ndarray, k: int, seed: int = 1, iterations: int = 50) -> np.ndarray:
    """Spherical k-means (cosine) with k-means++ seeding; returns a cluster per row."""
    rng = np.random.default_rng(seed)
    n = len(x)
    centres = [x[rng.integers(n)]]
    for _ in range(1, k):
        d = np.min(1 - x @ np.stack(centres).T, axis=1).clip(0)
        total = d.sum()
        centres.append(x[rng.choice(n, p=d / total)] if total > 0 else x[rng.integers(n)])
    c = np.stack(centres)
    assign = np.zeros(n, dtype=int)
    for _ in range(iterations):
        new = np.argmax(x @ c.T, axis=1)
        if _ and (new == assign).all():
            break
        assign = new
        for j in range(k):
            members = x[assign == j]
            if len(members):
                v = members.sum(axis=0)
                norm = np.linalg.norm(v)
                c[j] = v / norm if norm > 0 else c[j]
    return assign


def silhouette(x: np.ndarray, assign: np.ndarray) -> float | None:
    """Mean silhouette with cosine distance."""
    labels = np.unique(assign)
    if len(labels) < 2:
        return None
    d = 1 - x @ x.T
    out = []
    for i in range(len(x)):
        same = assign == assign[i]
        same[i] = False
        if not same.any():
            out.append(0.0)
            continue
        a = d[i, same].mean()
        b = min(d[i, assign == j].mean() for j in labels if j != assign[i])
        out.append((b - a) / max(a, b) if max(a, b) > 0 else 0.0)
    return float(np.mean(out))


def clusters(bundle: Bundle, space: Space, weights: dict[str, float], fits: dict[str, np.ndarray],
             ks=range(2, 9), sample: int = 2000, seed: int = 3) -> dict:
    x = space.vectors(weights).astype(np.float64)
    norms = np.linalg.norm(x, axis=1)
    x = x / np.where(norms > 0, norms, 1)[:, None]
    rng = np.random.default_rng(seed)
    pick = rng.choice(len(x), size=min(sample, len(x)), replace=False) if len(x) else np.array([], dtype=int)
    scan = []
    for k in ks:
        if k >= len(pick):
            break
        a = kmeans(x[pick], k, seed)
        scan.append({"k": k, "silhouette": silhouette(x[pick], a)})
    best_k = max(scan, key=lambda r: r["silhouette"] or -1)["k"] if scan else None
    tables = {}
    for k in sorted({k for k in (best_k, len(MOODS)) if k and k < len(x)}):
        a = kmeans(x, k, seed)
        rows = []
        for j in range(k):
            members = [space.hashes[i] for i in np.flatnonzero(a == j)]
            thumbs = sum((fits[h] for h in members if h in fits), np.zeros(len(MOODS) * 2))
            genres = Counter(bundle.songs[h].genre for h in members if h in bundle.songs and bundle.songs[h].genre)
            examples = [f"{bundle.songs[h].artist} – {bundle.songs[h].title}" for h in members if h in bundle.songs][:3]
            rows.append({
                "cluster": j, "songs": len(members),
                "up": {m: int(thumbs[i * 2]) for i, m in enumerate(MOODS)},
                "down": {m: int(thumbs[i * 2 + 1]) for i, m in enumerate(MOODS)},
                "genres": [g for g, _ in genres.most_common(3)], "examples": examples,
            })
        tables[k] = rows
    return {"scan": scan, "best_k": best_k, "tables": tables, "sampled": int(len(pick))}


def separation(space: Space, weights: dict[str, float], fits: dict[str, np.ndarray]) -> dict:
    """Mean distance between songs thumbed up on mood a and on mood b (a = b: within)."""
    groups = {}
    for c, mood in enumerate(MOODS):
        groups[mood] = np.array([space.index[h] for h in space.hashes if h in fits and fits[h][c * 2] > fits[h][c * 2 + 1]], dtype=int)
    out = {}
    for a in MOODS:
        out[a] = {}
        for b in MOODS:
            ga, gb = groups[a], groups[b]
            if len(ga) == 0 or len(gb) == 0 or (a == b and len(ga) < 2):
                out[a][b] = None
                continue
            d = space.distances(weights, ga, gb)
            if a == b:
                d = d[~np.eye(len(ga), dtype=bool)]
            out[a][b] = float(d.mean())
    return {"matrix": out, "sizes": {m: int(len(g)) for m, g in groups.items()}}


def propose(bundle: Bundle, space: Space, weights: dict[str, float], fits: dict[str, np.ndarray], p: Params) -> list[dict]:
    """Every analysed song placed by the model with its own thumbs plus its neighbours', next to
    where the export says it is now."""
    if not len(space):
        return []
    rated = [h for h in space.hashes if h in fits and fits[h].sum() > 0]
    zero = np.zeros(len(MOODS) * 2)
    own = np.stack([fits.get(h, zero) for h in space.hashes])
    rows_idx = np.arange(len(space))
    if rated:
        cols = np.array([space.index[h] for h in rated], dtype=int)
        d = space.distances(weights, rows_idx, cols)
        idx, dist = nearest(d, p.k, rows_idx[:, None] == cols[None, :])
        rated_fit = np.stack([fits[h] for h in rated])
        prob, confidence = evidence(own, rated_fit, idx, dist, p)
        placed = place(own, prob, confidence, (idx >= 0).any(axis=1), p)
    else:
        prob = np.full((len(space), len(MOODS)), np.nan)
        confidence = np.zeros((len(space), len(MOODS)))
        placed = thumb_rule(own)
    out = []
    for i, h in enumerate(space.hashes):
        song = bundle.songs.get(h)
        now = sorted(c for c in bundle.placement.get(h, ()) if c in MOODS) if h in bundle.placement else None
        proposed = [m for c, m in enumerate(MOODS) if placed[i, c]]
        out.append({
            "hash": h, "title": song.title if song else "", "artist": song.artist if song else "",
            "path": song.path if song else "", "thumbs": int(own[i].sum()),
            "proposed": proposed, "current": now,
            "changed": None if now is None else sorted(now) != sorted(proposed),
            "p_fit": {m: (None if np.isnan(prob[i, c]) else round(float(prob[i, c]), 3)) for c, m in enumerate(MOODS)},
            "confidence": {m: round(float(confidence[i, c]), 3) for c, m in enumerate(MOODS)},
        })
    return out
