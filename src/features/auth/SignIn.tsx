import { useState, type FormEvent } from "react";
import {
  GoogleAuthProvider,
  signInWithEmailAndPassword,
  signInWithPopup,
  sendPasswordResetEmail,
} from "firebase/auth";
import { auth } from "../../lib/firebase";
import { shellIsDesktop } from "../../lib/shell";
import Logo from "../../components/Logo";
import Icon from "../../components/Icon";

/*
 * Sign-in only — no account creation. Desktop is a companion to the phone app,
 * where accounts are made; creating one here would just be a second path to
 * get wrong. Providers match AuthManager.kt: Google + email/password.
 */
export default function SignIn() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const friendly = (code: string): string => {
    switch (code) {
      case "auth/invalid-email":
        return "That doesn't look like an email address.";
      case "auth/user-not-found":
      case "auth/wrong-password":
      case "auth/invalid-credential":
        return "Email or password is incorrect.";
      case "auth/too-many-requests":
        return "Too many attempts. Try again in a few minutes.";
      case "auth/network-request-failed":
        return "Can't reach Firebase — check your connection.";
      case "auth/popup-blocked":
      case "auth/popup-closed-by-user":
        return "The Google sign-in window was closed before finishing.";
      case "auth/operation-not-supported-in-this-environment":
        return "Google sign-in isn't available in this window yet — use email and password.";
      default:
        return code.replace(/^auth\//, "").replace(/-/g, " ");
    }
  };

  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await fn();
    } catch (e) {
      const code = (e as { code?: string })?.code ?? "unknown error";
      setError(friendly(code));
    } finally {
      setBusy(false);
    }
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!email || !password) {
      setError("Enter your email and password.");
      return;
    }
    void run(() => signInWithEmailAndPassword(auth, email.trim(), password));
  };

  const onGoogle = () => void run(() => signInWithPopup(auth, new GoogleAuthProvider()));

  const onReset = () => {
    if (!email.trim()) {
      setError("Enter your email first, then choose Forgot password.");
      return;
    }
    void run(async () => {
      await sendPasswordResetEmail(auth, email.trim());
      setNotice(`Reset link sent to ${email.trim()}.`);
    });
  };

  return (
    <div className="signin">
      <div className="signin__card">
        <Logo size={64} radius={18} />
        <h1 className="signin__title">Yap</h1>
        <p className="signin__sub">
          Sign in to reach the notes you captured on your phone.
        </p>

        <button className="btn btn--google" onClick={onGoogle} disabled={busy}>
          <svg width="17" height="17" viewBox="0 0 48 48" aria-hidden="true">
            <path
              fill="#4285F4"
              d="M45.1 24.5c0-1.6-.1-2.7-.4-3.9H24v7.1h12.1c-.2 1.8-1.6 4.6-4.5 6.4l-.1.3 6.5 5 .5.1c4.1-3.8 6.6-9.5 6.6-15z"
            />
            <path
              fill="#34A853"
              d="M24 46c5.9 0 10.9-2 14.5-5.3l-6.9-5.4c-1.9 1.3-4.4 2.2-7.6 2.2-5.8 0-10.7-3.8-12.5-9.1l-.3.02-6.8 5.2-.1.3C7.9 41 15.4 46 24 46z"
            />
            <path
              fill="#FBBC05"
              d="M11.5 28.4c-.5-1.4-.8-2.9-.8-4.4s.3-3 .7-4.4l-.01-.3-6.9-5.3-.2.1C2.8 17 2 20.4 2 24s.8 7 2.3 10l7.2-5.6z"
            />
            <path
              fill="#EA4335"
              d="M24 10.2c4.1 0 6.9 1.8 8.5 3.3l6.2-6C34.9 4 29.9 2 24 2 15.4 2 7.9 7 4.3 14l7.2 5.6C13.3 14 18.2 10.2 24 10.2z"
            />
          </svg>
          Continue with Google
        </button>

        {shellIsDesktop && (
          <p className="signin__hint">
            <Icon name="info" size={14} /> If the Google window doesn't open, sign in with
            email below.
          </p>
        )}

        <div className="signin__or">
          <span />
          <em>or</em>
          <span />
        </div>

        <form onSubmit={onSubmit} className="signin__form">
          <label className="field">
            <span>Email</span>
            <input
              type="email"
              value={email}
              autoComplete="username"
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
            />
          </label>

          <label className="field">
            <span>Password</span>
            <input
              type="password"
              value={password}
              autoComplete="current-password"
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
            />
          </label>

          <button className="btn btn--primary" type="submit" disabled={busy}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>

        <button className="signin__link" onClick={onReset} disabled={busy}>
          Forgot password?
        </button>

        {error && (
          <p className="signin__error" role="alert">
            <Icon name="error" size={16} /> {error}
          </p>
        )}
        {notice && (
          <p className="signin__notice" role="status">
            <Icon name="mark_email_read" size={16} /> {notice}
          </p>
        )}
      </div>
    </div>
  );
}
