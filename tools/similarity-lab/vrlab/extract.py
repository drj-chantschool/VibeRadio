"""`vrlab extract`: run model manifests over the music on a PC (FeaturePipeline), writing each
block to `<work>/features/<id>@<version>.tsv` in the app's own format. Incremental: songs that
already have a block are skipped, so it can be stopped and rerun."""

import json
import os
import subprocess
import time

import numpy as np

from vrlab import audio, store
from vrlab.models import ModelSpec, OnnxExtractor


def order(specs: list[ModelSpec]) -> list[ModelSpec]:
    """Upstream models first; a head whose upstream isn't installed is dropped."""
    by_id = {s.id: s for s in specs}
    out, seen = [], set()

    def visit(s, depth=0):
        if s.id in seen:
            return True
        if depth > len(specs):
            raise ValueError(f"model cycle through '{s.id}'")
        if s.upstream:
            up = by_id.get(s.upstream)
            if up is None or not visit(up, depth + 1):
                return False
        seen.add(s.id)
        out.append(s)
        return True

    for s in specs:
        visit(s)
    return out


def run(specs: list[ModelSpec], songs: list[dict], work_dir: str, log=print, limit: int | None = None) -> int:
    specs = order(specs)
    features_dir = os.path.join(work_dir, "features")
    write_catalogue(specs, features_dir)
    done = {s.key: set(store.read(os.path.join(features_dir, f"{s.key}.tsv"), len(s.names))) for s in specs}
    todo = [song for song in songs if any(song["hash"] not in done[s.key] for s in specs)]
    if limit is not None:
        todo = todo[:limit]
    log(f"{len(todo)} songs to analyse with {', '.join(s.key for s in specs)}")
    extractors = {s.id: OnnxExtractor(s) for s in specs}
    started = time.monotonic()
    analysed = 0
    for n, song in enumerate(todo, 1):
        path, hash_ = song["path"], song["hash"]
        try:
            _, rate, channels = audio.probe(path)
            clips = [audio.decode(path, start, length, rate, channels) for start, length in audio.excerpts(int(song["duration_ms"]))]
        except (subprocess.CalledProcessError, KeyError, IndexError, ValueError) as e:
            log(f"  can't decode {path}: {e}")
            continue
        clips = [c for c in clips if len(c)]
        if not clips:
            continue
        resampled: dict[int, list[np.ndarray]] = {}
        frames: dict[str, list[np.ndarray]] = {}
        for s in specs:
            if s.upstream and s.upstream not in frames:
                continue
            audio_in = [] if s.upstream else resampled.setdefault(s.sample_rate, [audio.resample(c, rate, s.sample_rate) for c in clips])
            try:
                result = extractors[s.id].extract(audio_in, frames.get(s.upstream) if s.upstream else None)
            except Exception as e:  # a model that fails on one song shouldn't stop the run
                log(f"  {s.id} failed on {path}: {e}")
                continue
            if result is None:
                continue
            value, rows = result
            if value.size != len(s.names) or not np.isfinite(value).all():
                continue
            frames[s.id] = rows
            if hash_ not in done[s.key]:
                store.append(os.path.join(features_dir, f"{s.key}.tsv"), hash_, value)
                done[s.key].add(hash_)
        analysed += 1
        if n % 10 == 0 or n == len(todo):
            rate_s = (time.monotonic() - started) / n
            log(f"  {n}/{len(todo)} ({rate_s:.1f} s per song, ~{rate_s * (len(todo) - n) / 60:.0f} min left)")
    return analysed


CATALOGUE = "blocks.json"


def write_catalogue(specs: list[ModelSpec], features_dir: str) -> None:
    """Names and weights of the blocks computed here, merged into `features/blocks.json`, so
    `vrlab report` can label and weight them without the manifests."""
    os.makedirs(features_dir, exist_ok=True)
    path = os.path.join(features_dir, CATALOGUE)
    catalogue = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            catalogue = json.load(f)
    for s in specs:
        catalogue[s.key] = {"id": s.id, "version": s.version, "names": s.names, "similarity_weight": s.weight}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(catalogue, f, indent=2)


def load_blocks(work_dir: str):
    """Every block in `<work>/features`, as bundle.Block with source "pc"."""
    from vrlab.bundle import Block

    features_dir = os.path.join(work_dir, "features")
    if not os.path.isdir(features_dir):
        return []
    catalogue = {}
    path = os.path.join(features_dir, CATALOGUE)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            catalogue = json.load(f)
    blocks = []
    for name in sorted(os.listdir(features_dir)):
        if not name.endswith(".tsv") or "@" not in name:
            continue
        key = name[:-4]
        id_, _, version = key.rpartition("@")
        meta = catalogue.get(key, {})
        values = store.read(os.path.join(features_dir, name), len(meta["names"]) if "names" in meta else None)
        if not values:
            continue
        size = next(iter(values.values())).size
        values = {h: v for h, v in values.items() if v.size == size}
        blocks.append(Block(
            id=id_, version=int(version), names=meta.get("names") or [f"e{i}" for i in range(size)],
            weight=float(meta.get("similarity_weight", 1.0)), values=values, source="pc",
        ))
    return blocks
