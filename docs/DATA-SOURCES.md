# Data sources

Where the coarse-bucket training text comes from, what is already in the tree,
and what is queued. Sourcing rules first, because they are the ones most likely
to be broken.

## Sourcing rules

1. **External corpora are training text, never gold.** Public dumps give us
   *content and a rough label*; only the in-domain sets (`eval/ml_test.jsonl`,
   `eval/rss_test.jsonl`) grade the model. Never add an external set to test.
2. **Single label in, multi-label out.** A source carries one label; the teacher
   relabel (`eval/relabel_indic.py`, one shared prompt in `eval/teacher_student.py`)
   turns it into our multi-label buckets, or `none` to abstain.
3. **Buckets are IPTC Media Topics top-levels** so the vocabulary is not invented
   here and new sources map cleanly. Current set (13 + `other` code fallback):
   `finance, official, politics, crime, incident, sports, entertainment, tech,
   weather, travel, festival, health, education`.
4. **Dedup by normalised text across train and test**, train emitted first, so no
   test item can leak in from train (`eval/build_indic.py`).
5. **`official` = who is acting, plus the subject.** A regulator or government
   body issuing a notice/circular/reminder is `official` **and** the subject
   bucket (RBI rate or tax-filing reminder → `finance` + `official`; court order →
   `crime` + `official`; ministry advisory → subject + `official`). `official`
   alone only for a purely administrative notice.
6. **Data-quality floor (audited, applied in `build_indic.py`).** Two drops, both
   measured, not guessed:
   - **Length floor (`MIN_CHARS = 15`).** A one-word title cannot carry a topic.
     The Bengali `editorial` section ships these (`বাধা`, `দুঃস্বপ্ন`).
   - **Near-duplicate collapse (`DUP_PREFIX = 48`).** News re-serves one story as
     templated variants (*"\<X\> Elections 2022 vote counting LIVE: For
     \<constituency\>"*, 342 rows), which exact dedup misses and which **leak
     across the train/test split** (48 groups did). Two rows sharing a 48-char
     normalised prefix are the same story; keep one.
   Audit result per language: `en` clean; `hi`/`bn` have **no** rows lacking native
   script (English brand names inside Hindi/Bengali headlines are normal, not
   mislabels). After cleanup: **0** rows under the floor, **0** near-dup groups
   crossing the split.

## Indic (L3Cube-IndicNews)

Source: L3Cube-IndicNews, **SHC** (short-headline) variant, HF mirror
`ayushbagaria17/indic-nlp`, per-language CSVs under `eval/indicnews/`.

Filtered into `eval/indic_pool.jsonl` by `eval/build_indic.py`; the teacher relabel
proceeds next.

**Done — en / hi / bn:**

| | en | hi | bn | total |
|---|---|---|---|---|
| raw (train+test) | 41,487 | 34,687 | 25,965 | **103,139** |
| after cleanup | 24,554+2,826 | 27,461+3,177 | 23,296+2,904 | **84,218** |
| split | — | — | — | train 75,311 / test 8,907 |

Removed from the raw 103,139: exact duplicates, **628** sub-15-char fragments, and
**910** templated near-duplicates (the audit above). Raw→final also drops rows with
an empty text or label field.

- Kept as single-label buckets: `politics, crime, sports, entertainment, tech,
  finance, health, education`.
- Remaining source labels (`national, international, world, india, state,
  kolkata, editorial, lifestyle, auto, automobile`) are kept as `"remainder"` with
  no bucket — the teacher decides them (mostly `official`/announcements, else
  `none`).
- Only the **SHC** variant is used; L3Cube also publishes longer full-article
  versions, not taken.

**Queued — the other languages, in batches of three** (same pipeline, one line
each in `eval/build_indic.py`):

1. `mr` Marathi, `ta` Tamil, `te` Telugu
2. `gu` Gujarati, `kn` Kannada, `ml` Malayalam
3. `pa` Punjabi, `as` Assamese, `or` Odia
4. `ur` Urdu, `ne` Nepali

## Region expansion

The on-device encoder is **EmbeddingGemma** (100+ languages), so a new region is a
**label head + a vocabulary batch**, not a new model. Chosen regions, in order:

### 1. Indonesia — `id` (Bahasa Indonesia)

Nearest large twin after the Indic set: Android-first, sideloading normal,
WhatsApp-dominant, low-end devices. Buckets that carry the most signal there:
**weather** (floods, volcanic ash), **incident**, **official** (ministry and
regional notices), **finance**, **sports** (football, badminton), **politics**.

### 2. Nigeria — `en` + `ha` (Hausa), `yo` (Yoruba), `ig` (Igbo), `pcm` (Pidgin)

Android-first and English-adjacent, price-sensitive, high fraud/scam volume. The
buckets that already exist and matter most: **official**, **finance** (mobile
money), **crime** (fraud/scam), **incident**, **politics**, **sports** (football).

### 3. Philippines — `tl`/`fil` (Filipino/Tagalog), `en`, `ceb` (Cebuano)

SMS is still a primary government channel (not just chat apps). Buckets:
**weather** (typhoons), **incident**, **official**, **entertainment**, **sports**
(basketball).

### 4. Russia — `ru` (Russian)

Android-heavy, and one of the most sideload-normal markets: Play paid apps and
billing are restricted there, so APK distribution is already the norm. Buckets:
**official**, **politics**, **finance**, **incident**, **weather** (winter),
**sports** (football, hockey).

### 5. Japan — `ja` (Japanese)

Large market, but the weakest fit on this axis: it has among the **highest iOS
shares in the world**, so a sideloaded Android app reaches a minority. Worth a head
only because the encoder (`EmbeddingGemma`) and the news corpora cost nothing extra.
Buckets: **weather** (typhoons, earthquakes), **incident**, **official**,
**finance**, **sports** (baseball), **tech**.

### 6. China — `zh` (Chinese, `zh-Hans` / `zh-Hant`)

Android-only in practice, but with **no Google services and domestic app stores**,
so the cost is distribution and regulation, not the language. Buckets: **official**,
**finance**, **politics**, **tech**, **incident**, **weather**, **sports**.

### Candidate corpora — to verify before use

Marked *unverified*: confirm licence, format and label granularity before wiring
any of these in.

- Indonesia: IndoNLU / IndoNLG news benchmarks; Indonesian news corpora on HF
  (Kompas- and Suara-derived sets). *unverified.*
- Nigeria: NaijaSenti (Hausa/Yoruba/Igbo/Pidgin), MasakhaNER (Hausa/Yoruba/Igbo).
  *unverified.*
- Philippines: Tagalog/Filipino news corpora (e.g. TLUnified-derived). *unverified.*
- Russia: Russian news corpora (e.g. Lenta.ru-derived), RuSentiment. *unverified.*
- Japan: Japanese news corpora (e.g. the livedoor news corpus). *unverified.*
- China: Chinese news corpora (THUCNews; the CLUE/TNEWS short-text set). *unverified.*

Same rules as above apply: single-label source in, teacher relabel, in-domain gold
only.
