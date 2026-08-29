import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

const REGION = "africa-south1";

/**
 * What Google is actually going to charge us, measured rather than assumed.
 *
 * The problem this exists to solve: our monthly cap is denominated in AUDIO SECONDS, and it
 * is tempting to reason about cost the same way — "a minute of speech costs a minute's worth
 * of transcription". That is wrong by roughly a factor of two, and every term it misses
 * pushes the same direction (under-estimating):
 *
 *   - One note is not one model call. Transcription, formalization and action-item
 *     extraction are three, and a note whose markdown comes back malformed makes a fourth.
 *     Only the first of them has anything to do with the length of the audio.
 *   - Audio input bills at 3.3x the text rate on 2.5-flash ($1.00 vs $0.30 per 1M), so
 *     modality has to be split out. A token is not a token.
 *   - Output bills at $2.50/1M — over twice the audio input rate. The transcript that comes
 *     BACK can cost more than the audio we sent.
 *   - Thinking tokens bill as output and are reported in neither the prompt nor the
 *     candidates count. They are invisible in the response body and land on the invoice.
 *
 * So this module counts TOKENS, not money, and prices them at read time. Two reasons: token
 * counts are exact integers straight from the provider and accumulate without float drift,
 * and pricing a stored count at read time means a rate change (or a correction to the table
 * below) re-prices history, instead of leaving a month recorded at a price that no longer
 * exists anywhere.
 */

// ---------------------------------------------------------------------- pricing

export interface ModelPrice {
  textIn: number;
  audioIn: number;
  out: number;
}

/**
 * USD per 1M tokens, paid tier, from https://ai.google.dev/gemini-api/docs/pricing
 * as read on 2026-08-29. Some rates on that page change on 2027-01-01; when they do, this
 * table is the only thing that needs editing and past months re-price themselves.
 *
 * audioIn is kept apart from textIn deliberately: on 2.5-flash they differ by 3.3x, which
 * is more than enough to make a blended rate worse than no estimate at all.
 */
export const PRICES: Record<string, ModelPrice> = {
  "gemini-2.5-flash": { textIn: 0.3, audioIn: 1.0, out: 2.5 },
  "gemini-3.5-transcribe": { textIn: 2.0, audioIn: 2.0, out: 12.0 },
};

/** Tokens seen for one (model, operation) pair within one billing cycle. */
export interface TokenCounts {
  calls: number;
  textIn: number;
  audioIn: number;
  out: number;
}

/**
 * Price a bucket of tokens, in USD.
 *
 * An unknown model prices at 0 rather than throwing: a meter that takes down the screen it
 * is drawn on because someone swapped a model id is worse than one reading low. Callers
 * surface unpriced models by name so the hole is visible rather than silently absorbed.
 */
export function costOf(model: string, t: TokenCounts): number {
  const p = PRICES[model];
  if (!p) return 0;
  return (t.textIn * p.textIn + t.audioIn * p.audioIn + t.out * p.out) / 1_000_000;
}

// ------------------------------------------------------------------- recording

/** Firestore map keys cannot carry dots the way model ids do. */
function keyOf(model: string): string {
  return model.replace(/\./g, "_");
}

function unkeyOf(key: string): string {
  return key.replace(/_/g, ".");
}

