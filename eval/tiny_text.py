#!/usr/bin/env python3
"""Tiny text classifier: bag-of-words + multinomial logistic regression.

Answers one question: **how much SMS-category accuracy does a tiny, ship-able
classifier buy per byte?**  It is the missing apples-to-apples comparison
against the content rules (`category_rules.py`) and the frozen-embedding head
(`head.py`, ~0.83 acc).

Design:
  * numpy only -- no torch, no scipy, no onnxruntime. Reproducible with
    ``python eval/tiny_text.py``.
  * Features: word unigrams + bigrams (optionally char n-grams), digit runs
    folded to ``<n>`` so OTP codes / amounts don't explode the vocabulary.
    Sublinear tf (1 + log count), L2-normalised rows.
  * Model: multinomial logistic regression (softmax) trained by full-batch
    gradient descent with momentum on `train.jsonl`, evaluated on `test.jsonl`.
  * ``unknown`` is the **abstain** outcome, never a trained class. Its label
    rows are dropped from fitting (they are "the rules couldn't decide", not a
    seventh category). The model may still *predict* unknown via a
    score-margin threshold.

The split is already sender-grouped (`split_dataset.py`), so train and test
share no sender and the numbers are honest.

Reported:
  * SIZE  -- vocabulary, parameter count, bytes at fp32 and int8, plus the
    vocabulary strings (which also have to ship).
  * QUALITY -- test accuracy, per-category precision/recall, and an abstain
    sweep on the softmax margin with the rules-`unknown` bucket's coverage and
    rescue precision.
  * Apple-to-apple rules-only on the identical test rows, and the shipping
    combination (rules first, model only on the residual).
"""
import argparse
import json
import math
import os
import re
import sys
from collections import Counter

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from category_rules import UNKNOWN_ID, categorize  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
TRAIN_PATH = os.path.join(HERE, "train.jsonl")
TEST_PATH = os.path.join(HERE, "test.jsonl")

# The six trained classes. `unknown` is abstain, not a class.
TRAINED = ["personal", "transactional", "promotions", "purchases", "government", "spam"]

_WORD = re.compile(r"[a-z]+|<n>")
_NUM = re.compile(r"\d+")


# --- features ---------------------------------------------------------------

def normalise(text):
    """Lowercase, fold digit runs, keep alphabetic words and the <n> marker."""
    return text.lower().replace("\n", " ")


def word_tokens(text):
    return _WORD.findall(_NUM.sub("<n>", normalise(text)))


def char_ngrams(text, lo, hi):
    t = re.sub(r"\s+", " ", normalise(text))
    out = []
    for n in range(lo, hi + 1):
        out.extend(t[i:i + n] for i in range(len(t) - n + 1))
    return out


def featurise(text, char=False):
    """One body -> list of feature strings (unigrams + bigrams [+ char n-grams])."""
    ws = word_tokens(text)
    feats = list(ws)
    feats += [f"{a}_{b}" for a, b in zip(ws, ws[1:])]
    if char:
        feats += char_ngrams(text, 3, 4)
    return feats


def build_vocab(texts, min_df, max_features, char=False):
    df = Counter()
    for t in texts:
        df.update(set(featurise(t, char)))
    kept = [(w, c) for w, c in df.items() if c >= min_df]
    kept.sort(key=lambda wc: (-wc[1], wc[0]))
    if max_features and len(kept) > max_features:
        kept = kept[:max_features]
    return {w: i for i, (w, _) in enumerate(kept)}


def vectorise(texts, vocab, char=False):
    """Dense float32 count matrix, sublinear tf, L2-normalised per row."""
    X = np.zeros((len(texts), len(vocab)), dtype=np.float32)
    for i, t in enumerate(texts):
        counts = Counter(f for f in featurise(t, char) if f in vocab)
        for f, c in counts.items():
            X[i, vocab[f]] = 1.0 + math.log(c)  # sublinear tf
    norm = np.linalg.norm(X, axis=1, keepdims=True)
    norm[norm == 0] = 1.0
    return X / norm


# --- model ------------------------------------------------------------------

