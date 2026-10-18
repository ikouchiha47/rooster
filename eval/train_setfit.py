#!/usr/bin/env python3
"""SetFit binary labour head, backbone shootout.

Contrastive-tune the encoder on mined pairs, freeze, train logistic head.
Compares backbones on identical data: BAAI/bge-small-en-v1.5 (retrieval-tuned)
vs sentence-transformers/all-MiniLM-L6-v2 (MLM lineage, ST-native).
Evaluates frozen test AND paraphrase test (robustness is the question).
Usage: python eval/train_setfit.py --backbone BAAI/bge-small-en-v1.5
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load(path):
    rows = [json.loads(x) for x in open(path, encoding="utf-8") if x.strip()]
    texts = [(r.get("subject", "") + "\n" + r.get("body", "")) for r in rows]
    y = [1 if "labour-action" in r.get("labels", [r.get("label")] if r.get("label") else []) else 0
         for r in rows]
    return rows, texts, y


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backbone", required=True)
    ap.add_argument("--train", default=os.path.join(HERE, "ml_train.jsonl"))
    a = ap.parse_args()
    from datasets import Dataset
    from setfit import SetFitModel, SetFitTrainer

    _, tr_texts, ytr = load(a.train)
    te_rows, te_texts, yte = load(os.path.join(HERE, "ml_test.jsonl"))
    _, pa_texts, ypa = load(os.path.join(HERE, "ml_test_para.jsonl"))

    train_ds = Dataset.from_dict({"text": tr_texts, "label": ytr})
    model = SetFitModel.from_pretrained(a.backbone)
    trainer = SetFitTrainer(model=model, train_dataset=train_ds, seed=7,
                            num_iterations=5, num_epochs=1)
    trainer.train()

    for name, texts, y in (("frozen", te_texts, yte), ("para", pa_texts, ypa)):
        import numpy as np
        p = np.array(trainer.model.predict(texts))
        y = np.array(y)
        acc = float((p == y).mean())
        tp, tn = int(((p == 1) & (y == 1)).sum()), int(((p == 0) & (y == 0)).sum())
        fp, fn = int(((p == 1) & (y == 0)).sum()), int(((p == 0) & (y == 1)).sum())
        print(f"setfit[{a.backbone.split('/')[-1]}] {name}: acc {acc:.3f} "
              f"TP {tp} TN {tn} FP {fp} FN {fn}", flush=True)


if __name__ == "__main__":
    main()
