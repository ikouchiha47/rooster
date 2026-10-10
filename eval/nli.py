#!/usr/bin/env python3
"""Zero-shot classification by NLI (mDeBERTa-v3 multilingual), vs embedding-cosine.

Instead of cosine to a noun-list, it asks an entailment question per tag:
"does this message entail: 'this is about finance, money or banking'?" That is
much closer to "asking the model to classify", and the model is multilingual.

Reports exact-set-match against eval/corpus.json across a score threshold.
"""
import json
import os

from transformers import pipeline

HERE = os.path.dirname(os.path.abspath(__file__))

TAGS = {
    "weather": "weather, rain, floods or a natural disaster",
    "finance": "finance, money, banking or stock markets",
    "tech": "technology, software or gadgets",
    "travel": "travel, flights, trains or hotels",
    "festival": "a festival, a religious holiday or a celebration",
    "games": "sports, games or matches",
    "paper": "a newspaper or a publication",
    "expense": "a bill, a payment or a bank transaction",
    "promo": "an advertisement, a sale or a discount offer",
    "incident": "an accident, a crime, an outage, a protest or a disaster",
    "personal": "a personal message between two people",
    "official": "a government notice, an advisory or an official announcement",
}


def main():
    clf = pipeline("zero-shot-classification", model="MoritzLaurer/mDeBERTa-v3-base-mnli-xnli", device=-1)
    corpus = json.load(open(os.path.join(HERE, "corpus.json")))["items"]
    hypes = list(TAGS.values())
    results = []
    for it in corpus:
        r = clf(it["text"], candidate_labels=hypes, multi_label=True)
        scores = {tag: s for tag, s in zip(r["labels"], r["scores"])}
        results.append((it, scores))

    for t in (0.3, 0.5, 0.7):
        ok = 0
        for it, scores in results:
            pred = {tag for tag, s in scores.items() if s >= t}
            ok += pred == set(it["expect"])
        print(f"threshold {t}: {ok}/{len(corpus)}")

    print("\nper item (threshold 0.5):")
    for it, scores in results:
        pred = {tag for tag, s in scores.items() if s >= 0.5}
        top = sorted(scores.items(), key=lambda x: -x[1])[:3]
        flag = "" if pred == set(it["expect"]) else "  MISS"
        print(f"  {it['id']:16} exp={','.join(sorted(set(it['expect']))) or '-':20} got={','.join(sorted(pred)) or '-':22} {top}{flag}")


if __name__ == "__main__":
    main()
