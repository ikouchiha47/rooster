#!/usr/bin/env python3
"""Baseline eval for the embedding tagging rung.

Runs the real EmbeddingGemma (the same q4f16 ONNX model the app uses) over
eval/corpus.json and reports what the selection rule produces versus the
hand-labelled expectation — once with raw cosine, once with centered cosine
(the P2 change). Mirrors TagSelection.kt so it can be tuned here first.

    MODEL_DIR=/path/to/model eval.py [subject_min] [nature_min] [margin]

The model dir holds: model_q4f16.onnx, model_q4f16.onnx_data, and the tokenizer
under embeddinggemma_onnx_tokenizer.json.
"""
import json
import os
import sys

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.environ.get("MODEL_DIR", "/tmp/model")
PROMPT = "task: classification | query: "

SUBJECTS = {"weather", "finance", "tech", "travel", "festival", "games", "paper"}
NATURES = {"expense", "promo", "incident", "personal", "official"}

DESCRIPTIONS = {
    "weather": "weather, rain, floods, storms, a natural disaster",
    "finance": "finance, money, stock markets, banking, loans, interest rates",
    "tech": "technology, gadgets, software, artificial intelligence, cybersecurity",
    "travel": "travel, flights, trains, hotels, tourism, tickets and bookings",
    "festival": "a festival, a religious holiday or a cultural celebration",
    "games": "sports, games, matches, tournaments, cricket",
    "paper": "a newspaper or a publication",
    "expense": "a bill, a payment, a debit or credit, a recharge, a transaction with an amount",
    "promo": "a promotional offer, a discount, a cashback or a marketing message",
    "incident": "an accident, an outage, a strike, a disruption or something that went wrong",
    "personal": "a personal message from an individual person",
    "official": "an official notice from a government or an organisation",
}

SUBJECT_MIN = float(sys.argv[1]) if len(sys.argv) > 1 else 0.55
NATURE_MIN = float(sys.argv[2]) if len(sys.argv) > 2 else 0.45
MARGIN = float(sys.argv[3]) if len(sys.argv) > 3 else 0.10


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


def leaders(scores, group, floor, margin, cap):
    ranked = sorted(((scores[t], t) for t in group), reverse=True)
    above = [(v, t) for v, t in ranked if v >= floor]
    if not above:
        return []
    best = above[0][0]
    return [t for v, t in above if v >= best - margin][:cap]


def select(scores):
    tags = set(leaders(scores, SUBJECTS, SUBJECT_MIN, MARGIN, 2))
    tags |= set(leaders(scores, NATURES, NATURE_MIN, MARGIN, 3))
    return tags


def main():
    sess, tok = load()
    desc = {t: embed(sess, tok, d) for t, d in DESCRIPTIONS.items()}
    centroid = np.mean(list(desc.values()), axis=0)

    def centered(v):
        return (v - centroid) / np.linalg.norm(v - centroid)

    corpus = json.load(open(os.path.join(HERE, "corpus.json")))["items"]
    raw_pass = ctr_pass = 0
    print(f"floors subject={SUBJECT_MIN} nature={NATURE_MIN} margin={MARGIN}\n")
    print(f"{'id':18} {'expect':26} {'raw':24} {'centered':24}")
    for it in corpus:
        v = embed(sess, tok, it["text"])
        raw = {t: float(v @ desc[t]) for t in desc}
        ctr = {t: float(centered(v) @ centered(desc[t])) for t in desc}
        got_raw, got_ctr = select(raw), select(ctr)
        exp = set(it["expect"])
        raw_pass += got_raw == exp
        ctr_pass += got_ctr == exp
        mark = "" if got_ctr == exp else "  <-"
        print(f"{it['id']:18} {','.join(sorted(exp)):26} {','.join(sorted(got_raw)):24} {','.join(sorted(got_ctr)):24}{mark}")
    n = len(corpus)
    print(f"\nraw exact-match      : {raw_pass}/{n}")
    print(f"centered exact-match : {ctr_pass}/{n}")


if __name__ == "__main__":
    main()
