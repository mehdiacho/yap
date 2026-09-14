import React, { useMemo, useState } from "react";
import { call } from "../firebase.js";
import { useApi } from "../useApi.js";
import { Err, Loading, Empty, Pill, when, ago, duration } from "../ui.jsx";

const PLANS = ["free", "pro", "byok"];

/**
 * Accounts.
 *
 * The plain newest-first list was true and nearly useless. On 2026-09-08 it held 298
 * accounts: 262 anonymous guests, ~23 Google accounts belonging to the Play pre-launch
 * report's device rack, a handful of our own test logins — and eight accounts in total that
 * had ever saved a single note. Finding the real people meant reading uids.
 *
 * So the list now arrives segmented and filtered on two questions: where did this account
 * come from, and has it ever done anything. Every row carries its own answer, and the
 * segment chips carry the counts.
 */

/** Segments, in the order they are worth looking at. */
const SEGMENTS = [
  { id: "all", label: "All", of: () => true },
  { id: "active", label: "Have recorded", of: (u) => u.notes > 0 },
  { id: "tester", label: "Testers", of: (u) => u.kind === "tester" },
  { id: "person", label: "People", of: (u) => u.kind === "person" },
  { id: "guest", label: "Guests", of: (u) => u.kind === "guest" },
  { id: "crawler", label: "Play crawler", of: (u) => u.kind === "crawler" },
  { id: "test", label: "Our tests", of: (u) => u.kind === "test" },
];

const KIND_TONE = { tester: "good", person: "good", crawler: undefined, test: "warn", guest: undefined };
const KIND_LABEL = { tester: "tester", person: "person", crawler: "crawler", test: "test", guest: "guest" };

