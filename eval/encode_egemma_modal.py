#!/usr/bin/env python3
"""EmbeddingGemma-256 (Matryoshka) frozen-head eval, on Modal GPU.

Encodes ml_train / indic_test / ml_test with the *shipping* multilingual embedder
(google/embeddinggemma-300m), truncated 768 -> 256 dims via the MRL property and
renormalised, then trains balanced logistic heads for the 13 coarse buckets and
reports overall + per-language (en/hi/bn) quality. Answers whether the multilingual
embedder fixes the hi/bn collapse an English stand-in (bge-small) produced.

Prompts / seq length / dim mirror the device: the app tags with
`task: classification | query: ` and its ONNX runtime is seq256 + 256-dim MRL.

Run:  modal run eval/encode_egemma_modal.py
"""
import json
import os

import modal

CLASSES = ["finance", "official", "politics", "crime", "incident", "sports",
           "entertainment", "tech", "weather", "travel", "festival",
           "health", "education"]
PROMPT = "task: classification | query: "  # the app's tagger prompt
SEQ = 256  # the shipping ONNX runtime truncates to 256 tokens
MRL = 256  # Matryoshka dim we store on device

app = modal.App("rooster-egemma-eval")

image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install("torch", "sentence-transformers", "transformers", "scikit-learn",
                 "numpy", "huggingface_hub")
    .add_local_file("eval/ml_train.jsonl", "/data/train.jsonl")
    .add_local_file("eval/ml_test.jsonl", "/data/test.jsonl")
    .add_local_file("eval/indic_test.jsonl", "/data/indic_test.jsonl")
)
volume = modal.Volume.from_name("rooster-eval", create_if_missing=True)
secret = modal.Secret.from_dict({"HF_TOKEN": os.environ["HF_TOKEN"]})


def _rows(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(x) for x in f if x.strip()]


@app.function(image=image, gpu="T4", volumes={"/vol": volume}, secrets=[secret], timeout=3600)
def run() -> dict:
    import numpy as np
    from sentence_transformers import SentenceTransformer
    from sklearn.linear_model import LogisticRegression

    tr, ite, mte = _rows("/data/train.jsonl"), _rows("/data/indic_test.jsonl"), _rows("/data/test.jsonl")

    def mh(rows):
        Y = np.zeros((len(rows), len(CLASSES)), int)
        for i, r in enumerate(rows):
            for c in (r.get("labels") or ([r["label"]] if r.get("label") else [])):
                if c in CLASSES:
                    Y[i, CLASSES.index(c)] = 1
        return Y

    Ytr, Yite, Ymte = mh(tr), mh(ite), mh(mte)

    st = SentenceTransformer("google/embeddinggemma-300m", device="cuda")
    st.max_seq_length = SEQ

    def enc(rows):
        txt = [PROMPT + (r.get("subject", "") + "\n" + (r.get("body", "") or ""))[:2000] for r in rows]
        E = np.asarray(st.encode(txt, batch_size=64, normalize_embeddings=True,
                                 show_progress_bar=True))
        E = E[:, :MRL]  # MRL truncation
        return (E / np.maximum(np.linalg.norm(E, axis=1, keepdims=True), 1e-9)).astype(np.float32)

    Etr, Eite, Emte = enc(tr), enc(ite), enc(mte)
    np.savez("/vol/egemma256-tr.npz", E=Etr)
    np.savez("/vol/egemma256-indic_test.npz", E=Eite)
    np.savez("/vol/egemma256-ml_test.npz", E=Emte)

    ktr = Ytr.sum(1) > 0
    clfs = [LogisticRegression(C=1.0, max_iter=500, class_weight="balanced")
            .fit(Etr[ktr], Ytr[ktr, i]) for i in range(len(CLASSES))]

    def prf(Y, P):
        tp = ((P == 1) & (Y == 1)).sum(0).astype(float)
        fp = ((P == 1) & (Y == 0)).sum(0).astype(float)
        fn = ((P == 0) & (Y == 1)).sum(0).astype(float)
        with np.errstate(invalid="ignore", divide="ignore"):
            pr, rc = tp / np.maximum(tp + fp, 1), tp / np.maximum(tp + fn, 1)
            f1 = 2 * pr * rc / np.maximum(pr + rc, 1e-9)
        return 2 * tp.sum() / max(2 * tp.sum() + fp.sum() + fn.sum(), 1), float(np.nanmean(f1)), pr, rc, f1

    def evalset(Y, E, name, langs=None):
        Prob = np.stack([c.predict_proba(E)[:, 1] for c in clfs], 1)
        P = (Prob > 0.5).astype(int)
        mi, ma, pr, rc, f1 = prf(Y, P)
        out = {"name": name, "n": len(Y), "micro": round(mi, 3), "macro": round(ma, 3),
               "exact": round(float((P == Y).all(1).mean()), 3),
               "per_class": {CLASSES[i]: {"p": round(float(pr[i]), 2), "r": round(float(rc[i]), 2),
                                          "f1": round(float(f1[i]), 2), "n": int(Y[:, i].sum())}
                             for i in range(len(CLASSES))}}
        best = (-1.0, 0.5, 0.0, 0.0)
        for t in np.linspace(0.2, 0.9, 15):
            m2, a2, _, _, _ = prf(Y, (Prob > t).astype(int))
            h = 2 * m2 * a2 / max(m2 + a2, 1e-9)
            if h > best[0]:
                best = (h, float(t), m2, a2)
        out["tuned"] = {"t": round(best[1], 2), "micro": round(best[2], 3), "macro": round(best[3], 3)}
        if langs:
            out["per_lang"] = {}
            for lg in sorted(set(langs)):
                idx = [i for i, x in enumerate(langs) if x == lg]
                m2, a2, _, _, _ = prf(Y[idx], P[idx])
                out["per_lang"][lg] = {"n": len(idx), "micro": round(m2, 3), "macro": round(a2, 3),
                                       "exact": round(float((P[idx] == Y[idx]).all(1).mean()), 3)}
        return out

    langs = [r["id"].split("-")[1] for r in ite]
    res = {"embedder": "google/embeddinggemma-300m", "dim": MRL, "seq": SEQ, "prompt": PROMPT,
           "indic_test": evalset(Yite, Eite, "indic_test", langs),
           "ml_test": evalset(Ymte, Emte, "ml_test")}
    with open("/vol/egemma256-results.json", "w") as f:
        json.dump(res, f, indent=2)
    volume.commit()
    return res


@app.local_entrypoint()
def main():
    res = run.remote()
    print(json.dumps(res, indent=2))
    it, en = res["indic_test"], res["indic_test"]["per_lang"]
    print(f"\nindic_test: micro {it['micro']} macro {it['macro']} | "
          f"tuned t={it['tuned']['t']} micro {it['tuned']['micro']} macro {it['tuned']['macro']}")
    for lg in sorted(en):
        print(f"  {lg}: micro {en[lg]['micro']} macro {en[lg]['macro']} (n={en[lg]['n']})")
