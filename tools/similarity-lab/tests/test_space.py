import unittest

import numpy as np

from vrlab.bundle import Block
from vrlab.space import Space, transform


def block(id_, rows, weight=1.0):
    return Block(id_, 1, [f"x{i}" for i in range(rows.shape[1])], weight, {f"h{i}": r.astype(np.float32) for i, r in enumerate(rows)}, "phone")


class SpaceTest(unittest.TestCase):
    def test_transform_gives_unit_rows(self):
        rows = np.random.default_rng(1).normal(size=(20, 5))
        t = transform("a@1", rows)
        np.testing.assert_allclose(np.linalg.norm(t, axis=1), 1, atol=1e-5)

    def test_constant_dimension_doesnt_divide_by_zero(self):
        rows = np.random.default_rng(1).normal(size=(10, 3))
        rows[:, 1] = 7
        self.assertTrue(np.isfinite(transform("a@1", rows)).all())

    def test_large_blocks_are_projected(self):
        rows = np.random.default_rng(1).normal(size=(10, 100))
        self.assertEqual(transform("big@1", rows).shape, (10, 64))

    def test_combined_cosine_is_weighted_mean_of_block_cosines(self):
        rng = np.random.default_rng(2)
        a, b = block("a", rng.normal(size=(12, 4))), block("b", rng.normal(size=(12, 6)))
        s = Space([a, b])
        weights = {"a": 1.0, "b": 3.0}
        v = s.vectors(weights)
        idx = np.arange(len(s))
        np.testing.assert_allclose(1 - v @ v.T, s.distances(weights, idx, idx), atol=1e-5)

    def test_only_songs_with_every_block(self):
        rng = np.random.default_rng(3)
        a = block("a", rng.normal(size=(5, 2)))
        b = block("b", rng.normal(size=(3, 2)))
        self.assertEqual(Space([a, b]).hashes, ["h0", "h1", "h2"])


if __name__ == "__main__":
    unittest.main()
