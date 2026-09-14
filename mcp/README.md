# yap-mcp — MCP server over your notes

Lets an agent read the Yap note history and reason across it: what was recorded, when,
under which topic, and what action items came out of it. **One write:** `set_follow_up`
records what became of a note, and touches no other field. Nothing else here writes to
Firestore.

It runs two ways, over the same tools (`src/tools.ts`):

- **Locally over stdio** (`src/index.ts`) for Claude Code on this machine, signed in as one
  person with `npm run login`.
- **Remotely as a Claude connector** (`src/worker/`) at
  **`https://yap-mcp.greenforrest.app/mcp`**, for any Yap user, in Claude on the web,
  Desktop, mobile and Cowork. See [As a Claude connector](#as-a-claude-connector).

## Why a refresh token and not an admin key

The obvious way to build this is the Firebase Admin SDK with a service-account key. That
key bypasses `firestore.rules` completely and can read *every* user's notes, and it would
live as a file on this machine.

Instead this signs in as an ordinary Firebase user and keeps that user's refresh token.
`firestore.rules` then adjudicates every read exactly as it does for the phone:

```
match /users/{uid}/{document=**} {
  allow read, write: if request.auth != null && request.auth.uid == uid;
}
```

So the server can reach one account's notes and is structurally incapable of reaching
anyone else's. If the token file leaks, the blast radius is one account and the fix is a
sign-out, not a key rotation across the project.

The connector keeps the same property for every user: each grant carries that person's own
refresh token, so a connected Claude reaches their notes through the same rules and no
one else's. There is still no admin credential anywhere in this folder.

## Setup

```bash
npm install && npm run build
```

Config is the Firebase **web** app config — public values, the same ones the desktop app
ships to browsers. If `X:\Projects\yap-desktop\.env` exists it is read automatically and
there is nothing to set up. Otherwise copy `.env.example` to `.env` and fill it in.

Then sign in once:

```bash
npm run login
```

That serves a page on `http://localhost:5891`, you sign in with Google in your own
browser, and only the refresh token comes back to this process — the Google credentials
never touch it. The token is written to `~/.yap-mcp/credentials.json` (mode 600).
`localhost` is an authorized domain in Firebase Auth by default, so no console change is
needed.

## Registering it with Claude Code

`.mcp.json` at the repo root already does this:

```json
{
  "mcpServers": {
    "yap": {
      "command": "node",
      "args": ["mcp/dist/index.js"]
    }
  }
}
```

The path is relative to the repo root, which is where Claude Code launches the server from.
`dist/` is gitignored, so **on a fresh clone that path does not exist until you run
`npm install && npm run build` in this folder** — the server will simply fail to connect
until you do. Claude Code asks for approval the first time it sees this entry.

## Tools

| Tool | What it does |
|---|---|
| `list_notes` | Notes newest-first with previews. Filters: `since`, `until`, `category`, `topic`, `pinnedOnly`, `includeTrashed`, `followUp` (`none` / `any` / a state). |
| `search_notes` | Literal case-insensitive substring match over title and both text bodies, same filters. |
| `get_note` | One note in full — metadata, action items, and the formalized text if it exists, otherwise the raw transcript. Pass `transcript: true` to get the word-for-word transcript as well; the formalized text is a rewrite. |
| `get_notes` | Up to 20 notes in full in one call, same `transcript` flag, and names any id that matched nothing. |
| `list_topics` | Every category and topic cluster with counts and date spans. The orientation tool — cheapest way to find out what is in there. |
| `list_action_items` | Action items across all notes, with their source note. Open ones by default. |
| `set_follow_up` | **The one write.** Marks a note `pursued`, `dropped` or `converted` with a sentence on what happened, or `clear`s it. PATCHes `followUp` with an update mask, so no other field is ever sent back. |

Trashed notes are excluded everywhere unless asked for. Every read tool is annotated
`readOnlyHint: true`; `set_follow_up` is `readOnlyHint: false, destructiveHint: false,
idempotentHint: true`, which is what Claude uses to decide what to ask before running.

## As a Claude connector

In Claude: **Settings → Connectors → Add custom connector**, URL
`https://yap-mcp.greenforrest.app/mcp`. Claude opens a Yap sign-in page (Google or email),
the person approves, and the connector is available wherever that Claude account is.
Guest accounts are refused: their notes never leave the phone.

**Pieces.** A Cloudflare Worker (`yap-mcp`) that is both the OAuth authorization server
and the MCP resource. `@cloudflare/workers-oauth-provider` does the protocol — protected
resource metadata and the 401 challenge, authorization server metadata, CIMD and DCR
client registration, S256 PKCE, token issuance, refresh rotation and revocation, all in the
`YAP_MCP_OAUTH_KV` namespace. The Worker supplies the sign-in page (`/authorize`) and the
tools at `/mcp`.

**Sign-in.** `/authorize` validates Claude's request and shows the consent page, which runs
the Firebase web SDK. The page posts the Firebase refresh token to
`/authorize/complete`, which checks the Origin, a one-use nonce and a matching `__Host-`
cookie, confirms the token with Google, refuses anonymous accounts, and completes the
grant. The refresh token is stored only inside the grant's props, which the provider
encrypts with key material wrapped by the issued token. Grant metadata (unencrypted) holds
the client name and the uid, nothing personal.

**Transport.** Stateless Streamable HTTP with JSON responses: each POST gets a fresh
`McpServer`; GET is 405. An isolate keeps up to 20 users' libraries warm so the scan cache
and ID tokens survive between requests.

**Why this hostname.** `greenforrest.app` hosts several apps; the name is one level below
the apex because Cloudflare's Universal SSL does not cover deeper names
(`mcp.yap.greenforrest.app` would serve no valid certificate). `workers_dev` and preview
URLs are off, so the custom domain is the only issuer.

**Firebase side.** `yap-mcp.greenforrest.app` is in Firebase Auth's authorized domains, or
the sign-in popup is refused.

**Develop and deploy.**

```bash
npm run worker:check    # type-check the Worker
npm run worker:dev      # https://localhost:8787, self-signed; needs .dev.vars
npm run worker:deploy
```

`.dev.vars` (gitignored) holds `FIREBASE_API_KEY=` and `PUBLIC_ORIGIN=https://localhost:8787`.
In production the key is a Worker secret (`wrangler secret put FIREBASE_API_KEY`). Local dev
has to be https — the provider rejects a non-https issuer — and `--local-upstream` stops
wrangler rewriting the host and `Origin` to the production route.

**On the home network**, AdGuard's `*.greenforrest.app` rewrite sends this name to the NAS
unless an exception exists, and the sign-in page then fails to load in the browser. Claude
itself connects from Anthropic's servers and is unaffected.

## Two things to know about how it reads

**Search is a substring scan, not an index.** Firestore has no full-text search. The
server pulls the library (capped at `YAP_MCP_MAX_SCAN`, default 2000 notes) and filters in
memory. That is fine for a personal note store and would not be for a shared one. There is
no stemming or ranking — "hydroponic" will not find "hydroponics".

**Only the ordering and the `since` bound are pushed to Firestore.** Both ride the
automatic single-field index on `createdAt`, so this server needs **no new Firestore index
deployed**. Category, topic, trash and text filtering all happen locally. Slightly more
read volume, in exchange for never failing because an index was not deployed alongside a
code change.

Results are cached for 10 seconds so a single agent turn that calls three tools does one
read rather than three.

## The schema this maps

Fields come from `FirestoreSessionRepository.toMap()` in the Android app — **camelCase**:
`title`, `draftText`, `formattedText`, `status`, `createdAt` (epoch **milliseconds**),
`durationMs`, `category`, `topicCluster`, `trashedAt`, `pinned`, `actionItems`, `source`,
`sourceRef`.

`docs/API_CONTRACT.md` used to describe a different, snake_case shape with `name`,
`description` and `tags` — written before any client existed, and it never matched the
database. That was corrected on 2026-09-07; the contract now carries a **session document,
as built** table that agrees with this. The Kotlin repository is still the authority if the
two ever drift again.

## `followUp`

```
followUp: { state: "PURSUED" | "DROPPED" | "CONVERTED", note: string | null, updatedAt: <epoch ms>, by: "agent" }
```

Its own field because `status` belongs to the transcription pipeline (`NEW` /
`TRANSCRIBING` / `POLISHING` / `REFINED` / `DONE` / `FAILED` / `QUOTA` / `OFFLINE_PENDING`).
The Android app shows it on the note and never writes it: it is left out of `toMap()`, and
whole-note saves are merges, so a stale copy on the phone cannot erase an outcome recorded
here. An app build older than that change still saves with a plain `set()` and **will** drop
the field when it next saves that note.

## Not here yet

- **Reminders.** Nothing exists in the app or the backend for these yet.
