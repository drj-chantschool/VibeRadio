import unittest

import numpy as np

from vrlab.propagation import Params, evidence, kernel, nearest, place, thumb_rule


def fit(**counts):
    """fit(breeze=(ups, downs), ...)"""
    moods = ["breeze", "buzz", "zone", "breath", "hall"]
    v = np.zeros(10)
    for m, (u, d) in counts.items():
        v[moods.index(m) * 2] = u
        v[moods.index(m) * 2 + 1] = d
    return v


class PropagationTest(unittest.TestCase):
    def test_thumb_rule(self):
        own = np.stack([fit(), fit(zone=(1, 0), breeze=(0, 2), buzz=(0, 1))])
        r = thumb_rule(own)
        self.assertTrue(r[0].all())  # no feedback: all five
        self.assertEqual(list(r[1]), [False, False, True, True, True])

    def test_kernel_is_capped(self):
        p = Params()
        self.assertAlmostEqual(kernel(np.array([0.0]), p)[0], 0.8)
        self.assertAlmostEqual(kernel(np.array([0.35]), p)[0], np.exp(-1))
        self.assertEqual(kernel(np.array([np.inf]), p)[0], 0)

    def test_close_neighbours_thumbs_down_move_a_song_off(self):
        p = Params()
        rated = np.stack([fit(breeze=(0, 3))] * 3)
        idx = np.array([[0, 1, 2]])
        dist = np.zeros((1, 3))
        own = np.zeros((1, 10))
        prob, conf = evidence(own, rated, idx, dist, p)
        # misfit = 0.5 · 3 songs · 0.8 · 3 = 3.6 ≥ 2: decided, P = 1 / 5.6
        self.assertAlmostEqual(conf[0, 0], 3.6)
        self.assertAlmostEqual(prob[0, 0], 1 / 5.6)
        placed = place(own, prob, conf, np.array([True]), p)
        self.assertEqual(list(placed[0]), [False, True, True, True, True])

    def test_own_thumbs_up_always_keeps_a_song(self):
        p = Params()
        rated = np.stack([fit(breeze=(0, 10))] * 3)
        own = np.stack([fit(breeze=(1, 0))])
        prob, conf = evidence(own, rated, np.array([[0, 1, 2]]), np.zeros((1, 3)), p)
        self.assertTrue(place(own, prob, conf, np.array([True]), p)[0, 0])

    def test_nearest_skips_self_and_pads(self):
        d = np.array([[0.0, 0.3, 0.1], [0.3, 0.0, 0.2]])
        idx, dist = nearest(d, 3, np.array([[True, False, False], [False, True, False]]))
        self.assertEqual(list(idx[0]), [2, 1, -1])
        self.assertEqual(list(idx[1]), [2, 0, -1])
        self.assertTrue(np.isinf(dist[0, 2]))


if __name__ == "__main__":
    unittest.main()
