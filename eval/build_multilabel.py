#!/usr/bin/env python3
"""Assemble multi-label sets datasets. Quiet, deterministic.

Teacher sets come from rss_pool.sets.jsonl, ext_pool.sets.jsonl and
/tmp/pradar/chunk*.sets.labelled.jsonl. labour-action is a RULE, not a bucket:
rows whose title matches labour_labels.json strike/bandh kinds get incident added.
none -> other. games/paper/personal/promo/expense dropped.
Test = frozen rss_test.jsonl ids joined to their sets (single label kept as
fallback); train = everything else, story-deduped against test.
Usage: python eval/build_multilabel.py
"""
import glob
import json
import os
import random
import re
import sqlite3
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
CLASSES = ["finance", "official", "politics", "crime", "incident", "sports",
           "entertainment", "tech", "weather", "travel", "festival",
           "health", "education", "other"]
DROP = {"games", "paper", "personal", "promo", "expense", "none"}


def story_key(t):
    t = t.lower()
    t = re.sub(r"\s*[|\-–—]\s*(the hindu|times of india|live law|bar and bench|"
               r"the news minute|ndtv\.com|cricinfo|deccan herald|telegraph india|"
               r"the statesman|the new indian express).*$", "", t)
    return re.sub(r"\s+", " ", t).strip()


def main():
    lab = json.load(open(os.path.join(HERE, "labour_labels.json")))["labels"]
    db = sqlite3.connect("/tmp/pradar/personalos.db")
    titles = {str(e): t.strip().lower() for e, t in db.execute("SELECT id, title FROM events")}
    title2kind = {titles[e]: k for e, k in lab.items() if e in titles}

    def to_set(r):
        labs = [x for x in (r.get("labels") or ([r.get("label")] if r.get("label") else []))
                if x in CLASSES or x in DROP or x == "none"]
        s = {"other" if x == "none" else x for x in labs} - DROP
        kind = title2kind.get(r["subject"].strip().lower())
        if kind in ("strike", "bandh"):
            s.add("incident")
        return sorted(s - DROP)

    frozen_ids = set()
    for line in open(os.path.join(HERE, "rss_test.jsonl"), encoding="utf-8"):
        if line.strip():
            frozen_ids.add(json.loads(line)["id"])

    files = [os.path.join(HERE, "rss_pool.sets.jsonl"), os.path.join(HERE, "ext_pool.sets.jsonl")]
    files += sorted(glob.glob("/tmp/pradar/chunk*.sets.labelled.jsonl"))
    pool = []
    for p in files:
        try:
            pool += [json.loads(x) for x in open(p, encoding="utf-8") if x.strip()]
        except FileNotFoundError:
            print(f"missing {p}, skipping")
    print(f"pool rows: {len(pool)}")

    # Audited rows keep human labels: the 30 reviewed nones stay ["other"]
    # even where the sets teacher drifted loose (politics -> official).
    audit_other = set()
    rows15 = [json.loads(l) for l in open(os.path.join(HERE, "label15k.labelled.jsonl"), encoding="utf-8") if l.strip()]
    import random as _rnd
    _rnd.seed(13)
    audit_other = {r["id"] for r in _rnd.sample([x for x in rows15 if x.get("label") == "none"], 30)}

    test, train, seen_test = [], [], set()
    for r in pool:
        s = to_set(r)
        if r["id"] in audit_other:
            s = ["other"]
        if not s:
            continue
        row = {"id": r["id"], "subject": r["subject"], "body": r["body"],
               "source": r.get("source", "?"), "labels": s, "ts": r.get("ts", 0)}
        if r["id"] in frozen_ids:
            row["split"] = "test"
            test.append(row)
        else:
            train.append(row)
    # story-dedupe train against test + itself
    test_keys = {story_key(r["subject"]) for r in test}
    seen, train2 = set(), []
    for r in train:
        k = story_key(r["subject"])
        if k in test_keys or k in seen:
            continue
        seen.add(k)
        r["split"] = "train"
        train2.append(r)
    random.seed(21)
    random.shuffle(train2)
    with open(os.path.join(HERE, "ml_train.jsonl"), "w", encoding="utf-8") as f:
        f.write("\n".join(json.dumps(r, ensure_ascii=False) for r in train2))
    with open(os.path.join(HERE, "ml_test.jsonl"), "w", encoding="utf-8") as f:
        f.write("\n".join(json.dumps(r, ensure_ascii=False) for r in test))
    print(f"train {len(train2)} test {len(test)}")
    print("train dist:", dict(sorted(Counter(x for r in train2 for x in r["labels"]).items())))
    print("test dist:", dict(sorted(Counter(x for r in test for x in r["labels"]).items())))
    print("test multi:", sum(1 for r in test if len(r["labels"]) > 1),
          "train multi:", sum(1 for r in train2 if len(r["labels"]) > 1))


if __name__ == "__main__":
    main()
