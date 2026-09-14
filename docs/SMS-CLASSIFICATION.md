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
