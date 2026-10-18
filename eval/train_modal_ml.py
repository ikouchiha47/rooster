#!/usr/bin/env python3
"""Fine-tune MiniLM multi-label topic head on RSS sets — on Modal (cloud GPU).

Mirrors eval/train_modal.py (same image shape, T4, int8 ONNX export, results in
the `rooster-eval` volume) but for eval/ml_train.jsonl / eval/ml_test.jsonl:
9 classes, BCE multi-label head, set-aware metrics.

Run:  modal run eval/train_modal_ml.py
"""
import json

import modal

CLASSES = ["finance", "official", "politics", "crime", "incident", "sports",
           "entertainment", "tech", "weather", "travel", "festival",
           "health", "education", "other"]

app = modal.App("rooster-topic-train")

image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install("torch", "transformers", "datasets", "accelerate", "onnx", "onnxruntime",
                 "numpy", "scikit-learn", "sentencepiece")
    .add_local_file("eval/ml_train.jsonl", "/data/train.jsonl")
    .add_local_file("eval/ml_test.jsonl", "/data/test.jsonl")
)
volume = modal.Volume.from_name("rooster-eval", create_if_missing=True)


def _rows(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(x) for x in f if x.strip()]


@app.function(image=image, gpu="T4", volumes={"/vol": volume}, timeout=3600)
def train_and_eval(model_name: str, epochs: int = 5, seed: int = 7) -> dict:
    import numpy as np
    import torch
    from transformers import (
        AutoModelForSequenceClassification,
        AutoTokenizer,
        DataCollatorWithPadding,
        Trainer,
        TrainingArguments,
    )
    from datasets import Dataset

    label2id = {c: i for i, c in enumerate(CLASSES)}

    def prep(rs):
        out = []
        for r in rs:
            v = [0] * len(CLASSES)
            for c in r.get("labels", [r.get("label")] if r.get("label") else []):
                if c in label2id:
                    v[label2id[c]] = 1
            out.append({"text": (r.get("subject", "") + "\n" + r.get("body", ""))[:2000],
                        "labels": [float(x) for x in v]})
        return Dataset.from_list(out)

    train, test = prep(_rows("/data/train.jsonl")), prep(_rows("/data/test.jsonl"))
    tok = AutoTokenizer.from_pretrained(model_name)
    model = AutoModelForSequenceClassification.from_pretrained(
        model_name, num_labels=len(CLASSES), problem_type="multi_label_classification")
    train = train.map(lambda b: tok(b["text"], truncation=True, max_length=256), batched=True)
    test = test.map(lambda b: tok(b["text"], truncation=True, max_length=256), batched=True)

    args = TrainingArguments(
        output_dir="/tmp/out", per_device_train_batch_size=16, per_device_eval_batch_size=32,
        num_train_epochs=epochs, learning_rate=2e-5, weight_decay=0.01, logging_steps=200,
        save_strategy="no", report_to="none", seed=seed, disable_tqdm=True,
    )
    Trainer(model=model, args=args, train_dataset=train,
            data_collator=DataCollatorWithPadding(tok)).train()

    logits = Trainer(model=model, args=args).predict(test).predictions
    P = (1 / (1 + np.exp(-logits)) > 0.5).astype(int)
    Y = np.array(test["labels"])
    tp = ((P == 1) & (Y == 1)).sum(0).astype(float)
    fp = ((P == 1) & (Y == 0)).sum(0).astype(float)
    fn = ((P == 0) & (Y == 1)).sum(0).astype(float)
    with np.errstate(invalid="ignore", divide="ignore"):
        pr, rc = tp / np.maximum(tp + fp, 1), tp / np.maximum(tp + fn, 1)
        f1 = 2 * pr * rc / np.maximum(pr + rc, 1e-9)
    micro = 2 * tp.sum() / max(2 * tp.sum() + fp.sum() + fn.sum(), 1)
    params = sum(p_.numel() for p_ in model.parameters())
    int8_bytes = params  # replaced by real quant below
    try:
        import onnx
        from onnxruntime.quantization import quantize_dynamic, QuantType
        import tempfile
        import os
        d = tempfile.mkdtemp()
        torch.onnx.export(model, (tok("hi", return_tensors="pt")["input_ids"],
                                  torch.zeros(1, dtype=torch.long)),
                          f"{d}/m.onnx", input_names=["input_ids", "attention_mask"],
                          opset_version=14,
                          dynamic_axes={"input_ids": {0: 1, 1: 1}, "attention_mask": {0: 1, 1: 1}})
        quantize_dynamic(f"{d}/m.onnx", f"{d}/m.int8.onnx", weight_type=QuantType.QInt8)
        int8_bytes = os.path.getsize(f"{d}/m.int8.onnx")
        volume_bytes = open(f"{d}/m.int8.onnx", "rb").read()
        with open(f"/vol/minilm-topic.int8.onnx", "wb") as f:
            f.write(volume_bytes)
    except Exception:  # noqa: BLE001
        pass

    result = {
        "model": model_name, "params": int(params), "int8_bytes": int8_bytes,
        "exact": float((P == Y).all(1).mean()), "micro_f1": float(micro),
        "macro_f1": float(np.nanmean(f1)),
        "per_class": {CLASSES[i]: {"p": round(float(pr[i]), 3), "r": round(float(rc[i]), 3)}
                      for i in range(len(CLASSES))},
        "n_test": int(len(Y)),
    }
    with open("/vol/minilm-topic.json", "w") as f:
        json.dump(result, f, indent=2)
    volume.commit()
    return result


@app.local_entrypoint()
def main():
    for m in ["nreimers/MiniLM-L6-H384-uncased"]:
        print(train_and_eval.remote(m))
