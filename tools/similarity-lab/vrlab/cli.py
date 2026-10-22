"""vrlab: the PC side of song similarity (docs/similarity-plan.md, Phase 2).

  vrlab extract MUSIC_DIR --models MODELS_DIR [--work DIR] [--bundle BUNDLE] [--limit N]
  vrlab report BUNDLE [--work DIR] [--out DIR] [--blocks a,b] [--weight id=w ...]
"""

import argparse
import os
import shutil
import sys

from vrlab import __version__, audio, bundle as bundle_mod, extract, report
from vrlab.analysis import analyse
from vrlab.bundle import Song


def _log(msg):
    print(msg, file=sys.stderr, flush=True)


def cmd_extract(a) -> int:
    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            _log(f"{tool} isn't on PATH; install ffmpeg first.")
            return 2
    from vrlab.models import scan_models

    specs, problems = scan_models(a.models)
    for name, why in problems.items():
        _log(f"skipped {name}: {why}")
    if not specs:
        _log(f"no usable model manifest in {a.models}")
        return 2
    files = audio.scan(a.music)
    _log(f"{len(files)} audio files; hashing new or changed ones…")
    songs = audio.SongIndex(a.work).update(files, _log)
    if a.bundle:
        # Songs with thumbs first: they're what the analysis needs most.
        b = bundle_mod.load(a.bundle)
        rated = set(b.fit_counts())
        songs.sort(key=lambda s: s["hash"] not in rated)
        _log(f"{sum(1 for s in songs if s['hash'] in rated)} of them match songs with thumbs in the export")
    done = extract.run(specs, songs, a.work, _log, a.limit)
    _log(f"analysed {done} songs; blocks in {os.path.join(a.work, 'features')}")
    return 0


def cmd_report(a) -> int:
    b = bundle_mod.load(a.bundle)
    _log(f"bundle {b.path}")
    pc = extract.load_blocks(a.work) if a.work else []
    if a.work and os.path.exists(os.path.join(a.work, "songs.csv")):
        for h, row in audio.SongIndex(a.work).by_hash().items():
            song = b.songs.get(h)
            if song is None:
                b.songs[h] = Song(hash=h, title=os.path.splitext(os.path.basename(row["path"]))[0], path=row["path"])
            else:
                song.path = row["path"]
    weights = {}
    for item in a.weight or []:
        k, _, v = item.partition("=")
        weights[k] = float(v)
    only = [x.strip() for x in a.blocks.split(",")] if a.blocks else None
    result = analyse(b, pc, only=only, weights=weights, log=_log)
    out = a.out or os.path.join(a.work or ".", "report")
    path = report.write(result, out)
    for w in result["warnings"]:
        _log(f"note: {w}")
    _log(f"report: {path}")
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="vrlab", description="VibeRadio similarity lab: song similarity and channel placement on a PC.")
    p.add_argument("--version", action="version", version=__version__)
    sub = p.add_subparsers(dest="command", required=True)

    e = sub.add_parser("extract", help="run ONNX feature models over music files on this PC")
    e.add_argument("music", help="folder with the same music files as the phone (byte-identical, so hashes match)")
    e.add_argument("--models", required=True, help="folder with .onnx models and their .properties manifests (as installed on the phone)")
    e.add_argument("--work", default="vrlab-work", help="where hashes and feature blocks are kept (default: vrlab-work)")
    e.add_argument("--bundle", help="diagnostics bundle; songs with thumbs are analysed first")
    e.add_argument("--limit", type=int, help="analyse at most this many songs this run")
    e.set_defaults(func=cmd_extract)

    r = sub.add_parser("report", help="analyse a diagnostics bundle (plus PC blocks) and write report.html")
    r.add_argument("bundle", help="a bundle folder, or the diagnostics folder (the latest bundle is used)")
    r.add_argument("--work", default=None, help="work folder from `vrlab extract`, for PC-computed blocks")
    r.add_argument("--out", help="output folder (default: <work>/report or ./report)")
    r.add_argument("--blocks", help="only these block IDs, comma-separated")
    r.add_argument("--weight", action="append", metavar="ID=W", help="start from this block weight instead of the manifest's (repeatable)")
    r.set_defaults(func=cmd_report)

    a = p.parse_args(argv)
    return a.func(a)


if __name__ == "__main__":
    raise SystemExit(main())
