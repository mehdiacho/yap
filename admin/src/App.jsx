import React, { useCallback, useEffect, useState } from "react";
import { onAuthStateChanged } from "firebase/auth";
import { auth, signIn, leave, call } from "./firebase.js";
import Overview from "./views/Overview.jsx";
import Users from "./views/Users.jsx";
import Testers from "./views/Testers.jsx";
import Subscriptions from "./views/Subscriptions.jsx";
import Feedback from "./views/Feedback.jsx";
import Activity from "./views/Activity.jsx";
import Costs from "./views/Costs.jsx";
import { Err } from "./ui.jsx";

const TABS = [
  { id: "overview", label: "Overview", View: Overview },
  { id: "users", label: "Accounts", View: Users },
  { id: "testers", label: "Testers", View: Testers },
  { id: "subs", label: "Subscriptions", View: Subscriptions },
  { id: "feedback", label: "Feedback", View: Feedback },
  { id: "activity", label: "Activity", View: Activity },
  { id: "costs", label: "Costs", View: Costs },
];

export default function App() {
  const [user, setUser] = useState(undefined); // undefined = still deciding
  const [isAdmin, setIsAdmin] = useState(false);
  const [gateMsg, setGateMsg] = useState(null);
  // The tab lives in the hash so a reload keeps you where you were, and so a link to
  // "#feedback" is a link to the feedback inbox. Not worth a router for six tabs.
  const [tab, setTab] = useState(() => window.location.hash.slice(1) || "overview");
  const [unread, setUnread] = useState(null);

  useEffect(() => {
    return onAuthStateChanged(auth, async (u) => {
      if (!u) {
        setUser(null);
        setIsAdmin(false);
        return;
      }
      const token = await u.getIdTokenResult();
      if (token.claims.admin !== true) {
        // Signed in as a real person who simply is not an admin. Say which account, because
        // the usual cause is being signed into the wrong Google account in this browser.
        setGateMsg(`${u.email} is not an admin account.`);
        await leave();
        return;
      }
      setGateMsg(null);
      setUser(u);
      setIsAdmin(true);
    });
  }, []);

  useEffect(() => {
    const onHash = () => setTab(window.location.hash.slice(1) || "overview");
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  // The unread badge is owned here rather than by the Feedback tab, so it is visible from
  // every other tab — which is the entire point of a badge.
  const refreshUnread = useCallback(() => {
    if (!isAdmin) return;
    call("adminOverview")
      .then((d) => setUnread(d?.feedback?.unread ?? 0))
      .catch(() => setUnread(null));
  }, [isAdmin]);

  useEffect(refreshUnread, [refreshUnread]);

  if (user === undefined) return <div className="gate" />;

  if (!isAdmin) {
    return (
      <div className="gate">
        <div className="card">
          <h1>Yap Admin</h1>
          <p className="sub" style={{ margin: "0 0 18px" }}>
            Sign in with the Google account that holds the admin claim.
          </p>
          <Err>{gateMsg}</Err>
          <button className="primary" onClick={() => signIn().catch((e) => setGateMsg(e.message))}>
            Sign in with Google
          </button>
        </div>
      </div>
    );
  }

  const current = TABS.find((t) => t.id === tab) ?? TABS[0];
  const { View } = current;

  return (
    <div className="shell">
      <aside className="side">
        <div className="brand">
          Yap <span>admin</span>
        </div>
        <nav>
          {TABS.map((t) => (
            <button
              key={t.id}
              className={t.id === current.id ? "on" : ""}
              onClick={() => {
                window.location.hash = t.id;
                setTab(t.id);
              }}
            >
              {t.label}
              {t.id === "feedback" && unread > 0 && <span className="pill">{unread}</span>}
            </button>
          ))}
        </nav>
        <div className="foot">
          <div className="who">{user.email}</div>
          <button className="small" onClick={leave}>
            Sign out
          </button>
        </div>
      </aside>
      <main>
        <View onChanged={refreshUnread} />
      </main>
    </div>
  );
}
