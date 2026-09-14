# Yap — session handoff (2026-07-14)

Snapshot for continuing in a fresh chat. Covers what shipped this session, current repo/branch
state, what's live in Play Console, and what's still open.

## Project one-liner
Yap — Kotlin + Jetpack Compose Android voice idea-capture app. Firebase backend (Cloud
Functions v2, Firestore, Storage, Auth) in `africa-south1`. Package `app.greenforrest.yap`
(namespace stays `com.mehdiacho.yap`). Gemini via backend for transcription/formatting.

## Repo / branch state
- Working branch: **`feature/design-pass`**. Default branch: **`main`**.
- A PR into `main` was opened this session (see GitHub) specifically to exercise the new CI/CD
  pipeline on merge. **Merging to `main` triggers a signed-AAB build → Play internal track.**
- Keystore files (`upload-keystore.jks`, `keystore.properties`) are git-ignored — user must keep
  their own backups. Alias `upload`.
- Firebase Android app id (current): `1:179392577570:android:c69bb85e567d9ec0fa0a7b`.

## Shipped this session (all built + installed to the 16UCP device, debug)

### Onboarding / auth
- **Skip onboarding for returning users**: `AuthManager.SignInResult.existingAccount` — when a
  Google/email sign-in lands on an already-registered account, onboarding is marked done and the
  user drops straight into Capture. New accounts/guests still get the full flow.
- **Password visibility toggle**: see-no-evil monkey (🙈/🐵) with a springy spin, in
  `AuthComponents.kt` (`PeekabooToggle`).
- **Live password checklist** (`PasswordChecklist` + `passwordMeetsPolicy`): 6+ chars, one
  uppercase, one number, one symbol — ticks green as satisfied. Shown in create-account mode
  only; the create button is gated on the full policy.
- **Sign in / Create account mode toggle** on BOTH the onboarding account step and the settings
  `AuthScreen`. Sign-in mode: button "Sign in"/"Log in", only min-length gate, forgot-password
  link, no checklist. Create mode: button "Create account", full policy + checklist. This fixed
  the settings screen's "grayed-out button with no hint" confusion.
- **Animated Google button** (`GoogleGradientButton`): Google-colors gradient that flows one
  direction (leftward "river", `TileMode.Repeated`, ~7.2s loop), white "G" chip + bold white
  label. Docked at the bottom of both auth screens.
- **Email verification**: sent automatically (non-blocking) on new email-account creation.
- **Friendlier "no credentials" message** when Play services' credential module isn't ready
  (the Google sign-in failure the user hit — see below).

### Capture
- **Removed the debug state previewer** ("preview: live" button, bottom-left of Capture) and its
  preview-only flows: `ProcessingContent` and `QuotaSheet` composables are deleted, and the
  `devState` plumbing is gone. `ErrorContent` and `PermissionDeniedContent` remain (real
  triggers: mic-silenced interruption, permission denied). Git history retains the deleted code.
- **Empty-draft race fixed** (`LiveDraftEngine`): back-to-back recordings no longer let the
  previous session's delayed recognizer teardown/consume clobber the new one. Everything delayed
  is now generation-tagged (`generation`, `consumeDraft(expectedGeneration)`,
  `makeListener(gen)`).

### Notes / player
- **Append-to-note keeps separate clips** instead of WAV-splicing. New model field
  `Session.extraRecordings: List<ExtraRecording>` (persisted in `FirestoreSessionRepository`).
  Detail screen `MultiRecordingPlayer` pages primary + appended clips with dots + horizontal
  swipe confined to the player area (note text still merges as before). Delete cleans up all
  clips (local + cloud).

### Settings / system
- **App-lock timeout** (`SettingsStore.lockTimeoutMins`, `shouldRelock()`, `noteBackgrounded()`):
  Privacy & data → chips "Right away / 1 / 5 / 15 min". Re-lock only after the timeout elapses in
  background; rotation never re-locks (process-level `unlocked` flag in `MainActivity`
  companion). Fixes the "unlock again just to read a polish notification" annoyance.
- **AMOLED pure-black dark** toggle (Appearance → "Pure black dark"): `SettingsStore.amoledDark`,
  applied in `Theme.kt` via `colorScheme.copy(...)`. **Placeholder palette — needs a real Claude
  Design pass.**
