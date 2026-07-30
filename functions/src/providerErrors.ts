/**
 * Classifying failures coming back from the model provider.
 *
 * Every provider failure used to collapse into `Transcription failed: <raw>`, and the note
 * just went FAILED. That is how a depleted Gemini prepay balance read as "the app is
 * broken" for two days: an 80-second recording and a 31-minute one produced the identical
 * dead end, so there was nothing to distinguish "top up your account" from "this audio is
 * bad" or "the model is overloaded".
 *
 * The distinctions that matter to a user are: is this my fault, is it worth retrying, and
 * is there something I can do right now.
 */

export type ProviderFailure =
  | "PROVIDER_CREDIT" // billing balance exhausted — retry works only after a top-up
  | "PROVIDER_RATE_LIMIT" // throttled — the same request will succeed shortly
  | "PROVIDER_AUTH" // key invalid, revoked, or lacking access to the model
  | "PROVIDER_API_DISABLED" // key is fine; its project hasn't enabled the API
  | "PROVIDER_KEY_RESTRICTED" // key is fine; its restrictions forbid server-side calls
  | "PROVIDER_UNAVAILABLE" // 5xx / overloaded — transient, not the caller's doing
  | "AUDIO_REJECTED" // the provider refused this specific audio (format, length, safety)
  | "UNKNOWN";

export interface ClassifiedFailure {
  reason: ProviderFailure;
  /** Shown to the user verbatim. Written for someone who did not read a stack trace. */
  message: string;
  /** Whether pressing "Try again" without changing anything could plausibly work. */
  retryable: boolean;
  /** Callable error code. Deliberately never "resource-exhausted" — clients map that to
   *  the user's own monthly plan cap, which would be an outright lie here. */
  httpsCode: "unavailable" | "failed-precondition" | "invalid-argument" | "internal";
}

/**
 * The @google/genai SDK throws with the raw HTTP body as the message, e.g.
 * `{"error":{"code":429,"message":"...","status":"RESOURCE_EXHAUSTED"}}`. Sometimes the
 * status/code also land on the error object. Read both and prefer whichever is present.
 */
function extract(e: any): { code: number; status: string; text: string } {
  const raw = String(e?.message ?? e ?? "");
  let code = typeof e?.status === "number" ? e.status : 0;
  let status = typeof e?.status === "string" ? e.status : "";
  try {
    const start = raw.indexOf("{");
    if (start >= 0) {
      const parsed = JSON.parse(raw.slice(start));
      const err = parsed?.error ?? parsed;
      if (typeof err?.code === "number") code = err.code;
      if (typeof err?.status === "string") status = err.status;
    }
  } catch {
    // Not JSON — fall back to substring matching on the raw text below.
  }
  return { code, status: status.toUpperCase(), text: raw.toLowerCase() };
}

/**
 * Google's own one-line explanation, cleaned up for display.
 *
 * When we can't map a failure to specific advice, the provider's wording beats anything we
 * could invent — the user owns that Cloud project and can act on what Google tells them.
 * Falls back to the raw text, trimmed, so this never renders as "undefined".
 */
export function providerDetail(e: any): string {
  const raw = String(e?.message ?? e ?? "").trim();
  try {
    const start = raw.indexOf("{");
    if (start >= 0) {
      const parsed = JSON.parse(raw.slice(start));
      const msg = parsed?.error?.message ?? parsed?.message;
      if (typeof msg === "string" && msg.trim()) return truncate(msg.trim());
    }
  } catch {
    // Not JSON; fall through to the raw text.
  }
  return truncate(raw) || "no reason given";
}

const truncate = (s: string, max = 300): string =>
  s.length > max ? `${s.slice(0, max - 1)}…` : s;

/**
 * @param byok true when the request ran on the user's OWN provider key. The remedy for a
 *   billing failure is then theirs to action directly, so say so plainly instead of the
 *   vaguer "the service is unavailable" that fits a shared key.
 */
