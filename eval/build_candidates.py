#!/usr/bin/env python3
"""Build the human labelling sheet: cluster-first category representatives.

The classifier's bottleneck is labels, not the model. This picks a small,
reviewable set of cluster representatives so a human can label ~300 messages in
one sitting, over-sampling the pockets the content rules leak on (rules-`unknown`,
non-DLT senders, `-S` service messages, Bengali/Devanagari).

Clustering: bodies are normalised (URLs -> <url>, long digit runs -> <n>,
punctuation/whitespace collapsed), then deduped on (normalised body, sender).
Near-duplicates are collapsed for representative diversity by a digit-masked
signature, so an OTP/amount variant does not occupy a slot.

`suggested` is a **weak labeller guess** (rule output first, else a map from the
corpus's OLD noisy `category` column) and is marked `suggested_source`. It is not
truth; the human fills `label`.

    python3 eval/build_candidates.py            # writes categories.label.template.json
    python3 eval/build_candidates.py 320        # change target size
"""
import json
import os
import random
import re
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from category_rules import UNKNOWN_ID, categorize, dlt_suffix, sender_kind  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CORPUS = os.environ.get(
    "SMS_CORPUS",
    "/var/folders/2_/4mhmjvl14tbdpwyskd146vmc0000gn/T/opencode/sms_corpus.txt",
)
OUT = os.path.join(HERE, "categories.label.template.json")
SEED = 20261013

# Rule-`unknown` first: the head exists mainly to rescue that bucket.
POCKETS = [
    ("rules_unknown", 90),
    ("service_s", 55),
    ("non_dlt", 45),
    ("script_bn", 35),
    ("script_hi", 35),
]

SCRIPT_BN = re.compile(r"[\u0980-\u09FF]")
SCRIPT_HI = re.compile(r"[\u0900-\u097F]")

# old noisy SmsClassifier label -> weak taxonomy guess (None = abstain in weak pass)
OLD_TO_SUGGEST = {
    "PROMOTIONAL": "promotions",
    "BILL": "transactional",
    "TRANSACTION": "transactional",
    "OTP": "transactional",
    "TRAVEL_LOGISTICS": "purchases",
    "SPAM_FRAUD": "spam",
    "PERSONAL": "personal",
    "UPDATES": None,
}


def normalise(body):
    b = body.lower()
    b = re.sub(r"https?://\S+|www\.\S+", " <url> ", b)
    b = re.sub(r"\d{4,}", " <n> ", b)
    b = re.sub(r"[^a-z0-9\u0900-\u097F\u0980-\u09FF]+", " ", b)
    return re.sub(r"\s+", " ", b).strip()


def signature(body):
    """Digit-masked normalised body: collapses OTP/amount template variants."""
    return re.sub(r"\d+", "<n>", normalise(body))


def load_corpus():
    rows = []
    with open(CORPUS, encoding="utf-8") as fh:
        for line in fh.read().splitlines():
            if line.count("|") < 2:
                continue
            sender, old, body = (p.strip() for p in line.split("|", 2))
            if not sender or not body:
                continue
            rows.append({"sender": sender, "old": old, "body": body})
    return rows


def pocket_of(row):
    s, b = row["sender"], row["body"]
    if row["rules_category"] == UNKNOWN_ID:
        return "rules_unknown"
    if SCRIPT_BN.search(b):
        return "script_bn"
    if SCRIPT_HI.search(b):
        return "script_hi"
    if dlt_suffix(s) == "S":
        return "service_s"
    if dlt_suffix(s) is None and sender_kind(s) == "header":
        return "non_dlt"
    return None


def main():
    target = int(sys.argv[1]) if len(sys.argv) > 1 else 320
    rows = load_corpus()
    for r in rows:
        r["norm"] = normalise(r["body"])
        r["sig"] = signature(r["body"])
        r["rules_category"] = categorize(r["sender"], r["body"])
        r["pocket"] = pocket_of(r)

    rng = random.Random(SEED)
    for r in rows:
        r["_key"] = rng.random()

    # Exact cluster dedupe (normalised body + sender).
    clusters = {}
    for r in rows:
        clusters.setdefault((r["norm"], r["sender"]), r)
    pool = list(clusters.values())

    # Representative diversity: one per digit-masked signature, globally.
    chosen, seen_sig = [], set()

    def take(cands, quota):
        cands = sorted(cands, key=lambda r: r["_key"])
        n = 0
        for r in cands:
            if n >= quota:
                break
            if r["sig"] in seen_sig:
                continue
            seen_sig.add(r["sig"])
            chosen.append(r)
            n += 1

    # Prioritise the leaked pockets, then balance the remaining rule categories.
    by_pocket = defaultdict(list)
    for r in pool:
        by_pocket[r["pocket"]].append(r)
    for name, quota in POCKETS:
        take(by_pocket.get(name, []), quota)

    remaining = [r for r in pool if r not in chosen]
    per_cat_floor = 30
    for cat in ["personal", "transactional", "promotions", "purchases", "government", "spam", UNKNOWN_ID]:
        have = sum(1 for r in chosen if r["rules_category"] == cat)
        if have < per_cat_floor:
            take([r for r in remaining if r["rules_category"] == cat], per_cat_floor - have)

    remaining = [r for r in pool if r not in chosen]
    if len(chosen) < target:
        take(remaining, target - len(chosen))

    chosen = chosen[:target]
    chosen.sort(key=lambda r: (r["pocket"] or r["rules_category"], r["sender"], r["norm"]))

    items = []
    for i, r in enumerate(chosen, 1):
        rule = r["rules_category"]
        if rule != UNKNOWN_ID:
            suggested, source = rule, "rule"
        else:
            suggested = OLD_TO_SUGGEST.get(r["old"])
            source = "old-category" if suggested else "none"
        items.append(
            {
                "id": f"lbl-{i:04d}",
                "sender": r["sender"],
                "body": r["body"],
                "suggested": suggested,
                "suggested_source": source,
                "label": "",
                "pocket": r["pocket"] or f"cat_{rule}",
                "old_category": r["old"],
                "rules_category": rule,
            }
        )

    doc = {
        "note": "Human labelling sheet for the 7-way taxonomy. `label` is empty "
        "until a human fills it. `suggested` is a WEAK labeller guess (rules first, "
        "else the corpus's old noisy category column) — treat it as a hint, not "
        "truth. Trained classes: personal, transactional, promotions, purchases, "
        "government, spam; leave `label` empty to mean abstain/unknown.",
        "taxonomy": ["personal", "transactional", "promotions", "purchases", "government", "spam", "unknown"],
        "trained_classes": ["personal", "transactional", "promotions", "purchases", "government", "spam"],
        "source_corpus": CORPUS,
        "seed": SEED,
        "pockets": dict(Counter(i["pocket"] for i in items)),
        "suggested_sources": dict(Counter(i["suggested_source"] for i in items)),
        "label_status": "EMPTY — weak suggestions only, not validation",
        "items": items,
    }
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    print(f"wrote {OUT}: {len(items)} items")
    print("pockets   :", dict(Counter(i["pocket"] for i in items)))
    print("suggested :", dict(Counter(i["suggested_source"] for i in items)))
    print("old-label :", dict(Counter(i["old_category"] for i in items)))


if __name__ == "__main__":
    main()
