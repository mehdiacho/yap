# Android templates

Everything reusable for taking a Firebase-backed Android app from code to a live Play Store
listing. Assumes: Kotlin + Jetpack Compose, Firebase (Auth/Firestore/Storage/Functions), Play
App Signing, optional Play Billing.

## Folders

| Folder | What it gives you |
|---|---|
| [`play-console/`](play-console/CLAUDE.md) | The full per-app Play Console setup walkthrough (every content/policy/data-safety section), plus ready-to-host **privacy** and **account-deletion** page templates. |
| [`ci-cd/`](ci-cd/CLAUDE.md) | GitHub Actions workflow that builds a signed AAB and uploads to a Play track per pushed branch, plus the Gradle release-signing config and the exact secrets/service-accounts to set. |
| [`store-assets/`](store-assets/CLAUDE.md) | Python/PIL generator for the app icon (512²) and feature graphic (1024×500) from a brand mark. |

## Typical order for a brand-new app
1. **Code** builds and installs. Firebase project exists; `google-services.json` in `app/`.
2. **`ci-cd/`** — add the release-signing config, workflow, secrets, and the CI publisher service
   account. Get a signed AAB building.
3. **`play-console/`** — host the privacy + deletion pages, then work top-to-bottom through the
   content/policy sections. Create + activate products if paid.
4. **`store-assets/`** — generate icon + feature graphic; add ≥4 screenshots.
5. First upload to **internal testing**; register the App-signing SHA-1 in Firebase; test; promote.

## Cross-cutting gotchas (bit us on the first app, save you on the next)
- **applicationId is immutable**; the store listing name is not.
- **Product IDs are immutable + lowercase**, and must equal your billing-code constants *and*
  backend plan keys exactly. Subscriptions need **base-plan activation AND subscription activation**.
- **Google Sign-In on Play-delivered builds needs the Play App-signing-key SHA-1 in Firebase**
  (not just your upload/debug keys). Fresh installs can throw "no credentials available" for a
  minute while Play services downloads its credential module — environmental, not a config bug.
- **namespace ≠ applicationId** is a supported split (e.g. namespace `com.dev.app`, applicationId
  `app.brand.app`).
