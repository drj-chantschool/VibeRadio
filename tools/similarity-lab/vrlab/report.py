"""Writes the results of `vrlab report`: report.html (self-contained), results.json and
placement.csv."""

import csv
import html
import json
import os

from vrlab.bundle import MOODS

CSS = """
:root { --bg:#fbfbf8; --fg:#1d1d1b; --muted:#6b6b66; --line:#dddcd5; --bar:#8a8a82; --band:#f0efe9; }
@media (prefers-color-scheme: dark) { :root { --bg:#171716; --fg:#e4e3dc; --muted:#9a9a93; --line:#34342f; --bar:#7d7d76; --band:#22221f; } }
body { background:var(--bg); color:var(--fg); font:15px/1.5 system-ui,sans-serif; margin:0; padding:24px 16px 64px; }
main { max-width:1100px; margin:0 auto; }
h1 { font-size:24px; margin:0 0 4px; } h2 { font-size:19px; margin:36px 0 8px; border-bottom:1px solid var(--line); padding-bottom:4px; }
h3 { font-size:16px; margin:20px 0 6px; }
p, li { max-width:75ch; } .muted { color:var(--muted); }
.scroll { overflow-x:auto; }
table { border-collapse:collapse; margin:8px 0; font-variant-numeric:tabular-nums; }
th, td { padding:4px 10px; border-bottom:1px solid var(--line); text-align:right; white-space:nowrap; }
th:first-child, td:first-child, td.l, th.l { text-align:left; }
thead th { background:var(--band); font-weight:600; }
.bar { display:inline-block; height:8px; background:var(--bar); vertical-align:middle; margin-right:6px; border-radius:1px; }
pre { background:var(--band); padding:10px 12px; overflow-x:auto; border-radius:3px; }
.warn { border-left:3px solid var(--bar); padding:6px 12px; background:var(--band); }
"""


def _f(x, digits=3):
    if x is None:
        return "–"
    if isinstance(x, float):
        return f"{x:.{digits}f}"
    return html.escape(str(x))


def _auc(x):
    if x is None:
        return "–"
    width = max(0, (x - 0.5) * 2) * 60
    return f'<span class="bar" style="width:{width:.0f}px"></span>{x:.3f}'


def _table(header, rows, left=1):
    head = "".join(f'<th class="{"l" if i < left else ""}">{html.escape(h)}</th>' for i, h in enumerate(header))
    body = "".join("<tr>" + "".join(f'<td class="{"l" if i < left else ""}">{c}</td>' for i, c in enumerate(r)) + "</tr>" for r in rows)
    return f'<div class="scroll"><table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>'


def _scores_row(label, s, names):
    return [html.escape(label), _auc(s["auc"])] + [_auc(s["channel_auc"].get(m)) for m in MOODS] + [
        _f(s["brier"]), _f(s["balanced_accuracy"]), _f(s["decided"] * 100 if s["decided"] is not None else None, 0) + "%", s["pairs"]]


def _scores_header(names):
    return ["", "AUC (mean)"] + [f"AUC {names[m]}" for m in MOODS] + ["Brier", "Bal. acc.", "Decided", "Pairs"]


