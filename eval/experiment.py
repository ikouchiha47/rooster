#!/usr/bin/env python3
"""Two cheap levers, measured against the same corpus/model:

  1. text mode  — embed SMS content only, vs sender + content (what the app does now)
  2. descriptions — the current tag sentences, vs a more discriminative set

Centered cosine, floors subject=0.25 nature=0.19 margin=0.08 (the sweep's best).

    MODEL_DIR=/path/to/model experiment.py
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

DESC_CURRENT = {
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

# Tuned: name the distinguishing signal directly, and separate the confusable pairs.
DESC_TUNED = dict(DESC_CURRENT)
DESC_TUNED["official"] = "a government advisory, a regulatory notice, a public alert or an official announcement"
DESC_TUNED["expense"] = "a bank transaction: an amount debited, credited, billed or recharged"
DESC_TUNED["personal"] = "a personal message between two people, a private conversation"
DESC_TUNED["promo"] = "an advertisement, a sale, a discount or a promotional offer from a business"
DESC_TUNED["festival"] = "a religious festival, a puja, a holiday or a cultural celebration"
DESC_TUNED["tech"] = "technology, software, semiconductors, gadgets or cybersecurity"
DESC_TUNED["incident"] = "a crime, an accident, an outage, a protest, a strike or a disaster"

# SMS senders (DLT headers / numbers) as they arrive on the device.
SENDERS = {
    "sms-strike": "VA-WBGBNK-S",
    "sms-promo-flat": "AD-RELIANCE-P",
    "sms-promo-bata": "AD-BATA-P",
    "sms-dues": "VM-BSNL-S",
    "sms-recharge": "VM-JIO-S",
    "sms-debit": "VM-HDFCBK-S",
    "sms-otp": "VM-HDFCBK-S",
    "sms-interview": "VM-ARCTIC-S",
    "sms-advisory": "JZ-NDMAEW-G",
    "sms-data-quota": "VM-JIO-S",
}


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


def select(scores):
    def leaders(group, floor, cap):
        ranked = sorted(((scores[t], t) for t in group), reverse=True)
        above = [(v, t) for v, t in ranked if v >= floor]
        if not above:
            return []
        best = above[0][0]
        return [t for v, t in above if v >= best - 0.08][:cap]

    return set(leaders(SUBJECTS, 0.25, 2)) | set(leaders(NATURES, 0.19, 3))


def run(sess, tok, corpus, desc, prepend_sender):
    vectors = {t: embed(sess, tok, d) for t, d in desc.items()}
    centroid = np.mean(list(vectors.values()), axis=0)

    def c(v):
        return (v - centroid) / np.linalg.norm(v - centroid)

    cv = {t: c(v) for t, v in vectors.items()}
    nok = 0
    for it in corpus:
        text = it["text"]
        if prepend_sender and it["source"] == "sms":
            text = SENDERS.get(it["id"], "VM-XXXXXX-S") + "\n" + text
        v = c(embed(sess, tok, text))
        got = select({t: float(v @ cv[t]) for t in cv})
        nok += got == set(it["expect"])
    return nok


def main():
    sess, tok = load()
    corpus = json.load(open(os.path.join(HERE, "corpus.json")))["items"]
    n = len(corpus)
    print(f"{'':22} {'content only':>14} {'sender+content':>16}")
    for label, desc in (("current descriptions", DESC_CURRENT), ("tuned descriptions", DESC_TUNED)):
        c_only = run(sess, tok, corpus, desc, prepend_sender=False)
        c_send = run(sess, tok, corpus, desc, prepend_sender=True)
        print(f"{label:22} {c_only:>10}/{n} {c_send:>14}/{n}")


if __name__ == "__main__":
    main()
