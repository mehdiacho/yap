import React from "react";
import { useApi } from "../useApi.js";
import { Stat, Err, Loading } from "../ui.jsx";

export default function Overview() {
  const { data, loading, error, reload } = useApi("adminOverview");

  return (
    <>
      <h1>Overview</h1>
      <p className="sub">
        Where things stand right now, across every account. Everything on this page is read
        through a function — nothing on the client can see these collections directly.
      </p>
      <Err>{error}</Err>
      {loading && <Loading />}

      {data && (
        <>
          <div className="grid">
            <Stat
              n={data.users.total}
              k="Accounts"
              hint={data.users.more ? "first 1,000 — there are more" : undefined}
            />
            <Stat n={data.newUsersThisWeek} k="New this week" />
            <Stat n={data.subscriptions.active} k="Active plans" />
            <Stat
              n={data.feedback.unread}
              k="Unread feedback"
              hint={`${data.feedback.total} total`}
            />
          </div>

          <h2>Cycle</h2>
          <div className="card">
            <div className="row" style={{ justifyContent: "space-between" }}>
              <div>
                <div className="mono">{data.cycle}</div>
                <div className="k" style={{ color: "var(--dim)", marginTop: 4 }}>
                  {data.subscriptions.expired} expired plan
                  {data.subscriptions.expired === 1 ? "" : "s"} on file
                </div>
              </div>
              <button className="small" onClick={reload}>
                Refresh
              </button>
            </div>
          </div>
        </>
      )}
    </>
  );
}
