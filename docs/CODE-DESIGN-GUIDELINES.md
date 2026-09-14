# Code design guidelines

**Status: binding.** These are not preferences. A change that violates one of these
is wrong even if it works, because the next change becomes harder.

Written because the same class of mistake kept recurring: a fact gets baked into
code, a screen grows its own copy of it, and then adding a place, a feed, or a
festival name means editing several files and hoping nothing was missed.

---

## 1. Layering

```
source            repository / domain           UI model              component
(network, disk,   Flow<DomainModel>,            per-screen state,     layout, caps,
 device, user)    single source of truth        derived/combined      formatting
```

- **Sources** are swappable implementations behind an interface, chosen in one place
  (`AppContainer`).
- **The repository / domain model** is the single owner of a fact. It exposes
  `Flow`s (or suspend calls). It has no opinion about presentation.
- **The UI model** is per-surface and free to reshape, combine, and cap. It is
  derived, never a stored second copy.
- **Components** decide *how much* to show and *how* to format it.

**Rules**

- A fact has **exactly one owner**. If two surfaces each fetch or each store it,
  that is a bug, not an optimisation.
- Data flows **one way**. The data layer never imports anything from `ui/`.
- A surface may hold a *derived* view of a fact. It may never hold a *second copy*
  that can drift.
- Caps, limits, ordering-for-display, and string formatting live in `ui/`. A
  repository that returns "the first 3 places" has stolen a UI decision.

---

## 2. Open/Closed — extend, do not modify

Adding a *kind of thing* must never mean editing the code that handles the existing
kinds. If your diff adds a word, a branch, or a constant to an existing class to
support a new case, stop: that case wants to be **an implementation** or **data**.

### Worked example — festival vocabulary

**Today (violates OCP).** `HeuristicTagger` owns `const val FESTIVAL_WORDS`. Adding
"Burning Man" means editing the tagger, bumping its version, and re-tagging the
entire store.

**Target.** The tagger *receives* vocabulary; it does not own it.

```kotlin
/** A source of terms for one tag. Extend by adding an implementation. */
interface TermSource {
    val id: String
    fun terms(): Flow<Map<String, Set<String>>>   // tag -> terms
}

class BundledTermSource      : TermSource   // ships with the app (today's seed)
class UserTermSource(dao)    : TermSource   // written from Settings
class RemoteEventTermSource  : TermSource   // Exa/Tavily job, later
class CompositeTermSource(sources: List<TermSource>) : TermSource  // merges; later wins

class HeuristicTagger(
    private val vocabulary: Flow<Map<String, Set<String>>>,
) : Tagger
```

Now adding "Burning Man" is **a Settings row or a new `TermSource`** — the tagger is
never opened. The version bump and full re-tag disappear too, because the vocabulary
is data the tagger reads, not code it embeds.

This generalises. The same shape applies to: which feeds exist (`FeedCatalog` is a
hardcoded `object` today), which places are tracked (`weatherLocations` is a literal
list in `AppContainer`), radar tabs (`RADAR_TABS`), and tile configs (`NEWS_TILE`,
`WEATHER_TILE`).

---

## 3. Liskov — implementations are substitutable

Any implementation of an interface must honour its contract for **every** input:

- No narrowing: if the interface promises a result, do not return "unsupported".
- No throwing where another implementation succeeds.
- No silent no-ops that look like success.
- Same units, same ordering, same null semantics.

If an implementation genuinely cannot serve a case, **that case belongs in the
interface's contract** — model it (e.g. `Result.Unavailable(reason)`), rather than
letting one implementation quietly behave differently.

Corollary: an interface that only one implementation can satisfy is not a seam. It
is a class wearing a costume.

---

## 4. ISP — small, role-specific interfaces

One reason to change per interface. Prefer several narrow interfaces over one broad
one: `WeatherProvider`, `LocationProvider`, `FxProvider` rather than a single
`DataProvider` with fifteen methods.

A consumer should depend only on what it uses. If a screen must implement or stub
methods it never calls, the interface is too wide.

---

## 5. Settings is a writer, not a special case

Settings is the **edit surface for the same store** everything else reads. It is not
a parallel path.

The acceptance test: **adding a place (or a feed, or a festival) must change the
Settings layer only.** If it requires touching Home, Weather, Radar, or the tagger,
the model is wrong and should be fixed before the feature ships.

Corollary: nothing user-editable may live as a literal in code. If a user can change
it, it lives in the store, seeded with sensible defaults on first run.

---

## 6. This codebase today

**Already right — preserve it.**

- `container.weather` is shared by Home and the Weather tile. One source, two views,
  different presentations. That is the target shape.
- `Tagger` and `PlaceRanker` are interface seams chosen once in `AppContainer`.

**Violations to fix, not copy.**

| Where | What is baked into code | Should be |
|---|---|---|
| `HeuristicTagger.FESTIVAL_WORDS` | festival vocabulary | a `TermSource` (§2) |
| `FeedCatalog` (`object`) | which feeds exist | a `sources` table + repository |
| `AppContainer.weatherLocations` | which places are tracked | a `places` table + repository |
| `RadarScreen.RADAR_TABS` | which tabs exist | saved filters in the store |
| `NEWS_TILE` / `WEATHER_TILE` | tile config | a tile registry (the aggregate Settings page needs one) |

None of these are urgent individually. Collectively they are why "add a place" is
currently impossible without a code change.

---

## 7. Checklists

**Adding a new kind of X** (source, tagger, ranker, provider):

1. Write it against the interface. Change nothing in consumers.
2. Bind it in `AppContainer` — one line.
3. Test it through the interface, not the class.
4. If you had to widen the interface, stop and reconsider: you probably need a
   second interface.

**Adding user-editable data** (a place, a feed, a term, a rule):

1. Table + DAO + repository returning a `Flow`.
2. Seed defaults on first run.
3. Settings writes through the repository.
4. Consumers observe the `Flow`. **Do not touch them.**
