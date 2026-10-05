# ADR 0007: Tagging as facts and relations — orthogonal axes, taxonomy as data

- Status: Proposed
- Date: 2026-09-26
- Extends: `docs/adrs/0005-facet-catalog-and-typed-measures.md` (facets, `item_tags`, `Tagger` substitutability)
- Related: `docs/adrs/0006-sources-unification.md`, `docs/ARCHITECTURE.md` §10 (tagger ladder), §10.5 (taxonomy)
- Supersedes: the "vocabulary is code" note in `TagGroups.kt`; the bare `strike|bandh` terms in `travel` (`Vocabulary.kt`)

## Summary

Tagging stops being **one flat set of tags** and becomes **one value per orthogonal
axis**, where some values are *extracted* (objective), some are *classified* (a
small closed read), and some are *derived* (relations over facts plus user
context). The vocabulary becomes **versioned data** — leaf ids with descriptions
and per-language surfaces — bundled as an asset for cold start and downloadable
like a model through `mlsdk`. Classifiers (heuristic, embedding, LLM) are confined
to the objective layers; meaning (`travel`, `local`, relevance) is computed as a
relation, never inferred as a label.

## The problem, with evidence

### 1. Tags from different axes collide in one set

A message carries several independent facts at once, and today they compete for
one slot:

| axis | question | values | where it lives now |
|---|---|---|---|
| source/transport | where from? | `sms`, `rss:…`, `gnews:…` | `events.source` ✅ |
| marker | structural? | `news` | `TagGroups.MARKERS` ✅ |
| subject | about what? | `finance`, `tech`, `travel` | `TagGroups.SUBJECTS` |
| nature | what kind of thing? | `incident`, `promo`, `expense` | `TagGroups.NATURES` |
| **function** | what *is* it (as a message)? | `otp`, `statement`, `personal` | **missing** |
| **disposition** | what do *I* do with it? | `inbox`, `muted`, `spam`, `saved` | **missing** (MessagesTab, ad hoc) |

`sms` and `spam` are not natures: `sms` is a transport, `spam` is a per-user
disposition. An OTP has a `function` and no `subject`; a bank notice has a
`subject` and no `function`. Flattening them is why the Messages screen's tabs
(`All/Alerts/Digest/Muted/Saved`) are a lie — a *disposition* axis aliased onto a
*function* control.

### 2. A surface word was promoted to a tag, skipping a layer

`travel`'s term list contains bare `strike|bandh` (`Vocabulary.kt`, since the
bootstrap — its spec put bandhs under the Travel screen). So a **bank** strike
tags `travel` (`heuristic-v9`), when it is `incident` at a `place`. There is no
"I don't know" and no place in that path: surface → tag, no kind, no context.

### 3. The embedding rung decides inside its own noise

Measured on the strike SMS, 256-dim EmbeddingGemma:

```
item vs tags      : 0.409 – 0.544   (spread 0.135)
tag vs tag (mean) : 0.627           (max 0.783)
```

The tag **descriptions are more similar to each other than the item is to its best
tag**, so absolute thresholds (`SUBJECT_MIN .55`, `NATURE_MIN .45`) cut through
noise: `promo` (0.528), `incident` (0.544), `finance` (0.544) are indistinguishable.
The rung is not "wrong"; the decision rule is.

### 4. The vocabulary is code, so it cannot cold-start or scale languages

`Tags`, `TagGroups`, `TagOwnership`, `BundledVocabulary` are Kotlin. A new tag is
a code change; 13 leaves × N languages of surfaces is unbuildable by hand; the
repo already documents the trap (`বন্ধ` "closed" ≠ `বনধ` "bandh", NFC, Bijoy).
A curated closed **surface** list cannot cold-start; a curated **description**
list can.

## Decision

### 1. Axes are orthogonal; an item carries one value per axis

Axes: **source** (L0), **marker** (L0), **subject** (L2), **nature** (L2),
**function** (L2), **disposition** (L3, user-scoped). Subject/nature/marker stay
leaves on `item_tags` + `TagGroups` (0005 §2). `function` and `disposition` are
new; `source` stays a column (0005 §8). Absence is legal on every axis — an OTP
has `function=otp`, `nature=—`, `subject=—`.

### 2. Five layers, one-way, one owner per fact

```
L0 DECLARED   source, declared tags, time        — never inferred
L1 EXTRACTED  place/org/event spans, measures    — evidence + confidence
L2 CLASSIFIED nature, subject, function          — small closed read
L3 DERIVED    local, travel, relevant            — relations + user context
L4 PRESENTED  chips, order, caps                 — UI only
```

A layer reads only the layers below it. **Classifiers never emit L3.** `travel`
is `incident ∧ place ∧ movement-relevant`, not a label — so `strike` cannot
become `travel` by spelling.

### 3. The taxonomy is versioned data; code derives from it

A taxonomy asset (`assets/taxonomy/taxonomy.v1.json`) owns, per leaf: `tag`,
`group`, `owner`, `description`, `terms`. `Tags`, `TagGroups`, `TagOwnership`,
`BundledVocabulary` become **views over the loaded taxonomy**. New leaf or new
term is data; the partition tests become taxonomy-validation tests.

