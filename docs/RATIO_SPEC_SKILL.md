# Skill — Ratio Spec (design → one-shot-buildable handoff)

**What this is.** A reusable instruction set for **Claude Design**. Paste the "PROMPT TO
CLAUDE DESIGN" block (below the divider) at the start of any design request. Claude Design then
produces **two** artifacts every time: the visual design kit, *and* a companion **Ratio Spec**
that lets Claude Code implement it pixel-perfect in one shot, with no eyeballing.

**Why it works.** Mockups are perfect but their pixel values are tied to one canvas size.
Handing raw px to an engineer produces spacing that's "close" but never 1:1, and it breaks on
other screen sizes. The Ratio Spec removes the guesswork by classifying *every* dimension into
one of three kinds and giving the engineer the rule for each kind — not a screenshot to measure.

**The one idea to internalize:** three different problems hide inside "make it pixel-perfect,"
and they need three different answers.

| Kind | Examples | Rule in the spec |
|---|---|---|
| **(a) Density** | type size, corner radius, stroke width, icon glyph size, elevation, internal padding, gaps | **Fixed `dp`/`sp`** — never scaled by screen width. The platform's density units already handle different-DPI screens. |
| **(b) Width-class** | screen edge margin, hero/capture button, avatar, FAB — things that *should* track how wide the screen is | **% of screen width**, with `clamp(min, %, max)`. A whitelist — most elements are NOT here. |
| **(c) Vertical position** | where each block sits down the screen | **Never** a top%/height% coordinate table. A single flex column of fixed-height blocks with **exactly one** `weight(1f)` spacer that absorbs the leftover space. |

A top%/height% table looks precise but shatters the moment the aspect ratio changes (tall vs.
short phone). A flex-column with one weighted spacer holds on every device. That distinction is
the whole reason this format one-shots.

---

# PROMPT TO CLAUDE DESIGN

You are producing a UI design **and** its implementation spec. Alongside the visual design kit,
output a **Ratio Spec** — a resolution-independent, engineer-facing companion document. It is not
a description of the mockup; it is the build instructions that let an engineer replicate the
design exactly on any screen size, in one pass, without measuring pixels off the image.

## Core method — classify every dimension, then specify by class

For every element, decide which of three kinds it is and specify accordingly:

- **(a) Density** — type size, corner radius, stroke/border width, icon glyph size, elevation,
  padding, gaps, non-circular fixed heights (row height, chip height, bar height). → give a
  **fixed dp/sp** value. Do **not** express these as a % of anything. These are already
  resolution-independent because dp/sp are.
- **(b) Width-class** — only elements that should visibly grow/shrink with screen width: screen
  edge margin, hero buttons, avatars, FABs, anything full-bleed-ish. → give a **% of screen
  width** plus a `clamp(minDp, %, maxDp)`. Keep this list short; when unsure, an element is
  density (fixed dp), not width-class.
- **(c) Vertical layout** — never a coordinate table. Describe each screen as an **ordered
  top-to-bottom stack of blocks**, each block sized by its own content or a dp/§width token, and
  call out the **single** flexible gap (`weight(1f)`) that centers/absorbs the remainder.

State a **baseline reference device** (e.g. 360×780 dp for phones) and compute all % against it.
Because they're ratios, they hold at any width.

## Required output structure (follow these section numbers)

**§0 · How to read this spec.** A short note restating the (a) density / (b) width-class /
(c) vertical separation, and the baseline device. (Copy the intent of the table above.)

**§1 · Foundation tokens.**
- §1.1 Color — light scheme: every token as a name + hex + where it's used.
- §1.2 Color — dark scheme: the same tokens, dark values.
- §1.3 Type scale — each style: family, weight, size (sp), line-height, letter-spacing, usage.
  All sizes are fixed sp, never scaled.
- §1.4 Spacing scale — named dp tokens (xs/sm/md/base/lg/xl) with values and usage.
- §1.5 Corner-radius scale — named dp tokens (sm/md/lg/xl/full) with values and usage.
- §1.6 **Width-class ratios** — THE table that adapts per device. One row per width-class
  element: element name · % of screen width · note with the `@baseline` px and the
  `clamp(min, %, max)`. Explicitly state that everything not in this table is fixed dp.

**§2 · Global components** — every reusable component (nav bar, buttons, cards, chips, inputs,
players, sheets, dialogs, switches, sliders, snackbars) specified once as a property table, so
screen recipes can reference them by name (e.g. "status chip §2.5") instead of re-describing.
Include all interaction states.

**§3 · Screen recipes** — one entry per screen. Each is an **ordered block list** (a fenced/
boxed list), every block one line: its name and how it's sized (content, a dp/§token, or a §1.6
ratio). Mark the **one** `weight(1f)` flexible spacer explicitly. Note variants (empty, error,
loading, selected, etc.) as deltas against the base recipe, not full re-specs.

**§4 · Motion** — a quick-reference table of every animation: name · property · from→to ·
duration/easing.

## Rules

- Reuse tokens by reference; never restate a component spec inside a screen recipe.
- Prefer fixed dp/sp. The §1.6 width-class list should be small and deliberate.
- Exactly one `weight(1f)` per screen recipe. If a screen seems to need two, rethink the blocks.
- Copy is design material: write the real strings, not lorem, so the engineer types them verbatim.
- Deliver the spec as its own document (same format as the design kit), titled
  "<Product> — <Platform> Ratio Spec," cross-referencing the design kit by file name.

## Platform adaptation

The method is universal; only unit (b) changes per platform:
- **Phone / tablet (Android/Compose):** width-class = **% of screen width**; density = **dp/sp**.
- **Desktop (window-resizable):** width-class becomes **window-size-class breakpoints** — specify
  the layout at defined widths (e.g. 960 / 1280 / 1920) and how the panes flex between them (fixed
  list column vs. flexible detail pane), not a single %. Density stays fixed dp/sp. Vertical rule
  is unchanged (flex column, one weighted spacer per region).
- **Web:** same as desktop, plus a stated max content width and the reflow point to the mobile stack.

---

## How to use it (for me, mehdiacho)

1. Start a Claude Design session for the new surface.
2. Paste the **PROMPT TO CLAUDE DESIGN** block above ahead of your design request.
3. You get back the design kit **and** its Ratio Spec.
4. Drop both files into the repo (`designs/`) and hand them to Claude Code — it builds one-shot.

The working proof of this format is `designs/Yap - Android Ratio Spec.dc.html` (the companion to
`designs/Yap - Android Designs.dc.html`). Use it as the reference for tone, density, and the exact
shape of the §1.6 table and §3 recipes.
