#!/usr/bin/env python3
"""Multi-class RSS topic head: BoW baseline + MiniLM-L6 fine-tune.

Class categories: finance, official, politics, crime, incident, sports,
entertainment, tech, weather, travel, festival, other.
(labour-action is a rule, not a bucket — excluded.)
Data: eval/rss_train.jsonl / eval/rss_test.jsonl (story-grouped split).
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tiny_text import build_vocab, train_softmax, vectorise  # noqa: E402

try:
    from scipy.sparse import csr_matrix as _csr
    from sklearn.feature_extraction.text import CountVectorizer as _CV
    from sklearn.linear_model import LogisticRegression as _LR
    from sklearn.preprocessing import normalize as _norm
    HAVE_SPARSE = True
except ImportError:
    HAVE_SPARSE = False

HERE = os.path.dirname(os.path.abspath(__file__))
CLASSES = ["finance", "official", "politics", "crime", "incident", "sports", "entertainment",
           "tech", "weather", "travel", "festival", "health", "education", "other"]
LABEL2ID = {c: i for i, c in enumerate(CLASSES)}


def load(path):
    rows = [json.loads(x) for x in open(path, encoding="utf-8") if x.strip()]
    texts = [(r.get("subject", "") + "\n" + r.get("body", "")) for r in rows]
    y = np.array([LABEL2ID[r["label"]] for r in rows])
    return rows, texts, y


def show(name, y, p, rows):
    from sklearn.metrics import precision_recall_fscore_support
    acc = float((p == y).mean())
    pr, rc, _, _ = precision_recall_fscore_support(y, p, labels=range(len(CLASSES)), zero_division=0)
    print(f"{name}: acc {acc:.3f}")
    for i, c in enumerate(CLASSES):
        print(f"  {c:14s} P {pr[i]:.2f} R {rc[i]:.2f}")
    print("  misses:")
    for i in np.where(p != y)[0]:
        print(f"    true={CLASSES[y[i]]:14s} pred={CLASSES[p[i]]:14s} {rows[i]['subject'][:75]}")


def bow():
    from tiny_text import word_tokens  # noqa: E402
    tr_rows, tr_texts, ytr = load(TRAIN_PATH)
    te_rows, te_texts, yte = load(TEST_PATH)

    def feats(t):
        ws = word_tokens(t)
        return ws + [f"{a}_{b}" for a, b in zip(ws, ws[1:])]

    if HAVE_SPARSE and len(tr_texts) > 2000:
        # Dense float32 (11K x 150K) does not fit RAM; same family, sparse.
        from sklearn.feature_extraction.text import TfidfVectorizer  # noqa: E402
        vec = TfidfVectorizer(analyzer=feats, min_df=2, max_features=60000,
                              sublinear_tf=True, use_idf=False, norm="l2")
        Xtr, Xte = vec.fit_transform(tr_texts), vec.transform(te_texts)
        clf = _LR(C=1.0, max_iter=500, class_weight="balanced").fit(Xtr, ytr)
        print(f"[bow-sparse] vocab {len(vec.vocabulary_)} train {len(ytr)} test {len(yte)}")
        show("bow-lr", yte, clf.predict(Xte), te_rows)
        from sklearn.naive_bayes import MultinomialNB  # noqa: E402
        from sklearn.neighbors import NearestCentroid  # noqa: E402
        show("bow-nb", yte, MultinomialNB(alpha=1.0).fit(Xtr, ytr).predict(Xte), te_rows)
        from sklearn.naive_bayes import ComplementNB  # noqa: E402
        show("bow-cnb", yte, ComplementNB(alpha=1.0).fit(Xtr, ytr).predict(Xte), te_rows)
        show("rocchio", yte, NearestCentroid().fit(Xtr, ytr).predict(Xte), te_rows)
        return
    vocab = build_vocab(tr_texts, 1, 30000)
    Xtr, Xte = vectorise(tr_texts, vocab), vectorise(te_texts, vocab)
    print(f"[bow] vocab {len(vocab)} train {len(ytr)} test {len(yte)}")
    W, b = train_softmax(Xtr, ytr, len(CLASSES))
    show("bow", yte, ((Xte @ W + b).argmax(1)), te_rows)


def minilm():
    import torch
    from sklearn.metrics import precision_recall_fscore_support
    from sklearn.utils.class_weight import compute_class_weight
    from transformers import (AutoModelForSequenceClassification, AutoTokenizer,
                              DataCollatorWithPadding, Trainer, TrainingArguments)
    tr_rows, tr_texts, ytr = load(TRAIN_PATH)
    te_rows, te_texts, yte = load(TEST_PATH)

    class Enc(torch.utils.data.Dataset):
        def __init__(self, texts, y):
            self.enc = tok(texts, truncation=True, max_length=256, padding=False)
            self.y = list(y)

        def __len__(self):
            return len(self.y)

        def __getitem__(self, i):
            d = {k: self.enc[k][i] for k in self.enc}
            d["labels"] = self.y[i]
            return d

    name = "nreimers/MiniLM-L6-H384-uncased"
    tok = AutoTokenizer.from_pretrained(name)
    model = AutoModelForSequenceClassification.from_pretrained(name, num_labels=len(CLASSES))
    wts = torch.tensor(
        compute_class_weight("balanced", classes=np.arange(len(CLASSES)), y=ytr),
        dtype=torch.float32)
    print("class weights:", {c: round(float(w), 2) for c, w in zip(CLASSES, wts)})

    class WeightedTrainer(Trainer):
        def compute_loss(self, model, inputs, return_outputs=False, **kwargs):
            labels = inputs.pop("labels")
            out = model(**inputs)
            loss = torch.nn.functional.cross_entropy(
                out.logits, labels, weight=wts.to(out.logits.device))
            return (loss, out) if return_outputs else loss
    args = TrainingArguments(output_dir="/tmp/rss_out", per_device_train_batch_size=16,
                             per_device_eval_batch_size=32, num_train_epochs=15,
                             learning_rate=2e-5, weight_decay=0.01, save_strategy="no",
                             report_to="none", seed=SEED, logging_steps=200,
                             disable_tqdm=True)
    tr = WeightedTrainer(model=model, args=args, train_dataset=Enc(tr_texts, ytr),
                 eval_dataset=Enc(te_texts, yte), data_collator=DataCollatorWithPadding(tok))
    tr.train()
    pred = tr.predict(Enc(te_texts, yte)).predictions.argmax(-1)
    params = sum(x.numel() for x in model.parameters())
    print(f"[minilm] params {params} fp32 {params*4/1e6:.1f}MB int8 {params/1e6:.1f}MB")
    show("minilm", yte, pred, te_rows)


TRAIN_PATH = os.path.join(HERE, "rss_train.jsonl")
TEST_PATH = os.path.join(HERE, "rss_test.jsonl")
SEED = 7


def load_split(train_path, test_path):
    tr_rows, tr_texts, ytr = load(train_path)
    te_rows, te_texts, yte = load(test_path)
    return (tr_rows, tr_texts, ytr), (te_rows, te_texts, yte)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=["bow", "minilm", "both"], default="both")
    ap.add_argument("--train", default=None)
    ap.add_argument("--seed", type=int, default=None)
    a = ap.parse_args()
    if a.train:
        TRAIN_PATH = a.train
    if a.seed is not None:
        SEED = a.seed
    if a.model in ("bow", "both"):
        bow()
    if a.model in ("minilm", "both"):
        minilm()
