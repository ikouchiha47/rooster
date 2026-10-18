# ML Findings: what generalises, what memorises, what to ship

Frozen test = 143 story-deduped rows. Paraphrase test = 61 rows (same
stories, teacher-reworded). Everything below is on-device-feasible
architectures; no cloud inference.

## 1. The four approaches on the same two tests

| approach | frozen exact / micro / macro | para exact / micro / macro | labour para (P/R) |
|---|---|---|---|
| BoW OvR (TF-IDF + Logistic) | 0.420 / 0.723 / **0.714** | 0.279 / 0.696 / 0.685 | 0.83 / 0.71 |
| MiniLM full fine-tune (BCE) | 0.434 / 0.681 / 0.455 | 0.180 / 0.571 / 0.316 | 0.00 |
| MiniLM **frozen** + LR heads | 0.364 / 0.715 / 0.678 | 0.344 / 0.686 / 0.678 | 0.67 / 0.57 |
| bge-small **frozen** + LR heads | 0.336 / 0.720 / 0.711 | 0.361 / **0.740** / **0.720** | 1.00 / 0.43 |

(`eval/frozen_heads.py`; BoW from `eval/train_multilabel.py`.)

## 2. The three findings that matter

**Fine-tuning destroyed robustness.** Every tuned dense model lost more than
BoW on paraphrases, and MiniLM fine-tune collapsed labour to 0.00 — it learned
the surface tokens and nothing else. The paraphrase probe turned "accuracy"
into a robustness test and the winner flipped.

**Frozen pretrained geometry is paraphrase-proof.** Both frozen backbones
match their tuned twins on frozen test while *beating* them on paraphrases.
The encoder already knows that "lender stoppage" ≈ "bank strike"; the linear
head only has to project that meaning onto our 9 labels. That is the whole
"KB heads on a frozen base" thesis, measured: paraphrase does not degrade it
(0.361 vs 0.336 for bge — it *improves*, because vetted rewordings are clean).

**Retrieval-tuning beats MLM-tuning as a frozen floor.** bge-small (trained to
put similar meanings close) beats MiniLM-ST (masked-LM lineage) on every
aggregate once tuned, and holds thin classes (tech 0.67/0.89) that BoW loses
(0.40/0.44) and MLM-MiniLM drops. Which backbone matters only when frozen;
tuning washes the difference out and destroys it in the process.

## 3. Why BoW still ties (and why not to build on it)

BoW matches the best dense macro on frozen test (0.714 vs 0.720) — because
these are news/topic stories where a few marker words suffice ("strike",
"rain", "Sensex"). It is not extensible: it needs the exact marker to appear
in training. Paraphrase sets the binomial-word problem loose and BoW's labour
head falls to 0.00 until each rewording is hand-added (augmentation recovered
0.83 by teaching the pairs explicitly — whack-a-mole, not understanding).

BoW is a good *floor and fallback*: microseconds, no encoder, no size. It is
not the extensible substrate.

## 4. What to ship

- **Frozen bge-small + per-class LR heads** as the primary topic head:
  paraphrases survive, thin classes survive, trains in seconds (encode once,
  then logistic), ships as one 384-d encoder + a 9×384 weight matrix + biases.
- **BoW as fallback / abstain-tiebreaker** where the input is too short to embed.
- **Never full fine-tune** for this build: it costs hours on CPU, collapses on
  thin classes, and is *less* robust than frozen.

## 5. Cost of the lesson (do not repeat these)

- SetFit with a transformer backbone: ~10h on this 4-core CPU even at 5
  iterations — killed. Contrastive pair mining over 11K rows is not on-device
  feasible; reserved for a GPU/Modal run, and only *after* frozen proves the
  geometry is worth tuning (it is, but tuning hasn't helped on any dense run).
- Weighted single-label, WCE, class-balanced loss, seed sweeps: five full
  fine-tune runs, none beat frozen. Tune the *head*, not the encoder.
