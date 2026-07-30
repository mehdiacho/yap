# Yap Desktop

Desktop companion for [Yap](https://github.com/mehdiacho/yap), the voice idea-capture app.
You capture on the phone; you work the library at your desk.

Phase 1 (this repo, now): **read + sync.** Sign in with your existing Yap account and every
note you have ever recorded — plus anything the phone syncs while you work — appears here
live. Recording on desktop comes next.

## Stack and why

| Layer | Choice | Reason |
|---|---|---|
| UI | React 19 + TypeScript + Vite | The design kit ships as HTML/CSS, so frames port near-directly |
| Data | Firebase JS SDK (Firestore + Storage + Auth) | Realtime listeners and offline persistence, first-class on web |
| Shell | Tauri (planned) | Native tray, global hotkey, always-on-top mini overlay; ~10 MB binary |

The [yap BACKLOG](https://github.com/mehdiacho/yap/blob/main/docs/BACKLOG.md) records Compose
Multiplatform as the long-term cross-platform direction. Desktop deliberately diverges,
because **Firebase has no official JVM/desktop client SDK** — the community port
(GitLive `firebase-java-sdk`) is alpha, and realtime sync is the whole point of this app.
The backend stays the shared moat, exactly as the BACKLOG argues; only the client differs.

### The portability rule

`src/lib/shell.ts` is the **only** module allowed to touch a shell SDK. Nothing under
`src/features` or `src/components` may import `@tauri-apps/*`, Electron, or Node APIs.
That keeps the React core a plain web app: it runs in `vite dev`, can be re-wrapped by a
different shell, or ship as the web companion, without touching feature code.

Adding a native capability = extend the `Shell` interface, implement per shell, give the web
build an honest fallback.

## Deployed

- **https://yap.mehdiacho.tech** — custom domain (CNAME → `yap-desktop.web.app`)
- **https://yap-desktop.web.app** — Firebase Hosting default URL

Deploy with `npm run deploy`. It targets the `yap-desktop` Hosting site, which is separate
from the project's default site (that one serves the admin app — don't deploy over it).

The Cloudflare record for `yap.mehdiacho.tech` **must be DNS-only (grey cloud)**. Proxying it
puts Cloudflare's certificate in front of Firebase's, which breaks domain validation.

## Running it

```bash
npm install && npm run dev
```

Then open http://localhost:5273 and sign in with the same account you use on the phone
(Google, or email + password).

| Script | Does |
|---|---|
| `npm run dev` | Vite dev server on :5273 |
| `npm run build` | Typecheck + production bundle |
| `npm run typecheck` | `tsc --noEmit` |

## Data model

Reads `users/{uid}/sessions/{sessionId}` — the same documents the Android client writes.
`src/data/session.ts` is a 1:1 port of `Session.kt` + the mapping in
`FirestoreSessionRepository.kt`.

> **Field naming:** `docs/API_CONTRACT.md` in the yap repo documents these as snake_case
> (`draft_text`, `formatted_text`, `name`). The shipping Android client writes **camelCase**
> (`draftText`, `formattedText`, `title`), and that is what is actually stored. This client
> follows the live data. The doc is wrong and should be corrected.

Audio streams from Cloud Storage via `storagePath`. `audioPath` is a local path on whichever
device recorded the note and is meaningless here — a note with no `storagePath` simply hasn't
uploaded yet, which the player states plainly rather than failing.

## What works

- Google + email/password sign-in, password reset
- **Import an audio file** — pick or drag-drop; uploads to Storage, calls `transcribeSession`,
  and becomes a note exactly as a phone recording does
- **BYOK** — add your own Gemini key in Settings (validated server-side against the real API
  before it's stored, then encrypted; no client ever reads it back). Transcription runs on it
  with no monthly cap, since you're billed by Google directly.
- Live library: search, filters (All / Pinned / Drafts / Trash), date sections, pinned hoisting
- Note detail: Markdown with GFM tables, Formatted/Raw toggle, "not refined yet" banner
- Audio playback from Storage with clickable bookmark markers; appended recordings
- Pin, trash, restore, delete forever, export as `.md`
- Light/dark/system theming from the kit's token sheet
- Resizable list column (320–520px, persisted), keyboard shortcuts

Shortcuts: `Ctrl+K` search · `↑`/`↓` move through list · `Ctrl+P` pin · `Del` trash · `Esc` clear

## Not built yet

- **Recording** — the Capture surface renders but its button is deliberately inert
  (importing a file is the working path in the meantime)
- **Tauri shell** — see below; window controls and tray are no-ops in the browser
- Smart Review, Polish now, group/formalize, multi-select, subscriptions

## Adding the Tauri shell

Requires the Rust toolchain and MSVC build tools, neither of which is installed yet:

```bash
winget install Rustlang.Rustup
```

Then, from this directory:

```bash
npm install -D @tauri-apps/cli && npx tauri init
```

Point it at `dist` (frontend output), `http://localhost:5273` (dev URL), and `npm run dev` /
`npm run build`. Then install the plugins `shell.ts` expects:

```bash
npm install @tauri-apps/api @tauri-apps/plugin-shell @tauri-apps/plugin-dialog @tauri-apps/plugin-fs
```

`getShell()` detects Tauri at runtime and upgrades automatically — no feature code changes.

**Known issue to handle then:** Firebase Auth authorizes `localhost` by default but *not*
Tauri's `tauri://localhost` origin, so `signInWithPopup` will fail in the packaged app. Fix by
opening the OAuth flow in the system browser and handling the redirect via a deep link, or by
staying on email/password. The sign-in screen already degrades to that path.

## Configuration

`.env` holds the Firebase web config for project `yap-mehdiacho`. It is committed on purpose:
Firebase web config is embedded in the client bundle of every Firebase web app by design.
Access is enforced by Firestore/Storage rules (per-user subtree isolation) and later App Check
— never by hiding these values.
