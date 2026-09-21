# Plan 0003 — Sources UI, kinds and keys, and the rule builder

- **Status:** **Proposed.** Task list written from a discussion; the slices marked *(open decision)*
  depend on questions in ADR 0004 that are not settled.
- **Date:** 2026-09-16
- **Depends on:** ADR 0004 (sources, kinds, keys — proposed), ADR 0003 (sources/items/rules — built)
- **Companion:** ADR 0003 §14 (written prematurely; to be superseded by ADR 0004)

---

## What already exists

The engine is built and device-verified: `sources` / `rules` / `item_rules` / `item_fields` (schema
v14), the condition language, `RuleEvaluator`, `RuleWriter`, the bounded preview, five seeded rules,
background SMS polling, a debug force-sync, and a rules authoring screen. A real rule has matched a
real item and written a real row.

What this plan addresses is the **model and the surface**, not the engine.

---

## Slice A — the feed surface, unified (in flight)

Worktree `feat/feeds-ui`.

- [ ] A1 One feed-list component, used once per screen. `SourcesScreen` currently renders the list
      **twice** ("Feeds" and the older "My feeds"); `RssScreen` renders a third variant. Keep the
      better treatment — dot, name, domain, right-aligned `1H` / `N NEW` — and delete the others.
- [ ] A2 `RssScreen`: under the Add form, a small **Feeds** header and the unified list.
- [ ] A3 Rename the `Sources` screen to **Overview**; remove its entry from the services grid while
      leaving `Destination.Sources` resolving.
- [ ] A4 Group renames, capital after `&`: **Radar & Monitoring** · **Feeds** (was News & topics) ·
      **Money & Travel** · **Daily** (was Media & daily) · **App**. `News` moves to Daily; `Places`
      moves to Money & Travel.

**Owner's finding behind A1:** two components over one table, built at different times, is exactly the
duplication this design is supposed to prevent. The rule for the rest of this plan: **one component per
concept, one owner per number.**

## Slice B — the key vocabulary, and how sources are selected

The reference is ADR 0004 §3 (the vocabulary) and §4 (per-source), §5 (selection). Nothing here should
be invented at implementation time; if the ADR is wrong, fix the ADR first.

- [ ] B1 Declare per-kind keys in one place, as ADR §3.2 lists them: `sms` → `sender`, `amount`;
      `weather` → `temp_c`, `feels_like_c`, `humidity`, `wind_kph`; `fx` → `rate`; `rss` and `search` →
      none. `FieldNames.SUPPLIED` is a global `{sender, amount}` today, which is why the builder cannot
      offer a temperature.
- [ ] B2 The builder's key list is the union of: the **universal** keys (`source`, `title`, `content`,
      `date`, `url`), the **tag** keys (`subject`, `nature`, `marker`, values from `TagGroups`), the
      **mention** keys (`place`, `party`), and the **declared** keys of the kinds selected.
- [ ] B3 **Correct the tags-versus-fields conflation.** `amount` is a field (extracted fact, in
      `item_fields`); `nature = expense` is a tag (classification, in `item_tags`). An SMS carries both.
      SMS's keys are the universal set **plus** `sender` and `amount` — not "sender and amount" instead
      of them. An SMS can be `incident`, `announcement` or `promo` exactly as an article can.
- [ ] B4 **The two-level source picker (ADR §5).** Level 1 lists only kinds that have an instance —
      `SMS · Feeds · Topics · Weather · FX` — so a source that produces nothing can never be offered.
      Level 2 lists that kind's instances by name. Selection is multi-kind and multi-instance, and
      **empty means every source**.
- [ ] B5 **Label is not identity.** The UI shows the instance's name; the rule stores its identity
      (`rss:thehindu-top`, `gnews:kolkata`). Note the registry row id (`seed:rss:thehindu-top`) is a
      third string again — use the existing mapping, never assume equality.
- [ ] B6 The key list must **follow the selection**: universal/tag/mention keys always; declared keys
      only for the kinds chosen. This is the mechanism that stops a rule being authored against a key
      no source supplies.
- [ ] B7 Mention values: `place` can be sourced from the gazetteer; `party` from the parties table. Both
      need exposing at the composition point, which is why this was left as validated text before —
      decide now whether to expose them or keep validated text, and say which.

## Slice C — Google News in Topics

- [ ] C1 `Topics` becomes the query home: add several queries, same flow as adding feeds (name +
      query + interval, verify before add).
- [ ] C2 The kind stays `search`; the provider (`google_news`) moves into `spec_json` rather than being
      baked into the kind or a hardcoded host in `GnewsUrl`, so Exa/Tavily later is a new provider and
      not a new kind.
- [ ] C3 Surface the CSV: the three identity prefixes (`rss:`, `userrss:`, `gnews:`) encode kind *and*
      provenance; state whether that is left as-is or normalised, and why.

## Slice D — Weather and FX as tracked values *(open decision 1)*

- [ ] D1 A `weather` source per place and an `fx` source per pair, polled like any other source.
- [ ] D2 Each fetch writes observations into `events` + `item_fields`, place as a mention, identity
      **(source, valid time)** so a re-poll cannot duplicate.
- [ ] D3 Series are then visible to rules and, later, to monitors (ADR 0003 §8).
- [ ] D4 *(open)* The Weather and M&M tiles either read the store (one owner; real rewiring) or keep
      their provider cache (two owners). If they read the store, they need an honest empty state.

## Slice E — the rule builder on key / operator / value *(open decision 2)*

- [ ] E1 Decide whether the condition language moves from predicate types to `key, op, value`.
- [ ] E2 If it does: a versioned parse or a migration for `condition_json`, since the shape changes.
- [ ] E3 The builder then has one row shape — pick key, pick operator, give value — with the key list
      from Slice B and no per-predicate special cases.
- [ ] E4 Keep `all` / `any` for OR, and state that tag and mention keys are **multi-valued**.

## Slice F — vocabulary, and delivery

- [ ] F1 Extend the tag vocabulary with Indian-context incident terms (`bandh`, `hartal`, `flood`, `IMD`,
      `red alert`), so they become keys for every rule rather than a regex per rule. The seeded
      `bandh|strike` text rule is the evidence that this is currently the vocabulary's job.
- [ ] F2 **Delivery.** No notification code exists anywhere in the app, so a match writes a row and
      nothing else. This is why the IMD heavy-rain article cannot alert. Detection already works
      (`nature = incident`, `subject = weather`); delivery is the missing half.
- [ ] F3 Revisit the seeded rules once F1 lands: `bandh|strike` should become a tag match.

---

## Verification

Same bar as everything else:

```bash
cd android && ./gradlew :app:ktlintFormat
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest ktlintCheck
```

A slice is not done until it also builds from a **fresh clone**, and device claims are checked with
data (`adb`, `uiautomator dump`, the pulled DB **plus** `-wal`).

## Rules for this plan

- One component per concept; one owner per number. Slice A exists because that rule was broken.
- A key that cannot match must not be offered. The builder's permissiveness is how a rule that silently
  never fires gets authored.
- Do not record a discussion as a decision. ADR 0004 is a proposal until the owner says otherwise.
