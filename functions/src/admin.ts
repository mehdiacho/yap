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
 * Everyone, newest first, with enough attached to tell the accounts apart.
 *
 * The plain list was true and useless. On 2026-09-08 it held 298 accounts, of which 262 were
 * anonymous guests, ~23 were the Google accounts the Play pre-launch report signs in with on
 * its device rack, and 8 in total had ever saved a single note. Finding the four real people
 * in that meant reading uids. So every row now carries where the account came from and what
 * it has actually done, and the dashboard filters on both.
 *
 * The whole list is returned in one call rather than paged. Filtering a page is a lie - a
 * segment count that only covers the first 200 accounts is worse than no count - and at this
 * size the read is cheap. `truncated` says plainly when that stops being true.
 */
const MAX_ACCOUNTS = 5000;
const MAX_SESSION_SCAN = 20000;

/**
 * The Play pre-launch report signs in with generated Google accounts, and they all look
 * alike: a first and last name run together, a dot, then digits. `gilbertogreer.57026@`.
 *
 * This is a heuristic on an address and it can be wrong about a real person, so it never
 * removes anything and never hides a row on its own - it sets a label the dashboard can
 * filter by, and a manual tag on the account overrides it.
 */
const CRAWLER_EMAIL = /^[a-z]+\.\d{4,6}@gmail\.com$/;
/** Addresses used for our own testing. Same rule: a label, not a judgement. */
const TEST_EMAIL = /(^|\+)test|test\d*@|@sharklasers\.com|@test\./i;

export const adminListUsers = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const db = admin.firestore();

  // Every account, walked to the end (or to MAX_ACCOUNTS, whichever comes first).
  const users: admin.auth.UserRecord[] = [];
  let token: string | undefined;
  let truncated = false;
  do {
    const page = await admin.auth().listUsers(1000, token);
    users.push(...page.users);
    token = page.pageToken;
    if (users.length >= MAX_ACCOUNTS) {
      truncated = !!token;
      break;
    }
  } while (token);

  // What each account has actually done. One collection-group scan of the ids only - no
  // note content is read, and at 230 sessions project-wide it is far cheaper than a
  // per-account aggregate query would be. It stops at MAX_SESSION_SCAN rather than growing
  // without limit; when it does, the counts below are floors and `truncated` says so.
  const notes = new Map<string, { count: number; lastAt: number; seconds: number }>();
  const sessions = await db
    .collectionGroup("sessions")
    .select("createdAt", "durationMs")
    .limit(MAX_SESSION_SCAN)
    .get();
  if (sessions.size >= MAX_SESSION_SCAN) truncated = true;
  for (const doc of sessions.docs) {
    const uid = doc.ref.parent.parent?.id;
    if (!uid) continue;
    const d = doc.data();
    const row = notes.get(uid) ?? { count: 0, lastAt: 0, seconds: 0 };
    row.count++;
    row.lastAt = Math.max(row.lastAt, typeof d.createdAt === "number" ? d.createdAt : 0);
    row.seconds += Math.round((typeof d.durationMs === "number" ? d.durationMs : 0) / 1000);
    notes.set(uid, row);
  }

  // Entitlements, usage and tags exist only for accounts that have one, so all three
  // collections are smaller than the user list and are read whole.
  const [ents, usage, tags] = await Promise.all([
    db.collection("entitlements").get(),
    db.collection("usage").get(),
    db.collection("adminTags").get(),
  ]);
  const entByUid = new Map(ents.docs.map((d) => [d.id, d.data() as any]));
  const usageByUid = new Map(usage.docs.map((d) => [d.id, d.data() as any]));
  const tagByUid = new Map(tags.docs.map((d) => [d.id, d.data() as any]));

  const rows = users.map((u) => {
    const providers = u.providerData.map((p) => p.providerId);
    const email = u.email ?? null;
    const ent = entByUid.get(u.uid);
    const act = notes.get(u.uid);
    const tag = tagByUid.get(u.uid);

    // Where the account came from. `guest` is the app's own anonymous sign-in; the rest is
    // whatever provider linked it.
    const origin = providers.length === 0 ? "guest" : providers.includes("google.com") ? "google" : "email";

    // What kind of account this looks like. A tag set by hand always wins - the heuristics
    // below are guesses about strangers and this is the place to correct one.
    const kind =
      tag?.kind ??
      (email && TEST_EMAIL.test(email)
        ? "test"
        : email && CRAWLER_EMAIL.test(email)
          ? "crawler"
          : origin === "guest"
            ? "guest"
            : "person");

    return {
      uid: u.uid,
      email,
      displayName: u.displayName ?? null,
      created: u.metadata.creationTime,
      lastSignIn: u.metadata.lastSignInTime ?? null,
      // Auth refreshes a token every hour the app is open, so this tracks "last used" far
      // more closely than lastSignIn, which for a guest never moves after the first day.
      lastActive: u.metadata.lastRefreshTime ?? u.metadata.lastSignInTime ?? null,
      disabled: u.disabled,
      admin: u.customClaims?.admin === true,
      providers,
      origin,
      kind,
      taggedBy: tag?.kind ? (tag.by ?? null) : null,
      tagNote: tag?.note ?? null,
      plan: ent?.plan ?? "free",
      expiresAt: typeof ent?.expiresAt === "number" ? ent.expiresAt : null,
      byokUnlocked: ent?.byokUnlocked === true,
      notes: act?.count ?? 0,
      lastNoteAt: act?.lastAt ?? null,
      recordedSeconds: act?.seconds ?? 0,
      // Minutes charged against the plan this cycle, which is not the same as time recorded:
      // a note refined on someone's own key costs nothing.
      usedSeconds: usageByUid.get(u.uid)?.secondsUsed ?? 0,
      usageCycle: usageByUid.get(u.uid)?.cycle ?? null,
    };
  });

  rows.sort((a, b) => Date.parse(b.created) - Date.parse(a.created));
  return { users: rows, truncated, sessionsScanned: sessions.size };
});

