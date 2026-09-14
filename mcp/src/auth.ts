import { readFileSync, writeFileSync, mkdirSync, existsSync, chmodSync } from "node:fs";
import { dirname } from "node:path";
import type { Config } from "./config.js";

export interface StoredCredentials {
  refreshToken: string;
  uid: string;
  email?: string;
  savedAt: string;
}

/**
 * The server holds a refresh token for one ordinary Firebase user — deliberately, instead
 * of an Admin SDK service-account key.
 *
 * An admin key would bypass firestore.rules entirely and could read every user's notes;
 * it would also be a file on disk that grants that. A refresh token grants exactly what
 * the phone app grants the same person, and `firestore.rules` still adjudicates every
 * read. If this file leaks, the blast radius is one account, and revoking it is a
 * password reset rather than a key rotation across a project.
 */
export function loadCredentials(cfg: Config): StoredCredentials {
  if (!existsSync(cfg.credentialsPath)) {
    throw new Error(
      `Not signed in. Run "npm run login" first (looked in ${cfg.credentialsPath}).`,
    );
  }
  return JSON.parse(readFileSync(cfg.credentialsPath, "utf8")) as StoredCredentials;
}

export function saveCredentials(cfg: Config, creds: StoredCredentials): void {
  mkdirSync(dirname(cfg.credentialsPath), { recursive: true });
  writeFileSync(cfg.credentialsPath, JSON.stringify(creds, null, 2), { mode: 0o600 });
  // No-op on Windows ACLs, but correct on anything POSIX and harmless here.
  try {
    chmodSync(cfg.credentialsPath, 0o600);
  } catch {
    /* best effort */
  }
}

interface TokenResponse {
  id_token: string;
  refresh_token: string;
  expires_in: string;
  user_id: string;
}

/**
 * Exchanges the stored refresh token for a short-lived ID token, caching it until just
 * before it expires. One exchange per hour rather than one per tool call.
 */
export class TokenSource {
  private idToken: string | null = null;
  private expiresAt = 0;
  private uid: string | null = null;

  constructor(
    private readonly cfg: Config,
    private readonly creds: StoredCredentials,
  ) {}

  async token(): Promise<{ idToken: string; uid: string }> {
    // 60s of slack: a token that expires mid-request is the same as an expired one.
    if (this.idToken && Date.now() < this.expiresAt - 60_000 && this.uid) {
      return { idToken: this.idToken, uid: this.uid };
    }

    const res = await fetch(
      `https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(this.cfg.apiKey)}`,
      {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "refresh_token",
          refresh_token: this.creds.refreshToken,
        }),
      },
    );

    if (!res.ok) {
      const body = await res.text();
      // TOKEN_EXPIRED / USER_DISABLED / invalid grant all land here, and they all mean the
      // same thing to the user: sign in again. Say that rather than surfacing the raw JSON.
      throw new Error(
        `Could not refresh the Yap sign-in (${res.status}). Run "npm run login" again. ${body}`,
      );
    }

    const json = (await res.json()) as TokenResponse;
    this.idToken = json.id_token;
    this.uid = json.user_id;
    this.expiresAt = Date.now() + Number(json.expires_in) * 1000;
    return { idToken: this.idToken, uid: this.uid };
  }
}
