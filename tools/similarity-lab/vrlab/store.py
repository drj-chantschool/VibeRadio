"""Feature block files in the app's own format (FileAudioFeatureStore): `<id>@<version>.tsv`,
one `hash<TAB>base64 little-endian float32s` line per song, the last line for a hash winning.
So blocks computed here can be compared with, or copied next to, the phone's."""

import base64
import os

import numpy as np


def encode(value) -> str:
    return base64.b64encode(np.asarray(value, dtype="<f4").tobytes()).decode("ascii")


def decode(text: str) -> np.ndarray:
    return np.frombuffer(base64.b64decode(text), dtype="<f4").astype(np.float32)


def read(path: str, size: int | None = None) -> dict[str, np.ndarray]:
    out: dict[str, np.ndarray] = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            tab = line.find("\t")
            if tab <= 0:
                continue
            try:
                value = decode(line[tab + 1:])
            except ValueError:
                continue
            if size is None or value.size == size:
                out[line[:tab]] = value
    return out


def append(path: str, hash_: str, value) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write(f"{hash_}\t{encode(value)}\n")
