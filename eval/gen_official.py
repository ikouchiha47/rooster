#!/usr/bin/env python3
"""Generate synthetic government/regulator announcement items for the coarse pool.

The Indic remainder gives us real announcements (national/state/kolkata/...), but
it is thin on the administrative style we care about — tax-filing reminders, RBI
circulars, municipal notices, advisories. This asks the teacher to *write* such
items, with labels, so the `official` bucket (and its finance/health/crime overlaps)
has explicit coverage.

Output appends to eval/official_synth.jsonl and is resumable to a target count.
Usage: python eval/gen_official.py [--target 200] [--batch 20] [--model ...]
"""
import argparse
import hashlib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import teacher_student as ts  # noqa: E402

OUT = os.path.join(HERE, "official_synth.jsonl")
SEEN = os.path.join(HERE, "indic_pool.jsonl")

GEN_PROMPT = """You write short, realistic Indian news items to train a classifier.
Each item is ONE notice, order, circular, scheme or reminder issued by a government
body or a regulator.
Cover a wide mix, e.g.: RBI monetary policy or circulars (repo rate, KYC, UPI and
payment rules, bank licences, currency notes); income-tax / advance-tax / GST filing
and payment reminders and deadlines; ministry schemes and launches; municipal and
civic notices (property tax, water, roads, building permission); court and tribunal
orders; TRAI / CERT-In / FSSAI advisories; passport, ration card, Aadhaar, driving
licence notices; state government orders. Vary the issuer, the state and the wording.
Rules:
- 8 to 30 words, a headline, optionally " — " plus one short clause. No quotes, no
  numbering, no markdown, no duplicate concepts.
- Always tag "official" (the issuer is a government body or a regulator) AND the
  subject bucket when one applies: an RBI rate or a tax-filing item is
  finance + official; a health advisory is health + official; a court order is
  crime + official; a flood or weather alert is weather + official.
Subjects: %s
Write %d items.
Answer ONLY with a JSON array of {"text": <string>, "labels": [<label>, ...]}.
"""


def norm(t):
    return re.sub(r"\s+", " ", t.lower()).strip()


def seen_texts():
    s = set()
    if os.path.exists(SEEN):
        for line in open(SEEN, encoding="utf-8"):
            if line.strip():
                s.add(norm(json.loads(line)["text"]))
    if os.path.exists(OUT):
        for line in open(OUT, encoding="utf-8"):
            if line.strip():
                s.add(norm(json.loads(line)["text"]))
    return s


def parse(text):
    m = re.search(r"\[.*\]", text, re.S)
    if not m:
        return []
    rows = []
    try:
        for row in json.loads(m.group(0)):
            t = str(row.get("text", "")).strip()
            labs = [str(x).lower() for x in row.get("labels", []) if isinstance(x, str)]
            labs = [x for x in labs if x in ts.TOPIC_CLASSES and x != "none"]
            if t and labs:
                rows.append({"text": t, "labels": labs[:3]})
    except Exception:  # noqa: BLE001
        return []
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", type=int, default=200)
    ap.add_argument("--batch", type=int, default=20)
    ap.add_argument("--model", default="gemini-flash-lite-latest")
    a = ap.parse_args()

    key = ts.gemini_key()
    seen = seen_texts()
    have = sum(1 for _ in open(OUT, encoding="utf-8")) if os.path.exists(OUT) else 0
    subjects = ", ".join(ts.TOPIC_CLASSES[:-1])
    print(f"have {have}  target {a.target}", flush=True)

    with open(OUT, "a", encoding="utf-8") as f:
        while have < a.target:
            prompt = GEN_PROMPT % (subjects, a.batch)
            try:
                rows = parse(ts.gemini(a.model, key, prompt))
            except Exception as e:  # noqa: BLE001
                print(f"  teacher error: {e}", flush=True)
                rows = []
            added = 0
            for r in rows:
                k = norm(r["text"])
                if k in seen:
                    continue
                seen.add(k)
                r["id"] = "synth-official-" + hashlib.sha1(k.encode()).hexdigest()[:12]
                r["origin"] = "synth"
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
                added += 1
            have += added
            f.flush()
            print(f"  +{added} -> {have}/{a.target}", flush=True)
    print(f"wrote {OUT} ({have})", flush=True)


if __name__ == "__main__":
    main()
