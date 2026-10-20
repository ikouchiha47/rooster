#!/usr/bin/env python3
"""Export the frozen-EmbeddingGemma logistic heads the app ships.

The eval trains one balanced logistic head per coarse class on the 256-d MRL
embeddings. This writes the same heads as JSON: weights, bias, a per-class
threshold tuned on a held-out slice of train, and the taxonomy tags each class
may emit.

    python3 eval/export_heads.py \
        --train-emb egemma256-tr.npz --train-jsonl eval/ml_train.jsonl \
        --test-emb egemma256-ml_test.npz --test-jsonl eval/ml_test.jsonl \
        --out heads.v1.json

Weights are applied on-device as `sigmoid(w . x + b)` over a normalised vector
with the same prompt, so the JSON is the whole model.
"""
import argparse
import json
import zlib

import numpy as np
from sklearn.linear_model import LogisticRegression

CLASSES = [
    "finance", "official", "politics", "crime", "incident", "sports",
    "entertainment", "tech", "weather", "travel", "festival", "health", "education",
]

# Model class -> taxonomy tags. Empty means "never inferred": these are categories
# the source declares (politics/entertainment/health) or classes taxonomy v1 does
# not have (education). Crime is an incident; sport is taxonomy's `games`.
TAGS = {
    "finance": ["finance"],
    "official": ["official"],
    "politics": [],
    "crime": ["incident"],
    "incident": ["incident"],
    "sports": ["games"],
    "entertainment": [],
    "tech": ["tech"],
    "weather": ["weather"],
    "travel": ["travel"],
    "festival": ["festival"],
    "health": [],
    "education": [],
}

PROMPT = "task: classification | query: "


def labels(path, n):
    out = np.zeros((n, len(CLASSES)), int)
    with open(path, encoding="utf-8") as f:
        rows = [json.loads(x) for x in f if x.strip()]
    assert len(rows) == n, f"{path}: {len(rows)} rows, embeddings {n}"
    for i, r in enumerate(rows):
        for c in r.get("labels") or []:
            if c in CLASSES:
                out[i, CLASSES.index(c)] = 1
    return out, rows


def fit(E, Y):
    keep = Y.sum(1) > 0
    return [
        LogisticRegression(C=1.0, max_iter=500, class_weight="balanced").fit(E[keep], Y[keep, i])
        for i in range(len(CLASSES))
    ]


def scores(clfs, E):
    return np.stack([c.predict_proba(E)[:, 1] for c in clfs], 1)


def prf(Y, P):
    tp = ((P == 1) & (Y == 1)).sum(0).astype(float)
    fp = ((P == 1) & (Y == 0)).sum(0).astype(float)
    fn = ((P == 0) & (Y == 1)).sum(0).astype(float)
    with np.errstate(invalid="ignore", divide="ignore"):
        p, r = tp / np.maximum(tp + fp, 1), tp / np.maximum(tp + fn, 1)
        f1 = 2 * p * r / np.maximum(p + r, 1e-9)
    micro = 2 * tp.sum() / max(2 * tp.sum() + fp.sum() + fn.sum(), 1)
    return micro, float(np.nanmean(f1)), p, r, f1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train-emb", required=True)
    ap.add_argument("--train-jsonl", required=True)
    ap.add_argument("--test-emb", required=True)
    ap.add_argument("--test-jsonl", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    Etr = np.load(args.train_emb)["E"]
    Ete = np.load(args.test_emb)["E"]
    Ytr, train_rows = labels(args.train_jsonl, len(Etr))
    Yte, _ = labels(args.test_jsonl, len(Ete))

    # Threshold calibration on a stable 1/7 slice, then refit on all rows.
    calib = np.array([(zlib.crc32(r["id"].encode()) % 7) == 0 for r in train_rows])
    clfs_calib = fit(Etr[~calib], Ytr[~calib])
    Prob_calib = scores(clfs_calib, Etr[calib])
    thresholds = []
    for i, c in enumerate(CLASSES):
        Y, P = Ytr[calib, i], Prob_calib[:, i]
        best = (0.0, 0.5)
        for t in np.arange(0.30, 0.81, 0.05):
            pred = (P > t).astype(int)
            tp = ((pred == 1) & (Y == 1)).sum()
            fp = ((pred == 1) & (Y == 0)).sum()
            fn = ((pred == 0) & (Y == 1)).sum()
            f1 = 2 * tp / max(2 * tp + fp + fn, 1)
            if f1 > best[0]:
                best = (f1, float(round(t, 2)))
        thresholds.append(best[1] if Y.sum() else 0.5)

    clfs = fit(Etr, Ytr)
    for name, E, Y in (("ml_test", Ete, Yte),):
        P = scores(clfs, E)
        tuned = (P > np.array(thresholds)).astype(int)
        mi, ma, p, r, f1 = prf(Y, tuned)
        print(f"{name}: micro {mi:.3f} macro {ma:.3f} exact {(tuned == Y).all(1).mean():.3f}")
        for i, c in enumerate(CLASSES):
            print(f"  {c:14} t={thresholds[i]:.2f} P {p[i]:.2f} R {r[i]:.2f} F1 {f1[i]:.2f} n={int(Y[:, i].sum())}")

    doc = {
        "id": "heads",
        "version": 1,
        "dim": int(Etr.shape[1]),
        "prompt": PROMPT,
        "classes": [
            {
                "label": c,
                "threshold": thresholds[i],
                "bias": round(float(clfs[i].intercept_[0]), 7),
                "weights": [round(float(w), 7) for w in clfs[i].coef_[0]],
                "tags": TAGS[c],
            }
            for i, c in enumerate(CLASSES)
        ],
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(doc, f, separators=(",", ":"))
    print(f"wrote {args.out}: {len(doc['classes'])} classes, {doc['dim']} dims")


if __name__ == "__main__":
    main()
