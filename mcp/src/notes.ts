/**
 * The note shape, mapped from what the Android client actually writes in
 * `FirestoreSessionRepository.toMap()` — camelCase `draftText` / `formattedText` /
 * `title`, with `createdAt` in epoch milliseconds.
 *
 * Note that `docs/API_CONTRACT.md` describes a snake_case shape with `name`, `description`
 * and `tags`. That is the pre-implementation contract and it does not match the database:
 * there is no `tags` field, no `description`, and the title field is `title`. Trust this
 * file and the Kotlin repository; the contract is stale on this point.
 */
export interface Note {
  id: string;
  title: string;
  draftText: string;
  formattedText: string | null;
  status: string;
  createdAt: number;
  durationMs: number;
  category: string;
  topicCluster: string;
  trashedAt: number | null;
  pinned: boolean;
  source: string | null;
  sourceRef: string | null;
  actionItems: ActionItem[];
  followUp: FollowUp | null;
}

/**
 * What became of a note: whether the idea in it was pursued, dropped, or turned into
 * something concrete.
 *
 * Its own field because `status` belongs to the transcription pipeline. Written only by
 * `set_follow_up`; the Android app reads and shows it but deliberately leaves it out of the
 * whole-note save, so a stale copy on the phone cannot erase it.
 */
export interface FollowUp {
  state: FollowUpState;
  note: string | null;
  updatedAt: number;
  by: string | null;
}

export const FOLLOW_UP_STATES = ["PURSUED", "DROPPED", "CONVERTED"] as const;
export type FollowUpState = (typeof FOLLOW_UP_STATES)[number];

export interface ActionItem {
  id: string;
  text: string;
  done: boolean;
  doneAt: number | null;
}

const str = (v: unknown, fallback = ""): string => (typeof v === "string" ? v : fallback);
const num = (v: unknown, fallback = 0): number => (typeof v === "number" ? v : fallback);

export function toNote(raw: Record<string, unknown>): Note {
  const items = Array.isArray(raw.actionItems) ? raw.actionItems : [];
  return {
    id: str(raw.id),
    title: str(raw.title, "Untitled"),
    draftText: str(raw.draftText),
    formattedText: typeof raw.formattedText === "string" ? raw.formattedText : null,
    status: str(raw.status, "DONE"),
    createdAt: num(raw.createdAt),
    durationMs: num(raw.durationMs),
    category: str(raw.category, "Memo"),
    topicCluster: str(raw.topicCluster, "General"),
    trashedAt: typeof raw.trashedAt === "number" ? raw.trashedAt : null,
    pinned: raw.pinned === true,
    source: typeof raw.source === "string" ? raw.source : null,
    sourceRef: typeof raw.sourceRef === "string" ? raw.sourceRef : null,
    actionItems: items.map((i) => {
      const m = (i ?? {}) as Record<string, unknown>;
      return {
        id: str(m.id),
        text: str(m.text),
        done: m.done === true,
        doneAt: typeof m.doneAt === "number" ? m.doneAt : null,
      };
    }),
    followUp: toFollowUp(raw.followUp),
  };
}

function toFollowUp(v: unknown): FollowUp | null {
  if (!v || typeof v !== "object") return null;
  const m = v as Record<string, unknown>;
  const state = FOLLOW_UP_STATES.find((s) => s === m.state);
  if (!state) return null;
  return {
    state,
    note: typeof m.note === "string" && m.note.trim() ? m.note : null,
    updatedAt: num(m.updatedAt),
    by: typeof m.by === "string" ? m.by : null,
  };
}

export function describeFollowUp(f: FollowUp): string {
  const what = f.state.toLowerCase();
  return `${what}${f.note ? ` — ${f.note}` : ""}${f.updatedAt ? ` (${isoDate(f.updatedAt)})` : ""}`;
}

/**
 * The text a reader should be shown: the formalized version when there is one, otherwise
 * the raw transcript. Same precedence the app uses when it opens a note.
 */
export function body(n: Note): string {
  return n.formattedText && n.formattedText.trim() ? n.formattedText : n.draftText;
}

export function isoDate(ms: number): string {
  return ms > 0 ? new Date(ms).toISOString().slice(0, 10) : "unknown";
}

/** Trims to a whole word so a preview never ends mid-token. */
export function preview(text: string, chars = 220): string {
  const flat = text.replace(/\s+/g, " ").trim();
  if (flat.length <= chars) return flat;
  const cut = flat.slice(0, chars);
  const lastSpace = cut.lastIndexOf(" ");
  return `${(lastSpace > chars * 0.6 ? cut.slice(0, lastSpace) : cut).trimEnd()}…`;
}

export interface Filters {
  category?: string;
  topic?: string;
  pinnedOnly?: boolean;
  includeTrashed?: boolean;
  until?: number;
  /** "none" = no outcome recorded yet, "any" = some outcome, or one lowercase state. */
  followUp?: string;
}

export function applyFilters(notes: Note[], f: Filters): Note[] {
  const eq = (a: string, b?: string) => !b || a.toLowerCase() === b.toLowerCase();
  return notes.filter((n) => {
    // Trash is excluded by default for the same reason the library hides it: a deleted
    // note is not an answer to "what did I say about X".
    if (!f.includeTrashed && n.trashedAt !== null) return false;
    if (f.until !== undefined && n.createdAt > f.until) return false;
    if (f.pinnedOnly && !n.pinned) return false;
    if (f.followUp === "none" && n.followUp) return false;
    if (f.followUp === "any" && !n.followUp) return false;
    if (f.followUp && f.followUp !== "none" && f.followUp !== "any" &&
        n.followUp?.state !== f.followUp.toUpperCase()) return false;
    return eq(n.category, f.category) && eq(n.topicCluster, f.topic);
  });
}

/** Case-insensitive substring match across title and both bodies. */
export function matches(n: Note, query: string): boolean {
  const q = query.toLowerCase();
  return (
    n.title.toLowerCase().includes(q) ||
    n.draftText.toLowerCase().includes(q) ||
    (n.formattedText ?? "").toLowerCase().includes(q)
  );
}

export function summarize(n: Note): string {
  const bits = [
    `[${n.id}] ${n.title}`,
    `  ${isoDate(n.createdAt)} · ${n.category} · ${n.topicCluster}`,
  ];
  const open = n.actionItems.filter((a) => !a.done).length;
  const flags: string[] = [];
  if (n.pinned) flags.push("pinned");
  if (n.trashedAt !== null) flags.push("trashed");
  if (open > 0) flags.push(`${open} open action${open === 1 ? "" : "s"}`);
  if (!n.formattedText) flags.push("not formalized");
  if (n.followUp) flags.push(`follow-up: ${n.followUp.state.toLowerCase()}`);
  if (flags.length) bits[1] += ` · ${flags.join(" · ")}`;
  bits.push(`  ${preview(body(n))}`);
  return bits.join("\n");
}
