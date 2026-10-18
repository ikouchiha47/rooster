#!/usr/bin/env python3
"""Teacher → student labelling for the categoriser.

Teacher = an LLM (Gemini) labels a *content pool* against our taxonomy.
Student = the tiny on-device model (BoW/logistic) trained on those labels.
The human gold (eval/train.jsonl, eval/test.jsonl) is the **eval only** — never
trained on as teacher output.

Why: public dumps give us *content*, not our labels. The teacher scales labelling
onto varied text (Gmail, Enron, ...); the student, learned on it, is what ships.
`unknown` is the abstain outcome: the teacher may say "unknown" and the student
abstains.

Usage (stdlib only for the teacher; numpy for the student):
  ~/dev/ideas/mofy/ml/.venv/bin/python eval/teacher_student.py \
    --pool eval/gmail_content.jsonl --text-field body \
    --out eval/gmail.labelled.jsonl --limit 2000
  # then train/eval the student on the pool + gold:
  ~/dev/ideas/mofy/ml/.venv/bin/python eval/teacher_student.py \
    --pool eval/gmail.labelled.jsonl --text-field text --train
"""
import argparse
import json
import os
import re
import sys
import urllib.request

CLASSES = ["personal", "transactional", "promotions", "purchases", "government", "spam", "unknown"]
MULTI_LABEL = False

# Coarse buckets. Taxonomy names follow IPTC Media Topics (top level). labour-action
# is a RULE (embedding match), never a bucket, so it is absent here.
TOPIC_CLASSES = ["finance", "official", "politics", "crime", "incident", "sports",
                 "entertainment", "tech", "weather", "travel", "festival",
                 "health", "education", "none"]

TOPIC_PROMPT = """You label NEWS items for a personal assistant. Use the TITLE and DESCRIPTION together. For EACH item pick ALL topics that apply (1 to 3), PRIMARY topic first, from:
- finance: money, stock markets, banking, loans, interest rates, business, economy
- official: an official notice, order or announcement from a government body or an organisation
- politics: politics and government — elections, parties, leaders, campaigns, policy, protests
- crime: crime, law and justice — police cases, arrests, courts, violence, fraud
- incident: a disaster, accident, emergency, outage, strike or disruption (not political in nature)
- sports: sport — matches, tournaments, leagues, players, cricket, football
- entertainment: arts, culture, cinema, TV, music, celebrities, awards
- tech: technology, gadgets, software, artificial intelligence, cybersecurity, science
- weather: weather, rain, floods, storms, a natural disaster (weather-caused)
- travel: travel, flights, trains, hotels, tourism, tickets, visa and immigration policy, travel advisories
- festival: a festival, a religious holiday or a cultural celebration
- health: health, medicine, disease, hospitals, treatment, wellbeing
- education: schools, colleges, universities, exams, students, learning
- none: no topic above fits, or you cannot tell (use ["none"] alone, never with others)
If a story is about politics (elections, parties, leaders) prefer politics over official. If it is a crime, prefer crime over incident.
A government body or regulator ISSUING a notice, order, circular or reminder is official AND the
subject bucket. So an RBI rate/circular or an income-tax/GST filing reminder is finance + official;
a health-ministry advisory is health + official; a court order is crime + official. Tag official
alone only for a purely administrative notice with no other subject (a scheme launch, an
appointment, a holiday or census notice).
Answer ONLY with a JSON array of {"i": <index>, "labels": [<label>, ...]} for the items given.
Items:
%s
"""

PROMPT = """You label messages for a personal assistant. For EACH item pick exactly ONE label from:
- personal: a human talking to the user (a saved contact, a one-to-one chat)
- transactional: OTP, bank/txn, bill, payment, recharge, delivery of a service notice
- promotions: marketing, offers, sales, coupons, loyalty
- purchases: an order's lifecycle (placed, shipped, out for delivery, delivered)
- government: a government/regulator body (tax, census, police, CERT-In, TRAI)
- spam: fraud/scam/phishing (fake prize, KYC-phish, investment scam)
- unknown: none fit, or you cannot tell
Answer ONLY with a JSON array of {"i": <index>, "label": <label>} for the items given.
Items:
%s
"""


