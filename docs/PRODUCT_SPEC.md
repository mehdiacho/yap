# Yap — Master Product & Feature Specification
### Native Android voice-first idea-capture app · Source of truth for Claude Design → AI Studio

> **Status:** v1 fully specified to screen/state level; v1.5–vNext specified to feature/screen level for roadmap design.
> **Build target:** Native Android — **Kotlin + Jetpack Compose, Material 3 (Material You)**. Generated via Google AI Studio (Android output: `MainActivity.kt`, real hardware access).
> **Design system:** Clean modern Material 3, light + dark, dynamic color. NOT the legacy "Terminal-U" cyberpunk theme.
> **Name:** **Yap** (working name for design/build). ⚠️ A near-identical "Yap! — Voice Notes & Widget" already exists on Google Play (same on-device-transcription + widget concept) — **finalize the public Play Store name + trademark before launch** (see §1 for vetted clear alternatives).

---

## 0. How to read this document

- **§1–§5** = product framing, naming, design system, IA, principles. Read first.
- **§6** = every v1 screen with every state (this is the bulk of the design work).
- **§7** = capture entry points (widget, tile, share, shortcuts).
- **§8** = transcription engine (on-device + cloud + scoring).
- **§9** = accounts, subscription, BYOK.
- **§10** = settings + Power-User Mode.
- **§11** = opt-in/opt-out matrix (which controls appear where).
- **§12** = data model.
- **§13** = roadmap (v1 / v1.5 / v2 / v3 / vNext).
- **§14** = app icon & branding brief.
- **§15** = permissions & onboarding framework.
- **§16** = open decisions.

Every screen lists its **states**. Claude Design must produce a mockup for **each state**: `empty`, `loading`, `populated`, `error`, `offline`, `permission-needed`, `permission-denied`, `quota-hit` where applicable.

---

## 1. Naming

Working title is a placeholder. Proposed shortlist (voice-first, idea-capture, ownable, short):

| Name | Why it works | Notes |
|---|---|---|
| **Inkling** ★ | "a vague idea" + ink/writing pun. Warm, memorable, on-theme. | Verify Play Store / domain availability. |
| **Spoke** ★ | Past tense of "speak" + hub/spoke. Short, clever, ownable. | Common word — check trademark. |
| **Jot** | "jot it down" — implies instant, low-friction capture. | Very short; may be taken. |
| **Riff** | Casual "riff on an idea," voice-y, energetic. | Short, brandable. |
| **Muse** | Inspiration/idea angle. | Common; check conflicts. |
| **Murmur** | Soft, voice/audio connotation. | Distinctive. |

**More candidates** (name deferred — keep generating):

| Name | Angle |
|---|---|
| **Cadence** | Rhythm of speech/thought; premium feel. |
| **Aside** | "as an aside" — a quick captured thought. |
| **Tangent** | Where ideas wander; playful. |
| **Verbatim** | Accuracy/transcription angle; serious. |
| **Utter** | "utter a thought"; short, voice-y. |
| **Trove** | A trove of captured ideas; archive angle. |
| **Recall** | Memory/retrieval angle (check conflicts w/ existing products). |
| **Lyra / Vox / Voxa** | Abstract, brandable, voice roots. |
| **Brainwave** | Idea + signal; energetic. |
| **Quill** | Writing/capture; classic. |

**Decided:** **Yap** — the working name; all docs now use it. ⚠️ **Open risk for launch:** "Yap! — Voice Notes & Widget" is already live on Google Play in the *same* category, and "Yapp" is a known event app — so "Yap" is fine for design/build but the **public store name + trademark must be finalized before release.** Already vetted as **collision-free on Play**: **YapDrop, YapBook, YapVault, YapJar** (any can drop in as the public name with a one-word swap). Icon/wordmark should be designed to survive that swap.

---

## 2. Product overview

**One-liner:** Capture any spoken idea in one tap — anywhere, online or off — and get it instantly transcribed, auto-organized, and refined into clean structured notes.

**Core promise:** Zero friction from "I have a thought" → "it's saved and readable." The app must be reachable and recording in **under 2 seconds** from a cold idea (widget/tile/assistant), and must **never lose** a recording (offline-first, auto-recover).

