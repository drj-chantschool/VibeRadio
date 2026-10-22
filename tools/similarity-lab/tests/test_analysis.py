import os
import tempfile
import unittest

import numpy as np

from vrlab import bundle, cli
from vrlab.analysis import analyse
from vrlab.evaluate import auc

from tests.synthetic import write_bundle


class AucTest(unittest.TestCase):
    def test_auc(self):
        self.assertEqual(auc(np.array([0.1, 0.2, 0.8, 0.9]), np.array([0, 0, 1, 1])), 1.0)
        self.assertEqual(auc(np.array([0.5, 0.5]), np.array([0, 1])), 0.5)
        self.assertIsNone(auc(np.array([0.5]), np.array([1])))


class AnalysisTest(unittest.TestCase):
    def test_clustered_songs_predict_their_channels(self):
        with tempfile.TemporaryDirectory() as d:
            write_bundle(d)
            b = bundle.load(d)  # the latest dated subfolder
            r = analyse(b, [], log=lambda _: None)
            self.assertEqual(r["rated_songs"], 100)
            spaces = {s["space"]: s for s in r["spaces"]}
            self.assertGreater(spaces["dsp"]["auc"], 0.9)
            self.assertLess(abs(r["shuffled_auc"] - 0.5), 0.15)
            self.assertGreater(r["calibration"]["best_score"]["balanced_accuracy"], 0.8)
            # Unrated songs (100–149) end up on their own cluster's channel.
            moods = bundle.MOODS
            hits = [moods[i % 5] in p["proposed"] for p in r["placement"] for i in [int(p["hash"][7:], 16)] if i >= 100]
            self.assertGreater(np.mean(hits), 0.9)

    def test_report_command_writes_files(self):
        with tempfile.TemporaryDirectory() as d:
            write_bundle(d)
            out = os.path.join(d, "out")
            self.assertEqual(cli.main(["report", d, "--out", out]), 0)
            for name in ("report.html", "results.json", "placement.csv"):
                self.assertTrue(os.path.getsize(os.path.join(out, name)) > 0, name)

    def test_too_little_feedback_is_reported_not_crashed(self):
        with tempfile.TemporaryDirectory() as d:
            write_bundle(d, rated=3)
            r = analyse(bundle.load(d), [], log=lambda _: None)
            self.assertNotIn("spaces", r)
            self.assertTrue(r["warnings"])


if __name__ == "__main__":
    unittest.main()
