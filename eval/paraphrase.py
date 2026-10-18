#!/usr/bin/env python3
"""Meaning-preserving rewrites with varied vocabulary (robustness probe).

Reads pool-format rows, asks the teacher to rewrite subject+body keeping all
facts while changing the wording aggressively. Output keeps original ids for
label join downstream. One line per batch, nothing else.
Usage: python eval/paraphrase.py IN.jsonl OUT.jsonl [--limit N --batch 10]
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from teacher_student import gemini, gemini_key  # noqa: E402

PROMPT = """Rewrite each news item preserving its EXACT meaning and all facts (names, numbers, places). Change the wording AGGRESSIVELY: replace key nouns and verbs with synonyms (strike->walkout/stoppage, missile->projectile, bank->lender, defer->postpone, threaten->warn of, kill->leave dead), restructure sentences, reorder clauses. Similar length. Add nothing, remove nothing.
Answer ONLY with a JSON array of {"i": <index>, "subject": "<rewritten title>", "body": "<rewritten description>"}.
Items:
%s
"""


def main():
    inp, out, limit, batch = sys.argv[1], sys.argv[2], 1000, 10
    rest = sys.argv[3:]
    for i, a in enumerate(rest):
        if a in ("--limit", "--batch") and i + 1 < len(rest):
            if a == "--limit":
                limit = int(rest[i + 1])
            else:
                batch = int(rest[i + 1])
        elif a.startswith("--limit="):
            limit = int(a.split("=")[1])
        elif a.startswith("--batch="):
            batch = int(a.split("=")[1])
    rows = [json.loads(x) for x in open(inp, encoding="utf-8") if x.strip()][:limit]
    key, model = gemini_key(), os.environ.get("TEACHER_MODEL", "gemini-flash-lite-latest")
    done = []
    for s in range(0, len(rows), batch):
        chunk = rows[s:s + batch]
        items = "\n".join(f"{j}. TITLE: {c.get('subject', '')}\n   BODY: {(c.get('body') or '')[:600]}"
                          for j, c in enumerate(chunk))
        try:
            m = re.search(r"\[.*\]", gemini(model, key, PROMPT % items), re.S)
            data = json.loads(m.group(0)) if m else []
        except Exception as e:  # noqa: BLE001
            print(f"batch {s}: FAILED {type(e).__name__}", flush=True)
            continue
        for d in data:
            j = int(d["i"])
            if 0 <= j < len(chunk) and d.get("subject") and d.get("body"):
                c = dict(chunk[j])
                c["subject"], c["body"] = d["subject"], d["body"]
                done.append(c)
        print(f"{s + len(chunk)}/{len(rows)} -> {len(done)} rewrites", flush=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(json.dumps(r, ensure_ascii=False) for r in done))
    print(f"wrote {out} ({len(done)}/{len(rows)})")


if __name__ == "__main__":
    main()
