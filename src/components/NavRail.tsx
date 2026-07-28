import type { User } from "firebase/auth";
import Icon from "./Icon";

export type RailTab = "capture" | "library" | "settings";

const ITEMS: { id: RailTab; icon: string; label: string }[] = [
  { id: "capture", icon: "mic", label: "Capture" },
  { id: "library", icon: "library_books", label: "Library" },
  { id: "settings", icon: "settings", label: "Settings" },
];

/*
 * §09: 78px fixed, always visible, never collapses to a hamburger.
 * 64px-wide active pill at 14px radius; avatar pinned to the bottom.
 */
export default function NavRail({
  active,
  onChange,
  user,
  onAvatarClick,
}: {
  active: RailTab;
  onChange: (t: RailTab) => void;
  user: User | null;
  onAvatarClick: () => void;
}) {
  const initial = (user?.displayName || user?.email || "?").trim().charAt(0).toUpperCase();

  return (
    <nav className="rail chrome" aria-label="Primary">
      <div className="rail__items">
        {ITEMS.map((it) => {
          const on = active === it.id;
          return (
            <button
              key={it.id}
              className={`rail__item${on ? " is-active" : ""}`}
              onClick={() => onChange(it.id)}
              aria-current={on ? "page" : undefined}
            >
              <span className="rail__pill">
                <Icon name={it.icon} size={22} fill={on} />
              </span>
              <span className="rail__label">{it.label}</span>
            </button>
          );
        })}
      </div>

      <button
        className="rail__avatar"
        onClick={onAvatarClick}
        title={user?.email ?? "Account"}
        aria-label="Account"
      >
        {user?.photoURL ? <img src={user.photoURL} alt="" /> : <span>{initial}</span>}
      </button>
    </nav>
  );
}
