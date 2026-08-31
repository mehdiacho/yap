import React, { useEffect, useState } from "react";
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

  // Pages after the first, kept here rather than refetched from the top. listUsers pages by
  // an opaque token and cannot jump to an offset, so the only way to reach account 201 is to
  // have walked there — which means holding what has already been walked.
  const [extra, setExtra] = useState([]);
  const [next, setNext] = useState(undefined); // undefined = "use the first page's token"
  const [more, setMore] = useState(false);
  const [moreErr, setMoreErr] = useState(null);

  // A reload starts the walk again, so anything accumulated from the old walk is stale.
  useEffect(() => {
    setExtra([]);
    setNext(undefined);
    setMoreErr(null);
  }, [data]);

  const users = data ? [...data.users, ...extra] : [];
  const token = next === undefined ? data?.pageToken : next;

  async function loadMore() {
    if (!token) return;
    setMore(true);
    setMoreErr(null);
    try {
      const res = await call("adminListUsers", { pageToken: token });
      setExtra((e) => [...e, ...res.users]);
      // null, not undefined — undefined would fall back to the first page's token and
      // fetch page two forever.
      setNext(res.pageToken ?? null);
    } catch (e) {
      setMoreErr(e.message);
    }
    setMore(false);
  }

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
      {data && users.length === 0 && <Empty>No accounts yet.</Empty>}
      {data && users.length > 0 && (
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
              {users.map((u) => (
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
          <div className="row" style={{ padding: "10px 12px 2px" }}>
            <span className="mono" style={{ color: "var(--dim)" }}>
              {users.length} shown{token ? "" : " · that's everyone"}
            </span>
            {token && (
              <button className="small" onClick={loadMore} disabled={more}>
                {more ? "Loading…" : "Load more"}
              </button>
            )}
          </div>
          <Err>{moreErr}</Err>
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
