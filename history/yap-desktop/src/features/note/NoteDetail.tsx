import { useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import Icon from "../../components/Icon";
import AudioPlayer from "./AudioPlayer";
import StatusChip from "../library/StatusChip";
import { getShell } from "../../lib/shell";
import {
  deleteForever,
  moveToTrash,
  restoreFromTrash,
  setPinned,
} from "../../data/sessionsRepo";
import {
  displayText,
  formatDuration,
  formatTimestamp,
  isRefined,
  type Session,
} from "../../data/session";

type View = "formatted" | "raw";

export default function NoteDetail({
  session: s,
  uid,
  inTrash,
}: {
  session: Session | null;
  uid: string;
  inTrash: boolean;
}) {
  const [view, setView] = useState<View>("formatted");
  const [busy, setBusy] = useState(false);

  // Reset to the polished view whenever a different note opens.
  useEffect(() => setView("formatted"), [s?.id]);

  if (!s) {
    return (
      <div className="detail detail--empty">
        <Icon name="article" size={40} />
        <p className="detail__empty-title">Select a note</p>
        <p className="detail__empty-body">
          Pick something on the left, or use ↑ and ↓ to move through the list.
        </p>
      </div>
    );
  }

  const refined = isRefined(s);
  const body = view === "raw" || !refined ? s.draftText : displayText(s);

  const exportMd = async () => {
    const shell = await getShell();
    const front = [
      `# ${s.title}`,
      "",
      `*${formatTimestamp(s.createdAt)} · ${s.category} · ${s.topicCluster}*`,
      "",
      "---",
      "",
    ].join("\n");
    const safe = s.title.replace(/[^\w\s-]/g, "").trim() || "note";
    await shell.saveTextFile(`${safe}.md`, front + body);
  };

  const guard = async (fn: () => Promise<void>) => {
    setBusy(true);
    try {
      await fn();
    } catch (e) {
      console.error(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="detail">
      <header className="detail__head">
        <div className="detail__headline">
          <h1 className="detail__title">{s.title}</h1>
          <div className="detail__actions chrome">
            {!inTrash && (
              <button
                className={`iconbtn${s.pinned ? " is-on" : ""}`}
                disabled={busy}
                onClick={() => guard(() => setPinned(uid, s.id, !s.pinned))}
                title={s.pinned ? "Unpin (Ctrl+P)" : "Pin (Ctrl+P)"}
              >
                <Icon name="keep" size={19} fill={s.pinned} />
              </button>
            )}
            <button className="iconbtn" onClick={exportMd} title="Export as .md">
              <Icon name="download" size={19} />
            </button>
            {inTrash ? (
              <>
                <button
                  className="iconbtn"
                  disabled={busy}
                  onClick={() => guard(() => restoreFromTrash(uid, s.id))}
                  title="Restore"
                >
                  <Icon name="restore_from_trash" size={19} />
                </button>
                <button
                  className="iconbtn iconbtn--danger"
                  disabled={busy}
                  onClick={() =>
                    confirm(`Permanently delete “${s.title}”? This can't be undone.`) &&
                    guard(() => deleteForever(uid, s.id))
                  }
                  title="Delete forever"
                >
                  <Icon name="delete_forever" size={19} />
                </button>
              </>
            ) : (
              <button
                className="iconbtn"
                disabled={busy}
                onClick={() => guard(() => moveToTrash(uid, s.id))}
                title="Move to trash (Del)"
              >
                <Icon name="delete" size={19} />
              </button>
            )}
          </div>
        </div>

        <div className="detail__chips">
          <StatusChip session={s} />
          <span className="tag">{s.topicCluster}</span>
          <span className="detail__meta">{formatTimestamp(s.createdAt)}</span>
          {s.durationMs > 0 && (
            <span className="detail__meta">{formatDuration(s.durationMs)}</span>
          )}
        </div>

        {refined ? (
          <div className="seg" role="tablist" aria-label="Text version">
            <button
              role="tab"
              aria-selected={view === "formatted"}
              className={view === "formatted" ? "is-on" : ""}
              onClick={() => setView("formatted")}
            >
              Formatted
            </button>
            <button
              role="tab"
              aria-selected={view === "raw"}
              className={view === "raw" ? "is-on" : ""}
              onClick={() => setView("raw")}
            >
              Raw
            </button>
          </div>
        ) : (
          <div className="banner">
            <Icon name="auto_awesome" size={17} />
            <div>
              <strong>Not refined yet</strong>
              <span>
                This is the on-device draft. Polish it from the phone app — desktop polish
                lands with the recording features.
              </span>
            </div>
          </div>
        )}
      </header>

      <div className="detail__scroll">
        {/*
         * A failed note is not an empty note. Without this the page rendered "This note has
         * no text.", which reads as "nothing was recorded" — the opposite of the truth. The
         * audio is intact; only the transcription didn't land, and statusMessage says why.
         */}
        {s.status === "FAILED" && (
          <div className="banner banner--error" role="status">
            <Icon name="error" size={17} />
            <div>
              <strong>Transcription didn&apos;t finish</strong>
              <span>
                {s.statusMessage?.trim() ||
                  "Your recording is safe — only the transcription failed."}
                {s.statusReason === "PROVIDER_CREDIT" &&
                  " Retry from the phone app once it's topped up."}
              </span>
            </div>
          </div>
        )}
        {s.status === "QUOTA" && (
          <div className="banner banner--error" role="status">
            <Icon name="error" size={17} />
            <div>
              <strong>Monthly limit reached</strong>
              <span>
                The audio is saved. It can be transcribed after an upgrade, a promo code, or
                when the cycle resets.
              </span>
            </div>
          </div>
        )}
        <article className="md">
          {body.trim() ? (
            view === "raw" || !refined ? (
              <pre className="md__raw">{body}</pre>
            ) : (
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{body}</ReactMarkdown>
            )
          ) : (
            <p className="md__empty">This note has no text.</p>
          )}
        </article>

        {s.extraRecordings.length > 0 && (
          <section className="extras">
            <h2 className="eyebrow">
              Appended recordings · {s.extraRecordings.length}
            </h2>
            {s.extraRecordings.map((r, i) => (
              <AudioPlayer
                key={`${r.storagePath ?? "none"}-${i}`}
                storagePath={r.storagePath}
                durationMs={r.durationMs}
                bookmarks={r.bookmarks}
              />
            ))}
          </section>
        )}
      </div>

      <footer className="detail__player">
        <AudioPlayer
          storagePath={s.storagePath}
          durationMs={s.durationMs}
          bookmarks={s.bookmarks}
        />
      </footer>
    </div>
  );
}