- **Faster nav transitions**: NavHost enter/exit set to 220ms fade+slide (was the 700ms default).
- **Deprecated window color APIs gated** below Android 15 in `MainActivity` (kept for the MIUI
  nav-bar scrim fix; the Android 15 Play warning's remaining hits are inside Material lib).

### Billing
- **Purchase modal state machine** (`BillingManager.PurchasePhase`: Idle/Initiating/Verifying/
  Confirmed/Cancelled/Failed) driving `SubscriptionScreen`'s sheet: "Initiating purchase…" →
  "Confirming purchase…" (server `verifyPlayPurchase`) → spinner morphs into green tick "Purchase
  confirmed", or ✕ "Purchase failed" with Try again. **Fixes the forever-spinner** on cancel/
  decline (USER_CANCELED auto-dismisses; failures land on the ✕ state). Receipts are Google
  Play's job (automatic emails).

### CI/CD
- `.github/workflows/release.yml`: on push to `main`, builds a signed AAB and uploads to the Play
  **internal** track. versionCode = `git rev-list --count HEAD`.
- **Six GitHub secrets set** on `mehdiacho/yap` (private): `UPLOAD_KEYSTORE_BASE64`,
  `KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_PASSWORD`, `KEYSTORE_KEY_ALIAS`, `GOOGLE_SERVICES_JSON`,
  `PLAY_SERVICE_ACCOUNT_JSON`.
- **Dedicated CI service account**: `play-ci@yap-mehdiacho.iam.gserviceaccount.com` (no GCP roles;
  only Play publishing). User invited it in Play Console with "Release apps to testing tracks".
  This is separate from the purchase-verification account
  `yap-mehdiacho@appspot.gserviceaccount.com`.

## Play Console — live state
- App `app.greenforrest.yap`, closed testing has a release; internal testing used for CI.
- Products created + active: subscriptions `pro_monthly` ($1.99), `pro_yearly` ($19.99),
  `byok_storage_monthly` ($0.99); one-time `byok_unlock` ($4.99). IDs are lowercase and match
  `BillingManager` constants + `functions/src/billing.ts` `SUBSCRIPTION_PLANS`. Base plans use
  7-day grace + ~53-day account hold, charge-at-next-billing-date, resubscribe allowed.
- Purchase-verification service account `yap-mehdiacho@appspot.gserviceaccount.com` invited with
  "View financial data" + "Manage orders and subscriptions".
- License testing: user's Gmail list added (test purchases, no charge, ~5-min renewals).
- Firebase SHAs on the current Android app: debug `8B:3C:…:5EA4`, upload `C6:78:…:2297`,
  Play app-signing SHA-1 `14:D4:9E:1E:…:6B:E3`, plus the Play SHA-256.

## Known open item — Google sign-in "no credentials available"
- **Config is verified correct** (debug keystore SHA-1 matches Firebase; `google-services.json`
  carries the Android OAuth clients + web client id for both packages). logcat showed Play
  services was **mid-download of its Identity Credentials module** on a fresh install — a GMS
  staleness window, not a config bug.
- If still failing when resumed: retry after a few minutes / open Play Store once / clear Google
  Play services **cache** (not data). To capture the exact GMS error, run a filtered logcat while
  the user taps the button (grep `credential|googleid|GetCredential|NoCredential`).

## Deferred / backlog (see `docs/BACKLOG.md` for full notes)
- Share a social-media video link → transcribed note (premium; yt-dlp-on-Cloud-Run vs paid API).
- Ecosystem share sheet across sibling apps (shelved by design until app #2 exists).
- Connectors / smart actions (calendar+contacts from action blocks) — consideration only.
- Claude Design work: AMOLED palette, Library page rework, note-detail 3-dot menu decomposition.
- R8/minification before production. Store-name final decision. App Check (Play Integrity) before
  wide distribution. Remaining stub features (#4 block editing, #16 Moonshine,
  notification restyle, 4×1 widget).

## Build / device notes
- Build: `./gradlew assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Fresh-package installs on MIUI need on-screen approval (INSTALL_FAILED_USER_RESTRICTED);
  in-place updates don't. Release-signed build can't overwrite a Play-signed install (must
  uninstall first).
- Primary device ends in **16UCP**; occasionally build to **3EA** on request.
- Debug builds animate ~2-3× slower than release — judge transition smoothness on release.

## Constraints (persistent)
- **No AI attribution** in commits/PRs (no `Co-Authored-By: Claude`, no 🤖, use `mehdiacho`
  identity). Global CLAUDE.md rule.
- No pixel-diff screenshot testing — user eyeballs on-device.
- Design fidelity is one-to-one with the design kit; read the frame HTML before building UI.
