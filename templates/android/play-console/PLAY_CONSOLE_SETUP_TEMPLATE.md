# Play Console — reusable app-setup template

Everything that must be filled out **per app** in Play Console, generalized so it can be reused
for any new app. Each item lists **where** it lives, a **reusable default + the reasoning**, and
a **PER APP** blank to fill.

> How to read the defaults: they were derived for a Firebase-backed utility app (Yap — voice
> notes) that has in-app purchases, no ads, and collects only what it needs to function. Sections
> marked **⚠ RE-DECIDE PER APP** genuinely change with the app's features — copy the *approach*,
> not the answer. Yap's concrete answers are shown as the worked example.

---

## 0 · Fill-in header (do this first, reuse throughout)

| Field | Value |
|---|---|
| App name (listing, ≤30 chars) | `________` |
| applicationId / package | `________` (e.g. `app.<org>.<app>`) |
| Firebase project id | `________` |
| Support email | `________` |
| Website / brand domain | `________` |
| Privacy policy URL | `________/privacy.html` |
| Account deletion URL | `________/delete-account.html` |
| Regions/price | `________` |

Naming rules that bit us before: **applicationId is immutable** (listing name can change any
time); **product IDs are immutable and must be lowercase** letters/numbers/underscore/period.

---

## Quick checklist (tick per app)

- [ ] Privacy policy URL live
- [ ] App access (sign-in details)
- [ ] Ads declaration
- [ ] Content rating (IARC questionnaire)
- [ ] Target audience & content
- [ ] Data safety
- [ ] Government apps
- [ ] Financial features
- [ ] Health
- [ ] App category & contact details
- [ ] Store listing (name, short + full description)
- [ ] Graphics (icon 512, feature 1024×500, ≥4 phone screenshots ≥1080px)
- [ ] Content rating + News + COVID declarations (any extras Console shows)
- [ ] Release: internal testing track + testers
- [ ] Monetization: products created **and activated** (if paid)
- [ ] Service accounts invited (purchase verification + CI publisher)
- [ ] License testing accounts added
- [ ] App-signing SHA-1 registered in Firebase (if Google Sign-In)

---

## 1 · Privacy policy
**Where:** Policy → App content → Privacy policy.
**Reusable:** host a static page (Firebase Hosting works: `https://<project>.web.app/privacy.html`).
Template pages sit next to this file — `privacy.template.html` and `delete-account.template.html`:
copy, replace the `{{PLACEHOLDERS}}`, redeploy. Must state: what's collected & why, that Google
Play handles payments (you store only a purchase token), retention, deletion, contact email.
**PER APP:** `privacy URL = ________`

## 2 · App access (sign-in details)
**Where:** App content → App access.
**Reusable default:** if the app is fully usable without login (guest mode), choose
**"All functionality is available without special access."** Reserve note for reviewers:
*"No credentials required — choose 'Continue as guest'."*
**⚠ RE-DECIDE PER APP:** if any feature is gated behind login, instead provide **test credentials**
(a demo account) here.

## 3 · Ads
**Where:** App content → Ads.
**Reusable default:** **No ads** — *only* if no ad SDK is present. **⚠ RE-DECIDE PER APP** if you
integrate AdMob/any ad network.

## 4 · Content rating (IARC questionnaire)
**Where:** App content → Content ratings.
**Reusable defaults (utility/productivity app):**
- Category: **Utility / Productivity / Communication / Other** (pick the closest).
- Violence / sexuality / language / controlled substances / gambling: **No**.
- Users interact / exchange content / UGC visible to others: **No** *(⚠ Yes if the app has social,
  sharing-to-other-users, chat, or public content)*.
- Shares user's current location: **⚠ Yes if the app uses/shares location.**
- Purchases digital goods: **Yes if it has IAP/subscriptions**, else No.
- Expected result: **Everyone / PEGI 3**.
**⚠ RE-DECIDE PER APP** — this whole questionnaire is feature-dependent.

