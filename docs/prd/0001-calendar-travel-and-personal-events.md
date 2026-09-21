# PRD 0001 — Calendar, travel and personal events

Status: **draft for review**. Companion to `docs/adrs/0005-facet-catalog-and-typed-measures.md`.

## 1. The one sentence

A calendar that holds **both** the world's fixed dates (public holidays, festivals)
and **your** dates (flights, meetings, birthdays, trips), so that a plan can be
chosen against the dates that actually constrain it.

The point is not the list. It is the **answer to a scheduling question**: which
window gives me the most days off for the least leave, and does a local holiday
or festival fall usefully inside it.

## 2. Why now

Three pieces already exist, and they meet here:

- `calendar_dates` (schema v17): dated observances from ICS feeds, keyed
  `(source, feed_uid)`, regional, pruned per source.
- The ICS reader (`core/calendar`): pure, unfolds/unescapes, national vs
  regional from the feed's own UID.
- The source registry: a row of kind `calendar` is an instance; a new provider is
  an adapter. Regions are **user data**, never literals in code.

What is missing is a way to *choose a region* (so nothing can be synced today),
and any notion of a **plan** or a **personal event**.

## 3. Personas

A persona earns its place only if it changes what gets built. Each states the
trigger, the decision, what data it needs, and what the app must **not** do.

### P1 — The leave optimiser (salaried, home country + destination)

- **Trigger:** "I want a long break, and I get limited leave."
- **Decision:** which window to book.
- **Needs:** home-region holidays **and** destination-region holidays, a leave
  budget, a rough window.
- **Must not:** invent prices.
- **Drives:** the plan object + the leave optimiser (§6).

### P2 — The festival traveller

- **Trigger:** "I want to be there for X" (Durga Puja in Kolkata, cherry
  blossom in Japan).
- **Decision:** travel dates around a **fixed** destination date; home leave is
  the constraint.
- **Needs:** the festival's date, home holidays to bridge with.
- **Must not:** get a date wrong — a wrong date is worse than no date.
- **Drives:** plan with a must-include; distinguishes *fixed* vs *flexible* ends.

### P3 — The working professional

- **Trigger:** the workday.
- **Decision:** when to schedule, and what a recurring commitment collides with.
- **Needs:** **timed** events with timezones, **recurring** events (daily
  standup, weekly 1:1, monthly report, quarterly review, yearly appraisal),
  interviews (timed, one-off, cross-timezone), office holidays.
- **Must not:** silently move or drop an occurrence.
- **Drives:** timed events + recurrence (§7), and timezone handling.

### P4 — The personal-life keeper

- **Trigger:** an anniversary of something.
- **Decision:** remember it, and plan around it.
- **Needs:** **birthdays** (yearly), insurance/passport/vehicle renewals
  (yearly, sometimes every 2 years or 6 months), a leap-day birthday that exists
  only every 4th year.
- **Must not:** fire on 1 March for a 29 Feb birthday without saying so.
- **Drives:** custom intervals and the leap/month-end edge cases in §7.

### P5 — The booked traveller

- **Decision:** "is anything going to disrupt my trip?"
- **Needs:** travel-tagged news and closures near the booked dates.
- **Must not:** imply it monitors fares.
- **Drives:** Travel's News tab (already flowing via the `travel` tag).

### P6 — The holiday reference

- **Trigger:** "are the banks shut?"
- **Decision:** none — pure lookup.
- **Needs:** local regions only.
- **Must not:** demand effort.
- **Drives:** the region picker alone; no plan needed.

## 4. Scope

| Version | Contents |
|---|---|
| **v1** | Region picker (write a `calendar` source), holiday list for chosen regions. |
| **v2** | Travel tile: Calendar tab (upcoming by region, month-grouped) + News tab. |
| **v3** | Personal **plans** (§6) + leave optimiser. |
| **v4** | Personal **events**: timed, timezone, all-day; basic recurrence (§7). |
| **v5** | Custom intervals, leap/month-end semantics, per-occurrence exceptions. |
| **v6** | Reminders / notifications for events and plans. |

Deliberately **not** in v1–v2: importing a personal calendar, ICS export, timed
events. Each is independently useful and none blocks the others.

## 5. Requirements

### Regions

- R1 A user can add a region (`country` or `country/subdivision`) as a source of kind
  `calendar`; no region is hardcoded.
- R2 A region is verified before it is stored: the feed must parse to at least one
  dated row, so a typo fails at add time rather than silently producing nothing.
- R3 A region can be removed, and removal deletes that region's rows only.
- R4 Re-syncing a region **corrects** rows in place (feed UID is the identity) and
  prunes only rows that feed no longer lists.

### Holidays (existing behaviour, restated as requirements)

