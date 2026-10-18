#!/usr/bin/env python3
"""Sender-grouped, label-stratified train/test split of eval/dataset.full.jsonl.

Grouping by SENDER (not row) is mandatory here: the corpus has 940 duplicate
bodies and 122 senders that mix labels, so a row split leaks and inflates any
score. Stratification is by each sender's dominant (majority) label.

Emits eval/train.jsonl, eval/test.jsonl, eval/split.summary.json.
"""
import json
import os
import random
from collections import Counter, defaultdict

HERE = os.path.dirname(__file__)
FULL = os.path.join(HERE, "dataset.full.jsonl")
TEST_FRAC = 0.2
SEED = 20261013


def load():
    rows = []
    with open(FULL, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def main():
    rows = load()
    # sender -> rows, and each sender's dominant label
    by_sender = defaultdict(list)
    for r in rows:
        by_sender[r["sender"]].append(r)
    sender_label = {s: Counter(x["label"] for x in rs).most_common(1)[0][0] for s, rs in by_sender.items()}

    # stratify senders by dominant label
    strata = defaultdict(list)
    for s, lab in sender_label.items():
        strata[lab].append(s)

    rng = random.Random(SEED)
    test_senders = set()
    for lab, senders in strata.items():
        senders = sorted(senders)
        rng.shuffle(senders)
        n_test = max(1, round(len(senders) * TEST_FRAC)) if len(senders) > 1 else 0
        test_senders.update(senders[:n_test])

    train = [r for r in rows if r["sender"] not in test_senders]
    test = [r for r in rows if r["sender"] in test_senders]

    def write(path, data):
        with open(path, "w", encoding="utf-8") as f:
            for r in data:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")

    write(os.path.join(HERE, "train.jsonl"), train)
    write(os.path.join(HERE, "test.jsonl"), test)

    def dist(data):
        return dict(sorted(Counter(r["label"] for r in data).items(), key=lambda kv: -kv[1]))

    summary = {
        "seed": SEED,
        "test_fraction": TEST_FRAC,
        "rows": {"train": len(train), "test": len(test)},
        "senders": {"train": len(by_sender) - len(test_senders), "test": len(test_senders)},
        "train_label_dist": dist(train),
        "test_label_dist": dist(test),
        "note": "labels are the CURRENT categorizer output until reviewed; re-split after review.",
    }
    with open(os.path.join(HERE, "split.summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
