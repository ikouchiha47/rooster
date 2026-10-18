#!/usr/bin/env python3
"""One-line-per-fact status: datasets, last results, labelling progress.

Replaces ad-hoc greps. Quiet by design: numbers only, no dumps.
Usage: python eval/summarise.py
"""
import glob
import json
import os
import re
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))


def count(path):
    try:
        rows = [json.loads(x) for x in open(path, encoding="utf-8") if x.strip()]
    except FileNotFoundError:
        return None
    return len(rows), Counter(r.get("label", "?") for r in rows)


def main():
    for f in ("labour_train.jsonl", "labour_test.jsonl", "rss_train.jsonl",
              "rss_test.jsonl", "train.jsonl", "test.jsonl"):
        c = count(os.path.join(HERE, f))
        print(f"{f:22s} {c[0] if c else 'missing':>5}  {dict(sorted(c[1].items())) if c else ''}")
    for log in sorted(glob.glob(os.path.join(HERE, "*.log"))):
        txt = open(log, encoding="utf-8", errors="replace").read()
        accs = re.findall(r"(?m)^(?:bow|minilm|random-split|story-probe[^\n]*): acc ([\d.]+)", txt)
        labs = sum(1 for x in open(log, encoding="utf-8", errors="replace") if '"label": "' in x)
        print(f"{os.path.basename(log):22s} accs={accs[-4:] if accs else []}"
              f"{f' labelled_rows~{labs}' if labs else ''}")
    chunks = sorted(f for f in glob.glob("/tmp/pradar/chunk*.jsonl")
                      if not f.endswith(".labelled.jsonl"))
    if chunks:
        done = sum(1 for c in chunks if os.path.exists(c.replace(".jsonl", ".labelled.jsonl")))
        print(f"chunks: {done}/{len(chunks)} labelled")


if __name__ == "__main__":
    main()
