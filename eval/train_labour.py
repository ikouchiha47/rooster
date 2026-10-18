#!/usr/bin/env python3
"""Binary labour-action head: BoW + logistic, same featurizer as the shipping BoW.

Positive = strike|bandh kind rows (human pass, eval/labour_labels.json).
Trains on eval/labour_train.jsonl, evaluates on eval/labour_test.jsonl, and
runs a story-split probe: bank-Sept-cluster held out entirely, to measure
"new strike event" generalisation rather than memorised UFBU/IBA vocabulary.
"""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tiny_text import build_vocab, train_softmax, vectorise  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def load(path):
    rows = [json.loads(x) for x in open(path, encoding="utf-8") if x.strip()]
    texts = [(r.get("subject", "") + "\n" + r.get("body", "")) for r in rows]
    y = np.array([1 if r["label"] == "labour" else 0 for r in rows])
    return rows, texts, y


def report(name, y, p, rows):
    acc = float((p == y).mean())
    tp, tn, fp, fn = (int(((p == 1) & (y == 1)).sum()), int(((p == 0) & (y == 0)).sum()),
                      int(((p == 1) & (y == 0)).sum()), int(((p == 0) & (y == 1)).sum()))
    print(f"{name}: acc {acc:.3f}  TP {tp} TN {tn} FP {fp} FN {fn}")
    for tag, sel in (("FP", (p == 1) & (y == 0)), ("FN", (p == 0) & (y == 1))):
        for r in [rows[i] for i in np.where(sel)[0]]:
            print(f"  {tag} [{r['kind']}] {r['subject'][:90]}")


def threshold_scan(name, y, s, rows):
    print(f"{name} operating scan:")
    for tau in (0.3, 0.4, 0.5, 0.6, 0.7):
        p = (s > tau).astype(int)
        acc = float((p == y).mean())
        fp = int(((p == 1) & (y == 0)).sum())
        fn = int(((p == 0) & (y == 1)).sum())
        print(f"  tau={tau}: acc {acc:.3f} FP {fp} FN {fn}")


def main():
    tr_rows, tr_texts, ytr = load(os.path.join(HERE, "labour_train.jsonl"))
    te_rows, te_texts, yte = load(os.path.join(HERE, "labour_test.jsonl"))
    vocab = build_vocab(tr_texts, 1, 20000)
    print(f"vocab {len(vocab)}  train {len(ytr)} (pos {int(ytr.sum())})"
          f"  test {len(yte)} (pos {int(yte.sum())})")

    Xtr, Xte = vectorise(tr_texts, vocab), vectorise(te_texts, vocab)
    W, b = train_softmax(Xtr, ytr, 2)
    s = np.exp((Xte @ W + b)[:, 1]) / np.exp((Xte @ W + b)).sum(1)
    p = (s > 0.5).astype(int)
    report("random-split", yte, p, te_rows)
    threshold_scan("random-split", yte, s, te_rows)

    # story probe: hold the whole bank-Sept cluster out of training
    bank = np.array(["ufbu" in t.lower() or "iba" in t.lower() or "28-30" in t
                     or "28 to 30" in t for t in tr_texts])
    print(f"\nbank-cluster rows in train: {int(bank.sum())}")
    keep = ~bank
    vocab2 = build_vocab([t for t, k in zip(tr_texts, keep) if k], 1, 20000)
    Xtr2 = vectorise([t for t, k in zip(tr_texts, keep) if k], vocab2)
    Xte2 = vectorise(te_texts, vocab2)
    W2, b2 = train_softmax(Xtr2, ytr[keep], 2)
    p2 = (Xte2 @ W2 + b2)[:, 1] > (Xte2 @ W2 + b2)[:, 0]
    report("story-probe(test as-is)", yte, p2.astype(int), te_rows)


if __name__ == "__main__":
    main()
