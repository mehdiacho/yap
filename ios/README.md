# Yap for iOS

A working iOS client for the same account, the same notes and the same backend as the Android
app. It exists to answer two questions — how far an app can be put on an iPhone without an
Apple Developer account, and what of Yap survives the crossing — and to put a usable build on
a test device.

Nothing here is signed, and nothing here talks to the App Store.

## What it does

Sign in or sign up with email, or continue with Google. Sign in with an account made on
Android and the notes are already there: both clients read `users/{uid}/sessions` from one
Firestore, so nothing is migrated or synced — there is only ever one copy.

Record, and the audio goes straight to Cloud Storage and through the same `transcribeSession`
callable the Android app uses, in the same region. The note appears immediately as
TRANSCRIBING and fills itself in when the backend writes the result. Play back any note whose
audio reached the cloud. Read transcripts and tasks.

## What is deliberately missing, and why

**No live draft.** On Android that is a fed-stream `SpeechRecognizer` built to avoid mic
contention, and it took real work to get right. iOS `SFSpeechRecognizer` has different limits,
its own permission and its own model downloads. Recording goes straight to the cloud and the
transcript comes back — one path, nothing to reconcile.

**No in-app purchases.** Not a scheduling decision: IAP cannot be built at all without a paid
Apple Developer account. Products live in App Store Connect, which does not exist for a free
Apple ID, so StoreKit would return an empty list with nothing to buy — no sandbox, no test
mode, no offline stub that proves anything. Google Play Billing is not an alternative either;
it is delivered by Play Services, which does not exist on iOS. Plans is an information screen
that points at the Android app. Entitlement is read from the same user document either way, so
a plan bought on Android is already in force here.

**Markdown renders as plain text.** The backend returns Markdown and Android has a native
renderer for it. Half-rendered Markdown reads worse than none, so this shows the text as-is.

**Android-local audio paths are ignored.** Every note the Android app writes carries an
`audioPath` into that device's private storage. On iOS it is meaningless, so `storagePath` —
the Cloud Storage copy — is the only thing this app will play. A note whose audio never
reached the cloud says so instead of offering a play button that cannot work.

**The Android theme is not ported.** Yap's identity there is a set of Compose tokens and a
brand gradient reserved for identity moments. Half-translating that into SwiftUI would produce
a worse copy of the real app; this is plain and dark, and looks like a deliberate test build.

## Building it

The Xcode project is generated, never committed — a `pbxproj` is thousands of lines of opaque
object ids that nobody can review and every merge conflicts on. `project.yml` is the same
information in a form a person can read.

```bash
brew install xcodegen
cd ios && xcodegen generate && open Yap.xcodeproj
```

There is no Mac in this project's development loop, so the real compiler is CI:
`.github/workflows/ios.yml` builds on a macOS runner and uploads an **unsigned `.ipa`** as an
artifact. It runs only when `ios/**` changes, because macOS minutes bill at 10x — a four-minute
build spends about forty minutes of the 2,000/month free allowance.

The repository does **not** need to be public for this. A private repo draws on the same free
allowance; it just consumes it faster than a public one, which is free outright.

## Getting it onto a phone

An `.ipa` is a zip with the `.app` inside a folder called `Payload`, which is why one can be
built without ever being signed — and re-signed later by something else. The something else,
with a free Apple ID, is a sideloader: it takes the unsigned bundle, signs it with a
development certificate your Apple ID is allowed to issue, and installs it.

### What you need

- The **`Yap-unsigned-ipa` artifact** from the CI run, downloaded and unzipped. GitHub wraps
  artifacts in a zip, so what you download is `Yap-unsigned-ipa.zip` and the `.ipa` is inside
  it. Artifacts expire after 30 days; re-run the workflow to get a fresh one.
- An **Apple ID**. The free one is fine — that is the whole point of this exercise.
- The iPhone and the Windows machine on the **same Wi-Fi**, and a Lightning/USB-C cable for
  the first pairing.

### The route that works on Windows: AltStore Classic

AltStore comes in two versions and picking the wrong one wastes an evening. **AltStore PAL**
is the EU-only marketplace app and is not this. **AltStore Classic** is the sideloader, and
it is what these steps mean.

1. **Install Apple's iTunes and iCloud — from apple.com, not the Microsoft Store.** This is
   the step everything else depends on and the one that most often goes wrong. AltServer
   talks to the phone through the pairing and networking components those installers ship,
   and the Microsoft Store editions are sandboxed such that AltServer cannot reach them. If
   you already have the Store versions, uninstall them first.
2. **Install AltServer** on the PC from altstore.io and let it run. It lives in the system
   tray, not as a window.
3. **Plug the phone in and trust the computer** when iOS asks.
4. **Tray icon → Install AltStore → [your iPhone]**, and sign in with the Apple ID. This puts
   the AltStore app itself on the phone; it is the thing that will install Yap.
5. On the phone: **Settings → General → VPN & Device Management**, tap your Apple ID under
   Developer App, and **Trust** it. iOS will not launch a sideloaded app until you do.
6. **iOS 16 and later: Settings → Privacy & Security → Developer Mode → on**, then restart
   the phone. The toggle does not appear until a development-signed app has been installed
   once, which is why this comes after step 4 rather than before.
7. Open **AltStore on the phone → My Apps → + → pick the `.ipa`**. Get the file onto the
   phone however is easiest — AirDrop from a Mac, iCloud Drive, or just email it to yourself
   and save it to Files.

### Keeping it alive

A free Apple ID signs for **seven days**. After that the app stops launching until it is
re-signed. AltStore refreshes automatically when the phone is on the same Wi-Fi as the
running AltServer, so in practice this is invisible — as long as the PC is on and AltServer
is running at least once a week. Data is not lost when a build expires; only launching is
blocked, and a refresh restores it.

The other free-Apple-ID limits are not negotiable either:

- **Three sideloaded apps** on the device at once. AltStore itself is one of them.
- **No push notifications, no CloudKit, no App Groups** — none of which this build uses.

### If AltStore will not cooperate

**SideStore** is the alternative and refreshes on-device without a PC on the network, which
is genuinely better once it is running. It is harder to set up now than it used to be: it
needs a **pairing file** generated on a computer (iDevicePair or Jitterbug), and it needs
**StosVPN**, which has been removed from the App Store — existing installs still work, but a
fresh one cannot get it from the Store. Try AltStore first.

The EU third-party app store ruling does not help here. It applies to iPhones in the EU, and it
still requires the paid Developer Programme membership to distribute through one — it opens up
who may run a store, not who may sign an app for free.

So: a $99/year membership buys a one-year signing certificate and removes the three-app limit
and the weekly refresh. Without it, everything above works, on a seven-day clock.
