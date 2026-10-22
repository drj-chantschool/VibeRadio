"""The app's model manifests and frontends (features/ModelSpec.kt, Spectral.kt), so the same
`.properties` + `.onnx` files that go on the phone run here, with the same log-mel frontend,
patches, batching and activation. See docs/audio-features.md, Model manifests."""

import math
import os
from dataclasses import dataclass

import numpy as np


# --- Mel frontend -------------------------------------------------------------------------

@dataclass(frozen=True)
class MelSpec:
    sample_rate: int = 16_000
    n_fft: int = 512
    hop: int = 256
    n_mels: int = 96
    f_min: float = 0.0
    f_max: float | None = None
    scale: str = "slaney"
    area_normalize: bool = True
    power: int = 2
    log: str = "log10_1p_10000"


def hz_to_mel(hz: float, scale: str) -> float:
    if scale == "htk":
        return 2595.0 * math.log10(1.0 + hz / 700.0)
    return hz / (200.0 / 3) if hz < 1000.0 else 15.0 + math.log(hz / 1000.0) / (math.log(6.4) / 27.0)


def mel_to_hz(mel: float, scale: str) -> float:
    if scale == "htk":
        return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)
    return mel * (200.0 / 3) if mel < 15.0 else 1000.0 * math.exp((mel - 15.0) * (math.log(6.4) / 27.0))


def filter_bank(spec: MelSpec) -> np.ndarray:
    """MelFilterBank's weights as an n_mels × (n_fft/2 + 1) matrix."""
    bins = spec.n_fft // 2 + 1
    f_max = spec.f_max if spec.f_max is not None else spec.sample_rate / 2.0
    if not (spec.f_min >= 0 and f_max > spec.f_min and f_max <= spec.sample_rate / 2.0):
        raise ValueError(f"mel range {spec.f_min}–{f_max} Hz doesn't fit {spec.sample_rate} Hz")
    lo, hi = hz_to_mel(spec.f_min, spec.scale), hz_to_mel(f_max, spec.scale)
    edges = [mel_to_hz(lo + (hi - lo) * i / (spec.n_mels + 1), spec.scale) for i in range(spec.n_mels + 2)]
    bin_hz = spec.sample_rate / spec.n_fft
    w = np.zeros((spec.n_mels, bins), dtype=np.float32)
    for m in range(spec.n_mels):
        left, centre, right = edges[m], edges[m + 1], edges[m + 2]
        norm = 2.0 / (right - left) if spec.area_normalize else 1.0
        first = max(0, int(left / bin_hz))
        last = min(bins - 1, int(right / bin_hz) + 1)
        for b in range(first, last + 1):
            f = b * bin_hz
            if f <= left or f >= right:
                v = 0.0
            elif f <= centre:
                v = (f - left) / (centre - left)
            else:
                v = (right - f) / (right - centre)
            w[m, b] = v * norm
    return w


def mel_spectrogram(samples: np.ndarray, spec: MelSpec) -> np.ndarray:
    """Frames × n_mels, as MelSpectrogram.of: periodic Hann, frames while a full window fits."""
    n = spec.n_fft
    count = 0 if len(samples) < n else (len(samples) - n) // spec.hop + 1
    if count == 0:
        return np.zeros((0, spec.n_mels), dtype=np.float32)
    window = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / n)
    idx = np.arange(n)[None, :] + spec.hop * np.arange(count)[:, None]
    frames = samples.astype(np.float32)[idx] * window.astype(np.float32)
    spectrum = np.abs(np.fft.rfft(frames.astype(np.float64), axis=1)) ** 2
    if spec.power == 1:
        spectrum = np.sqrt(spectrum)
    mel = (spectrum.astype(np.float32) @ filter_bank(spec).T).astype(np.float32)
    return compress(mel, spec.log)


