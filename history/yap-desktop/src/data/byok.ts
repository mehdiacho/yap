import { httpsCallable } from "firebase/functions";
import { functions } from "../lib/firebase";

/*
 * Bring-your-own-key: the user supplies their own Gemini API key and transcription runs
 * on it instead of the shared one, with no monthly cap (they pay the provider directly).
 *
 * The key is WRITE-ONLY from a client's point of view. `setByokKey` sends it once; nothing
 * ever reads it back, here or on Android. All the UI gets is a last-4 hint, which is enough
 * to answer "is a key set, and is it the one I think it is" without the key existing in a
 * second place it could leak from.
 */

export interface ByokStatus {
  /** The BYOK feature is unlocked on this account (purchase or referrals). */
  unlocked: boolean;
  /** A key is stored and in use. */
  active: boolean;
  provider: string | null;
  keyHint: string | null;
  updatedAt: number | null;
}

export async function getByokStatus(): Promise<ByokStatus> {
  const call = httpsCallable<void, ByokStatus>(functions, "getByokStatus");
  const res = await call();
  return res.data;
}

/**
 * Validate and store a key. The server makes a real (tiny) request against it before
 * saving, so success genuinely means transcription will work — not just that the string
 * had the right shape. Throws with a readable message when the key is rejected or its
 * billing account is empty; those are different problems with different fixes.
 *
 * @returns the last-4 hint of the stored key.
 */
export async function setByokKey(apiKey: string, provider = "gemini"): Promise<string> {
  const call = httpsCallable<
    { apiKey: string; provider: string },
    { ok: boolean; provider: string; keyHint: string }
  >(functions, "setByokKey", { timeout: 60_000 });
  const res = await call({ apiKey, provider });
  return res.data.keyHint;
}

/** Remove the stored key — transcription falls back to the built-in allowance. */
export async function clearByokKey(): Promise<void> {
  const call = httpsCallable<void, { ok: boolean }>(functions, "clearByokKey");
  await call();
}
