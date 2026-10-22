"""Phase 2 questions (docs/similarity-plan.md): does similarity predict channel fit, which blocks
and weights to ship, and how to set τ, k, the priors and the thresholds.

Leave-one-out throughout: each rated song's own thumbs are hidden and its fit is predicted from
its neighbours alone, exactly as the app would place a song that has never been thumbed.
"""

import itertools
from dataclasses import dataclass, field, replace

import numpy as np

from vrlab.bundle import MOODS
from vrlab.propagation import Params, evidence, nearest, place
from vrlab.space import Space

TAUS = [0.1, 0.2, 0.35, 0.5, 0.75, 1.0, 1.5]
KS = [5, 10, 20, 40]
CAPS = [0.5, 0.8, 1.0]
DOWN_WEIGHTS = [0.25, 0.5, 1.0]
PRIORS = [(0.5, 0.5), (1.0, 1.0), (2.0, 2.0), (1.0, 2.0), (2.0, 1.0)]
MIN_CONFIDENCES = [0.5, 1.0, 2.0, 3.0, 5.0, 8.0]
THRESHOLDS = [0.3, 0.4, 0.5, 0.6, 0.7]
WEIGHT_STEPS = [0.0, 0.5, 1.0, 2.0]


def auc(scores: np.ndarray, labels: np.ndarray) -> float | None:
    """Area under the ROC curve (Mann–Whitney, ties averaged); None without both classes."""
    pos = labels == 1
    n_pos, n_neg = int(pos.sum()), int((~pos).sum())
    if n_pos == 0 or n_neg == 0:
        return None
    order = np.argsort(scores, kind="mergesort")
    ranks = np.empty(len(scores), dtype=np.float64)
    sorted_scores = scores[order]
    i = 0
    while i < len(scores):
        j = i
        while j + 1 < len(scores) and sorted_scores[j + 1] == sorted_scores[i]:
            j += 1
        ranks[order[i:j + 1]] = (i + j) / 2 + 1
        i = j + 1
    return float((ranks[pos].sum() - n_pos * (n_pos + 1) / 2) / (n_pos * n_neg))


def labels_of(fit: np.ndarray) -> np.ndarray:
    """Per (song, mood): 1 if more thumbs up than down there, 0 if more down, NaN otherwise."""
    up, down = fit[:, 0::2], fit[:, 1::2]
    out = np.full(up.shape, np.nan)
    out[up > down] = 1.0
    out[down > up] = 0.0
    return out


def balanced_accuracy(predicted: np.ndarray, labels: np.ndarray) -> float | None:
    mask = ~np.isnan(labels)
    y, p = labels[mask] == 1, predicted[mask]
    if y.all() or not y.any():
        return None
    return float((p[y].mean() + (~p[~y]).mean()) / 2)


@dataclass
class Scores:
    # Mean of the per-channel AUCs. Pooling pairs across channels would mostly measure how
    # often each channel is liked, not whether neighbours tell songs apart.
    auc: float | None
    channel_auc: dict[str, float | None]
    brier: float | None
    balanced_accuracy: float | None
    # Share of labelled (song, mood) pairs where neighbours carried enough evidence to decide.
    decided: float | None
    pairs: int

    def as_dict(self) -> dict:
        return dict(self.__dict__)


@dataclass
class Data:
    """The rated songs of a space: their thumbs, labels and per-block similarity among them."""

    space: Space
    hashes: list[str]
    fit: np.ndarray
    labels: np.ndarray
    block_sims: dict[str, np.ndarray] = field(default_factory=dict)

    @classmethod
    def of(cls, space: Space, fits: dict[str, np.ndarray]) -> "Data":
        hashes = [h for h in space.hashes if h in fits and fits[h].sum() > 0]
        fit = np.stack([fits[h] for h in hashes]) if hashes else np.zeros((0, 10))
        idx = np.array([space.index[h] for h in hashes], dtype=int)
        sims = {b.id: space.similarity(b.id, idx, idx).astype(np.float64) for b in space.blocks}
        return cls(space, hashes, fit, labels_of(fit), sims)

    def distances(self, weights: dict[str, float]) -> np.ndarray:
        used = {b: w for b, w in weights.items() if w > 0 and b in self.block_sims}
        total = sum(used.values())
        s = sum((w / total) * self.block_sims[b] for b, w in used.items())
        return 1.0 - s