- R5 Holidays are read-only; a feed sync may never touch rows written by a user.
- R6 A holiday is never invented: a date is either from a feed or from the user.
- R7 National rows that appear inside a regional feed are labelled national, not
  regional (already true — read from the feed's UID).

### Personal events (v4+)

- R8 An event has a start, an optional end, an all-day flag, and a timezone.
- R9 An event is owned by the user: no feed sync may modify or delete it.
- R10 An event may recur (§7). Editing a recurring event offers "this occurrence"
  or "this and following", and never silently rewrites history.
- R11 An event carries free-text notes; a plan carries notes too (this is where
  "notes" land — no separate notes feature is required).

### Plans (v3+)

- R12 A plan is an **intent**, not a date: title, destination region(s), home
  region, a search window, a leave budget, optional must-include dates, notes.
- R13 A plan may exist before any date is chosen; it is not an event.
- R14 A plan's candidate windows are **derived**, never stored as events, until the
  user picks one — at which point it becomes an event (or a dated plan).

## 6. The leave optimiser

Pure, offline, deterministic — the differentiator, and it needs no external API.

```
input:  home region holidays, destination region holidays,
        search window, max leave days, optional must-include dates
output: ranked candidate windows, each with
        (days off, leave days required, holidays included, weekend days used)
```

Worked example (the motivating one):

> *Planning a trip to Japan in 2027.* Japan's Golden Week and an Indian holiday
> can both be used: a window is better when an Indian holiday bridges the
> departure and a Japanese holiday lands inside the stay, so fewer leave days
> buy more consecutive days off.

Rules it must honour:

- **Bridge day**: a single working day between two non-working days costs one
  leave day and buys three — count it, or the optimiser recommends worse windows.
- **Weekends are not leave.** Only working days cost budget.
- **A holiday is not a leave day**, in either region.
- **Determinism**: same inputs, same ranking. No randomness, no network.
- **Explainability**: every candidate states *why* it ranked where it did
  ("3 leave days for 9 off; includes Holi + Vernal Equinox Day"). A recommendation
  that cannot be explained will not be trusted.

## 7. Recurrence

The requirement is broader than "repeats". It must eventually express:

| Rule | Example |
|---|---|
| Daily, with interval | every 2 days |
| Weekly by weekday(s) | Mon/Wed/Fri standup |
| Monthly by day, with interval | 1st of every month; every 3 months |
| Monthly on month-end | the 31st ⇒ last day of shorter months |
| Yearly | birthday, anniversary |
| Yearly by interval | every 2 years (renewal), every 4 (leap) |
| Yearly on a leap day | **29 Feb** — see below |
| Exceptions | skip one occurrence without ending the series |

Edge cases that must be specified and tested, not discovered:

- **29 February**: in a non-leap year, fall back to 28 Feb (or 1 Mar) — the app
  must state which, and say so when it happens (P4's "must not").
- **31st of the month**: 31 Jan → 28/29 Feb → 31 Mar. Decide between "clamp to
  last day" and "skip", and keep it consistent.
- **Every-N-months across a year**: 31 Jan + 1 month ≠ 3 Mar.
- **DST and timezones**: a 09:00 meeting is 09:00 *local*, not 09:00 UTC.
- **Edit semantics**: "this occurrence" must not be expressed by mutating the
  series, or the next edit loses it.

**Scope honesty:** full RFC 5545 `RRULE` is a large surface (BYDAY/BYMONTHDAY/
BYSETPOS/UNTIL/COUNT and their interactions). v4 should implement a named
**subset** (daily/weekly/monthly/yearly with interval, weekday sets, month-end
and leap handling, UNTIL/COUNT) and reject rather than guess what it cannot
express. Widening the subset later is additive.

## 8. Peak windows, not prices

Live fares are **blocked** (no free live API: Amadeus gone, Kiwi invite-only,
Duffel test-only). Therefore:

- The calendar may state that a window is **peak** (festival + long weekend ⇒
  expensive) as an **inference from dates**, clearly labelled as an inference.
- It may **never** show a price, a saving, or a "cheapest time" claim it cannot
  source.
- Holidays on both ends of a trip are the strongest signal we legitimately have:
  demand spikes where everybody is off.

## 9. Non-goals

- Live flight or hotel prices (§8).
- Google Calendar **API** write-sync. Reading a personal Google calendar is done
  by the calendar's *secret iCal address*, which is just another ICS feed and
  needs no OAuth, consent screen or quota. Two-way API sync is a large surface
  (OAuth, scopes, sync tokens, conflict resolution) and is out of scope until
  there is a demonstrated need.
- Global search across the calendar.
- A separate notes feature; notes attach to plans and events.
- Bank-holiday scraping: RBI/other bank lists are PDFs, JS, and encrypted search;
  announced closures come through news, not scraping.

## 10. Open questions

1. **Where do destinations live** — `sources` rows of kind `calendar` (a
   destination is a region), a `places` table, or a plan-owned field? The plan
   needs a *region* to query holidays; the place you fly to is arguably a place.
2. **Does a picked plan become an event**, or stay a dated plan? (Affects
   whether plans and events are one table or two.)
3. **One table or two for calendar data**: holidays and personal events share
   "something on a date", but events need time, timezone and recurrence, and
   holidays must never be user-writable. Two owners argues for two tables.
4. **Recurrence subset** — exactly which rules ship in v4, and how a rejected
   rule is surfaced (must be loud, never a silent no-op).
5. **Cross-timezone display** — show in device TZ, event TZ, or both.