## 5 · Target audience and content
**Where:** App content → Target audience and content.
**Reusable default:** target **18 and over** only → keeps you out of Families-policy requirements.
"Could your store listing unintentionally appeal to children?" → **No**.
**⚠ RE-DECIDE PER APP** if you actually want younger audiences (adds Families obligations).

## 6 · Data safety
**Where:** App content → Data safety. **This is the most app-specific section.**

**Reusable framework:**
- Does the app collect/share user data? → **Yes** (almost always, if it has accounts/analytics/cloud).
- All data encrypted in transit? → **Yes** (Firebase/HTTPS).
- **"Sharing" rule (reusable):** transfers to **service providers processing on your behalf**
  (Firebase, an AI API called by *your* backend, crash reporting) are **"collected, NOT shared."**
  Only mark "shared" if data goes to a third party for *their* own use. We transfer to no one else
  → everything is **Collected, not shared**.

**Account creation methods** (if the app has accounts): select every method the app offers, e.g.
**Username and password** (email+password) and/or **OAuth** (Google). Do NOT pick "app does not
allow accounts" if guest+optional-signup exists — anonymous mode needs no declaration.

**Account deletion URL** (required once accounts are declared): `________/delete-account.html`.
Also answer **Yes** to "delete some data without deleting the account?" and give the same URL if
the app supports per-item deletion / export.

