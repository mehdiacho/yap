# Skill — Ratio Spec (design → one-shot-buildable handoff)

**Portable, platform-agnostic.** Copy this whole file into any project's instructions, or paste
the `PROMPT TO PASTE` block below to any model/tool, to get the same behavior: every UI design
ships with a companion **Ratio Spec** that lets a coding agent or engineer implement it
pixel-accurate — on the reference size *and* every other real device/window size — in one pass,
with no follow-up correction.

*This doc merges the operational how-to with the portable methodology authored by Claude Design
(the model that produced the original spec). Worked example: `designs/Yap - Android Ratio Spec.dc.html`,
the companion to `designs/Yap - Android Designs.dc.html` — reference it for tone and table shape,
but this file is the definition.*

## Why it exists
Handing a design off as screenshots/mockups forces whoever implements it to eyeball dimensions —
spacing never survives that translation precisely, and it breaks on other sizes. The Ratio Spec
removes the guesswork: every element gets an exact number, and that number is explicitly
categorized as **fixed** or **scaling**, so the implementation is right the first time everywhere.

## The one idea — classify every dimension into one of three kinds

| Kind | Examples | Rule in the spec |
|---|---|---|
| **(a) Density** | type size, corner radius, stroke/border width, icon glyph size, elevation/shadow, padding, gaps, non-circular fixed heights (rows, chips, bars) | **Fixed density-unit token** — never scaled by width. The platform's density unit already handles different-DPI screens. |
| **(b) Width-class** | screen edge margin, card width, primary/hero action button, avatar, FAB, hero art — things that *should* visibly track available width | **% of container/viewport width**, with `clamp(min, %, max)`. A short whitelist — most elements are NOT here. |
| **(c) Vertical position** | where each block sits down the screen | **Never** a top%/left%/height% coordinate table. An ordered flex/stack of fixed- or intrinsic-height blocks with **exactly one** flexible spacer absorbing the leftover. |

Two different problems get conflated as "pixel-perfect": density is already solved by the
platform's density unit; width-class is the real "different screen sizes" problem. Keep them
separate. A %-coordinate vertical table looks precise but shatters the instant the aspect ratio
changes (tall vs. short/wide device); a stack with one weighted spacer does not — that
distinction is the whole reason this format one-shots onto real layout code.

## Non-negotiable rules
- **Prefer fixed density units.** The §1.6 width-class list stays small and deliberate. When
  unsure, an element is density (fixed), not width-class.
- **Exactly one flexible spacer per screen region** (`Modifier.weight(1f)` / `flex: 1` /
  `Spacer()`). If a screen seems to need two, the block breakdown is wrong — rethink it.
- **No absolute-coordinate tables** for screen layout. Screens are ordered flex/stack layouts
  (Compose `Column`, SwiftUI `VStack`, CSS flexbox/grid, Flutter `Column`), full stop.
- **Reuse by reference.** Specify each shared component once in §2; screen recipes name it
  (e.g. "status chip §2.5"), never restate its dimensions inline.
- **Real copy, not lorem.** Write the exact strings so the implementer types them verbatim.

## When to produce one (lifecycle)
Any time a UI design is built or substantially revised, produce or update its companion Ratio
Spec **before the design work is considered done** — unless the request is an explicit throwaway
exploration. Regenerate it whenever the design changes materially. It sits next to the design it
documents. Name it `<Design Name> Ratio Spec` (`.dc.html`, `.layout-spec.md`, etc. — match the
tool). If a spec is ever missing for an existing design, **regenerate it from this methodology
rather than guessing** at what it said.

## Required output structure (mirror this section order every time)
- **§0 — How to read this spec.** Restate the (a) density / (b) width-class / (c) vertical split
  in the doc itself, and state the **baseline reference size** (device/window/viewport) every
  ratio is computed against.
- **§1 — Foundation tokens.**
  - §1.1 / §1.2 Color — one full table per theme/scheme (light, dark, brand modes): token name ·
    hex · where used.
  - §1.3 Type scale — each style: family · weight · size · line-height · letter-spacing · usage.
    Sizes are fixed units, never ratios.
  - §1.4 Spacing scale — named fixed-unit tokens (xs/sm/md/base/lg/xl) + values + usage.
  - §1.5 Corner-radius scale — named fixed-unit tokens (sm/md/lg/xl/full) + values + usage.
  - §1.6 **Width-class ratios** — THE adaptive table: one row per width-class element — name ·
    % of container width · note with the `@baseline` px and `clamp(min, %, max)`. State
    explicitly that everything not in this table is fixed.