### 4. Bundled for cold start; downloadable like a model

The taxonomy is an APK asset (offline default) **and** a `mlsdk` `ModelSpec`
downloaded via Settings, verified by sha, with `evictUnlisted` keeping only the
active set. A downloaded taxonomy merges over the bundled one by leaf id.

### 5. Descriptions are the cold-start path; surfaces accrete

Curate **descriptions** (one sentence per leaf) and let a multilingual embedder +
LLM cover unknown languages/senses; surfaces (`hartal`, `धर्मঘট`, `বনধ`) accrete as
evidence onto existing leaves, via a review queue — never straight into the
runtime taxonomy. The taxonomy stays closed per version.

### 6. The decision rule must be honest

`selectTags` moves to **centered cosine + margin**, and reports *unsure* rather
than emitting a low-margin guess. This is a prerequisite for the description rung
cross-linguistically (anisotropy fix), not a nicety.

### 7. Meaning is a relation, with provenance

`derivations` are data: `{tag, from, when}` (e.g. `travel = incident ∧ place ∧
movement-relevant(place, me)`; `local = place ≈ my places`). Every derived fact
records its inputs and method (`extracted | inferred | derived`), so the UI can
explain and a correction targets the **base** fact.

### 8. Disposition is user-scoped, never vocabulary

`spam`/`muted`/`saved` are per-user derived facts (structural inputs: bulk sender,
unknown contact, nature; plus behaviour). They are not leaves in the shared
taxonomy.

### 9. The LLM is one more rung, gated

`LlmTagger` (on-device Gemma-class, per the §10 ladder) is a substitutable
`Tagger`, escalated **only** for items the cheap rungs mark *unsure*. Strict enum,
unknown labels dropped. Cloud is out (zero recurring cost).

## Options & Tradeoffs

| Option | Correctness | Complexity | Cost | Time-to-Value | Notes |
|---|---|---|---|---|---|
| **A (chosen): axes + layered facts + taxonomy data** | ✅ | ◑ | low | medium | extend 0005; relations explainable |
| B: keep one flat tag set, fix terms | ◑ | low | low | fast | re-breaks next axis/language |
| C: one big LLM classifier over everything | ✅ | ◑ | high/run | fast | violates on-device budget |
| D: kNN / prototype over labelled items | ◑ | ◑ | grows | slow | needs labels + cold-start; not this |
| E: graph DB | ✅ | high | high | slow | SQLite joins already suffice |

## Contracts & Interfaces

```kotlin
// core/tag/taxonomy — zero android.*
data class Leaf(
    val tag: String,               // stable id, e.g. "incident"
    val group: TagGroup,           // MARKER | SUBJECT | NATURE | FUNCTION
    val owner: TagOwner,           // LEXICON | EMBEDDING
    val description: String,       // zero-shot sentence (rung 1)
    val terms: String,             // '|'-separated surfaces (rung 0; may be blank)
)

data class Derivation(             // L3 relation, data not code
    val tag: String,               // e.g. "travel"
    val from: List<String>,        // ["incident", "place"]
    val when_: String,             // human rule; engine binding later
)

data class Taxonomy(
    val id: String,
    val version: Int,
    val leaves: List<Leaf>,
    val markers: List<Leaf>,
    val derivations: List<Derivation>,
)

interface TaxonomySource {          // bundled asset, downloaded file, later remote
    val id: String
    fun load(): Taxonomy?
}

interface FactStore {               // L1/L2 read/write; item-scoped
    suspend fun put(itemId: String, fact: Fact)
    suspend fun facts(itemId: String): List<Fact>
}

data class Fact(
    val axis: String,               // "nature" | "subject" | "function" | "place" | "event" | …
    val value: String,
    val method: Method,             // EXTRACTED | INFERRED | DERIVED
    val confidence: Float,
    val evidence: String? = null,   // span, rule id, or model id
)
```

`selectTags` returns `Selection(tags, confidence, unsure: Boolean)`; `Tagger` gains
`tagAll` (already added) and stays substitutable.

## Data model

