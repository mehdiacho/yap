import { onCall, HttpsError } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import * as admin from "firebase-admin";
import { transcribeAndFormalize } from "./gemini";

admin.initializeApp();

// Set with: firebase functions:secrets:set GEMINI_API_KEY
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");

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
  { secrets: [GEMINI_API_KEY], timeoutSeconds: 300, memory: "512MiB" },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Sign-in required.");
    }

    const data = request.data ?? {};
    const mimeType: string = data.mimeType || "audio/wav";

    let audio: Buffer;
    if (data.storagePath) {
      try {
        const [buf] = await admin.storage().bucket().file(data.storagePath).download();
        audio = buf;
      } catch (e) {
        throw new HttpsError("not-found", `Could not read audio at ${data.storagePath}`);
      }
    } else if (data.audioBase64) {
      audio = Buffer.from(data.audioBase64, "base64");
    } else {
      throw new HttpsError("invalid-argument", "Provide storagePath or audioBase64.");
    }

    try {
      return await transcribeAndFormalize(audio, mimeType, GEMINI_API_KEY.value());
    } catch (e: any) {
      throw new HttpsError("internal", `Transcription failed: ${e?.message ?? "unknown"}`);
    }
  }
);
