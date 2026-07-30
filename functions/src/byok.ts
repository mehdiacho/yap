import { onCall, HttpsError } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import * as admin from "firebase-admin";
import * as crypto from "crypto";
import { GoogleGenAI } from "@google/genai";
import { getEntitlement, Entitlement } from "./accounts";
import { classifyProviderError, providerDetail } from "./providerErrors";

const REGION = "africa-south1";

/**
 * Wrapping key for stored user API keys. 32 random bytes, base64.
 * Set with:  openssl rand -base64 32 | firebase functions:secrets:set BYOK_ENC_KEY
 */
export const BYOK_ENC_KEY = defineSecret("BYOK_ENC_KEY");

/**
 * Where a user's own provider key lives: `byokKeys/{uid}`, ciphertext only.
 *
 * Two independent barriers, because this is the one collection in the project holding a
 * credential that can spend someone's money:
 *   1. No `match` in firestore.rules → clients cannot read it at all (rules default-deny).
 *      Note the key is never returned by any callable either, not even to its owner.
 *   2. AES-256-GCM under a Secret Manager key → a Firestore export, a backup, or a rules
 *      regression still yields nothing usable without a separate secret.
 * The readable half (`byokKeyHint`, last 4 chars) goes on `entitlements/{uid}`, which the
 * client may already read, so the UI can say "key set" without the key ever coming back.
 */
const KEYS = "byokKeys";

interface StoredKey {
  provider: string;
  ciphertext: string;
  hint: string;
  updatedAt: number;
}

// --------------------------------------------------------------------------- crypto

function wrappingKey(): Buffer {
  const raw = BYOK_ENC_KEY.value();
  const key = Buffer.from(raw, "base64");
  if (key.length !== 32) {
    // Fail loudly at use time rather than silently storing something we can't decrypt.
    throw new Error(`BYOK_ENC_KEY must be 32 bytes base64 (got ${key.length})`);
  }
  return key;
}

/** `iv.tag.ciphertext`, all base64. Random IV per write — never reuse one under GCM. */
function encrypt(plain: string): string {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", wrappingKey(), iv);
  const ct = Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]);
  return [iv, cipher.getAuthTag(), ct].map((b) => b.toString("base64")).join(".");
}

function decrypt(packed: string): string {
  const [ivB64, tagB64, ctB64] = packed.split(".");
  if (!ivB64 || !tagB64 || !ctB64) throw new Error("malformed stored key");
  const decipher = crypto.createDecipheriv(
    "aes-256-gcm",
    wrappingKey(),
    Buffer.from(ivB64, "base64")
  );
  decipher.setAuthTag(Buffer.from(tagB64, "base64"));
  return Buffer.concat([
    decipher.update(Buffer.from(ctB64, "base64")),
    decipher.final(),
  ]).toString("utf8");
}

// ------------------------------------------------------------------------ resolution

export interface ResolvedKey {
  apiKey: string;
  /** True when this is the user's own key: they pay the provider, so we charge no quota. */
  byok: boolean;
}

/**
 * Which key should this request run on?
 *
 * BYOK applies only when the entitlement is unlocked AND a key is actually stored. Anything
 * that goes wrong reading it falls back to the shared key: a user whose stored key can't be
 * decrypted should get a working transcription, not an outage.
 */
export async function resolveGeminiKey(
  uid: string,
  entitlement: Entitlement,
  sharedKey: string
): Promise<ResolvedKey> {
  if (!entitlement.byokUnlocked) return { apiKey: sharedKey, byok: false };
  try {
    const snap = await admin.firestore().collection(KEYS).doc(uid).get();
    if (!snap.exists) return { apiKey: sharedKey, byok: false };
    const stored = snap.data() as StoredKey;
    const apiKey = decrypt(stored.ciphertext);
    return apiKey ? { apiKey, byok: true } : { apiKey: sharedKey, byok: false };
  } catch (e: any) {
    console.error("byok: could not resolve stored key, using shared", {
      uid,
      message: e?.message,
    });
    return { apiKey: sharedKey, byok: false };
  }
}

