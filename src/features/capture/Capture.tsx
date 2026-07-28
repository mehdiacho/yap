import type { User } from "firebase/auth";
import Icon from "../../components/Icon";
import { formatTimestamp, type Session } from "../../data/session";

/*
 * The designed Capture surface, with recording itself deliberately inert.
 * Phase 1 of desktop is read/sync only; capture arrives with the Tauri shell
 * (cpal -> raw PCM -> upload). The button is disabled rather than hidden so the
 * layout is real and the gap is explicit instead of implied.
 */
export default function Capture({
  user,
  latest,
  onGoLibrary,
}: {
  user: User;
  latest: Session | null;
  onGoLibrary: () => void;
}) {
  const hour = new Date().getHours();
  const greeting = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
  const name = (user.displayName ?? "").split(" ")[0];

  return (
    <div className="capture">
      <div className="capture__inner">
        <p className="capture__greeting">
          {greeting}
          {name ? `, ${name}` : ""}
        </p>
        <h1 className="capture__headline">What's on your mind?</h1>

        <button className="capture__button" disabled title="Recording arrives with the Tauri shell">
          <span className="capture__ring" />
          <Icon name="mic" size={44} fill />
        </button>

        <div className="capture__soon">
          <Icon name="construction" size={16} />
          Desktop recording is next — for now, record on your phone and it syncs here.
        </div>

        {latest ? (
          <button className="capture__last" onClick={onGoLibrary}>
            <div className="capture__last-label">Most recent note</div>
            <div className="capture__last-title">{latest.title}</div>
            <div className="capture__last-meta">
              {formatTimestamp(latest.createdAt)} · {latest.category}
            </div>
          </button>
        ) : (
          <p className="capture__none">No notes yet — record one on your phone to get started.</p>
        )}
      </div>
    </div>
  );
}
