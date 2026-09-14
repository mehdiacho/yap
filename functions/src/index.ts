import { onCall, HttpsError } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import * as admin from "firebase-admin";
import {
  transcribeAndFormalize,
  transcribeAndAppend,
  formalizeText,
  groupNotes,
  reviewAndPropagate,
  judgeCorrectionOccurrences,
  extractActionItems,
  NoteToGroup,
  ReviewBlock,
} from "./gemini";

admin.initializeApp();

export {
  getAccountStatus,
  redeemPromo,
  adminSearchUser,
  adminSetEntitlement,
  adminCreatePromo,
  adminListPromos,
} from "./accounts";
export {
  deleteAccount,
  getReferralInfo,
  applyReferral,
  onSessionCreatedReferral,
} from "./lifecycle";
export { verifyPlayPurchase } from "./billing";
export { setByokKey, clearByokKey, getByokStatus } from "./byok";
export { getProfile, setProfileName, setProfilePhoto } from "./profile";
export { getUsagePersona } from "./persona";
export { getCostMeter } from "./costMeter";
export { submitFeedback } from "./feedback";
export { onFeedbackCreated } from "./notify";
export {
  adminOverview,
  adminListFeedback,
  adminUpdateFeedback,
  adminListUsers,
  adminTagUser,
  adminPurgeGuests,
  adminRecentActivity,
} from "./admin";
import { getEntitlement, capForPlan } from "./accounts";
import { BYOK_ENC_KEY, resolveGeminiKey } from "./byok";
import { classifyProviderError } from "./providerErrors";
import { existingLabels } from "./labels";

// Set with: firebase functions:secrets:set GEMINI_API_KEY
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");

