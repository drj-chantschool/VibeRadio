"""Reads a diagnostics bundle (docs/features.md, Diagnostics) exported by Settings → Debug."""

import csv
import json
import os
import re
from dataclasses import dataclass, field

import numpy as np

# Stable channel IDs (dial/ChannelLineup.kt). Learning keys on these, never on display names.
MOODS = ["breeze", "buzz", "zone", "breath", "hall"]
SHUFFLE = "all-songs"
FEATURE_CHANNELS = MOODS + [SHUFFLE]

_BUNDLE_NAME = re.compile(r"^\d{4}-\d{2}-\d{2}_\d{6}$")


@dataclass
class Block:
    """One audio feature block: values by content hash."""

    id: str
    version: int
    names: list[str]
    weight: float
    values: dict[str, np.ndarray]
    source: str  # "phone" or "pc"

    @property
    def key(self) -> str:
        return f"{self.id}@{self.version}"

    @property
    def size(self) -> int:
        return len(self.names)


@dataclass
class Song:
    hash: str
    title: str = ""
    artist: str = ""
    album: str = ""
    genre: str = ""
    folder: str = ""
    duration_ms: int = 0
    path: str = ""  # on the PC, when known


@dataclass
class Bundle:
    path: str
    summary: dict
    channel_names: dict[str, str]
    feedback: list[dict]
    # hash → channel IDs it is on now (rows without a known hash are skipped).
    placement: dict[str, set[str]]
    songs: dict[str, Song]
    blocks: list[Block] = field(default_factory=list)

    def name(self, channel_id: str) -> str:
        return self.channel_names.get(channel_id, channel_id)

    def fit_counts(self) -> dict[str, np.ndarray]:
        """Per song, the feature vector's channel-fit slice rebuilt from the log
        (learning/FeatureVector.kt): [breeze↑, breeze↓, …, hall↑, hall↓]."""
        out: dict[str, np.ndarray] = {}
        for e in self.feedback:
            channel = e["channel_id"]
            if channel not in MOODS:
                continue
            v = out.setdefault(e["track_hash"], np.zeros(len(MOODS) * 2, dtype=np.float64))
            v[MOODS.index(channel) * 2 + (0 if e["thumb"] == "UP" else 1)] += 1
        return out


def resolve(path: str) -> str:
    """The bundle folder: [path] itself, or its latest dated subfolder (names sort by time)."""
    if os.path.exists(os.path.join(path, "summary.json")):
        return path
    dated = sorted(d for d in os.listdir(path) if _BUNDLE_NAME.match(d) and os.path.isdir(os.path.join(path, d)))
    if not dated:
        raise FileNotFoundError(f"no diagnostics bundle (summary.json) in {path}")
    return os.path.join(path, dated[-1])


def _rows(path: str) -> list[dict]:
    if not os.path.exists(path):
        return []
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def load(path: str) -> Bundle:
    path = resolve(path)
    with open(os.path.join(path, "summary.json"), encoding="utf-8") as f:
        summary = json.load(f)
    names: dict[str, str] = {}
    settings_path = os.path.join(path, "settings.json")
    if os.path.exists(settings_path):
        with open(settings_path, encoding="utf-8") as f:
            for c in json.load(f).get("channels", []):
                names[c["id"]] = c.get("name") or c.get("default_name") or c["id"]

    feedback = [r for r in _rows(os.path.join(path, "feedback.csv")) if r.get("track_hash")]

    placement: dict[str, set[str]] = {}
    for r in _rows(os.path.join(path, "channels.csv")):
        if r.get("track_hash"):
            placement.setdefault(r["track_hash"], set()).add(r["channel_id"])

    songs: dict[str, Song] = {}
    for r in _rows(os.path.join(path, "library.csv")):
        if r.get("track_hash"):
            songs[r["track_hash"]] = Song(
                hash=r["track_hash"], title=r.get("title", ""), artist=r.get("artist", ""), album=r.get("album", ""),
                genre=r.get("genre", ""), folder=r.get("folder", ""), duration_ms=int(float(r.get("duration_ms") or 0)),
            )

    versions = {b["id"]: b for b in summary.get("audio_feature_blocks", [])}
    blocks = []
    for file in sorted(os.listdir(path)):
        m = re.match(r"^audio_features_(.+)\.csv$", file)
        if not m:
            continue
        with open(os.path.join(path, file), newline="", encoding="utf-8") as f:
            reader = csv.reader(f)
            header = next(reader, None)
            if not header:
                continue
            values = {}
            for row in reader:
                if not row or not row[0]:
                    continue
                try:
                    values[row[0]] = np.array([float(x) for x in row[1:]], dtype=np.float32)
                except ValueError:
                    continue
        meta = versions.get(m.group(1), {})
        blocks.append(Block(
            id=m.group(1), version=int(meta.get("version", 1)), names=header[1:],
            weight=float(meta.get("similarity_weight", 1.0)), values=values, source="phone",
        ))
    return Bundle(path, summary, names, feedback, placement, songs, blocks)
