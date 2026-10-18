#!/usr/bin/env python3
"""Frozen-backbone heads: same backbones, no tuning.

Encodes train/test/para once per backbone, trains balanced logistic heads on
frozen vectors. Answers: does the pretrained geometry separate our classes
(frozen vs full-finetune contrast), and which backbone's geometry is better
(bge-small retrieval-tuned vs MiniLM MLM lineage)?
Usage: python eval/frozen_heads.py
"""
import json
import os

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
# Coarse buckets only (IPTC-aligned). labour-action is a RULE, not a bucket.
CLASSES = ["finance", "official", "politics", "crime", "incident", "sports",
           "entertainment", "tech", "weather", "travel", "festival",
           "health", "education", "other"]
BACKBONES = ["BAAI/bge-small-en-v1.5", "sentence-transformers/all-MiniLM-L6-v2"]


def load(path):
    rows = [json.loads(x) for x in open(path, encoding="utf-8") if x.strip()]
    texts = [(r.get("subject", "") + "\n" + (r.get("body", "") or ""))[:2000] for r in rows]
    Y = np.zeros((len(rows), len(CLASSES)), dtype=int)
    for i, r in enumerate(rows):
        for c in r.get("labels", [r.get("label")] if r.get("label") else []):
            if c in CLASSES:
                Y[i, CLASSES.index(c)] = 1
    return rows, texts, Y


def show(name, Y, P):
    exact = float((P == Y).all(1).mean())
    tp = ((P == 1) & (Y == 1)).sum(0).astype(float)
    fp = ((P == 1) & (Y == 0)).sum(0).astype(float)
    fn = ((P == 0) & (Y == 1)).sum(0).astype(float)
    with np.errstate(invalid="ignore", divide="ignore"):
        pr, rc = tp / np.maximum(tp + fp, 1), tp / np.maximum(tp + fn, 1)
        f1 = 2 * pr * rc / np.maximum(pr + rc, 1e-9)
    micro = 2 * tp.sum() / max(2 * tp.sum() + fp.sum() + fn.sum(), 1)
    print(f"{name}: exact {exact:.3f} micro {micro:.3f} macro {float(np.nanmean(f1)):.3f}",
          flush=True)
    for i, c in enumerate(CLASSES):
        print(f"  {c:14s} P {pr[i]:.2f} R {rc[i]:.2f} (n={int(Y[:, i].sum())})", flush=True)


def main():
    from sentence_transformers import SentenceTransformer
    from sklearn.linear_model import LogisticRegression
    _, tr_texts, Ytr = load(os.path.join(HERE, "ml_train.jsonl"))
    _, te_texts, Yte = load(os.path.join(HERE, "ml_test.jsonl"))
    _, pa_texts, Ypa = load(os.path.join(HERE, "ml_test_para.jsonl"))
    for bb in BACKBONES:
        st = SentenceTransformer(bb, device="cpu")
        Etr = np.array(st.encode(tr_texts, batch_size=32, show_progress_bar=False))
        Ete = np.array(st.encode(te_texts, batch_size=32, show_progress_bar=False))
        Epa = np.array(st.encode(pa_texts, batch_size=32, show_progress_bar=False))
        Pte = np.zeros_like(Yte)
        Ppa = np.zeros_like(Ypa)
        for i in range(len(CLASSES)):
            clf = LogisticRegression(C=1.0, max_iter=500,
                                     class_weight="balanced").fit(Etr, Ytr[:, i])
            Pte[:, i] = (clf.predict_proba(Ete)[:, 1] > 0.5).astype(int)
            Ppa[:, i] = (clf.predict_proba(Epa)[:, 1] > 0.5).astype(int)
        short = bb.split("/")[-1]
        show(f"frozen-{short} frozen-test", Yte, Pte)
        show(f"frozen-{short} para-test", Ypa, Ppa)


if __name__ == "__main__":
    main()
