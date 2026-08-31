import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

/**
 * Admin-only reads for the dashboard.
 *
 * Everything here is gated on the `admin` custom claim and runs with the admin SDK, which is
 * why none of these collections need a client rule: the dashboard is a normal signed-in web
 * app with no privileged database access of its own. Anything it can see, it sees because a
 * function decided to show it.
 */

const REGION = "africa-south1";

function requireAdmin(request: CallableRequest): void {
  if (request.auth?.token?.admin !== true) {
    throw new HttpsError("permission-denied", "Admin only.");
  }
}

/** Numbers for the dashboard's front page. */
export const adminOverview = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const db = admin.firestore();
  const cycle = new Date().toISOString().slice(0, 7);

  const [newFeedback, totalFeedback, entitlements, users] = await Promise.all([
    db.collection("feedback").where("status", "==", "NEW").count().get(),
    db.collection("feedback").count().get(),
    // Every paying account. Small enough to read whole: entitlements only exist for accounts
    // that have ever had a plan, not for every user.
    db.collection("entitlements").get(),
    // listUsers caps at 1000 per page. One page is the right answer for a project this size,
    // and `more` says plainly when it stops being the whole truth rather than quietly
    // reporting a wrong total.
    admin.auth().listUsers(1000),
  ]);

  const now = Date.now();
  let paying = 0;
  let expired = 0;
  for (const doc of entitlements.docs) {
    const d = doc.data();
    if (!d.plan || d.plan === "free") continue;
    const exp = typeof d.expiresAt === "number" ? d.expiresAt : null;
    if (exp !== null && exp < now) expired++;
    else paying++;
  }

  return {
    cycle,
    users: { total: users.users.length, more: !!users.pageToken },
    // Signed up in the last week. The number that matters while recruiting testers.
    newUsersThisWeek: users.users.filter((u) => {
      const t = Date.parse(u.metadata.creationTime);
      return Number.isFinite(t) && now - t < 7 * 24 * 60 * 60 * 1000;
    }).length,
    feedback: { unread: newFeedback.data().count, total: totalFeedback.data().count },
    subscriptions: { active: paying, expired },
  };
});

/**
 * Feedback, newest first.
 *
 * A voice message is returned as a signed URL rather than a path, so the dashboard can play it
 * without being handed Storage credentials. Signing needs the runtime service account to be
 * able to sign blobs; when it cannot, the row still returns with a reason attached instead of
 * failing the whole listing — a broken player is a smaller problem than a page that will not
 * load.
 */
export const adminListFeedback = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const status = typeof request.data?.status === "string" ? request.data.status : null;
  const limit = Math.min(Number(request.data?.limit ?? 50) || 50, 200);

  let query: admin.firestore.Query = admin.firestore().collection("feedback");
  if (status && status !== "ALL") query = query.where("status", "==", status);
  const snap = await query.orderBy("createdAt", "desc").limit(limit).get();

  const rows = await Promise.all(
    snap.docs.map(async (doc) => {
      const d = doc.data();
      let voiceUrl: string | null = null;
      let voiceError: string | null = null;
      if (d.voicePath) {
        try {
          const bucket = process.env.STORAGE_BUCKET || "yap-mehdiacho.firebasestorage.app";
          const [url] = await admin.storage().bucket(bucket).file(d.voicePath).getSignedUrl({
            action: "read",
            expires: Date.now() + 60 * 60 * 1000,
          });
          voiceUrl = url;
        } catch (e: any) {
          voiceError = e?.message ?? "could not sign URL";
        }
      }
      return { id: doc.id, ...d, voiceUrl, voiceError };
    })
  );

  return { feedback: rows };
});

/** Triage one report. NEW -> READ -> DONE, or back again. */
export const adminUpdateFeedback = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const id = String(request.data?.id ?? "");
  const status = String(request.data?.status ?? "");
  if (!id) throw new HttpsError("invalid-argument", "id required");
  if (!["NEW", "READ", "DONE"].includes(status)) {
    throw new HttpsError("invalid-argument", "status must be NEW, READ or DONE");
  }
  await admin.firestore().collection("feedback").doc(id).set(
    {
      status,
      // Stamped the first time it leaves NEW, and never overwritten — so "how long did this sit
      // unread" stays answerable after the row has been moved around a few times.
      ...(status === "NEW" ? { readAt: null } : { readAt: Date.now() }),
    },
    { merge: true }
  );
  return { ok: true };
});

/**
 * Everyone, newest first.
 *
 * The dashboard needs a list and not just the existing search-by-email: recruiting testers
 * means watching who actually arrives, and you cannot search for an account whose address you
 * do not already know.
 */
export const adminListUsers = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const pageToken = typeof request.data?.pageToken === "string" ? request.data.pageToken : undefined;
  const page = await admin.auth().listUsers(200, pageToken);

  // Entitlements are fetched per user rather than read as a collection: most accounts have no
  // entitlement document at all, and a getAll of only the ids on this page is a far smaller
  // read than pulling every document to join against 200 of them.
  const db = admin.firestore();
  const refs = page.users.map((u) => db.collection("entitlements").doc(u.uid));
  const ents = refs.length ? await db.getAll(...refs) : [];
  const planByUid = new Map(
    ents.filter((d) => d.exists).map((d) => [d.id, (d.data() as any)?.plan ?? "free"])
  );

  return {
    users: page.users.map((u) => ({
      uid: u.uid,
      email: u.email ?? null,
      displayName: u.displayName ?? null,
      created: u.metadata.creationTime,
      lastSignIn: u.metadata.lastSignInTime ?? null,
      disabled: u.disabled,
      plan: planByUid.get(u.uid) ?? "free",
      admin: u.customClaims?.admin === true,
    })),
    pageToken: page.pageToken ?? null,
  };
});

/**
 * Recent notes across all accounts — volume, not content.
 *
 * Deliberately returns no transcript text. Watching that the pipeline is working does not
 * require reading what people said, and a dashboard that quietly exposes every note is a
 * privacy problem waiting for someone else to find.
 */
export const adminRecentActivity = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const limit = Math.min(Number(request.data?.limit ?? 50) || 50, 200);
  const snap = await admin
    .firestore()
    .collectionGroup("sessions")
    .orderBy("createdAt", "desc")
    .limit(limit)
    .get();

  return {
    sessions: snap.docs.map((doc) => {
      const d = doc.data();
      return {
        id: doc.id,
        // users/{uid}/sessions/{id} — the grandparent is the account.
        uid: doc.ref.parent.parent?.id ?? null,
        createdAt: d.createdAt ?? 0,
        durationMs: d.durationMs ?? 0,
        status: d.status ?? "DONE",
        statusReason: d.statusReason ?? null,
        category: d.category ?? null,
        source: d.source ?? "UNKNOWN",
        hasAudio: !!d.storagePath,
      };
    }),
  };
});
