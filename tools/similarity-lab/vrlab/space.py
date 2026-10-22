"""The app's similarity space (features/Similarity.kt), in numpy.

Each block is z-scored over the songs, blocks over [project_to] dimensions are projected with
the app's own seeded Gaussian matrix, each is scaled to unit length, then weighted by
√(weight / total). The combined cosine is the weighted mean of the per-block cosines, which is
what lets the lab try block weights without rebuilding vectors.
"""

from functools import lru_cache

import numpy as np

from vrlab.javarandom import JavaRandom, string_hash

PROJECT_TO = 64


@lru_cache(maxsize=32)
def projection(key: str, dims: int, to: int = PROJECT_TO) -> np.ndarray:
    """The app's projection for block [key]: `Random(key.hashCode())`, row by row, as floats."""
    random = JavaRandom(string_hash(key))
    return np.array([[random.next_gaussian() for _ in range(dims)] for _ in range(to)], dtype=np.float32)


def transform(key: str, matrix: np.ndarray, project_to: int = PROJECT_TO) -> np.ndarray:
    """One block's rows (songs × dims) standardised, projected if large, at unit length."""
    x = matrix.astype(np.float64)
    mean = x.mean(axis=0)
    std = np.sqrt(np.maximum((x * x).mean(axis=0) - mean * mean, 0.0))
    std[std <= 1e-6] = 1.0
    z = ((x - mean) / std).astype(np.float32)
    if z.shape[1] > project_to:
        z = z @ projection(key, z.shape[1], project_to).T
    norm = np.sqrt((z.astype(np.float64) ** 2).sum(axis=1))
    # A song exactly at the mean has no direction; it stays a zero vector, as in the app.
    safe = np.where(norm < 1e-9, 1.0, norm)
    return (z / safe[:, None]).astype(np.float32)


class Space:
    """Unit vectors per block for a fixed list of songs (the songs that have every block)."""

    def __init__(self, blocks, hashes: list[str] | None = None):
        self.blocks = list(blocks)
        if hashes is None:
            common = set.intersection(*(set(b.values) for b in self.blocks)) if self.blocks else set()
            hashes = sorted(common)
        self.hashes = hashes
        self.index = {h: i for i, h in enumerate(hashes)}
        self.parts = {}
        for b in self.blocks:
            if hashes:
                self.parts[b.id] = transform(b.key, np.stack([b.values[h] for h in hashes]))
            else:
                self.parts[b.id] = np.zeros((0, 1), dtype=np.float32)

    def __len__(self):
        return len(self.hashes)

    def vectors(self, weights: dict[str, float]) -> np.ndarray:
        """Combined unit-ish vectors, as SimilaritySpace.build makes them."""
        used = [b for b in self.blocks if weights.get(b.id, 0) > 0]
        if not used:
            raise ValueError("no block has a weight above 0")
        total = sum(weights[b.id] for b in used)
        return np.hstack([self.parts[b.id] * np.float32(np.sqrt(weights[b.id] / total)) for b in used])

    def similarity(self, block_id: str, rows: np.ndarray, cols: np.ndarray) -> np.ndarray:
        """Cosine similarity of one block between two song subsets (indices into hashes)."""
        p = self.parts[block_id]
        return p[rows] @ p[cols].T

    def distances(self, weights: dict[str, float], rows: np.ndarray, cols: np.ndarray) -> np.ndarray:
        """Combined cosine distance (0..2) between two subsets."""
        used = [b for b in self.blocks if weights.get(b.id, 0) > 0]
        total = sum(weights[b.id] for b in used)
        s = np.zeros((len(rows), len(cols)), dtype=np.float64)
        for b in used:
            s += (weights[b.id] / total) * self.similarity(b.id, rows, cols)
        return 1.0 - s
