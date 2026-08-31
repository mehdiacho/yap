import React from "react";
import { useApi } from "../useApi.js";
import { Err, Loading, Stat, when } from "../ui.jsx";

const usd = (n) => `$${(n ?? 0).toFixed(2)}`;

/**
 * What the model calls actually cost.
 *
 * Two numbers, kept apart on purpose: the projection is priced from the tokens we counted
 * ourselves, and the actual comes from Google's billing export. Averaging them or showing
 * only one would hide exactly the thing worth knowing — the gap between what we think we
 * are spending and what we are being charged.
 */
export default function Costs() {
  const { data, loading, error, reload } = useApi("getCostMeter");

  return (
    <>
      <h1>Costs</h1>
      <p className="sub">
        Whole-project spend for the current billing cycle, not any one account's usage.
      </p>

      <Err>{error}</Err>
      {loading && <Loading what="Adding it up" />}

      {data && (
        <>
          <div className="grid">
            <Stat n={usd(data.projected.usd)} k={`Projected · ${data.cycle}`} />
            <Stat
              n={data.actual ? usd(data.actual.usd) : "—"}
              k="Billed by Google"
              hint={
                data.actual
                  ? `as of ${when(data.actual.asOf)}`
                  : data.actualReason || "billing export not configured"
              }
            />
            <Stat n={data.projected.calls} k="Model calls" />
            <Stat n={Math.round(data.projected.audioMinutes)} k="Audio minutes" />
          </div>

          {data.unpricedModels?.length > 0 && (
            <div className="err" style={{ marginTop: 16 }}>
              No price on file for: {data.unpricedModels.join(", ")}. Those calls are counted
              but not costed, so the projection is low.
            </div>
          )}

          <h2>By operation</h2>
          <div className="card" style={{ padding: "16px 4px" }}>
            <table>
              <thead>
                <tr>
                  <th>Operation</th>
                  <th>Model</th>
                  <th>Calls</th>
                  <th>Cost</th>
                </tr>
              </thead>
              <tbody>
                {data.projected.perOp.map((r) => (
                  <tr key={`${r.op}-${r.model}`}>
                    <td>{r.op}</td>
                    <td className="mono">{r.model}</td>
                    <td className="mono">{r.calls}</td>
                    <td className="mono">{usd(r.usd ?? r.cost)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {data.actual?.byService?.length > 0 && (
            <>
              <h2>Google invoice</h2>
              <div className="card" style={{ padding: "16px 4px" }}>
                <table>
                  <thead>
                    <tr>
                      <th>Service</th>
                      <th>Cost</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.actual.byService.map((r) => (
                      <tr key={r.service}>
                        <td>{r.service}</td>
                        <td className="mono">{usd(r.cost)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          <div className="row" style={{ marginTop: 16 }}>
            <button className="small" onClick={reload}>
              Refresh
            </button>
          </div>
        </>
      )}
    </>
  );
}
