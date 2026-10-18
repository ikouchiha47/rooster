#!/usr/bin/env python3
"""Ad-hoc training-data pull: topic feeds from the internet (NOT app seeds).

Writes pool-format JSONL (subject/body) for the teacher. stdlib only.
Usage: python eval/pull_rss.py  (writes eval/ext_pool.jsonl)
"""
import json
import os
import re
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))

FEEDS = {
    # travel: advisories + policy + industry (user spec: visa/immigration over fluff)
    "ext:state-advisory": "https://travel.state.gov/_res/rss/TAsTWs.xml",
    "ext:et-advisory": "https://travel.economictimes.indiatimes.com/rss/travel-advisory",
    "ext:et-tourism": "https://travel.economictimes.indiatimes.com/rss/tourism",
    "ext:et-aviation": "https://travel.economictimes.indiatimes.com/rss/aviation",
    "ext:toi-nri": "https://timesofindia.indiatimes.com/rssfeeds/7098551.cms",
    # weather / environment
    "ext:toi-env": "https://timesofindia.indiatimes.com/rssfeeds/2647163.cms",
    # tech
    "ext:techcrunch": "https://techcrunch.com/feed/",
}

UA = {"User-Agent": "personalos-trainpull/1.0"}


def text(el):
    return (el.text or "").strip() if el is not None else ""


def clean(html):
    t = re.sub(r"<[^>]+>", " ", html or "")
    t = re.sub(r"\s+", " ", t).strip()
    return t


def items(xml_bytes):
    root = ET.fromstring(xml_bytes)
    out = []
    for item in root.iter("item"):  # RSS 2.0
        out.append((text(item.find("title")), clean(text(item.find("description"))),
                    text(item.find("link"))))
    if not out:
        ns = "{http://www.w3.org/2005/Atom}"
        for e in root.iter(ns + "entry"):  # Atom
            link = ""
            for l in e.iter(ns + "link"):
                if l.get("rel", "alternate") == "alternate":
                    link = l.get("href", "")
            summary = clean(text(e.find(ns + "summary"))) or clean(text(e.find(ns + "content")))
            out.append((text(e.find(ns + "title")), summary, link))
    return out


def main():
    all_rows, seen = [], set()
    for source, url in FEEDS.items():
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=60) as r:
                got = items(r.read())
        except Exception as e:  # noqa: BLE001
            print(f"{source}: FAILED {type(e).__name__} {e}")
            continue
        n = 0
        for title, desc, link in got:
            if not title or not desc or len(desc) < 40:
                continue
            key = (re.sub(r"\s+", " ", title.lower()).strip(), link)
            if key in seen:
                continue
            seen.add(key)
            all_rows.append({"id": link or f"{source}:{n}", "source": source,
                             "subject": title, "body": desc})
            n += 1
        print(f"{source}: {n} rows")
    out = os.path.join(HERE, "ext_pool.jsonl")
    with open(out, "w", encoding="utf-8") as f:
        for r in all_rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"wrote {out} ({len(all_rows)} rows)")


if __name__ == "__main__":
    main()
