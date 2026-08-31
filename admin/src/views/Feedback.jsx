import React, { useState } from "react";
import { call } from "../firebase.js";
import { useApi } from "../useApi.js";
import { Err, Loading, Empty, Pill, when, duration } from "../ui.jsx";

const FILTERS = ["NEW", "READ", "DONE", "ALL"];

const TOPIC_LABELS = {
  bug: "Something's broken",
  "transcription-quality": "Transcription quality",
  billing: "Billing or plans",
  "feature-request": "Feature request",
  performance: "Speed or battery",
  design: "How it looks",
  other: "Something else",
};

/**
 * The feedback inbox.
 *
 * Voice messages are played here rather than downloaded, because the reason the app offers
 * recording at all is that people say what they actually think out loud — and that only pays
 * off if listening is as cheap as reading.
 */
export default function Feedback({ onChanged }) {
  const [filter, setFilter] = useState("NEW");
  const { data, loading, error, reload } = useApi("adminListFeedback", { status: filter });
  const [busyId, setBusyId] = useState(null);

  async function setStatus(id, status) {
    setBusyId(id);
    try {
      await call("adminUpdateFeedback", { id, status });
      reload();
      onChanged?.();
    } catch (e) {
      alert(e.message);
    }
    setBusyId(null);
  }

  return (
    <>
      <h1>Feedback</h1>
      <p className="sub">
        Everything sent from Settings → Send feedback, newest first. Typed, spoken, or both.
      </p>

      <div className="row" style={{ marginBottom: 16 }}>
        {FILTERS.map((f) => (
          <button
            key={f}
            className={f === filter ? "primary small" : "small"}
            onClick={() => setFilter(f)}
          >
            {f.toLowerCase()}
          </button>
        ))}
        <button className="small" onClick={reload}>
          Refresh
        </button>
      </div>

      <Err>{error}</Err>
      {loading && <Loading what="Loading feedback" />}
      {data && data.feedback.length === 0 && (
        <Empty>
          {filter === "NEW" ? "Nothing unread." : "Nothing here."}
        </Empty>
      )}

      {data &&
        data.feedback.map((f) => (
          <div key={f.id} className={f.status === "NEW" ? "fb unread" : "fb"}>
            <div className="head">
              <Pill tone={f.topic === "bug" ? "bad" : undefined}>
                {TOPIC_LABELS[f.topic] || f.topic}
              </Pill>
              <span className="mono">{f.email || f.uid}</span>
              <span className="mono">· {when(f.createdAt)}</span>
              {f.status !== "NEW" && <Pill tone={f.status === "DONE" ? "good" : "warn"}>{f.status.toLowerCase()}</Pill>}
            </div>

            {f.message ? (
              <p className="msg">{f.message}</p>
            ) : (
              <p className="msg" style={{ color: "var(--dim)" }}>
                No typed message — voice only.
              </p>
            )}

            {f.voicePath && f.voiceUrl && (
              <audio controls preload="none" src={f.voiceUrl}>
                <a href={f.voiceUrl}>Voice message</a>
              </audio>
            )}
            {f.voicePath && !f.voiceUrl && (
              <div className="err">
                Voice message attached ({duration(f.voiceDurationMs)}) but the link could not be
                signed: {f.voiceError || "unknown reason"}
              </div>
            )}

            <div className="row" style={{ justifyContent: "space-between" }}>
              <span className="meta">
                {[f.platform, f.appVersion && `v${f.appVersion}`, f.device, f.osVersion]
                  .filter(Boolean)
                  .join(" · ") || "no device info"}
              </span>
              <span className="row" style={{ gap: 6 }}>
                {f.status !== "READ" && (
                  <button
                    className="small"
                    disabled={busyId === f.id}
                    onClick={() => setStatus(f.id, "READ")}
                  >
                    Mark read
                  </button>
                )}
                {f.status !== "DONE" && (
                  <button
                    className="small"
                    disabled={busyId === f.id}
                    onClick={() => setStatus(f.id, "DONE")}
                  >
                    Done
                  </button>
                )}
                {f.status !== "NEW" && (
                  <button
                    className="small"
                    disabled={busyId === f.id}
                    onClick={() => setStatus(f.id, "NEW")}
                  >
                    Reopen
                  </button>
                )}
              </span>
            </div>
          </div>
        ))}
    </>
  );
}
