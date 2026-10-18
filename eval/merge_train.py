#!/usr/bin/env python3
"""Merge external labelled pools into the coarse TRAINING set (train only).

External corpora are training text, never gold: this appends to
eval/ml_train.jsonl and never touches ml_test.jsonl. Rows are normalised to the
ml_train shape (id / subject / body / source / labels / ts / split) and their
labels restricted to CLASSES — a row left with no class label is skipped (it
abstains rather than joining a bucket).

A source row tagged split=="test" (an external corpus's own test split) is routed
to --test-out instead, so out-of-domain text can be inspected without ever
joining the in-domain gold.

Dedup is by id and by normalised subject+body against everything already present.

Usage:
  python eval/merge_train.py --add eval/indic_pool.labelled.jsonl \
      --add eval/official_synth.jsonl --test-out eval/indic_test.jsonl [--dry-run]
"""
import argparse
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
TRAIN = os.path.join(HERE, "ml_train.jsonl")

# Coarse buckets (IPTC-aligned). labour-action is a rule, not a bucket.
CLASSES = ["finance", "official", "politics", "crime", "incident", "sports",
           "entertainment", "tech", "weather", "travel", "festival",
           "health", "education"]


def norm(t):
    return re.sub(r"\s+", " ", t.lower()).strip()


def load(path):
    if not os.path.exists(path):
        return []
    return [json.loads(x) for x in open(path, encoding="utf-8") if x.strip()]


def row_text(r):
    text = (r.get("text") or "").strip()
    if not text:
        text = (r.get("subject", "") + "\n" + (r.get("body", "") or "")).strip()
    return text


def row_labels(r):
    labs = r.get("labels") or ([r["label"]] if r.get("label") else [])
    return [c for c in labs if c in CLASSES]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--add", action="append", default=[], help="labelled jsonl to merge")
    ap.add_argument("--test-out", default="", help="route test-split rows here")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    base = load(TRAIN)
    seen_ids = {r["id"] for r in base}
    seen_keys = {norm(row_text(r)) for r in base}
    added_rows, test_rows = [], []
    stats = {"kept": 0, "no_class": 0, "dup": 0}

    for p in a.add:
        src = os.path.basename(p).replace(".jsonl", "")
        for r in load(p):
            labs = row_labels(r)
            if not labs:
                stats["no_class"] += 1
                continue
            text = row_text(r)
            if not text:
                stats["no_class"] += 1
                continue
            key = norm(text)
            if r["id"] in seen_ids or key in seen_keys:
                stats["dup"] += 1
                continue
            seen_ids.add(r["id"])
            seen_keys.add(key)
            row = {"id": r["id"], "subject": text, "body": "",
                   "source": f"pool:{src}", "labels": labs, "ts": 0, "split": "train"}
            if r.get("split") == "test":
                test_rows.append(row)
            else:
                added_rows.append(row)
            stats["kept"] += 1

    print(f"base {len(base)}  kept {stats['kept']}  no_class {stats['no_class']}  "
          f"dup {stats['dup']}  -> train {len(base) + len(added_rows)}  test_out {len(test_rows)}",
          flush=True)
    if a.dry_run:
        return
    with open(TRAIN, "w", encoding="utf-8") as f:
        for r in base + added_rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    if a.test_out and test_rows:
        # A bare filename lands in eval/; anything with a path is used as given
        # (repo-root-relative), so `--test-out eval/x.jsonl` does not become
        # `eval/eval/x.jsonl`.
        out_path = a.test_out if os.sep in a.test_out else os.path.join(HERE, a.test_out)
        with open(out_path, "w", encoding="utf-8") as f:
            for r in test_rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"wrote {TRAIN} ({len(base) + len(added_rows)})"
          + (f" and {a.test_out} ({len(test_rows)})" if a.test_out else ""), flush=True)


if __name__ == "__main__":
    main()
