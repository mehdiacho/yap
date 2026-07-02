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