function currentCycle(): string {
  const now = new Date();
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, "0")}`;
}

/**
 * Atomically checks and records usage in usage/{uid} (clients can read theirs but never
 * write it). The cap comes from the caller's entitlement (plan tier).
 *
 * Returns the cycle the seconds were booked against, so a later failure can refund them
 * without clawing back from the wrong month.
 */
async function checkAndRecordUsage(
  uid: string,
  seconds: number,
  capSeconds: number
): Promise<string> {
  const ref = admin.firestore().collection("usage").doc(uid);
  const cycle = currentCycle();
  await admin.firestore().runTransaction(async (t) => {
    const snap = await t.get(ref);
    const sameCycle = snap.exists && snap.get("cycle") === cycle;
    const used = sameCycle ? (snap.get("secondsUsed") ?? 0) : 0;
    if (used + seconds > capSeconds) {
      throw new HttpsError(
        "resource-exhausted",
        `Monthly transcription limit reached (${Math.round(capSeconds / 60)} min on your plan).`
      );
    }
    t.set(ref, { cycle, secondsUsed: used + seconds, updatedAt: Date.now() });
  });
  return cycle;
}

/**
 * Give the seconds back when the transcription they paid for never happened.
 *
 * Usage has to be booked BEFORE the model call (otherwise a user could run unlimited
 * concurrent jobs and only ever be charged for the ones that finish), which means every
 * server-side failure was silently costing the user quota. A depleted provider balance
 * burned ~17 minutes of a 30-minute monthly cap across seven doomed retries, so the person
 * with the least ability to fix the problem paid for it.
 *
 * Best-effort and never throws: a failed refund must not mask the original error.
 * Skipped when the cycle has rolled over — those seconds are already forgiven.
 */
async function refundUsage(uid: string, seconds: number, bookedCycle: string): Promise<void> {
  if (seconds <= 0) return;
  try {
    const ref = admin.firestore().collection("usage").doc(uid);
    await admin.firestore().runTransaction(async (t) => {
      const snap = await t.get(ref);
      if (!snap.exists || snap.get("cycle") !== bookedCycle) return;
      const used = snap.get("secondsUsed") ?? 0;
      t.set(
        ref,
        { cycle: bookedCycle, secondsUsed: Math.max(0, used - seconds), updatedAt: Date.now() },
        { merge: true }
      );
    });
  } catch (e: any) {
    console.error("usage refund failed", { uid, seconds, message: e?.message });
  }
}

/**
 * transcribeSession — the app uploads a recording to Storage, then calls this with
 * its path. We download the audio, run the Gemini pipeline, and return the note.
 *
 * Request:  { storagePath: string }  OR  { audioBase64: string, mimeType?: string }
 * Response: { rawText, name, description, tags, category, topicCluster, formattedText }
 *
 * Auth: requires a signed-in user (anonymous is fine for now). Play Integrity
 * attestation + per-user quota enforcement are layered in later.
 */
export const transcribeSession = onCall(
  {
    region: "africa-south1",
    secrets: [GEMINI_API_KEY, BYOK_ENC_KEY],
    timeoutSeconds: 540,
    // 512MiB OOM'd on a 31-min recording: the downloaded Buffer plus its base64
    // copy hit 627MiB before transcription even started. Audio is no longer
    // base64'd for large files (see gemini.ts audioPart), but the whole clip is
    // still held in memory, so give it real headroom.
    memory: "1GiB",
  },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Sign-in required.");
    }

    const data = request.data ?? {};
    const mimeType: string = data.mimeType || "audio/wav";

    let audio: Buffer;
    if (data.storagePath) {
      try {
        const bucket = process.env.STORAGE_BUCKET || "yap-mehdiacho.firebasestorage.app";
        const [buf] = await admin.storage().bucket(bucket).file(data.storagePath).download();
        audio = buf;
      } catch (e: any) {
        console.error("storage download failed", { path: data.storagePath, message: e?.message });
        throw new HttpsError("not-found", `Could not read audio at ${data.storagePath}`);
      }
    } else if (data.audioBase64) {
      audio = Buffer.from(data.audioBase64, "base64");
    } else {
      throw new HttpsError("invalid-argument", "Provide storagePath or audioBase64.");
    }

    // Duration for quota, computed server-side where possible (client claim can't lower it):
    // WAV (16kHz mono 16-bit) is exact from byte count; for compressed formats take the
    // larger of the client claim and a conservative size floor. Capped at 3h.
    const claimedSec = (typeof data.durationMs === "number" ? data.durationMs : 0) / 1000;
    const wavExactSec = audio.length / 32000;
    const seconds =
      mimeType === "audio/wav"
        ? wavExactSec
        : mimeType.startsWith("video/")
          // The size floor exists to stop a client under-reporting the length of a
          // compressed audio file. Applied to video it is nonsense: picture dominates the
          // byte count, so a one-minute 1080p clip at 5 Mbps measures as 37 MB and would be
          // billed as nineteen minutes. Video only reaches here when the audio track could
          // not be split out on the device (see MediaExtraction.kt), which is rare — take
          // the client's duration, which it read from the file's own metadata.
          ? Math.max(claimedSec, 1)
          : Math.max(claimedSec, audio.length / 32000);
    const quotaSec = Math.ceil(Math.max(1, Math.min(seconds, 3 * 3600)));

    // Optional: when the client tells us which note this is for, Firestore becomes the
    // authoritative result channel (docs/API_CONTRACT.md). The callable response is then
    // only a convenience — if the caller has already hung up (the client SDK gives up
    // after 70s by default, and long audio blows straight past that), the work still
    // lands. Without a sessionId we keep the old response-only behaviour so clients
    // already in the field are unaffected.
    //
    // Resolved BEFORE the quota check so a quota rejection can be recorded on the note
    // too — otherwise it would sit on TRANSCRIBING forever.
    const sessionId: string | null =
      typeof data.sessionId === "string" && data.sessionId ? data.sessionId : null;
    const sessionRef = sessionId
      ? admin.firestore().collection("users").doc(request.auth.uid)
          .collection("sessions").doc(sessionId)
      : null;

    const entitlement = await getEntitlement(request.auth.uid);
    // On the user's own key they pay the provider directly, so the plan cap doesn't apply
    // and nothing is booked against it.
    const { apiKey, byok } = await resolveGeminiKey(
      request.auth.uid,
      entitlement,
      GEMINI_API_KEY.value()
    );

    let bookedCycle: string | null = null;
    if (!byok) {
      try {
        bookedCycle = await checkAndRecordUsage(
          request.auth.uid,
          quotaSec,
          capForPlan(entitlement.plan)
        );
      } catch (e) {
        if (sessionRef) {
          await sessionRef
            .set(
              {
                status: "QUOTA",
                title: "Monthly limit reached",
                // Remember where the audio landed. The upload happens before this call, so
                // without this the note has no pointer to bytes we are already storing —
                // and transcribing it later would mean re-uploading the whole recording,
                // or discovering the local copy is gone after a reinstall.
                ...(data.storagePath ? { storagePath: data.storagePath } : {}),
              },
              { merge: true }
            )
            .catch((writeErr) =>
              console.error("could not mark session QUOTA", { sessionId, writeErr })
            );
        }
        throw e;
      }
    }

    console.log(
      `transcribeSession: mimeType=${mimeType} bytes=${audio.length} sec=${quotaSec}` +
        (byok ? " byok" : "") +
        (sessionId ? ` session=${sessionId}` : " (no session; response-only)")
    );

    try {
      // Word timings are only asked for by a note that has a video to play them against
      // (see MediaExtraction.kt on the client). Opt-in, so the preview model never sits on
      // the path an ordinary recording takes.
      const wantWordTimings = data.wantWordTimings === true;
      const result = await transcribeAndFormalize(audio, mimeType, apiKey, {
        wordTimings: wantWordTimings,
        durationSec: seconds,
        labels: await existingLabels(request.auth.uid),
      });

      // The user can pick a category before they hit record ("this one's an app update").
      // Their choice wins over the model's guess — they said it deliberately, and having the
      // note land somewhere else would make the pre-selection pointless.
      const chosenCategory =
        typeof data.category === "string" && data.category.trim()
          ? data.category.trim().slice(0, 60)
          : null;

      // Tasks come from the same extractor the backfill uses, so a new note and a
      // ten-month-old one end up with identical shape. Never fatal: a note with a
      // transcript but no task list is fine, one that failed to save is not.
      let tasks: string[] = [];
      try {
        tasks = await extractActionItems(result.formattedText || result.rawText, apiKey);
      } catch (e: any) {
        console.warn("action item extraction failed (non-fatal)", { sessionId, message: e?.message });
      }

      if (sessionRef) {
        // merge:true so we never clobber fields the client owns (pinned, trashedAt,
        // bookmarks, extraRecordings) if it edited the note while we were working.
        await sessionRef.set(
          {
            title: result.name || "Recording",
            draftText: result.rawText,
            formattedText: result.formattedText,
            category: chosenCategory || result.category || "Memo",
            topicCluster: result.topicCluster || "General",
            storagePath: data.storagePath ?? null,
            status: "DONE",
            actionItems: tasks.map((text, i) => ({
              id: `${sessionId}-${i}`,
              text,
              done: false,
              doneAt: null,
            })),
            enrichVersion: 1,
            // Only written when we actually have timings — an empty write here would clear
            // captions off a note that is being re-transcribed after a failure.
            ...(result.words?.length ? { wordTimings: result.words } : {}),
            statusReason: admin.firestore.FieldValue.delete(),
            statusMessage: admin.firestore.FieldValue.delete(),
          },
          { merge: true }
        );
        console.log(
          `transcribeSession: wrote result to session ${sessionId} (${tasks.length} tasks` +
            `${result.words?.length ? `, ${result.words.length} timed words` : ""})`
        );
      }
      return { ...result, category: chosenCategory || result.category, actionItems: tasks };
    } catch (e: any) {
      const failure = classifyProviderError(e, byok);
      console.error("transcribeSession failed", {
        mimeType,
        bytes: audio.length,
        sessionId,
        byok,
        reason: failure.reason,
        message: e?.message,
        stack: e?.stack,
      });

      // The transcription never happened — don't charge for it.
      if (bookedCycle) await refundUsage(request.auth.uid, quotaSec, bookedCycle);

      // Record the failure on the note too, so a disconnected client doesn't leave it
      // spinning on TRANSCRIBING forever. `statusReason`/`statusMessage` are additive:
      // the status stays FAILED so clients already in the field still offer "Try again".
      if (sessionRef) {
        await sessionRef
          .set(
            {
              status: "FAILED",
              statusReason: failure.reason,
              statusMessage: failure.message,
              // As above: keep the pointer to the uploaded audio so "Try again" is a
              // re-run rather than a re-upload.
              ...(data.storagePath ? { storagePath: data.storagePath } : {}),
            },
            { merge: true }
          )
          .catch((writeErr) =>
            console.error("could not mark session FAILED", { sessionId, writeErr })
          );
      }
      throw new HttpsError(failure.httpsCode, failure.message);
    }
  }
);

/**
 * appendToSession — the user re-opened a note and recorded an addition. Transcribes the
 * new clip and integrates it into the existing note (lists get appended, sections extend;
 * nothing unrelated is rewritten). Quota is charged like a normal transcription.
 *
 * Request:  { audioBase64|storagePath, mimeType?, durationMs?, title, existingFormatted, existingDraft }
 * Response: { rawText, formattedText, name }
 */
export const appendToSession = onCall(
  {
    region: "africa-south1",
    secrets: [GEMINI_API_KEY, BYOK_ENC_KEY],
    timeoutSeconds: 540,
    // 512MiB OOM'd on a 31-min recording: the downloaded Buffer plus its base64
    // copy hit 627MiB before transcription even started. Audio is no longer
    // base64'd for large files (see gemini.ts audioPart), but the whole clip is
    // still held in memory, so give it real headroom.
    memory: "1GiB",
  },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
    const data = request.data ?? {};
    const mimeType: string = data.mimeType || "audio/wav";

    let audio: Buffer;
    if (data.storagePath) {
      try {
        const bucket = process.env.STORAGE_BUCKET || "yap-mehdiacho.firebasestorage.app";
        const [buf] = await admin.storage().bucket(bucket).file(data.storagePath).download();
        audio = buf;
      } catch (e: any) {
        throw new HttpsError("not-found", `Could not read audio at ${data.storagePath}`);
      }
    } else if (data.audioBase64) {
      audio = Buffer.from(data.audioBase64, "base64");
    } else {
      throw new HttpsError("invalid-argument", "Provide storagePath or audioBase64.");
    }

    const claimedSec = (typeof data.durationMs === "number" ? data.durationMs : 0) / 1000;
    const wavExactSec = audio.length / 32000;
    const seconds = mimeType === "audio/wav" ? wavExactSec : Math.max(claimedSec, wavExactSec);
    const quotaSec = Math.ceil(Math.max(1, Math.min(seconds, 3 * 3600)));
    const entitlement = await getEntitlement(request.auth.uid);
    const { apiKey, byok } = await resolveGeminiKey(
      request.auth.uid,
      entitlement,
      GEMINI_API_KEY.value()
    );
    const bookedCycle = byok
      ? null
      : await checkAndRecordUsage(request.auth.uid, quotaSec, capForPlan(entitlement.plan));

    try {
      return await transcribeAndAppend(
        audio,
        mimeType,
        String(data.title ?? "Untitled"),
        String(data.existingFormatted ?? ""),
        String(data.existingDraft ?? ""),
        apiKey
      );
    } catch (e: any) {
      const failure = classifyProviderError(e, byok);
      console.error("appendToSession failed", { byok, reason: failure.reason, message: e?.message });
      if (bookedCycle) await refundUsage(request.auth.uid, quotaSec, bookedCycle);
      throw new HttpsError(failure.httpsCode, failure.message);
    }
  }
);

/**
 * refineText — "Polish now" for draft-only notes: formalizes existing draft text
 * without an audio round-trip. Text refine is cheap, so it doesn't consume the
 * per-minute transcription quota (still requires auth).
 *
 * Request:  { draftText: string }
 * Response: { name, description, tags, category, topicCluster, formattedText }
 */
export const refineText = onCall(
  { region: "africa-south1", secrets: [GEMINI_API_KEY, BYOK_ENC_KEY], timeoutSeconds: 120 },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
    const draftText = String(request.data?.draftText ?? "").trim();
    if (!draftText) throw new HttpsError("invalid-argument", "draftText required");
    if (draftText.length > 100_000) throw new HttpsError("invalid-argument", "draft too long");
    const entitlement = await getEntitlement(request.auth.uid);
    const { apiKey, byok } = await resolveGeminiKey(
      request.auth.uid,
      entitlement,
      GEMINI_API_KEY.value()
    );
    try {
      return await formalizeText(draftText, apiKey, await existingLabels(request.auth.uid));
    } catch (e: any) {
      const failure = classifyProviderError(e, byok);
      console.error("refineText failed", { byok, reason: failure.reason, message: e?.message });
      throw new HttpsError(failure.httpsCode, failure.message);
    }
  }
);

/**
 * groupSessions — "Group" in the Library's multi-select bar: merge several notes into one.
 *
 * Returns the merged note's content and lets the client create the document, the same way
 * refineText does. The alternative — writing the new session here and returning its id —
 * would put a second author on the sessions collection, and the client's repository would
 * have to learn that some notes arrive without it asking. (API_CONTRACT.md describes the
 * server-writes shape; it predates the implementation and is corrected there.)
 *
 * The source notes are not touched. Grouping adds a note, it never removes one.
 *
 * Text-only, so — like refineText and enrichNotes — it does not consume the per-minute
 * transcription quota. The user already paid to transcribe this content once.
 *
 * Request:  { notes: [{ id, title, createdAt, text }] }   // 2..20
 * Response: { name, description, tags, category, topicCluster, formattedText, sourceIds }
 */
const GROUP_MAX_NOTES = 20;
/**
 * Total input characters allowed across the selection.
 *
 * Sized against the OUTPUT budget, not the input one. A merge is close to length-preserving —
 * that is the point of it — so the model has to be able to write back roughly what it was
 * given, and 2.5-flash's output ceiling is the binding constraint long before its million-token
 * context is. ~120k characters is ~30k tokens in and a comparable amount out, which leaves room
 * for the thinking tokens that also come out of that budget.
 *
 * Overrunning it isn't silent, at least: a truncated response is invalid JSON, and
 * parseFormalizeResponse falls back to the concatenated sources — the user gets everything
 * they selected, unformatted, rather than a document that quietly stops halfway.
 */
const GROUP_MAX_CHARS = 120_000;

export const groupSessions = onCall(
  { region: "africa-south1", secrets: [GEMINI_API_KEY, BYOK_ENC_KEY], timeoutSeconds: 300 },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");

    const raw = request.data?.notes;
    if (!Array.isArray(raw)) throw new HttpsError("invalid-argument", "notes required");

    const notes: NoteToGroup[] = raw.flatMap((n: any) => {
      const id = typeof n?.id === "string" ? n.id : null;
      const text = typeof n?.text === "string" ? n.text.trim() : "";
      if (!id || !text) return [];
      return [{
        id,
        title: typeof n?.title === "string" && n.title.trim() ? n.title.trim() : "Untitled",
        createdAt: Number(n?.createdAt) || 0,
        text,
      }];
    });

    // Two is the smallest thing that can be merged; one note "grouped" is just that note.
    if (notes.length < 2) {
      throw new HttpsError("invalid-argument", "Pick at least two notes with something in them.");
    }
    if (notes.length > GROUP_MAX_NOTES) {
      throw new HttpsError(
        "invalid-argument",
        `That's more than ${GROUP_MAX_NOTES} notes at once — group them in smaller batches.`
      );
    }
    const total = notes.reduce((sum, n) => sum + n.text.length, 0);
    if (total > GROUP_MAX_CHARS) {
      throw new HttpsError(
        "invalid-argument",
        "Those notes are too long to merge in one pass — try fewer at a time."
      );
    }

    const entitlement = await getEntitlement(request.auth.uid);
    const { apiKey, byok } = await resolveGeminiKey(
      request.auth.uid,
      entitlement,
      GEMINI_API_KEY.value()
    );

    try {
      const merged = await groupNotes(notes, apiKey, await existingLabels(request.auth.uid));
      return { ...merged, sourceIds: notes.map((n) => n.id) };
    } catch (e: any) {
      const failure = classifyProviderError(e, byok);
      console.error("groupSessions failed", {
        byok,
        count: notes.length,
        reason: failure.reason,
        message: e?.message,
      });
      throw new HttpsError(failure.httpsCode, failure.message);
    }
  }
);