- **§2 — Global / shared components**, each specified once with all interaction states: nav
  bars/rails/tab bars, primary action button, cards, status chips/badges, list rows, custom
  visualizers, app bars/headers, segmented controls, search fields, media/player bars,
  sheets/dialogs/modals, switches/sliders, FABs, snackbars/toasts, coach marks/tooltips, and the
  motion primitives (pulse/spin/etc.) — whatever the actual design system includes.
- **§3 — Screen recipes.** One **ordered block-list** per screen (per the flex-stack rule): each
  block one line — its name and how it's sized (own content · a §1 token · a §1.6 ratio) — with
  the single flexible spacer marked explicitly. Group state variants (empty/loading/error/
  selected/…) as a short **diff** against the base recipe, not a full re-spec.
- **§4 — Motion / dynamic quick-reference.** One table: effect · animated property · from→to ·
  duration/easing — covering every animation used anywhere.
- **Second form factor** (a desktop spec written after the mobile one, etc.): **don't restate
  shared tokens** — open with a note that §1 is inherited from the first spec, and cover only
  what differs structurally (window chrome, resizable panes, hover/focus states, OS surfaces
  like a tray or global overlay).

## Tone & format
Dense reference tables over prose. Every concrete number gets `monospace`/code-styled treatment
so it's unambiguous and greppable. Use two kinds of callouts, **sparingly**, exactly where a
reader could otherwise misapply a rule: one for a key methodology point, one for a
variant/exception worth flagging.

## Platform units (only the width-class + density unit names change)
| Platform / framework | Density unit (fixed) | Width-class unit (scales) |
|---|---|---|
| Android · Compose | `dp` / `sp` | % of screen width, `clamp()` |
| iOS · SwiftUI | `pt` | % of container width |
| Web · CSS | `px`/`rem` at a fixed base | % + breakpoints + a max content width |
| Desktop (resizable window) | `dp`/`px` | **window size-class breakpoints** — specify the layout at defined widths (e.g. `960` / `1280` / `1920`) and how panes flex between them (fixed list column vs. flexible detail pane), not a single % |
| Figma | points | % via constraints |

The vertical rule (flex stack, one weighted spacer per region) is identical on every platform.

---

# PROMPT TO PASTE

You are producing a UI design **and** its implementation spec. Alongside the visual design kit,
output a **Ratio-Based Layout Spec** — a resolution-independent, engineer-facing companion doc.
It is not a description of the mockup; it is the build instructions that let an engineer
replicate the design exactly on any screen size, in one pass, without measuring pixels off the
image.

Classify every dimension as **(a) density** → fixed density-unit token, never scaled; **(b)
width-class** → % of container width with `clamp(min,%,max)`, kept to a short whitelist; or **(c)
vertical layout** → an ordered flex/stack of fixed-height blocks with exactly one weighted
spacer, never a coordinate table. State a baseline reference size and compute all % against it.

Structure the spec as: **§0** how-to-read (restate the a/b/c split + baseline) · **§1** foundation
tokens (§1.1/§1.2 color per theme, §1.3 type, §1.4 spacing, §1.5 radii, §1.6 the width-class
ratio table) · **§2** shared components each specified once with all states · **§3** screen
recipes as ordered block-lists that reference §2 by name, variants as diffs · **§4** a motion
table. Dense tables over prose; every number in monospace; real copy, not lorem. For a second
form factor, inherit §1 and document only structural differences.

---

## How to use it (for me, mehdiacho)
1. Claude Design already carries this as project instructions (its `CLAUDEDESIGN.md` trigger +
   `Ratio-Based Layout Spec — Methodology.md`), so it auto-produces a spec with each design.
2. For any other model/tool, paste the `PROMPT TO PASTE` block ahead of the design request.
3. You get the design kit **and** its Ratio Spec → drop both in `designs/` → hand to Claude Code,
   which builds one-shot.
4. Claude Code can also use this file to **generate a spec from a design that arrived without
   one**, or **audit** that a design/spec pair is consistent before a build.
