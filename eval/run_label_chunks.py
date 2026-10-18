#!/usr/bin/env python3
"""Resume-safe chunk labeller. One line per chunk, nothing else.

Skips chunks whose .labelled.jsonl already exists, so a crash resumes cleanly.
Usage: python eval/run_label_chunks.py /tmp/pradar/chunk*.jsonl [--limit 1000]
"""
import glob
import json
import os
import subprocess
import sys

VENV_PY = os.path.expanduser("~/dev/ideas/mofy/ml/.venv/bin/python")


def main():
    pats, limit, only = [], 1000, None
    for a in sys.argv[1:]:
        if a.startswith("--limit"):
            limit = int(a.split("=")[1])
        elif a.startswith("--only"):
            only = a.split("=")[1]
        else:
            pats.append(a)
    files = sorted(f for p in pats for f in glob.glob(p)
                   if not f.endswith(".labelled.jsonl"))
    if only:
        files = [f for f in files if os.path.basename(f) == only]
    suffix = ""
    import re as _re
    for a in sys.argv[1:]:
        if a.startswith("--suffix"):
            suffix = a.split("=")[1]
    for c in files:
        out = c.replace(".jsonl", f"{suffix}.labelled.jsonl")
        if os.path.exists(out):
            print(f"skip {os.path.basename(c)} (done)")
            continue
        r = subprocess.run(
            [VENV_PY, "eval/teacher_student.py", "--pool", c, "--label-set",
             "topic", "--text-field", "body", "--out", out, "--limit", str(limit)],
            capture_output=True, text=True, cwd="/Users/alexday/dev/ideas/personalos")
        n, bad = 0, 0
        if os.path.exists(out):
            for line in open(out, encoding="utf-8"):
                if line.strip():
                    n += 1
                    bad += 0 if json.loads(line).get("label") else 1
        print(f"{'ok ' if r.returncode == 0 else 'ERR'} {os.path.basename(c)}: {n} rows, {bad} unlabeled")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
