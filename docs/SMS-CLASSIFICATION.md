# SMS Classification

On-device only. No SMS content leaves the phone.

---

## 1. Two-stage classification

Spam is not a peer of the other intents - it is a gate. Run it first.

```
                      SMS
                       |
        stage 1:  [ SPAM_FRAUD? ] ---- yes ---> drop / quarantine
                       | no
        stage 2:  intent class (7 legitimate classes)
```

## 2. Taxonomy (final)

Synthesised from the two proposals (Gemini's 8-class list and Qwen's tree).
Gemini's classes are kept as the backbone because they are mutually exclusive and
have clear definitions. Qwen's contribution is kept as the **spam-first gate** and
as a reminder that `TRAVEL` and `LOGISTICS` are conceptually distinct sub-types.

| # | Class | Definition | Signals | Example |
|---|---|---|---|---|
| 1 | `PERSONAL` | Human-to-human conversation | 10-digit / unknown sender, no DLT suffix, conversational, no amount | "Hey, are we still on for dinner?" |
| 2 | `OTP` | Authentication / verification codes | 4-8 digit code, "OTP", "do not share", "valid for N min", short message | "482910 is your one-time password" |
| 3 | `TRANSACTION` | Money movement | `debited`/`credited`/`spent`, amount, masked a/c, txn ref | "Acct X123 debited Rs 45.00 at Starbucks" |
| 4 | `BILL` | Payment due / renewal | "due", "minimum payment", "expires in N days", amount | "Your internet bill of Rs 60 is due on 5 Oct" |
| 5 | `TRAVEL_LOGISTICS` | Things in motion: travel and shipping | PNR, flight/train, boarding, "out for delivery", courier, ride | "PNR 6209444490, DOJ 21-09-26" |
| 6 | `UPDATES` | Informational business / service (CCU) | appointment, ticket resolved, booking confirmed, non-marketing | "Confirmed: dentist appointment tomorrow 10 AM" |
| 7 | `PROMOTIONAL` | Legitimate opted-in marketing | `% off`, "sale", "code", loyalty points, coupon | "FLAT 25% OFF with code WISH" |
| 8 | `SPAM_FRAUD` | Malicious / unsolicited | prize/lottery, "verify your account", mismatched sender, shortened URL, urgency | "You won a $1000 gift card, click bit.ly/..." |

Sub-types (optional, do not change the class):
- `TRAVEL_LOGISTICS` -> `travel` | `logistics` | `ride` | `food`
- `TRANSACTION` -> `debit` | `credit` | `salary`
- `SPAM_FRAUD` -> `phishing` | `lottery` | `aggressive_marketing`

## 3. Strong free signal: the DLT sender suffix

Indian commercial sender IDs follow `CC-HEADER-C` (TRAI/DLT). The trailing letter
already separates the big buckets, before reading a single word:

| Suffix | Meaning | Maps to |
|---|---|---|
| `-P` | Promotional | `PROMOTIONAL` (or `SPAM_FRAUD` if it looks deceptive) |
| `-S` | Service / transactional | one of `OTP` / `TRANSACTION` / `BILL` / `TRAVEL_LOGISTICS` / `UPDATES` |
| `-T` | Transactional | as above |
| `-G` | Government | `UPDATES` |

And the 6-character `HEADER` identifies the principal entity (brand): `JX-BATAIn`
= Bata, `AK-AIRDUE` = Airtel. A small header -> brand map gives entity extraction
for free.

Verify the suffix mapping against the real corpus before relying on it.

## 4. Features

1. **Sender / DLT**: `sender_type` (alpha | shortcode | mobile | intl), `dlt_category`, `header_id`, `is_first_seen`, sender prior class rate.
2. **Lexical**: keyword set per class, script detection (Devanagari / Bengali / Tamil present).
3. **Structural**: has standalone 4-8 digit code; has amount (`Rs|INR|` + number, Indian grouping `1,23,456`); masked account (`••4021`, `XXXX1234`); URL + shortener domain; date/time; percentage.
4. **Statistical**: length, uppercase ratio, digit ratio, punctuation/URL density, char 3-5-grams + word n-grams (TF-IDF). Character n-grams are script-agnostic, so they work for Bengali/Tamil without a special tokenizer.
5. **Temporal**: hour/day, burst detection.
6. **Context**: app foreground / recent user action, sender in contacts, historical label for that sender.

## 5. Model ladder (cheapest first, each rung optional)

| Rung | Approach | Training | Labels | Size |
|---|---|---|---|---|
| 0 | Rules + DLT suffix | none | none | 0 |
| 1 | Prototype / nearest-centroid over a few examples per class | none | a handful per class | ~0 |
| 2 | TF-IDF char n-grams + logistic regression | seconds | ~200-500 | 1-5 MB |
| 3 | Pretrained static embeddings + linear head | head only | ~200-500 | 8-15 MB |
| 4 | Fine-tuned TinyBERT / MiniLM (int8) | a real run | 100s-1000s | 10-25 MB |

