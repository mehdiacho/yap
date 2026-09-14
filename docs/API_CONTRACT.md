# Yap — Backend API contract

**Stack:** Firebase Cloud Functions v2 (TypeScript, Node) — same Firebase project as Auth/Firestore/Storage. The web app's `services/geminiService.ts` pipeline ports into the functions almost verbatim. **Built in Claude Code (this repo); deployed separately.** The Android frontend (AI Studio) calls these; calls return real data only once the backend is deployed.

## Conventions
- **Transport:** Firebase **Callable Functions** (`httpsCallable` / Android Functions SDK) — Auth token handled automatically. Audio is **not** sent in the call body; it's uploaded to Cloud Storage first, then referenced by path.
- **Auth:** every call requires a signed-in Firebase user. Guest/local-only users never call the backend.
- **Abuse protection:** every call must include a **Play Integrity** token (`integrityToken` field); the function verifies it before doing work. Reject if missing/invalid.
- **Result channel = Firestore (realtime), not polling.** The client writes a pending session doc and **listens** to it; functions write results back. This makes offline-queue + live status trivial.
- **Keys:** managed tier uses the server's Gemini key. **BYOK:** the client passes `{ provider, byokKey }` per request; the server uses it for that call and **does not store** it. Provider is `"gemini"` for now (multi-provider-ready).

## Core flow (record → refine)
1. App uploads audio → Storage `users/{uid}/audio/{sessionId}.m4a` (Firebase Storage SDK; offline-queues automatically).
2. App writes `users/{uid}/sessions/{sessionId}` = `{ status:"queued", draft_text, createdAt, source, ... }`.
3. App calls **`transcribeSession`** (below).
4. Function: verify Auth + Integrity + quota → download audio → run pipeline (chunk 4:30 + 5s buffer → per-chunk transcribe → 30s bridge-stitch → formalize → VTT if requested) → compute **refinement-delta score** vs `draft_text` → write `{ status:"refined", raw_text, formatted_text, name, description, tags, category, topicCluster, vtt?, refinement:{score,model,refinedAt} }` to the session doc → decrement usage.
5. App's Firestore listener updates the UI draft→polishing→refined. Optional FCM push on completion.

## Callable functions

| Function | Request | Effect / Response |
|---|---|---|
| **`transcribeSession`** | `{ sessionId, audioPath, clientDraft, options:{ vtt:bool, language, mode:"managed"\|"byok", provider, byokKey? }, integrityToken }` | Runs full pipeline; writes results to the session doc; decrements quota. Returns `{ ok, jobId }`. Heavy work async — client listens to the doc. |
| **`refineSession`** | `{ sessionId, rawText, recentContext[], integrityToken }` | Re-runs formalization only ("Polish now" / manual re-formalize). Updates the session doc. |
| **`smartReview`** | `{ blocks:[{versions[],activeIndex}], integrityToken }` | Ports `reviewAndPropagateEdits`. Returns `{ updatedBlocks:[{blockIndex,newText,flagged,reason}] }`. |
| **`groupSessions`** | `{ notes: [{ id, title, createdAt, text }] }` — 2..20, ≤120k chars total | Merges the notes into one document and returns its content: `{ name, description, tags, category, topicCluster, formattedText, rawText, sourceIds }`. The client maps `name` onto the note's `title`; `description` and `tags` are returned by the model but **not stored** — there are no such fields on a session. **The client creates the note**, not the function — see below. Text-only, so it does not consume the transcription quota. Source notes are never modified. |
| **`validateProviderKey`** | `{ provider, key, integrityToken }` | Test call to the provider. Returns `{ valid:bool, providerLabel, error? }`. (No storage.) |
| **`verifyPurchase`** | `{ purchaseToken, productId, type:"sub"\|"oneTime", integrityToken }` | Verifies with Google Play Developer API; sets entitlement (`plan` / `byok.unlocked`). Returns `{ entitled, plan }`. |
| **`getModelManifest`** | `{ deviceTier:"high"\|"mid"\|"low", integrityToken }` | Returns on-device model file(s) to download: `{ models:[{id,url,sizeMB,sha256}] }`. (Files hosted in Storage.) |
| **`referralStatus`** | `{ integrityToken }` | `{ code, completedCount, target:5, referred:[{status,reason}] }`. |
| **`attributeReferral`** | `{ code, integrityToken }` | Called once at signup to attribute the inviter. Returns `{ ok }`. |

> **`groupSessions`, as built.** This contract was written before the implementation and
> described the function creating the merged session itself. It doesn't. The client sends the
> note bodies it already has in memory (so the server does no Firestore reads), gets the merged
> content back, and writes the new note through its own repository — the same shape as
> `refineText`. That keeps the client the only writer of `users/{uid}/sessions` from the app
> side; a second author would mean the repository seeing notes appear that it never asked for.
> The new note is stored with `source: "MERGED"` and `sourceRef` = the comma-joined source ids.

## Firestore triggers (server-side, no client call)
- **`onUserCreate`** — initialize `usage` (cycle, zeroed) + `referrals.code`.
- **Referral completion watcher** — when a referred user reaches **≥3 saved notes** OR makes any payment, mark their referral `completed` and bump the inviter's `completedCount`; unlock BYOK at 5.
- **Play RTDN handler** (Pub/Sub) — subscription renew/cancel/refund → keep `plan` in sync.
- **Usage cycle reset** (scheduled) — reset `minutesUsed`/`refinementsUsed` monthly.
- **Inactivity cleanup** (scheduled) — delete Free-tier audio backups after 6 months inactivity.

## Endpoints the FRONTEND must wire (summary for AI Studio)
- Upload audio to Storage; write/listen to session docs (no endpoint — Firestore SDK).
- Call `transcribeSession` after upload; `refineSession` for Polish-now; `smartReview` from the editor; `groupSessions` from Library multi-select.
- `validateProviderKey` on the BYOK screen; `verifyPurchase` after any Play Billing purchase; `getModelManifest` on first run / model download; `referralStatus` + `attributeReferral` for referrals.
- Until the backend is live, stub these to show the designed loading/empty/error states.
