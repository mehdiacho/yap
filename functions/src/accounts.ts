import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

const REGION = "africa-south1";
const DAY_MS = 86_400_000;

// Per-plan monthly transcription caps (seconds). Spec: Free 30 min, Pro 30 h.
export const PLAN_CAPS_SECONDS: Record<string, number> = {
  free: 30 * 60,
  pro: 1800 * 60,
};

export interface Entitlement {
  plan: string;
  byokUnlocked: boolean;
  expiresAt?: number;
}

/** Read the user's entitlement; missing doc or past expiry = free. */
export async function getEntitlement(uid: string): Promise<Entitlement> {
  const snap = await admin.firestore().collection("entitlements").doc(uid).get();
  if (!snap.exists) return { plan: "free", byokUnlocked: false };
  const d = snap.data()!;
  const expired = typeof d.expiresAt === "number" && d.expiresAt < Date.now();
  return {
    plan: expired ? "free" : (d.plan ?? "free"),
    byokUnlocked: !!d.byokUnlocked,
    expiresAt: typeof d.expiresAt === "number" ? d.expiresAt : undefined,
  };
}

export function capForPlan(plan: string): number {
  return PLAN_CAPS_SECONDS[plan] ?? PLAN_CAPS_SECONDS.free;
}

function requireAuth(request: CallableRequest): string {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
  return request.auth.uid;
}

function requireAdmin(request: CallableRequest): void {
  if (request.auth?.token?.admin !== true) {
    throw new HttpsError("permission-denied", "Admin only.");
  }
}

function currentCycle(): string {
  const now = new Date();
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, "0")}`;
}

// ---------------------------------------------------------------- user-facing

export const getAccountStatus = onCall({ region: REGION }, async (request) => {
  const uid = requireAuth(request);
  const ent = await getEntitlement(uid);
  const usageSnap = await admin.firestore().collection("usage").doc(uid).get();
  const cycle = currentCycle();
  const sameCycle = usageSnap.exists && usageSnap.get("cycle") === cycle;
  return {
    plan: ent.plan,
    byokUnlocked: ent.byokUnlocked,
    expiresAt: ent.expiresAt ?? null,
    cycle,
    secondsUsed: sameCycle ? usageSnap.get("secondsUsed") ?? 0 : 0,
    secondsCap: capForPlan(ent.plan),
  };
});

export const redeemPromo = onCall({ region: REGION }, async (request) => {
  const uid = requireAuth(request);
  const code = String(request.data?.code ?? "").trim().toUpperCase();
  if (!code) throw new HttpsError("invalid-argument", "Enter a code.");

  const db = admin.firestore();
  const promoRef = db.collection("promos").doc(code);
  const user = await admin.auth().getUser(uid);
  const createdAt = new Date(user.metadata.creationTime!).getTime();
  const accountAgeDays = (Date.now() - createdAt) / DAY_MS;

  const grant = await db.runTransaction(async (t) => {
    const snap = await t.get(promoRef);
    if (!snap.exists) throw new HttpsError("not-found", "Invalid code.");
    const p = snap.data()!;
    const redemptionRef = promoRef.collection("redemptions").doc(uid);
    const already = await t.get(redemptionRef); // all reads before writes

    if (p.active === false) throw new HttpsError("failed-precondition", "This code is no longer active.");
    if (typeof p.expiresAt === "number" && p.expiresAt < Date.now())
      throw new HttpsError("failed-precondition", "This code has expired.");
    if ((p.redeemedCount ?? 0) >= (p.maxRedemptions ?? 1))
      throw new HttpsError("resource-exhausted", "This code has been fully redeemed.");
    if (already.exists) throw new HttpsError("already-exists", "You've already redeemed this code.");

    const c = p.criteria ?? {};
    if (c.uid && c.uid !== uid)
      throw new HttpsError("permission-denied", "This code is for a different account.");
    if (typeof c.accountAgeMaxDays === "number" && accountAgeDays > c.accountAgeMaxDays)
      throw new HttpsError("failed-precondition", "This code is only for newer accounts.");
    if (typeof c.accountAgeMinDays === "number" && accountAgeDays < c.accountAgeMinDays)
      throw new HttpsError("failed-precondition", "This code is only for accounts older than that.");

    t.set(redemptionRef, { at: Date.now() });
    t.update(promoRef, { redeemedCount: (p.redeemedCount ?? 0) + 1 });

    const g = p.grant ?? {};
    const ent: Record<string, unknown> = {
      source: "promo",
      promoCode: code,
      activatedAt: Date.now(),
    };
    if (g.plan) {
      ent.plan = g.plan;
      if (typeof g.days === "number") ent.expiresAt = Date.now() + g.days * DAY_MS;
    }
    if (g.byokUnlock) ent.byokUnlocked = true;
    t.set(db.collection("entitlements").doc(uid), ent, { merge: true });
    return g;
  });

  return { ok: true, grant };
});

// -------------------------------------------------------------------- admin

export const adminSearchUser = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const q = String(request.data?.query ?? "").trim();
  if (!q) throw new HttpsError("invalid-argument", "query required");
  let user;
  try {
    user = q.includes("@") ? await admin.auth().getUserByEmail(q) : await admin.auth().getUser(q);
  } catch {
    throw new HttpsError("not-found", "No user found.");
  }
  const ent = await getEntitlement(user.uid);
  const usage = await admin.firestore().collection("usage").doc(user.uid).get();
  return {
    uid: user.uid,
    email: user.email ?? null,
    displayName: user.displayName ?? null,
    created: user.metadata.creationTime,
    plan: ent.plan,
    byokUnlocked: ent.byokUnlocked,
    expiresAt: ent.expiresAt ?? null,
    usage: usage.exists ? usage.data() : null,
  };
});

export const adminSetEntitlement = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const { uid, plan, days, byokUnlocked } = request.data ?? {};
  if (!uid || !plan) throw new HttpsError("invalid-argument", "uid and plan required");
  if (!(plan in PLAN_CAPS_SECONDS)) throw new HttpsError("invalid-argument", `Unknown plan '${plan}'.`);
  const doc: Record<string, unknown> = { plan, source: "admin", activatedAt: Date.now() };
  doc.expiresAt = typeof days === "number"
    ? Date.now() + days * DAY_MS
    : admin.firestore.FieldValue.delete();
  if (typeof byokUnlocked === "boolean") doc.byokUnlocked = byokUnlocked;
  await admin.firestore().collection("entitlements").doc(uid).set(doc, { merge: true });
  return { ok: true };
});

export const adminCreatePromo = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const d = request.data ?? {};
  const code = (d.code ? String(d.code) : generateCode()).trim().toUpperCase();
  const ref = admin.firestore().collection("promos").doc(code);
  if ((await ref.get()).exists) throw new HttpsError("already-exists", `Code ${code} already exists.`);
  await ref.set({
    grant: d.grant ?? { plan: "pro", days: 30 },
    criteria: d.criteria ?? {},
    maxRedemptions: d.maxRedemptions ?? 1,
    redeemedCount: 0,
    active: true,
    expiresAt: typeof d.expiresAt === "number" ? d.expiresAt : null,
    createdAt: Date.now(),
  });
  return { code };
});

export const adminListPromos = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const snap = await admin.firestore().collection("promos")
    .orderBy("createdAt", "desc").limit(100).get();
  return { promos: snap.docs.map((doc) => ({ code: doc.id, ...doc.data() })) };
});

function generateCode(): string {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I ambiguity
  let s = "";
  for (let i = 0; i < 8; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}
