# Design templates

Reusable design methodology — framework-agnostic (Compose, SwiftUI, Flutter, CSS all covered by
the same idea). Use when producing UI designs or handing them off so they build pixel-accurately
in one pass instead of being eyeballed.

## Files
- **`RATIO_SPEC_SKILL.md`** — the **Ratio Spec** skill. Every UI design ships with a companion
  spec that classifies each dimension as **density (fixed dp/pt/rem)**, **width-class (% of
  viewport with clamp)**, or **vertical position (one flex spacer, never a coordinate table)**.
  That classification is what lets a coding agent implement a screen correct on the reference size
  *and* every other device/window in one shot. The file is self-contained and portable — paste it
  into a project's instructions, or hand the `PROMPT TO PASTE` block to any design model.

## How to use
- **Producing designs** (e.g. in Claude Design): ask for the design **plus its Ratio Spec
  companion** using the skill's prompt block. You get a designs file + a spec file (see this
  project's `designs/Yap - Android Designs.dc.html` + `designs/Yap - Android Ratio Spec.dc.html`
  for a worked pair).
- **Building from designs**: point the coding agent at the Ratio Spec — it reads the fixed vs.
  scaling classification per element and lays screens out as ordered flex/stack with one weighted
  spacer. No `%`-coordinate tables.

## Reusable rules of thumb (from the skill)
- When unsure whether something scales, it's **density (fixed)**, not width-class. Keep the
  width-class whitelist small (edge margins, card/hero width, primary button, avatar, FAB, hero art).
- **Exactly one flexible spacer per screen region.** Needing two means the block breakdown is wrong.
- **No absolute-coordinate layout tables** — they shatter the moment the aspect ratio changes.
