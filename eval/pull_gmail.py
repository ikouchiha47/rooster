#!/usr/bin/env python3
"""Pull Gmail content (read-only) into a categorisation content pool.

Scope: gmail.readonly (bodies + headers; gmail.metadata cannot use `q`).
Auth: installed-app loopback flow — opens a browser once, caches the token.

Usage:
  python eval/pull_gmail.py \
    --client ~/Downloads/rooster_desktop_client_secret_*.json \
    --token  <private>/gmail_token.json \
    --out    eval/gmail_content.jsonl \
    --days 365 --max 4000

Output rows: {id, threadId, from, to, subject, date, snippet, body}
where `body` is the decoded text/plain (falls back to text/html stripped).
"""
import argparse
import base64
import html
import json
import os
import random
import re
import sys
import threading
import time

import httplib2
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_httplib2 import AuthorizedHttp
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]
_thread_local = threading.local()


def service(client_secret: str, token_path: str):
    """Return authorised credentials (never the secret/token contents).

    Non-interactive whenever a cached token with a refresh token is present;
    only falls back to the loopback browser flow when it genuinely must.
    """
    creds = None
    if os.path.exists(token_path):
        creds = Credentials.from_authorized_user_file(token_path, SCOPES)
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            # Bind IPv4 explicitly: a browser may resolve `localhost` to ::1 while
            # the loopback server is on 127.0.0.1 (and some proxies eat `localhost`).
            creds = InstalledAppFlow.from_client_secrets_file(client_secret, SCOPES).run_local_server(
                port=0, host="127.0.0.1", open_browser=True
            )
        with open(token_path, "w", encoding="utf-8") as f:
            f.write(creds.to_json())
    return creds


def thread_service(creds):
    """One Gmail client per thread: the shared HTTP transport is not thread-safe.

    An explicit AuthorizedHttp with a socket timeout is required — the default
    transport created by `build(credentials=...)` can hang indefinitely.
    """
    svc = getattr(_thread_local, "svc", None)
    if svc is None:
        http = AuthorizedHttp(creds, http=httplib2.Http(timeout=30))
        svc = build("gmail", "v1", http=http, cache_discovery=False)
        _thread_local.svc = svc
    return svc


def execute_with_retry(req, what="request", max_retries=8):
    """Run a Gmail request, backing off on quota (403 rateLimitExceeded) and flaky reads.

    The per-user budget is 250 units/min and a `get` costs 5, so the ceiling is ~50
    msg/min regardless of concurrency; retrying with backoff is the only way through.
    """
    delay = 2.0
    for attempt in range(1, max_retries + 1):
        try:
            return req.execute()
        except HttpError as e:
            status = getattr(getattr(e, "resp", None), "status", None)
            msg = str(e)
            retryable = status in (429, 500, 503) or "rateLimitExceeded" in msg or "Quota exceeded" in msg
        except (httplib2.HttpLib2Error, TimeoutError, OSError) as e:
            retryable = True
            msg = f"{type(e).__name__}: {e}"
        if not retryable or attempt == max_retries:
            raise
        sleep_for = delay + random.uniform(0, 1.0)
        print(
            f"  transient {what} (attempt {attempt}, {msg[:70]}); sleeping {sleep_for:.1f}s",
            file=sys.stderr,
        )
        time.sleep(sleep_for)
        delay = min(delay * 2, 60.0)
    raise RuntimeError("unreachable")


def fetch_record(creds, mid):
    req = thread_service(creds).users().messages().get(userId="me", id=mid, format="full")
    msg = execute_with_retry(req, what=f"get {mid}")
    h = header_map(msg)
    return {
        "id": msg.get("id"),
        "threadId": msg.get("threadId"),
        "from": h.get("from", ""),
        "to": h.get("to", ""),
        "subject": h.get("subject", ""),
        "date": h.get("date", ""),
        "snippet": msg.get("snippet", ""),
        "body": body_of(msg.get("payload", {}))[:8000],
    }


def header_map(msg):
    return {h["name"].lower(): h["value"] for h in msg.get("payload", {}).get("headers", [])}


def _decode(data):
    return base64.urlsafe_b64decode(data + "===").decode("utf-8", "replace")


def _walk(payload, mime):
    """Collect all parts of a given mime type, recursively."""
    out = []
    if payload.get("mimeType") == mime and payload.get("body", {}).get("data"):
        out.append(_decode(payload["body"]["data"]))
    for part in payload.get("parts", []) or []:
        out += _walk(part, mime)
    return out


def body_of(payload):
    plain = "\n".join(_walk(payload, "text/plain"))
    if plain.strip():
        return plain
    html_parts = "\n".join(_walk(payload, "text/html"))
    text = re.sub(r"(?s)<(script|style).*?</\1>", " ", html_parts)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    return html.unescape(re.sub(r"[ \t]+", " ", text)).strip()


def list_ids(creds, query, cap):
    svc = thread_service(creds)
    ids, token = [], None
    while len(ids) < cap:
        req = svc.users().messages().list(
            userId="me", q=query, maxResults=min(500, cap - len(ids)), pageToken=token
        )
        resp = execute_with_retry(req, what=f"list {query!r}")
        ids += [m["id"] for m in resp.get("messages", [])]
        token = resp.get("nextPageToken")
        if not token:
            break
    return ids


def existing_ids(path):
    """Ids already present in the output — lets a run resume instead of redoing work."""
    done = set()
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    done.add(json.loads(line)["id"])
                except Exception:  # noqa: BLE001
                    continue
    return done


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--client", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--out", default="eval/gmail_content.jsonl")
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--max", type=int, default=4000)
    ap.add_argument("--fresh", action="store_true", help="ignore any existing rows and start over")
    ap.add_argument("--pace", type=float, default=1.25, help="seconds between requests (quota throttle)")
    ap.add_argument("--batch", type=int, default=0, help="exit after fetching N new rows (0 = no limit)")
    a = ap.parse_args()

    creds = service(os.path.expanduser(a.client), os.path.expanduser(a.token))

    queries = [
        f"in:inbox newer_than:{a.days}d",
        f"category:purchases newer_than:{a.days}d",
    ]
    ids = []
    for q in queries:
        got = list_ids(creds, q, a.max)
        print(f"  {q!r}: {len(got)}", file=sys.stderr)
        ids += got
    ids = list(dict.fromkeys(ids))[: a.max]

    done = set() if a.fresh else existing_ids(a.out)
    todo = [m for m in ids if m not in done]
    mode = "w" if a.fresh or not done else "a"
    print(
        f"listed {len(ids)} unique; {len(done)} already on disk; fetching {len(todo)}",
        file=sys.stderr,
    )

    n = len(done)
    t0 = time.time()
    last = 0.0
    with open(a.out, mode, encoding="utf-8") as f:
        for i, mid in enumerate(todo, 1):
            gap = a.pace - (time.time() - last)
            if gap > 0:
                time.sleep(gap)
            last = time.time()
            try:
                rec = fetch_record(creds, mid)
            except Exception as e:  # noqa: BLE001
                print(f"  skip {mid}: {e}", file=sys.stderr)
                continue
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            f.flush()
            n += 1
            if a.batch and i >= a.batch:
                print(f"  batch limit reached ({i})", file=sys.stderr)
                break
            if i % 100 == 0 or i == len(todo):
                rate = i / max(time.time() - t0, 1e-6)
                print(f"  {i}/{len(todo)}  ({n} total, {rate*60:.1f} msg/min)", file=sys.stderr)
    print(f"wrote {n} rows total -> {a.out}")


if __name__ == "__main__":
    main()
