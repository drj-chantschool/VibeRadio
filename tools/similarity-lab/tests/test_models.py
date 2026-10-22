import os
import tempfile
import unittest

import numpy as np

from vrlab import store
from vrlab.extract import order
from vrlab.models import MelSpec, ModelSpec, batches, filter_bank, hz_to_mel, mel_spectrogram, mel_to_hz, read_properties, rows, scan_models, units

EFFNET = """
# effnet.properties
id=effnet
version=1
model=effnet.onnx
input=mel
sampleRate=16000
nFft=512
hop=256
nMels=96
scale=slaney
log=log10_1p_10000
patchFrames=128
patchHop=62
inputShape=-1,128,96
outputName=embeddings
dims=1280
"""

HEAD = """
id=mood_happy
version=1
model=head.onnx
input=upstream
upstream=effnet
inputShape=-1,1280
labelsFile=mood_happy.txt
"""


class ModelsTest(unittest.TestCase):
    def test_mel_scales(self):
        self.assertAlmostEqual(hz_to_mel(1000, "slaney"), 15.0)
        for scale in ("slaney", "htk"):
            self.assertAlmostEqual(mel_to_hz(hz_to_mel(3210.0, scale), scale), 3210.0, places=6)

    def test_filter_bank_area_normalised(self):
        spec = MelSpec()
        w = filter_bank(spec)
        self.assertEqual(w.shape, (96, 257))
        bin_hz = spec.sample_rate / spec.n_fft
        # Slaney normalisation: each triangle's area is about 1 (in Hz × weight).
        areas = w.sum(axis=1) * bin_hz
        np.testing.assert_allclose(areas[20:], 1.0, rtol=0.1)

    def test_mel_spectrogram_frames(self):
        spec = MelSpec()
        x = np.sin(2 * np.pi * 1000 * np.arange(16_000) / 16_000).astype(np.float32)
        m = mel_spectrogram(x, spec)
        self.assertEqual(m.shape, ((16_000 - 512) // 256 + 1, 96))
        peak_hz = mel_to_hz(np.linspace(hz_to_mel(0, "slaney"), hz_to_mel(8000, "slaney"), 98)[1 + m.mean(axis=0).argmax()], "slaney")
        self.assertAlmostEqual(peak_hz, 1000, delta=120)

    def test_manifests_parse_and_heads_follow_their_embedding(self):
        with tempfile.TemporaryDirectory() as d:
            for name, text in (("effnet.properties", EFFNET), ("head.properties", HEAD), ("broken.properties", "id=x\n")):
                with open(os.path.join(d, name), "w") as f:
                    f.write(text)
            with open(os.path.join(d, "mood_happy.txt"), "w") as f:
                f.write("happy\nnon_happy\n")
            for model in ("effnet.onnx", "head.onnx"):
                open(os.path.join(d, model), "wb").close()
            specs, problems = scan_models(d)
            self.assertEqual([s.id for s in specs], ["effnet", "mood_happy"])
            self.assertIn("broken.properties", problems)
            effnet, head = specs
            self.assertEqual(effnet.mel.n_mels, 96)
            self.assertEqual(effnet.input_shape, [-1, 128, 96])
            self.assertEqual(head.names, ["happy", "non_happy"])
            self.assertEqual([s.id for s in order([head, effnet])], ["effnet", "mood_happy"])
            self.assertEqual(order([head]), [])

    def test_units_batches_and_rows(self):
        spec = ModelSpec.parse(read_properties_text(EFFNET), ".")
        clip = np.zeros(16_000 * 10, dtype=np.float32)
        us = units(spec, [clip], None)
        frames = (len(clip) - 512) // 256 + 1
        self.assertEqual(len(us), len(range(0, frames - 128 + 1, 62)))
        self.assertEqual(us[0].size, 128 * 96)
        bs = batches(spec.input_shape, us * 20)
        self.assertEqual(bs[0].shape, (32, 128, 96))
        self.assertEqual(sum(b.shape[0] for b in bs), len(us) * 20)
        r = rows(np.array([[0.0, 0.0], [10.0, -10.0]]), 2, "softmax")
        np.testing.assert_allclose(r[0], [0.5, 0.5])
        np.testing.assert_allclose(rows(np.array([0.0]), 1, "sigmoid")[0], [0.5])

    def test_store_round_trips_the_apps_format(self):
        self.assertEqual(store.encode([1.0]), "AACAPw==")
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "x@1.tsv")
            store.append(path, "sha256:a", [1, 2])
            store.append(path, "sha256:a", [3, 4])
            store.append(path, "sha256:b", [5])
            self.assertEqual(list(store.read(path, 2)["sha256:a"]), [3, 4])
            self.assertNotIn("sha256:b", store.read(path, 2))


def read_properties_text(text):
    with tempfile.NamedTemporaryFile("w", suffix=".properties", delete=False) as f:
        f.write(text)
    try:
        return read_properties(f.name)
    finally:
        os.unlink(f.name)


if __name__ == "__main__":
    unittest.main()
