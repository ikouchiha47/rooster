# ADR 0002: Rules Slice (v1) — One Table, Two Kinds, JSON Spec

- **Status:** Proposed
- **Date:** 2026-09-14
- **Deciders:** Owner (single user)
- **Scope:** Personalos v1 — rules persistence and v1 execution only. Not the full filter+delivery engine (`ARCHITECTURE.md` §2 `rules`: tile scope, condition, delivery, position), not UI layout, not scheduling policy.

---

## Context

v1 needs user-extensible news coverage without building the full rules engine yet. Two mechanisms cover everything v1 ingests:

1. A plain feed URL polled directly (RSS/Atom).
2. A Google News (GNews) query for places/topics with no direct feed.

Future mechanisms (RapidAPI, web-scraper + R2) are explicitly out of v1 per ADR 0001 zero-cost / no-browser policy, but the schema must not block them later.

Existing docs describe a richer `rules` row (tile scope, condition, delivery, position, builtin, enabled). That is the target; this ADR defines the minimal v1 slice that ships now and migrates forward.

## Decision

One `rules` table. Two kinds. Spec is an opaque JSON string.

```sql
CREATE TABLE rules (
  id          TEXT PRIMARY KEY,      -- ULID (cf. ARCHITECTURE.md §11.1)
  name        TEXT NOT NULL,         -- user-visible label, e.g. "Berhampore news"
  kind        TEXT NOT NULL,         -- 'rss' | 'search' (CHECK constraint)
  spec_json   TEXT NOT NULL,         -- opaque per-kind JSON, validated at write
  seeded      INTEGER NOT NULL DEFAULT 0,  -- 0 = user rule, 1 = bundled seed
  enabled     INTEGER NOT NULL DEFAULT 1,
  created_at  INTEGER NOT NULL
);
```

### Kinds (v1 closed set)

| kind | Meaning today | Spec keys |
|---|---|---|
| `rss` | Poll a plain feed URL directly. The steady state. | `url`, `tags` |
| `search` | GNews Google News query, polled as RSS. Discovery/coverage only. | `query`, `query_lang_code`, `source_locale`, `tags` |

`kind` is a CHECK constraint, not an enum table — adding a third kind later is a data + adapter change, never a schema migration (Open/Closed per `CODE-DESIGN-GUIDELINES.md` §2).

### Spec shapes (v1)

```jsonc
// kind = "rss"
{ "url": "https://example.com/feed.xml", "tags": ["news"] }

// kind = "search"  (Google News)
{
  "query": "Berhampore",
  "query_lang_code": "en",      // "en" | "hi" only in v1
  "source_locale": "en-IN",     // GNews edition, e.g. en-IN / hi-IN
  "tags": ["news"]
}
```

- `query_lang_code` is the language of `query` itself (`en`/`hi` in v1), distinct from `source_locale` (which GNews edition to hit). Gaps for `kn`/`or`/`as` (no GNews edition — Plan 0001 §1) are covered by `rss` outlet feeds, never by faking `query_lang_code`.
- `tags` are subject/nature tags applied at ingest (`news` default; specialist feeds may declare e.g. `finance` per ARCHITECTURE.md §10.5).
- Parsers validate required keys per kind at write; unknown keys are rejected in v1 so a future kind can't silently masquerade as a v1 kind.

### Seeded vs user rules

- `seeded = 0` (user rule): fully editable and disablable. Edit, disable (`enabled = 0`), delete.
- `seeded = 1` (bundled seed): **locked. Add-only.** The UI offers no edit/disable/delete affordance for the row; the only operation is inserting new rows. Seeds provide day-one coverage (national + per-state catalog); corrections ship as new seed versions, never as user edits to seed rows.
- Enforcement is at the repository layer (single owner per `CODE-DESIGN-GUIDELINES.md` §1), not just hidden buttons: `update/delete/disable` on `seeded = 1` is rejected.

### What this ADR does NOT decide

- Polling cadence, backoff, health (`last_ok_at`) — owned by the adapter/scheduler per ADR 0001.
- Tile scope, delivery (`timeline`/`push`), position — the full-engine columns; v1 rows carry no delivery semantics (everything lands in the store; tiles read by tag).
- Dedupe — unchanged (`dedupe_key`, ARCHITECTURE.md §11).

## Consequences

**Positive**

- One table, one repository (`Flow<List<Rule>>`), one writer (Settings). Adding a feed/query touches Settings only — passes the acceptance test in `CODE-DESIGN-GUIDELINES.md` §5.
- `spec_json` as string keeps v1 shippable while leaving the door open: `kind = "rapidapi"` or `"scrape"` later is a new parser + adapter, zero migrations.
- Seeded-add-only preserves a known-good baseline across updates without merge conflicts against user edits.
- Both kinds stay inside ADR 0001: keyless, plain-HTTP, zero recurring cost.

**Negative / accepted**

- Opaque JSON sacrifices queryability (no `WHERE spec->>'url'` index in v1; list-and-parse is fine at this scale).
- No per-rule delivery/position in v1 — the Watchers/Alerts UX (§4.7) still needs the full engine later.
- `query_lang_code` limited to `en`/`hi` in v1; other languages go through `rss` until verified.

## Alternatives considered

- **Typed columns per kind (`url`, `query`, …):** rejected — every future kind becomes a schema migration.
- **Separate `rss_rules` / `search_rules` tables:** rejected — two owners for one fact; consumers would union two sources.
- **Editable seeds:** rejected — user edits to bundled rows diverge on every catalog update; add-only keeps seeds replaceable.

## Open questions

1. Seed versioning: replace-whole-seed-set on update, or versioned rows with `seed_version`?
2. Should `search` harvest `<source url>` domains into suggested `rss` rules (Plan 0001 §2), or stay pure content?
3. Where does GNews `search` output land when a direct `rss` feed for the same outlet already exists — dedupe by URL suffices?
