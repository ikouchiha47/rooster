#!/usr/bin/env python3
"""kNN over labelled examples, vs the description-cosine rung.

Each corpus item is an (embedding, tag-set) example. A query item is classified
by its k nearest labelled neighbours (leave-one-out, so an item never votes for
itself) — a weighted vote: a tag scores the best similarity among neighbours that
carry it, and tags above a threshold are predicted.

This is supervised: accuracy should climb as the labelled set grows (add to
corpus.json). Reports exact-set-match, leave-one-out, for a few k and threshold.

    MODEL_DIR=/path/to/model knn.py
"""
import json
import os
import sys
from collections import defaultdict

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.environ.get("MODEL_DIR", "/tmp/model")
PROMPT = "task: classification | query: "


def load():
    sess = ort.InferenceSession(f"{MODEL_DIR}/model_q4f16.onnx", providers=["CPUExecutionProvider"])
    tok = Tokenizer.from_file(f"{MODEL_DIR}/embeddinggemma_onnx_tokenizer.json")
    return sess, tok


def embed(sess, tok, text):
    ids = tok.encode(PROMPT + text).ids[:256]
    n = len(ids)
    ii = np.array([ids + [0] * (256 - n)], np.int64)
    am = np.array([[1] * n + [0] * (256 - n)], np.int64)
    v = sess.run(["sentence_embedding"], {"input_ids": ii, "attention_mask": am})[0][0][:256]
    return v / np.linalg.norm(v)


def evaluate(X, Y, k, thresh):
    n = len(X)
    correct = 0
    for i in range(n):
        sims = X @ X[i]
        sims[i] = -np.inf
        nn = np.argsort(-sims)[:k]
        score = defaultdict(float)
        for j in nn:
            for tag in Y[j]:
                score[tag] = max(score[tag], float(sims[j]))
        pred = {t for t, s in score.items() if s >= thresh}
        correct += pred == set(Y[i])
    return correct / n


def main():
    sess, tok = load()
    items = json.load(open(os.path.join(HERE, "corpus.json")))["items"]
    raw = np.stack([embed(sess, tok, it["text"]) for it in items])
    cen = raw - raw.mean(axis=0)
    cen = cen / np.linalg.norm(cen, axis=1, keepdims=True)
    Y = [it["expect"] for it in items]

    print(f"{len(items)} labelled items\n")
    for name, X in (("raw", raw), ("centered", cen)):
        print(f"--- {name} ---")
        for k in (1, 3, 5):
            row = " ".join(f"t={t}:{evaluate(X, Y, k, t):.2f}" for t in (0.2, 0.3, 0.4, 0.5))
            print(f"  k={k}  {row}")


if __name__ == "__main__":
    main()
