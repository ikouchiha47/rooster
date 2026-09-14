# Kotlin and Android Best Practices

Conventions and tooling for the Personal Radar Android app. Companion to `docs/DESIGN-GUIDELINES.md`.

---

## 1. Tooling (linter + static analysis)

Wire both into the build so checks are repeatable:

- **ktlint** - formatting and import order. Gradle plugin `org.jlleitschuh.gradle.ktlint`.
- **detekt** - static analysis (complexity, smells, coroutine misuse). Gradle plugin `io.gitlab.arturbosch.detekt`.
- **Android Lint** - already available via `./gradlew :app:lintDebug`.

Commands (run from `android/`):
```
./gradlew ktlintCheck detekt lintDebug
./gradlew ktlintFormat        # auto-fix formatting
```

CI/verify gate: build + `ktlintCheck` + `detekt`. Do not merge with a failing check.

---

## 2. Kotlin style

- Official Kotlin coding conventions; **4-space indent**; trailing commas in multi-line argument lists.
- **No wildcard imports.**
- Prefer `val`; make immutability the default.
- Explicit types on public APIs; infer locally.
- `data class` for value/DTO types; `sealed interface` for closed hierarchies (states, row models).
- Avoid `!!`. Model absence with nullable types or a sealed state.
- Keep functions small and pure where possible - pure logic is unit-testable.

## 3. Package layout

```
com.personalos.app
  core/            # cross-cutting: Chars (Unicode), Result types, clock
  data/            # Event model, Room entities/DAO/DB, sources (SmsSource...)
  ui/common/       # design system: rules, chips, glyphs, app bar, tiles
  ui/theme/        # colours, type ramp, shapes
  ui/<feature>/    # one package per screen
```

## 4. Compose

- **State hoisting**, and read state as low in the tree as possible. If only one widget needs a value, collect it *inside that widget* so recomposition is scoped to it - do not read it at screen level and pass it down, or every sibling recomposes on every change.
- **Lists must be `LazyColumn`/`LazyRow`.** Never render an unbounded collection inside `verticalScroll`.
- Always supply a **stable `key`** in `items(...)`.
- **No heavy work in composition.** No JSON parsing, no `SimpleDateFormat` construction, no full-table scans during composition. Hoist formatters to file-level `val`s and format lazily per item with `remember(key)`.
- Use `derivedStateOf` for values computed from other state, and `remember(key)` for expensive derivations.
- Don't wrap content in a bare `MaterialTheme { }` inside a screen - it resets the app theme. The theme is applied once in `MainActivity`.
- Fixed chrome (app bar, tab strip, filter rail) goes **outside** the scrolling container.

## 5. Coroutines

- Structured concurrency only: `viewModelScope` / a scoped `CoroutineScope`. **No `GlobalScope`.**
- Blocking IO (`contentResolver`, network, DB) on `Dispatchers.IO`; UI on `Dispatchers.Main`.
- Database work should be `suspend` and called from a coroutine, never on the main thread.

## 6. Room and data

- **Never load a whole table to compute a number.** Use aggregate queries (`COUNT(*)`, `COUNT(*) WHERE ...`, `COUNT(DISTINCT col)`).
- **Keyset (cursor) pagination, not `LIMIT ... OFFSET`.** Page with `WHERE timestamp < :cursor ORDER BY timestamp DESC LIMIT :n`; offset pagination degrades and skips/duplicates rows under writes.
- Expose reactive reads as `Flow`, collected with `collectAsStateWithLifecycle`.
- Index the columns you sort/filter on. Deduplicate on a stable natural key with a unique index + `OnConflictStrategy.IGNORE`.
- Make ingestion **incremental**: track a high-water mark (e.g. last SMS `_id`) and fetch only new rows.

## 7. Testing

- Unit-test pure logic (classifiers, parsers, mappers) with JUnit.
- Android instrumented tests only where behaviour genuinely needs a device.
- Test through public behaviour, not implementation details - avoid assertions that restate the code.

## 8. Resources and strings

- No emoji in source or UI.
- No hand-typed stylised punctuation - use `core/Chars.kt` (see design guidelines section 4).
- User-visible strings should eventually move to `res/values/strings.xml`; hardcoded strings are acceptable only in throwaway/mockup-stage screens.