def render(r: dict) -> str:
    names = r["channel_names"]
    out = [f"<h1>VibeRadio similarity lab</h1><p class='muted'>Bundle {html.escape(os.path.basename(r['bundle']))}, "
           f"app {html.escape(str(r.get('app_version')))}, exported {html.escape(str(r.get('exported_at')))}</p>"]
    if r["warnings"]:
        out.append("<div class='warn'><ul>" + "".join(f"<li>{html.escape(w)}</li>" for w in r["warnings"]) + "</ul></div>")

    out.append("<h2>Data</h2>")
    out.append(f"<p>{r['feedback_events']} thumbs presses; {r['rated_songs']} songs with mood-channel thumbs. "
               + (f"{r['space_songs']} songs have every block below, {r['space_rated']} of them rated, "
                  f"{r['pairs']} labelled (song, channel) pairs." if "space_songs" in r else "") + "</p>")
    out.append(_table(["Block", "Version", "Dims", "From", "Weight", "Songs", "Rated songs"],
                      [[html.escape(b["id"]), b["version"], b["dims"], b["source"], _f(b["weight"], 2), b["songs"], b["rated_songs"]] for b in r["blocks"]]))
    if "labels" in r:
        out.append("<p class='muted'>A pair is labelled <i>fit</i> when the song has more thumbs up than down on that channel, <i>misfit</i> when more down; ties are left out.</p>")
        out.append(_table(["Channel", "Fit", "Misfit"], [[html.escape(names[m]), v["fit"], v["misfit"]] for m, v in r["labels"].items()]))
    if r["agreement"]:
        out.append("<h3>Phone vs PC, same block</h3><p class='muted'>Songs computed on both. A cosine near 1 means the PC frontend matches the phone's.</p>")
        out.append(_table(["Block", "Songs", "Mean cosine", "Min cosine", "Mean |diff| (rel.)"],
                          [[html.escape(a["block"]), a["songs"], _f(a["mean_cosine"], 4), _f(a["min_cosine"], 4), _f(a["mean_abs_diff_rel"], 4)] for a in r["agreement"]]))
    if "spaces" not in r:
        return _page(out)

    out.append("<h2>Does similarity predict channel fit?</h2>")
    out.append("<p>Leave-one-out: each rated song's own thumbs are hidden and its fit on each channel is predicted from its neighbours' thumbs with the app's current model. "
               "AUC per channel: how well P(fit) ranks the songs that fit it above those that don't (0.5 is chance, 1 perfect); the headline is their mean. Brier: lower is better. Balanced accuracy of the actual placement (with the confidence fallback, which puts an unsure song on the channel). "
               "Decided: share of pairs where neighbours carried enough evidence to decide.</p>")
    rows = [_scores_row(s["space"], s, names) for s in r["spaces"]]
    out.append(_table(_scores_header(names), rows))
    out.append(f"<p class='muted'>Same data with songs' audio shuffled (no signal): AUC {_f(r['shuffled_auc'])}.</p>")

    w = r["weights"]
    out.append("<h2>Which representation to ship</h2>")
    out.append("<p>Block weights searched (each 0, 0.5, 1 or 2) for the best mean AUC with the app's current model settings.</p>")
    out.append(_table(["Block", "Now", "Best"], [[html.escape(b), _f(w["current"][b], 2), _f(w["best"].get(b, 0), 2)] for b in w["current"]]))
    lines = "\n".join(f"{b}: similarityWeight={v:g}" for b, v in w["best"].items())
    kotlin = "SimilaritySpace(weights = mapOf(" + ", ".join(f'"{b}" to {v:.1f}' for b, v in w["best"].items()) + "))"
    out.append(f"<p>In each block's manifest:</p><pre>{html.escape(lines)}</pre><p>or in <code>AppContainer</code>:</p><pre>{html.escape(kotlin)}</pre>")

    c = r["calibration"]
    out.append("<h2>Calibration</h2>")
    out.append("<p>Stage 1 picks τ, k, the weight cap, the thumbs-down weight and the priors on the Brier score of P(fit); stage 2 picks the confidence floor and threshold on balanced accuracy. "
               "Chosen and scored on the same songs, so expect the live numbers a little lower.</p>")
    out.append(_table(_scores_header(names), [
        _scores_row("App now (current weights, defaults)", c["app_now"], names),
        _scores_row("Best weights, default settings", c["defaults_on_best_weights"], names),
        _scores_row("Best weights, calibrated", c["best_score"], names),
    ]))
    keys = list(c["best"])
    out.append(_table(["Setting"] + ["App default", "Calibrated"], [[k, _f(c["defaults"][k], 2), _f(c["best"][k], 2)] for k in keys]))
    out.append(f"<p>In <code>AppContainer</code>'s <code>LearningModels</code>:</p><pre>{html.escape(c['kotlin'])}</pre>")
    out.append("<h3>Stage 1, best 15</h3>")
    out.append(_table(["Brier", "AUC", "k", "τ", "Cap", "Down w.", "α₀", "β₀"], [
        [_f(s["brier"], 4), _f(s["auc"]), s["params"]["k"], s["params"]["tau"], s["params"]["max_weight"], s["params"]["down_weight"], s["params"]["alpha0"], s["params"]["beta0"]]
        for s in c["stage1"]]))
    out.append("<h3>Stage 2, best 15</h3>")
    out.append(_table(["Bal. acc.", "Min confidence", "Threshold", "Decided"], [
        [_f(s["balanced_accuracy"]), s["params"]["min_confidence"], s["params"]["threshold"], _f(s["decided"] * 100 if s["decided"] is not None else None, 0) + "%"]
        for s in c["stage2"]]))

    cl = r["clusters"]
    out.append("<h2>Channel discovery</h2>")
    out.append(f"<p>Spherical k-means over the best-weighted space. Silhouette (cosine, on {cl['sampled']} songs): higher means cleaner clusters. "
               "The tables show where each cluster's thumbs went; a cluster whose thumbs up fall on one channel supports that channel.</p>")
    out.append(_table(["Clusters", "Silhouette"], [[s["k"], _f(s["silhouette"])] for s in cl["scan"]]))
    for k, rows in cl["tables"].items():
        out.append(f"<h3>{k} clusters</h3>")
        out.append(_table(["Cluster", "Songs"] + [f"{names[m]} ↑/↓" for m in MOODS] + ["Top genres", "Examples"], [
            [row["cluster"], row["songs"]] + [f"{row['up'][m]}/{row['down'][m]}" for m in MOODS]
            + [f"<span class='l'>{html.escape(', '.join(row['genres']))}</span>", html.escape("; ".join(row["examples"]))]
            for row in rows], left=1))
    sep = r["separation"]
    out.append("<h3>Distance between channels</h3><p class='muted'>Mean cosine distance between songs thumbed up on the row channel and on the column channel; "
               "the diagonal (within a channel) should be clearly below the rest.</p>")
    out.append(_table([""] + [names[m] for m in MOODS] + ["Songs"], [
        [html.escape(names[a])] + [_f(sep["matrix"][a][b]) for b in MOODS] + [sep["sizes"][a]] for a in MOODS]))

    out.append("<h2>Proposed placement</h2>")
    out.append("<p>Every analysed song placed by the calibrated model (own thumbs plus neighbours'), against channels.csv. "
               f"Full list in placement.csv. {r['placement_unknown_now']} songs had no hash in the export yet (never played), so their current channels are unknown.</p>")
    out.append(_table(["Channel", "Now", "Proposed", "Added", "Removed"], [
        [html.escape(names[m]), v["now"], v["proposed"], v["added"], v["removed"]] for m, v in r["placement_summary"].items()]))
    changed = sorted((p for p in r["placement"] if p["changed"]), key=lambda p: -p["thumbs"])[:40]
    if changed:
        out.append("<h3>Changed songs (most thumbed first, up to 40)</h3>")
        out.append(_table(["Song", "Thumbs", "Now", "Proposed"], [
            [html.escape(f"{p['artist']} – {p['title']}" if p["title"] else p["hash"][:19]), p["thumbs"],
             html.escape(", ".join(names[m] for m in p["current"])), html.escape(", ".join(names[m] for m in p["proposed"]))]
            for p in changed]))

    out.append("<h2>Not answered yet</h2><ul><li>Implicit signals (knob turns mid-song): the play and tune logs aren't exported yet.</li>"
               "<li>Wrong channel events (#16) aren't logged yet, so only thumbs count as evidence.</li>"
               "<li>Seasonal weighting: thumbs are counted unweighted, as the app does today.</li></ul>")
    return _page(out)


def _page(parts) -> str:
    return ("<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            f"<title>Similarity lab</title><style>{CSS}</style></head><body><main>" + "\n".join(parts) + "</main></body></html>\n")


def write(r: dict, out_dir: str) -> str:
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "results.json"), "w", encoding="utf-8") as f:
        json.dump({k: v for k, v in r.items() if k != "placement"}, f, indent=2, default=str)
    if r.get("placement"):
        with open(os.path.join(out_dir, "placement.csv"), "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["track_hash", "artist", "title", "path", "thumbs", "current", "proposed", "changed"]
                       + [f"p_fit_{m}" for m in MOODS] + [f"confidence_{m}" for m in MOODS])
            for p in r["placement"]:
                w.writerow([p["hash"], p["artist"], p["title"], p["path"], p["thumbs"],
                            "" if p["current"] is None else " ".join(p["current"]), " ".join(p["proposed"]),
                            "" if p["changed"] is None else p["changed"]]
                           + [p["p_fit"][m] for m in MOODS] + [p["confidence"][m] for m in MOODS])
    path = os.path.join(out_dir, "report.html")
    with open(path, "w", encoding="utf-8") as f:
        f.write(render(r))
    return path
