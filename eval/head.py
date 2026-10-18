#!/usr/bin/env python3
"""Frozen-embedding linear heads vs the content rules, on a sender-grouped split.

Vector space (frozen, identical to `eval.py` -- nothing is fine-tuned):
  EmbeddingGemma `model_q4f16.onnx`, `task: classification | query: ` prompt,
  tokenizer truncated to 256 tokens, read out `sentence_embedding` **and sliced
  to the first 256 dims**, then L2-normalised. That 256-d unit vector is the
  frozen representation; the heads are trained on top and never touch the model.

Heads (6 trained classes; `unknown` is abstain, never a trained class):
  (a) multinomial logistic regression (numpy, softmax + L2)
  (b) nearest-centroid baseline (mean unit vector per class, max cosine)

Split is by SENDER, not row: the corpus has ~428 duplicate bodies and senders
that mix labels (e.g. `TX-SWIGGY-S` = OTP+transaction+updates), so a row split
leaks. Groups are shuffled with a fixed seed; every message of a sender lands on
one side.

LABELS: there are no human labels yet, so by default this trains on the corpus's
OLD `SmsClassifier` column -- a **weak-label distillation baseline, NOT model
validation**. Pass a filled `categories.label.template.json` with `--labels` to
switch to human labels.

    MODEL_DIR=/path/to/model python3 eval/head.py
    MODEL_DIR=/path/to/model python3 eval/head.py --labels eval/categories.label.template.json
"""
import argparse
import hashlib
import json
import os
import re
import sys
from collections import Counter, defaultdict

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from category_rules import UNKNOWN_ID, categorize  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.environ.get("MODEL_DIR", "/tmp/model")
CORPUS = os.environ.get(
    "SMS_CORPUS",
    "/var/folders/2_/4mhmjvl14tbdpwyskd146vmc0000gn/T/opencode/sms_corpus.txt",
)
PROMPT = "task: classification | query: "
CACHE = os.path.join(HERE, "embeddings_q4f16_256.npz")

TRAINED = ["personal", "transactional", "promotions", "purchases", "government", "spam"]
ALL_CLASSES = TRAINED + [UNKNOWN_ID]

# old noisy SmsClassifier label -> weak taxonomy label (None -> unknown/abstain)
OLD_TO_WEAK = {
    "PROMOTIONAL": "promotions",
    "BILL": "transactional",
    "TRANSACTION": "transactional",
    "OTP": "transactional",
    "TRAVEL_LOGISTICS": "purchases",
    "SPAM_FRAUD": "spam",
    "PERSONAL": "personal",
    "UPDATES": UNKNOWN_ID,
}


# --- frozen embeddings (same loader as eval.py) -----------------------------

def load_model():
    import onnxruntime as ort
    from tokenizers import Tokenizer

    sess = ort.InferenceSession(f"{MODEL_DIR}/model_q4f16.onnx", providers=["CPUExecutionProvider"])
    tok = Tokenizer.from_file(f"{MODEL_DIR}/embeddinggemma_onnx_tokenizer.json")
    return sess, tok


def check_runtime():
    """Fail with a precise message rather than a traceback when the runtime is absent."""
    missing = []
    for mod in ("numpy", "onnxruntime", "tokenizers"):
        try:
            __import__(mod)
        except ImportError:
            missing.append(mod)
    for f in ("model_q4f16.onnx", "embeddinggemma_onnx_tokenizer.json"):
        if not os.path.exists(os.path.join(MODEL_DIR, f)):
            missing.append(f"{MODEL_DIR}/{f}")
    if missing:
        print("Cannot run: missing " + ", ".join(missing))
        print("  install:  pip install numpy onnxruntime tokenizers")
        print("  model:    MODEL_DIR=/path/to/dir with model_q4f16.onnx (+_data) and the tokenizer json")
        print("The labelling sheet (eval/categories.label.template.json) and the rules "
              "(eval/category_rules.py) do not need the model.")
        return False
    return True


def embed(sess, tok, text):
    """One text -> frozen 256-d unit vector (identical shape/space to eval.py)."""
    ids = tok.encode(PROMPT + text).ids[:256]
    n = len(ids)
    ii = np.array([ids + [0] * (256 - n)], np.int64)
    am = np.array([[1] * n + [0] * (256 - n)], np.int64)
    v = sess.run(["sentence_embedding"], {"input_ids": ii, "attention_mask": am})[0][0][:256]
    return v / np.linalg.norm(v)


