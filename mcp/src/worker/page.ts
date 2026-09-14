/** The two pages the Worker serves to people: the sign-in/consent page and a landing page. */

const esc = (s: string) =>
  s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

/** JSON that is safe inside a <script> element. */
const scriptJson = (v: unknown) => JSON.stringify(v).replace(/</g, "\\u003c");

const STYLE = `
  :root {
    --ground: #f5f6f3; --card: #ffffff; --ink: #1b1f1c; --muted: #5d655f;
    --line: #dfe3dd; --accent: #1f6a55; --accent-ink: #ffffff; --danger: #a33a2b;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --ground: #121513; --card: #1a1e1b; --ink: #e8ece8; --muted: #9aa39c;
      --line: #2c322e; --accent: #5cc3a0; --accent-ink: #0d1a15; --danger: #f08a78;
    }
  }
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 24px 16px;
         background: var(--ground); color: var(--ink);
         font: 16px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  main { width: 100%; max-width: 30rem; background: var(--card); border: 1px solid var(--line);
         border-radius: 14px; padding: 28px; }
  .brand { margin: 0 0 20px; font-weight: 700; letter-spacing: .02em; }
  h1 { margin: 0 0 12px; font-size: 1.4rem; line-height: 1.25; text-wrap: balance; }
  p, li { color: var(--muted); }
  ul { padding-left: 1.2rem; margin: 0 0 16px; }
  li { margin-bottom: 6px; }
  strong { color: var(--ink); }
  .fine { font-size: .875rem; }
  button { font: inherit; font-weight: 600; width: 100%; padding: 12px 16px; border-radius: 10px;
           border: 1px solid var(--accent); background: var(--accent); color: var(--accent-ink);
           cursor: pointer; }
  button.secondary { background: transparent; color: var(--ink); border-color: var(--line); }
  button:disabled { opacity: .6; cursor: progress; }
  button:focus-visible, input:focus-visible, summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
  details { margin-top: 14px; }
  summary { cursor: pointer; color: var(--muted); font-size: .9rem; }
  form { display: grid; gap: 10px; margin-top: 12px; }
  input { font: inherit; padding: 10px 12px; border-radius: 8px; border: 1px solid var(--line);
          background: var(--ground); color: var(--ink); }
  #status { min-height: 1.5em; margin: 16px 0 0; }
  #status.error { color: var(--danger); }
  code { font-size: .9em; word-break: break-all; }
`;

export interface AuthorizePageInput {
  clientName: string;
  redirectHost: string;
  nonce: string;
  firebase: { apiKey: string; authDomain: string; projectId: string };
}

export function authorizePage(i: AuthorizePageInput): string {
  const client = esc(i.clientName);
  const config = scriptJson({ nonce: i.nonce, firebase: i.firebase });
  return `<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>Connect Yap</title>
<style>${STYLE}</style>
<main>
  <p class="brand">Yap</p>
  <h1>Connect your notes to ${client}</h1>
  <p>After you sign in, <strong>${client}</strong> will be able to:</p>
  <ul>
    <li>Read and search your notes, their transcripts and action items</li>
    <li>Record what became of a note (pursued, dropped or converted), which you'll see on that note in the app</li>
  </ul>
  <p class="fine">It can't edit, delete or create notes. When you're done you'll go back to <code>${esc(i.redirectHost)}</code>.</p>
  <button id="google" type="button">Continue with Google</button>
  <details>
    <summary>Sign in with email instead</summary>
    <form id="email-form">
      <input id="email" type="email" autocomplete="email" placeholder="Email" required>
      <input id="password" type="password" autocomplete="current-password" placeholder="Password" required>
      <button class="secondary" type="submit">Sign in</button>
    </form>
  </details>
  <p id="status" role="status" aria-live="polite"></p>
  <p class="fine">Use the account you sign in to Yap with. Guest notes stay on your phone, so they can't be connected.</p>
</main>
<script type="module">
  import { initializeApp } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js";
  import { getAuth, setPersistence, inMemoryPersistence, GoogleAuthProvider, signInWithPopup, signInWithEmailAndPassword }
    from "https://www.gstatic.com/firebasejs/11.0.2/firebase-auth.js";

  const cfg = ${config};
  const auth = getAuth(initializeApp(cfg.firebase));
  // Nothing about the sign-in should outlive this tab on this domain.
  const ready = setPersistence(auth, inMemoryPersistence);
  const status = document.getElementById("status");
  const buttons = [...document.querySelectorAll("button")];

  function show(message, { busy = false, error = false } = {}) {
    status.textContent = message;
    status.className = error ? "error" : "";
    for (const b of buttons) b.disabled = busy;
  }

  function explain(e) {
    switch (e && e.code) {
      case "auth/popup-closed-by-user":
      case "auth/cancelled-popup-request":
        return "The Google window closed before sign-in finished. Try again.";
      case "auth/popup-blocked":
        return "Your browser blocked the Google window. Allow pop-ups for this page, then try again.";
      case "auth/invalid-credential":
      case "auth/wrong-password":
      case "auth/user-not-found":
        return "That email and password don't match a Yap account.";
      default:
        return (e && e.message) || String(e);
    }
  }

  async function run(signIn) {
    try {
      await ready;
      show("Signing in…", { busy: true });
      const { user } = await signIn();
      show("Connecting…", { busy: true });
      const res = await fetch("/authorize/complete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ nonce: cfg.nonce, refreshToken: user.refreshToken }),
      });
      const out = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(out.error || "Couldn't connect (" + res.status + "). Try again.");
      show("Connected. Taking you back…", { busy: true });
      location.replace(out.redirectTo);
    } catch (e) {
      show(explain(e), { error: true });
    }
  }

  document.getElementById("google").addEventListener("click", () =>
    run(() => signInWithPopup(auth, new GoogleAuthProvider())));
  document.getElementById("email-form").addEventListener("submit", (e) => {
    e.preventDefault();
    run(() => signInWithEmailAndPassword(auth,
      document.getElementById("email").value, document.getElementById("password").value));
  });
</script>
</html>`;
}

export function messagePage(title: string, message: string): string {
  return `<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>${esc(title)}</title>
<style>${STYLE}</style>
<main>
  <p class="brand">Yap</p>
  <h1>${esc(title)}</h1>
  <p>${message}</p>
</main>
</html>`;
}

export function landingPage(origin: string): string {
  return messagePage(
    "Yap for Claude",
    `Connect your Yap notes to Claude: in Claude, open <strong>Settings → Connectors</strong>, ` +
      `choose <strong>Add custom connector</strong> and paste <code>${esc(origin)}/mcp</code>. ` +
      `You'll be asked to sign in with your Yap account.`,
  );
}
