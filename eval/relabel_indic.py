#!/usr/bin/env python3
"""Relabel the cleaned Indic pool to multi-label, resumable.

Reuses the one teacher prompt/parser in teacher_student.py (so the taxonomy has a
single owner). Appends to the output and skips ids already present, so an
interrupted run continues where it stopped.

Usage:
  python eval/relabel_indic.py [--limit N] [--batch 25]
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import teacher_student as ts  # noqa: E402

POOL = os.path.join(HERE, "indic_pool.jsonl")
OUT = os.path.join(HERE, "indic_pool.labelled.jsonl")
MODEL = "gemini-flash-lite-latest"


def done_ids(path):
    if not os.path.exists(path):
        return set()
    ids = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                try:
                    ids.add(json.loads(line)["id"])
                except Exception:  # noqa: BLE001
                    pass
    return ids


def load(path):
    return [json.loads(x) for x in open(path, encoding="utf-8") if x.strip()]


def label_chunk(chunk, key):
    items = "\n".join(f"{j}. {c['text'][:700]}" for j, c in enumerate(chunk))
    try:
        return ts.parse_labels(ts.gemini(MODEL, key, ts.PROMPT % items), len(chunk))
    except Exception as e:  # noqa: BLE001
        print(f"  teacher error: {e}", flush=True)
        return [None] * len(chunk)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--batch", type=int, default=25)
    ap.add_argument(
        "--retry-null",
        action="store_true",
        help="re-label rows already written with null labels (transient errors)",
    )
    a = ap.parse_args()

    ts.CLASSES, ts.PROMPT, ts.MULTI_LABEL = ts.TOPIC_CLASSES, ts.TOPIC_PROMPT, True
    key = ts.gemini_key()

    # Retry pass: a row that landed with no labels is a failed call, not a
    # decision, so it is re-asked and the output is rewritten in place.
    if a.retry_null:
        rows = load(OUT)
        todo = [r for r in rows if r.get("labels") is None]
        print(f"out {len(rows)}  null {len(todo)}", flush=True)
        fixed = {}
        for start in range(0, len(todo), a.batch):
            chunk = todo[start:start + a.batch]
            for c, lab in zip(chunk, label_chunk(chunk, key)):
                if isinstance(lab, list):
                    fixed[c["id"]] = lab
            print(f"  {start + len(chunk)}/{len(todo)}  fixed {len(fixed)}", flush=True)
        for r in rows:
            if r["id"] in fixed:
                r["labels"] = fixed[r["id"]]
        with open(OUT, "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"fixed {len(fixed)}/{len(todo)}; out {OUT}", flush=True)
        return

    pool = load(POOL)
    done = done_ids(OUT)
    todo = [r for r in pool if r["id"] not in done]
    if a.limit:
        todo = todo[:a.limit]
    print(f"pool {len(pool)}  done {len(done)}  todo {len(todo)}", flush=True)

    labelled = 0
    with open(OUT, "a", encoding="utf-8") as f:
        for start in range(0, len(todo), a.batch):
            chunk = todo[start:start + a.batch]
            labs = label_chunk(chunk, key)
            for c, lab in zip(chunk, labs):
                c["labels"] = lab if isinstance(lab, list) else None
                f.write(json.dumps(c, ensure_ascii=False) + "\n")
                labelled += 1
            f.flush()
            print(f"  {start + len(chunk)}/{len(todo)}", flush=True)
    print(f"labelled {labelled}; out {OUT}", flush=True)


if __name__ == "__main__":
    main()