// ------------------------------------------------------------------------- callables

/**
 * setByokKey — validate a user-supplied Gemini key, then store it encrypted.
 *
 * Validation is a real (tiny) generateContent call, not a regex. A key can be well-formed
 * and still be revoked, restricted to other APIs, or attached to an empty billing account —
 * all of which would otherwise surface much later as a failed recording. Proving it can
 * complete a request now is the whole point of the green tick in the UI.
 */
export const setByokKey = onCall(
  { region: REGION, secrets: [BYOK_ENC_KEY], timeoutSeconds: 60 },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
    const uid = request.auth.uid;
    const apiKey = String(request.data?.apiKey ?? "").trim();
    const provider = String(request.data?.provider ?? "gemini").toLowerCase();

    if (!apiKey) throw new HttpsError("invalid-argument", "Enter your API key.");
    if (apiKey.length > 400) throw new HttpsError("invalid-argument", "That doesn't look like an API key.");
    if (provider !== "gemini") {
      throw new HttpsError("invalid-argument", `${provider} keys aren't supported yet — Gemini only.`);
    }

    const entitlement = await getEntitlement(uid);
    if (!entitlement.byokUnlocked) {
      throw new HttpsError("permission-denied", "Unlock BYOK before adding your own key.");
    }

    try {
      const ai = new GoogleGenAI({ apiKey });
      await ai.models.generateContent({
        model: "gemini-2.5-flash",
        contents: "ok",
      });
    } catch (e: any) {
      const failure = classifyProviderError(e, true);
      // Log what Google actually said, not just our verdict. Logging the verdict alone made
      // a rejected-key report undiagnosable without asking the user to reproduce it.
      console.warn("byok: key validation failed", {
        uid,
        reason: failure.reason,
        providerMessage: providerDetail(e),
      });
      // Every branch already carries advice specific to the cause (bad key vs API not
      // enabled vs restricted key vs unfunded project); don't flatten them back into one.
      throw new HttpsError(
        failure.reason === "PROVIDER_AUTH" ? "invalid-argument" : "failed-precondition",
        failure.message
      );
    }

    const hint = apiKey.slice(-4);
    const stored: StoredKey = {
      provider,
      ciphertext: encrypt(apiKey),
      hint,
      updatedAt: Date.now(),
    };
    await admin.firestore().collection(KEYS).doc(uid).set(stored);
    await admin.firestore().collection("entitlements").doc(uid).set(
      { byokActive: true, byokProvider: provider, byokKeyHint: hint, byokKeySetAt: stored.updatedAt },
      { merge: true }
    );

    console.log("byok: key stored", { uid, provider });
    return { ok: true, provider, keyHint: hint };
  }
);

/** clearByokKey — remove the stored key; transcription falls back to the shared key. */
export const clearByokKey = onCall({ region: REGION }, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
  const uid = request.auth.uid;
  await admin.firestore().collection(KEYS).doc(uid).delete();
  await admin.firestore().collection("entitlements").doc(uid).set(
    {
      byokActive: false,
      byokProvider: admin.firestore.FieldValue.delete(),
      byokKeyHint: admin.firestore.FieldValue.delete(),
      byokKeySetAt: admin.firestore.FieldValue.delete(),
    },
    { merge: true }
  );
  return { ok: true };
});

/**
 * getByokStatus — what the key screen renders on open. Returns the hint only; the key
 * itself is write-only by design and never leaves the server once stored.
 */
export const getByokStatus = onCall({ region: REGION }, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
  const uid = request.auth.uid;
  const entitlement = await getEntitlement(uid);
  const snap = await admin.firestore().collection(KEYS).doc(uid).get();
  const stored = snap.exists ? (snap.data() as StoredKey) : null;
  return {
    unlocked: entitlement.byokUnlocked,
    active: !!stored,
    provider: stored?.provider ?? null,
    keyHint: stored?.hint ?? null,
    updatedAt: stored?.updatedAt ?? null,
  };
});
