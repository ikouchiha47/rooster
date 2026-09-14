# Design Guidelines

Applies to the Personal Radar Android app (`android/`) and the reference mockups (`design/`).
Source of truth for visuals: `design/style.css` and `design/app.html`.

---

## 1. UX style: dense, not minimal

This is an **information-dense editorial** product, not a whitespace-heavy consumer app. Think Yahoo! Japan / a Nikkei front page, not a US SaaS dashboard.

Rules:
- **Many modules visible at once.** Prefer showing 6-10 information blocks per screen viewport over one hero element.
- **Hierarchy comes from rules, weight, size and colour — never from empty space.**
- Tight gutters: **8dp** between modules, 4dp intra-row padding. Do not "breathe" layouts by adding whitespace.
- **1px hard rules** separate rows/sections. Soft hairlines for secondary separations. Dotted rules for metadata rows.
- **3dp category spine** on event/rule rows; colour-coded by category.
- Corner radius **2-4dp**. Never pills/stadium shapes.
- Body type is small: **12-13sp body**, 14sp titles, 9-10sp letter-spaced caps labels.
- Numbers use **tabular figures** so columns align like a financial page.

Explicitly avoid: hero sections, large empty margins, centred marketing copy, gradient/glass cards, floating shadows, "breathing room" as a design goal.

---

## 2. Colour

Light, warm newsprint. Flat and matte. No gradients, no glow, no blur.

**Surfaces**
| Token | Hex | Use |
|---|---|---|
| paper2 | `#FAF8F2` | page base |
| paper | `#F7F4EC` | alternate page |
| rail | `#EFEADB` | header/filter bands |
| fill | `#E4DCC7` | pressed/inactive |
| fresh | `#FFFFF4` | unread row tint |

**Ink**
| Token | Hex | Use |
|---|---|---|
| ink | `#171513` | primary text + all hard rules |
| ink2 | `#4A443C` | secondary text |
| ink3 | `#837B70` | metadata |
| ruleSoft | `#CFC6B3` | hairlines |
| paper4 | `#CFC6B3` | muted-on-ink text |

**Category accents** (chip fill + row spine; keep them flat)
Vermilion `#D93B2B` · Indigo `#2B4C8C` · Mustard `#E0A800` · Teal `#1F8A70` · Chartreuse `#A8C020` · Periwinkle `#6C7BD9` · Plum `#7A2E5E` · Cyan `#1B7A8C` · Rust `#B4532A`

Accent semantics: Vermilion = alerts/emphasis, Indigo = news, Mustard = travel/fares, Teal = money, Chartreuse = weather/odd, Periwinkle = social, Plum = tech, Cyan = radio.

Implementation: `ui/theme/Theme.kt` (`PersonalOSColors`) and `ui/common/CommonComponents.kt` (`RadarColors`, `CategoryColors`).

---

## 3. Typography

| Role | Style | Notes |
|---|---|---|
| App title / section headers | **serif** (mincho-like) | `FontFamily.Serif`; carries the print/newspaper voice |
| Event titles | serif, 14sp | |
| Body | sans, 12-13sp | |
| Time / figures | **monospace**, tabular | time gutter, amounts, deltas |
| Labels / metadata | sans, 9-10sp, **letter-spaced caps** | `letterSpacing ~0.5sp`, uppercase |

Ramp lives in `ui/theme/RadarType.kt` (serif heads/titles, sans body, mono figures, caps labels).

---

## 4. Characters and punctuation (ENFORCED)

**Never hand-type stylised punctuation into code or UI strings.** A hand-typed em dash, arrow, smart quote or bullet is indistinguishable in review, encodes inconsistently across editors, and breaks under re-encoding.

All such characters live as **Unicode escapes in one constants file**:

`android/app/src/main/kotlin/com/personalos/app/core/Chars.kt`

Use them:
```kotlin
Text("BLR${Chars.ARROW_RIGHT}HND ${Chars.MIDDLE_DOT} 14-22 Oct")
Text("${Chars.DEGREE}31")
```

Rules:
- Em dash, en dash, arrows, middle dot, bullet, ellipsis, degree, rupee, multiplication sign must come from `Chars`.
- **In UI copy, prefer plain words over decorative punctuation.** Write "to" instead of an arrow where it reads naturally, and use a comma or a slash instead of an em dash.
- Straight ASCII quotes `"` `'` in code. Do **not** use curly quotes in source.
- No emoji, anywhere in the UI or in source strings.

Why: consistency, greppability, and so a reviewer can actually tell a real dash from a mojibake artefact.

---

## 5. Icons

- Use the drawn, flat glyph set in `ui/common/Glyphs.kt` (24x24 grid, ~1.7 stroke, outline style).
- Material icons are acceptable where a glyph does not exist; reference them as `Icons.Filled.X` (never alias an icon extension property to a bare name - it will not compile).
- **No emoji. No letter tiles as icons.** A tile shows a glyph plus a 9sp caps label.

---

## 6. Sizing tokens

| Token | Value |
|---|---|
| Gutter | 8dp |
| Intra-row padding | 4dp |
| Corner radius | 2dp (small), 4dp (medium) |
| Service tile | 42dp square, glyph 18-19dp, label 9sp caps |
| Category spine | 3dp |
| Bottom nav | 48-56dp, indicator 3dp, icon 19dp, label 9sp |
| App bar rule | 3dp ink base |

---

## 7. Placeholders

A screen that is not built yet renders as a **blank placeholder**: correct app bar with the service name, empty body, no filler copy ("coming soon" is banned).