def softmax(z):
    z = z - z.max(1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(1, keepdims=True)


def train_softmax(X, y_idx, n_classes, iters=600, lr=0.5, l2=1e-4, momentum=0.9):
    """Multinomial LR by full-batch GD with momentum. Returns W (V x C), b (C,)."""
    n, d = X.shape
    W = np.zeros((d, n_classes), dtype=np.float64)
    b = np.zeros(n_classes, dtype=np.float64)
    Y = np.zeros((n, n_classes), dtype=np.float64)
    Y[np.arange(n), y_idx] = 1.0
    vW = np.zeros_like(W)
    vb = np.zeros_like(b)
    for _ in range(iters):
        p = softmax(X @ W + b)
        g = (p - Y) / n
        gW = X.T @ g + l2 * W
        gb = g.sum(0)
        vW = momentum * vW - lr * gW
        vb = momentum * vb - lr * gb
        W += vW
        b += vb
    return W, b


def predict_proba(X, W, b):
    return softmax(X @ W + b)


def margin_abstain(p, classes, tau):
    """Abstain when top1 - top2 < tau. Returns predicted label ids."""
    order = np.argsort(-p, axis=1)
    top1 = order[:, 0]
    margin = p[np.arange(len(p)), order[:, 0]] - p[np.arange(len(p)), order[:, 1]]
    out = []
    for i in range(len(p)):
        out.append(classes[top1[i]] if margin[i] >= tau else UNKNOWN_ID)
    return out, margin


# --- metrics ----------------------------------------------------------------

def per_class(y_true, y_pred, classes):
    rows = {}
    for c in classes:
        tp = sum(1 for a, b in zip(y_true, y_pred) if a == c and b == c)
        fp = sum(1 for a, b in zip(y_true, y_pred) if a != c and b == c)
        fn = sum(1 for a, b in zip(y_true, y_pred) if a == c and b != c)
        sup = sum(1 for a in y_true if a == c)
        p = tp / (tp + fp) if tp + fp else float("nan")
        r = tp / (tp + fn) if tp + fn else float("nan")
        f1 = 2 * p * r / (p + r) if (p == p and r == r and p + r) else float("nan")
        rows[c] = {"support": sup, "tp": tp, "fp": fp, "fn": fn,
                   "precision": p, "recall": r, "f1": f1}
    return rows


def summarise(name, y_true, y_pred, rules_unknown_mask):
    n = len(y_true)
    acc = sum(1 for a, b in zip(y_true, y_pred) if a == b) / n
    abstain = sum(1 for b in y_pred if b == UNKNOWN_ID) / n
    idx = [i for i, m in enumerate(rules_unknown_mask) if m]
    if idx:
        rescued = [i for i in idx if y_pred[i] != UNKNOWN_ID]
        coverage = len(rescued) / len(idx)
        correct = sum(1 for i in rescued if y_pred[i] == y_true[i])
        rescue_p = correct / len(rescued) if rescued else float("nan")
    else:
        coverage, rescue_p = float("nan"), float("nan")
    return {"name": name, "accuracy": acc, "abstain_rate": abstain,
            "unknown_bucket_n": len(idx), "unknown_coverage": coverage,
            "rescue_precision": rescue_p, "per_class": per_class(y_true, y_pred, TRAINED)}


def print_block(s):
    print(f"\n== {s['name']} ==")
    print(f"  accuracy {s['accuracy']:.3f} | abstain {s['abstain_rate']:.3f} | "
          f"rules-unknown bucket n={s['unknown_bucket_n']} "
          f"coverage {s['unknown_coverage']:.3f} rescue-precision {s['rescue_precision']:.3f}")
    print(f"  {'class':14} {'sup':>4} {'P':>6} {'R':>6} {'F1':>6}")
    for c in TRAINED:
        r = s["per_class"][c]
        print(f"  {c:14} {r['support']:>4} {r['precision']:>6.3f} "
              f"{r['recall']:>6.3f} {r['f1']:>6.3f}")


# --- size accounting --------------------------------------------------------

def size_report(vocab, n_classes):
    V = len(vocab)
    params = V * n_classes + n_classes  # weights + bias
    fp32 = params * 4
    int8 = params * 1
    # The vocabulary strings also ship -- account for them honestly.
    vocab_bytes = sum(len(w.encode("utf-8")) + 1 for w in vocab)  # +1 newline sep
    return {
        "vocab": V, "classes": n_classes, "params": params,
        "params_fp32_bytes": fp32, "params_int8_bytes": int8,
        "vocab_bytes": vocab_bytes,
        "total_fp32_bytes": fp32 + vocab_bytes,
        "total_int8_bytes": int8 + vocab_bytes,
    }


def _mb(b):
    return b / 1024.0


# --- data -------------------------------------------------------------------

_WS = re.compile(r"\s+")


def norm_body(b):
    """Normalised body for cross-split duplicate detection (fold digits/space)."""
    return _WS.sub(" ", _NUM.sub("<n>", b.lower())).strip()


def load(path):
    rows = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--min-df", type=int, default=2, help="drop features seen in fewer docs")
    ap.add_argument("--max-features", type=int, default=20000, help="cap vocabulary size")
    ap.add_argument("--char-ngrams", action="store_true", help="add char 3-4 grams")
    ap.add_argument("--iters", type=int, default=600)
    ap.add_argument("--lr", type=float, default=0.5)
    ap.add_argument("--l2", type=float, default=1e-4)
    ap.add_argument("--tau", type=float, default=0.30, help="default score-margin abstain threshold")
    ap.add_argument("--train", default=TRAIN_PATH)
    ap.add_argument("--test", default=TEST_PATH)
    args = ap.parse_args()

    train, test = load(args.train), load(args.test)
    print(f"data: {len(train)} train / {len(test)} test rows "
          f"(sender-grouped: {len({r['sender'] for r in train})} / "
          f"{len({r['sender'] for r in test})} senders)")

    # ---- features ----
    vocab = build_vocab([r["body"] for r in train], args.min_df, args.max_features,
                        char=args.char_ngrams)
    Xtr = vectorise([r["body"] for r in train], vocab, char=args.char_ngrams)
    Xte = vectorise([r["body"] for r in test], vocab, char=args.char_ngrams)

    # ---- fit on labelled, non-unknown training rows (unknown = abstain) ----
    ytr = [r["label"] for r in train]
    classes = [c for c in TRAINED if ytr.count(c) > 0]
    fit = np.array([y in classes for y in ytr])
    idx = {c: i for i, c in enumerate(classes)}
    yfit = np.array([idx[y] for y in np.array(ytr)[fit]])
    print(f"fit rows: {int(fit.sum())}/{len(ytr)} "
          f"(dropped {len(ytr) - int(fit.sum())} unknown/unlabelled) | classes {classes}")

    W, b = train_softmax(Xtr[fit], yfit, len(classes),
                         iters=args.iters, lr=args.lr, l2=args.l2)
    p_te = predict_proba(Xte, W, b)

    yte = [r["label"] for r in test]
    rules_te = [categorize(r["sender"], r["body"]) for r in test]
    rules_unknown_mask = [p == UNKNOWN_ID for p in rules_te]

    # ---- size ----
    sz = size_report(vocab, len(classes))
    print("\n" + "=" * 72)
    print("MODEL SIZE")
    print("=" * 72)
    print(f"  vocabulary      : {sz['vocab']} features "
          f"(words+bigrams{'+char3-4' if args.char_ngrams else ''}, min_df={args.min_df})")
    print(f"  classes         : {sz['classes']}")
    print(f"  parameters      : {sz['params']}  (= V*C + C)")
    print(f"  params fp32     : {sz['params_fp32_bytes']/1024:8.1f} KB")
    print(f"  params int8     : {sz['params_int8_bytes']/1024:8.1f} KB")
    print(f"  vocab strings   : {sz['vocab_bytes']/1024:8.1f} KB")
    print(f"  TOTAL fp32      : {sz['total_fp32_bytes']/1024:8.1f} KB "
          f"({sz['total_fp32_bytes']/1024/1024:.2f} MB)")
    print(f"  TOTAL int8      : {sz['total_int8_bytes']/1024:8.1f} KB "
          f"({sz['total_int8_bytes']/1024/1024:.2f} MB)   <-- ship size")

    # ---- logistic, no abstain ----
    pred_full = [classes[int(j)] for j in p_te.argmax(1)]
    # ---- logistic with margin abstain ----
    pred_tau, margin = margin_abstain(p_te, classes, args.tau)

    # ---- rules + logistic-on-residual (the shipping combination) ----
    combined = [pred_tau[i] if rules_te[i] == UNKNOWN_ID else rules_te[i]
                for i in range(len(test))]

    print("\n" + "=" * 72)
    print("QUALITY  (same sender-grouped test set, truth = gold `label`)")
    print("=" * 72)
    print_block(summarise("linear-only (no abstain)", yte, pred_full, rules_unknown_mask))
    print_block(summarise(f"linear-only (margin tau={args.tau})", yte, pred_tau, rules_unknown_mask))
    print_block(summarise("rules-only", yte, rules_te, rules_unknown_mask))
    print_block(summarise("rules + linear-on-unknown (residual)", yte, combined, rules_unknown_mask))

    # ---- abstain sweep ----
    print("\nmargin sweep (linear): tau -> abstain / rules-unknown coverage / rescue-precision")
    for t in [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7]:
        pr, _ = margin_abstain(p_te, classes, t)
        idxu = [i for i, m in enumerate(rules_unknown_mask) if m]
        resc = [i for i in idxu if pr[i] != UNKNOWN_ID]
        cov = len(resc) / len(idxu) if idxu else float("nan")
        rp = (sum(1 for i in resc if pr[i] == yte[i]) / len(resc)) if resc else float("nan")
        acc = sum(1 for a, cc in zip(yte, pr) if a == cc) / len(yte)
        print(f"  tau={t:.1f}  abstain={sum(1 for q in pr if q == UNKNOWN_ID)/len(pr):.3f}"
              f"  acc={acc:.3f}  unknown-cov={cov:.3f}  rescue-P={rp:.3f}")

    # ---- context vs earlier experiments ----
    print("\n" + "=" * 72)
    print("LEAKAGE & BASELINES")
    print("=" * 72)
    # Cross-split duplicate bodies: sender-grouping does not catch a template
    # body sent by two different senders, which inflates a bag-of-words model.
    train_bodies = Counter(norm_body(r["body"]) for r in train)
    dup = [norm_body(r["body"]) in train_bodies for r in test]
    n_dup = sum(dup)
    print(f"  test rows with a normalised-duplicate body in train: {n_dup}/{len(test)} "
          f"({n_dup/len(test):.1%}) -- BoW memorises these")
    clean = [i for i in range(len(test)) if not dup[i]]
    maj = Counter(ytr).most_common(1)[0][0]
    maj_acc = sum(1 for y in yte if y == maj) / len(yte)
    print(f"  majority-class baseline (always '{maj}'): {maj_acc:.3f}")

    def subset(idx, pred):
        yt = [yte[i] for i in idx]
        pr = [pred[i] for i in idx]
        rm = [rules_unknown_mask[i] for i in idx]
        return summarise("x", yt, pr, rm)

    print("\n  leakage-free subset (test rows with NO duplicate body in train, "
          f"n={len(clean)}):")
    for nm, pred in [("linear-only (no abstain)", pred_full),
                     (f"linear-only (margin tau={args.tau})", pred_tau),
                     ("rules-only", rules_te),
                     ("rules + linear-on-unknown", combined)]:
        s = subset(clean, pred)
        print(f"    {nm:34} acc {s['accuracy']:.3f}  "
              f"abstain {s['abstain_rate']:.3f}  rescue-P {s['rescue_precision']:.3f}")

    print("\n" + "=" * 72)
    print("CONTEXT")
    print("=" * 72)
    print("  frozen EmbeddingGemma head (head.py, 256-d + logistic) ~0.83 acc "
          "but the ONNX embedding model is ~300 MB -- not this budget.")
    print("  1-layer LSTM V=10k,d=128,h=128 int8 ~1.4 MB (size table).")
    print(f"  this linear: {sz['total_int8_bytes']/1024/1024:.2f} MB int8, "
          f"acc {summarise('x', yte, pred_full, rules_unknown_mask)['accuracy']:.3f} "
          f"(no abstain) / {summarise('x', yte, pred_tau, rules_unknown_mask)['accuracy']:.3f} "
          f"(tau={args.tau}).")


if __name__ == "__main__":
    main()
