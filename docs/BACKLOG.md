# Yap — backlog / deferred items

Running list of things intentionally deferred while we build the core (functionality-first).
Not bugs — conscious "later" decisions so they don't get lost.

## Product / UX
- **Post-capture behavior setting.** Let the user choose, in Settings, what happens after Stop:
  (a) open the new note showing "Transcribing…" (current behavior), or
  (b) stay on Capture (ready for the next idea) with the note transcribing in the Library.
  Default TBD. — *requested 2026-07-01.*
- **Notification media-style controls.** Real icon Pause/Stop buttons (MediaStyle / MediaSession)
  like a music player, instead of text actions.
- **Background share-transcription (optional).** Setting: when audio is shared into Yap, don't
  open the app — receive + transcribe silently in the background with a "received, transcribing…"
  notification (prompting for any needed permission). Lets you keep scrolling in the source app.
  — *requested 2026-07-02.*

- **"Make available offline" model (OneDrive-style).** The full picture, per user 2026-07-02:
  - Everything lives in the cloud by default (for signed-in, opted-in users). The card icon is
    about presence on THIS device: phone icon = offline-ready here, cloud icon = online-only here.
  - A recording **made on this device** defaults to *available offline* on that device (it was
    born local — don't upload-then-evict). User can later demote it to "keep online only".
  - Cloud-only audio can be pulled on demand ("make available offline"); pulled copies that go
    unused for N days get evicted locally (stay in cloud) IF the note is set online-only.
  - Optional per-note "never upload" mark for sensitive recordings.
  - Keep Settings small: prefer sensible defaults over toggles.
  - **Time+activity eviction (later):** downloads persist indefinitely for now (cleared only via
    Settings → Clear downloads). Future: auto-evict cached copies after a lengthy idle window,
    where any access resets the clock; pinned (available-offline) notes are never evicted.
  - **Legend/key** somewhere discoverable explaining the OneDrive-style icons.
  - **Smartphone icon is reserved** for the future "local only / never upload" state
    (offline-pin covers device-born/pinned; cloud-done covers cached downloads).
  *Shipped so far: persistent download cache (OneDrive model) + tap-to-download player +
  3-state card icons (offline-pin / cloud-done cached / cloud online-only) + Settings → Storage →
  Clear downloads (never touches available-offline originals).*

## Pre-launch checklist
- **Enable App Check (Play Integrity)** on `transcribeSession` BEFORE distributing any build:
  client attests first (debug provider + registered token for dev builds), verify accepted-token
  logs, then flip `enforceAppCheck: true`. Until then the function is callable by anyone who
  extracts the Firebase config from an APK (quotas cap per-user damage only).

## Architecture hardening (interim shortcuts currently in place)
- **Audio transport:** currently sent inline (base64) to the function. Move to Firebase Storage
  upload for larger files + retention (`users/{uid}/audio/...`).
- **Storage:** notes currently in local Room only. Migrate to per-user Firestore for multi-device sync.
- **Auth:** anonymous-only right now. Add Google + email/password + guest→account upgrade.
- **Abuse/cost:** add Play Integrity attestation on the function + per-user quota/rate limits.

## Transcription accuracy
- Option to use `gemini-2.5-pro` (better on non-US accents) vs Flash — likely Pro-tier / opt-in.
- Custom vocabulary (names/jargon/brands) injected as hints.
- Smart Review: fix a misheard word once, propagate across the note (port from web app).
- On-device draft + cloud refine + delta scoring (cross-check).

## Design
- Dedicated design-fidelity pass to match the design kit pixel-for-pixel (deferred by choice;
  doing cheap high-visibility fixes inline in the meantime).