**⚠ Data-types matrix — RE-DECIDE PER APP.** For each type you collect: mark Collected, whether
it's user-optional, the purpose, and that it's *stored* (not ephemeral). Common types to walk:
- Personal info: **Name, Email address** (account) · Address/phone (only if collected)
- Financial info: **usually "Not collected"** — Google Play handles payment details
- App activity / **Other user-generated content** (the user's notes/entries/lists)
- Device/other IDs: **User IDs** (Firebase auth UID)
- Audio (**Voice or sound recordings**) — only if the app records audio
- Location / Contacts / Photos / Messages / Calendar / Health — mark **Not collected** unless used
- **Crash logs + Diagnostics** — add this if Crashlytics/analytics is present

*Yap's worked example* (voice notes): Collected-not-shared = Voice recordings, Email, Name,
Other UGC (transcripts), User IDs. Everything else Not collected.

## 7 · Government apps
**Where:** App content → Government apps. **Reusable:** **No** (unless it's an official gov app).

## 8 · Financial features
**Where:** App content → Financial features.
**Reusable default:** **"My app doesn't provide any financial features."** Selling your own
subscriptions via Play Billing is **not** a "financial feature" (that section means banking,
loans, crypto, payments, investing, insurance).
**⚠ RE-DECIDE PER APP:** a budgeting/finance app may still be "none" if it only tracks the user's
own numbers, **but** if it connects bank accounts, handles real transactions, gives financial
advice, or does crypto — you must declare and may face extra verification. Read the sub-options.

## 9 · Health
**Where:** App content → Health.
**Reusable default:** **"My app does not have any health features."**
**⚠ RE-DECIDE PER APP** if it does fitness/medical/mental-health/health-data.

## 10 · App category & contact details
**Where:** Store presence → Store settings.
- App or game: **App** · Category: **⚠ pick per app** (Productivity, Finance, Shopping, …).
- Tags: pick relevant ones.
- Contact email: `________` · Website: `________` · Phone: optional.

## 11 · Store listing
**Where:** Store presence → Main store listing.
- **App name** (≤30) — check the name isn't taken on Play; listing name is changeable later.
- **Short description** (≤80).
- **Full description** — structure that worked: one-line hook, then 2–4 feature sections with
  bullet points, then a pricing line if monetized.
- **PER APP:** write fresh copy. Keep a house style (benefit-led hook, scannable bullets).

## 12 · Graphics
**Where:** Store presence → Main store listing → Graphics.
- **App icon** 512×512 PNG (full-bleed square; Play masks corners).
- **Feature graphic** 1024×500 PNG.
- **Phone screenshots** ≥4, each ≥1080px on the short side. Tablet/Chromebook/XR: optional, skip.
- Video: optional, skip.
- *(A Python/PIL generator that builds icon + feature graphic from a brand mark lives in this
  project's scratchpad history — reusable pattern: gradient bg + white glyph.)*

## 13 · Release order (after the content sections above)
1. **Internal testing** → upload the AAB, add tester Google accounts, roll out.
2. **Setup → App integrity / App signing** → copy the **App signing key SHA-1** → register it in
   Firebase (`firebase apps:android:sha:create <androidAppId> <sha1>`) so Google Sign-In works on
   Play-delivered builds. Also register your **upload key** SHA-1 and each **debug** SHA-1.
3. **Monetize → Products** (if paid) — see §14.
4. **Users & permissions** → invite service accounts — see §15.
5. **Settings → License testing** → add test Google accounts (test purchases, no charge).
6. Promote internal → closed → production only after testing proves the build.

## 14 · Monetization (only if the app is paid) ⚠ PER APP
**Where:** Monetize → Products → Subscriptions / In-app products.
- Product IDs: **lowercase, immutable**, must match your billing code + backend constants exactly.
- **Subscriptions need TWO activations:** create → **Add base plan** (auto-renewing, billing
  period, price) → **Activate base plan** → then **Activate the subscription** itself. It stays
  invisible to the app until both are active.
- Base-plan defaults that worked: **7-day grace period**, accept the calculated **account hold**
  (~53 days), **charge at next billing date** on plan changes, **resubscribe allowed**. Tags:
  leave empty unless you add multiple offers.
- One-time products: set price → **Activate**.
- Tax/compliance: **digital app sales** + **service over digital content** (for a
  service/consumable); leave product-level age rating and payment-location restrictions unset
  unless required.
- **PER APP product list:** `________`

## 15 · Service accounts & CI ⚠ if using backend verification / CI
- **Purchase verification** (backend calls Play Developer API): invite the service account your
  Cloud Functions run as (e.g. `<project>@appspot.gserviceaccount.com`) under **Users &
  permissions** with **"View financial data"** + **"Manage orders and subscriptions."**
- **CI publisher** (GitHub Actions uploads AAB): create a **dedicated, no-GCP-roles** service
  account (`play-ci@<project>.iam.gserviceaccount.com`), download a JSON key, invite it in Play
  Console with **"Release apps to testing tracks."** Keep it separate from the verification
  account so a leaked CI secret can't touch your project.
- **GitHub secrets** for the release workflow: `UPLOAD_KEYSTORE_BASE64`, `KEYSTORE_STORE_PASSWORD`,
  `KEYSTORE_KEY_PASSWORD`, `KEYSTORE_KEY_ALIAS`, `GOOGLE_SERVICES_JSON`, `PLAY_SERVICE_ACCOUNT_JSON`.
  The workflow (`.github/workflows/release.yml` in this repo) is reusable — swap `packageName`.

---

## Reusable reasoning cheat-sheet (the "why" behind the defaults)
- **Guest mode → "all functionality available"** instead of supplying test creds.
- **Service-provider transfers are "collected, not shared."** Only third-party-for-their-own-use
  is "shared."
- **Play Billing ≠ a financial feature.** That section is banking/loans/crypto/investing.
- **Target 18+** to dodge Families-policy overhead unless you want younger users.
- **Financial info is "not collected"** — Google Play owns the payment data; you keep a token.
- **Product IDs are immutable + lowercase**, must equal your code constants; subscriptions need
  base-plan AND subscription activation.
- **applicationId is immutable**; the store listing name is not.
- **Google Sign-In on Play builds needs the App-signing-key SHA-1 in Firebase** (fresh GMS
  installs may throw "no credentials available" until the credential module finishes downloading —
  environmental, not a config bug).
