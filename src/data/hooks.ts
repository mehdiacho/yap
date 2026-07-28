import { useEffect, useMemo, useState } from "react";
import { onAuthStateChanged, type User } from "firebase/auth";
import { auth } from "../lib/firebase";
import { watchActiveSessions, watchTrashedSessions } from "./sessionsRepo";
import { sectionFor, type Session } from "./session";

export function useAuthUser(): { user: User | null; ready: boolean } {
  const [user, setUser] = useState<User | null>(auth.currentUser);
  const [ready, setReady] = useState(false);

  useEffect(
    () =>
      onAuthStateChanged(auth, (u) => {
        setUser(u);
        setReady(true);
      }),
    [],
  );

  return { user, ready };
}

export interface SessionsState {
  sessions: Session[];
  loading: boolean;
  error: Error | null;
}

export function useSessions(uid: string | null, view: "library" | "trash"): SessionsState {
  const [state, setState] = useState<SessionsState>({
    sessions: [],
    loading: true,
    error: null,
  });

  useEffect(() => {
    if (!uid) {
      setState({ sessions: [], loading: false, error: null });
      return;
    }
    setState((s) => ({ ...s, loading: true, error: null }));

    const watch = view === "trash" ? watchTrashedSessions : watchActiveSessions;
    return watch(uid, {
      onData: (sessions) => setState({ sessions, loading: false, error: null }),
      onError: (error) => setState({ sessions: [], loading: false, error }),
    });
  }, [uid, view]);

  return state;
}

/** Case-insensitive match across title and both text bodies. */
export function useFilteredSessions(sessions: Session[], search: string): Session[] {
  return useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return sessions;
    return sessions.filter((s) =>
      [s.title, s.draftText, s.formattedText ?? "", s.category, s.topicCluster]
        .join("\n")
        .toLowerCase()
        .includes(q),
    );
  }, [sessions, search]);
}

export interface SessionGroup {
  label: string;
  items: Session[];
}

/**
 * Section eyebrows for the list. Pinned notes are hoisted into their own group
 * so they read as pinned rather than as an odd date bucket.
 */
export function useGroupedSessions(sessions: Session[]): SessionGroup[] {
  return useMemo(() => {
    const groups: SessionGroup[] = [];
    const push = (label: string, s: Session) => {
      const last = groups[groups.length - 1];
      if (last && last.label === label) last.items.push(s);
      else groups.push({ label, items: [s] });
    };

    for (const s of sessions) push(s.pinned ? "Pinned" : sectionFor(s.createdAt), s);
    return groups;
  }, [sessions]);
}
