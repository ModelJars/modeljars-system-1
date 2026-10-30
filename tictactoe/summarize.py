#!/usr/bin/env python3
"""Re-derives the tic-tac-toe numbers from the bench's raw jsonl files and prints one markdown table.

    python3 summarize.py ../../article/runs/tictactoe > ../../article/tictactoe-results.md

Every row is recomputed here from the per-decision records (not copied from the bench's own
summary), so the two can be checked against each other. Minimax ground truth is stored in each
record (optimal cells, winning cells, threat cells); only the arithmetic happens here.
"""
import json
import pathlib
import statistics
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
NAMES = ["top-left", "top-center", "top-right", "middle-left", "center", "middle-right", "bottom-left", "bottom-center", "bottom-right"]


def load(path):
    return [json.loads(l) for l in open(path)]


def top(d):
    return NAMES.index(max(d["move"], key=d["move"].get))


def summarize(records):
    n = len(records)
    opt = sum(top(r["d"]) in r["optimal"] for r in records)
    win_avail = [r for r in records if r["oWins"]]
    took = sum(top(r["d"]) in r["oWins"] for r in win_avail)
    block_needed = [r for r in records if not r["oWins"] and r["xThreats"]]
    blocked = sum(top(r["d"]) in r["xThreats"] for r in block_needed)
    ms = sorted(r["d"]["millis"] for r in records)
    games = {}
    # game outcomes are not in the jsonl; they are read from the bench log by the caller
    return dict(n=n, opt=opt, took=took, win_avail=len(win_avail), blocked=blocked, block_needed=len(block_needed),
                ms_med=statistics.median(ms), ms_min=ms[0], ms_max=ms[-1])


def games_from_log(log, arm, rep):
    """Counts 'game vs-random/vs-perfect ... -> result' lines for one arm and representation."""
    out = {"vs-random": [0, 0, 0], "vs-perfect": [0, 0, 0]}
    if not log.exists():
        return out
    for l in open(log, errors="ignore"):
        parts = l.split()
        if len(parts) >= 7 and parts[0] == arm and parts[1] == rep and parts[2] == "game":
            kind = parts[3]
            result = l.split("->")[-1].strip()
            idx = 0 if result.startswith("O") else 1 if result.startswith("draw") else 2
            out[kind][idx] += 1
    return out


print("| arm, machine | board text | decisions | top cell optimal | took a win | blocked | median s per decision | vs random X (W/D/L) | vs perfect X (W/D/L) |")
print("|---|---|---:|---:|---:|---:|---:|---:|---:|")
for machine, label in [("laptop", "Harriet, 2019 laptop"), ("vps", "Harriet, 4-core VPS"), ("jev", "Jev 1.13.0, hosted")]:
    d = root / machine
    if not d.exists():
        continue
    arm = "jev" if machine == "jev" else "harriet"
    for rep in ["GRID", "LIST", "GRID_HINTS", "LIST_HINTS"]:
        f = d / f"{arm}-{rep}.jsonl"
        if not f.exists():
            continue
        s = summarize(load(f))
        g = games_from_log(d / "bench.log", arm, rep)
        print(f"| {label} | {rep} | {s['n']} | {s['opt']}/{s['n']} ({100*s['opt']/s['n']:.0f}%) | {s['took']}/{s['win_avail']} | {s['blocked']}/{s['block_needed']} | "
              f"{s['ms_med']/1000:.1f} | {'/'.join(map(str, g['vs-random']))} | {'/'.join(map(str, g['vs-perfect']))} |")