export function cycleOf(d: Date = new Date()): string {
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

/**
 * Pull billable token counts out of a response's usageMetadata.
 *
 * Exported for the tests, because this is the part most likely to go quietly wrong: the
 * shape is provider-defined, every field on it is optional, and each one we fail to read
 * reads as zero cost rather than as an error.
 */
export function tokensFrom(meta: any): TokenCounts {
  const promptTotal = Number(meta?.promptTokenCount ?? 0) || 0;
  const details: any[] = Array.isArray(meta?.promptTokensDetails)
    ? meta.promptTokensDetails
    : [];

  let audioIn = 0;
  for (const d of details) {
    if (String(d?.modality ?? "").toUpperCase() === "AUDIO") {
      audioIn += Number(d?.tokenCount ?? 0) || 0;
    }
  }

  // Everything the modality breakdown didn't attribute to audio is charged as text. That
  // includes the remainder when the details don't add up to promptTokenCount, and the whole
  // prompt when the breakdown is missing entirely — which is correct for our text-only
  // calls, and for an audio call with a truncated response is an under-read we would rather
  // have than an invented one. Clamped at 0 so a details array that over-counts the prompt
  // can never push text negative and quietly subtract from the bill.
  const textIn = Math.max(0, promptTotal - audioIn);

  // Thinking tokens bill at the output rate and are reported apart from the candidates
  // count. Omitting them is the largest silent under-read available here.
  const out =
    (Number(meta?.candidatesTokenCount ?? 0) || 0) +
    (Number(meta?.thoughtsTokenCount ?? 0) || 0);

  return { calls: 1, textIn, audioIn, out };
}

/**
 * The counter is spread over this many documents.
 *
 * Every model call increments the cycle's total, and one note makes three or four of them.
 * A single document would be a textbook hot spot — Firestore sustains roughly one write per
 * second to one document, so a handful of concurrent transcriptions would put the meter into
 * contention and start losing writes to retries. Ten shards is far more headroom than this
 * app needs and costs ten document reads on a screen that is opened by hand.
 */
const SHARDS = 10;

function shardRef(cycle: string, shard: number) {
  return admin
    .firestore()
    .collection("costMeter")
    .doc(cycle)
    .collection("shards")
    .doc(String(shard));
}

/**
 * Add one model call to the cycle's running total.
 *
 * Best-effort, and never throws. This is instrumentation sitting on the transcription path:
 * if the meter cannot be written, the note the user is waiting for still has to arrive. A
 * failure here is logged and dropped — an unmetered call is a wrong number on a debug
 * screen, a thrown one is a lost recording.
 */
export async function recordModelUsage(
  op: string,
  model: string,
  meta: any,
): Promise<void> {
  try {
    const t = tokensFrom(meta);
    const inc = admin.firestore.FieldValue.increment;
    const shard = Math.floor(Math.random() * SHARDS);
    await shardRef(cycleOf(), shard).set(
      {
        updatedAt: Date.now(),
        models: {
          [keyOf(model)]: {
            [op]: {
              calls: inc(t.calls),
              textIn: inc(t.textIn),
              audioIn: inc(t.audioIn),
              out: inc(t.out),
            },
          },
        },
      },
      { merge: true },
    );
  } catch (e: any) {
    console.error("cost meter write failed", { op, model, message: e?.message });
  }
}

// ---------------------------------------------------------------------- reading

/**
 * Fully-qualified BigQuery billing export table, e.g.
 *   yap-mehdiacho.billing_export.gcp_billing_export_v1_0123AB_4567CD_89EFGH
 *
 * Unset until the export is switched on (Cloud Console -> Billing -> Billing export ->
 * BigQuery export), then put it in functions/.env as
 *
 *     BILLING_BQ_TABLE=yap-mehdiacho.billing_export.gcp_billing_export_v1_XXXXXX
 *
 * There is no Google API that returns accrued spend; the export is the only programmatic
 * source of what will actually be invoiced. With this unset the meter reports the projection
 * alone and says why, rather than showing a zero that reads exactly like "we owe nothing".
 *
 * Read straight from the environment rather than through defineString: this is optional
 * configuration, and a declared param with no value makes `firebase deploy` stop and demand
 * one. An unset billing table should degrade the meter, never block a deployment.
 */
function billingTable(): string {
  return (process.env.BILLING_BQ_TABLE ?? "").trim();
}

interface ServiceCost {
  service: string;
  cost: number;
}

interface ActualSpend {
  total: number;
  currency: string;
  byService: ServiceCost[];
  /** Epoch ms of the newest row in the export. The comparison is meaningless without it. */
  asOf: number | null;
}

/**
 * What Google says we have accrued this invoice month.
 *
 * Billing export lands throughout the day, but it lags — hours, sometimes longer. That lag
 * is the reason asOf is returned and shown: a live projection held up against a stale actual
 * shows a gap that is entirely the lag, and it reads as "we are under-estimating" when what
 * it means is "the last few hours haven't been exported yet".
 */
async function fetchActual(table: string, cycle: string): Promise<ActualSpend> {
  // Imported lazily so this module still loads — and the projection still works — on a
  // deployment where the BigQuery client isn't installed.
  const { BigQuery } = await import("@google-cloud/bigquery");
  const bq = new BigQuery();

  // invoice.month is the billing month as YYYYMM: the invoice's own boundaries, which are
  // what we are trying to predict. A window derived from usage_start_time instead would
  // drift from the invoice by the export's own timezone handling.
  const invoiceMonth = cycle.replace("-", "");
  const [rows] = await bq.query({
    query: `
      SELECT
        service.description AS service,
        SUM(cost) + SUM(IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0)) AS cost,
        ANY_VALUE(currency) AS currency,
        MAX(export_time) AS export_time
      FROM \`${table}\`
      WHERE invoice.month = @invoiceMonth
      GROUP BY service
      ORDER BY cost DESC
    `,
    params: { invoiceMonth },
  });

  const byService: ServiceCost[] = rows.map((r: any) => ({
    service: String(r.service ?? "unknown"),
    cost: Number(r.cost ?? 0),
  }));
  const asOf = rows
    .map((r: any) => r.export_time?.value ?? r.export_time)
    .filter(Boolean)
    .map((v: any) => new Date(v).getTime())
    .filter((n: number) => Number.isFinite(n))
    .sort((a: number, b: number) => b - a)[0];

  return {
    total: byService.reduce((s, r) => s + r.cost, 0),
    currency: String(rows[0]?.currency ?? "USD"),
    byService,
    asOf: asOf ?? null,
  };
}

/**
 * getCostMeter — the projection, the invoice, and the gap between them.
 *
 * Admin-only. This is whole-project spend rather than the caller's own usage: it is a number
 * about the business, and any signed-in user able to read it could infer our margins and our
 * total traffic from it.
 *
 * Response: { cycle, projected, actual | null, actualReason, unpricedModels }
 */
export const getCostMeter = onCall(
  { region: REGION, timeoutSeconds: 60 },
  async (request: CallableRequest) => {
    if (request.auth?.token?.admin !== true) {
      throw new HttpsError("permission-denied", "Admin only.");
    }
    const cycle =
      typeof request.data?.cycle === "string" && request.data.cycle
        ? String(request.data.cycle)
        : cycleOf();

    // Sum the shards back into one view. Ten reads, once, on a screen opened by hand.
    const shards = await admin
      .firestore()
      .collection("costMeter")
      .doc(cycle)
      .collection("shards")
      .get();
    const models: Record<string, Record<string, TokenCounts>> = {};
    for (const doc of shards.docs) {
      const docModels = (doc.data()?.models ?? {}) as Record<
        string,
        Record<string, Partial<TokenCounts>>
      >;
      for (const [mKey, ops] of Object.entries(docModels)) {
        const into = (models[mKey] ??= {});
        for (const [op, raw] of Object.entries(ops ?? {})) {
          const acc = (into[op] ??= { calls: 0, textIn: 0, audioIn: 0, out: 0 });
          acc.calls += Number(raw?.calls ?? 0) || 0;
          acc.textIn += Number(raw?.textIn ?? 0) || 0;
          acc.audioIn += Number(raw?.audioIn ?? 0) || 0;
          acc.out += Number(raw?.out ?? 0) || 0;
        }
      }
    }

    const perOp: Array<{ model: string; op: string; usd: number } & TokenCounts> = [];
    const unpricedModels: string[] = [];
    let projected = 0;
    let calls = 0;
    let audioTokens = 0;

    for (const [mKey, ops] of Object.entries(models)) {
      const model = unkeyOf(mKey);
      if (!PRICES[model] && !unpricedModels.includes(model)) unpricedModels.push(model);
      for (const [op, t] of Object.entries(ops ?? {})) {
        const usd = costOf(model, t);
        projected += usd;
        calls += t.calls;
        audioTokens += t.audioIn;
        perOp.push({ model, op, usd, ...t });
      }
    }
    perOp.sort((a, b) => b.usd - a.usd);

    // 32 audio tokens per second is what makes tokens and minutes comparable at all — it is
    // the bridge between this meter and the quota figure the user actually sees.
    const audioMinutes = audioTokens / 32 / 60;

    let actual: ActualSpend | null = null;
    let actualReason: string | null = null;
    const table = billingTable();
    if (!table) {
      actualReason =
        "BigQuery billing export not configured. Cloud Console -> Billing -> Billing export, " +
        "then set BILLING_BQ_TABLE to the exported table.";
    } else {
      try {
        actual = await fetchActual(table, cycle);
      } catch (e: any) {
        console.error("billing export query failed", { table, message: e?.message });
        actualReason = `Billing export unreadable: ${e?.message ?? "unknown error"}`;
      }
    }

    return {
      cycle,
      projected: {
        usd: projected,
        calls,
        audioMinutes,
        perOp: perOp.slice(0, 12),
      },
      actual: actual
        ? {
            usd: actual.total,
            currency: actual.currency,
            asOf: actual.asOf,
            byService: actual.byService.slice(0, 8),
          }
        : null,
      actualReason,
      unpricedModels,
    };
  },
);
