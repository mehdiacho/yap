# Claude Design Brief — Yap
### Native Android voice idea-capture app · Material 3 · for Claude Design → AI Studio

> **Paste this whole file into Claude Design.** It defines every screen, every state, the visual language, and the icon set to produce. Full product/engineering detail lives in `PRODUCT_SPEC.md`; this brief is the design-focused slice and is self-contained for v1.
> **Deliverable:** high-fidelity Android mockups for **every screen in every state listed**, in **both light and dark**, plus the **app icon set** and a **component library**. Design for **Jetpack Compose + Material 3 (Material You)** so AI Studio can execute directly.

---

## 1. What the app is (context for tone)

A voice-first app for capturing ideas the instant they strike — you tap, ramble, and it transcribes and organizes automatically, online or offline. The feeling: **calm, fast, frictionless, trustworthy.** It's an everyday tool people reach for one-handed, often in motion. Not techy, not playful-childish — quietly premium.

**Name:** **Yap** (working name). Design the wordmark + icon around it (motif in §8). ⚠️ Note: a separate "Yap! — Voice Notes & Widget" exists on Play, so the *public* name may be finalized to a differentiated one-word variant (e.g. **YapDrop / YapBook**) before launch — so make the icon/wordmark **flexible enough to survive a one-word name swap**, and provide 2–3 logo-lockup variants.

---

## 2. Visual language (Material 3)

- **System:** Material 3 / Material You. Dynamic color on Android 12+; the seed below is the brand fallback + anchor.
- **Seed color:** **deep indigo/violet `#5B5BD6`** (calm, thoughtful). Derive full M3 tonal palette from it.
- **Recording accent:** **warm coral `#FF6B6B`** — used ONLY for the active-recording state (button, waveform, timer, rec notification) so "recording" is instantly legible. Nothing else uses coral.
- **Modes:** Light + Dark, both first-class, designed in parallel. Mock every screen in both.
- **Type:** M3 type scale; humanist sans (Roboto Flex / system) for body; optional distinct display face for the wordmark only. Large, legible, generous line height.
- **Shape:** M3 rounded — large/extra-large corners on cards, sheets, the record button. Friendly, soft.
- **Elevation:** subtle; rely on tonal surfaces over heavy shadows.
- **Motion:** calm and quick. Recording = a gentle pulsing waveform, not frantic. Standard M3 transitions between screens; shared-element on card → detail.
- **Density:** comfortable, thumb-reachable. Primary actions in the bottom half of the screen.
- **Accessibility (must hold in mockups):** WCAG AA contrast in both themes; touch targets ≥48dp; clear focus/selected states; designs must survive 130% font scaling.

**Signature element — the Capture Button:** one large, unmistakable circular primary action. On the Capture tab it's center-stage and large; elsewhere it's a FAB. Three visual states: **idle** (indigo, mic icon), **recording** (coral, pulsing ring + stop icon), **paused** (coral outline, resume icon). Pressing implies haptic + optional sound (show a subtle ripple).

---

## 3. Navigation & information architecture

**Bottom navigation — 3 destinations:**
1. **Capture** (default landing) — the record screen.
2. **Library** — all notes: search, filters, clusters, collections.
3. **Settings.**

**Pushed full-screen (not tabs):** Session Detail, Account/Auth screens, Subscription, the Sync-queue sheet, onboarding.

**External entry points** (design their surfaces too — §5.8): home-screen **Widget**, **Quick Settings tile**, **app shortcuts**, **share-sheet** appearance. All deep-link into the Recording state.

---

## 4. Global components to define as a library

- **Bottom nav bar** (3 items, selected/unselected).
- **Capture Button** (idle/recording/paused) + **live waveform** + **timer** + **live-draft text** block.
- **Session card** (Library): title, **category pill**, **cluster pill**, snippet, **tag chips**, date, audio indicator, **status chip** (Draft / Polishing / Refined), pin marker, selected state.
- **Status chip** component: Draft (grey), Polishing (indigo, animated), Refined (green/positive), Offline-pending (amber).
- **Sync-queue indicator** (app-bar badge) + the **queue bottom sheet** (per-item status + retry).
- **Filter chip row** + **search field** (idle/active/results/no-results).
- **Bottom sheets:** actions menu, export, filters, picker.
- **Segmented button** (Formatted / Raw).
- **Block** (transcript paragraph) with: hover/selected, inline-edit affordance, **version stepper** (‹ 2/3 ›), **semantic-warning banner** (amber).
- **Audio player bar** (play/pause, scrubber, speed, tap-line-to-seek, download).
- **Snackbar w/ Undo.**
- **Coach-mark / tooltip** (the onboarding moment component) + **empty-state** template.
- **Permission rationale sheet** template.
- **Plan card** (Subscription) + **usage meter** (minutes + storage).
- **Slider** (auto-stop seconds) + **switch row** (settings toggles).
- **Recording foreground notification** + **refinement-complete notification** (design both).

