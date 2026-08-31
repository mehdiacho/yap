# Yap Admin

The operations console for Yap: accounts, entitlements, promo codes, the feedback inbox,
pipeline activity and model spend. A React app built with Vite and deployed to Firebase
Hosting.

It replaced a single 232-line `index.html` that could search one user at a time and create
promo codes. Everything else on this list had no interface at all.

## Getting in

Every function behind this dashboard is gated on the `admin` custom claim, and nothing in the
codebase sets it — so a fresh project has no admins and the whole console is unreachable
until somebody grants the claim. `mehdiacho@gmail.com` already has it. For anyone else:

```bash
gcloud auth application-default login
gcloud auth application-default set-quota-project yap-mehdiacho
cd functions
node scripts/grant-admin.js them@example.com
```

That script deliberately does not exist as a callable. A function that can make somebody an
admin has to be protected from being called by the wrong person, and the safest version of
that is one that cannot be reached over the network at all.

Custom claims land in the ID token, so sign out and back in afterwards — an existing session
carries the old token for up to an hour.

## Running it

```bash
npm --prefix admin install
npm --prefix admin run dev
```

Sign-in is Google popup against the live project, so the dev server talks to production
data. There is no emulator wiring here on purpose: the interesting failures are things like
"the signed URL for a voice message came back empty", which an emulator would not reproduce.

## Deploying

```bash
firebase deploy --only hosting
```

`firebase.json` builds before it uploads, so there is no separate step and no way to ship a
stale `dist/`.

## The two pages that must not break

`public/privacy.html` and `public/delete-account.html` are the URLs submitted to the Play
Store listing. Google fetches them, and a 404 on either is a listing problem, not a website
problem.

They live in `public/` because Vite copies that folder to the output root untouched, so they
keep resolving at `/privacy.html` and `/delete-account.html` exactly as they did when this
directory was a static site. They are plain HTML with no build step and no dependency on the
app — do not move them into `src/`, do not turn them into routes, and do not let the SPA
rewrite swallow them (it cannot: Hosting matches real files before rewrites).

## What it can do

- **Overview** — accounts, signups this week, active plans, unread feedback.
- **Users** — every account, newest first, plus lookup by email or uid for support. Set a
  plan, an expiry, or unlock bring-your-own-key on any account.
- **Subscriptions** — create and audit promo codes. Paid subscriptions are *not* editable
  here: Play owns the purchase and `verifyPlayPurchase` writes the entitlement, so a hand
  edit would be overwritten by the next verification. Change the account's entitlement
  under Users instead.
- **Feedback** — everything sent from Settings → Send feedback, with voice messages played
  in place, triaged new / read / done.
- **Activity** — recent notes across all accounts: how many, how long, which failed and why.
  Deliberately no transcript text; watching the pipeline does not require reading what
  people said.
- **Costs** — projected spend from our own token counts against Google's billing export,
  kept as two separate numbers so the gap between them stays visible.
