import React, { useMemo } from "react";
import { useApi } from "../useApi.js";
import { Err, Loading, Empty, Pill, Stat, when, ago, duration } from "../ui.jsx";

const DAY = 24 * 60 * 60 * 1000;

/**
 * Testers.
 *
 * Closed testing on Play is not a headcount, it is an attendance record: twelve people have
 * to stay opted in for fourteen days, and a tester who installs once and never opens the app
 * again can sink the production review. So this screen answers one question per row — when
 * did this person last actually use Yap — and says nothing else louder than that.
 *
 * A tester is whoever has been labelled one on the Accounts tab. Nothing in an auth record
 * says "this person agreed to test the app", and guessing would put the Play crawler in here.
 */
export default function Testers() {
  const { data, loading, error } = useApi("adminListUsers");
  const activity = useApi("adminRecentActivity", { limit: 200 });

  const users = data?.users ?? [];
  const testers = useMemo(() => users.filter((u) => u.kind === "tester"), [users]);

  /**
   * Guests who came back on another day.
   *
   * Somebody who opens the app on Monday and again on Friday without ever signing in is a
   * real user the account list cannot name — and at least one of them is a tester: the
   * developer who filed three bugs on 2026-09-08 did it all from an anonymous account
   * created a week earlier. This is where you find people worth labelling.
   */
  const returners = useMemo(
    () =>
      users
        .filter((u) => u.kind === "guest" && u.lastActive)
        .filter((u) => Date.parse(u.lastActive) - Date.parse(u.created) > DAY)
        .sort((a, b) => Date.parse(b.lastActive) - Date.parse(a.lastActive)),
    [users]
  );

  const now = Date.now();
  const stale = testers.filter((t) => !t.lastActive || now - Date.parse(t.lastActive) > 3 * DAY);
  const notesByUid = useMemo(() => {
    const m = new Map();
    for (const s of activity.data?.sessions ?? []) {
      if (!s.uid) continue;
      m.set(s.uid, (m.get(s.uid) ?? 0) + 1);
    }
    return m;
  }, [activity.data]);

  return (
    <>
      <h1>Testers</h1>
      <p className="sub">
        The people who agreed to test, and when each of them last opened the app. Play wants
        twelve testers engaged across fourteen days — one-and-done installs are what gets a
        production release rejected.
      </p>

      <Err>{error}</Err>
      {loading && <Loading what="Loading testers" />}

      {data && (
        <>
          <div className="grid" style={{ marginBottom: 18 }}>
            <Stat n={testers.length} k="labelled testers" hint="Play wants 12" />
            <Stat
              n={testers.filter((t) => t.lastActive && now - Date.parse(t.lastActive) <= 3 * DAY).length}
              k="active in 3 days"
            />
            <Stat n={testers.filter((t) => t.notes > 0).length} k="have recorded something" />
            <Stat n={returners.length} k="unlabelled returners" hint="guests who came back" />
          </div>

          {testers.length === 0 && (
            <Empty>
              Nobody is labelled a tester yet. Open <strong>Accounts</strong>, click a row, and
              set its label to <em>tester</em> — start with the returners below.
            </Empty>
          )}

          {testers.length > 0 && (
            <div className="card" style={{ padding: "16px 4px", marginBottom: 18 }}>
              <table>
                <thead>
                  <tr>
                    <th>Tester</th>
                    <th>Last opened</th>
                    <th>Notes</th>
                    <th>Recorded</th>
                    <th>Joined</th>
                    <th>Note to self</th>
                  </tr>
                </thead>
                <tbody>
                  {testers.map((t) => {
                    const idle = t.lastActive ? now - Date.parse(t.lastActive) : null;
                    return (
                      <tr key={t.uid}>
                        <td>
                          <div>{t.email || <span className="mono">guest · no email</span>}</div>
                          <div className="mono">{t.displayName || t.uid}</div>
                        </td>
                        <td>
                          {idle === null ? (
                            <Pill tone="bad">never</Pill>
                          ) : idle > 3 * DAY ? (
                            <Pill tone="warn" title={when(t.lastActive)}>
                              {ago(t.lastActive)}
                            </Pill>
                          ) : (
                            <Pill tone="good" title={when(t.lastActive)}>
                              {ago(t.lastActive)}
                            </Pill>
                          )}
                        </td>
                        <td className="mono">
                          {t.notes || "—"}
                          {notesByUid.get(t.uid) ? <div>{notesByUid.get(t.uid)} recent</div> : null}
                        </td>
                        <td className="mono">
                          {t.recordedSeconds ? duration(t.recordedSeconds * 1000) : "—"}
                        </td>
                        <td className="mono">
                          {when(t.created)}
                          <div>{Math.floor((now - Date.parse(t.created)) / DAY)}d ago</div>
                        </td>
                        <td className="mono">{t.tagNote || "—"}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {stale.length > 0 && (
                <div className="row" style={{ padding: "10px 12px 2px" }}>
                  <span className="mono" style={{ color: "var(--warn)" }}>
                    {stale.length} tester{stale.length === 1 ? "" : "s"} haven't opened Yap in over
                    three days — worth a nudge.
                  </span>
                </div>
              )}
            </div>
          )}

          <h2>Guests who came back</h2>
          <p className="sub">
            Anonymous accounts used on more than one day. Nobody does that by accident, so
            these are real users — label the ones you recognise.
          </p>
          {returners.length === 0 && <Empty>No guest has returned on another day yet.</Empty>}
          {returners.length > 0 && (
            <div className="card" style={{ padding: "16px 4px" }}>
              <table>
                <thead>
                  <tr>
                    <th>Account</th>
                    <th>First seen</th>
                    <th>Last opened</th>
                    <th>Span</th>
                    <th>Notes</th>
                  </tr>
                </thead>
                <tbody>
                  {returners.slice(0, 30).map((u) => (
                    <tr key={u.uid}>
                      <td className="mono">{u.uid}</td>
                      <td className="mono">{when(u.created)}</td>
                      <td className="mono">{ago(u.lastActive)}</td>
                      <td className="mono">
                        {Math.round((Date.parse(u.lastActive) - Date.parse(u.created)) / DAY)}d
                      </td>
                      <td className="mono">{u.notes || "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </>
  );
}
