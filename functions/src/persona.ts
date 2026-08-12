import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { defineSecret } from "firebase-functions/params";
import { GoogleGenAI } from "@google/genai";
import { BYOK_ENC_KEY, resolveGeminiKey } from "./byok";
import { getEntitlement } from "./accounts";

const REGION = "africa-south1";
const MODEL = "gemini-2.5-flash";
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");

/** Regenerating on every screen open would spend a model call to say the same thing. */
const CACHE_MS = 7 * 24 * 60 * 60 * 1000;

function requireAuth(request: CallableRequest): string {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
  return request.auth.uid;
}

/**
 * A short read on how someone uses Yap, from their usage shape alone.
 *
 * ## What the model is given
 *
 * Counts, durations, times of day, and topic *labels* — never note text. The client assembles
 * this in `UsageInsights.personaSummary`. That boundary is deliberate: a persona is a
 * description of behaviour, and shipping the contents of someone's private notes to generate
 * one would be a far larger disclosure than the feature needs, for no better result.
 *
 * ## Why it is cached for a week
 *
 * The input barely moves. Two more notes will not change "records in short bursts on weekday
 * mornings", so regenerating on every screen open would spend a model call per visit to
 * re-derive the same paragraph. The client can force a refresh; the cache is the default.
 */
export const getUsagePersona = onCall(
  { region: REGION, secrets: [GEMINI_API_KEY, BYOK_ENC_KEY], timeoutSeconds: 60 },
  async (request) => {
    const uid = requireAuth(request);
    const summary = request.data?.summary;
    const force = request.data?.refresh === true;

    if (!summary || typeof summary !== "object") {
      throw new HttpsError("invalid-argument", "Usage summary required.");
    }
    const totalNotes = Number(summary.totalNotes ?? 0);
    if (totalNotes < 3) {
      throw new HttpsError("failed-precondition", "Not enough notes yet.");
    }

    const db = admin.firestore();
    const ref = db.collection("profiles").doc(uid);
    const snap = await ref.get();
    const cached = snap.get("persona");
    const cachedAt = snap.get("personaAt");
    const cachedNotes = snap.get("personaNotes");

    // Reuse unless it has gone stale by time, or the library has grown enough that the shape
    // could genuinely have changed. 50% more notes is the threshold — a handful is noise.
    const fresh =
      typeof cached === "string" &&
      typeof cachedAt === "number" &&
      Date.now() - cachedAt < CACHE_MS &&
      totalNotes < (cachedNotes ?? 0) * 1.5;
    if (fresh && !force) {
      return { persona: cached, generatedAt: cachedAt, cached: true };
    }

    // Runs on the user's own key when they have one — the persona is their data, and a
    // BYOK user is not spending our quota on anything else either.
    const { apiKey } = await resolveGeminiKey(
      uid,
      await getEntitlement(uid),
      GEMINI_API_KEY.value(),
    );
    const ai = new GoogleGenAI({ apiKey });

    const prompt = `You are describing how one person uses a voice-notes app, based only on
usage statistics. You have NOT seen any of their notes — only counts, durations, times, and
topic labels.

### Their usage
${JSON.stringify(summary, null, 2)}

### Write
Two or three sentences, second person ("You..."), plain and specific. Name the concrete
pattern you can actually see in the numbers: when they record, how long for, whether it is
bursty or steady, what they keep coming back to.

### Rules
- **Never quote a figure that is already on the screen.** Every number in the JSON above is
  displayed next to this paragraph. Writing "you recorded 12 notes totalling 40 minutes over
  6 active days" tells the reader nothing they cannot already see, and is the single most
  common way this comes out worthless. Describe the SHAPE — bursty or steady, early or late,
  narrow or scattered, growing or tailing off — not the values.
- At most one number, and only when it is the point of the sentence.
- No flattery, no encouragement, no "keep it up". This is an observation, not a review.
- Do not invent motives or personality traits the numbers cannot support. "You record in
  short bursts before 9am" is supported. "You are a driven early riser" is not.
- If there is genuinely too little history to see a pattern, say that in one sentence and
  stop. A short honest answer beats a padded one.
- No headings, no bullets, no emoji. Prose only.
- Never guess at note content from a topic label.

Return only the sentences.`;

    const result = await ai.models.generateContent({
      model: MODEL,
      contents: prompt,
      config: { temperature: 0.4, maxOutputTokens: 20000 },
    });
    const persona = (result.text ?? "").trim();
    if (!persona) throw new HttpsError("internal", "Couldn't generate a summary right now.");

    const generatedAt = Date.now();
    await ref.set(
      { persona, personaAt: generatedAt, personaNotes: totalNotes },
      { merge: true },
    );
    return { persona, generatedAt, cached: false };
  },
);
