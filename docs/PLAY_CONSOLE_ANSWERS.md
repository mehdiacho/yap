# Play Console — exact answers for every setup task

Work top-to-bottom; each section is one dashboard task. Answers reflect what the app
actually does as of commit 2490f3c — if a feature changes, revisit the matching section.

## 1 · Privacy policy
`https://yap-mehdiacho.web.app/privacy.html`

## 2 · App access (sign-in details)
Choose **"All functionality is available without special access"** — guest mode makes this
true: every feature works without an account; signing in only adds cross-device sync.
Reserve text if a reviewer asks: *"No credentials required — choose 'Continue as guest' on
the onboarding account step."*

## 3 · Ads
**No, my app does not contain ads.** (No ad SDKs are present.)

## 4 · Content rating (IARC questionnaire)
- Category: **Utility / Productivity / Other**
- All content questions (violence, sexuality, language, controlled substances, gambling): **No**
- Users can interact or exchange content (social/UGC exposure): **No** (notes are private)
- Shares user's current location: **No**
- Purchases digital goods: **Yes** (subscriptions + one-time unlock)
- Expected outcome: **Everyone / PEGI 3**

## 5 · Target audience and content
- Target age groups: **18 and over** only (keeps you out of Families-policy requirements;
  widen later if desired).
- "Could your store listing unintentionally appeal to children?" → **No**

## 6 · Data safety
- Collects or shares required data types? → **Yes** (collects).
- All data encrypted in transit? → **Yes**.

### Account creation methods (select all that apply)
Select **two**:
- **Username and password** (email + password sign-up)
- **OAuth** (Continue with Google)
Do NOT select "my app does not allow users to create an account". Guest/anonymous mode
doesn't fit their taxonomy and needs no declaration.

### Account deletion URL (required after the above)
`https://yap-mehdiacho.web.app/delete-account.html`
(Names the app, shows the in-app steps + an email fallback, and specifies what's deleted vs.
kept — exactly what the form requires.)

### Optional: delete some data without deleting the account?
Answer **Yes**, same URL — the page documents per-note deletion, Clear downloads, retention
policies, and export.

### Data types — mark Collected, NOT shared
(Per Play policy, transfers to service providers processing on your behalf — Firebase, the
Gemini API via our backend — do not count as "sharing"; we transfer to no one else.)

**The Claude connector does not change this, as of 2026-09-14.** Notes reach Anthropic only when
the person adds the Yap connector in Claude themselves and signs in to approve it. Play treats a
transfer the user starts, where they reasonably expect the data to go to that third party, as
not "sharing". The Worker in between (Cloudflare) is a service provider. The privacy policy
discloses the connector either way. If Yap ever pushes notes to an AI service the user didn't
connect, revisit this.

| Data type | Collected | User-optional? | Purposes |
|---|---|---|---|
| Audio → **Voice or sound recordings** | Yes | Yes (only when user records; on-device-only mode exists) | App functionality |
| Personal info → **Email address** | Yes | Yes (guest mode) | App functionality, Account management |
| Personal info → **Name** | Yes | Yes | Account management |
| App activity → **Other user-generated content** | Yes | Yes | App functionality (the notes/transcripts) |
| Device or other IDs → **User IDs** | Yes | No | App functionality, Account management (Firebase auth UID) |

Everything else (location, contacts, messages, photos/videos, files, financial info, health,
browsing, installed apps, calendar, crash logs/diagnostics): **Not collected**. Payment details
are handled entirely by Google Play. Each "Yes" row: data is **stored** (not ephemeral).
⚠️ If Crashlytics is added later, come back and add "Crash logs + Diagnostics".

## 7 · Government apps
**No.**

## 8 · Financial features
**"My app doesn't provide any financial features."** (Selling your own subscriptions through
Play Billing is not a "financial feature" — that section means banking, loans, crypto, etc.)

## 9 · Health
**"My app does not have any health features."**

## 10 · App category & contact details
- App or game: **App** · Category: **Productivity**
- Tags (if offered): Note-taking, Voice recorder, AI, Transcription, Organization
- Contact email: `mehdiacho@gmail.com` · Website: `https://yap-mehdiacho.web.app`
  (swap to greenforrest.app once registered) · Phone: leave blank

## 11 · Store listing copy (paste as-is)
- **App name (≤30):** `Yap: AI Voice Notes` (19 chars — see naming note)
- **Short description (≤80):**
  `Speak your ideas. Yap turns rambles into clean, organized notes — instantly.` (77)
- **Full description:**

```
Never lose an idea again.

Yap is one-tap voice capture: hit record, ramble, and watch your words become a clean,
organized note. A live draft appears as you speak — then Yap polishes it in the cloud into
structured Markdown with a title, category, and topic, filed automatically in your library.

CAPTURE ANYWHERE
• One tap from the app, home-screen widget, Quick Settings tile, or a floating bubble
• Works fully offline — drafts save instantly and refine when you're back online
• Import audio or video files, or share them straight into Yap

YOUR NOTES, ORGANIZED
• Automatic titles, categories, and topic clusters
• Search everything — including by voice
• Pin what matters, and add to an existing note by just talking ("add milk and eggs")

BUILT-IN INTELLIGENCE
• Smart Review flags possible mis-hears and fixes them with one tap
• Polish any rough draft into a structured note
• Bookmarks while recording become markers on playback

PRIVATE BY DESIGN
• Recording happens only when you start it — always shown by a notification
• Export everything as a zip, or delete your account and data entirely, any time
• Optional on-device-only mode and biometric app lock

Free plan includes 30 minutes of cloud transcription per month — recording and on-device
drafts are always unlimited. Pro ($1.99/mo or $19.99/yr) raises that to 30 hours with 2 GB
of synced audio storage.
```

- **Naming note:** "Yap!" (voice notes) already exists on Play. `Yap: AI Voice Notes` is fine
  for testing tracks; before PRODUCTION decide whether to keep it or switch to a vetted
  alternate (YapDrop / YapBook / YapVault / YapJar). The listing name can change any time;
  the package name cannot.

## 12 · Graphics
- App icon 512×512: `play-assets/icon-512.png`
- Feature graphic 1024×500: `play-assets/feature-1024x500.png`
- Video: skip (optional).
- Phone screenshots: ≥4 at ≥1080px per side — in `play-assets/screenshots/`
  (Capture idle, Recording, Library, Note detail, Settings). Tablet / Chromebook / XR: skip.

## 13 · Release order (after the setup tasks above)
1. **Internal testing** → upload `app-release.aab`, add your Google account as a tester, roll out.
2. After first upload: **Setup → App integrity** → copy the *App signing key* SHA-1 → hand it to
   Claude Code to register in Firebase (Google Sign-In on Play-delivered builds needs it).
3. **Monetize → Products**: subscriptions `pro_monthly` $1.99 / `pro_yearly` $19.99 /
   `byok_storage_monthly` $0.99, and in-app product `byok_unlock` $4.99.
4. **Users & permissions** → invite `yap-mehdiacho@appspot.gserviceaccount.com` with
   *View financial data* + *Manage orders and subscriptions* (purchase verification).
5. **Settings → License testing** → add your Google account (test purchases, no charges).
6. Closed testing → Production only after internal testing proves the build.