def embed_many(sess, tok, texts, batch=32):
    """Batched form of embed() -- same prompt/truncation/mask/slice, just grouped.

    Padding is attention-masked, so each row equals the single-item embedding.
    """
    enc = [tok.encode(PROMPT + t).ids[:256] for t in texts]
    out = None
    for start in range(0, len(enc), batch):
        chunk = enc[start:start + batch]
        # Fixed width 256, exactly like eval.py's single-item path, so a row is
        # bit-for-bit the same vector whether run alone or batched.
        ii = np.zeros((len(chunk), 256), np.int64)
        am = np.zeros((len(chunk), 256), np.int64)
        for i, e in enumerate(chunk):
            ii[i, :len(e)] = e
            am[i, :len(e)] = 1
        v = sess.run(["sentence_embedding"], {"input_ids": ii, "attention_mask": am})[0][:, :256]
        v = v / np.linalg.norm(v, axis=1, keepdims=True)
        out = v if out is None else np.vstack([out, v])
        if start % (batch * 20) == 0:
            print(f"[embeddings] {start + len(chunk)}/{len(enc)}", flush=True)
    return out


def embeddings(bodies, use_cache=True):
    digest = hashlib.sha256("\x00".join(bodies).encode("utf-8")).hexdigest()
    # Cache keyed by content, so a different label sheet gets its own file
    # instead of clobbering the full-corpus cache.
    cache = CACHE.replace(".npz", f"_{digest[:12]}.npz")
    if use_cache and os.path.exists(cache):
        z = np.load(cache, allow_pickle=True)
        if str(z["digest"]) == digest:
            print(f"[embeddings] cache hit {cache}")
            return z["X"]
    sess, tok = load_model()
    print(f"[embeddings] embedding {len(bodies)} bodies on {MODEL_DIR} ...", flush=True)
    X = embed_many(sess, tok, bodies)
    np.savez(cache, X=X, digest=digest)
    return X


# --- heads ------------------------------------------------------------------

def train_logistic(X, y, classes, iters=800, lr=0.5, l2=1e-3):
    mu, sd = X.mean(0), X.std(0) + 1e-8
    Xs = (X - mu) / sd
    idx = {c: i for i, c in enumerate(classes)}
    Y = np.zeros((len(y), len(classes)))
    for i, c in enumerate(y):
        Y[i, idx[c]] = 1.0
    W = np.zeros((Xs.shape[1], len(classes)))
    b = np.zeros(len(classes))
    for _ in range(iters):
        logits = Xs @ W + b
        logits -= logits.max(1, keepdims=True)
        p = np.exp(logits)
        p /= p.sum(1, keepdims=True)
        g = (p - Y) / len(y)
        W -= lr * (Xs.T @ g + l2 * W)
        b -= lr * g.sum(0)
    return {"mu": mu, "sd": sd, "W": W, "b": b, "classes": classes}


def predict_logistic(m, X):
    p = _softmax(((X - m["mu"]) / m["sd"]) @ m["W"] + m["b"])
    return p