def gemini_key():
    key = os.environ.get("GEMINI_API_KEY")
    if key:
        return key
    path = os.path.expanduser("~/.credentials")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            if line.startswith("GEMINI_API_KEY="):
                return line.split("=", 1)[1].strip()
    sys.exit("no GEMINI_API_KEY (env or ~/.credentials)")


def gemini(model, key, prompt, timeout=120):
    body = json.dumps({"contents": [{"parts": [{"text": prompt}]}]}).encode()
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}"
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.load(r)
    return data["candidates"][0]["content"]["parts"][0]["text"]


def parse_labels(text, n):
    m = re.search(r"\[.*\]", text, re.S)
    out = [None] * n
    if not m:
        return out
    try:
        for row in json.loads(m.group(0)):
            i = int(row["i"])
            labs = row.get("labels", [row.get("label")])
            labs = [str(x).lower() for x in labs if isinstance(x, str)]
            labs = [x for x in labs if x in CLASSES][:3]
            if 0 <= i < n and labs and not (len(labs) > 1 and "none" in labs):
                out[i] = labs if MULTI_LABEL else labs[0]
    except Exception:  # noqa: BLE001
        pass
    return out


def label_pool(pool, field, out_path, model, batch, limit):
    key = gemini_key()
    rows = [json.loads(x) for x in open(pool, encoding="utf-8") if x.strip()][:limit]
    todo = [r for r in rows if not r.get("label")]
    print(f"pool {len(rows)} rows, {len(todo)} to label", file=sys.stderr)
    with open(out_path, "w", encoding="utf-8") as f:
        for start in range(0, len(todo), batch):
            chunk = todo[start:start + batch]
            items = "\n".join(
                f"{j}. {((c.get('subject') + ' — ') if c.get('subject') else '')}"
                f"{(c.get(field) or c.get('body') or c.get('text') or '')[:700]}"
                for j, c in enumerate(chunk)
            )
            try:
                labs = parse_labels(gemini(model, key, PROMPT % items), len(chunk))
            except Exception as e:  # noqa: BLE001
                print(f"  teacher error at {start}: {e}", file=sys.stderr)
                labs = [None] * len(chunk)
            for c, lab in zip(chunk, labs):
                if isinstance(lab, list):
                    c["labels"] = lab
                    c["label"] = lab[0]
                else:
                    c["label"] = lab
                f.write(json.dumps(c, ensure_ascii=False) + "\n")
            print(f"  {start + len(chunk)}/{len(todo)}", file=sys.stderr)
    print(f"wrote {out_path}")


def train_student(pool, field, train_gold, test_gold):
    # The student is the existing BoW/logistic (eval/tiny_text.py). Concatenate the
    # teacher-labelled pool with the gold train as the train set, and evaluate on the
    # gold test. Wire by pointing tiny_text's TRAIN_PATH/TEST_PATH at those files
    # (train = pool + gold train, test = gold test) and running it.
    print("student = eval/tiny_text.py, trained on (teacher-labelled pool + gold train),")
    print(f"evaluated on gold test.\n  pool={pool} (field={field})  gold={train_gold}/{test_gold}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", required=True)
    ap.add_argument("--label-set", choices=["message", "topic"], default="message")
    ap.add_argument("--text-field", default="body")
    ap.add_argument("--out", default=None)
    ap.add_argument("--model", default="gemini-flash-lite-latest")
    ap.add_argument("--batch", type=int, default=20)
    ap.add_argument("--limit", type=int, default=2000)
    ap.add_argument("--train", action="store_true")
    ap.add_argument("--train-gold", default="eval/train.jsonl")
    ap.add_argument("--test-gold", default="eval/test.jsonl")
    a = ap.parse_args()
    global CLASSES, PROMPT, MULTI_LABEL
    if a.label_set == "topic":
        CLASSES, PROMPT = TOPIC_CLASSES, TOPIC_PROMPT
        MULTI_LABEL = True
    if a.train:
        train_student(a.pool, a.text_field, a.train_gold, a.test_gold)
    else:
        label_pool(a.pool, a.text_field, a.out or (a.pool + ".labelled.jsonl"), a.model, a.batch, a.limit)


if __name__ == "__main__":
    main()
