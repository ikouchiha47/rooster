#!/usr/bin/env python3
"""Build the bundled India gazetteer (`android/app/src/main/assets/places.dat`).

Not run at build time — the output is committed, because it changes only when
GeoNames does. Re-run it to refresh, then regenerate the attribution if the
filters changed.

    python3 tools/build_places.py

Inputs (download into INPUT_DIR first):

    IN.zip                https://download.geonames.org/export/dump/IN.zip
    alternatenames/IN.zip https://download.geonames.org/export/dump/alternatenames/IN.zip

Both are CC BY 4.0 — see `android/app/src/main/assets/places-attribution.txt`,
which must ship with the output.

Design notes for whoever reads this next:

* The filter is deliberately **not** "all of India". Raw `IN.txt` is 660k places
  and 70 MB, mostly hamlets; 12.6k rows at 0.41 MB gzipped is the useful subset.
* Names are matched on the **ASCII** column, not the display name, because 2,130
  names carry diacritics (`Telangāna`, `Sāran`).
* `State of …` / `Union Territory of …` prefixes are stripped. GeoNames writes
  "State of Telangana"; news writes "Telangana". Leaving the prefix on makes
  those states unmatchable, and the failure is silent — it looks identical to
  "the article mentioned no place". The long form is kept as an alternate name
  so both spellings resolve.
"""

from __future__ import annotations

import gzip
import io
import os
import re
import sys

# --- configuration ---------------------------------------------------------

INPUT_DIR = os.environ.get(
    "GEO_DIR",
    "/var/folders/2_/4mhmjvl14tbdpwyskd146vmc0000gn/T/opencode/geo",
)
OUTPUT = os.environ.get(
    "GEO_OUT",
    os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        # NOTE: `.dat`, not `.gz`. aapt decompresses any asset ending in `.gz` at
        # package time and strips the extension, so `places.tsv.gz` ships as a
        # 1.05 MB `places.tsv` — a runtime lookup of the .gz path fails and the
        # APK loses the compression. A non-.gz extension survives intact, which
        # also means the committed artifact is byte-for-byte what ships.
        "android/app/src/main/assets/places.dat",
    ),
)

# Feature classes worth carrying. Everything else (streams, reservoirs, hotels,
# mountains) is noise for "which place is this article about".
ADMIN_CODES = {"A/ADM1", "A/ADM2", "A/ADM3", "P/PPLC", "P/PPLA", "P/PPLA2", "P/PPLA3"}

# Towns below this population are dropped: they add rows without adding matches.
POP_MIN = 10_000

# Alternate-name languages to keep. Excludes GeoNames pseudo-languages (`link`,
# `wkdt`) which map to Wikipedia URLs and Wikidata ids, not readable names.
LANGUAGES = {"en", "hi", "bn", "ta", "te", "mr", "gu", "kn", "ml", "pa", "or", "as", "ur"}
MAX_ALIASES = 10

# India's own node lives in countryInfo.txt, not IN.txt, so it is synthesised.
INDIA_ROW = ("1269750", "India", "India", "22.0", "79.0", "A", "PCLI", "", "", "", "1352617328", "")

PREFIX = re.compile(r"^(?:State of|Union Territory of)\s+", re.I)


def strip_prefix(name: str) -> tuple[str, str | None]:
    """Return (display_name, dropped_prefix). See the module docstring."""
    stripped = PREFIX.sub("", name).strip()
    return (stripped, name) if stripped != name else (name, None)


def read_places(path: str) -> dict[str, tuple[str, ...]]:
    keep: dict[str, tuple[str, ...]] = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            f = line.rstrip("\n").split("\t")
            if len(f) < 19:
                continue
            code = f"{f[6]}/{f[7]}"
            if code in ADMIN_CODES or (code == "P/PPL" and int(f[14] or 0) >= POP_MIN):
                keep[f[0]] = (f[1], f[2], f[4], f[5], f[6], f[7], f[10], f[11], f[12], f[14])
    return keep


def read_aliases(path: str, keep: dict[str, tuple[str, ...]]) -> dict[str, list[str]]:
    aliases: dict[str, list[str]] = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            f = line.rstrip("\n").split("\t")
            if len(f) < 4:
                continue
            if f[1] in keep and f[2] in LANGUAGES and f[3]:
                aliases.setdefault(f[1], []).append(f[3])
    return aliases


def main() -> int:
    places_path = os.path.join(INPUT_DIR, "IN.txt")
    aliases_path = os.path.join(INPUT_DIR, "alt", "IN.txt")
    for path in (places_path, aliases_path):
        if not os.path.exists(path):
            print(f"missing input: {path}", file=sys.stderr)
            return 1

    keep = read_places(places_path)
    aliases = read_aliases(aliases_path, keep)

    rows: list[tuple[str, ...]] = [INDIA_ROW]
    prefixed = 0
    for gid, (name, ascii_name, lat, lon, cls, code, a1, a2, a3, pop) in keep.items():
        display, dropped = strip_prefix(name)
        if dropped is not None:
            prefixed += 1
            aliases.setdefault(gid, []).append(dropped)
        joined = "|".join(sorted(set(aliases.get(gid, [])))[:MAX_ALIASES])
        rows.append((gid, display, strip_prefix(ascii_name)[0], lat, lon, cls, code, a1, a2, a3, pop, joined))

    tsv = "\n".join("\t".join(row) for row in rows) + "\n"
    buffer = io.BytesIO()
    # mtime=0 so the output is byte-identical between runs. Without it gzip embeds
    # the current time and every rebuild shows a spurious diff.
    with gzip.GzipFile(fileobj=buffer, mode="wb", compresslevel=9, mtime=0) as gz:
        gz.write(tsv.encode("utf-8"))

    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    with open(OUTPUT, "wb") as handle:
        handle.write(buffer.getvalue())

    print(f"places                : {len(rows):,}")
    print(f"'State of ...' stripped: {prefixed}")
    print(f"gzipped               : {len(buffer.getvalue()) / 1e6:.2f} MB")
    print(f"written               : {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
