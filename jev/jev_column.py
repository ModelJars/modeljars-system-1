#!/usr/bin/env python3
"""The article's demo questions sent to TypeSafe's hosted Jev, timing each call from the client.

The same state and question strings as HarrietDemoTimed.java (section A) and HarrietHello.java /
LayaHello.java (section B). The API key is read from the TYPESAFE_API_KEY environment variable and
is never written anywhere. Output: jev-column.json in $OUT (default: this directory).
"""
import datetime
import json
import os
import pathlib
import platform
import sys
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
OUT = pathlib.Path(os.environ.get("OUT", HERE))
key = os.environ.get("TYPESAFE_API_KEY") or sys.exit("set TYPESAFE_API_KEY in the environment")
URL = "https://api.typesafe.ai/v1/systemone"
MODEL = ["jev-1.13.0", "jev-latest"]  # pinned first; falls back if the API no longer serves it
out = {
    "date": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "client": platform.platform(),
    "note": "wall time measured around the HTTP round trip from the client; includes network and TLS",
    "calls": [],
}
model_used = None


def call(label, state, questions):
    global model_used
    for m in ([model_used] if model_used else MODEL):
        body = json.dumps({"model": m, "state": state, "questions": questions}).encode()
        req = urllib.request.Request(URL, data=body, headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
        t = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                parsed = json.loads(r.read())
        except urllib.error.HTTPError as e:
            msg = e.read().decode(errors="ignore")[:300]
            if m != MODEL[-1] and e.code in (400, 404, 422):
                print(f"  model {m} rejected ({e.code}); falling back", file=sys.stderr)
                continue
            out["calls"].append({"label": label, "error": f"HTTP {e.code}: {msg}"})
            print(label, "ERROR", e.code, msg[:120])
            return None
        dt = time.perf_counter() - t
        model_used = m
        rec = {"label": label, "model": parsed.get("model"), "seconds": round(dt, 3), "usage": parsed.get("usage"), "answers": parsed.get("answers")}
        out["calls"].append(rec)
        print(f"{label:52s} {dt*1000:7.0f} ms  {summ(parsed.get('answers'))}")
        return rec


def summ(a):
    if not a:
        return ""
    parts = []
    for k, v in a.items():
        if v.get("type") == "noul" or "noul" in v:
            parts.append(f"{k}=noul {v.get('noul')}")
        elif "choice" in v:
            parts.append(f"{k}={v.get('choice')} ({v.get('confidence')})")
        elif "score" in v:
            parts.append(f"{k}=score {v.get('score')}")
    return "; ".join(parts)


# A. the CLI demo's evidence, bare and rubric questions, as in HarrietDemoTimed.java
ev = "I was charged twice for my September subscription and no refund arrived."
bare = {
    "billing": ("billing?", {"type": "noul", "instructions": "Is this ticket about billing?"}),
    "queue": ("queue", {"type": "choice", "instructions": "Which queue should handle this?", "criteria": {"billing": "billing", "technical": "technical", "shipping": "shipping"}}),
    "urgency": ("urgency", {"type": "score", "instructions": "How urgent is this ticket?", "criteria": ["can wait", "this week", "today"]}),
}
rubric = {
    "billing": ("billing? (rubric)", {"type": "noul", "instructions": "Is this ticket about billing?", "criteria": {"true": "the customer is writing about a charge, an invoice, a payment, a refund or a subscription fee", "false": "the ticket is about anything else, such as a bug, a login problem or a delivery"}}),
    "queue": ("queue (rubric)", {"type": "choice", "instructions": "Which queue should handle this?", "criteria": {"billing": "charges, invoices, refunds, subscription payments", "technical": "bugs, outages, errors, login and integration problems", "shipping": "delivery, tracking, damaged or missing parcels"}}),
    "urgency": ("urgency (rubric)", {"type": "score", "instructions": "How urgent is this ticket?", "criteria": ["a question or a request with no money or access at stake", "the customer is losing money or a feature, but can still use the product", "the customer is blocked, locked out, or being charged wrongly right now"]}),
}
print("== A. CLI-demo evidence: single-question calls, each twice (cold, warm) ==")
for group, name in [(bare, "bare"), (rubric, "rubric")]:
    for qid, (label, q) in group.items():
        for rep in ("cold", "warm"):
            call(f"A {name} {label} [{rep}]", ev, {qid: q})
print("== A4. three rubric questions in one call, twice ==")
for rep in ("cold", "warm"):
    call(f"A systemOne 3 rubric [{rep}]", ev, {k: v[1] for k, v in rubric.items()})

# B. HarrietHello / LayaHello ticket, three bare questions in one call
ticket = "My invoice was charged twice this month and nobody answers the phone."
hello = {
    "urgent": {"type": "noul", "instructions": "Is this ticket urgent?"},
    "department": {"type": "choice", "instructions": "Which team should handle this ticket?", "criteria": {"billing": "billing", "technical": "technical", "sales": "sales"}},
    "frustration": {"type": "score", "instructions": "How frustrated is the customer?", "criteria": ["calm", "frustrated", "very angry"]},
}
print("== B. HarrietHello ticket, three questions, one call, twice ==")
for rep in ("cold", "warm"):
    call(f"B hello 3 questions [{rep}]", ticket, hello)

OUT.mkdir(parents=True, exist_ok=True)
json.dump(out, open(OUT / "jev-column.json", "w"), indent=1)
print("model used:", model_used, "| calls:", len(out["calls"]), "| written:", OUT / "jev-column.json")
