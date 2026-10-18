#!/usr/bin/env python3
"""Corrected embedder eval: BGE asymmetric usage + bootstrap CIs + watch retrieval.

Per BAAI docs (bge-small-en-v1.5 model card):
  - instruction = "Represent this sentence for searching relevant passages: "
  - retrieval: instruction on the QUERY only, never on passages
  - classification/other: no instruction recommended (instruction may degrade)

Runs three configs in one encode pass each:
  minilm      all-MiniLM-L6-v2, no prefix (symmetric baseline)
  bge         bge-small-en-v1.5, no prefix (documented for classification)
  bge-instr   bge-small-en-v1.5, instruction on every text (to test the warning)

Reports classification exact/micro/macro + paired bootstrap CI of each delta
vs minilm, and a watch-retrieval comparison (regex vs embedding cosine) on
in-distribution labour_test and on the OOV paraphrase set.

Usage: python eval/embed_eval.py
"""
import json
import os
import re

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "embed_cache")
CLASSES = ["finance", "official", "politics", "crime", "incident", "sports",
           "entertainment", "tech", "weather", "travel", "festival",
           "health", "education"]
INSTR = "Represent this sentence for searching relevant passages: "
WATCH = "strike, bandh, shutdown, labour unrest, trade union protest"
REGEX = re.compile(r"\b(bandh|strike|strikes|shutdown)\b", re.I)
CONFIGS = [
    ("minilm", "sentence-transformers/all-MiniLM-L6-v2", ""),
    ("bge", "BAAI/bge-small-en-v1.5", ""),
    ("bge-instr", "BAAI/bge-small-en-v1.5", INSTR),
]


def text_of(r):
    return (r.get("subject", "") + "\n" + (r.get("body", "") or ""))[:2000]


def load(path):
    return [json.loads(x) for x in open(path, encoding="utf-8") if x.strip()]


def multi_hot(rows):
    Y = np.zeros((len(rows), len(CLASSES)), dtype=int)
    for i, r in enumerate(rows):
        for c in (r.get("labels") or ([r["label"]] if r.get("label") else [])):
            if c in CLASSES:
                Y[i, CLASSES.index(c)] = 1
    return Y


def topics_only(rows):
    """Rows and their multi-hot topics, dropping rows with no topic after
    labour-action (a watch, not a bucket) is removed from the taxonomy."""
    Y = multi_hot(rows)
    keep = Y.sum(1) > 0
    return [r for r, k in zip(rows, keep) if k], Y[keep]


def prf(Y, P):
    tp = ((P == 1) & (Y == 1)).sum(0).astype(float)
    fp = ((P == 1) & (Y == 0)).sum(0).astype(float)
    fn = ((P == 0) & (Y == 1)).sum(0).astype(float)
    with np.errstate(invalid="ignore", divide="ignore"):
        pr = tp / np.maximum(tp + fp, 1)
        rc = tp / np.maximum(tp + fn, 1)
        f1 = 2 * pr * rc / np.maximum(pr + rc, 1e-9)
    micro = 2 * tp.sum() / max(2 * tp.sum() + fp.sum() + fn.sum(), 1)
    return float(micro), float(np.nanmean(f1)), float((P == Y).all(1).mean()), f1


def train_heads(Etr, Ytr, Et, Yt):
    from sklearn.linear_model import LogisticRegression
    P = np.zeros_like(Yt)
    for i in range(len(CLASSES)):
        clf = LogisticRegression(C=1.0, max_iter=500, class_weight="balanced").fit(Etr, Ytr[:, i])
        P[:, i] = (clf.predict_proba(Et)[:, 1] > 0.5).astype(int)
    return P


def bootstrap(Y, Pa, Pb, n=1000, seed=7):
    """Paired bootstrap of micro & macro delta (a - b) over rows."""
    rng = np.random.default_rng(seed)
    m, N = len(Y), len(Y)
    dmi, dma, mi, mb = [], [], [], []
    ma_a, ma_b = [], []
    for _ in range(n):
        idx = rng.integers(0, N, N)
        mia, maa, _, _ = prf(Y[idx], Pa[idx])
        mib, mab, _, _ = prf(Y[idx], Pb[idx])
        dmi.append(mia - mib)
        dma.append(maa - mab)
        mi.append(mia); mb.append(mib)
    def ci(x):
        return float(np.percentile(x, 2.5)), float(np.percentile(x, 97.5))
    return {"micro_delta": (float(np.mean(dmi)),) + ci(dmi),
            "macro_delta": (float(np.mean(dma)),) + ci(dma)}


