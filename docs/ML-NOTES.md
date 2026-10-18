# ML Notes: the classical text heads, learned on our data

All four heads below do the same job — score every class, pick the best —
so they are swappable behind one runner. They differ in what the score *means*.
Numbers are from `eval/train_rss.py` on the frozen 143-row test (train 11,097).

## 1. Multinomial Naive Bayes — "how does each class write?"

Generative story (McCallum & Nigam 1998; Manning ch.13): imagine each class
*authors* text by rolling a loaded die over the vocabulary, one word at a time,
independently (the "naive" position-independence assumption). Learning is
counting: for `labour-action`, count every word across its 41 rows.

Classify with Bayes:

```
score(c) = log P(c) + Σ count(w) · log P(w|c)
P(w|c)   = (count(w in c) + α) / (total words in c + α·V)
```

Worked miniature: `strike` appears 60× in labour rows (say 2,000 total words)
and 30× in official rows (say 200,000 words), V = 52,331, α = 1:

```
P(strike|labour)   = 61 / 54,331  ≈ 0.00112
P(strike|official) = 31 / 252,331 ≈ 0.00012   → 9x weaker, good
```

The `+α` is Laplace smoothing: without it, one unseen word zeroes the whole
product (hence log-space: sums, no underflow). Our result: **0.483**, labour
0.00. Why it lost here: with 78× skew and a 52K vocab, the blunt `+1` spreads
mass over tens of thousands of unseen words while the prior `log P(c)` buries
41-row classes. Textbook NB assumes balanced-ish small sets — ours is neither.

**ComplementNB** (Rennie et al. 2003) is the textbook fix for skew: estimate
each class from its *complement* ("what does non-labour write?") and pick the
*worst* complement fit. Ours: **0.643**, every class non-zero. Skew handled,
in one line.

Ships as: two small tables (log-priors + log-likelihoods). Micro-seconds.

## 2. Nearest centroid (Rocchio) — "which class direction is closest?"

Geometric story (Rocchio 1971, relevance feedback): average every class's rows
into one centroid vector (mean TF-IDF direction), classify by cosine
similarity. No training iterations, no priors, no loss function:

```
centroid(c) = mean of L2-normalised rows in c
score(c)    = cosine(x, centroid(c))
```

Miniature: two labour rows, `strike` heavy in both → centroid points along
`strike/unions/bank`. A test row sharing those words has high cosine even if
the class has 41 rows and its rival has 3,222 — counts never enter the formula.

Ours: **0.476** overall, but `labour-action` **1.00/0.71**, weather 0.75/0.50,
tech 0.20/0.67 — the only head that holds *every* thin class. The price shows
on fat classes: `official`'s centroid averages 3,222 diverse rows (courts,
bypolls, universities…) into mush, so everything is kinda-close and recall
bleeds (0.31). No priors means no tyranny of the majority — and no help from it.

Ships as: 9 centroid vectors. One matrix multiply per item.

## 3. The three families, one table

| | Logistic (discriminative) | NB (generative) | Rocchio (geometric) |
|---|---|---|---|
| Learns | the boundary | how each class writes | the class direction |
| Score is | calibrated-ish logit | log-probability (overconfident) | cosine (unbounded) |
| Loves | fat classes, big data | tiny balanced sets | thin classes, any skew |
| Dies on | 78× skew (fixed by weights: 0.615→0.650, labour back to 1.00/0.86) | skew + huge vocab (fixed by Complement) | diverse fat classes |
| Ships as | weight matrix | two log tables | centroid matrix |

Practical rule from our board: no single classical head holds all nine —
LR owns fat classes, Rocchio owns thin ones, ComplementNB is the honest middle.
That is a routing argument (thin-class second opinion), not a bigger-model
argument. The neural head (MiniLM) has to beat *this* ensemble, not bare LR.