export function classifyProviderError(e: any, byok = false): ClassifiedFailure {
  const { code, status, text } = extract(e);

  const saysCredit =
    text.includes("prepayment") ||
    text.includes("credits are depleted") ||
    text.includes("billing") ||
    text.includes("quota exceeded") ||
    text.includes("exceeded your current quota");
  const saysRate =
    text.includes("rate limit") ||
    text.includes("too many requests") ||
    text.includes("requests per minute");

  // Checked BEFORE the numeric codes: Gemini reports an invalid API key as 400
  // INVALID_ARGUMENT, not 401/403, so code alone would file it under "bad audio" and tell
  // someone who just mistyped their key that their recording was unreadable.
  const saysBadKey =
    text.includes("api key not valid") ||
    text.includes("api_key_invalid") ||
    text.includes("invalid api key") ||
    text.includes("api key expired");

  // A brand-new Google Cloud / AI Studio project does not have the Generative Language API
  // turned on, and a key from it fails with 403 SERVICE_DISABLED. This is the single most
  // likely reason a key the user believes is fine gets refused, and it has nothing to do
  // with the key being mistyped — lumping it under "bad key" sent people to re-copy a
  // string that was already correct.
  const saysApiDisabled =
    text.includes("has not been used in project") ||
    text.includes("is disabled") ||
    text.includes("service_disabled") ||
    text.includes("enable it by visiting");
  if (saysApiDisabled) {
    return {
      reason: "PROVIDER_API_DISABLED",
      message: byok
        ? "That key is valid, but the Generative Language API isn't enabled on its Google " +
          "Cloud project. Enable it (or create the key from Google AI Studio, which enables " +
          "it for you), then try again."
        : "Transcription is misconfigured on our side. We've been notified.",
      retryable: false,
      httpsCode: "failed-precondition",
    };
  }

  // Application restrictions (HTTP referrer / Android / iOS / IP allowlist) or an API
  // allowlist that omits generativelanguage. The key is real; it just won't accept a call
  // from a server. Distinct advice again — nothing to re-copy.
  const saysRestricted =
    text.includes("referer") ||
    text.includes("referrer") ||
    text.includes("blocked") ||
    text.includes("not authorized to use") ||
    text.includes("api_key_service_blocked") ||
    text.includes("api_key_http_referrer_blocked") ||
    text.includes("api_key_ip_address_blocked") ||
    text.includes("api_key_android_app_blocked");
  if (saysRestricted) {
    return {
      reason: "PROVIDER_KEY_RESTRICTED",
      message: byok
        ? "That key has restrictions that block server-side use. In Google Cloud, set its " +
          "Application restriction to None and allow the Generative Language API, then try again."
        : "Transcription is misconfigured on our side. We've been notified.",
      retryable: false,
      httpsCode: "failed-precondition",
    };
  }

  if (saysBadKey || text.includes("permission denied") || status === "PERMISSION_DENIED") {
    return {
      reason: "PROVIDER_AUTH",
      message: byok
        ? saysBadKey
          ? "That key was rejected by Google. Check you copied all of it."
          : // Not a malformed key — Google refused it for a reason only its project owner
            // can see, so hand over what Google actually said rather than guessing.
            `Google refused that key: ${providerDetail(e)}`
        : "Transcription is misconfigured on our side. We've been notified.",
      retryable: false,
      httpsCode: "failed-precondition",
    };
  }

  // 429 covers both "you owe money" and "you're going too fast" — opposite remedies, so
  // split on the body text and treat an ambiguous 429 as a billing problem. Telling a user
  // to wait when their balance is empty wastes their time; the reverse merely looks silly.
  if (code === 429 || status === "RESOURCE_EXHAUSTED") {
    if (saysRate && !saysCredit) {
      return {
        reason: "PROVIDER_RATE_LIMIT",
        message: "The AI service is busy right now. Try again in a minute.",
        retryable: true,
        httpsCode: "unavailable",
      };
    }
    return {
      reason: "PROVIDER_CREDIT",
      message: byok
        ? "Your Gemini API key is out of credit. Top it up in Google AI Studio, then " +
          "tap Try again — your recording is safe."
        : "Transcription is temporarily unavailable (the AI service account needs " +
          "topping up). Your recording is safe — try again shortly.",
      retryable: true,
      httpsCode: "unavailable",
    };
  }

  if (code === 401 || code === 403 || status === "UNAUTHENTICATED" || status === "PERMISSION_DENIED") {
    return {
      reason: "PROVIDER_AUTH",
      message: byok
        ? "Your Gemini API key was rejected. Check it in Settings → API key."
        : "Transcription is misconfigured on our side. We've been notified.",
      // Not retryable: the same key will be rejected again until someone changes it.
      retryable: false,
      httpsCode: "failed-precondition",
    };
  }

  if (code >= 500 || status === "UNAVAILABLE" || status === "INTERNAL" || text.includes("overloaded")) {
    return {
      reason: "PROVIDER_UNAVAILABLE",
      message: "The AI service is having trouble. Your recording is safe — try again shortly.",
      retryable: true,
      httpsCode: "unavailable",
    };
  }

  if (code === 400 || status === "INVALID_ARGUMENT") {
    return {
      reason: "AUDIO_REJECTED",
      message: "The AI service couldn't read that audio. The recording is still saved.",
      retryable: false,
      httpsCode: "invalid-argument",
    };
  }

  return {
    reason: "UNKNOWN",
    message: "Transcription failed. Your recording is safe — try again.",
    retryable: true,
    httpsCode: "internal",
  };
}
