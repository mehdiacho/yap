import React from "react";
import { useApi } from "../useApi.js";
import { Err, Loading, Empty, Pill, when, duration } from "../ui.jsx";

const SOURCE_LABELS = {
  RECORDING: "recorded",
  SHARED_AUDIO: "shared audio",
  SHARED_VIDEO: "shared video",
  SHARED_TEXT: "shared text",
  RECOVERED: "recovered",
  UNKNOWN: "unknown",
};

/**
 * Recent notes across every account — how many, how long, and whether they worked.
 *
 * There is deliberately no transcript here and the backend does not send one. Watching that
 * the pipeline is healthy does not require reading what anybody said, and an operations
 * screen that quietly shows every user's notes is a privacy incident waiting to be
 * discovered by someone else.
 */
export default function Activity() {
  const { data, loading, error, reload } = useApi("adminRecentActivity", { limit: 60 });

  const failed = data?.sessions.filter((s) => s.status === "FAILED").length ?? 0;

  return (
    <>
      <h1>Activity</h1>
      <p className="sub">
        The last notes created anywhere in the app. Volume and outcome only — no transcripts.
      </p>

      <div className="row" style={{ marginBottom: 16 }}>
        <button className="small" onClick={reload}>
          Refresh
        </button>
        {data && (
          <span className="mono">
            {data.sessions.length} notes · {failed} failed
          </span>
        )}
      </div>

      <Err>{error}</Err>
      {loading && <Loading what="Loading activity" />}
      {data && data.sessions.length === 0 && <Empty>No notes yet.</Empty>}

      {data && data.sessions.length > 0 && (
        <div className="card" style={{ padding: "16px 4px" }}>
          <table>
            <thead>
              <tr>
                <th>When</th>
                <th>Account</th>
                <th>Length</th>
                <th>Origin</th>
                <th>Category</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {data.sessions.map((s) => (
                <tr key={s.id}>
                  <td className="mono">{when(s.createdAt)}</td>
                  <td className="mono">{s.uid ? s.uid.slice(0, 10) : "—"}</td>
                  <td className="mono">{duration(s.durationMs)}</td>
                  <td className="mono">{SOURCE_LABELS[s.source] || s.source}</td>
                  <td className="mono">{s.category || "—"}</td>
                  <td>
                    {s.status === "FAILED" ? (
                      <Pill tone="bad" title={s.statusReason}>
                        {s.statusReason || "failed"}
                      </Pill>
                    ) : s.status === "QUOTA" ? (
                      <Pill tone="warn">over cap</Pill>
                    ) : s.status === "TRANSCRIBING" ? (
                      <Pill tone="warn">working</Pill>
                    ) : (
                      <Pill tone="good">done</Pill>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
