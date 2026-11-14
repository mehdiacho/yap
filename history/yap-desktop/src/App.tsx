import { useCallback, useEffect, useRef, useState } from "react";
import { signOut } from "firebase/auth";
import { auth } from "./lib/firebase";
import { useAuthUser, useSessions } from "./data/hooks";
import { moveToTrash, setPinned } from "./data/sessionsRepo";
import TitleBar from "./components/TitleBar";
import NavRail, { type RailTab } from "./components/NavRail";
import SignIn from "./features/auth/SignIn";
import LibraryList, { type Filter } from "./features/library/LibraryList";
import NoteDetail from "./features/note/NoteDetail";
import Capture from "./features/capture/Capture";
import Settings from "./features/settings/Settings";
import { useTheme } from "./lib/theme";

const LIST_W_KEY = "yap.listWidth";

export default function App() {
  const { user, ready } = useAuthUser();
  const [theme, setTheme] = useTheme();
  const [tab, setTab] = useState<RailTab>("library");
  const [filter, setFilter] = useState<Filter>("all");
  const [search, setSearch] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const inTrash = filter === "trash";
  const { sessions, loading, error } = useSessions(
    user?.uid ?? null,
    inTrash ? "trash" : "library",
  );

  // Keep the selection valid as the list changes underneath it.
  useEffect(() => {
    if (!sessions.length) {
      setSelectedId(null);
      return;
    }
    if (!selectedId || !sessions.some((s) => s.id === selectedId)) {
      setSelectedId(sessions[0].id);
    }
  }, [sessions, selectedId]);

  const selected = sessions.find((s) => s.id === selectedId) ?? null;

  /* ---- resizable list column (§09: 320–520px) ---- */
  const [listW, setListW] = useState(() => {
    const saved = Number(localStorage.getItem(LIST_W_KEY));
    return Number.isFinite(saved) && saved >= 320 && saved <= 520 ? saved : 400;
  });
  const dragging = useRef(false);

  useEffect(() => {
    const onMove = (e: MouseEvent) => {
      if (!dragging.current) return;
      const w = Math.min(520, Math.max(320, e.clientX - 78));
      setListW(w);
    };
    const onUp = () => {
      if (!dragging.current) return;
      dragging.current = false;
      document.body.style.cursor = "";
      localStorage.setItem(LIST_W_KEY, String(listW));
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [listW]);

  /* ---- keyboard shortcuts (§09) ---- */
  const uid = user?.uid;
  const onKey = useCallback(
    (e: KeyboardEvent) => {
      const typing =
        e.target instanceof HTMLElement &&
        (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA");

      if (e.ctrlKey && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setTab("library");
        searchRef.current?.focus();
        return;
      }
      if (e.key === "Escape") {
        if (search) setSearch("");
        else (document.activeElement as HTMLElement | null)?.blur();
        return;
      }
      if (typing || !uid || !selectedId) return;

      if (e.ctrlKey && e.key.toLowerCase() === "p") {
        e.preventDefault();
        const s = sessions.find((x) => x.id === selectedId);
        if (s) void setPinned(uid, s.id, !s.pinned);
      }
      if (e.key === "Delete" && !inTrash) {
        e.preventDefault();
        void moveToTrash(uid, selectedId);
      }
    },
    [search, uid, selectedId, sessions, inTrash],
  );

  useEffect(() => {
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onKey]);

  if (!ready) {
    return (
      <div className="boot">
        <div className="boot__spinner" />
      </div>
    );
  }

  if (!user) return <SignIn />;

  return (
    <div className="app">
      <TitleBar onRecord={() => setTab("capture")} />

      <div className="app__body">
        <NavRail
          active={tab}
          onChange={setTab}
          user={user}
          onAvatarClick={() => setTab("settings")}
        />

        {tab === "library" && (
          <>
            <div className="app__list" style={{ width: listW }}>
              <LibraryList
                sessions={sessions}
                loading={loading}
                error={error}
                selectedId={selectedId}
                onSelect={setSelectedId}
                search={search}
                onSearch={setSearch}
                filter={filter}
                onFilter={setFilter}
                searchRef={searchRef}
              />
            </div>

            <div
              className="divider"
              onMouseDown={() => {
                dragging.current = true;
                document.body.style.cursor = "col-resize";
              }}
              role="separator"
              aria-label="Resize list"
            />

            <div className="app__detail">
              <NoteDetail session={selected} uid={user.uid} inTrash={inTrash} />
            </div>
          </>
        )}

        {tab === "capture" && (
          <Capture user={user} latest={sessions[0] ?? null} onGoLibrary={() => setTab("library")} />
        )}

        {tab === "settings" && (
          <Settings
            user={user}
            theme={theme}
            onTheme={setTheme}
            noteCount={sessions.length}
            onSignOut={() => void signOut(auth)}
          />
        )}
      </div>
    </div>
  );
}
