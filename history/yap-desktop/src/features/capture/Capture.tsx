import { useCallback, useRef, useState, type DragEvent } from "react";
import type { User } from "firebase/auth";
import Icon from "../../components/Icon";
import { formatTimestamp, type Session } from "../../data/session";
import {
  ACCEPTED_AUDIO,
  importAudioFile,
  type ImportProgress,
} from "../../data/importAudio";

/*
 * The designed Capture surface. Recording itself is still inert — that arrives with the
 * Tauri shell — but importing a file is fully wired: pick or drop an audio file and it
 * uploads, transcribes, and becomes a note exactly as a phone recording does.
 *
 * The record button stays visible-but-disabled rather than hidden so the layout is honest
 * about what's coming and what isn't.
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
  const inputRef = useRef<HTMLInputElement>(null);
  const [progress, setProgress] = useState<ImportProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  // Nested dragenter/dragleave fire per child element; count depth so the overlay
  // doesn't flicker as the pointer crosses inner nodes.
  const dragDepth = useRef(0);

  const busy = progress != null && progress.stage !== "done" && progress.stage !== "error";

  const runImport = useCallback(
    async (file: File) => {
      setError(null);
      let moved = false;
      try {
        await importAudioFile(user.uid, file, (p) => {
          setProgress(p);
          // Once the upload lands, the note exists and the server owns the rest — it
          // writes the transcript straight to Firestore. Move to the Library so the
          // live status is visible instead of pinning the user to a spinner that may
          // have minutes left to run.
          if (p.stage === "transcribing" && !moved) {
            moved = true;
            onGoLibrary();
          }
        });
        setProgress(null);
      } catch (e) {
        setError((e as Error).message);
        setProgress(null);
      }
    },
    [user.uid, onGoLibrary],
  );

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    dragDepth.current = 0;
    setDragging(false);
    if (busy) return;
    const file = e.dataTransfer.files?.[0];
    if (file) void runImport(file);
  };

  const hour = new Date().getHours();
  const greeting = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
  const name = (user.displayName ?? "").split(" ")[0];

  return (
    <div
      className={`capture${dragging ? " is-dropping" : ""}`}
      onDragEnter={(e) => {
        e.preventDefault();
        dragDepth.current += 1;
        if (!busy) setDragging(true);
      }}
      onDragOver={(e) => e.preventDefault()}
      onDragLeave={(e) => {
        e.preventDefault();
        dragDepth.current -= 1;
        if (dragDepth.current <= 0) setDragging(false);
      }}
      onDrop={onDrop}
    >
      <div className="capture__inner">
        <p className="capture__greeting">
          {greeting}
          {name ? `, ${name}` : ""}
        </p>
        <h1 className="capture__headline">What's on your mind?</h1>

        <button
          className="capture__button"
          disabled
          title="Recording arrives with the Tauri shell"
        >
          <span className="capture__ring" />
          <Icon name="mic" size={44} fill />
        </button>

        <div className="capture__soon">
          <Icon name="construction" size={16} />
          Desktop recording is next — record on your phone, or import a file below.
        </div>

        <input
          ref={inputRef}
          type="file"
          accept={ACCEPTED_AUDIO}
          hidden
          onChange={(e) => {
            const file = e.target.files?.[0];
            e.target.value = ""; // let the same file be picked twice
            if (file) void runImport(file);
          }}
        />

        {busy ? (
          <ImportProgressCard progress={progress!} />
        ) : (
          <>
            <button
              className="import-pill"
              onClick={() => inputRef.current?.click()}
            >
              <Icon name="upload_file" size={19} />
              Import a file
            </button>
            <p className="capture__drophint">
              or drop audio anywhere here to transcribe it
            </p>
          </>
        )}

        {error && (
          <p className="capture__error" role="alert">
            <Icon name="error" size={16} /> {error}
          </p>
        )}

        {latest ? (
          <button className="capture__last" onClick={onGoLibrary}>
            <div className="capture__last-label">Most recent note</div>
            <div className="capture__last-title">{latest.title}</div>
            <div className="capture__last-meta">
              {formatTimestamp(latest.createdAt)} · {latest.category}
            </div>
          </button>
        ) : (
          <p className="capture__none">
            No notes yet — import a recording or capture one on your phone.
          </p>
        )}
      </div>

      {dragging && (
        <div className="dropzone">
          <div className="dropzone__card">
            <Icon name="graphic_eq" size={38} />
            <p>Drop audio here to transcribe</p>
          </div>
        </div>
      )}
    </div>
  );
}

function ImportProgressCard({ progress }: { progress: ImportProgress }) {
  const label =
    progress.stage === "reading"
      ? "Reading file…"
      : progress.stage === "uploading"
        ? `Uploading… ${progress.percent ?? 0}%`
        : "Transcribing — this can take a minute";

  return (
    <div className="importing">
      <div className="importing__row">
        <span className="importing__spinner" />
        <span>{label}</span>
      </div>
      <div className="importing__bar">
        <span
          className={`importing__fill${progress.stage === "transcribing" ? " is-indeterminate" : ""}`}
          style={
            progress.stage === "uploading"
              ? { width: `${progress.percent ?? 0}%` }
              : undefined
          }
        />
      </div>
      <p className="importing__note">
        You can keep working — the note appears in your Library right away.
      </p>
    </div>
  );
}
