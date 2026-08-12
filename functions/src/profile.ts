import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

const REGION = "africa-south1";

function requireAuth(request: CallableRequest): string {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
  return request.auth.uid;
}

// ---------------------------------------------------------------- Green Forrest ID

/**
 * No 0/O/1/I/L. The ID is meant to be read off one screen and typed into another, and
 * those five characters are where that goes wrong. Same alphabet as promo codes.
 */
const ID_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

function randomId(): string {
  let s = "";
  for (let i = 0; i < 8; i++) {
    if (i === 4) s += "-";
    s += ID_ALPHABET[Math.floor(Math.random() * ID_ALPHABET.length)];
  }
  return `GF-${s}`;
}

/**
 * The account's identifier across Green Forrest apps.
 *
 * ## What this is, and what it is not yet
 *
 * Yap's Firebase uid is scoped to the `yap-mehdiacho` project. A second Green Forrest app on
 * its own project would issue the same person a completely different uid, so the uid can
 * never be the shared identifier — which is the whole point of this one.
 *
 * So the ID is minted here and reverse-indexed by the address that DOES travel between
 * projects: the account's email. `greenForrestIds/{id}` holds `{ uid, email }`. When a second
 * app exists, it authenticates the same person, finds the existing ID by their email, and
 * claims it rather than minting a new one — the lookup is why the reverse index stores email
 * and not only uid.
 *
 * Until that second app is real this is a stable, portable handle and nothing more. It is
 * deliberately not a credential: knowing someone's ID grants no access to anything.
 */
async function ensureGreenForrestId(uid: string, email: string | null): Promise<string> {
  const db = admin.firestore();
  const profileRef = db.collection("profiles").doc(uid);
  const existing = await profileRef.get();
  const already = existing.get("greenForrestId");
  if (typeof already === "string" && already.length > 0) return already;

  // An account that already holds an ID under another Green Forrest app keeps it. Anonymous
  // guests have no email, so they get a fresh ID that binds to their address if they sign up.
  if (email) {
    const claimed = await db.collection("greenForrestIds")
      .where("email", "==", email.toLowerCase()).limit(1).get();
    if (!claimed.empty) {
      const id = claimed.docs[0].id;
      await claimed.docs[0].ref.set({ uid }, { merge: true });
      await profileRef.set({ greenForrestId: id }, { merge: true });
      return id;
    }
  }

  for (let attempt = 0; attempt < 8; attempt++) {
    const id = randomId();
    const ref = db.collection("greenForrestIds").doc(id);
    const won = await db.runTransaction(async (tx) => {
      if ((await tx.get(ref)).exists) return false;
      tx.set(ref, { uid, email: email?.toLowerCase() ?? null, createdAt: Date.now() });
      return true;
    });
    if (won) {
      await profileRef.set({ greenForrestId: id }, { merge: true });
      return id;
    }
  }
  // 31^8 of space and eight tries: reaching here means something is wrong with Firestore,
  // not that we ran out of IDs. Say so rather than handing back a duplicate.
  throw new HttpsError("internal", "Couldn't allocate a Green Forrest ID. Try again.");
}

// ---------------------------------------------------------------- callables

/**
 * Everything the profile screen shows. Mints the Green Forrest ID on first call, so no
 * separate "create profile" step has to be remembered at signup — or backfilled for the
 * accounts that already exist.
 */
export const getProfile = onCall({ region: REGION }, async (request) => {
  const uid = requireAuth(request);
  const user = await admin.auth().getUser(uid);
  const greenForrestId = await ensureGreenForrestId(uid, user.email ?? null);
  return {
    greenForrestId,
    displayName: user.displayName ?? null,
    photoUrl: user.photoURL ?? null,
    email: user.email ?? null,
    emailVerified: user.emailVerified,
    isGuest: !user.email && !user.providerData.length,
    memberSince: user.metadata.creationTime
      ? new Date(user.metadata.creationTime).getTime()
      : null,
  };
});

/**
 * Set the display name.
 *
 * Written to the Firebase auth user rather than only to Firestore: it is what every other
 * surface already reads (the home greeting, the settings row, the admin user search), and
 * keeping a second copy in a profile doc would guarantee the two drift.
 */
export const setProfileName = onCall({ region: REGION }, async (request) => {
  const uid = requireAuth(request);
  const raw = String(request.data?.displayName ?? "").replace(/\s+/g, " ").trim();
  if (raw.length < 1) throw new HttpsError("invalid-argument", "Enter a name.");
  if (raw.length > 60) throw new HttpsError("invalid-argument", "That name is too long.");
  // C0/C1 controls render as tofu in the greeting, and the bidi overrides can make a name
  // display as something other than what was stored. Written as escapes on purpose: the
  // literal characters are invisible in an editor, so nobody can review them.
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001F\u007F-\u009F\u200E\u200F\u202A-\u202E]/.test(raw)) {
    throw new HttpsError("invalid-argument", "That name contains characters we can't display.");
  }
  await admin.auth().updateUser(uid, { displayName: raw });
  await admin.firestore().collection("profiles").doc(uid)
    .set({ displayName: raw, updatedAt: Date.now() }, { merge: true });
  return { displayName: raw };
});

/**
 * Point the auth user at an avatar the client has already uploaded to
 * `users/{uid}/profile/...` in Storage.
 *
 * The upload itself stays on the client — it is a multi-megabyte transfer that the Storage
 * rules already scope to the owner, and routing it through a callable would mean base64ing
 * an image through a 10MB request limit for no security we don't already have. What the
 * server does is refuse to record a URL that doesn't point into the caller's own folder,
 * which is the part a client cannot be trusted with.
 */
export const setProfilePhoto = onCall({ region: REGION }, async (request) => {
  const uid = requireAuth(request);
  const url = request.data?.photoUrl;
  if (url === null || url === "") {
    await admin.auth().updateUser(uid, { photoURL: null });
    await admin.firestore().collection("profiles").doc(uid)
      .set({ photoUrl: null, updatedAt: Date.now() }, { merge: true });
    return { photoUrl: null };
  }
  const photoUrl = String(url ?? "");
  const bucket = admin.storage().bucket().name;
  const ownPrefix = encodeURIComponent(`users/${uid}/profile/`);
  const looksRight =
    photoUrl.startsWith(`https://firebasestorage.googleapis.com/v0/b/${bucket}/o/${ownPrefix}`) ||
    photoUrl.startsWith(`https://storage.googleapis.com/${bucket}/users/${uid}/profile/`);
  if (!looksRight) {
    throw new HttpsError("invalid-argument", "That image isn't in your account's storage.");
  }
  await admin.auth().updateUser(uid, { photoURL: photoUrl });
  await admin.firestore().collection("profiles").doc(uid)
    .set({ photoUrl, updatedAt: Date.now() }, { merge: true });
  return { photoUrl };
});
