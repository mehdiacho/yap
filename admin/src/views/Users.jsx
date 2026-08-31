import React, { useState } from "react";
import { call } from "../firebase.js";
import { useApi } from "../useApi.js";
import { Err, Loading, Empty, Pill, when, ago } from "../ui.jsx";

const PLANS = ["free", "pro"];

/**
 * Accounts.
 *
 * The old dashboard could only look up a user you could already name. That is fine for
 * support and useless for everything else — while recruiting testers the question is "who
 * has actually signed up", and you cannot search for an address you have never seen.
 */
export default function Users() {
  const [query, setQuery] = useState("");
  const [found, setFound] = useState(null);
  const [searchErr, setSearchErr] = useState(null);
  const [searching, setSearching] = useState(false);
  const { data, loading, error, reload } = useApi("adminListUsers");

  async function search(e) {
    e.preventDefault();
    if (!query.trim()) return;
    setSearching(true);
    setSearchErr(null);
    setFound(null);
    try {
      setFound(await call("adminSearchUser", { query: query.trim() }));
    } catch (err) {
      setSearchErr(err.message);
    }
    setSearching(false);
  }

  return (
    <>
      <h1>Users</h1>
      <p className="sub">
        Everyone with an account, newest first, plus a direct lookup for support — paste an
        email or a uid from a crash report and get the whole picture.
      </p>

      <form className="row" onSubmit={search} style={{ marginBottom: 8 }}>
        <input
          placeholder="email or uid"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ maxWidth: 340 }}
        />
        <button className="primary" disabled={searching || !query.trim()}>
          {searching ? "Looking…" : "Look up"}
        </button>
        {found && (
          <button type="button" className="small" onClick={() => setFound(null)}>
            Clear
          </button>
        )}
      </form>
      <Err>{searchErr}</Err>

      {found && <UserCard user={found} onSaved={reload} />}

      <h2>All accounts</h2>
      <Err>{error}</Err>
      {loading && <Loading what="Loading accounts" />}
      {data && data.users.length === 0 && <Empty>No accounts yet.</Empty>}
      {data && data.users.length > 0 && (
        <div className="card" style={{ padding: "16px 4px" }}>
          <table>
            <thead>
              <tr>
                <th>Account</th>
                <th>Plan</th>
                <th>Joined</th>
                <th>Last seen</th>
              </tr>
            </thead>
            <tbody>
              {data.users.map((u) => (
                <tr key={u.uid}>
                  <td>
                    <div>
                      {u.email || <span className="mono">no email</span>}{" "}
                      {u.admin && <Pill tone="good">admin</Pill>}{" "}
                      {u.disabled && <Pill tone="bad">disabled</Pill>}
                    </div>
                    <div className="mono">{u.displayName || u.uid}</div>
                  </td>
                  <td>
                    <Pill tone={u.plan !== "free" ? "good" : undefined}>{u.plan}</Pill>
                  </td>
                  <td className="mono">{when(u.created)}</td>
                  <td className="mono">{ago(u.lastSignIn)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {data.pageToken && (
            <div className="empty" style={{ paddingLeft: 12 }}>
              Showing the first {data.users.length}. Use the lookup above for anyone older.
            </div>
          )}
        </div>
      )}
    </>
  );
}

/** One account, with the entitlement controls attached. */
function UserCard({ user, onSaved }) {
  const [plan, setPlan] = useState(user.plan || "free");
  const [days, setDays] = useState("");
  const [byok, setByok] = useState(!!user.byokUnlocked);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState(null);
  const [err, setErr] = useState(null);

  const usedMin = user.usage?.secondsUsed ? Math.round(user.usage.secondsUsed / 60) : 0;

  async function save() {
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      await call("adminSetEntitlement", {
        uid: user.uid,
        plan,
        // An empty box means "no expiry", which the backend represents by deleting the
        // field. Sending 0 would instead expire the plan immediately — the opposite.
        ...(days.trim() ? { days: Number(days) } : {}),
        byokUnlocked: byok,
      });
      setMsg("Saved.");
      onSaved?.();
    } catch (e) {
      setErr(e.message);
    }
    setBusy(false);
  }

  return (
    <div className="card" style={{ marginBottom: 8 }}>
      <div className="row" style={{ justifyContent: "space-between", marginBottom: 14 }}>
        <div>
          <div style={{ fontWeight: 600 }}>{user.email || "No email"}</div>
          <div className="mono">{user.uid}</div>
        </div>
        <div style={{ textAlign: "right" }}>
          <Pill tone={user.plan !== "free" ? "good" : undefined}>{user.plan}</Pill>
          <div className="mono" style={{ marginTop: 6 }}>
            joined {when(user.created)}
          </div>
        </div>
      </div>

      <div className="mono" style={{ marginBottom: 14 }}>
        {usedMin} min used this cycle
        {user.usage?.cycle ? ` (${user.usage.cycle})` : ""}
        {user.expiresAt ? ` · plan expires ${when(user.expiresAt)}` : " · no expiry"}
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
          style={{ width: 190 }}
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
        <button className="primary" onClick={save} disabled={busy}>
          {busy ? "Saving…" : "Apply"}
        </button>
        {msg && <span className="ok">{msg}</span>}
      </div>
      <Err>{err}</Err>
    </div>
  );
}