def encode(st, texts, cfg_prefix, key):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, key + ".npz")
    if os.path.exists(path):
        return np.load(path)["E"]
    E = np.asarray(st.encode([cfg_prefix + t for t in texts], batch_size=32,
                             normalize_embeddings=True, show_progress_bar=False))
    np.savez(path, E=E)
    return E


def main():
    from sentence_transformers import SentenceTransformer
    tr0, te0, pa0 = (load(os.path.join(HERE, f)) for f in
                     ("ml_train.jsonl", "ml_test.jsonl", "ml_test_para.jsonl"))
    tr, Ytr = topics_only(tr0)
    te, Yte = topics_only(te0)
    pa, Ypa = topics_only(pa0)
    lt = load(os.path.join(HERE, "labour_test.jsonl"))
    ylt = np.array([1 if r.get("label") == "labour" else 0 for r in lt])
    print(f"topics {CLASSES}", flush=True)
    print(f"data: train {len(tr)}/{len(tr0)} test {len(te)}/{len(te0)} "
          f"para {len(pa)}/{len(pa0)} (dropped = labour-only rows) | "
          f"labour_test {len(lt)} (pos {int(ylt.sum())})", flush=True)

    # --- classification: exact/micro/macro per config, paired bootstrap vs minilm ---
    cls = {}
    for name, mid, prefix in CONFIGS:
        st = SentenceTransformer(mid, device="cpu")
        Etr = encode(st, [text_of(r) for r in tr], prefix, f"{name}-tr")
        Ete = encode(st, [text_of(r) for r in te], prefix, f"{name}-te")
        Epa = encode(st, [text_of(r) for r in pa], prefix, f"{name}-pa")
        Pte = train_heads(Etr, Ytr, Ete, Yte)
        Ppa = train_heads(Etr, Ytr, Epa, Ypa)
        cls[name] = Pte
        mi, ma, ex, f1 = prf(Yte, Pte)
        pmi, pma, pex, _ = prf(Ypa, Ppa)
        print(f"\n{name:10s} frozen exact {ex:.3f} micro {mi:.3f} macro {ma:.3f}"
              f" | para exact {pex:.3f} micro {pmi:.3f} macro {pma:.3f}", flush=True)
        if name in ("bge",):
            for i, c in enumerate(CLASSES):
                ntr, nte = int(Ytr[:, i].sum()), int(Yte[:, i].sum())
                print(f"    {c:10s} n_train {ntr:5d} n_test {nte:3d} F1 {f1[i]:.2f}", flush=True)
        if name != "minilm":
            b = bootstrap(Yte, Pte, cls["minilm"])
            print(f"           vs minilm frozen  micro delta {b['micro_delta'][0]:+.3f} "
                  f"[{b['micro_delta'][1]:+.3f},{b['micro_delta'][2]:+.3f}]  "
                  f"macro delta {b['macro_delta'][0]:+.3f} "
                  f"[{b['macro_delta'][1]:+.3f},{b['macro_delta'][2]:+.3f}]", flush=True)

    # --- watch retrieval: asymmetric query instruction; regex baseline ---
    print(f"\nwatch retrieval (query='{WATCH[:40]}...')", flush=True)
    print(f"  regex baseline: {int(ylt.sum())} pos, regex matches "
          f"{sum(1 for r in lt if REGEX.search(text_of(r)))} docs", flush=True)
    for name, mid, _ in CONFIGS[:2]:
        st = SentenceTransformer(mid, device="cpu")
        qprefix = INSTR if "bge" in name else ""
        qe = np.asarray(st.encode([qprefix + WATCH], normalize_embeddings=True))[0]
        de = np.asarray(st.encode([text_of(r) for r in lt], normalize_embeddings=True))
        s = de @ qe
        best = max(((th, *roc(ylt, s > th)) for th in np.linspace(0.1, 0.9, 81)),
                   key=lambda x: x[3])
        from sklearn.metrics import roc_auc_score
        print(f"  {name:10s} AUC {roc_auc_score(ylt, s):.3f} | best-F1 th={best[0]:.2f} "
              f"P {best[1]:.2f} R {best[2]:.2f} F1 {best[3]:.2f}", flush=True)


def roc(y, pred):
    tp = int(((pred == 1) & (y == 1)).sum()); fp = int(((pred == 1) & (y == 0)).sum())
    fn = int(((pred == 0) & (y == 1)).sum())
    p = tp / max(tp + fp, 1); r = tp / max(tp + fn, 1)
    return p, r, 2 * p * r / max(p + r, 1e-9)


if __name__ == "__main__":
    main()
