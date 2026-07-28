import { useEffect, useState } from "react";
import type { User } from "firebase/auth";
import Icon from "../../components/Icon";
import { getShell, type Shell } from "../../lib/shell";
import type { ThemeChoice } from "../../lib/theme";

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
