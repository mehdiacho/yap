import { useEffect, useState } from "react";
import type { User } from "firebase/auth";
import Icon from "../../components/Icon";
import { getShell, type Shell } from "../../lib/shell";
import type { ThemeChoice } from "../../lib/theme";
import { clearByokKey, getByokStatus, setByokKey, type ByokStatus } from "../../data/byok";

const THEMES: { id: ThemeChoice; label: string; icon: string }[] = [
  { id: "system", label: "System", icon: "contrast" },
  { id: "light", label: "Light", icon: "light_mode" },
  { id: "dark", label: "Dark", icon: "dark_mode" },
];

export default function Settings({
  user,
  theme,
  onTheme,
  noteCount,
  onSignOut,
}: {
  user: User;
  theme: ThemeChoice;
  onTheme: (t: ThemeChoice) => void;
  noteCount: number;
  onSignOut: () => void;
}) {
  const [shell, setShell] = useState<Shell | null>(null);
  useEffect(() => {
    let alive = true;
    getShell().then((s) => alive && setShell(s));
    return () => {
      alive = false;
    };
  }, []);

  return (
    <div className="settings">
      <div className="settings__inner">
        <h1 className="settings__h1">Settings</h1>

        <section className="account">
          <div className="account__avatar">
            {user.photoURL ? (
              <img src={user.photoURL} alt="" />
            ) : (
              <span>{(user.displayName || user.email || "?").charAt(0).toUpperCase()}</span>
            )}
          </div>
          <div className="account__who">
            <div className="account__name">{user.displayName ?? "Signed in"}</div>
            <div className="account__email">{user.email ?? user.uid}</div>
          </div>
          <button className="btn btn--quiet" onClick={onSignOut}>
            Sign out
          </button>
        </section>

        <h2 className="eyebrow">Appearance</h2>
        <div className="group">
          <div className="row">
            <Icon name="palette" size={20} />
            <span className="row__label">Theme</span>
            <div className="seg seg--sm">
              {THEMES.map((t) => (
                <button
                  key={t.id}
                  className={theme === t.id ? "is-on" : ""}
                  onClick={() => onTheme(t.id)}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        <h2 className="eyebrow">Sync</h2>
        <div className="group">
          <div className="row">
            <Icon name="cloud_done" size={20} />
            <span className="row__label">
              Notes synced
              <em className="row__sub">Live from Firestore, cached locally for offline reads</em>
            </span>
            <span className="row__value">{noteCount}</span>
          </div>
          <div className="row">
            <Icon name="devices" size={20} />
            <span className="row__label">
              Shell
              <em className="row__sub">
                {shell?.isDesktop
                  ? "Native desktop window"
                  : "Running in a browser — window controls and tray are inert"}
              </em>
            </span>
            <span className="row__value">{shell?.name ?? "…"}</span>
          </div>
        </div>

        <h2 className="eyebrow">AI key</h2>
        <ByokSection />

        <h2 className="eyebrow">About</h2>
        <div className="group">
          <div className="row">
            <Icon name="info" size={20} />
            <span className="row__label">
              Yap Desktop
              <em className="row__sub">Phase 1 — library and sync. Recording is next.</em>
            </span>
            <span className="row__value">0.1.0</span>
          </div>
        </div>

        <p className="settings__foot">
          Signed in as {user.email ?? user.uid} · project yap-mehdiacho
        </p>
      </div>
    </div>
  );
}

/**
 * Bring-your-own-key. Saving is an explicit button, never a debounce on typing: verifying a
 * key means spending a real request against it, and firing that per keystroke would bill
 * the user for their own typing.
 */
function ByokSection() {
  const [status, setStatus] = useState<ByokStatus | null>(null);
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    getByokStatus()
      .then((s) => alive && setStatus(s))
      .catch(
        () =>
          alive &&
          setStatus({ unlocked: false, active: false, provider: null, keyHint: null, updatedAt: null }),
      );
    return () => {
      alive = false;
    };
  }, []);

  if (!status) {
    return (
      <div className="group">
        <div className="row">
          <Icon name="key" size={20} />
          <span className="row__label">Checking…</span>
        </div>
      </div>
    );
  }

  if (!status.unlocked) {
    return (
      <div className="group">
        <div className="row">
          <Icon name="lock" size={20} />
          <span className="row__label">
            Bring your own key
            <em className="row__sub">
              Unlock BYOK in the phone app (one-off purchase, or invite 5 friends) to use your
              own Gemini key with no monthly cap.
            </em>
          </span>
        </div>
      </div>
    );
  }

  const save = async () => {
    const candidate = key.trim();
    if (!candidate) return;
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      const hint = await setByokKey(candidate);
      setStatus({ ...status, active: true, provider: "gemini", keyHint: hint });
      setKey("");
      setNote("Key verified and saved. Transcription now runs on it.");
    } catch (e) {
      // The server tells apart "wrong key" from "right key, empty billing account".
      setError((e as Error)?.message || "Couldn't verify that key.");
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      await clearByokKey();
      setStatus({ ...status, active: false, provider: null, keyHint: null });
      setNote("Key removed — back to the built-in allowance.");
    } catch (e) {
      setError((e as Error)?.message || "Couldn't remove the key.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="group">
      <div className="row">
        <Icon name={status.active ? "key" : "key_off"} size={20} />
        <span className="row__label">
          Gemini key
          <em className="row__sub">
            {status.active
              ? `Active · ending ${status.keyHint ?? "••••"}. You're billed by Google directly, with no monthly cap.`
              : "No key set — transcription uses the built-in monthly allowance."}
          </em>
        </span>
        {status.active && (
          <button className="btn btn--quiet" onClick={remove} disabled={busy}>
            Remove
          </button>
        )}
      </div>
      <div className="row">
        <Icon name="password" size={20} />
        <span className="row__label">
          <input
            className="input"
            type="password"
            value={key}
            placeholder={status.active ? "Replace key — AIza…" : "AIza…"}
            autoComplete="off"
            spellCheck={false}
            disabled={busy}
            onChange={(e) => {
              setKey(e.target.value);
              setError(null);
            }}
            onKeyDown={(e) => e.key === "Enter" && save()}
          />
          {error && <em className="row__sub row__sub--error">{error}</em>}
          {note && !error && <em className="row__sub">{note}</em>}
        </span>
        <button className="btn" onClick={save} disabled={busy || !key.trim()}>
          {busy ? "Verifying…" : "Verify & save"}
        </button>
      </div>
    </div>
  );
}
