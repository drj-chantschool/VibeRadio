import os
import shutil
import subprocess
import tempfile
import unittest

import numpy as np

from vrlab import audio


class AudioTest(unittest.TestCase):
    def test_excerpts_match_the_app(self):
        self.assertEqual(audio.excerpts(240_000), [(45_000, 30_000), (105_000, 30_000), (165_000, 30_000)])
        self.assertEqual(audio.excerpts(80_000), [(0, 80_000)])
        self.assertEqual(audio.excerpts(0), [])

    def test_resample_keeps_a_tone_and_drops_what_is_above_nyquist(self):
        src, dst = 44_100, 16_000
        t = np.arange(src) / src
        low = audio.resample(np.sin(2 * np.pi * 440 * t).astype(np.float32), src, dst)
        high = audio.resample(np.sin(2 * np.pi * 12_000 * t).astype(np.float32), src, dst)
        self.assertEqual(len(low), dst)
        mid = slice(1000, -1000)
        ref = np.sin(2 * np.pi * 440 * np.arange(dst) / dst)
        self.assertLess(np.abs(low[mid] - ref[mid]).max(), 0.01)
        self.assertLess(np.abs(high[mid]).max(), 0.01)

    def test_content_hash_is_prefixed_sha256(self):
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(b"abc")
        try:
            self.assertEqual(audio.content_hash(f.name), "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        finally:
            os.unlink(f.name)

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "needs ffmpeg")
    def test_decodes_an_excerpt_to_mono(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "tone.wav")
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=3:sample_rate=22050",
                            "-ac", "2", path], check=True)
            duration, rate, channels = audio.probe(path)
            self.assertEqual((rate, channels), (22050, 2))
            self.assertAlmostEqual(duration, 3000, delta=20)
            x = audio.decode(path, 1000, 1000, rate, channels)
            self.assertAlmostEqual(len(x), 22050, delta=50)
            self.assertGreater(np.abs(x).max(), 0.05)


if __name__ == "__main__":
    unittest.main()
