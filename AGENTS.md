# Personal Radar — agent brief

Personal-use, sideloaded Android app. Zero recurring cost. On-device, offline-first.
Kotlin + Compose + Room, package `com.personalos.app`, project root `android/`.

## Read before writing code

- **`docs/CODE-DESIGN-GUIDELINES.md` — non-negotiable. Read it first.**
- `docs/ARCHITECTURE.md` — the data model, tiles, tagging, store schema.
- `docs/DESIGN-GUIDELINES.md` — UI language (dense, not minimal).
- `docs/KOTLIN-BEST-PRACTICES.md` — style.

## The rules that get violated most

1. **One fact, one owner.** Every fact has exactly one source of truth. Home, a
   service tile, and Settings are *views over the same source* — never each their
   own copy, their own fetch, or their own cached list.
2. **Layers are strictly one-way.** `source -> repository -> domain model -> UI model`.
   The data layer knows nothing about layout. The UI knows nothing about transport.
3. **Caps, limits, ordering-for-display and formatting belong to the UI.** The data
   layer returns the full set; a component decides to show 3 or 20 of them.
4. **Extend, don't modify (Open/Closed).** New behaviour is a new implementation or
   new data — not a new branch, keyword, or const inside an existing class.
5. **Implementations are substitutable (Liskov).** If `Tagger` promises a result for
   every input, every implementation must honour it exactly. A subtype may not
   narrow, throw, or silently no-op.
6. **Interfaces are small and role-specific (ISP).** One reason to change per
   interface. Do not build a god-interface to avoid adding a second one.
7. **Settings is a writer, not a special case.** Settings writes to the single
   store; everything downstream updates by itself. If adding a place requires
   touching a screen, the model is wrong.

## Before you finish

```bash
cd android && ./gradlew :app:ktlintFormat
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest ktlintCheck
```

All three must pass. If you cannot verify a claim, say so rather than asserting it.

## Verify claims about device state with data, not guesswork

- Real element bounds: `adb -s 0006934AH000333 shell uiautomator dump /sdcard/ui.xml`
- The DB is WAL-mode: pull `personalos.db` **and** `-wal`, or recent rows are missing.
- Logs are cheap and settle arguments: `adb logcat -d -s <Tag>`.

## Commits are short; the note goes in CHANGELOG.md

Subjects stay to one line. The reasoning, the trade-off and the verification go
after an HTML-comment marker, and a hook moves them into `CHANGELOG.md`:

```
feat(tag): games vocabulary as data

<!-- changelog -->
Adds a `games` tag, and moves the vocabulary out of the tagger into a TermSource.
Sources are merged and alternatives are unioned per tag, so a user-supplied
vocabulary extends the bundled one rather than replacing it.

Verified on device: v7 active, festival 28, games 21.
```

The hook strips everything from the marker on, so the commit holds the subject
only, and prepends the note to `CHANGELOG.md` in the same commit. A message with
no marker is left alone.

Enable it once per clone — the hook lives in the repo, but git does not read it
until you point it there:

```bash
git config core.hooksPath .githooks
```

