import { useEffect, useState } from "react";
import Icon from "./Icon";
import Logo from "./Logo";
import { getShell, type Shell } from "../lib/shell";

/*
 * §09: 44px fixed custom chrome — logo+wordmark left, centred Record pill,
 * window controls right. In the browser the controls are hidden (the real
 * browser chrome is already there); under Tauri they drive the native window.
 */
export default function TitleBar({ onRecord }: { onRecord: () => void }) {
  const [shell, setShell] = useState<Shell | null>(null);

  useEffect(() => {
    let alive = true;
    getShell().then((s) => alive && setShell(s));
    return () => {
      alive = false;
    };
  }, []);

  return (
    <header className="titlebar chrome" data-tauri-drag-region>
      <div className="titlebar__brand">
        <Logo size={22} radius={7} />
        <span className="titlebar__wordmark">Yap</span>
      </div>

      <button className="record-pill" onClick={onRecord} title="Start recording (Ctrl+R)">
        <span className="record-pill__dot" />
        Record
        <kbd className="record-pill__kbd">Ctrl+R</kbd>
      </button>

      {shell?.isDesktop ? (
        <div className="titlebar__controls">
          <button className="wctl" onClick={() => shell.minimize()} title="Minimize">
            <Icon name="remove" size={18} />
          </button>
          <button className="wctl" onClick={() => shell.toggleMaximize()} title="Maximize">
            <Icon name="crop_square" size={16} />
          </button>
          <button className="wctl wctl--close" onClick={() => shell.close()} title="Close">
            <Icon name="close" size={18} />
          </button>
        </div>
      ) : (
        <div className="titlebar__controls" />
      )}
    </header>
  );
}
