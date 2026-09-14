import React from "react";

/** A number with a label. */
export function Stat({ n, k, hint }) {
  return (
    <div className="card stat">
      <div className="n">{n}</div>
      <div className="k">{k}</div>
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

export function Err({ children }) {
  if (!children) return null;
  return <div className="err">{children}</div>;
}

/**
 * One place that decides what "still working" looks like.
 *
 * Every view here is a call to a cold-startable function in africa-south1, so the first load
 * of a tab can genuinely take a couple of seconds. Rendering "No users" while the request is
 * still in flight would read as a broken backend rather than a slow one.
 */
export function Loading({ what = "Loading" }) {
  return <div className="empty">{what}…</div>;
}

export function Empty({ children }) {
  return <div className="empty">{children}</div>;
}

/** `title` is the hover explanation. Activity has been passing one since it was written and
 *  this dropped it on the floor, so a failed note's reason was never actually readable. */
export function Pill({ tone, title, children }) {
  return (
    <span className={tone ? `pill ${tone}` : "pill"} title={title}>
      {children}
    </span>
  );
}

/** Local time, because whoever is reading this is in one place and it is not UTC. */
export function when(ms) {
  if (!ms) return "—";
  const d = typeof ms === "number" ? new Date(ms) : new Date(String(ms));
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString(undefined, {
    day: "numeric",
    month: "short",
    year: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function ago(ms) {
  if (!ms) return "—";
  const t = typeof ms === "number" ? ms : Date.parse(ms);
  if (!Number.isFinite(t)) return "—";
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

export function duration(ms) {
  const total = Math.round((ms || 0) / 1000);
  if (total < 60) return `${total}s`;
  if (total < 3600) return `${Math.floor(total / 60)}m`;
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  return m ? `${h}h ${m}m` : `${h}h`;
}
