import type { Config } from "./config.js";
import type { TokenSource } from "./auth.js";

/** A Firestore REST `Value`. Only the shapes Yap actually writes are handled. */
type FsValue = Record<string, unknown>;

export interface FsDocument {
  name: string;
  fields?: Record<string, FsValue>;
}

/** Firestore sends every scalar tagged. Integers arrive as strings — hence the Number(). */
export function decodeValue(v: FsValue | undefined): unknown {
  if (!v) return null;
  if ("nullValue" in v) return null;
  if ("stringValue" in v) return v.stringValue as string;
  if ("booleanValue" in v) return v.booleanValue as boolean;
  if ("integerValue" in v) return Number(v.integerValue);
  if ("doubleValue" in v) return v.doubleValue as number;
  if ("timestampValue" in v) return Date.parse(v.timestampValue as string);
  if ("arrayValue" in v) {
    const arr = (v.arrayValue as { values?: FsValue[] }).values ?? [];
    return arr.map(decodeValue);
  }
  if ("mapValue" in v) {
    const fields = (v.mapValue as { fields?: Record<string, FsValue> }).fields ?? {};
    return decodeFields(fields);
  }
  return null;
}

export function decodeFields(fields: Record<string, FsValue>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(fields)) out[k] = decodeValue(v);
  return out;
}

export interface QueryOptions {
  /** Epoch ms lower bound on createdAt, inclusive. */
  since?: number;
  /** Hard cap on documents returned across all pages. */
  limit: number;
}

/**
 * Reads a page-at-a-time from `users/{uid}/sessions`, newest first.
 *
 * Only two things are pushed to the server: the `createdAt` ordering and an optional
 * `createdAt >=` bound. Both ride the automatic single-field index, so this server needs
 * no new Firestore index deployed — everything else (category, topic, trash, text) is
 * filtered here. That is a deliberate trade: a little more read volume in exchange for
 * never breaking because an index was not deployed alongside the code.
 */
export class SessionReader {
  constructor(
    private readonly cfg: Config,
    private readonly tokens: TokenSource,
  ) {}

  private get base(): string {
    return `https://firestore.googleapis.com/v1/projects/${this.cfg.projectId}/databases/(default)/documents`;
  }

  async query(opts: QueryOptions): Promise<Record<string, unknown>[]> {
    const { idToken, uid } = await this.tokens.token();
    const pageSize = 300;
    const docs: Record<string, unknown>[] = [];
    let offset = 0;

    while (docs.length < opts.limit) {
      const structuredQuery: Record<string, unknown> = {
        from: [{ collectionId: "sessions" }],
        orderBy: [{ field: { fieldPath: "createdAt" }, direction: "DESCENDING" }],
        limit: Math.min(pageSize, opts.limit - docs.length),
        offset,
      };

      if (opts.since !== undefined) {
        structuredQuery.where = {
          fieldFilter: {
            field: { fieldPath: "createdAt" },
            op: "GREATER_THAN_OR_EQUAL",
            value: { integerValue: String(opts.since) },
          },
        };
      }

      const res = await fetch(`${this.base}/users/${uid}:runQuery`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${idToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ structuredQuery }),
      });

      if (!res.ok) throw new Error(await describeError(res));

      const rows = (await res.json()) as { document?: FsDocument }[];
      // runQuery emits a single row with no `document` when the result set is empty.
      const batch = rows.filter((r) => r.document).map((r) => toRecord(r.document!));
      docs.push(...batch);
      if (batch.length < pageSize) break;
      offset += batch.length;
    }

    return docs;
  }

  async get(id: string): Promise<Record<string, unknown> | null> {
    const { idToken, uid } = await this.tokens.token();
    const res = await fetch(`${this.base}/users/${uid}/sessions/${encodeURIComponent(id)}`, {
      headers: { Authorization: `Bearer ${idToken}` },
    });
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(await describeError(res));
    return toRecord((await res.json()) as FsDocument);
  }
}

function toRecord(doc: FsDocument): Record<string, unknown> {
  return { id: doc.name.split("/").pop() ?? "", ...decodeFields(doc.fields ?? {}) };
}

async function describeError(res: Response): Promise<string> {
  const body = await res.text();
  if (res.status === 403) {
    return `Firestore refused the read (403). firestore.rules only allows a user their own notes, so this usually means the signed-in account is not the one that owns them. ${body}`;
  }
  return `Firestore request failed (${res.status}). ${body}`;
}
