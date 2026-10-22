"""The app's channel-fit models (learning/Models.kt), vectorised over songs.

Fit arrays are songs × 10: [breeze↑, breeze↓, buzz↑, buzz↓, zone↑, zone↓, breath↑, breath↓,
hall↑, hall↓], the FeatureLayout CHANNEL_FIT slice.
"""

from dataclasses import asdict, dataclass

import numpy as np


@dataclass(frozen=True)
class Params:
    """PropagatedChannelFit's knobs plus the Learner's neighbour count. Defaults = the app's."""

    k: int = 20
    tau: float = 0.35
    max_weight: float = 0.8
    down_weight: float = 0.5
    alpha0: float = 1.0
    beta0: float = 1.0
    min_confidence: float = 2.0
    threshold: float = 0.5

    def kotlin(self) -> str:
        return (
            "PropagatedChannelFit(\n"
            f"    tau = {self.tau},\n    maxWeight = {self.max_weight},\n    downWeight = {self.down_weight},\n"
            f"    alpha0 = {self.alpha0},\n    beta0 = {self.beta0},\n"
            f"    minConfidence = {self.min_confidence},\n    threshold = {self.threshold},\n"
            f")\n// Learner(neighbourCount = {self.k})"
        )

    def as_dict(self) -> dict:
        return asdict(self)


def ups(fit: np.ndarray) -> np.ndarray:
    return fit[..., 0::2]


def downs(fit: np.ndarray) -> np.ndarray:
    return fit[..., 1::2]


def nearest(distances: np.ndarray, k: int, exclude: np.ndarray | None = None):
    """Per row, the [k] nearest columns (indices, distances), nearest first. [exclude] marks
    (row, column) pairs that can't be neighbours (the song itself). Missing slots get index -1
    and distance inf."""
    d = distances.astype(np.float64, copy=True)
    if exclude is not None:
        d[exclude] = np.inf
    n, m = d.shape
    kk = min(k, m)
    if kk == 0:
        return np.full((n, k), -1), np.full((n, k), np.inf)
    part = np.argpartition(d, kk - 1, axis=1)[:, :kk] if kk < m else np.tile(np.arange(m), (n, 1))
    pd = np.take_along_axis(d, part, axis=1)
    order = np.argsort(pd, axis=1, kind="stable")
    idx = np.take_along_axis(part, order, axis=1)
    dist = np.take_along_axis(pd, order, axis=1)
    idx = np.where(np.isinf(dist), -1, idx)
    if kk < k:
        idx = np.hstack([idx, np.full((n, k - kk), -1)])
        dist = np.hstack([dist, np.full((n, k - kk), np.inf)])
    return idx, dist


def kernel(distance: np.ndarray, p: Params) -> np.ndarray:
    with np.errstate(over="ignore", invalid="ignore"):
        w = np.minimum(p.max_weight, np.exp(-(distance ** 2) / (p.tau ** 2)))
    return np.where(np.isfinite(distance), w, 0.0)


def evidence(own: np.ndarray, rated_fit: np.ndarray, idx: np.ndarray, dist: np.ndarray, p: Params):
    """Per (song, mood): posterior P(fit) and confidence from own thumbs plus weighted
    neighbour thumbs. [idx]/[dist] come from [nearest] over the rated songs (first p.k used)."""
    idx, dist = idx[:, :p.k], dist[:, :p.k]
    w = kernel(dist, p)
    nb = rated_fit[np.maximum(idx, 0)] * (idx >= 0)[..., None]  # songs × k × 10
    borrowed = (w[..., None] * nb).sum(axis=1)
    up = ups(own) + ups(borrowed)
    misfit = p.down_weight * (downs(own) + downs(borrowed))
    confidence = up + misfit
    prob = (p.alpha0 + up) / (p.alpha0 + p.beta0 + confidence)
    return prob, confidence


def thumb_rule(own: np.ndarray) -> np.ndarray:
    """ThumbRuleChannelFit: any thumbs up there, or tied for the song's fewest thumbs down."""
    d = downs(own)
    return (ups(own) > 0) | (d == d.min(axis=1, keepdims=True))


def place(own: np.ndarray, prob: np.ndarray, confidence: np.ndarray, has_neighbours: np.ndarray, p: Params) -> np.ndarray:
    """PropagatedChannelFit.channelsFor plus LearningModels' coverage rule: songs × 5 booleans."""
    base = thumb_rule(own)
    decided = np.where(confidence < p.min_confidence, base, prob >= p.threshold)
    decided |= ups(own) > 0
    decided = np.where(has_neighbours[:, None], decided, base)
    empty = ~decided.any(axis=1)
    decided[empty] = base[empty]
    return decided