/**
 * smartReview — propagate the user's manual block corrections across the whole
 * document and flag semantic shifts. Request: { blocks: [{versions, activeIndex}] }
 * Response: { updatedBlocks: [{blockIndex, newText, flagged, reason?}] }
 */
export const smartReview = onCall(
  { region: "africa-south1", secrets: [GEMINI_API_KEY, BYOK_ENC_KEY], timeoutSeconds: 120 },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
    const blocks = request.data?.blocks as ReviewBlock[] | undefined;
    if (!Array.isArray(blocks) || blocks.length === 0) {
      throw new HttpsError("invalid-argument", "blocks required");
    }
    if (blocks.length > 500) throw new HttpsError("invalid-argument", "too many blocks");
    const entitlement = await getEntitlement(request.auth.uid);
    const { apiKey, byok } = await resolveGeminiKey(
      request.auth.uid,
      entitlement,
      GEMINI_API_KEY.value()
    );
    try {
      const updatedBlocks = await reviewAndPropagate(blocks, apiKey);
      return { updatedBlocks };
    } catch (e: any) {
      const failure = classifyProviderError(e, byok);
      console.error("smartReview failed", { byok, reason: failure.reason, message: e?.message });
      throw new HttpsError(failure.httpsCode, failure.message);
    }
  }
);

