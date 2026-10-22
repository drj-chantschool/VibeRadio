"""Songs on the PC: content hashes, durations and excerpts, decoded the way the app does it
(features/AudioClip.kt, ExcerptDecoder.kt): three 30 s excerpts centred at 25/50/75 % (a song
under 90 s whole), averaged to mono, resampled with the app's windowed-sinc resampler.

Decoding goes through ffmpeg/ffprobe, which must be on PATH.
"""

import csv
import hashlib
import json
import math
import os
import subprocess

import numpy as np

AUDIO_EXTENSIONS = {".mp3", ".m4a", ".aac", ".mp4", ".flac", ".ogg", ".oga", ".opus", ".wav", ".wma", ".aif", ".aiff", ".alac", ".ape", ".wv"}
# MIDI is never a song (library/MidiFiles.kt).
MIDI_EXTENSIONS = {".mid", ".midi", ".kar", ".rmi"}


def content_hash(path: str) -> str:
    """ContentHash: "sha256:" + SHA-256 of the file's bytes, the key the app logs songs under."""
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def probe(path: str) -> tuple[int, int, int]:
    """(duration ms, sample rate, channels) of the first audio stream."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
         "stream=sample_rate,channels:format=duration", "-of", "json", path],
        capture_output=True, check=True, text=True,
    ).stdout
    info = json.loads(out)
    stream = info["streams"][0]
    return int(float(info["format"]["duration"]) * 1000), int(stream["sample_rate"]), int(stream["channels"])


def excerpts(duration_ms: int, count: int = 3, length_ms: int = 30_000) -> list[tuple[int, int]]:
    """ExcerptPlan.excerpts: (start ms, length ms) pairs."""
    if duration_ms <= 0:
        return []
    if duration_ms <= count * length_ms:
        return [(0, duration_ms)]
    out = []
    for i in range(1, count + 1):
        centre = duration_ms * i // (count + 1)
        start = min(max(centre - length_ms // 2, 0), duration_ms - length_ms)
        out.append((start, length_ms))
    return out


def decode(path: str, start_ms: int, length_ms: int, rate: int, channels: int) -> np.ndarray:
    """Mono float32 samples of one excerpt at the file's own rate (channels averaged)."""
    raw = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-ss", f"{start_ms / 1000:.3f}", "-t", f"{length_ms / 1000:.3f}",
         "-i", path, "-map", "0:a:0", "-vn", "-ar", str(rate), "-f", "f32le", "-acodec", "pcm_f32le", "-"],
        capture_output=True, check=True,
    ).stdout
    x = np.frombuffer(raw, dtype="<f4")
    if channels > 1:
        x = x[: len(x) // channels * channels].reshape(-1, channels).mean(axis=1)
    return x.astype(np.float32)


_ZEROS = 16
_ROLLOFF = 0.9


def resample(x: np.ndarray, src: int, dst: int, chunk: int = 8192) -> np.ndarray:
    """Resampler.resample: Blackman-windowed sinc, cutoff 0.9 × the lower Nyquist rate, each
    output normalised by the sum of the taps that fall inside the signal.

    Polyphase: output i sits at t = i·src/dst, so its taps repeat every dst/gcd outputs; one
    weight row per phase gives the app's numbers at numpy speed."""
    if src == dst or len(x) == 0:
        return x.astype(np.float32, copy=True)
    ratio = dst / src
    cutoff = 0.5 * _ROLLOFF * min(1.0, ratio)
    half = _ZEROS / (2 * cutoff)
    out_size = len(x) * dst // src
    g = math.gcd(src, dst)
    phases, step = dst // g, src // g
    reach = int(math.ceil(half)) + 1
    offsets = np.arange(-reach, reach + 1)
    pad = reach + 1
    xp = np.concatenate([np.zeros(pad), x.astype(np.float64), np.zeros(pad + step)])
    out = np.zeros(out_size, dtype=np.float32)
    for phase in range(min(phases, out_size)):
        t0 = phase / ratio
        j0 = math.floor(t0)
        d = t0 - (j0 + offsets)
        u = d / half
        p = math.pi * (u + 1)
        w = np.sinc(2 * cutoff * d) * np.where(np.abs(u) < 1, 0.42 - 0.5 * np.cos(p) + 0.08 * np.cos(2 * p), 0.0)
        q = np.arange(phase, out_size, phases) // phases
        result = np.empty(len(q))
        for s in range(0, len(q), chunk):
            j = j0 + q[s:s + chunk, None] * step + offsets[None, :]
            norm = (w * ((j >= 0) & (j < len(x)))).sum(axis=1)
            total = xp[j + pad] @ w
            result[s:s + chunk] = np.where(norm != 0, total / np.where(norm != 0, norm, 1), 0.0)
        out[phase::phases] = result
    return out


def scan(music_dir: str) -> list[str]:
    files = []
    for root, _, names in os.walk(music_dir):
        for name in names:
            ext = os.path.splitext(name)[1].lower()
            if ext in AUDIO_EXTENSIONS and ext not in MIDI_EXTENSIONS:
                files.append(os.path.join(root, name))
    return sorted(files)


class SongIndex:
    """Path → (hash, duration), cached in `<work>/songs.csv` and trusted while size and mtime
    match (like the app's track_hashes table), so a rerun doesn't rehash the library."""

    FIELDS = ["path", "size", "mtime_ns", "hash", "duration_ms"]

    def __init__(self, work_dir: str):
        self.file = os.path.join(work_dir, "songs.csv")
        self.rows: dict[str, dict] = {}
        if os.path.exists(self.file):
            with open(self.file, newline="", encoding="utf-8") as f:
                for r in csv.DictReader(f):
                    self.rows[r["path"]] = r

    def update(self, files: list[str], log=print) -> list[dict]:
        out = []
        for n, path in enumerate(files, 1):
            st = os.stat(path)
            row = self.rows.get(path)
            if not row or int(row["size"]) != st.st_size or int(row["mtime_ns"]) != st.st_mtime_ns:
                try:
                    duration = probe(path)[0]
                except (subprocess.CalledProcessError, KeyError, IndexError, ValueError):
                    log(f"  can't read {path}, skipped")
                    continue
                row = {"path": path, "size": st.st_size, "mtime_ns": st.st_mtime_ns, "hash": content_hash(path), "duration_ms": duration}
                self.rows[path] = row
                if n % 100 == 0:
                    log(f"  hashed {n}/{len(files)}")
            out.append(row)
        self.save()
        return out

    def save(self):
        os.makedirs(os.path.dirname(self.file) or ".", exist_ok=True)
        with open(self.file, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=self.FIELDS)
            w.writeheader()
            for r in sorted(self.rows.values(), key=lambda r: r["path"]):
                w.writerow({k: r[k] for k in self.FIELDS})

    def by_hash(self) -> dict[str, dict]:
        return {r["hash"]: r for r in self.rows.values() if os.path.exists(r["path"])}