**Why it exists:** Replace the round-trip of opening a web app (15–30s) and consolidate scattered note/transcription tools into one all-in-one capture+knowledge app.

**Primary user (persona):** "The out-loud thinker" — captures ideas constantly, often hands-busy (driving, walking), prefers speaking to typing, wants ideas organized without effort and retrievable later.

**Top jobs-to-be-done:**
1. Capture a spoken idea instantly, even offline.
2. Get a usable transcript automatically (draft now, polished later).
3. Find and reuse past ideas (search, clusters, "chat with my archive").
4. Turn ideas into action (summaries, tasks, exports).

---

## 3. Global design system (Material 3)

- **Foundation:** Material 3 (Material You). Dynamic color on Android 12+; fall back to a defined brand palette on older OS.
- **Brand palette (fallback / seed):** **Seed = deep indigo/violet `#5B5BD6`** (calm, distinctive, "thoughtful," works in light + dark and as an icon base). Material 3 derives the tonal palette from this. Secondary/tertiary auto-derived; accent for the record button can shift slightly warmer (e.g. coral `#FF6B6B`) for the active-recording state so "recording" reads instantly. On Android 12+, dynamic color overrides the seed (user's wallpaper); seed is the fallback + brand anchor. Avoid the legacy neon cyan.
- **Modes:** Light + Dark, both first-class. Respect system theme; manual override in Settings (System / Light / Dark).
- **Type:** Material 3 type scale; a humanist sans for body (e.g. system default / Roboto Flex), optional distinct display face for branding. Large, legible — this is used one-handed and in motion.
- **Shape:** Rounded (Material 3 large/extra-large corners) — friendly, not techy.
- **Key components:** Top app bar (small/center-aligned), **bottom navigation bar** (3 tabs, see §4), FAB / large capture button, cards (sessions), bottom sheets (actions, pickers), snackbars (undo), dialogs, chips (tags/categories/filters), segmented buttons (formatted/raw), sliders (auto-stop threshold), switches (toggles).
- **The Capture Button:** a single, large, unmistakable primary action. On the Capture tab it's center-stage; elsewhere it's a FAB. Pressing gives **haptic + optional sound** confirmation.
- **Motion:** Material motion; calm, quick. Recording state = subtle pulsing waveform, not frantic.
- **Accessibility:** WCAG AA contrast, ≥48dp touch targets, full TalkBack labels, dynamic font scaling, haptic cues so recording state is perceivable without looking.

---

## 4. Information architecture & navigation

**Bottom navigation — 3 tabs:**

1. **Capture** (default landing tab) — the record screen. App opens here.
2. **Library** — all notes: search, filters, clusters, collections.
3. **Settings** — account, subscription, transcription, privacy, etc.

**Above/around nav:**
- **Capture** is also reachable without the app via Widget, Quick Settings Tile, App Shortcut, Assistant, Share target → all deep-link into an in-progress recording.
- **Session Detail** is a full screen pushed from Library (or directly after a recording finishes).
- **In-app assistant** (v3) accessible from a persistent help affordance.

**Navigation map:**
```
App
├── Capture (tab, default)
│   ├── Idle / armed
│   ├── Recording (live waveform + live draft)
│   ├── Paused
│   ├── Processing (on-device → queued for cloud)
│   └── → Session Detail (on finish)
├── Library (tab)
│   ├── List / search / filters / smart collections
│   ├── Cluster view
│   └── → Session Detail
│       ├── Formatted / Raw toggle
│       ├── Block edit + version history + Smart Review
│       ├── Audio playback (tap-line-to-seek)
│       ├── AI actions (v2+)
│       └── Export / share
└── Settings (tab)
    ├── Account
    ├── Subscription & Usage
    ├── Transcription (language, VTT, on-device model/tier, storage)
    ├── API key (BYOK)
    ├── Capture (auto-stop, haptics, entry points)
    ├── Privacy & data (app lock, audio retention, export, delete account)
    ├── Notifications
    ├── Appearance (theme)
    ├── Help / Feature Tour / Assistant
    └── About → [tap 7× → Power-User Mode → Advanced]
```

---

## 5. Cross-cutting principles (binding on all features)

1. **Progressive disclosure.** Average users see a calm, simple app. Complexity is earned.
2. **Power-User Mode (gated like Developer Options).** Settings → About → tap version/build 7× → unlocks an **Advanced** section, with a one-time warning. Reversible.
3. **Contextual onboarding for every feature.** Just-in-time coach-marks at the right moment, each shown once, all replayable from **Help → Feature Tour**. Central framework; every feature registers its moment (§15).
4. **Opt-in vs opt-out tuned for satisfaction.** Default-ON only what nearly everyone wants and won't be surprised by; default-OFF anything needing a permission, system overlay, privacy trade, or behavior change (§11).
5. **Just-in-time permissions.** Never request all at launch; request each at the moment of use, paired with its onboarding.
6. **Never lose a recording.** Offline-first capture, auto-recover on interruption, local queue with visible pending state.
7. **Speed is a feature.** Cold-start to recording < 2s from any entry point.

---

## 6. v1 SCREENS — full state-by-state spec

### 6.1 First-run / onboarding flow
**Purpose:** prove core value in seconds, set up account, request mic just-in-time.

Screens/steps:
1. **Welcome** — one line value prop, "Get started" + "I already have an account."
2. **Instant first note** — "Tap to capture your first idea." Triggers mic permission *here* (in context). User records 5s, sees live draft, sees it saved. This is the aha.
3. **Account** — sign up / sign in (Google primary, email/password, **Continue as guest**). Guest notes stored locally; banner later prompts to create account to sync/back-up.
4. **How transcription works** — short: "Drafts instantly on your phone, polishes when online." Sets the draft→refine expectation.
5. **Key choice** — "Use Yap's AI (free tier / upgrade)" or "Use my own API key." Defaults to managed free tier; BYOK is one tap away.
6. **Suggested setup** — offer to add the Widget + Quick Settings Tile (opt-in prompts).

States per step: `default`, `loading` (auth), `error` (auth failed / network), `permission-denied` (mic — show how to enable, allow skip), `offline` (guest path works; cloud steps deferred).

### 6.2 Capture screen (default tab) — THE core screen
**Layout:** Large central capture button; above it a slot for live waveform + timer + live draft text; minimal chrome.

**States:**
| State | UI |
|---|---|
| **Idle / armed** | Big "Tap to record" button. Hint text. Recent-note peek (last 1) optional. If returning from interruption: "Resume recovered recording?" banner. |
| **Recording** | Pulsing live **waveform**, running **timer**, **live draft text** streaming below (on-device `SpeechRecognizer`), **Pause** + **Stop** controls. Persistent foreground-service notification active. Auto-stop-on-silence countdown indicator if armed. |
| **Paused** | Frozen waveform, "Paused" label, **Resume** + **Stop**. |
| **Processing** | On stop: "Transcribing on device…" → if online, "Polishing…"; if offline, "Saved — will polish when online." Progress + ability to navigate away (continues in background). |
| **Offline** | Recording works fully; banner "Offline — draft saved, will refine when connected." Pending badge. |
| **Permission-needed** | Mic rationale + request. |
| **Permission-denied** | Friendly explainer + "Open settings"; capture disabled with clear reason. |
| **Quota-hit (managed tier, no key)** | Recording + on-device draft still work; "Cloud polish paused — you're out of minutes" + upsell / "use your own key." |
| **Error** | Mic/hardware error; offer retry; never lose buffered audio. |

**Behaviors:** pause/resume; auto-stop on silence (configurable, §8/§11); auto-recover; haptic+sound on start/stop; append-to-existing (v2).

### 6.3 Post-capture → Session Detail (auto-opens after recording)
Shows the new note immediately: title (auto-generated, editable), draft transcript, processing status chip (Draft / Polishing / Refined), audio player. See 6.5 for full detail spec.

### 6.4 Library tab
**Layout:** Search bar; filter row (chips); list of session cards; smart collections section; FAB to capture.

**Session card:** title, category pill, cluster pill (if not "General"), description snippet, tags, date, audio indicator, **status chip** (Draft / Refined), pin indicator.

**States:**
| State | UI |
|---|---|
| **Empty** | Friendly empty state: "No notes yet — tap to capture your first idea." |
| **Loading** | Skeleton cards. |
| **Populated** | Cards list; pull-to-refresh; infinite scroll. |
| **Search active** | **Full-text search across transcript bodies** + title/tags/category/cluster; result count; clear. |
| **Filtered** | Active filter chips: category, cluster, date range, has-audio, Draft-vs-Refined, Pinned. |
| **No results** | "No matches" + clear filters. |
| **Offline** | Cached list; pending items show "will sync" badge. |
| **Multi-select** | Long-press → selection mode → bulk actions: Group Contextually, Bulk Formalize, Delete (→ trash), Pin. |

**Smart collections (auto):** This week · Has action items (v2) · Unrefined drafts · Pinned. (Manual folders = v2.)

### 6.5 Session Detail
**Header:** back, title (inline-editable), status chip, overflow menu (export, share, delete, AI actions [v2]).

**Body:**
- **Segmented control:** Formatted | Raw.
- **Formatted** = Markdown render (tables, headers, lists, blockquotes, dialogue).
- **Block-level editing:** tap/long-press a block → correct; **version history** per block (prev/next, n/m); **Smart Review** button (AI propagates a correction across the doc, flags semantic shifts with a warning banner).
- **Audio player:** play/pause, scrubber, **tap a transcript line → seek audio to that timestamp** (uses VTT timing), download original audio, speed control.
- **Metadata:** category, cluster, tags (editable), created date, draft-quality/refinement indicator (advisory).
- **AI actions menu (v2):** summarize, extract action items, rewrite/format-as (email/outline/tweet), translate.

**States:** `loading`, `draft-only` (not yet refined — show "Polish now" button), `refined`, `editing`, `reviewing` (Smart Review running), `flagged` (semantic-shift warnings present), `offline` (read cached; edits queue), `error`, `no-audio` (audio discarded/never kept).

**Export options:** PDF (visual), Markdown, plain .txt, share-sheet (text/PDF/audio). Copy Markdown.

### 6.6 Settings tab — see §10.

### 6.7 Account screens
Sign up / Sign in / Forgot password / Account (email, linked providers, sign out, **delete account**). States: `default`, `loading`, `error`, `offline` (queued where safe).

### 6.8 Subscription & Usage
Plan cards (Free / Pro), current plan, **usage meter** (minutes transcribed, refinements used this cycle), upgrade CTA (Play Billing), restore purchases, manage subscription (deep-link to Play). States: `default`, `loading`, `purchase-in-progress`, `purchase-error`, `over-quota`.

### 6.9 Global components
- **Pending/sync queue indicator** (header badge): tap → list of items waiting to transcribe/refine/sync, with per-item status + manual "retry now."
- **Snackbars with Undo** for delete/trash, edits.
- **Recording foreground notification:** "Recording… [Pause][Stop]" — controllable from the shade.
- **Refinement-complete notification:** "Your note '[title]' is polished" (opt-out).

---

## 7. Capture entry points (v1 set + later)

| Entry point | Android mechanism | Behavior | Phase |
|---|---|---|---|
| **Home-screen widget** | App Widget (Glance) | Sizes: 1×1 instant-record button; wide variant shows state + "resume last" + latest note. | v1 |
| **Quick Settings tile** | `TileService` | Tap = start/stop record without unlocking; long-press = open app. | v1 |
| **App shortcuts** | Static/dynamic shortcuts | Long-press launcher icon → New recording / Import / Last note. | v1 |
| **Share target** | `intent-filter` `ACTION_SEND`/`ACTION_SEND_MULTIPLE` for `audio/*`, `video/*` (extract audio track), `text/*` (import). Direct Share shortcuts. | App appears on system Share sheet from any app with audio/video/text. | v1 |
| **Lock-screen action** | From foreground service / notification | Start/stop from lock screen. | v1 |
| Google Assistant / App Actions | App Actions + shortcuts | "Hey Google, take a note in Yap." | v2 |
| Bluetooth/headset button | Media button receiver | Earbud press start/stop. | v2 |
| NFC trigger | NFC intent | Tap an NFC tag → instant record. | v2 |
| Android Auto | Auto app / voice | Hands-free capture while driving. | v2 |
| Floating bubble | `SYSTEM_ALERT_WINDOW` (opt-in) | Chat-head record button over any app. | v2 |
| Quick Tap / side-key | OEM mapping (user-set) | Double-tap back / side key → record. | v2 |

All entry points deep-link into the **Recording** state (already armed). Each appearance has a one-time coach-mark.

---

## 8. Transcription engine (grounded in current Android capabilities)

### 8.1 Pipeline (offline-first, draft → refine)
```
Record (foreground service, never blocked by network)
   │
   ├─► LIVE DRAFT (streaming, on-device, all devices):
   │     Android createOnDeviceSpeechRecognizer() → real-time partial text on screen
   │
   ├─► QUALITY LOCAL PASS (on-device, tiered, after stop):
   │     High tier  → ML Kit GenAI Speech Recognition (Gemini Nano / AICore)
   │     Mid/Low    → Whisper tiny/base (int8) via LiteRT  (downloaded post-install)
   │     Fallback   → keep the live-draft text
   │
   └─► CLOUD REFINE (online, when connected):
         Gemini (latest) via secure backend proxy OR user's BYOK
         → chunking pipeline (4:30 segments + 5s buffer + 30s bridge-stitch — port existing logic)
         → formalization: name, description, tags, category, topicCluster, formatted Markdown
         → REFINEMENT-DELTA SCORE computed & stored (see 8.4)
```

### 8.2 Device-tier detection & on-demand model download
- **On first run**, detect: AICore / Gemini Nano availability (ML Kit feature-availability API), SoC/NPU, RAM, ABI, Android version.
- Map to **3 tiers**:
  - **High** (Pixel 8+, Galaxy S24+, Snapdragon/Dimensity/Tensor w/ AICore): live draft + ML Kit GenAI on-device quality pass. No download needed for the GenAI pass (AICore-provisioned).
  - **Mid** (capable but no AICore): live draft + **download Whisper base (int8)** for quality pass.
  - **Low** (entry devices): live draft + **download Whisper tiny / Vosk-small**; live draft may be the primary local result.
- **Models download AFTER install** from backend/Play Asset Delivery, not bundled (keeps APK small). Show a one-time "Setting up offline transcription (~X MB) — Wi-Fi recommended" step; allow deferral; show storage used in Settings → Transcription.
- All tiers degrade gracefully: if a model isn't ready, the live-draft text is the offline result and cloud refine fills in later.

### 8.3 VTT / subtitle mode
Optional per-recording toggle (port existing bridge-stitch + reindex). Off by default for quick memos; on for media/subtitle use.

### 8.4 Refinement-delta scoring (cost optimizer)
- After cloud refine, compute a **delta score** between the local draft and the refined output:
  - lexical: WER/CER; structural: length/format change; **semantic similarity** (embedding cosine).
- **Store per session** (hidden by default). Aggregate analytics (backend) learn *when* refinement materially helps.
- **Default:** refinement is **advisory** — always runs unless user opts into auto-skip.
- **Auto-skip (opt-in / Power-User):** when predicted gain is below a threshold, skip cloud refine to save cost/quota; user can force "Polish anyway."
- **Optional user-facing** "draft quality: high/medium" chip (off by default; on in Power-User or Transcription settings).

### 8.5 Key handling (hybrid)
- **Managed:** app → **secure backend proxy** (Firebase Cloud Function / server) holding the shared Gemini key; enforces per-user quotas, rate limits, max audio size.
- **BYOK:** user pastes their own key (validated, stored encrypted on-device + optionally synced encrypted); requests use their key, bypassing managed quota/cost.
- Clear indicator everywhere transcription runs: "Using Yap AI" vs "Using your key."

---

## 9. Accounts, subscription & BYOK

- **Auth:** Firebase Auth — Google (primary), email/password; **guest** (local-only, upgradeable). Apple optional.
- **Multi-tenant isolation:** **Replace the open Firestore rules** (`allow read,write: if true`) with per-user rules: a user can read/write only their own `users/{uid}/sessions/...`. (Current global `sessions` collection is a security hole and must be migrated.)
**Core principle:** On-device draft + **local-only** use is **always free** and costs us ~nothing (no model cost, no storage). What users pay for is **our managed Gemini key** and/or **our cloud storage + sync**. BYOK removes our biggest variable cost (the API), so BYOK is *earned/gated*, not the free default.

- **Tiers (finalized from `docs/PRICING_SPEC.md`; Pro = $1.99/mo, decided 2026-07-13):**

| Tier | AI key | Cloud storage / sync | Price | Caps / inclusions |
|---|---|---|---|---|
| **Free (managed)** | Ours (metered) | Included | $0 | **30 min/mo** cloud refine · **50 MB** backup · on-device unlimited & free |
| **Pro (managed)** | Ours (generous) | Included | **$1.99/mo · $19.99/yr** | **1,800 min/mo (30 hrs)** cloud refine · **2 GB** backup · AI actions · priority |
| **BYOK + storage** | User's key | Included | **$0.99/mo** | Unlimited AI (their cost) · **2 GB** our sync/backup |
| **BYOK local-only** | User's key | None (device only) | **$0 after unlock** | Unlimited AI (their cost) · no cloud; ~$0 to us |

> **Pro price note:** DECIDED 2026-07-13: **$1.99/mo · $19.99/yr**. Margins support either (Typical user ≈ 96% GM at $2.99). Design the Subscription screen so the price is a single easily-swapped value.

- **BYOK is gated** (it grants near-free app access, so it's earned): unlock via **(a) one-time $4.99** OR **(b) 5 completed referrals.**
- **Completed referral =** referred user signs up **AND saves ≥ 3 voice notes** (proves real use; blocks emulator farms), **OR** makes any in-app payment (auto-counts immediately).
- **Referrals currently only unlock BYOK.** *(Later: referrals could also grant managed-minute credits / Pro trials — parked for v2/v3.)*
- **Abuse protection:** backend proxy requires a valid **Play Integrity** attestation token (stops API scraping through our key).
- **Storage creep mitigation:** Free-tier audio backups deletable after **6 months of account inactivity** (state in ToS).
- **Billing:** Google Play Billing. One-time BYOK unlock = managed product; Pro/BYOK-storage = Play subscriptions. Restore purchases. Usage meter (§6.8).
- **At quota=0 (managed Free/Pro):** capture + on-device draft continue; cloud refine pauses with upsell (Pro) / BYOK-unlock suggestion.

---

## 10. Settings + Power-User Mode

**Standard settings sections:**
- **Account** — identity, providers, sign out, delete account.
- **Subscription & Usage** — plan, meter, upgrade, restore.
- **Transcription** — language (auto + override), VTT default, on-device model/tier + storage used + re-download, refinement behavior (advisory/auto-skip is here when unlocked).
- **API key (BYOK)** — paste/validate/remove key; "using your key" indicator.
- **Capture** — auto-stop-on-silence (toggle + **5–120s slider**, default ~30s), haptics/sound, default VTT, manage entry points (widget/tile/share/shortcuts; v2 triggers).
- **Privacy & data** — biometric **app lock** (opt-in), audio retention (keep / discard after transcribe / auto-delete after N days), on-device-only mode (opt-in), data export, delete account.
- **Notifications** — recording (always), refinement-complete (opt-out), digests (v2 opt-in).
- **Appearance** — theme (System/Light/Dark), dynamic color toggle.
- **Help** — Feature Tour (replay onboarding), in-app Assistant (v3), contact/support, privacy policy, terms.
- **About** — version/build (**tap 7× → Power-User Mode**), licenses.

**Power-User Mode → Advanced section (gated):**
- Automation: **webhooks / Tasker / Zapier / IFTTT**, own-API access (v3).
- Pipeline tuning: chunk length (default 270s), buffer (5s), bridge window (30s).
- On-device model override / force tier.
- Refinement scoring: thresholds, **auto-skip** tuning, export raw scores.
- Diagnostics: logs, sync queue internals, beta-feature flags.
- Warning shown on unlock; reversible.

---

## 11. Opt-in / opt-out / power-user matrix

(Authoritative — drives which controls each screen needs and their defaults.)

| Feature | Default | Onboarding moment |
|---|---|---|
| Instant capture, on-device draft, cloud refine, save, library, search, edit, auth, sync | Core (always on) | First-run "first note in 5s" |
| Auto-recover interrupted recordings | On (core) | Only if it triggers |
| Auto-categorize / topic-clusters | On (opt-out) | First cluster formed |
| Auto-stop on silence (~30s, 5–120s slider) | On (opt-out) | First long pause |
| Keep original audio | On (opt-out) | Privacy onboarding |
| Refinement-complete notification | On (opt-out) | First refinement |
| Haptics / sound on start/stop | On (opt-out) | — |
| Widget / Quick Settings tile / shortcuts | Opt-in (user adds) | Suggested after a few captures |
| Share target | On (registered) | First share-in |
| Biometric app lock | Opt-in | When first private-looking note saved |
| Floating overlay bubble | Opt-in | Walkthrough on enable (overlay perm) |
| Geotag / context metadata | Opt-in | On enable (location perm) |
| Bluetooth-button / NFC / Android Auto / Assistant | Opt-in | Per-trigger setup guide |
| Voice-activated start | Opt-in | On enable |
| Custom vocabulary | Opt-in | After a repeated mis-hear |
| Reminder/task/calendar extraction | Opt-in | First detected action item (calendar perm) |
| On-device-only mode / auto-delete audio after N days | Opt-in | Privacy onboarding |
| Daily/weekly digest, streaks/stats | Opt-in | After a week of use |
| Refinement auto-skip (cost saver) | Advisory on; auto-skip opt-in | On enable |
| Webhooks / Tasker / Zapier / IFTTT / own-API | Power-User only | In-section docs |
| Pipeline tuning, model/tier override | Power-User only | Warning on unlock |
| Diagnostics / raw scoring export / beta flags | Power-User only | — |

---

## 12. Data model (per-user, Firestore)

```
users/{uid}
  profile: { displayName, email, plan: free|pro|byok_storage|byok_local, createdAt }
  settings: { language, vttDefault, autoStopEnabled, autoStopSeconds, audioRetention,
              appLock, onDeviceOnly, theme, dynamicColor, notif{...}, powerUser{...} }
  usage: { cycleStart, minutesUsed, refinementsUsed, plan, storageBytesUsed }
  byok: { unlocked: bool, unlockMethod: payment|referral, keyEncrypted?, validatedAt? }
  referrals: { code, referredCount, completedCount,
               referred[]: { uid, status: pending|completed, completedAt, reason: activity|payment } }
  sessions/{sessionId}
    name, description, tags[], category, topicCluster
    transcript (raw_text), formatted_text
    draft_text                 // on-device draft
    refinement: { status: draft|polishing|refined|skipped, score?, model, refinedAt }
    block_history_formatted[], block_history_raw[]
    audioUrl?, audioRetained: bool
    vtt?: string
    source: { entryPoint, sharedFromPackage?, location?, calendarEventId? }
    createdAt, updatedAt, pinned, trashedAt?
```
Migration: existing global `sessions` (open rules) → move under `users/{uid}` + lock rules.

---

## 13. Roadmap

**v1 — core loop + frictionless capture (largest phase)**
Foreground-service recording (waveform, timer, pause/resume, auto-recover) · Widget + Quick Settings Tile + App Shortcuts + Share target · on-device live draft + one downloadable quality model + device detection · Gemini cloud refine + chunking/bridge port + formalization (name/desc/tags/category/cluster/formatted) · refinement-delta scoring (advisory) · offline queue + pending states · Library (full-text search, categories, clusters, smart collections) · Session Detail (formatted/raw, block edit, version history, Smart Review, tap-to-seek audio, export PDF/MD/txt/share) · Auth (Google/email/guest) + per-user isolation + sync · hybrid keys (proxy + BYOK) + Free/Pro + usage meter + Play Billing · biometric lock · auto-stop-on-silence · privacy policy + data export + delete account · universal onboarding framework · Material 3 design + adaptive icon + new name.

**v1.5 — on-device maturity**
Full device-tier model matrix + on-demand download (high/mid/low) · scoring-driven auto-skip refinement.

**v2 — smarter capture & retrieval**
Assistant/App Actions · Bluetooth-button · NFC · Android Auto · floating bubble · Quick Tap · voice-activated start · custom vocabulary · context metadata (location/calendar) · actionable extraction (reminders/tasks/calendar) · per-note AI templates · translation · manual folders · pin/trash/archive · calendar/map views · more smart collections.

**v3 — knowledge & power**
RAG "chat with your archive" · **in-app app-aware assistant** (knowledge base generated from THIS spec; answers "how do I…", deep-links, later performs actions) · daily/weekly digest · auto-linking/related graph · Power-User suite (webhooks, automation glue, own-API, pipeline tuning, diagnostics) · "speak → email," public share links, Drive/Docs/Notion/Obsidian export.

**vNext / moonshots**
Wear OS · deeper Android Auto · shared/team workspaces · real-time collaboration · web companion. (Share-out architecture in v1 designed so these slot in without redesign.)

---

## 14. App icon & branding brief (for Claude Design)

Deliver a full set:
- **Adaptive icon:** foreground + background layers (safe-zone correct), **monochrome/themed icon** (Android 13+ themed icons), legible at small sizes.
- **Play Store icon:** 512×512.
- **Splash screen** (Android 12+ splash API): icon + brand color.
- **Notification icon** (monochrome).
- **Brand kit:** logo (mark + wordmark), color palette + seed (light/dark), type, motion accent.

**Motif direction:** combine **voice/waveform** + **idea/spark** + a hint of **writing/capture** — calm, modern, ownable. Not neon, not cyberpunk. Should feel like a friendly everyday tool. Tie to the name **Yap** — e.g. a **speech bubble + soundwave**, casual and energetic (and flexible enough to survive a launch rename to YapDrop/YapBook).

---

## 15. Permissions & onboarding framework

**Permissions (just-in-time, each with rationale + coach-mark):**
- Microphone — at first record (required).
- Notifications — for foreground recording + refinement (Android 13+ runtime).
- Overlay (`SYSTEM_ALERT_WINDOW`) — only when enabling floating bubble (v2).
- Location — only when enabling geotagging (v2).
- Calendar — only when enabling action extraction (v2).
- Bluetooth — only when enabling headset triggers (v2).

**Onboarding framework:**
- Central registry of one-time coach-marks keyed by feature; each fires at its contextual trigger, shown once, dismissible.
- **Help → Feature Tour** replays any/all.
- "What's new" surface after updates introduces newly shipped features.
- Privacy onboarding clearly states what stays on-device vs what leaves (audio/text to backend→Gemini only on refine).

---

## 16. Decisions log

**Resolved (applied throughout the spec):**
- **Brand seed color:** deep indigo/violet `#5B5BD6`, warm coral `#FF6B6B` for active-recording (§3/§14). ✅
- **Subscription numbers:** Free 90 min/mo managed cloud; Pro $6.99/mo · $59.99/yr · 3,000 min/mo; BYOK unlimited & free (§9). ✅ *(figures provisional — verify Gemini rates.)*
- **Guest mode:** YES — record before signup, local-only, upgrade prompt to sync (§6.1). ✅
- **Auto-skip refinement:** advisory-only in v1; auto-skip lives behind Power-User Mode (§8.4/§11). ✅
- **User-facing draft-quality chip:** internal by default in v1; surfaced only in Power-User/Transcription settings (§8.4). ✅
- **Audio retention:** keep audio ON by default, with discard + auto-delete-after-N-days options (§10/§11). ✅

**Monetization model (resolved — §9):**
- On-device + local-only = always free. Paid value = our managed key and/or our storage/sync. ✅
- Four tiers: Free (managed), Pro (managed), BYOK+storage, BYOK local-only. ✅
- **BYOK is gated:** unlock via one-time payment (~$4–5 TBD) OR ~5 completed referrals (TBD). ✅
- Completed referral = signup + activity threshold, OR any payment (auto-counts). ✅
- Referrals only unlock BYOK for now; other referral rewards parked for later. ✅

**Still open (your call):**
1. **Final name** (§1) — deferred; pick when ready → I verify availability + global replace `Yap`.
2. **Final prices & thresholds** — produced by running `docs/PRICING_PROMPT.md` against live rates (referral count, one-time unlock price, Pro price, BYOK-storage price, Free/Pro minute caps, storage caps).

---

*End of specification. Replace `Yap` globally once the name is chosen.*