- L0: `events.source`, declared tags, `sync_runs` (unchanged).
- L1: `mentions` (place/person/org/event: `kind`, `surface`, `entity_id`, `confidence`), `item_fields` (measures).
- L2: `item_tags` (+ `item_tags_current`) for subject/nature/**function**; `facets`/`kind_facets` (0005) describe them.
- L3: a **derivations** store keyed `(item_id, tag)` with `inputs_json`, `method`, `confidence`, `rule_id`; **disposition** is user-scoped (a pref- or row-scoped store, TBD).
- Taxonomy: asset + downloaded file; nothing code-side.

No new graph store: joins over the tables above.

## Functional Scope (Decision-Scoped)

| ID | Requirement (What) | Why | Verification |
|---|---|---|---|
| REQ-TAX-01 | The taxonomy shall load from the bundled asset with no network. | Cold start | asset parse |
| REQ-TAX-02 | A downloaded taxonomy shall override a bundled leaf by `tag`, and add leaves not present. | Update without release | merge test |
| REQ-TAX-03 | `TagGroups`/`TagOwnership` shall equal the taxonomy's groups/owners; a leaf missing a group shall fail load. | One owner; partition | validation test |
| REQ-TAX-04 | Every non-blank `description` shall be embeddable; a blank one and no terms shall be rejected. | Description rung needs a sentence | load throws |
| REQ-TAX-05 | An unknown downloaded taxonomy (bad sha/size) shall be rejected and the bundled one used. | Integrity | store test |
| REQ-AXIS-01 | An item shall carry at most one value per axis. | Orthogonality | write test |
| REQ-AXIS-02 | `sms` and `spam` shall never appear as a nature/subject/function leaf. | Axis hygiene | taxonomy test |
| REQ-SEL-01 | WHEN scores are centered THEN a clear top leaf shall pass and a flat set shall return **no** tag. | Honest abstention | synthetic vectors |
| REQ-SEL-02 | WHEN the top two are within margin THEN the selection shall be `unsure`. | Gate for LLM | synthetic vectors |
| REQ-DER-01 | `travel` shall be produced only by the `incident ∧ place ∧ movement` relation, never by a term. | Kills strike→travel | derivation test |
| REQ-DER-02 | A derived fact shall record `inputs` and `method=derived`. | Provenance | row assertion |
| REQ-DISP-01 | `spam` shall be user-scoped and shall not be a taxonomy leaf. | Disposition ≠ vocabulary | taxonomy test |
| REQ-LLM-01 | Only `unsure` items shall reach the LLM rung. | Bounded cost | fake LLM count |
| REQ-LLM-02 | An LLM label outside the closed set shall be dropped; every input shall yield a result. | Liskov | fake LLM |

## Non-goals

- No cloud LLM; no graph database; no `eval`/CEL.
- Not replacing 0005's rule facets or `Condition` JSON.
- Not implementing the `function` classifications beyond OTP/statement in v1.
- Not requiring `READ_CONTACTS` for `personal` now (sender-pattern first).
- Not defining the travel/movement store here (a follow-up fact).

## Rollout

1. **P1** taxonomy asset + `TaxonomySource` (asset + downloaded) + mlsdk spec + Settings row; `Tags`/`TagGroups`/`TagOwnership` become views. Behaviour unchanged → verify no regression.
2. **P2** `selectTags` centered-cosine + margin + `unsure`; retag; `RECOVERY_VERSION` bump.
3. **P3** `derivations` store + the `travel`/`local` relations; delete the bare `strike|bandh` terms.
4. **P4** `function` (sender/DLT/template) + Messages UI split into function vs disposition.
5. **P5** `LlmTagger` + gated cascade.
6. Rollback: pin the bundled taxonomy; the downloaded file is ignored on bad sha.

## Resolved defaults

Decided here (not deferred), each from an existing principle rather than preference:

| Question | Decision | Why |
|---|---|---|
| Is `function` a leaf group or `item_fields`? | **Leaf group `FUNCTION` on `item_tags`** | It is an enumerated class like `nature`; 0005 keeps classes on `item_tags` and only *measures* in `item_fields` |
| Where does `disposition` live? | **Leaf group `DISPOSITION` on `item_tags`, own producer** | Needs per-item rows and user override; a prefs cache cannot hold structure. The user's own correction is a `user-v1` producer that wins |
| Are `derivations` rows or `facets`? | **Definitions in the taxonomy (`derivations`, data); results written as `item_tags` by a relation writer (`taggerId = relation-v1`, `method = derived`)** | One store; do not overload 0005's catalog, which describes *definitions*, not per-item values |
| How is the taxonomy versioned? | **Pinned per version**, the tagger id embeds the taxonomy version, a change triggers a retag | Matches model pinning + the retag machinery; latest-wins-per-leaf would make stored tags ambiguous |
| Does `subject` apply to SMS? | **Yes, for every item; abstention is common for SMS** | Falls out of the margin rule; special-casing by source would be a `when(source)` (OCP) |

### Consequence: "current" becomes per axis

The moment more than one **producer** writes tags — the classifier (nature/subject/
function), the relation writer (travel/local), the disposition writer (spam) — the
old rule "`item_tags_current` = the one active tagger" is wrong. It must become
**the winning producer *per axis*** (latest writer per `(item, axis)`, with a
user override winning its axis). Every `Tagger`/producer therefore records its
**axis** and **method**; `item_tags` gains those two columns (or a `facts`
sidecar), and `item_tags_current` is re-expressed as a per-axis window.

Genuinely product-scoped decisions (yours, not mine):

- **the movement fact** — a tracked trip / away-from-home window. Without it,
  `travel` degrades to "incident not at home" (the next fact to model).
- **`READ_CONTACTS`** — whether `personal`/`spam` may use the contact list, or
  stays sender-pattern-only.

## Links

- Extends: `docs/adrs/0005-facet-catalog-and-typed-measures.md`
- Ladder: `docs/ARCHITECTURE.md` §10
- Taxonomy asset: `android/app/src/main/assets/taxonomy/taxonomy.v1.json`
- Tests: `android/app/src/test/kotlin/com/personalos/app/core/tag/`
