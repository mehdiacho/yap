# Yap — AI Studio handoff (read me first)

AI Studio can't attach a GitHub repo, so the prompt below is **self-contained** — it carries the product, the web-app logic to reuse, the design decisions, and the backend API contract inline. You'll also attach the files for full detail.

## Step 1 — Create a NEW AI Studio project
Native **Android** app (Kotlin + Jetpack Compose). This is a fresh project, not the web-app project.

## Step 2 — Attach these files
**Required**
- `docs/PRODUCT_SPEC.md` — full source of truth (build **v1**, §13).
- `designs/Yap - Android Designs.dc.html` — every screen + state, light + dark (patched version).
- `designs/Yap - Hero Flow Prototype.dc.html` — the core flow.
- `docs/API_CONTRACT.md` — the backend endpoints the app calls.

**Reuse the proven logic/prompts from the old web app (don't reinvent these):**
- `services/geminiService.ts` — the transcription prompts + bridge-stitch + formalization + smart-review logic.
- `App.tsx` — the 4:30-chunk + 5s-buffer algorithm.
- `services/wavEncoder.ts` — audio slicing/downsampling.

> If AI Studio rejects the 264 KB design HTML, export the kit frames as PNGs and attach those instead.

## Step 3 — Paste the prompt below into the chat window.

═══════════════════════════════════════════════════════════════
## ▼▼▼ COPY EVERYTHING BELOW THIS LINE INTO THE AI STUDIO PROMPT ▼▼▼
═══════════════════════════════════════════════════════════════

Build **Yap**, a native Android voice idea-capture app — **Kotlin + Jetpack Compose, Material 3 (Material You)**. Capture a spoken idea in one tap, online or offline; it transcribes on-device instantly, then refines in the cloud and auto-organizes. Build **v1 only**. The attached spec and design kit are authoritative — follow them exactly; don't make product or layout decisions yourself. The attached `.ts`/`App.tsx` files are an existing web app whose **transcription logic and prompts you should port** (not the React UI).

### Architecture — frontend vs backend (important)
This app has a **separate backend** (Firebase Cloud Functions, built elsewhere). **You build the Android frontend only.** Do NOT embed the managed AI key or run cloud transcription locally.
- **On the device (you build):** all UI; recording via a **foreground service** (survives screen-off/background); the **on-device live draft** (Android `SpeechRecognizer` / `createOnDeviceSpeechRecognizer()`, streams partial text, works offline); an **on-device quality model** (ML Kit GenAI / Gemini Nano where available, else a downloadable Whisper model via LiteRT, chosen by device tier and downloaded after install); the **offline queue**; Play Billing purchase UI; biometric app lock.
- **On the backend (already specified — you just call it):** the managed Gemini key, quota enforcement, the cloud refinement pipeline, purchase/referral validation. **Use the endpoints in the attached `API_CONTRACT.md`.** Until the backend is deployed, stub these calls to show the designed loading / empty / error / offline states (don't fake success).

### Transcription pipeline to reuse (from the attached web-app files)
Port this logic into how the app prepares audio and talks to the backend:
- Split long audio into **4:30 (270s) chunks** with a **5-second buffer rule** (if the remainder is <5s, merge it into the previous chunk).
- The cloud refinement (server-side) transcribes each chunk and uses a **30-second "bridge stitch"** across chunk boundaries to remove duplicate/hallucinated text — preserve this when calling the backend (send chunk boundaries / the on-device draft as context).
- A **formalization pass** produces title, description, tags, a **category** (Memo/Brainstorm/Call Log/Action Items/Journal…), a **topicCluster** that links recordings about the same project across days, and a **formatted Markdown** version (tables, dialogue, headers) alongside the raw text.
- Optional **WebVTT/subtitle** mode (per-recording toggle).
- **Smart Review:** in the editor, the user corrects a block; the app calls the backend to propagate that correction across the doc and flag any semantic shifts.
- A **refinement-delta score** (computed server-side) compares the on-device draft to the refined output — send the on-device draft up with each transcription request.

### Capture entry points (all deep-link into Recording/Processing)
App Widget (1×1 + 4×1), **Quick Settings Tile**, App Shortcuts (incl. **Import audio file**), and a **share target** registered for `audio/*`, `video/*`, and `text/*` — so the app appears on the Android share sheet when sharing a recording/voice note/video/text from any app, and imports it straight into transcription.

### Accounts, data, monetization
- Firebase **Auth**: Google + email/password + **guest** (local-only, upgradeable). Guest users never call the backend.
- **Per-user Firestore isolation** under `users/{uid}/...` with locked security rules. (The old web app used open rules — do NOT replicate that.) Use the data model in the spec (§12).
- **Result channel is Firestore realtime:** write a pending session doc, call the backend, and **listen** to the doc for draft→polishing→refined updates.
- **Tiers (exact):** Free $0 (30 min/mo cloud, 50 MB) · Pro $2.99/mo or $24.99/yr (30 hrs/mo, 2 GB) · BYOK+Storage $0.99/mo · BYOK local-only free after unlock. **Google Play Billing** for subscriptions + the one-time unlock; verify every purchase via the backend.
- **BYOK is multi-provider, Gemini active now** (OpenAI/Anthropic shown "Coming soon"); validate keys via the backend; "using your key" indicator; never log the key.
- **BYOK unlock gate:** one-time $4.99 OR 5 completed referrals (a referral completes when the invitee signs up and saves ≥3 notes, or makes any payment).

### Design system
Material 3 / Material You. Seed **indigo `#5B5BD6`**; **coral `#FF6B6B`** reserved exclusively for the active-recording state. Light + dark both first-class; dynamic color on Android 12+. Roboto Flex (body) + Outfit (display/wordmark). Build **every screen in every state** shown in the attached design kit (empty/loading/populated/recording/paused/processing/offline/quota/permission/error/editing/multi-select). Progressive disclosure: average users see a calm app; **Power-User/Advanced settings unlock by tapping the version number 7×** (with a warning dialog). Just-in-time permissions (mic at first record, etc.), each with a one-time coach-mark.

### Must-carry behaviors
- Cold-start → recording in under ~2s from widget/tile.
- Recording survives screen-off and interruptions; **auto-recover** buffered audio (never lose a recording).
- Fully usable offline; queue transcribes/refines/syncs on reconnect.
- Sharing an **audio file** from another app lands it in Yap and transcribes it.
- **On-device-only mode** truly keeps audio/text on the device.
- Adaptive icon + monochrome themed icon + splash (motif: speech bubble + soundwave).
- App name "Yap" throughout.

### Do NOT build in v1 (designed-for, deferred — spec §13)
Google Assistant/App-Actions runtime, Android Auto, NFC/Bluetooth triggers, floating bubble, manual folders, calendar/map views, RAG "chat with archive," in-app assistant, digests, and Power-User automation (webhooks etc.) beyond the gated section merely existing.

═══════════════════════════════════════════════════════════════
## ▲▲▲ END OF AI STUDIO PROMPT ▲▲▲
═══════════════════════════════════════════════════════════════

## After AI Studio builds the frontend
The backend (Firebase Cloud Functions, per `API_CONTRACT.md`) is built separately in Claude Code and deployed to the same Firebase project. Then point the app's Functions/Firebase config at it and the stubbed calls go live.
