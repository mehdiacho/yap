#!/usr/bin/env node
/**
 * One-time sign-in. Puts a refresh token for one ordinary Yap account on disk so the MCP
 * server can read that account's notes — and nothing else's.
 *
 * The sign-in happens in a real browser rather than here, because the account is a Google
 * one: there is no password this process could take, and asking for one would be the wrong
 * answer even if there were. This serves a page on localhost, that page runs the same
 * Firebase web SDK the desktop app uses, and it hands back only the refresh token. The
 * Google credentials themselves never touch this process.
 *
 * localhost is an authorized domain in Firebase Auth by default, so no console change is
 * needed to make this work.
 */
import { createServer } from "node:http";
import { loadConfig } from "./config.js";
import { saveCredentials } from "./auth.js";

const cfg = loadConfig();
const PORT = Number(process.env.YAP_MCP_LOGIN_PORT ?? 5891);

const page = `<!doctype html>
<meta charset="utf-8">
<title>Sign in to Yap</title>
<style>
  :root { color-scheme: light dark; }
  body { font: 16px/1.6 system-ui, sans-serif; display: grid; place-items: center;
         min-height: 100vh; margin: 0; text-align: center; }
  main { max-width: 32rem; padding: 2rem; }
  button { font: inherit; padding: .7rem 1.4rem; border-radius: .5rem; cursor: pointer;
           border: 1px solid currentColor; background: transparent; }
  #status { margin-top: 1.5rem; opacity: .8; }
  code { font-size: .9em; }
</style>
<main>
  <h1>Sign in to Yap</h1>
  <p>This authorizes the local MCP server to read your notes. It gets exactly the access
     the phone app has — your own notes, nothing else.</p>
  <button id="go">Continue with Google</button>
  <p id="status"></p>
</main>
<script type="module">
  import { initializeApp } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js";
  import { getAuth, GoogleAuthProvider, signInWithPopup }
    from "https://www.gstatic.com/firebasejs/11.0.2/firebase-auth.js";

  const app = initializeApp({
    apiKey: ${JSON.stringify(cfg.apiKey)},
    authDomain: ${JSON.stringify(cfg.authDomain)},
    projectId: ${JSON.stringify(cfg.projectId)},
  });
  const auth = getAuth(app);
  const status = document.getElementById("status");

  document.getElementById("go").addEventListener("click", async () => {
    status.textContent = "Opening Google…";
    try {
      const cred = await signInWithPopup(auth, new GoogleAuthProvider());
      status.textContent = "Signed in. Saving…";
      const res = await fetch("/callback", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          refreshToken: cred.user.refreshToken,
          uid: cred.user.uid,
          email: cred.user.email,
        }),
      });
      status.textContent = res.ok
        ? "Done. You can close this tab."
        : "Saving failed — check the terminal.";
    } catch (e) {
      status.textContent = "Sign-in failed: " + (e && e.message ? e.message : e);
    }
  });
</script>
`;

const server = createServer((req, res) => {
  if (req.method === "POST" && req.url === "/callback") {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      try {
        const b = JSON.parse(raw) as { refreshToken: string; uid: string; email?: string };
        if (!b.refreshToken || !b.uid) throw new Error("no token in callback");
        saveCredentials(cfg, { ...b, savedAt: new Date().toISOString() });
        res.writeHead(204).end();
        console.log(`Signed in as ${b.email ?? b.uid}.`);
        console.log(`Token saved to ${cfg.credentialsPath}`);
        server.close();
        // The browser still has a response to finish reading; give it a beat.
        setTimeout(() => process.exit(0), 250);
      } catch (e) {
        res.writeHead(400).end(String(e));
      }
    });
    return;
  }
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" }).end(page);
});

server.listen(PORT, () => {
  console.log(`Open http://localhost:${PORT} and sign in with the Google account that owns your Yap notes.`);
});
