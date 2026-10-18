#!/usr/bin/env python3
"""Benchmark tiny transformer categorisers on the gold SMS set — locally.

Reuses mofy's torch venv (no install):
  ~/dev/ideas/mofy/ml/.venv/bin/python eval/tiny_transformer.py

Trains MiniLM / TinyBERT / bert-tiny as sequence classifiers on eval/train.jsonl,
evaluates on eval/test.jsonl (sender-grouped, human gold; `unknown` held out as
the abstain bucket), reports params + fp32/int8 size + accuracy + per-class P/R,
and compares against the BoW baseline (~0.914 tau=0.3) and rules (0.882).
"""
import argparse
import json
import os

import numpy as np
import torch
from sklearn.metrics import precision_recall_fscore_support
from transformers import (
    AutoModelForSequenceClassification,
    AutoTokenizer,
    DataCollatorWithPadding,
    Trainer,
    TrainingArguments,
)

CLASSES = ["personal", "transactional", "promotions", "purchases", "government", "spam"]
LABEL2ID = {c: i for i, c in enumerate(CLASSES)}


def load(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(x) for x in f if x.strip()]


class Enc(torch.utils.data.Dataset):
    def __init__(self, rows, tok):
        self.enc = tok([(r["sender"] + " . " + r["body"])[:2000] for r in rows],
                       truncation=True, max_length=128, padding=False)
        self.y = [LABEL2ID[r["label"]] for r in rows]

    def __len__(self):
        return len(self.y)

    def __getitem__(self, i):
        d = {k: self.enc[k][i] for k in self.enc}
        d["labels"] = self.y[i]
        return d


def tokenizer_for(name):
    try:
        return AutoTokenizer.from_pretrained(name)
    except Exception:
        from transformers import BertTokenizerFast
        return BertTokenizerFast.from_pretrained(name)


def model_for(name):
    # Old uploads (e.g. prajjwal1/bert-tiny) carry a config that AutoModel in
    # transformers 5.x no longer maps; the explicit Bert classes still load it.
    try:
        return AutoModelForSequenceClassification.from_pretrained(name, num_labels=len(CLASSES))
    except ValueError:
        from transformers import BertForSequenceClassification
        return BertForSequenceClassification.from_pretrained(name, num_labels=len(CLASSES))


def run(name, train_rows, test_rows):
    tok = tokenizer_for(name)
    model = model_for(name)
    args = TrainingArguments(output_dir="/tmp/tt_out", per_device_train_batch_size=16,
                             per_device_eval_batch_size=32, num_train_epochs=3, learning_rate=2e-5,
                             weight_decay=0.01, save_strategy="no", report_to="none", seed=7,
                             logging_steps=200, disable_tqdm=True)
    tr = Trainer(model=model, args=args, train_dataset=Enc(train_rows, tok),
                 eval_dataset=Enc(test_rows, tok), data_collator=DataCollatorWithPadding(tok))
    tr.train()
    logits = tr.predict(Enc(test_rows, tok)).predictions
    pred = logits.argmax(-1)
    y = np.array([LABEL2ID[r["label"]] for r in test_rows])
    acc = float((pred == y).mean())
    p, r, _, _ = precision_recall_fscore_support(y, pred, labels=range(len(CLASSES)), zero_division=0)
    params = sum(x.numel() for x in model.parameters())
    per = {CLASSES[i]: {"p": round(float(p[i]), 3), "r": round(float(r[i]), 3)} for i in range(len(CLASSES))}
    return {"model": name, "params": int(params), "fp32_mb": round(params * 4 / 1e6, 1),
            "int8_mb": round(params / 1e6, 1), "acc": round(acc, 3), "per_class": per}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", default=[
        "nreimers/MiniLM-L6-H384-uncased",
        "huawei-noah/TinyBERT_General_4L_312D",
        "prajjwal1/bert-tiny",
    ])
    ap.add_argument("--train", default="eval/train.jsonl")
    ap.add_argument("--test", default="eval/test.jsonl")
    a = ap.parse_args()
    train_rows = [r for r in load(a.train) if r["label"] in LABEL2ID]
    test_rows = [r for r in load(a.test) if r["label"] in LABEL2ID]
    print(f"train {len(train_rows)} / test {len(test_rows)}  (baselines: BoW ~0.914, rules 0.882)")
    for m in a.models:
        try:
            res = run(m, train_rows, test_rows)
            print(json.dumps(res, indent=2))
        except Exception as e:  # noqa: BLE001
            print(f"{m}: FAILED — {type(e).__name__}: {e}")


if __name__ == "__main__":
    main()
