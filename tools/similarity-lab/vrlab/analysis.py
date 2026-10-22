"""`vrlab report`: every Phase 2 question in one run, as a plain dict for the report and JSON."""

from dataclasses import replace

import numpy as np

from vrlab import discover, evaluate
from vrlab.bundle import MOODS, Block, Bundle
from vrlab.propagation import Params
from vrlab.space import Space

# Below these the numbers are mostly noise (docs/similarity-plan.md: "a few hundred thumbs
# spread over every channel").
MIN_RATED_SONGS = 10
ENOUGH_PAIRS = 200


def merge_blocks(phone: list[Block], pc: list[Block]) -> tuple[list[Block], list[dict]]:
    """Phone and PC blocks in one list. The same block from both is merged (phone values win)
    and compared on the songs both have, which is the check docs/audio-features.md asks for
    before trusting a model's frontend."""
    blocks = {b.id: b for b in phone}
    agreement = []
    for b in pc:
        mine = blocks.get(b.id)
        if mine is None:
            blocks[b.id] = b
            continue
        if mine.version != b.version or mine.size != b.size:
            blocks[f"{b.id}-pc"] = replace(b, id=f"{b.id}-pc")
            continue
        both = sorted(set(mine.values) & set(b.values))
        if both:
            x = np.stack([mine.values[h] for h in both]).astype(np.float64)
            y = np.stack([b.values[h] for h in both]).astype(np.float64)
            cos = (x * y).sum(1) / np.maximum(np.linalg.norm(x, axis=1) * np.linalg.norm(y, axis=1), 1e-12)
            scale = np.abs(x).mean() or 1.0
            agreement.append({
                "block": b.key, "songs": len(both), "mean_cosine": float(cos.mean()), "min_cosine": float(cos.min()),
                "mean_abs_diff_rel": float(np.abs(x - y).mean() / scale),
            })
        blocks[b.id] = replace(mine, values={**b.values, **mine.values}, source="phone+pc")
    return list(blocks.values()), agreement


def analyse(bundle: Bundle, pc_blocks: list[Block], only: list[str] | None = None,
            weights: dict[str, float] | None = None, log=print) -> dict:
    fits = bundle.fit_counts()
    blocks, agreement = merge_blocks(bundle.blocks, pc_blocks)
    if only:
        blocks = [b for b in blocks if b.id in only]
    rated_all = {h for h, f in fits.items() if f.sum() > 0}
    block_rows = [{
        "id": b.id, "version": b.version, "dims": b.size, "source": b.source, "weight": b.weight,
        "songs": len(b.values), "rated_songs": len(rated_all & set(b.values)),
    } for b in blocks]
    warnings = []
    for b in [b for b in blocks if not rated_all & set(b.values)]:
        warnings.append(f"Block {b.id} has no song with thumbs; left out. (PC files must be byte-identical to the phone's for their hashes to match.)")
    blocks = [b for b in blocks if rated_all & set(b.values)]

    events = len(bundle.feedback)
    result = {
        "bundle": bundle.path, "app_version": bundle.summary.get("app_version"),
        "exported_at": bundle.summary.get("exported_at"), "channel_names": {m: bundle.name(m) for m in MOODS},
        "feedback_events": events, "rated_songs": len(rated_all), "blocks": block_rows,
        "agreement": agreement, "warnings": warnings,
    }
    if not blocks:
        warnings.append("No audio feature block to analyse: export with the dsp block analysed, or run `vrlab extract`.")
        return result

    space = Space(blocks)
    data = evaluate.Data.of(space, fits)
    labelled = ~np.isnan(data.labels)
    result.update({
        "space_songs": len(space), "space_rated": len(data.hashes),
        "labels": {m: {"fit": int((data.labels[:, c] == 1).sum()), "misfit": int((data.labels[:, c] == 0).sum())} for c, m in enumerate(MOODS)},
        "pairs": int(labelled.sum()),
    })
    if len(space) < sum(len(b.values) for b in blocks) / len(blocks) * 0.5:
        warnings.append(f"Only {len(space)} songs have every block; blocks computed on a subset shrink the space. Use --blocks to compare fewer.")
    if len(data.hashes) < MIN_RATED_SONGS:
        warnings.append(f"Only {len(data.hashes)} analysed songs have mood-channel thumbs; at least {MIN_RATED_SONGS} are needed.")
        return result
    if labelled.sum() < ENOUGH_PAIRS:
        warnings.append(f"{int(labelled.sum())} labelled (song, channel) pairs; the plan wants a few hundred before trusting these numbers.")
    for c, m in enumerate(MOODS):
        if not (data.labels[:, c] == 0).any():
            warnings.append(f"No song has more thumbs down than up on {bundle.name(m)}, so its AUC can't be measured.")

    defaults = Params()
    current = {b.id: b.weight for b in blocks}
    if weights:
        current.update({k: v for k, v in weights.items() if k in current})
    if not any(w > 0 for w in current.values()):
        current = {b.id: 1.0 for b in blocks}
    log("Comparing spaces…")
    result["spaces"] = evaluate.compare_spaces(data, current, defaults)
    result["shuffled_auc"] = evaluate.shuffled_auc(data, data.distances(current), defaults)
    log("Searching block weights…")
    best_w, best_w_score = evaluate.search_weights(data, current, defaults)
    result["weights"] = {"current": current, "best": best_w, "best_score": best_w_score.as_dict()}
    log("Calibrating τ, k, priors and thresholds…")
    distances = data.distances(best_w)
    cal = evaluate.calibrate(data, distances, defaults)
    best = cal["best"]
    result["calibration"] = {
        "stage1": [{"brier": b, "params": p.as_dict(), **s.as_dict()} for b, p, s in cal["stage1"]],
        "stage2": [{"balanced_accuracy": a, "params": p.as_dict(), **s.as_dict()} for a, p, s in cal["stage2"]],
        "best": best.as_dict(), "kotlin": best.kotlin(),
        "app_now": evaluate.score(data, data.distances(current), defaults).as_dict(),
        "defaults_on_best_weights": evaluate.score(data, distances, defaults).as_dict(),
        "best_score": evaluate.score(data, distances, best).as_dict(),
        "defaults": defaults.as_dict(),
    }
    log("Clustering…")
    result["clusters"] = discover.clusters(bundle, space, best_w, fits)
    result["separation"] = discover.separation(space, best_w, fits)
    log("Placing every analysed song…")
    placement = discover.propose(bundle, space, best_w, fits, best)
    result["placement"] = placement
    summary = {}
    for m in MOODS:
        now = sum(1 for r in placement if r["current"] is not None and m in r["current"])
        prop = sum(1 for r in placement if m in r["proposed"])
        added = sum(1 for r in placement if r["current"] is not None and m in r["proposed"] and m not in r["current"])
        removed = sum(1 for r in placement if r["current"] is not None and m not in r["proposed"] and m in r["current"])
        summary[m] = {"now": now, "proposed": prop, "added": added, "removed": removed}
    result["placement_summary"] = summary
    result["placement_unknown_now"] = sum(1 for r in placement if r["current"] is None)
    return result