def compress(mel: np.ndarray, log: str) -> np.ndarray:
    if log == "none":
        return mel
    if log == "ln":
        return np.log(mel + np.float32(1e-6))
    if log == "log10_1p_10000":
        return np.log10(np.float32(1) + np.float32(10_000) * mel)
    if log == "db":
        db = 10 * np.log10(mel + np.float32(1e-10))
        return np.maximum(db, db.max() - 80) if db.size else db
    raise ValueError(f"unknown log compression '{log}'")


# --- Manifests ----------------------------------------------------------------------------

def read_properties(path: str) -> dict[str, str]:
    """Java .properties, the subset manifests use: key=value or key: value, # and ! comments."""
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line[0] in "#!":
                continue
            sep = min((i for i in (line.find("="), line.find(":")) if i >= 0), default=-1)
            if sep < 0:
                continue
            out[line[:sep].strip()] = line[sep + 1:].strip()
    return out


@dataclass
class ModelSpec:
    id: str
    version: int
    names: list[str]
    weight: float
    model_file: str
    input: str  # waveform | mel | upstream
    sample_rate: int
    window: int
    hop: int
    mel: MelSpec | None
    patch_frames: int
    patch_hop: int
    upstream: str | None
    input_name: str | None
    input_shape: list[int]
    output_name: str | None
    activation: str
    dir: str

    @property
    def key(self) -> str:
        return f"{self.id}@{self.version}"

    @classmethod
    def parse(cls, props: dict[str, str], dir: str) -> "ModelSpec":
        def s(k):
            v = props.get(k, "").strip()
            return v or None

        def req(k):
            v = s(k)
            if v is None:
                raise ValueError(f"missing '{k}'")
            return v

        def i(k, default=None):
            v = s(k)
            if v is None:
                if default is None:
                    raise ValueError(f"'{k}' must be a whole number")
                return default
            return int(v)

        if s("labelsFile"):
            with open(os.path.join(dir, s("labelsFile")), encoding="utf-8") as f:
                names = [x.strip() for x in f if x.strip()]
        elif s("labels"):
            names = [x.strip() for x in s("labels").split("|")]
        elif s("dims"):
            names = [f"e{n}" for n in range(int(s("dims")))]
        else:
            raise ValueError("needs 'dims', 'labels' or 'labelsFile'")
        kind = req("input").lower()
        mel = None
        rate = window = hop = frames = patch_hop = 0
        if kind == "waveform":
            rate, window = i("sampleRate"), i("window")
            hop = i("hop", window)
        elif kind == "mel":
            rate = i("sampleRate")
            mel = MelSpec(
                sample_rate=rate, n_fft=i("nFft"), hop=i("hop"), n_mels=i("nMels"),
                f_min=float(s("fMin") or 0.0), f_max=float(s("fMax")) if s("fMax") else None,
                scale=(s("scale") or "slaney").lower(),
                area_normalize=(s("areaNormalize") or "true").lower() == "true",
                power=i("power", 2), log=(s("log") or "log10_1p_10000").lower(),
            )
            frames = i("patchFrames")
            patch_hop = i("patchHop", frames)
        elif kind != "upstream":
            raise ValueError("'input' must be waveform, mel or upstream")
        shape = [int(x.strip()) for x in req("inputShape").split(",")]
        if shape.count(-1) > 1 or any(x != -1 and x <= 0 for x in shape):
            raise ValueError(f"bad inputShape {shape}")
        return cls(
            id=req("id"), version=i("version"), names=names, weight=float(s("similarityWeight") or 1.0),
            model_file=req("model"), input=kind, sample_rate=rate, window=window, hop=hop, mel=mel,
            patch_frames=frames, patch_hop=patch_hop, upstream=s("upstream") if kind == "upstream" else None,
            input_name=s("inputName"), input_shape=shape, output_name=s("outputName"),
            activation=(s("activation") or "none").lower(), dir=dir,
        )


