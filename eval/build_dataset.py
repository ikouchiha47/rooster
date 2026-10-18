#!/usr/bin/env python3
"""Build the FULL category dataset from the live DB (+ old labels from the corpus).

Emits:
  eval/dataset.full.jsonl   one row per SMS: id, sender, body, current, old, label, reviewed
  eval/dataset.summary.json counts + duplicate/sender stats

`label` is pre-filled with the CURRENT categorizer output and is what a human
reviews/corrects. `label` is the truth field; `current` is the machine's guess.
"""
import json
import os
import sqlite3
import sys

T = "/var/folders/2_/4mhmjvl14tbdpwyskd146vmc0000gn/T/opencode"
DB = os.path.join(T, "personalos.db")
CORPUS = os.path.join(T, "sms_corpus.txt")
OUT = os.path.join(os.path.dirname(__file__), "dataset.full.jsonl")
SUM = os.path.join(os.path.dirname(__file__), "dataset.summary.json")

def norm(s):
    return " ".join((s or "").split())


# Old labels (the previous SmsClassifier output) for provenance only.
old = {}
if os.path.exists(CORPUS):
    for line in open(CORPUS, encoding="utf-8"):
        parts = line.rstrip("\n").split(" | ", 2)
        if len(parts) == 3:
            sender, oldcat, body = parts
            old[(sender, norm(body))] = oldcat

conn = sqlite3.connect(DB)
cur = conn.execute(
    "SELECT id, title, content, category FROM events WHERE source='sms' ORDER BY id"
)
rows = cur.fetchall()
conn.close()

senders = set()
dups = {}
with open(OUT, "w", encoding="utf-8") as f:
    for rid, sender, body, current in rows:
        sender = sender or ""
        body = body or ""
        rec = {
            "id": f"sms:{rid}",
            "sender": sender,
            "body": body,
            "current": current,
            "old": old.get((sender, norm(body))),
            "label": current,
            "reviewed": False,
        }
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        senders.add(sender)
        key = "".join(c if c.isdigit() is False else "" for c in body)  # digit-stripped shape
        dups[key] = dups.get(key, 0) + 1

by_label = {}
for r in rows:
    by_label[r[3]] = by_label.get(r[3], 0) + 1

summary = {
    "rows": len(rows),
    "distinct_senders": len(senders),
    "duplicate_body_groups": sum(1 for v in dups.values() if v > 1),
    "duplicate_rows": sum(v for v in dups.values() if v > 1),
    "by_current_label": dict(sorted(by_label.items(), key=lambda kv: -kv[1])),
    "note": "label == current until reviewed; current is the categorizer output (NOT verified).",
}
with open(SUM, "w", encoding="utf-8") as f:
    json.dump(summary, f, indent=2)

print(json.dumps(summary, indent=2))