/**
 * reviewCorrections — advisory pass over "you fixed one, here are the others".
 *
 * The client finds occurrences itself with a literal offline scan and shows them
 * immediately; this only annotates the ones that look deliberate ("cloud storage" when the
 * correction was cloud→Claude). Never on the critical path — the client applies a 12s
 * budget and drops the result if it isn't back, because correcting a typo must not depend
 * on the network.
 *
 * Cheap and text-only, so it doesn't touch the transcription quota.
 *
 * Request:  { from, to, snippets: string[] }
 * Response: { verdicts: [{ index, intentional, reason }] }
 */
export const reviewCorrections = onCall(
  { region: "africa-south1", secrets: [GEMINI_API_KEY, BYOK_ENC_KEY], timeoutSeconds: 60 },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
    const from = String(request.data?.from ?? "").trim();
    const to = String(request.data?.to ?? "").trim();
    const snippets = request.data?.snippets;
    if (!from || !to) throw new HttpsError("invalid-argument", "from and to required");
    if (!Array.isArray(snippets) || snippets.length === 0) return { verdicts: [] };
    // Bounded so a pathological note can't turn one edit into a huge prompt.
    const capped = snippets.slice(0, 60).map((s: unknown) => String(s ?? "").slice(0, 300));

    const entitlement = await getEntitlement(request.auth.uid);
    const { apiKey, byok } = await resolveGeminiKey(
      request.auth.uid,
      entitlement,
      GEMINI_API_KEY.value()
    );
    try {
      const verdicts = await judgeCorrectionOccurrences(from, to, capped, apiKey);
      return { verdicts };
    } catch (e: any) {
      const failure = classifyProviderError(e, byok);
      console.warn("reviewCorrections failed", { reason: failure.reason, message: e?.message });
      // Advisory only — degrade to "no advice" rather than failing the user's edit.
      return { verdicts: [] };
    }
  }
);

