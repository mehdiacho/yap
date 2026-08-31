import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

/**
 * In-app feedback.
 *
 * Every app should have one of these, and the voice option is the point of it rather than a
 * flourish: people structure their sentences when they type and stop saying the thing they
 * actually meant. Speaking is looser and more honest, and this app already knows how to
 * handle a recording.
 *
 * Feedback is written to a top-level `feedback` collection rather than under the user, so the
 * admin side reads one ordered list instead of walking every account — and so a user deleting
 * their account doesn't silently delete the report that would have explained why they left.
 */

/** What a report can be about. Mirrored by the client picker; "other" is always available. */
const TOPICS = new Set([
  "bug",
  "transcription-quality",
  "billing",
  "feature-request",
  "performance",
  "design",
  "other",
]);

const MAX_MESSAGE = 4000;

export const submitFeedback = onCall({ region: "africa-south1" }, async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Sign-in required.");
  }
  const data = request.data ?? {};

  const topic = typeof data.topic === "string" && TOPICS.has(data.topic) ? data.topic : "other";
  const message = typeof data.message === "string" ? data.message.trim().slice(0, MAX_MESSAGE) : "";
  const voicePath = typeof data.voicePath === "string" && data.voicePath ? data.voicePath : null;

  // One or the other has to be there. A report with neither is a mis-tap, and storing it
  // would put an empty row in front of whoever reads these.
  if (!message && !voicePath) {
    throw new HttpsError("invalid-argument", "Say something, or record a message.");
  }

  const uid = request.auth.uid;
  const doc = admin.firestore().collection("feedback").doc();
  await doc.set({
    uid,
    email: request.auth.token?.email ?? null,
    topic,
    message,
    voicePath,
    voiceDurationMs: Number(data.voiceDurationMs ?? 0) || 0,
    // The client's own account of itself. Worth having on a bug report and impossible to
    // reconstruct later, since the next build will have moved on.
    appVersion: typeof data.appVersion === "string" ? data.appVersion.slice(0, 40) : null,
    platform: typeof data.platform === "string" ? data.platform.slice(0, 20) : "android",
    device: typeof data.device === "string" ? data.device.slice(0, 80) : null,
    osVersion: typeof data.osVersion === "string" ? data.osVersion.slice(0, 40) : null,
    createdAt: Date.now(),
    // Admin-side triage state. Set here so every row sorts and filters the same way, rather
    // than the dashboard having to cope with the field being absent on older reports.
    status: "NEW",
    readAt: null,
  });

  console.log(`feedback: ${doc.id} topic=${topic} uid=${uid} voice=${voicePath ? "yes" : "no"}`);
  return { id: doc.id };
});
