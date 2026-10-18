"""Fine-tune tiny transformer categorisers on the gold SMS set — on Modal (cloud GPU).

Local disk is not used for training. Run:

    pip install modal          # once (small)
    modal run eval/train_modal.py

It fine-tunes MiniLM / TinyBERT / bert-tiny on eval/train.jsonl, evaluates on
eval/test.jsonl (sender-grouped, human gold; `unknown` = abstain, held out),
exports int8 ONNX, and returns a size + accuracy table. Results also land in the
`rooster-eval` volume so repeat runs are cheap.

Gold data files are baked into the image (small), so only this script and the
two jsonl files leave the machine — GPU work stays on Modal.
"""
import json
import os
import tempfile

import modal

MODELS = [
    "nreimers/MiniLM-L6-H384-uncased",
    "huawei-noah/TinyBERT_General_4L_312D",
    "prajjwal1/bert-tiny",
]
CLASSES = ["personal", "transactional", "promotions", "purchases", "government", "spam"]


def _tokenizer_for(name):
    """AutoTokenizer works for MiniLM; the BERT repos ship only vocab.txt, so fall
    back to a fast WordPiece tokenizer built from it (no sentencepiece needed)."""
    from transformers import AutoTokenizer, BertTokenizerFast

    try:
        return AutoTokenizer.from_pretrained(name)
    except Exception:  # noqa: BLE001
        return BertTokenizerFast.from_pretrained(name)


app = modal.App("rooster-categorizer-train")

image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install("torch", "transformers", "datasets", "accelerate", "onnx", "onnxruntime", "numpy", "scikit-learn", "sentencepiece")
    .add_local_file("eval/train.jsonl", "/data/train.jsonl")
    .add_local_file("eval/test.jsonl", "/data/test.jsonl")
)
volume = modal.Volume.from_name("rooster-eval", create_if_missing=True)


def _rows(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(x) for x in f if x.strip()]


@app.function(image=image, gpu="T4", volumes={"/vol": volume}, timeout=3600)
def train_and_eval(model_name: str) -> dict:
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
    from datasets import Dataset

    label2id = {c: i for i, c in enumerate(CLASSES)}
    id2label = {i: c for c, i in label2id.items()}

    def prep(rs):
        return Dataset.from_list(
            [{"text": (r["sender"] + " . " + r["body"])[:2000], "label": label2id[r["label"]]} for r in rs]
        )

    train = prep([r for r in _rows("/data/train.jsonl") if r["label"] in label2id])
    test = prep([r for r in _rows("/data/test.jsonl") if r["label"] in label2id])

    tok = _tokenizer_for(model_name)
    try:
        model = AutoModelForSequenceClassification.from_pretrained(
            model_name, num_labels=len(CLASSES), id2label=id2label, label2id=label2id
        )
    except Exception:  # bert-tiny's config has no model_type for transformers 5.x
        from transformers import BertForSequenceClassification

        model = BertForSequenceClassification.from_pretrained(
            model_name, num_labels=len(CLASSES), id2label=id2label, label2id=label2id
        )
    train = train.map(lambda b: tok(b["text"], truncation=True, max_length=128), batched=True)
    test = test.map(lambda b: tok(b["text"], truncation=True, max_length=128), batched=True)

    args = TrainingArguments(
        output_dir="/tmp/out", per_device_train_batch_size=16, per_device_eval_batch_size=32,
        num_train_epochs=3, learning_rate=2e-5, weight_decay=0.01, logging_steps=50,
        save_strategy="no", report_to=[], seed=7,
    )
    trainer = Trainer(model=model, args=args, train_dataset=train,
                      data_collator=DataCollatorWithPadding(tok))
    trainer.train()

    logits = trainer.predict(test).predictions
    pred = logits.argmax(-1)
    y = np.array(test["label"])
    acc = float((pred == y).mean())
    p, r, _, _ = precision_recall_fscore_support(y, pred, labels=range(len(CLASSES)), zero_division=0)

    # size: params + weight-only int8 estimate (+ optional ONNX dynamic quant)
    params = sum(p_.numel() for p_ in model.parameters())
    params_bytes = params * 4
    int8_bytes = params  # weight-only
    try:
        import onnx
        from onnxruntime.quantization import quantize_dynamic, QuantType
        d = tempfile.mkdtemp()
        torch.onnx.export(model, (tok("hi", return_tensors="pt")["input_ids"], torch.zeros(1, dtype=torch.long)),
                          f"{d}/m.onnx", input_names=["input_ids", "attention_mask"], opset_version=14,
                          dynamic_axes={"input_ids": {0: 1, 1: 1}, "attention_mask": {0: 1, 1: 1}})
        quantize_dynamic(f"{d}/m.onnx", f"{d}/m.int8.onnx", weight_type=QuantType.QInt8)
        int8_bytes = os.path.getsize(f"{d}/m.int8.onnx")
    except Exception as e:  # noqa: BLE001
        pass

    result = {
        "model": model_name, "params": int(params), "fp32_bytes": params_bytes,
        "int8_bytes": int8_bytes, "test_acc": acc,
        "per_class": {CLASSES[i]: {"p": round(float(p[i]), 3), "r": round(float(r[i]), 3)} for i in range(len(CLASSES))},
        "n_test": int(len(y)),
    }
    with open(f"/vol/{model_name.split('/')[-1]}.json", "w") as f:
        json.dump(result, f, indent=2)
    volume.commit()
    return result


@app.local_entrypoint()
def main():
    print("baselines on the same split: BoW ~0.914 (tau=0.3) / rules 0.882")
    for m in MODELS:
        try:
            r = train_and_eval.remote(m)
            print(json.dumps(r, indent=2))
        except Exception as e:  # noqa: BLE001
            print(f"{m}: FAILED — {type(e).__name__}: {e}")
