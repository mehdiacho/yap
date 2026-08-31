import React, { useState } from "react";
import { call } from "../firebase.js";
import { useApi } from "../useApi.js";
import { Err, Loading, Empty, Pill, when } from "../ui.jsx";

/**
 * Promo codes.
 *
 * Codes are how a plan gets granted without money changing hands — testers, refunds that Play
 * will not process, and the "sorry about that" after a bad week. Paid subscriptions are not
 * managed here and cannot be: Play owns the purchase, and `verifyPlayPurchase` writes the
 * entitlement from it. Editing one by hand would be overwritten by the next verification.
 */
export default function Subscriptions() {
  const { data, loading, error, reload } = useApi("adminListPromos");
  const [form, setForm] = useState({ code: "", plan: "pro", days: "30", max: "1" });
  const [busy, setBusy] = useState(false);
  const [made, setMade] = useState(null);
  const [err, setErr] = useState(null);

  async function create(e) {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    setMade(null);
    try {
      const res = await call("adminCreatePromo", {
        ...(form.code.trim() ? { code: form.code.trim() } : {}),
        grant: { plan: form.plan, days: Number(form.days) || 30 },
        maxRedemptions: Number(form.max) || 1,
      });
      setMade(res.code);
      setForm((f) => ({ ...f, code: "" }));
      reload();
    } catch (e2) {
      setErr(e2.message);
    }
    setBusy(false);
  }

  return (
    <>
      <h1>Subscriptions</h1>
      <p className="sub">
        Grant plans with a code. Paid subscriptions come from Google Play and are written by
        the purchase verifier — to change one of those, change the entitlement on the account
        itself under Users.
      </p>

      <div className="card">
        <form className="row" onSubmit={create}>
          <input
            placeholder="code (blank = generated)"
            value={form.code}
            onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })}
            style={{ width: 210 }}
          />
          <select
            value={form.plan}
            onChange={(e) => setForm({ ...form, plan: e.target.value })}
            style={{ width: 110 }}
          >
            <option value="pro">pro</option>
            <option value="free">free</option>
          </select>
          <input
            placeholder="days"
            value={form.days}
            onChange={(e) => setForm({ ...form, days: e.target.value.replace(/[^0-9]/g, "") })}
            style={{ width: 90 }}
          />
          <input
            placeholder="uses"
            value={form.max}
            onChange={(e) => setForm({ ...form, max: e.target.value.replace(/[^0-9]/g, "") })}
            style={{ width: 90 }}
          />
          <button className="primary" disabled={busy}>
            {busy ? "Creating…" : "Create code"}
          </button>
        </form>
        <Err>{err}</Err>
        {made && (
          <div className="row" style={{ marginTop: 12 }}>
            <span className="ok">Created</span>
            <code className="mono" style={{ fontSize: 15, color: "var(--text)" }}>
              {made}
            </code>
            <button className="small" onClick={() => navigator.clipboard?.writeText(made)}>
              Copy
            </button>
          </div>
        )}
      </div>

      <h2>Codes</h2>
      <Err>{error}</Err>
      {loading && <Loading what="Loading codes" />}
      {data && data.promos.length === 0 && <Empty>No codes yet.</Empty>}
      {data && data.promos.length > 0 && (
        <div className="card" style={{ padding: "16px 4px" }}>
          <table>
            <thead>
              <tr>
                <th>Code</th>
                <th>Grants</th>
                <th>Used</th>
                <th>Created</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {data.promos.map((p) => {
                const spent = (p.redeemedCount ?? 0) >= (p.maxRedemptions ?? 1);
                const expired = p.expiresAt && p.expiresAt < Date.now();
                return (
                  <tr key={p.code}>
                    <td>
                      <code style={{ fontWeight: 600 }}>{p.code}</code>
                    </td>
                    <td>
                      {p.grant?.plan ?? "pro"}
                      {p.grant?.days ? ` · ${p.grant.days}d` : ""}
                      {p.grant?.byokUnlock ? " · own key" : ""}
                    </td>
                    <td className="mono">
                      {p.redeemedCount ?? 0}/{p.maxRedemptions ?? 1}
                    </td>
                    <td className="mono">{when(p.createdAt)}</td>
                    <td>
                      {expired ? (
                        <Pill tone="bad">expired</Pill>
                      ) : spent ? (
                        <Pill tone="warn">spent</Pill>
                      ) : p.active === false ? (
                        <Pill>off</Pill>
                      ) : (
                        <Pill tone="good">live</Pill>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