export default function Users() {
  const { data, loading, error, reload } = useApi("adminListUsers");
  const [segment, setSegment] = useState("all");
  const [text, setText] = useState("");
  const [plan, setPlan] = useState("any");
  const [open, setOpen] = useState(null); // uid of the expanded row
  const [remote, setRemote] = useState(null); // server lookup result, when the filter finds nothing
  const [remoteErr, setRemoteErr] = useState(null);

  const users = data?.users ?? [];
  const counts = useMemo(() => {
    const c = {};
    for (const s of SEGMENTS) c[s.id] = users.filter(s.of).length;
    return c;
  }, [users]);

  const shown = useMemo(() => {
    const seg = SEGMENTS.find((s) => s.id === segment) ?? SEGMENTS[0];
    const q = text.trim().toLowerCase();
    return users.filter((u) => {
      if (!seg.of(u)) return false;
      if (plan !== "any" && u.plan !== plan) return false;
      if (!q) return true;
      return (
        (u.email ?? "").toLowerCase().includes(q) ||
        (u.displayName ?? "").toLowerCase().includes(q) ||
        u.uid.toLowerCase().includes(q)
      );
    });
  }, [users, segment, plan, text]);

  /**
   * The filter above searches what is on screen, which is everything — unless the backend
   * said it truncated. This is the escape hatch for that case, and for a uid out of a crash
   * report that predates whatever is loaded.
   */
  async function lookUp() {
    setRemote(null);
    setRemoteErr(null);
    try {
      setRemote(await call("adminSearchUser", { query: text.trim() }));
    } catch (e) {
      setRemoteErr(e.message);
    }
  }

  return (
    <>
      <h1>Accounts</h1>
      <p className="sub">
        Everyone with an account, and what each one has actually done. Most accounts here are
        guests the app signed in automatically or robots from the Play pre-launch report —
        the segments tell them apart from people.
      </p>

      <Err>{error}</Err>
      {loading && <Loading what="Loading accounts" />}

      {data && (
        <>
          <div className="row" style={{ marginBottom: 10 }}>
            {SEGMENTS.map((s) => (
              <button
                key={s.id}
                className={`chip${s.id === segment ? " on" : ""}`}
                onClick={() => setSegment(s.id)}
              >
                {s.label} <span className="n">{counts[s.id]}</span>
              </button>
            ))}
          </div>

          <div className="row" style={{ marginBottom: 14 }}>
            <input
              placeholder="filter by email, name or uid"
              value={text}
              onChange={(e) => setText(e.target.value)}
              style={{ maxWidth: 320 }}
            />
            <select value={plan} onChange={(e) => setPlan(e.target.value)} style={{ width: 130 }}>
              <option value="any">any plan</option>
              {PLANS.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
            <button className="small" onClick={reload}>
              Refresh
            </button>
            <span className="mono">
              {shown.length} of {users.length} accounts · {counts.active} have ever recorded
              {data.truncated ? " · list truncated" : ""}
            </span>
          </div>

          {shown.length === 0 && (
            <Empty>
              Nothing matches.{" "}
              {text.trim() && (
                <button className="small" onClick={lookUp}>
                  Look "{text.trim()}" up on the server
                </button>
              )}
            </Empty>
          )}
          <Err>{remoteErr}</Err>
          {remote && (
            <div className="card" style={{ marginBottom: 10 }}>
              <div style={{ fontWeight: 600 }}>{remote.email || "No email"}</div>
              <div className="mono">{remote.uid}</div>
              <div className="mono" style={{ marginTop: 6 }}>
                {remote.plan} · joined {when(remote.created)} ·{" "}
                {Math.round((remote.usage?.secondsUsed ?? 0) / 60)} min used
              </div>
            </div>
          )}

          {shown.length > 0 && (
            <div className="card" style={{ padding: "16px 4px" }}>
              <table>
                <thead>
                  <tr>
                    <th>Account</th>
                    <th>Kind</th>
                    <th>Plan</th>
                    <th>Notes</th>
                    <th>Recorded</th>
                    <th>Joined</th>
                    <th>Last active</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((u) => (
                    <React.Fragment key={u.uid}>
                      <tr
                        onClick={() => setOpen(open === u.uid ? null : u.uid)}
                        style={{ cursor: "pointer" }}
                      >
                        <td>
                          <div>
                            {u.email || <span className="mono">guest · no email</span>}{" "}
                            {u.admin && <Pill tone="good">admin</Pill>}{" "}
                            {u.disabled && <Pill tone="bad">disabled</Pill>}
                          </div>
                          <div className="mono">{u.displayName || u.uid}</div>
                        </td>
                        <td>
                          <Pill tone={KIND_TONE[u.kind]} title={u.tagNote || undefined}>
                            {KIND_LABEL[u.kind] ?? u.kind}
                          </Pill>
                          {u.taggedBy && <div className="mono">by hand</div>}
                        </td>
                        <td>
                          <Pill tone={u.plan !== "free" ? "good" : undefined}>{u.plan}</Pill>
                        </td>
                        <td className="mono">
                          {u.notes || "—"}
                          {u.lastNoteAt ? <div>last {ago(u.lastNoteAt)}</div> : null}
                        </td>
                        <td className="mono">
                          {u.recordedSeconds ? duration(u.recordedSeconds * 1000) : "—"}
                        </td>
                        <td className="mono">{when(u.created)}</td>
                        <td className="mono">{ago(u.lastActive)}</td>
                      </tr>
                      {open === u.uid && (
                        <tr>
                          <td colSpan={7} style={{ background: "var(--panel-2)" }}>
                            <UserCard user={u} onSaved={reload} />
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {segment === "guest" && <GuestCleanup onDone={reload} />}
        </>
      )}
    </>
  );
}

/** One account: how it is labelled, and what it is entitled to. */
function UserCard({ user, onSaved }) {
  const [plan, setPlan] = useState(user.plan || "free");
  const [days, setDays] = useState("");
  const [byok, setByok] = useState(!!user.byokUnlocked);
  const [kind, setKind] = useState(user.taggedBy ? user.kind : "");
  const [note, setNote] = useState(user.tagNote ?? "");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState(null);
  const [err, setErr] = useState(null);

  async function run(what, fn) {
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      await fn();
      setMsg(what);
      onSaved?.();
    } catch (e) {
      setErr(e.message);
    }
    setBusy(false);
  }

  return (
    <div style={{ display: "grid", gap: 14, padding: "4px 0 8px" }}>
      <div className="mono">
        {user.uid} · {user.providers.length ? user.providers.join(", ") : "anonymous"} ·{" "}
        {Math.round((user.usedSeconds ?? 0) / 60)} min charged this cycle
        {user.usageCycle ? ` (${user.usageCycle})` : ""} ·{" "}
        {user.expiresAt ? `plan expires ${when(user.expiresAt)}` : "no expiry"}
      </div>

      {/* Labelling. The listing guesses from the email address; this is where a guess about
          a real person gets corrected, and the only way to say "this one is a tester". */}
      <div className="row">
        <select value={kind} onChange={(e) => setKind(e.target.value)} style={{ width: 150 }}>
          <option value="">auto ({user.taggedBy ? "cleared" : user.kind})</option>
          <option value="tester">tester</option>
          <option value="person">person</option>
          <option value="test">our test</option>
          <option value="crawler">Play crawler</option>
          <option value="guest">guest</option>
        </select>
        <input
          placeholder="note (who they are, where they came from)"
          value={note}
          onChange={(e) => setNote(e.target.value)}
          style={{ maxWidth: 340 }}
        />
        <button
          className="small"
          disabled={busy}
          onClick={() =>
            run("Label saved.", () =>
              call("adminTagUser", { uid: user.uid, kind: kind || null, note: note || null })
            )
          }
        >
          Save label
        </button>
      </div>

      <div className="row">
        <select value={plan} onChange={(e) => setPlan(e.target.value)} style={{ width: 130 }}>
          {PLANS.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </select>
        <input
          placeholder="days (blank = forever)"
          value={days}
          onChange={(e) => setDays(e.target.value.replace(/[^0-9]/g, ""))}
          style={{ width: 180 }}
        />
        <label className="row" style={{ gap: 6, color: "var(--dim)" }}>
          <input
            type="checkbox"
            checked={byok}
            onChange={(e) => setByok(e.target.checked)}
            style={{ width: 16 }}
          />
          own API key
        </label>
        <button
          className="primary"
          disabled={busy}
          onClick={() =>
            run("Entitlement saved.", () =>
              call("adminSetEntitlement", {
                uid: user.uid,
                plan,
                // An empty box means "no expiry", which the backend represents by deleting
                // the field. Sending 0 would expire the plan immediately — the opposite.
                ...(days.trim() ? { days: Number(days) } : {}),
                byokUnlocked: byok,
              })
            )
          }
        >
          Apply plan
        </button>
        {msg && <span className="ok">{msg}</span>}
      </div>
      <Err>{err}</Err>
    </div>
  );
}

/**
 * Deleting the guests that never did anything.
 *
 * Two steps on purpose. The first only counts, and it is the only step that runs unless the
 * word DELETE is typed — this removes auth users and nothing brings them back. Accounts that
 * saved even one note are never in scope: the tester who filed three bugs on 2026-09-08 was
 * an anonymous guest with two notes to his name at the time.
 */
function GuestCleanup({ onDone }) {
  const [days, setDays] = useState("30");
  const [confirm, setConfirm] = useState("");
  const [preview, setPreview] = useState(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState(null);
  const [done, setDone] = useState(null);

  async function go(commit) {
    setBusy(true);
    setErr(null);
    if (!commit) setDone(null);
    try {
      const res = await call("adminPurgeGuests", {
        olderThanDays: Number(days) || 30,
        ...(commit ? { confirm: "DELETE" } : {}),
      });
      if (commit) {
        setDone(`Deleted ${res.deleted} of ${res.matched}${res.failed ? `, ${res.failed} failed` : ""}.`);
        setPreview(null);
        setConfirm("");
        onDone?.();
      } else {
        setPreview(res);
      }
    } catch (e) {
      setErr(e.message);
    }
    setBusy(false);
  }

  return (
    <div className="card" style={{ marginTop: 16 }}>
      <div style={{ fontWeight: 600, marginBottom: 6 }}>Clear out dead guest accounts</div>
      <p className="sub" style={{ margin: "0 0 12px" }}>
        Anonymous accounts with no linked sign-in, no note, no usage and no entitlement, last
        seen more than this many days ago. Counting is free; deleting is permanent.
      </p>
      <div className="row">
        <input
          value={days}
          onChange={(e) => setDays(e.target.value.replace(/[^0-9]/g, ""))}
          style={{ width: 90 }}
        />
        <span className="mono">days idle</span>
        <button className="small" onClick={() => go(false)} disabled={busy}>
          {busy ? "Counting…" : "Count them"}
        </button>
      </div>

      {preview && (
        <div style={{ marginTop: 14 }}>
          <div className="mono" style={{ marginBottom: 8 }}>
            {preview.matched} accounts match
            {preview.sample?.length
              ? ` · oldest: ${when(preview.sample[0].created)}`
              : ""}
          </div>
          {preview.matched > 0 && (
            <div className="row">
              <input
                placeholder="type DELETE to confirm"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                style={{ maxWidth: 220 }}
              />
              <button
                className="small danger"
                disabled={busy || confirm !== "DELETE"}
                onClick={() => go(true)}
              >
                Delete {preview.matched}
              </button>
            </div>
          )}
        </div>
      )}
      {done && <div className="ok" style={{ marginTop: 12 }}>{done}</div>}
      <Err>{err}</Err>
    </div>
  );
}