def _softmax(z):
    z = z - z.max(1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(1, keepdims=True)


def train_centroid(X, y, classes):
    C = {}
    for c in classes:
        mask = np.array([v == c for v in y])
        if mask.sum():
            cvec = X[mask].mean(0)
            C[c] = cvec / (np.linalg.norm(cvec) + 1e-12)
    return {"centroids": C, "classes": set(C)}


def predict_centroid(m, X):
    names = list(m["centroids"])
    sims = X @ np.stack([m["centroids"][c] for c in names], axis=1)
    return names, sims


def decide(scores, names, tau):
    """Predicted label ids for a score matrix (higher = better), abstain below tau."""
    out = []
    for row in scores:
        j = int(np.argmax(row))
        out.append(names[j] if row[j] >= tau else UNKNOWN_ID)
    return out


def decide_proba(p, classes, tau):
    out = []
    for row in p:
        j = int(np.argmax(row))
        out.append(classes[j] if row[j] >= tau else UNKNOWN_ID)
    return out


# --- metrics ----------------------------------------------------------------

def per_class(y_true, y_pred, classes):
    rows = {}
    for c in classes:
        tp = sum(1 for a, b in zip(y_true, y_pred) if a == c and b == c)
        fp = sum(1 for a, b in zip(y_true, y_pred) if a != c and b == c)
        fn = sum(1 for a, b in zip(y_true, y_pred) if a == c and b != c)
        sup = sum(1 for a in y_true if a == c)
        p = tp / (tp + fp) if tp + fp else float("nan")
        r = tp / (tp + fn) if tp + fn else float("nan")
        f1 = 2 * p * r / (p + r) if p and r and not np.isnan(p) and not np.isnan(r) else float("nan")
        rows[c] = {"support": sup, "precision": p, "recall": r, "f1": f1}
    return rows


def summarise(name, y_true, y_pred, rules_unknown_mask):
    acc = sum(1 for a, b in zip(y_true, y_pred) if a == b) / len(y_true)
    abstain = sum(1 for b in y_pred if b == UNKNOWN_ID) / len(y_pred)
    idx = [i for i, m in enumerate(rules_unknown_mask) if m]
    if idx:
        rescued = [i for i in idx if y_pred[i] != UNKNOWN_ID]
        coverage = len(rescued) / len(idx)
        correct = sum(1 for i in rescued if y_pred[i] == y_true[i])
        rescue_p = correct / len(rescued) if rescued else float("nan")
    else:
        coverage, rescue_p = float("nan"), float("nan")
    return {"name": name, "accuracy": acc, "abstain_rate": abstain,
            "unknown_bucket_n": len(idx), "unknown_coverage": coverage,
            "rescue_precision": rescue_p,
            "per_class": per_class(y_true, y_pred, TRAINED)}


def print_block(s):
    print(f"\n== {s['name']} ==")
    print(f"  accuracy {s['accuracy']:.3f} | abstain {s['abstain_rate']:.3f} | "
          f"rules-unknown bucket n={s['unknown_bucket_n']} coverage {s['unknown_coverage']:.3f} "
          f"rescue-precision {s['rescue_precision']:.3f}")
    print(f"  {'class':14} {'sup':>5} {'P':>6} {'R':>6} {'F1':>6}")
    for c in TRAINED:
        r = s["per_class"][c]
        print(f"  {c:14} {r['support']:>5} {r['precision']:>6.3f} {r['recall']:>6.3f} {r['f1']:>6.3f}")


# --- data -------------------------------------------------------------------

def load_corpus():
    rows = []
    with open(CORPUS, encoding="utf-8") as fh:
        for line in fh.read().splitlines():
            if line.count("|") < 2:
                continue
            sender, old, body = (p.strip() for p in line.split("|", 2))
            if not sender or not body:
                continue
            rows.append({"sender": sender, "old": old, "body": body,
                         "rules": categorize(sender, body)})
    return rows


def load_human_labels(path, rows):
    sheet = json.load(open(path, encoding="utf-8"))
    labelled = [(i["sender"], i["body"], i["label"].strip()) for i in sheet["items"] if i.get("label", "").strip()]
    valid = set(ALL_CLASSES)
    bad = [lb for _, _, lb in labelled if lb not in valid]
    if bad:
        print(f"  ! dropping labels outside taxonomy: {set(bad)}")
    return [(s, b, lb) for s, b, lb in labelled if lb in valid]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--labels", help="filled label sheet; default = weak labels from the old column")
    ap.add_argument("--no-cache", action="store_true")
    ap.add_argument("--tau-logistic", type=float, default=0.5)
    ap.add_argument("--tau-centroid", type=float, default=0.30)
    ap.add_argument("--test-frac", type=float, default=0.30)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    rows = load_corpus()
    if not check_runtime():
        return
    human = None
    if args.labels:
        human = load_human_labels(args.labels, rows)
        if not human:
            print(f"\n!! {args.labels} has no filled `label` values yet — nothing to train on.")
            print("   Fill `label` (uses the same `sender`/`body`), then rerun. Falling back is refused.")
            return
        senders = np.array([s for s, _, _ in human])
        bodies = [b for _, b, _ in human]
        y = [lb for _, _, lb in human]
        mode = f"HUMAN labels ({len(human)} items)"
        flag = ""
    else:
        senders = np.array([r["sender"] for r in rows])
        bodies = [r["body"] for r in rows]
        y = [OLD_TO_WEAK.get(r["old"], UNKNOWN_ID) for r in rows]
        mode = "WEAK labels (old SmsClassifier column)"
        flag = ("  *** WEAK-LABEL DISTILLATION BASELINE — NOT MODEL VALIDATION ***\n"
                "  *** target = the old classifier's own output; a high score only            ***\n"
                "  *** proves the plumbing learns the old labels, not that they are right.    ***\n"
                "  *** provide a filled --labels sheet for real numbers.                      ***")

    rules_pred = [categorize(s, b) for s, b in zip(senders, bodies)]
    n_dup = len(bodies) - len({re.sub(r'\d+', '<n>', b.lower()) for b in bodies})
    mixed = sum(1 for s in set(senders) if len({yy for ss, yy in zip(senders, y) if ss == s}) > 1)
    print(f"\nmode: {mode}")
    if flag:
        print(flag)
    print(f"corpus: {len(bodies)} bodies | ~{n_dup} duplicate(normalised-digit) bodies | "
          f"{mixed} mixed-label senders -> sender-grouped split mandatory")
    print(f"weak/real label counts: {dict(Counter(y))}")

    X = embeddings(bodies, use_cache=not args.no_cache)

    # sender-grouped split
    rng = np.random.default_rng(args.seed)
    uniq = np.array(sorted(set(senders)))
    rng.shuffle(uniq)
    n_test = max(1, int(round(len(uniq) * args.test_frac)))
    test_senders = set(uniq[:n_test])
    test = np.array([s in test_senders for s in senders])
    tr, te = np.where(~test)[0], np.where(test)[0]
    print(f"split: {len(tr)} train / {len(te)} test rows "
          f"({len(set(senders)) - len(test_senders)} / {len(test_senders)} senders)")

    y_arr = np.array(y)
    Xtr, ytr = X[tr], y_arr[tr]
    Xte = X[te]
    yte = y_arr[te].tolist()
    rules_te = [rules_pred[i] for i in te]
    rules_unknown_mask = [p == UNKNOWN_ID for p in rules_te]

    classes = [c for c in TRAINED if (ytr == c).sum() > 0]
    missing = [c for c in TRAINED if c not in classes]
    print(f"trained classes present in train: {classes}")
    if missing:
        print(f"  ! classes with ZERO training examples (cannot be learned from these labels): {missing}")

    # A head never trains on `unknown`: those rows are abstain, not a class.
    fit = np.array([yy in classes for yy in ytr])
    Xfit, yfit = Xtr[fit], ytr[fit]
    print(f"head fit rows: {int(fit.sum())}/{len(ytr)} (dropped {len(ytr) - int(fit.sum())} untrained/unknown)")

    # head (a): logistic
    logit = train_logistic(Xfit, yfit, classes)
    p_te = predict_logistic(logit, Xte)
    tau_sweep = [0.3, 0.4, 0.5, 0.6, 0.7]
    print("\nlogistic abstain sweep (tau -> abstain / rules-unknown coverage):")
    for t in tau_sweep:
        pred = decide_proba(p_te, classes, t)
        ab = sum(1 for q in pred if q == UNKNOWN_ID) / len(pred)
        idx = [i for i, m in enumerate(rules_unknown_mask) if m]
        cov = sum(1 for i in idx if pred[i] != UNKNOWN_ID) / len(idx) if idx else float("nan")
        print(f"  tau={t:.2f}  abstain={ab:.3f}  unknown-coverage={cov:.3f}")
    head_pred = decide_proba(p_te, classes, args.tau_logistic)

    # head (b): nearest centroid
    cent = train_centroid(Xfit, yfit, classes)
    names, sims = predict_centroid(cent, Xte)
    cent_pred = decide(sims, names, args.tau_centroid)

    # rules + head-on-unknown (logistic primary)
    combined = [head_pred[i] if rules_te[i] == UNKNOWN_ID else rules_te[i] for i in range(len(te))]

    print("\n" + "=" * 72)
    print("THREE-WAY COMPARISON  (same sender-grouped test set; truth = {} )".format(mode.split()[0]))
    print("=" * 72)
    for name, pred in [("rules-only", rules_te),
                       (f"head-only (logistic, tau={args.tau_logistic})", head_pred),
                       ("head-only (nearest-centroid, tau=%.2f)" % args.tau_centroid, cent_pred),
                       ("rules + head-on-unknown (logistic)", combined)]:
        print_block(summarise(name, yte, pred, rules_unknown_mask))

    print("\nNote: `unknown` is abstain, never a trained class. rules-unknown coverage = "
          "fraction of the rules' abstentions this method resolves; rescue-precision = "
          "fraction of those resolutions that match the (weak) label.")


if __name__ == "__main__":
    main()
