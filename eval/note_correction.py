#!/usr/bin/env python3
"""Append/update corrections in eval/review.corrections.jsonl:  note_correction.py sms:390 promotions sms:396 purchases ...
"""
import json
import os
import sys

HERE = os.path.dirname(__file__)
CORR = os.path.join(HERE, "review.corrections.jsonl")

existing = {}
if os.path.exists(CORR):
    with open(CORR, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                r = json.loads(line)
                existing[r["id"]] = r["label"]

args = sys.argv[1:]
if len(args) % 2 != 0:
    sys.exit("need id label pairs")
for i in range(0, len(args), 2):
    existing[args[i]] = args[i + 1]

with open(CORR, "w", encoding="utf-8") as f:
    for k in sorted(existing, key=lambda s: int(s.split(":")[1])):
        f.write(json.dumps({"id": k, "label": existing[k]}) + "\n")
print(f"corrections: {len(existing)}")
