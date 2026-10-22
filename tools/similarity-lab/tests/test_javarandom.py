import unittest

import numpy as np

from vrlab.javarandom import JavaRandom, string_hash
from vrlab.space import projection


class JavaRandomTest(unittest.TestCase):
    # Reference values printed by the JDK: new Random(seed).nextGaussian(), "key".hashCode().
    def test_gaussians_match_the_jdk(self):
        r = JavaRandom(42)
        # The JDK takes the log with StrictMath, so the last bit can differ; the app casts to float anyway.
        for got, want in zip([r.next_gaussian() for _ in range(3)], [1.1419053154730547, 0.9194079489827879, -0.9498666368908959]):
            self.assertAlmostEqual(got, want, places=13)

    def test_string_hash_matches_the_jdk(self):
        self.assertEqual(string_hash("dsp@1"), 95887730)
        self.assertEqual(string_hash("effnet@1"), -1008837239)
        self.assertEqual(string_hash("mood_happy@2"), 1650270602)

    def test_projection_rows_are_the_apps(self):
        m = projection("effnet@1", 3, 2)
        np.testing.assert_array_equal(m[0], np.array([-1.831358, -0.102219716, -1.3794864], dtype=np.float32))
        np.testing.assert_array_equal(m[1, :2], np.array([0.6972706, 1.2291842], dtype=np.float32))


if __name__ == "__main__":
    unittest.main()
