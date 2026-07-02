import { onCall, HttpsError } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import * as admin from "firebase-admin";
import { transcribeAndFormalize } from "./gemini";

admin.initializeApp();

export {
  getAccountStatus,
  redeemPromo,
  adminSearchUser,
  adminSetEntitlement,
  adminCreatePromo,
  adminListPromos,
} from "./accounts";
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
