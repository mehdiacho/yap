import { readFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));

/** Minimal .env reader. No dependency, and no interpolation — these are flat values. */
function readEnvFile(path: string): Record<string, string> {
  if (!existsSync(path)) return {};
  const out: Record<string, string> = {};
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const m = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!m) continue;
    out[m[1]] = m[2].trim().replace(/^["'](.*)["']$/, "$1");
  }
  return out;
}

export interface Config {
  apiKey: string;
  authDomain: string;
  projectId: string;
  credentialsPath: string;
  /**
   * Ceiling on how many notes a single scan will pull. Search and topic listing read the
   * whole library into memory because Firestore has no substring index, so this is the
   * bound on both memory and read cost. A personal note store is nowhere near it.
   */
  maxScan: number;
}

/**
 * Config comes from, in order: the process environment, `mcp/.env`, then the desktop web
 * app's own `.env` next door. The last one is the reason this usually needs no setup —
 * the values are the public Firebase web config, already sitting in the sibling repo,
 * and duplicating them into a second file is just another thing to get out of sync.
 */
export function loadConfig(): Config {
  const local = readEnvFile(join(HERE, "..", ".env"));
  const desktop = readEnvFile(resolve(HERE, "..", "..", "..", "yap-desktop", ".env"));

  const pick = (...names: string[]): string => {
    for (const n of names) {
      const v = process.env[n] ?? local[n] ?? desktop[n];
      if (v) return v;
    }
    return "";
  };

  const apiKey = pick("YAP_FB_API_KEY", "VITE_FB_API_KEY");
  const projectId = pick("YAP_FB_PROJECT_ID", "VITE_FB_PROJECT_ID") || "yap-mehdiacho";
  const authDomain =
    pick("YAP_FB_AUTH_DOMAIN", "VITE_FB_AUTH_DOMAIN") || `${projectId}.firebaseapp.com`;

  if (!apiKey) {
    throw new Error(
      "No Firebase web apiKey found. Set YAP_FB_API_KEY in mcp/.env (copy .env.example), " +
        "or make sure X:\Projects\yap-desktop\.env exists with VITE_FB_API_KEY.",
    );
  }

  return {
    apiKey,
    authDomain,
    projectId,
    credentialsPath:
      process.env.YAP_MCP_CREDENTIALS ?? join(homedir(), ".yap-mcp", "credentials.json"),
    maxScan: Number(process.env.YAP_MCP_MAX_SCAN ?? 2000),
  };
}
