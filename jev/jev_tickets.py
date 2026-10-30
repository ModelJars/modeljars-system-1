#!/usr/bin/env python3
"""The six tickets and the three rubric questions of HarrietTicketsApp.java, sent to hosted Jev.

Each ticket is one call with all three questions, and the whole set is sent twice. The API key is
read from the TYPESAFE_API_KEY environment variable and is never written anywhere. Output:
jev-tickets.json in $OUT (default: this directory).
"""
import datetime
import json
import os
import pathlib
import sys
import time
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
OUT = pathlib.Path(os.environ.get("OUT", HERE))
key = os.environ.get("TYPESAFE_API_KEY") or sys.exit("set TYPESAFE_API_KEY in the environment")
URL = "https://api.typesafe.ai/v1/systemone"
MODEL = "jev-1.13.0"
tickets = json.load(open(HERE / "tickets.json"))
questions = {
    "queue": {"type": "choice", "instructions": "Which queue should handle this ticket?", "criteria": {"billing": "charges, invoices, refunds and subscription payments", "technical": "defects, outages, errors, login and integration problems", "sales": "pricing, quotes, plans, seats and upgrades"}},
    "urgent": {"type": "noul", "instructions": "Is this ticket urgent?", "criteria": {"true": "the customer is blocked, locked out, losing money, or being charged wrongly right now", "false": "a question or request that can wait a few days without harm"}},
    "mood": {"type": "score", "instructions": "How frustrated is the customer?", "criteria": ["calm: neutral or friendly wording, a plain question", "frustrated: complains about a problem or a delay", "very angry: hostile wording, threats to leave, or repeated failed attempts"]},
}
levels = ["calm", "frustrated", "very angry"]
out = {"date": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"), "model": MODEL, "calls": []}
for run in ("run1", "run2"):
    for tid, text in tickets.items():
        body = json.dumps({"model": MODEL, "state": text, "questions": questions}).encode()
        req = urllib.request.Request(URL, data=body, headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
        t = time.perf_counter()
        with urllib.request.urlopen(req, timeout=60) as r:
            parsed = json.loads(r.read())
        dt = time.perf_counter() - t
        a = parsed["answers"]
        q = a["queue"]
        m = a["mood"]
        mp = m.get("probabilities", {})
        top = max(mp, key=mp.get) if mp else None
        out["calls"].append({"ticket": tid, "run": run, "seconds": round(dt, 3), "model": parsed.get("model"), "usage": parsed.get("usage"), "answers": a})
        print(f"{tid} {run} {dt*1000:5.0f} ms  queue={q['choice']} ({q['confidence']:.2f})  urgent={a['urgent']['noul']:.2f}  mood={levels[int(top)] if top is not None else '?'} ({mp.get(top, 0):.2f}) score={m.get('score')}")
OUT.mkdir(parents=True, exist_ok=True)
json.dump(out, open(OUT / "jev-tickets.json", "w"), indent=1)
print("written:", OUT / "jev-tickets.json")
