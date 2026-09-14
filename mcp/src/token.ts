/**
 * Firebase ID tokens from a refresh token. Only `fetch` and `atob`, so the same code runs
 * in the local Node server and in the Worker.
 */

/** Anything that can hand out a Firebase ID token for one user. */
export interface TokenProvider {
  token(): Promise<{ idToken: string; uid: string }>;
}

export interface FirebaseSession {
  idToken: string;
  uid: string;
  expiresAt: number;
  /** `google.com`, `password`, `anonymous`… from the token's own claims. */
  provider: string | null;
}

/** The refresh token itself was refused — revoked, expired, or the user was disabled. */
export class SignInExpiredError extends Error {}

interface TokenResponse {
  id_token: string;
  refresh_token: string;
  expires_in: string;
  user_id: string;
}

export async function exchangeRefreshToken(
  apiKey: string,
  refreshToken: string,
): Promise<FirebaseSession> {
  const res = await fetch(
    `https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(apiKey)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: refreshToken }),
    },
  );

  if (!res.ok) {
    const body = await res.text();
    // TOKEN_EXPIRED / USER_DISABLED / INVALID_REFRESH_TOKEN all arrive as a 400, and they all
    // mean the same thing to the person: sign in again.
    if (res.status === 400) throw new SignInExpiredError(body);
    throw new Error(`Firebase's token service returned ${res.status}. ${body}`);
  }

  const json = (await res.json()) as TokenResponse;
  return {
    idToken: json.id_token,
    uid: json.user_id,
    expiresAt: Date.now() + Number(json.expires_in) * 1000,
    provider: signInProvider(json.id_token),
  };
}

/**
 * Reads the sign-in provider claim without verifying the signature. That is safe only
 * because of where the token comes from: Google's token endpoint handed it over TLS in
 * exchange for the refresh token, so the thing being verified is the refresh token, and
 * Google just did that.
 */
function signInProvider(idToken: string): string | null {
  try {
    const b64 = idToken.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const claims = JSON.parse(atob(b64 + "=".repeat((4 - (b64.length % 4)) % 4)));
    return claims?.firebase?.sign_in_provider ?? null;
  } catch {
    return null;
  }
}

/**
 * Caches the ID token until just before it expires: one exchange per hour rather than one
 * per tool call.
 */
export class RefreshTokenSource implements TokenProvider {
  private session: FirebaseSession | null = null;

  constructor(
    private readonly apiKey: string,
    private readonly refreshToken: string,
    /** What to tell the person when the sign-in stops working. Differs local vs remote. */
    private readonly reauthHint: string,
  ) {}

  async token(): Promise<{ idToken: string; uid: string }> {
    // 60s of slack: a token that expires mid-request is the same as an expired one.
    if (!this.session || Date.now() >= this.session.expiresAt - 60_000) {
      try {
        this.session = await exchangeRefreshToken(this.apiKey, this.refreshToken);
      } catch (e) {
        if (e instanceof SignInExpiredError) {
          throw new Error(`The Yap sign-in is no longer valid. ${this.reauthHint}`);
        }
        throw e;
      }
    }
    return { idToken: this.session.idToken, uid: this.session.uid };
  }
}