---

## 5. SCREENS — design every state listed

> For each screen, produce a mockup per state, in light + dark. States use these labels: `empty · loading · populated · active · error · offline · permission-needed · permission-denied · quota-hit · editing · selected/multi-select`.

### 5.1 First-run / onboarding
Steps (full-screen, swipeable where sensible):
1. **Welcome** — wordmark, one-line value prop ("Capture any idea, the instant it strikes."), "Get started" + "I already have an account."
2. **Instant first note** — "Tap to capture your first idea." Shows the Capture Button; triggers **mic permission rationale** here. After a 5s record, show the **live draft appearing** then a **saved** confirmation. *(States: default, permission-needed, permission-denied, recording, saved.)*
3. **Account** — Google (primary), email/password, **Continue as guest**. *(States: default, loading, error.)*
4. **How it works** — 3 quick cards: "Drafts instantly on your phone → Polishes when online → Auto-organized." 
5. **Your AI key** — choose "Use Yap's AI (Free)" vs "Use my own key" (BYOK, gated — show locked state). 
6. **Suggested setup** — opt-in prompts to add the **Widget** and **Quick Settings tile** (with mini illustrations).

### 5.2 Capture tab (THE core screen)
Layout: minimal top bar (sync badge, profile), large Capture Button low-center, live area above it.
States:
- **Idle / armed** — big "Tap to record," subtle hint, optional last-note peek. If a recovered recording exists: top banner "Resume recovered recording?"
- **Recording** — coral pulsing **waveform**, running **timer**, **live draft text** streaming below, **Pause** + **Stop**. If auto-stop armed: a small silence-countdown indicator.
- **Paused** — frozen waveform, "Paused," Resume + Stop.
- **Processing** — "Transcribing on device…" → "Polishing…" (online) or "Saved — will polish when online" (offline). Dismissible (continues in background).
- **Offline** — full recording works; banner "Offline — draft saved, will refine when connected."
- **permission-needed / permission-denied** — rationale sheet / friendly "enable in settings."
- **quota-hit** (managed, out of cloud minutes) — recording + draft still work; sheet: "Cloud polish paused — out of minutes" → Upgrade / Use your own key.
- **error** — mic/hardware error, retry, buffered audio safe.

### 5.3 Session Detail (auto-opens after a recording; also from Library)
Top: back, **inline-editable title**, status chip, overflow (export, share, delete, AI actions[v2]).
Body: **Formatted | Raw** segmented control; rendered Markdown (headers, **tables**, lists, blockquotes, dialogue); **audio player bar** with tap-line-to-seek; metadata row (category, cluster, editable tags, date).
Block interactions: tap/long-press a block → **inline correct**; **version stepper** on edited blocks; **Smart Review** button in the bar; **semantic-warning banner** on flagged blocks.
States: `loading · draft-only` (show "Polish now") `· refined · editing · reviewing` (Smart Review running) `· flagged · offline · error · no-audio`.

### 5.4 Library tab
Layout: search field; filter chip row; list of session cards; smart-collections section header; FAB (capture).
States:
- **empty** — "No notes yet — tap to capture your first idea."
- **loading** — skeleton cards.
- **populated** — cards, pull-to-refresh.
- **search active / results / no-results.**
- **filtered** — active chips: Category, Cluster, Date, Has-audio, Draft/Refined, Pinned.
- **offline** — cached list, pending badges.
- **multi-select** (long-press) — selection bar with **Group Contextually · Bulk Formalize · Pin · Delete**.
Smart collections (auto): *This week · Unrefined drafts · Pinned.*

### 5.5 Settings tab
Sectioned list (design the list + each sub-screen):
- **Account** (identity, providers, sign out, **delete account**).
- **Subscription & Usage** → §5.6.
- **Transcription** (language auto+override, default VTT/subtitle toggle, on-device model/tier + **storage used** + re-download, refinement behavior).
- **API key (BYOK)** → §5.7.
- **Capture** (auto-stop switch + **5–120s slider**, default ~30s; haptics/sound; manage entry points).
- **Privacy & data** (biometric **app lock**, audio retention: keep / discard after transcribe / auto-delete after N days, on-device-only mode, **data export**, delete account).
- **Notifications** (recording [always], refinement-complete [on], digests [later]).
- **Appearance** (theme: System/Light/Dark; dynamic color toggle).
- **Help** (Feature Tour replay, contact, privacy policy, terms).
- **About** (version/build — note: tapping 7× unlocks a hidden **Advanced/Power-User** section; design the unlocked state + its warning dialog).

