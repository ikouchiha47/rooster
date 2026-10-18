#!/usr/bin/env python3
"""The app's categorisation rules, reimplemented for offline eval.

Behaviour is **data**: this loads the exact bundled asset
(`android/.../assets/category/categorization.v1.json`) and mirrors
`Categorizer.kt` / `RuleMatcher.kt` / `SenderKind.kt` so the offline numbers
describe the on-device rules, not a copy that can drift.

No Android types, no model, no I/O beyond reading the asset. `isContact` is
always False here because the SMS corpus has no address book.

    from category_rules import categorize, dlt_suffix, sender_kind
"""
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ASSET = os.environ.get(
    "CATEGORIZATION_ASSET",
    os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "category", "categorization.v1.json"),
)

with open(ASSET, encoding="utf-8") as fh:
    TAXONOMY = json.load(fh)

CATEGORIES = {c["id"]: c for c in TAXONOMY["categories"]}
UNKNOWN_ID = TAXONOMY.get("unknownId", "unknown")
RULES = sorted(TAXONOMY["rules"], key=lambda r: r["priority"])

# The six trained classes for the ML head (unknown is abstain, never trained).
TRAINED = ["personal", "transactional", "promotions", "purchases", "government", "spam"]


def dlt_suffix(sender):
    """TRAI/DLT category suffix (P/S/T/G) or None."""
    m = re.search(r"-([PSTG])$", sender.strip().upper())
    return m.group(1) if m else None


_DIGITS = re.compile(r"\D")


def sender_kind(sender, is_contact=False):
    """Mirror BundledSenderKinds order: contact, header, short_code, number."""
    if is_contact:
        return "contact"
    if any(c.isalpha() for c in sender):
        return "header"
    digits = _DIGITS.sub("", sender)
    if 4 <= len(digits) <= 9:
        return "short_code"
    if len(digits) >= 10:
        return "number"
    return "unknown"


_WS = re.compile(r"\s+")


def looks_human(body):
    """At least two words carrying two+ letters (HumanTextMatcher)."""
    return sum(1 for w in _WS.split(body) if sum(c.isalpha() for c in w) >= 2) >= 2


# --- matchers ---------------------------------------------------------------

def _term_matches(text, term):
    ascii_only = all(ord(c) < 0x80 for c in term)
    wordlike = ascii_only and all(c.isalnum() or c == " " for c in term)
    if wordlike:
        pat = r"\b" + r"\s+".join(re.escape(p) for p in term.split(" ")) + r"\b"
        return re.search(pat, text) is not None
    return term in text


def _matches(rule, sender, body, is_contact):
    mt = rule["matcherType"]
    pattern = rule.get("pattern", "")
    if mt == "contact":
        return is_contact
    if mt == "header_suffix":
        return bool(pattern) and dlt_suffix(sender) == pattern.upper()
    if mt == "sender_kind":
        return sender_kind(sender, is_contact) == pattern
    if mt == "body_any":
        text = body.lower()
        return any(_term_matches(text, t.strip()) for t in pattern.split("|") if t.strip())
    if mt == "body_regex":
        return bool(pattern) and re.search(pattern, body, re.IGNORECASE) is not None
    if mt == "human_text":
        kind = pattern or "number"
        return sender_kind(sender, is_contact) == kind and looks_human(body)
    raise ValueError(f"unknown matcherType {mt!r}")


def categorize(sender, body, is_contact=False):
    """First matching rule by ascending priority, else unknown (no Fallback)."""
    for rule in RULES:
        if _matches(rule, sender, body, is_contact):
            return rule["categoryId"]
    return UNKNOWN_ID


def matched_rule(sender, body, is_contact=False):
    for rule in RULES:
        if _matches(rule, sender, body, is_contact):
            return rule["id"]
    return None


if __name__ == "__main__":
    import sys

    samples = [
        ("VM-HDFCBK-S", "Rs 1,214 debited from A/c XX4021 on 20-Sep. Balance Rs 8,932."),
        ("JZ-NDMAEW-G", "Advisory as per the Telecommunications Act 2023."),
        ("AD-BATA-P", "Last 3 Days Left! Walk in before its gone @ BATA. FLAT 50% OFF"),
        ("6294097693", "Hi are we meeting tomorrow at the usual place"),
    ]
    for s, b in samples:
        print(f"{categorize(s, b):14} {matched_rule(s, b)!s:20} {s}  {b[:48]}")
    if len(sys.argv) > 1:
        print(f"\nasset: {ASSET}\ncategories: {list(CATEGORIES)}\nrules: {len(RULES)}")
