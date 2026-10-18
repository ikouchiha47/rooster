#!/usr/bin/env python3
"""Multi-label topic heads: BoW one-vs-rest + MiniLM BCE.

Data: eval/ml_train.jsonl / eval/ml_test.jsonl (sets built by build_multilabel.py).
Metrics are set-aware: exact-match, micro/macro F1, per-class P/R.
Usage: python eval/train_multilabel.py --model bow|minilm|both
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tiny_text import word_tokens  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
# Coarse buckets (IPTC-aligned). labour-action is a rule, not a bucket.
CLASSES = ["finance", "official", "politics", "crime", "incident", "sports",
           "entertainment", "tech", "weather", "travel", "festival",
           "health", "education", "other"]


def load(path):
    rows = [json.loads(x) for x in open(path, encoding="utf-8") if x.strip()]
    texts = [(r.get("subject", "") + "\n" + r.get("body", "")) for r in rows]
    Y = np.zeros((len(rows), len(CLASSES)), dtype=int)
    for i, r in enumerate(rows):
        for c in r.get("labels", [r.get("label")] if r.get("label") else []):
            if c in CLASSES:
                Y[i, CLASSES.index(c)] = 1
    return rows, texts, Y


def show(name, Y, P, rows, tau_note=""):
    exact = float((P == Y).all(1).mean())
    tp = ((P == 1) & (Y == 1)).sum(0).astype(float)
    fp = ((P == 1) & (Y == 0)).sum(0).astype(float)
    fn = ((P == 0) & (Y == 1)).sum(0).astype(float)
    with np.errstate(invalid="ignore", divide="ignore"):
        pr, rc = tp / np.maximum(tp + fp, 1), tp / np.maximum(tp + fn, 1)
        f1 = 2 * pr * rc / np.maximum(pr + rc, 1e-9)
    micro = 2 * tp.sum() / max(2 * tp.sum() + fp.sum() + fn.sum(), 1)
    print(f"{name}{tau_note}: exact {exact:.3f} micro-F1 {micro:.3f} macro-F1 {float(np.nanmean(f1)):.3f}")
    for i, c in enumerate(CLASSES):
        print(f"  {c:14s} P {pr[i]:.2f} R {rc[i]:.2f} (n={int(Y[:, i].sum())})")


def bow():
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.linear_model import LogisticRegression
    tr_rows, tr_texts, Ytr = load(TRAIN_PATH)
    te_rows, te_texts, Yte = load(TEST_PATH)

    def feats(t):
        ws = word_tokens(t)
        return ws + [f"{a}_{b}" for a, b in zip(ws, ws[1:])]

    vec = TfidfVectorizer(analyzer=feats, min_df=2, max_features=60000,
                          sublinear_tf=True, use_idf=False, norm="l2")
    Xtr, Xte = vec.fit_transform(tr_texts), vec.transform(te_texts)
    print(f"[bow-ovr] vocab {len(vec.vocabulary_)} train {len(Ytr)} test {len(Yte)}")
    P = np.zeros_like(Yte)
    for i in range(len(CLASSES)):
        clf = LogisticRegression(C=1.0, max_iter=500,
                                 class_weight="balanced").fit(Xtr, Ytr[:, i])
        P[:, i] = (clf.predict_proba(Xte)[:, 1] > 0.5).astype(int)
    show("bow-ovr", Yte, P, te_rows)


def minilm(epochs=5, seed=7, encoder="nreimers/MiniLM-L6-H384-uncased", frozen=False):
    import torch
    from transformers import (AutoModelForSequenceClassification, AutoTokenizer,
                              DataCollatorWithPadding, Trainer, TrainingArguments)
    tr_rows, tr_texts, Ytr = load(TRAIN_PATH)
    te_rows, te_texts, Yte = load(TEST_PATH)

    class Enc(torch.utils.data.Dataset):
        def __init__(self, texts, Y):
            self.enc = tok(texts, truncation=True, max_length=256, padding=False)
            self.Y = Y.astype(np.float32)

        def __len__(self):
            return len(self.Y)

        def __getitem__(self, i):
            d = {k: self.enc[k][i] for k in self.enc}
            d["labels"] = self.Y[i]
            return d

    name = encoder
    tok = AutoTokenizer.from_pretrained(name)
    model = AutoModelForSequenceClassification.from_pretrained(
        name, num_labels=len(CLASSES), problem_type="multi_label_classification")
    tag = "frozen" if frozen else "full"
    if frozen:
        # Separated weights: backbone frozen, only the head learns. The shared
        # space cannot warp, so minorities keep their pretrained geometry.
        for n, p in model.named_parameters():
            if "classifier" not in n:
                p.requires_grad = False
    args = TrainingArguments(output_dir="/tmp/ml_out", per_device_train_batch_size=16,
                             per_device_eval_batch_size=32, num_train_epochs=epochs,
                             learning_rate=2e-5, weight_decay=0.01, save_strategy="no",
                             report_to="none", seed=seed, logging_steps=200,
                             disable_tqdm=True)
    tr = Trainer(model=model, args=args, train_dataset=Enc(tr_texts, Ytr),
                 eval_dataset=Enc(te_texts, Yte), data_collator=DataCollatorWithPadding(tok))
    tr.train()
    logits = tr.predict(Enc(te_texts, Yte)).predictions
    P = (1 / (1 + np.exp(-logits)) > 0.5).astype(int)
    params = sum(x.numel() for x in model.parameters())
    print(f"[minilm-bce-{tag}] {name.split(chr(47))[-1]} params {params} int8 {params/1e6:.1f}MB")
    show(f"minilm-bce-{tag}", Yte, P, te_rows)


TRAIN_PATH = os.path.join(HERE, "ml_train.jsonl")
TEST_PATH = os.path.join(HERE, "ml_test.jsonl")


def load_split():
    return load(TRAIN_PATH), load(TEST_PATH)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=["bow", "minilm", "both"], default="both")
    ap.add_argument("--epochs", type=int, default=5)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--encoder", default="nreimers/MiniLM-L6-H384-uncased")
    ap.add_argument("--frozen", action="store_true")
    ap.add_argument("--train", default=None)
    ap.add_argument("--test", default=None)
    a = ap.parse_args()
    if a.train:
        TRAIN_PATH = a.train
    if a.test:
        TEST_PATH = a.test
    if a.model in ("bow", "both"):
        bow()
    if a.model in ("minilm", "both"):
        minilm(epochs=a.epochs, seed=a.seed, encoder=a.encoder, frozen=a.frozen)
