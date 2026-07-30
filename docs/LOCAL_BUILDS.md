# Local dev builds

Play's closed-testing review takes ~30 minutes. That is far too slow a loop for
"change one line, see if it worked". This is the way round it: build on your machine,
install straight onto the phone, test immediately — while the same commit is going
through review in the background.

```bash
./scripts/dev-install.sh
```

Both paths stay open. Nothing here changes what ships.

## What you get

| | Play build | Dev build |
|---|---|---|
| Package | `app.greenforrest.yap` | `app.greenforrest.yap.debug` |
| Launcher name | Yap | **Yap Dev** |
| Version | `1.0` | `1.0-dev` |
| Signed by | Play App Signing | your local debug keystore |
| Firebase project | `yap-mehdiacho` | **`yap-mehdiacho` — the same one** |
| Time to device | ~30 min review | ~3 min build |

They install **side by side**. The dev build is a different app to Android, so it gets its
own icon and its own private storage.

## Why a separate package, and not just the same one

A debug-signed APK cannot overwrite a Play-signed one — Android rejects the signature
mismatch. Sharing the applicationId would force `adb uninstall` before every local install,
and uninstalling wipes app-private storage.

That directory is where recordings live between being captured and being uploaded. Wiping it
is how a 31-minute recording was nearly lost once already. A separate package makes that
class of accident impossible.

The cost is that the two apps do not share local recordings. They do not need to: notes sync
through Firestore, so anything already transcribed shows up in both.

## Same backend, genuinely

`app.greenforrest.yap.debug` is registered as a second Android app inside the **same**
Firebase project, with the debug keystore's SHA-1 attached to it. So the dev build gets:

- the same Firestore data and the same `users/{uid}/sessions` documents
- the same Cloud Storage bucket
- the same Cloud Functions (`transcribeSession`, `setByokKey`, …) in `africa-south1`
- the same **user accounts** — sign in with the account you already use, and your notes
  are there

Sign in on the dev build and you are the same `uid` you were on the Play build. There is no
separate test environment to keep in sync, which is the point: you are testing against what
users actually hit.

### What that implies

Quota, billing and BYOK are **real**. A transcription from the dev build spends the same
monthly allowance and the same Gemini credit as one from the Play build. That is deliberate
— it is the only way to test the thing you care about — but it is worth knowing before you
run twenty test transcriptions.

## Setting it up on a new machine

The debug keystore is generated per machine, so its SHA-1 differs. Google Sign-In will fail
on a machine whose fingerprint isn't registered. To add one:

```bash
./gradlew :app:signingReport
```

Take the `SHA1` under `Variant: debug`, strip the colons, lowercase it, and register it:

```bash
firebase apps:sdkconfig ANDROID --project yap-mehdiacho
```

then add the fingerprint in Firebase Console → Project settings → **Yap Dev (local debug)** →
Add fingerprint, and re-download `google-services.json` into `app/`.

Email/password sign-in works without any of this. Only Google Sign-In checks the certificate.

## If the install is refused

MIUI / HyperOS blocks `adb install` by default and reports:

```
INSTALL_FAILED_USER_RESTRICTED: Install canceled by user
```

That is a device setting, not a build problem. Either:

- **Settings → Additional settings → Developer options → Install via USB** (MIUI requires a
  signed-in Mi account for this toggle), or
- run `./scripts/dev-install.sh --push`, which drops the APK in `Downloads` for you to tap.

The script falls back to the push path automatically when an install is refused.

## Wireless debugging

No cable needed:

```bash
adb pair <phone-ip>:<pair-port>
```

```bash
adb connect <phone-ip>:<port>
```

Both are shown under Developer options → Wireless debugging.

## What the dev build cannot do

**Google Play Billing.** Subscriptions and the BYOK unlock purchase are tied to the
`app.greenforrest.yap` package as published. Purchase flows will not complete on the dev
build. Entitlements themselves work fine — they live in Firestore and are read by `uid` —
so if your account already has `pro` or `byokUnlocked`, the dev build honours it. To grant
one for testing, use `adminSetEntitlement` or a promo code rather than a purchase.

## Shipping is unchanged

`git push` to `closed-testing` still builds and uploads to the Play alpha track exactly as
before. The dev variant only adds a local path; it is not part of the release build, and the
release `google-services.json` client is byte-identical to what it was.
