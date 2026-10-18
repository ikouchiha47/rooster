# Message categorization — strategy

> Derived from reading all 2694 SMS in the live corpus (dumped at `sms_corpus.txt`).
> Channel-agnostic by design: SMS now, email later, same model.

## Findings (why the current classifier is wrong so often)

- **94% of SMS is business**: 2552 alphanumeric DLT headers vs 142 numeric senders.
- **The DLT header suffix already encodes the type** and is barely used:
  - `-P` → Promotional (978 promo + spillover)
  - `-S` → Service: bill 291, updates 271, txn 222, otp 146, delivery 57
  - `-G` → Government (109) — *currently misclassified as PERSONAL*
- The current `SmsClassifier` is noisy: `…-G` govt → PERSONAL; "Redeem Rs.100 CLiQ Cash" → TRANSACTION; "Uber code 8623" → TRAVEL_LOGISTICS; "Airtel Xstream Play" → PERSONAL; a Crocs welcome → SPAM_FRAUD.
- **A sender is not one category**: `AK-AIRDUE-S` = BILL+TRANSACTION, `TX-SWIGGY-S` = OTP+TRANSACTION+UPDATES → classify **per message**.

## Model: two facets

| Facet | Values |
|---|---|
| **A. Relationship** (who) | `known-contact` · `unknown-number` · `business` (DLT header / short code / branded) |
| **B. Nature** (what) | `otp` · `transactional` (txn/bill) · `purchase`/`delivery` · `promotional` · `personal` · `notification` · `government` · `spam` |

## Categories (tabs) — the projection

**Personal · Transactional · Promotions · Purchases · Government · Spam · Unknown · Alerts**

Precedence (decided):
- A **known contact** always lands in **Personal**, even if the message is promotional (relationship wins).
- **Nature beats relationship** elsewhere: an OTP from an unsaved number is **Transactional**, not Unknown.
- **Government** (`-G`, and govt bodies: TRAI/EPFO/GST/income-tax/CBI) is its own tab.
- **Spam** = fraud/scam.
- **Unknown** = an unsaved number where nothing else fires (absorbs `notification`/updates for now).

## Pipeline (cheap → smart)

1. **Provenance prior**
   - DLT suffix: `P → promotional`, `S → service`, `G → government`.
   - Sender form: short code / DLT header → `business`; number in contacts → `known-contact`; else `unknown-number`.
2. **Content rules** (per message; fix the noisy classes above):

| target | markers |
|---|---|
| otp | `is your ... OTP`, `do not share`, `verification code` |
| transactional | `debited`/`credited`, `a/c`, `payment of Rs`, `bill of`, `due`, `recharge` |
| purchase | `your ... order`, `Order #`, `shipped`, `out for delivery`, `delivered` |
| promotional | `% off`, `offer`, `sale`, `redeem`, `cashback`, `activate`, `exclusive`, promo DND tail |
| government | `-G` header; TRAI/EPFO/GST/income-tax/CBI notice text |
| spam | `KYC`, `lottery`, `click here` + short link, "will be stopped", unregistered sender |
| personal | known contact; or a number sending human text with no template markers |

3. **ML fallback** — the on-device kNN tagger, for ambiguous cases; abstains.
4. **Unknown** — nothing fired.

## Channel-agnostic shape

A `Categorizer` port; each channel supplies a **provenance extractor** and feeds the **same Nature rules**:
- SMS: DLT suffix + sender form.
- Email: sender domain, `List-Unsubscribe`, subject prefixes.

Same categories, same tabs, both channels.

## Conversation → tab

The list is one row per **sender**; category is per **message**. A conversation appears under the tab of its **latest message**, and the row shows that message's category. (Alternative — dominant category — rejected for now.)

## Not categories

- **Alerts** = user-authored **rules** (saved predicates over any source, with a source selector + test/preview). The tab is a view over matches.
- **Missed calls** = an **Action/Reminder suggestion** (Actions seam), or an Alert rule match — never a category.

## Open

- `notification`/`updates` currently fold into **Unknown**; revisit if it deserves its own tab.