### 5.6 Subscription & Usage
**Plan cards** with REAL content:
- **Free** — $0 · 30 min/mo cloud refine · 50 MB backup · unlimited on-device.
- **Pro** — **$2.99/mo or $24.99/yr** · 30 hrs/mo cloud refine · 2 GB backup · AI actions · priority. *(Price is a single swappable value — may become $1.99.)*
- **BYOK + Storage** — **$0.99/mo** · unlimited AI (own key) · 2 GB sync.
- **Unlock BYOK** card — **one-time $4.99** OR **5 referrals** (show referral progress, e.g. "2 / 5").
**Usage meter:** minutes used / cap, storage used / cap, cycle reset date.
States: `default · loading · purchase-in-progress · purchase-error · over-quota · byok-locked · byok-unlocked.`
Also design a **Referral screen**: invite link/code, progress "2 / 5", explainer of what counts ("friend signs up + saves 3 notes, or pays").

### 5.7 BYOK / API key screen
Paste key field, **validate** (states: empty/validating/valid/invalid), "using your key" indicator, remove key. If BYOK locked: show the **locked state** with unlock options (pay / refer).

### 5.8 External capture surfaces (design these)
- **Home-screen Widget** — two sizes: 1×1 "tap = record" button; 2×1 wide showing record button + last-note title + sync state. Idle vs recording visuals.
- **Quick Settings tile** — idle and active (recording) tile states.
- **App shortcuts** menu (long-press icon): New recording · Import · Last note.
- **Share-sheet** — how the app's entry appears (icon + label) when sharing audio/text in.
- **Recording foreground notification** ("Recording 00:42 · Pause · Stop") + **refinement-complete notification** ("Your note '…' is polished").

### 5.9 Global / misc
- **Sync-queue bottom sheet** (items waiting to transcribe/refine/sync, per-item status, retry).
- **Permission rationale sheets** (mic, notifications).
- **Coach-mark examples** for: auto-stop (first long pause), add-widget suggestion, Smart Review, BYOK.
- **Account/Auth**: Sign up · Sign in · Forgot password (states: default/loading/error).

---

## 6. Key flows to prototype (clickable, if Claude Design supports flows)
1. **Cold capture:** Widget/tile → Recording → Stop → Processing → Session Detail (the hero flow; make it feel instant).
2. **First-run:** Welcome → first note → account (guest) → home.
3. **Offline capture → later refine:** record offline (pending) → reconnect → "polished" notification → refined note.
4. **Upgrade:** quota-hit sheet → Subscription → purchase → unlocked.
5. **Unlock BYOK:** locked key screen → pay $4.99 OR referral progress → unlocked.

---

## 7. Sample content to use in mockups (for realism)
- Categories: *Memo, Brainstorm, Call Log, Action Items, Journal.*
- A cluster name: *"App Launch Plan."*
- A sample note title: *"Pricing idea — drop Pro to feel free"*; tags: *pricing, growth, idea.*
- Show one note with a **Markdown table** and one formatted as **dialogue**, to exercise the renderer.
- Show a Draft note and a Refined note side by side in Library.

---

## 8. App icon & branding deliverables
- **Adaptive icon:** foreground + background layers (respect safe zone), **monochrome/themed** variant (Android 13+), legible at 48dp.
- **Play Store icon** 512×512. **Splash** (Android 12+ splash: icon + brand color). **Notification icon** (monochrome).
- **Logo lockups:** mark, mark + wordmark (2–3 name-agnostic variants).
- **Brand sheet:** light/dark palettes from seed `#5B5BD6` + coral `#FF6B6B` accent, type, spacing.
- **Motif:** blend **voice/soundwave** + **idea/spark** + a hint of **capture/ink** — calm, modern, ownable. Avoid neon/cyberpunk. Should read instantly at small sizes.

---

## 9. Out of scope for these v1 mockups (don't design yet)
Assistant/App-Actions UI, Android Auto, NFC/Bluetooth trigger screens, floating bubble, manual folders, calendar/map views, RAG "chat with archive," in-app assistant, daily/weekly digest, Power-User automation (webhooks etc.) beyond the gated section's existence. Design the architecture/navigation so these slot in later, but don't produce their screens now.

---

*Design all listed states in light + dark for Compose + Material 3. Wordmark: "Yap" (public name may change to a differentiated variant before launch — keep lockups flexible).*