Budget constraint: the classifier must stay in the **tens of MB**, so rungs 3-4 only;
Gemma-scale models are reserved for the hard minority (NL to rule, summarisation),
not per-SMS classification.

Measure rung 0 on the real corpus first. If rules + DLT cover most messages, do not
build the rest.

## 6. Output schema

Constrained JSON, one object per SMS:

```json
{
  "class": "TRANSACTION",
  "sub_type": "debit",
  "confidence": 0.93,
  "amount": 1214.00,
  "currency": "INR",
  "merchant": "ACT Fibernet",
  "entities": ["ACT Fibernet"],
  "deadline": null,
  "is_dlt_promo": false
}
```

## 7. Evaluation

- Label a held-out set (cluster first so you label ~40 groups, not thousands).
- Report **per-class precision/recall**, not just accuracy - `PROMOTIONAL` and
  `SPAM_FRAUD` are the classes where errors are most costly to the user.
- Re-run the eval whenever rules or model change.

## 8. Decision (2026-10-16): BoW ships, MiniLM is the reference

Setup: `eval/train.jsonl` / `eval/test.jsonl`, sender-grouped so train and test
share no sender, `unknown` held out as the abstain bucket. BoW from
`eval/tiny_text.py` (log: `eval/tiny_text.2026-10-16.log`); transformers from
`eval/tiny_transformer.py` (3 epochs, lr 2e-5; log:
`eval/tiny_transformer.2026-10-16.log`).

| Approach | Test acc | int8 size | Verdict |
|---|---|---|---|
| BoW linear (word uni+bigrams, margin tau=0.3) | 0.914 | 0.12 MB | **ships** |
| MiniLM-L6 fine-tuned | 0.906 | 22.7 MB | reference ceiling, not shipped |
| Rules-only | 0.882 | 0 | first rung, always on |
| TinyBERT-4L fine-tuned | 0.876 | 14.4 MB | rejected (below rules) |
| Frozen EmbeddingGemma head | ~0.83 | +300 MB model | rejected (budget) |
| bert-tiny fine-tuned | 0.819 | 4.4 MB | rejected |

Caveat: 123/591 test rows (20.8%) are normalised-duplicate bodies of train rows,
which BoW memorises. On the leakage-free subset (n=468): linear tau=0.3 0.895,
rules-only 0.855, rules + linear-on-unknown 0.874. Ranking is unchanged, so the
decision stands; the transformer numbers above are on the 498-row
unknown-held-out cut, not the leakage-free subset.

**Why BoW ships:** highest accuracy at ~1/45th the bytes of the nearest
neural rival. One weight matrix + softmax is a dozen lines of Kotlin, trivially
int8, and the abstain margin reads straight off a calibrated softmax.

**Why MiniLM stays:** best neural result and the architecture that *should* win
once data allows (context disambiguates what counts cannot - e.g. strike-sense).
At 22.7 MB it fits the tens-of-MB budget on paper, but -0.008 acc for ~190x the
bytes is not a trade today. Revisit after the teacher-labelled pools land.

**Key finding across all three transformers:** minority classes collapse
(personal / purchases / spam all 0.0 P/R; government weak). They win the two fat
classes and punt the rest, while BoW holds the tail up. That is a data-quantity
story, not architecture - 3 epochs over 1960 rows cannot teach rare classes to
a 4-23M-param model. Next fuel, in order: `eval/sms_pool.jsonl` (517 fresh
device SMS, teacher-labelled then concatenated) and the Gmail pool.

**Rejected without a run, and why:**
- LSTM: size-table reference only (`tiny_text.py` CONTEXT footer, ~1.4 MB int8
  for V=10k,d=128,h=128). A 1.4 MB recurrent model against a smaller linear one
  at ~0.914 is a tough sell; no experiment warranted.
- nanoGPT: wrong shape. "Nano" is the code, not the model (default build 124M
  params); a decoder-only LM needs far more text than 2.6K SMS to beat BoW and
  would classify autoregressively on-device.
- XGBoost: wrong tool for sparse text (trees split one feature at a time; ~10K
  sparse dims combine only linearly). Legitimate only for dense meta-features
  (sender-kind, length, digit runs) - unmeasured, offer stands.

**Harness note:** `prajjwal1/bert-tiny` no longer loads via `AutoModel` on
transformers 5.x; `eval/tiny_transformer.py` now falls back to the explicit
`BertForSequenceClassification` (`model_for`, mirroring `tokenizer_for`).

**Open drift:** the evals above use the 7-class message taxonomy
(`personal/transactional/promotions/purchases/government/spam/unknown`), not the
8-class table in §2. Reconciling the two taxonomies is still open.
