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
