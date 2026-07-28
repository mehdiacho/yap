import {
  collection,
  deleteDoc,
  doc,
  onSnapshot,
  orderBy,
  query,
  updateDoc,
} from "firebase/firestore";
import { getDownloadURL, ref } from "firebase/storage";
import { db, storage } from "../lib/firebase";
import { toSession, type Session } from "./session";

/*
 * Port of FirestoreSessionRepository.kt. Same collection path, same ordering,
 * same client-side trash split — so desktop and phone can never disagree about
 * what the library contains.
 *
 * Trash is filtered in memory (not with a where clause) precisely because the
 * Android client does the same: it avoids a composite index and keeps a single
 * snapshot listener feeding both the library and the trash view.
 */

const sessionsCol = (uid: string) => collection(db, "users", uid, "sessions");

export type Unsubscribe = () => void;

interface Watchers {
  onData: (sessions: Session[]) => void;
  onError: (err: Error) => void;
}

/** Realtime feed of every session, newest first. */
function watchAll(uid: string, { onData, onError }: Watchers): Unsubscribe {
  const q = query(sessionsCol(uid), orderBy("createdAt", "desc"));
  return onSnapshot(
    q,
    (snap) => {
      const all: Session[] = [];
      for (const d of snap.docs) {
        const s = toSession(d);
        if (s) all.push(s);
      }
      onData(all);
    },
    (err) => onError(err),
  );
}

/** Active notes — pinned first, then newest first (matches the phone's Library). */
export function watchActiveSessions(uid: string, w: Watchers): Unsubscribe {
  return watchAll(uid, {
    onError: w.onError,
    onData: (all) => {
      const active = all.filter((s) => s.trashedAt === null);
      active.sort((a, b) => {
        if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
        return b.createdAt - a.createdAt;
      });
      w.onData(active);
    },
  });
}

/** Trashed notes, most recently trashed first. */
export function watchTrashedSessions(uid: string, w: Watchers): Unsubscribe {
  return watchAll(uid, {
    onError: w.onError,
    onData: (all) => {
      const trashed = all.filter((s) => s.trashedAt !== null);
      trashed.sort((a, b) => (b.trashedAt ?? 0) - (a.trashedAt ?? 0));
      w.onData(trashed);
    },
  });
}

export async function setPinned(uid: string, id: string, pinned: boolean): Promise<void> {
  await updateDoc(doc(sessionsCol(uid), id), { pinned });
}

/** Soft delete, matching the phone: sets trashedAt, keeps the doc. */
export async function moveToTrash(uid: string, id: string): Promise<void> {
  await updateDoc(doc(sessionsCol(uid), id), { trashedAt: Date.now() });
}

export async function restoreFromTrash(uid: string, id: string): Promise<void> {
  await updateDoc(doc(sessionsCol(uid), id), { trashedAt: null });
}

/** Hard delete. Does not remove the Storage object — the server handles that. */
export async function deleteForever(uid: string, id: string): Promise<void> {
  await deleteDoc(doc(sessionsCol(uid), id));
}

/*
 * Audio lives at users/{uid}/audio/{sessionId}.m4a in Cloud Storage. `audioPath`
 * is a local path on whichever device recorded it and is meaningless here, so
 * desktop always resolves `storagePath`. A note recorded on the phone but not
 * yet uploaded has no storagePath — that's the "online only, not yet synced"
 * state, not an error.
 */
const urlCache = new Map<string, string>();

export async function audioUrlFor(storagePath: string | null): Promise<string | null> {
  if (!storagePath) return null;
  const cached = urlCache.get(storagePath);
  if (cached) return cached;

  try {
    const url = await getDownloadURL(ref(storage, storagePath));
    urlCache.set(storagePath, url);
    return url;
  } catch (err) {
    console.warn(`No audio at ${storagePath}`, err);
    return null;
  }
}
