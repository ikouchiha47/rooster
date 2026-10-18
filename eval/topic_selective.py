#!/usr/bin/env python3
"""Topics without an `other` class: `other` is a code fallback (abstain).

Trains 7 real topic heads, drops other-only rows from training, and measures:
  1. per-class + aggregate quality on the real topics
  2. selective prediction — as the abstain threshold rises, coverage falls and
     precision on the covered set rises; `other` rows should score low, so the
     threshold is what "handles other in code".

Reads cached embeddings (eval/embed_cache/*.npz) from embed_eval.py.
Usage: python eval/topic_selective.py
"""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "embed_cache")
CLASSES = ["finance", "official", "politics", "crime", "incident", "sports",
           "entertainment", "tech", "weather", "travel", "festival",
           "health", "education"]


def load(name):
    return [json.loads(x) for x in open(os.path.join(HERE, name), encoding="utf-8") if x.strip()]


def multi_hot(rows):
    Y = np.zeros((len(rows), len(CLASSES)), dtype=int)
    for i, r in enumerate(rows):
        for c in (r.get("labels") or ([r["label"]] if r.get("label") else [])):
            if c in CLASSES:
                Y[i, CLASSES.index(c)] = 1
    return Y


def prf(Y, P):
    tp = ((P == 1) & (Y == 1)).sum(0).astype(float)
    fp = ((P == 1) & (Y == 0)).sum(0).astype(float)
    fn = ((P == 0) & (Y == 1)).sum(0).astype(float)
    with np.errstate(invalid="ignore", divide="ignore"):
        pr, rc = tp / np.maximum(tp + fp, 1), tp / np.maximum(tp + fn, 1)
        f1 = 2 * pr * rc / np.maximum(pr + rc, 1e-9)
    micro = 2 * tp.sum() / max(2 * tp.sum() + fp.sum() + fn.sum(), 1)
    return micro, float(np.nanmean(f1)), pr, rc, f1


def main():
    from sentence_transformers import SentenceTransformer
    from sklearn.linear_model import LogisticRegression

    import embed_eval as ee

    tr_all = load("ml_train.jsonl")
    TEST = sys.argv[1] if len(sys.argv) > 1 else "ml_test.jsonl"
    te_all = load(TEST)
    Ytr_all, Yte_all = multi_hot(tr_all), multi_hot(te_all)
    ktr, kte = Ytr_all.sum(1) > 0, Yte_all.sum(1) > 0
    Ytr, Yte = Ytr_all[ktr], Yte_all[kte]

    # The cache holds only the classifiable rows (embed_eval drops other-only);
    # it must line up row-for-row with the masked labels, not the full sets.
    stem = os.path.splitext(os.path.basename(TEST))[0]
    Etr = np.load(os.path.join(CACHE, "bge-tr.npz"))["E"]
    Ete = np.load(os.path.join(CACHE, f"bge-{stem}.npz"))["E"]
    assert len(Etr) == int(ktr.sum()), (len(Etr), int(ktr.sum()))
    assert len(Ete) == int(kte.sum()), (len(Ete), int(kte.sum()))
    print(f"test set: {TEST} | classifiable: train {int(ktr.sum())}/{len(tr_all)} "
          f"test {int(kte.sum())}/{len(te_all)} | abstain set (other-only) {int((~kte).sum())}\n",
          flush=True)

    clfs = []
    P = np.zeros_like(Yte)
    for i in range(len(CLASSES)):
        clf = LogisticRegression(C=1.0, max_iter=500, class_weight="balanced")
        clf.fit(Etr, Ytr[:, i])
        clfs.append(clf)
        P[:, i] = (clf.predict_proba(Ete)[:, 1] > 0.5).astype(int)

    mi, ma, pr, rc, f1 = prf(Yte, P)
    print(f"real topics: micro {mi:.3f} macro {ma:.3f} "
          f"exact {(P == Yte).all(1).mean():.3f}")
    for i, c in enumerate(CLASSES):
        print(f"  {c:9s} P {pr[i]:.2f} R {rc[i]:.2f} F1 {f1[i]:.2f} "
              f"(n_train {int(Ytr[:, i].sum())}, n_test {int(Yte[:, i].sum())})", flush=True)

    Prob = np.stack([c.predict_proba(Ete)[:, 1] for c in clfs], 1)
    Pmax = Prob.max(1)
    srt = np.sort(Prob, 1)
    Margin = srt[:, -1] - srt[:, -2] if len(CLASSES) > 1 else Pmax

    # other-only rows are not in the cache; encode them now (31 rows, trivial).
    other_rows = [r for r, k in zip(te_all, ~kte) if k]
    if other_rows:
        st = SentenceTransformer("BAAI/bge-small-en-v1.5", device="cpu")
        Eo = np.asarray(st.encode([ee.text_of(r) for r in other_rows],
                                  normalize_embeddings=True, show_progress_bar=False))
        Prob_o = np.stack([c.predict_proba(Eo)[:, 1] for c in clfs], 1)
    else:
        Prob_o = np.zeros((0, len(CLASSES)))
    Pmax_o = Prob_o.max(1) if len(other_rows) else np.zeros(0)
    srt_o = np.sort(Prob_o, 1) if len(other_rows) else np.zeros((0, len(CLASSES)))
    Margin_o = srt_o[:, -1] - srt_o[:, -2] if len(other_rows) else np.zeros(0)

    def m(x):
        return f"{x.mean():.3f}" if x.size else "n/a"

    print(f"\nseparability  max-prob: real {Pmax.mean():.3f} | other {m(Pmax_o)}"
          f"   margin: real {Margin.mean():.3f} | other {m(Margin_o)}", flush=True)
    if other_rows:
        print("where do other rows land?", flush=True)
        other_hot = (Prob_o > 0.5).sum(0)
        print("  " + ", ".join(f"{c}:{n}" for c, n in zip(CLASSES, other_hot) if n), flush=True)
    print("selective (threshold -> coverage, precision on covered)  [margin, real rows]", flush=True)
    for t in (0.2, 0.3, 0.4, 0.5, 0.6, 0.7):
        cov = Margin >= t
        tp = ((P == 1) & (Yte == 1) & cov[:, None]).sum()
        fp = ((P == 1) & (Yte == 0) & cov[:, None]).sum()
        o_pass = int((Margin_o >= t).sum()) if other_rows else 0
        print(f"  t={t:.1f} real coverage {cov.mean():.2f} ({int(cov.sum())}/{len(Yte)}) "
              f"other-pass {o_pass}/{len(other_rows)} covered precision {tp / max(tp + fp, 1):.2f}",
              flush=True)


if __name__ == "__main__":
    main()
