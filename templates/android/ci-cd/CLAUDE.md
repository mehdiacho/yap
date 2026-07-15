# Android release CI/CD

Push a branch → GitHub Actions builds a **signed AAB** and uploads it to the matching Play track.
No manual bundling or version bumping.

## Files
- **`release.yml`** — the workflow. Drop into `.github/workflows/`. Branch → track mapping:
  `main`→production(draft), `open-testing`→beta, `closed-testing`→alpha, `internal-testing`→internal.
  Replace `{{PACKAGE}}`. versionCode = commit count (needs `fetch-depth: 0`).
- **`gradle-signing-snippet.md`** — the `signingConfigs`/`buildTypes` block for `app/build.gradle.kts`
  that reads the keystore from env vars (CI) with a local `keystore.properties` fallback, plus the
  env-var versionCode override.

## One-time setup for a new app
1. **Gradle**: paste the signing snippet into `app/build.gradle.kts`. Generate an upload keystore
   if you don't have one:
   `keytool -genkey -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`
   Keep `upload-keystore.jks` + `keystore.properties` **git-ignored**; back them up (losing the
   upload key is recoverable via Play support; still, back up).
2. **CI publisher service account** (separate from any purchase-verification account, no GCP roles):
   ```
   gcloud iam service-accounts create play-ci --project {{PROJECT}} --display-name "Play CI publisher"
   gcloud iam service-accounts keys create play-ci-key.json \
     --iam-account play-ci@{{PROJECT}}.iam.gserviceaccount.com --project {{PROJECT}}
   ```
   Then in **Play Console → Users & permissions**, invite `play-ci@{{PROJECT}}.iam.gserviceaccount.com`
   with **"Release apps to testing tracks"** (+ production if you want main→prod). Enable the
   **Google Play Android Developer API** in that GCP project.
3. **GitHub secrets** (`gh secret set NAME` or Settings → Secrets → Actions):
   | Secret | Value |
   |---|---|
   | `UPLOAD_KEYSTORE_BASE64` | `base64 -w0 upload-keystore.jks` |
   | `KEYSTORE_STORE_PASSWORD` | keystore password |
   | `KEYSTORE_KEY_PASSWORD` | key password |
   | `KEYSTORE_KEY_ALIAS` | `upload` |
   | `GOOGLE_SERVICES_JSON` | contents of `app/google-services.json` (git-ignored) |
   | `PLAY_SERVICE_ACCOUNT_JSON` | the `play-ci-key.json` contents — then delete the local file |
4. **First upload can't be via the API** — Play requires the very first AAB of a brand-new app to
   be uploaded manually in the console. After that, CI can upload.

## Gotchas
- Shallow clone → `git rev-list --count HEAD` returns 1 → duplicate versionCode. `fetch-depth: 0`
  fixes it (already in the template).
- `main` maps to production with **status: draft** on purpose — nothing auto-goes-live; promote by
  hand. Change to `completed` only when you want pushes to main to release immediately.
- Branch protection on `main` can block the merge that triggers a prod build — that's a repo
  setting, not a workflow issue.
