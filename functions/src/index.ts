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
import { getEntitlement, capForPlan } from "./accounts";

// Set with: firebase functions:secrets:set GEMINI_API_KEY
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");

/**
 * Atomically checks and records usage in usage/{uid} (clients can read theirs but never
 * write it). The cap comes from the caller's entitlement (plan tier).
 */
async function checkAndRecordUsage(uid: string, seconds: number, capSeconds: number): Promise<void> {
  const ref = admin.firestore().collection("usage").doc(uid);
  const now = new Date();
  const cycle = `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, "0")}`;
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
  { region: "africa-south1", secrets: [GEMINI_API_KEY], timeoutSeconds: 300, memory: "512MiB" },
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
    const entitlement = await getEntitlement(request.auth.uid);
    await checkAndRecordUsage(request.auth.uid, quotaSec, capForPlan(entitlement.plan));

    console.log(`transcribeSession: mimeType=${mimeType} bytes=${audio.length} sec=${quotaSec}`);
    try {
      return await transcribeAndFormalize(audio, mimeType, GEMINI_API_KEY.value());
    } catch (e: any) {
      console.error("transcribeSession failed", {
        mimeType,
        bytes: audio.length,
        message: e?.message,
        stack: e?.stack,
      });
      throw new HttpsError("internal", `Transcription failed: ${e?.message ?? "unknown"}`);
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
  { region: "africa-south1", secrets: [GEMINI_API_KEY], timeoutSeconds: 300, memory: "512MiB" },
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
    await checkAndRecordUsage(request.auth.uid, quotaSec, capForPlan(entitlement.plan));

    try {
      return await transcribeAndAppend(
        audio,
        mimeType,
        String(data.title ?? "Untitled"),
        String(data.existingFormatted ?? ""),
        String(data.existingDraft ?? ""),
        GEMINI_API_KEY.value()
      );
    } catch (e: any) {
      console.error("appendToSession failed", { message: e?.message });
      throw new HttpsError("internal", `Append failed: ${e?.message ?? "unknown"}`);
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
  { region: "africa-south1", secrets: [GEMINI_API_KEY], timeoutSeconds: 120 },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
    const draftText = String(request.data?.draftText ?? "").trim();
    if (!draftText) throw new HttpsError("invalid-argument", "draftText required");
    if (draftText.length > 100_000) throw new HttpsError("invalid-argument", "draft too long");
    try {
      return await formalizeText(draftText, GEMINI_API_KEY.value());
    } catch (e: any) {
      console.error("refineText failed", { message: e?.message });
      throw new HttpsError("internal", `Refine failed: ${e?.message ?? "unknown"}`);
    }
  }
);

/**
 * smartReview — propagate the user's manual block corrections across the whole
 * document and flag semantic shifts. Request: { blocks: [{versions, activeIndex}] }
 * Response: { updatedBlocks: [{blockIndex, newText, flagged, reason?}] }
 */
export const smartReview = onCall(
  { region: "africa-south1", secrets: [GEMINI_API_KEY], timeoutSeconds: 120 },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
    const blocks = request.data?.blocks as ReviewBlock[] | undefined;
    if (!Array.isArray(blocks) || blocks.length === 0) {
      throw new HttpsError("invalid-argument", "blocks required");
    }
    if (blocks.length > 500) throw new HttpsError("invalid-argument", "too many blocks");
    try {
      const updatedBlocks = await reviewAndPropagate(blocks, GEMINI_API_KEY.value());
      return { updatedBlocks };
    } catch (e: any) {
      console.error("smartReview failed", { message: e?.message });
      throw new HttpsError("internal", `Smart review failed: ${e?.message ?? "unknown"}`);
    }
  }
);
