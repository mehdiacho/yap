import { onCall, HttpsError } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import * as admin from "firebase-admin";
import {
  transcribeAndFormalize,
  transcribeAndAppend,
  formalizeText,
  reviewAndPropagate,
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
import { getEntitlement, capForPlan } from "./accounts";
import { BYOK_ENC_KEY, resolveGeminiKey } from "./byok";
import { classifyProviderError } from "./providerErrors";

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
            .set({ status: "QUOTA", title: "Monthly limit reached" }, { merge: true })
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
      const result = await transcribeAndFormalize(audio, mimeType, apiKey);

      if (sessionRef) {
        // merge:true so we never clobber fields the client owns (pinned, trashedAt,
        // bookmarks, extraRecordings) if it edited the note while we were working.
        await sessionRef.set(
          {
            title: result.name || "Recording",
            draftText: result.rawText,
            formattedText: result.formattedText,
            category: result.category || "Memo",
            topicCluster: result.topicCluster || "General",
            storagePath: data.storagePath ?? null,
            status: "DONE",
            statusReason: admin.firestore.FieldValue.delete(),
            statusMessage: admin.firestore.FieldValue.delete(),
          },
          { merge: true }
        );
        console.log(`transcribeSession: wrote result to session ${sessionId}`);
      }
      return result;
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
            { status: "FAILED", statusReason: failure.reason, statusMessage: failure.message },
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
      return await formalizeText(draftText, apiKey);
    } catch (e: any) {
      const failure = classifyProviderError(e, byok);
      console.error("refineText failed", { byok, reason: failure.reason, message: e?.message });
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
