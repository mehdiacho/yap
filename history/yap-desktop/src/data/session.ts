import type { DocumentSnapshot } from "firebase/firestore";

/*
 * Mirrors the Android model 1:1 — app/src/main/java/com/mehdiacho/yap/data/Session.kt
 * and the manual mapping in FirestoreSessionRepository.kt.
 *
 * NOTE: docs/API_CONTRACT.md describes these fields in snake_case (draft_text,
 * formatted_text, name). The shipping Android client writes camelCase, and that
 * is what is actually in Firestore. Field names below follow the live data.
 */

export interface ExtraRecording {
  /** Local file on the device that recorded it — absent/meaningless elsewhere. */
  audioPath: string | null;
  /** Canonical cloud copy: users/{uid}/audio/… — this is what desktop plays. */
  storagePath: string | null;
  durationMs: number;
  /** Clip-relative elapsed-ms markers. */
  bookmarks: number[];
}

export interface Session {
  id: string;
  title: string;
  draftText: string;
  formattedText: string | null;
  audioPath: string | null;
  storagePath: string | null;
  /** DRAFT | TRANSCRIBING | DONE | FAILED | QUOTA | POLISHING */
  status: string;
  /**
   * Why a FAILED note failed, written by the backend (functions/src/providerErrors.ts).
   * PROVIDER_CREDIT | PROVIDER_RATE_LIMIT | PROVIDER_AUTH | PROVIDER_UNAVAILABLE |
   * AUDIO_REJECTED | UNKNOWN. Null on every other status.
   */
  statusReason: string | null;
  /** Human-readable explanation for the same, shown verbatim. */
  statusMessage: string | null;
  createdAt: number;
  durationMs: number;
  category: string;
  topicCluster: string;
  /** Soft delete; purged ~30 days later. */
  trashedAt: number | null;
  pinned: boolean;
  bookmarks: number[];
  extraRecordings: ExtraRecording[];
}

const num = (v: unknown, fallback = 0): number =>
  typeof v === "number" && Number.isFinite(v) ? v : fallback;

const numList = (v: unknown): number[] =>
  Array.isArray(v) ? v.filter((n): n is number => typeof n === "number") : [];

const str = (v: unknown, fallback: string): string => (typeof v === "string" ? v : fallback);

const strOrNull = (v: unknown): string | null => (typeof v === "string" ? v : null);

/** Tolerant mapping — never throw on a malformed doc; degrade to defaults. */
export function toSession(snap: DocumentSnapshot): Session | null {
  if (!snap.exists()) return null;
  const d = snap.data() as Record<string, unknown>;

  return {
    id: snap.id,
    title: str(d.title, "Untitled"),
    draftText: str(d.draftText, ""),
    formattedText: strOrNull(d.formattedText),
    audioPath: strOrNull(d.audioPath),
    storagePath: strOrNull(d.storagePath),
    status: str(d.status, "DONE"),
    statusReason: strOrNull(d.statusReason),
    statusMessage: strOrNull(d.statusMessage),
    createdAt: num(d.createdAt),
    durationMs: num(d.durationMs),
    category: str(d.category, "Memo"),
    topicCluster: str(d.topicCluster, "General"),
    trashedAt: typeof d.trashedAt === "number" ? d.trashedAt : null,
    pinned: d.pinned === true,
    bookmarks: numList(d.bookmarks),
    extraRecordings: Array.isArray(d.extraRecordings)
      ? d.extraRecordings.flatMap((raw) => {
          if (typeof raw !== "object" || raw === null) return [];
          const m = raw as Record<string, unknown>;
          return [
            {
              audioPath: strOrNull(m.audioPath),
              storagePath: strOrNull(m.storagePath),
              durationMs: num(m.durationMs),
              bookmarks: numList(m.bookmarks),
            },
          ];
        })
      : [],
  };
}

/** The text to display: the polished version when it exists, else the raw draft. */
export function displayText(s: Session): string {
  return s.formattedText?.trim() ? s.formattedText : s.draftText;
}

/** True when the note has been through cloud polish. */
export function isRefined(s: Session): boolean {
  return Boolean(s.formattedText?.trim());
}

export function formatDuration(ms: number): string {
  if (ms <= 0) return "0:00";
  const total = Math.round(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const sec = total % 60;
  const two = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${two(m)}:${two(sec)}` : `${m}:${two(sec)}`;
}

/** Day-granularity grouping label used for the list's section eyebrows. */
export function sectionFor(createdAt: number, now = Date.now()): string {
  if (!createdAt) return "Undated";
  const d = new Date(createdAt);
  const startOf = (t: Date) => new Date(t.getFullYear(), t.getMonth(), t.getDate()).getTime();
  const days = Math.round((startOf(new Date(now)) - startOf(d)) / 86_400_000);

  if (days <= 0) return "Today";
  if (days === 1) return "Yesterday";
  if (days < 7) return "This week";
  if (days < 30) return "This month";
  return d.toLocaleDateString(undefined, { month: "long", year: "numeric" });
}

export function formatTimestamp(createdAt: number): string {
  if (!createdAt) return "";
  return new Date(createdAt).toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}
