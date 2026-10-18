#!/usr/bin/env python3
"""Apply eval/review.corrections.jsonl to eval/dataset.full.jsonl (set label, mark reviewed),
then re-split. Idempotent: re-run any time the corrections file grows.

Corrections where label == current are no-ops; only true corrections matter.
"""
import json
import os

HERE = os.path.dirname(__file__)
FULL = os.path.join(HERE, "dataset.full.jsonl")
CORR = os.path.join(HERE, "review.corrections.jsonl")


def main():
    fix = {}
    if os.path.exists(CORR):
        with open(CORR, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    r = json.loads(line)
                    fix[r["id"]] = r["label"]

    rows, changed, reviewed = [], 0, 0
    with open(FULL, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            if r["id"] in fix:
                r["label"] = fix[r["id"]]
                r["reviewed"] = True
                reviewed += 1
                if r["label"] != r["current"]:
                    changed += 1
            rows.append(r)

    with open(FULL, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    print(json.dumps({"corrections_applied": reviewed, "changed_vs_current": changed, "rows": len(rows)}, indent=2))


if __name__ == "__main__":
    main()