/**
 * Label one account by hand: tester, person, test, crawler, guest.
 *
 * The listing guesses from the email address, which is fine for sorting 300 rows and wrong
 * about somebody eventually. This is how that gets corrected, and it is also how a real
 * tester gets marked as one - there is nothing in an auth record that says "this person
 * agreed to test the app".
 */
const TAG_KINDS = ["tester", "person", "test", "crawler", "guest"];

export const adminTagUser = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const uid = String(request.data?.uid ?? "");
  const kind = request.data?.kind === null ? null : String(request.data?.kind ?? "");
  const note = typeof request.data?.note === "string" ? request.data.note.slice(0, 500) : null;
  if (!uid) throw new HttpsError("invalid-argument", "uid required");

  const ref = admin.firestore().collection("adminTags").doc(uid);
  if (kind === null || kind === "") {
    // Clearing the tag hands the row back to the heuristics rather than freezing whatever
    // they said last.
    await ref.delete();
    return { ok: true, kind: null };
  }
  if (!TAG_KINDS.includes(kind)) {
    throw new HttpsError("invalid-argument", `kind must be one of ${TAG_KINDS.join(", ")}`);
  }
  await ref.set(
    { kind, note, by: request.auth?.token?.email ?? request.auth?.uid ?? null, at: Date.now() },
    { merge: true }
  );
  return { ok: true, kind };
});

/**
 * Delete guest accounts that never did anything.
 *
 * Dry by default and dry unless the caller says otherwise in as many words, because this
 * deletes auth users and there is no undo. The filter is deliberately narrow: no linked
 * provider, no note ever saved, no entitlement, and untouched for `olderThanDays`. An
 * account that recorded even once is somebody's data and is never in scope here - the
 * tester who filed three bugs on 2026-09-08 was, at that point, an anonymous guest with two
 * notes to his name.
 */
export const adminPurgeGuests = onCall({ region: REGION }, async (request) => {
  requireAdmin(request);
  const olderThanDays = Math.max(1, Number(request.data?.olderThanDays ?? 30) || 30);
  const limit = Math.min(Number(request.data?.limit ?? 200) || 200, 500);
  const commit = request.data?.confirm === "DELETE";
  const cutoff = Date.now() - olderThanDays * 24 * 60 * 60 * 1000;
  const db = admin.firestore();

  const active = new Set<string>();
  const sessions = await db.collectionGroup("sessions").select().limit(MAX_SESSION_SCAN).get();
  for (const doc of sessions.docs) {
    const uid = doc.ref.parent.parent?.id;
    if (uid) active.add(uid);
  }
  const [ents, usage] = await Promise.all([
    db.collection("entitlements").get(),
    db.collection("usage").get(),
  ]);
  for (const d of ents.docs) active.add(d.id);
  for (const d of usage.docs) active.add(d.id);

  const doomed: { uid: string; created: string; lastActive: string | null }[] = [];
  let token: string | undefined;
  do {
    const page = await admin.auth().listUsers(1000, token);
    for (const u of page.users) {
      if (u.providerData.length > 0) continue; // linked an identity: not a throwaway guest
      if (active.has(u.uid)) continue; // has notes, usage or an entitlement
      const seen = Date.parse(u.metadata.lastRefreshTime ?? u.metadata.creationTime);
      if (!Number.isFinite(seen) || seen > cutoff) continue;
      doomed.push({
        uid: u.uid,
        created: u.metadata.creationTime,
        lastActive: u.metadata.lastRefreshTime ?? null,
      });
      if (doomed.length >= limit) break;
    }
    token = page.pageToken;
  } while (token && doomed.length < limit);

  if (!commit) return { dryRun: true, matched: doomed.length, sample: doomed.slice(0, 20) };

  // deleteUsers takes 1000 at a time and reports per-uid failures rather than throwing.
  const res = await admin.auth().deleteUsers(doomed.map((d) => d.uid));
  return {
    dryRun: false,
    matched: doomed.length,
    deleted: res.successCount,
    failed: res.failureCount,
    errors: res.errors.slice(0, 10).map((e) => e.error.message),
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
