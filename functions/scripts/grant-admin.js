#!/usr/bin/env node
/**
 * Grant or revoke the `admin` custom claim.
 *
 * Every admin callable in this project is gated on `request.auth.token.admin === true`, and
 * nothing in the codebase ever sets it — which means the dashboard is unreachable until this
 * runs once. It lives here rather than in a callable on purpose: a function that can make
 * somebody an admin is a function that has to be protected from being called by the wrong
 * person, and the safest version of that is one that cannot be called over the network at all.
 *
 * Run it from `functions/` so firebase-admin resolves:
 *
 *   gcloud auth application-default login          # once, if you have not already
 *   cd functions
 *   node scripts/grant-admin.js you@example.com
 *   node scripts/grant-admin.js you@example.com --revoke
 *
 * Or with a service account key instead of gcloud:
 *
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/key.json node scripts/grant-admin.js you@example.com
 *
 * The claim lands in the user's ID token, not immediately — an already-signed-in session keeps
 * the old token for up to an hour. Sign out and back in on the dashboard and it is there.
 */
const admin = require("firebase-admin");

const args = process.argv.slice(2);
const revoke = args.includes("--revoke");
const target = args.find((a) => !a.startsWith("--"));

if (!target) {
  console.error("usage: node scripts/grant-admin.js <email|uid> [--revoke]");
  process.exit(1);
}

admin.initializeApp({ projectId: process.env.GCLOUD_PROJECT || "yap-mehdiacho" });

(async () => {
  let user;
  try {
    user = target.includes("@")
      ? await admin.auth().getUserByEmail(target)
      : await admin.auth().getUser(target);
  } catch (e) {
    console.error(`No such user: ${target}`);
    console.error(e.message);
    process.exit(1);
  }

  // Merged rather than replaced. Custom claims are one object per user, so writing
  // { admin: true } on its own would silently drop anything else already set there.
  const claims = { ...(user.customClaims || {}) };
  if (revoke) delete claims.admin;
  else claims.admin = true;

  await admin.auth().setCustomUserClaims(user.uid, claims);
  console.log(
    `${revoke ? "Revoked" : "Granted"} admin for ${user.email || user.uid} (${user.uid})`
  );
  console.log("Sign out and back in on the dashboard to pick up the new token.");
  process.exit(0);
})();
