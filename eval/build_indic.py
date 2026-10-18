#!/usr/bin/env python3
"""Clean L3Cube-IndicNews SHC into our coarse-bucket pool.

Each source label maps to at most one of our buckets. Items whose label does not
map (national/international/kolkata/state/editorial/lifestyle/auto/...) are kept as
"remainder" with no bucket — the teacher relabel decides them (mostly
official/announcements, else none).
Output is single-label ("buckets": [one]); the teacher relabel (relabel_indic.py)
turns these into multi-label sets. Train is emitted before test and de-duplicated
by normalised text, so no test item can leak in from train.

Source labels (SHC only): en English_*, hi Hindi_*, bn Bengal_* under eval/indicnews.
Usage: python eval/build_indic.py
"""
import csv
import hashlib
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "indicnews")

# our bucket <- source labels (lower-cased)
MAP = {
    "politics": {"politics", "elections"},
    "crime": {"crime", "crime-news-hindi"},
    "sports": {"sports", "khel"},
    "entertainment": {"entertainment"},
    "tech": {"technology", "technology-news", "science"},
    "finance": {"business"},
    "health": {"health", "health-news-hindi"},
    "education": {"education"},
}
# Buckets we keep (health/education included: IPTC top-levels, given free by Indic).
KEEP = {"politics", "crime", "sports", "entertainment", "tech", "finance",
        "health", "education"}

LANG_DIRS = {
    "en": ("English", "English_train_shc.csv", "English_test_shc.csv"),
    "hi": ("Hindi", "Hindi_SHC_Train.csv", "Hindi_SHC_Test.csv"),
    "bn": ("Bengali", "Bengal_SHC_Train.csv", "Bengal_SHC_Test.csv"),
}
LABEL2OURS = {src: ours for ours, srcs in MAP.items() for src in srcs}

# Rows shorter than this are unclassifiable fragments (the Bengali `editorial`
# section ships one-word op-ed titles: 'বাধা', 'দুঃস্বপ্ন', ...). A word cannot
# carry a topic, so it is noise, not data.
MIN_CHARS = 15

# News re-serves the same story as templated variants ("<X> Elections vote
# counting LIVE: For <constituency>") that differ in one token. Exact dedup
# misses them, they inflate training, and they leak across the split. Two rows
# whose normalised text shares this many leading characters are the same story.
DUP_PREFIX = 48


def read(path, text_col="text", label_col="labels"):
    with open(path, encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            t = (row.get(text_col) or "").strip()
            lab = (row.get(label_col) or "").strip().lower()
            if t and lab:
                yield t, lab


def main():
    out, seen, seen_pre = [], set(), set()
    dropped = {"short": 0, "dup": 0}
    for lang, (d, tr, te) in LANG_DIRS.items():
        for split, fn in (("train", tr), ("test", te)):
            p = os.path.join(SRC, d, "SHC", fn)
            kept = 0
            for t, lab in read(p):
                ours = LABEL2OURS.get(lab)
                if len(t) < MIN_CHARS:
                    dropped["short"] += 1
                    continue
                # id basis is the whitespace-collapsed lowercase text, unchanged
                # from earlier runs so surviving rows keep their relabelled id.
                key = re.sub(r"\s+", " ", t.lower()).strip()
                # dedup key additionally drops punctuation, so variants collide.
                full = re.sub(r"\W+", " ", t.lower()).strip()
                if key in seen or full[:DUP_PREFIX] in seen_pre:
                    dropped["dup"] += 1
                    continue
                seen.add(key)
                seen_pre.add(full[:DUP_PREFIX])
                kept += 1
                hid = hashlib.sha1(key.encode()).hexdigest()[:12]
                # Unmapped labels (national/state/kolkata/international/editorial/
                # lifestyle/auto) are kept as "remainder" with no bucket — the
                # teacher assigns them (mostly official/announcements, else none).
                out.append({"id": f"indic-{lang}-{split}-{hid}", "text": t,
                            "lang": lang, "split": split, "source_label": lab,
                            "buckets": [ours] if ours in KEEP else [],
                            "remainder": ours not in KEEP})
            print(f"{lang} {split}: kept {kept}")
    with open(os.path.join(HERE, "indic_pool.jsonl"), "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"wrote indic_pool.jsonl {len(out)}  dropped {dropped}")


if __name__ == "__main__":
    main()