/**
 * enrichNotes — extract action items for a batch of notes.
 *
 * Serves the backfill described by CURRENT_ENRICH_VERSION on the client: rather than
 * teaching every reader to cope with notes that predate the feature, old notes are simply
 * brought up to date by running them through here. The same call also enriches a single
 * freshly-opened note, so there is one definition of what a task is.
 *
 * Batched because the alternative is one round trip per note and a library has hundreds.
 * Capped at 10 per call so a batch stays inside the timeout; the client pages.
 *
 * Text-only and cheap, so it doesn't consume the transcription quota — charging a user
 * minutes of their audio allowance to re-read notes they already paid to transcribe would
 * be indefensible.
 *
 * Request:  { notes: [{ id, text }] }
 * Response: { results: [{ id, tasks: string[] }] }   // absent id == failed, leave as-is
 */
export const enrichNotes = onCall(
  { region: "africa-south1", secrets: [GEMINI_API_KEY, BYOK_ENC_KEY], timeoutSeconds: 300 },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
    const notes = request.data?.notes;
    if (!Array.isArray(notes) || notes.length === 0) return { results: [] };
    const batch = notes.slice(0, 10).flatMap((n: any) => {
      const id = typeof n?.id === "string" ? n.id : null;
      const text = typeof n?.text === "string" ? n.text : "";
      return id ? [{ id, text }] : [];
    });

    const entitlement = await getEntitlement(request.auth.uid);
    const { apiKey, byok } = await resolveGeminiKey(
      request.auth.uid,
      entitlement,
      GEMINI_API_KEY.value()
    );

    const results: { id: string; tasks: string[] }[] = [];
    for (const note of batch) {
      try {
        results.push({ id: note.id, tasks: await extractActionItems(note.text, apiKey) });
      } catch (e: any) {
        // One bad note must not fail the batch — the client marks only what came back, so
        // anything omitted is simply retried on the next pass.
        const failure = classifyProviderError(e, byok);
        console.warn("enrichNotes: note failed", { id: note.id, reason: failure.reason });
        if (failure.reason === "PROVIDER_CREDIT" || failure.reason === "PROVIDER_AUTH") {
          // No point grinding through the rest on a dead key; surface it and stop.
          throw new HttpsError(failure.httpsCode, failure.message);
        }
      }
    }
    return { results };
  }
);