def scan_models(dir: str) -> tuple[list[ModelSpec], dict[str, str]]:
    """Every usable manifest in [dir], and the rest with the reason (ModelSpec.scan)."""
    specs, problems = [], {}
    for name in sorted(f for f in os.listdir(dir) if f.endswith(".properties")):
        try:
            spec = ModelSpec.parse(read_properties(os.path.join(dir, name)), dir)
        except (ValueError, OSError) as e:
            problems[name] = str(e)
            continue
        if not os.path.isfile(os.path.join(dir, spec.model_file)):
            problems[name] = f"model file '{spec.model_file}' missing"
        elif any(s.id == spec.id for s in specs):
            problems[name] = f"duplicate id '{spec.id}'"
        else:
            specs.append(spec)
    return specs, problems


# --- Units, batches, rows (ModelFrontend) -------------------------------------------------

def _windows(length: int, size: int, hop: int) -> list[int]:
    return [0] if length <= size else list(range(0, length - size + 1, hop))


def units(spec: ModelSpec, clips: list[np.ndarray], upstream: list[np.ndarray] | None) -> list[np.ndarray]:
    if spec.input == "upstream":
        return list(upstream or [])
    out = []
    for clip in clips:
        if spec.input == "waveform":
            for start in _windows(len(clip), spec.window, spec.hop):
                u = np.zeros(spec.window, dtype=np.float32)
                piece = clip[start:start + spec.window]
                u[:len(piece)] = piece
                out.append(u)
        else:
            frames = mel_spectrogram(clip, spec.mel)
            for start in _windows(len(frames), spec.patch_frames, spec.patch_hop):
                patch = np.zeros((spec.patch_frames, spec.mel.n_mels), dtype=np.float32)
                piece = frames[start:start + spec.patch_frames]
                patch[:len(piece)] = piece
                out.append(patch.reshape(-1))
    return out


def batches(shape: list[int], items: list[np.ndarray], max_batch: int = 32) -> list[np.ndarray]:
    if not items:
        return []
    fixed = math.prod(x for x in shape if x != -1)
    if fixed != items[0].size:
        raise ValueError(f"inputShape {shape} doesn't hold a unit of {items[0].size} values")
    if -1 not in shape:
        return [u.reshape(shape) for u in items]
    out = []
    for s in range(0, len(items), max_batch):
        chunk = items[s:s + max_batch]
        out.append(np.stack(chunk).reshape([len(chunk) if x == -1 else x for x in shape]).astype(np.float32))
    return out


def rows(output: np.ndarray, dims: int, activation: str) -> list[np.ndarray]:
    flat = np.asarray(output, dtype=np.float32).reshape(-1)
    if flat.size == 0 or flat.size % dims:
        raise ValueError(f"output of {flat.size} values isn't rows of {dims}")
    r = flat.reshape(-1, dims).astype(np.float64)
    if activation == "sigmoid":
        r = 1 / (1 + np.exp(-r))
    elif activation == "softmax":
        e = np.exp(r - r.max(axis=1, keepdims=True))
        r = e / e.sum(axis=1, keepdims=True)
    return list(r.astype(np.float32))


class OnnxExtractor:
    """OnnxModelExtractor: one model, CPU, outputs averaged over every unit."""

    def __init__(self, spec: ModelSpec):
        import onnxruntime  # only `vrlab extract` needs it

        self.spec = spec
        self.session = onnxruntime.InferenceSession(os.path.join(spec.dir, spec.model_file), providers=["CPUExecutionProvider"])
        self.input_name = spec.input_name or self.session.get_inputs()[0].name
        self.output_name = spec.output_name or self.session.get_outputs()[0].name

    def extract(self, clips, upstream):
        us = units(self.spec, clips, upstream)
        if not us:
            return None
        out_rows = []
        for batch in batches(self.spec.input_shape, us):
            result = self.session.run([self.output_name], {self.input_name: batch})[0]
            out_rows += rows(result, len(self.spec.names), self.spec.activation)
        return np.mean(out_rows, axis=0).astype(np.float32), out_rows
