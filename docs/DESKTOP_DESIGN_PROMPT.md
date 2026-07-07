# Claude Design prompt — Yap Desktop (Windows-first)

Copy everything below the line into Claude Design. Attach these two files from the repo —
they are the source of truth for tokens and existing screens:
- `designs/Yap - Android Designs.dc.html`
- `designs/Yap - Android Ratio Spec.dc.html`

---

I'm extending **Yap** — my Android voice idea-capture app — to **desktop**, Windows first
(the same code will later build for macOS/Linux, so keep chrome OS-agnostic: no Windows-only
visual metaphors like Mica/acrylic as a requirement, though a subtle version is fine as flavor).

The two attached documents are the existing **phone design kit** (all 54 frames) and the
**Ratio Spec** (the token sheet: §1.1/§1.2 colors, §1.3 type scale, §1.4 spacing, §1.5 radii,
§2 component specs). The desktop kit must reuse those tokens exactly — same palette
(primary #5B5BD6, surfaces #FCFBFF/#F2EFF7, recording coral #FF6B6B, the status-chip system),
same Outfit + Roboto Flex type pairing, same corner-radius scale, same status chips, cards, and
segmented controls. Desktop is the same product at a different size, not a re-skin.

## What the app is (one paragraph of context)

One-tap voice capture → instant on-device draft → cloud "polish" into a clean, organized
Markdown note (title, category, topic cluster). Notes live in a Library (search, filters,
pins, trash), each note has an audio player, Smart Review (AI flags possible mis-hears with
Keep/Fix), "Polish now" for unrefined drafts, and **append-to-note** (re-open a note and record
an addition — e.g. speak new items onto an existing shopping list; the AI integrates rather
than rewrites). Monetization: Free/Pro plans, usage meters, BYOK. All of that already exists
on the phone.

## The desktop mental model

Desktop Yap is a **companion work surface**, not a phone stretched wide. On the phone you
*capture*; on the desktop you *capture at your desk* (meetings, calls, thinking out loud while
working) and you *work the library* (review, edit, organize, export). Design for keyboard +
mouse + always-visible chrome.

## Window & layout rules

- Baseline window **1280×800**, minimum **960×600**, design must also make sense maximized
  at 1920×1080. Resizable; describe what flexes (list column fixed-ish width, detail pane
  takes the remainder).
- **Left navigation rail** (72–80dp, icons + labels) replaces the phone's bottom nav:
  Capture · Library · Settings, with the account avatar pinned at the rail's bottom.
- **Library is a two-pane list-detail**: note list (~360dp column: search field, filter chips,
  section eyebrows, session cards) on the left, the opened note filling the right pane.
  Selecting a note never navigates away from the list.
- **Capture** can be a dedicated surface (rail item) AND a persistent affordance: a record
  button always visible in the window's top bar or rail so capture is one click from anywhere.
- Dialogs replace the phone's bottom sheets (quota-hit, purchase, sync status, permission
  rationale become centered dialogs at radius-xl 28dp).
- Include **hover states** (cards, rows, chips), **context menus** (right-click a note card:
  Open, Pin, Add a recording, Export as .md, Move to trash), and visible **keyboard focus**.
- Density: desktop rows can be slightly tighter than phone (list rows ~64–72dp), body text
  stays 14–15sp equivalents; don't shrink type below the phone scale.

## Screens/frames I need (light mode for all; dark variants for the ★ ones)

1. ★ **Main window — Capture idle**: rail + a generous capture surface (greeting, big capture
   button, last-note card, import-file pill, drag-and-drop hint "Drop audio here to transcribe").
2. ★ **Recording**: timer, waveform, live-draft panel, pause/stop/bookmark transport, and the
   "Adding to «note title»" chip variant for append-to-note. Include the hold-stop-to-discard
   affordance (a discard bubble appears above the stop button).
3. ★ **Library — two-pane populated**: list column (search, chips, eyebrows, cards with status
   chips/pin/availability glyphs) + opened note on the right (title, chips, Formatted/Raw
   segmented control, Markdown body incl. a table, audio player docked at the pane's bottom
   with bookmark markers on the scrubber).
4. **Library states**: empty, search-with-highlights, no-results, multi-select (checkbox
   badges + a contextual action bar: Group, Formalize, Pin, Delete).
5. **Note — draft-only** with the "Not refined yet · Polish now" banner.
6. **Note — Smart Review**: reviewing chip, flagged amber block with Keep/Fix, summary bar.
7. **Note — append-to-note entry**: where "Add a recording" lives in the note header/toolbar,
   and a small inline state showing the note is being updated after an append.
8. **Settings**: rail + settings content using the phone's grouped-row system (account panel,
   GENERAL/PRIVACY/ABOUT groups). Include the Appearance theme picker (System/Light/Dark) and
   the About page's 7-tap Advanced unlock.
9. **Subscription/plans** as a settings sub-page (usage meters + the three plan cards).
10. **Quota-hit dialog** (hourglass, "Cloud polish paused", Upgrade to Pro / Use my own key).
11. **System tray flyout** (small: mic button "Start recording", last note line, "Open Yap") —
    plus the tray icon's recording state (coral dot).
12. **Global capture overlay**: a compact always-on-top mini window (like a music mini-player)
    shown when recording starts via the global hotkey while Yap is minimized — timer, waveform
    sliver, stop/pause. This is the desktop cousin of the phone's floating bubble.
13. **Onboarding — desktop**: a single welcome window (icon mark, wordmark, "Get started" /
    "I already have an account") and one "set up shortcuts" step (global hotkey, launch at
    startup, tray behavior). Desktop onboarding should be 2–3 steps, not the phone's 7.

## Keyboard shortcuts (show them in the designs where natural — menus, tooltips)

- `Ctrl+R` start/stop recording (global hotkey when enabled: `Ctrl+Alt+Y`)
- `Ctrl+F` focus search · `Ctrl+N` new recording · `Ctrl+P` pin · `Del` move to trash
- `Ctrl+,` settings · `Esc` closes dialogs/deselects

## Deliverable format

Same format as the existing kit: a single HTML document of desktop frames
(`Yap - Desktop Designs.dc.html`) at a consistent frame size (use 1280×800 device frames),
each frame labelled `data-screen-label="Desktop · <name>"`, followed by a **Desktop Ratio
Spec** section in the style of the Android one: which measurements are fixed dp, which scale
with window width, how the two-pane split behaves across 960→1920 widths, and the block
recipe (rail / list / detail) per screen. Reuse §1 token tables by reference — only document
what desktop *changes* (rail specs, pane widths, row heights, hover/focus states, dialog
specs, tray/mini-window specs).

Do not invent new colors or type styles. Where a phone component exists (status chip, session
card, segmented control, search field, player), reuse its spec and only adjust for pointer
ergonomics (hover, min click targets 32dp).