def score(data: Data, distances: np.ndarray, p: Params, neighbours=None) -> Scores:
    """Leave-one-out scores for one space (as a rated × rated distance matrix) and one setting."""
    m = len(data.hashes)
    if neighbours is None:
        neighbours = nearest(distances, p.k, np.eye(m, dtype=bool))
    idx, dist = neighbours
    own = np.zeros_like(data.fit)
    prob, confidence = evidence(own, data.fit, idx, dist, p)
    has = (idx[:, :p.k] >= 0).any(axis=1)
    placed = place(own, prob, confidence, has, p)
    mask = ~np.isnan(data.labels)
    pairs = int(mask.sum())
    channel = {}
    for c, mood in enumerate(MOODS):
        mc = mask[:, c]
        channel[mood] = auc(prob[mc, c], data.labels[mc, c]) if mc.any() else None
    measured = [a for a in channel.values() if a is not None]
    return Scores(
        auc=float(np.mean(measured)) if measured else None,
        channel_auc=channel,
        brier=float(((prob[mask] - data.labels[mask]) ** 2).mean()) if pairs else None,
        balanced_accuracy=balanced_accuracy(placed, data.labels),
        decided=float((confidence[mask] >= p.min_confidence).mean()) if pairs else None,
        pairs=pairs,
    )


def shuffled_auc(data: Data, distances: np.ndarray, p: Params, rounds: int = 5, seed: int = 7) -> float | None:
    """Mean AUC with songs' audio swapped at random: what "no signal" looks like on this data."""
    rng = np.random.default_rng(seed)
    out = []
    for _ in range(rounds):
        perm = rng.permutation(len(data.hashes))
        s = score(data, distances[np.ix_(perm, perm)], p).auc
        if s is not None:
            out.append(s)
    return float(np.mean(out)) if out else None


def _key(s: Scores) -> float:
    return s.auc if s.auc is not None else -1.0


def compare_spaces(data: Data, weights: dict[str, float], p: Params) -> list[dict]:
    """Each block alone, then all blocks at their own weights."""
    rows = []
    for b in data.space.blocks:
        s = score(data, data.distances({b.id: 1.0}), p)
        rows.append({"space": b.id, "weights": {b.id: 1.0}, **s.as_dict()})
    if len(data.space.blocks) > 1:
        s = score(data, data.distances(weights), p)
        rows.append({"space": "all (current weights)", "weights": dict(weights), **s.as_dict()})
    return rows


def search_weights(data: Data, start: dict[str, float], p: Params, rounds: int = 3) -> tuple[dict[str, float], Scores]:
    """Coordinate ascent over block weights (each in WEIGHT_STEPS) on mean AUC."""
    best_w = dict(start)
    best = score(data, data.distances(best_w), p)
    if len(best_w) < 2:
        return best_w, best
    for _ in range(rounds):
        improved = False
        for b in list(best_w):
            for step in WEIGHT_STEPS:
                trial = {**best_w, b: step}
                if trial == best_w or not any(w > 0 for w in trial.values()):
                    continue
                s = score(data, data.distances(trial), p)
                if _key(s) > _key(best) + 1e-4:
                    best_w, best, improved = trial, s, True
        if not improved:
            break
    return best_w, best


def calibrate(data: Data, distances: np.ndarray, base: Params = Params()) -> dict:
    """Stage 1: τ, k, cap, down weight and priors on the Brier score of P(fit).
    Stage 2: minimum confidence and threshold on balanced accuracy of the placement."""
    m = len(data.hashes)
    neighbours = nearest(distances, max(KS), np.eye(m, dtype=bool))
    stage1 = []
    for tau, k, cap, dw, (a0, b0) in itertools.product(TAUS, KS, CAPS, DOWN_WEIGHTS, PRIORS):
        p = replace(base, tau=tau, k=k, max_weight=cap, down_weight=dw, alpha0=a0, beta0=b0)
        s = score(data, distances, p, neighbours)
        if s.brier is not None:
            stage1.append((s.brier, p, s))
    if not stage1:
        return {"stage1": [], "stage2": [], "best": base}
    stage1.sort(key=lambda t: t[0])
    best1 = stage1[0][1]
    stage2 = []
    for mc, th in itertools.product(MIN_CONFIDENCES, THRESHOLDS):
        p = replace(best1, min_confidence=mc, threshold=th)
        s = score(data, distances, p, neighbours)
        if s.balanced_accuracy is not None:
            stage2.append((s.balanced_accuracy, p, s))
    stage2.sort(key=lambda t: -t[0])
    best = stage2[0][1] if stage2 else best1
    return {"stage1": stage1[:15], "stage2": stage2[:15], "best": best}
