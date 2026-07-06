import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import * as admin from "firebase-admin";

const REGION = "africa-south1";
const REFERRALS_NEEDED = 5;
const QUALIFYING_NOTES = 3;

function requireAuth(request: CallableRequest): string {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
  return request.auth.uid;
}

// ================================================================ delete account

/**
 * Deletes everything belonging to the caller: Firestore user subtree, usage,
 * entitlement, referral docs, Storage audio, then the auth user itself.
 */
export const deleteAccount = onCall(
  { region: REGION, timeoutSeconds: 300, memory: "512MiB" },
  async (request) => {
    const uid = requireAuth(request);
    const db = admin.firestore();

    // Firestore: users/{uid} subtree (sessions etc.), usage, entitlements, referral.
    await db.recursiveDelete(db.collection("users").doc(uid));
    await db.collection("usage").doc(uid).delete().catch(() => undefined);
    await db.collection("entitlements").doc(uid).delete().catch(() => undefined);
    const refDoc = await db.collection("referrals").doc(uid).get();
    if (refDoc.exists) {
      const code = refDoc.get("code");
      if (typeof code === "string") {
        await db.collection("referralCodes").doc(code).delete().catch(() => undefined);
      }
      await refDoc.ref.delete().catch(() => undefined);
    }

    // Storage: all of the user's uploaded audio.
    await admin.storage().bucket().deleteFiles({ prefix: `users/${uid}/` }).catch(() => undefined);

    // Finally the auth user. The client signs out on success.
    await admin.auth().deleteUser(uid);
    return { ok: true };
  },
);

// ================================================================ referrals

function codeFromUid(uid: string): string {
  const clean = uid.replace(/[^a-zA-Z0-9]/g, "").toUpperCase();
  return (clean + "YAP000").slice(0, 6);
}

/**
 * Returns the caller's referral code (created on first call) and progress.
 * referrals/{uid}: { code, qualifiedCount } · referralCodes/{code}: { uid }
 */
export const getReferralInfo = onCall({ region: REGION }, async (request) => {
  const uid = requireAuth(request);
  const db = admin.firestore();
  const mine = db.collection("referrals").doc(uid);
  const snap = await mine.get();

  let code: string;
  if (snap.exists && typeof snap.get("code") === "string") {
    code = snap.get("code");
  } else {
    // Derive from uid; on collision, salt with a counter until free.
    code = codeFromUid(uid);
    for (let i = 0; i < 10; i++) {
      const claim = db.collection("referralCodes").doc(code);
      const existing = await claim.get();
      if (!existing.exists) {
        await claim.set({ uid });
        break;
      }
      if (existing.get("uid") === uid) break;
      code = codeFromUid(uid + String(i));
    }
    await mine.set({ code, qualifiedCount: snap.get("qualifiedCount") ?? 0 }, { merge: true });
  }

  return {
    code,
    qualifiedCount: snap.get("qualifiedCount") ?? 0,
    needed: REFERRALS_NEEDED,
  };
});

/**
 * A new user applies a friend's code. Guards: real code, not self, one per user,
 * account younger than 7 days (anti-gaming). Qualification happens when this user
 * saves their 3rd note (trigger below).
 */
export const applyReferral = onCall({ region: REGION }, async (request) => {
  const uid = requireAuth(request);
  const code = String(request.data?.code ?? "").trim().toUpperCase();
  if (!code) throw new HttpsError("invalid-argument", "Referral code required.");

  const db = admin.firestore();
  const codeSnap = await db.collection("referralCodes").doc(code).get();
  if (!codeSnap.exists) throw new HttpsError("not-found", "That referral code doesn't exist.");
  const referrerUid = codeSnap.get("uid");
  if (referrerUid === uid) throw new HttpsError("failed-precondition", "You can't refer yourself.");

  const user = await admin.auth().getUser(uid);
  const ageMs = Date.now() - new Date(user.metadata.creationTime).getTime();
  if (ageMs > 7 * 86_400_000) {
    throw new HttpsError("failed-precondition", "Referral codes only work for new accounts.");
  }

  const myRef = db.collection("referrals").doc(uid);
  const mine = await myRef.get();
  if (mine.exists && mine.get("referredBy")) {
    throw new HttpsError("already-exists", "A referral code was already applied.");
  }
  await myRef.set({ referredBy: referrerUid, qualified: false }, { merge: true });
  return { ok: true };
});

/**
 * Qualification trigger: when a referred user saves their 3rd session, credit the
 * referrer; at 5 qualified referrals, unlock BYOK on their entitlement.
 */
export const onSessionCreatedReferral = onDocumentCreated(
  { region: REGION, document: "users/{uid}/sessions/{sessionId}" },
  async (event) => {
    const uid = event.params.uid;
    const db = admin.firestore();

    const myRef = db.collection("referrals").doc(uid);
    const mine = await myRef.get();
    const referredBy = mine.exists ? mine.get("referredBy") : undefined;
    if (!referredBy || mine.get("qualified") === true) return;

    const sessions = await db.collection("users").doc(uid).collection("sessions").count().get();
    if (sessions.data().count < QUALIFYING_NOTES) return;

    // Mark qualified + credit the referrer atomically.
    await db.runTransaction(async (tx) => {
      const fresh = await tx.get(myRef);
      if (fresh.get("qualified") === true) return;
      const referrerRef = db.collection("referrals").doc(referredBy);
      const referrer = await tx.get(referrerRef);
      const newCount = (referrer.get("qualifiedCount") ?? 0) + 1;
      tx.set(myRef, { qualified: true }, { merge: true });
      tx.set(referrerRef, { qualifiedCount: newCount }, { merge: true });
      if (newCount >= REFERRALS_NEEDED) {
        tx.set(db.collection("entitlements").doc(referredBy), { byokUnlocked: true }, { merge: true });
      }
    });
  },
);
