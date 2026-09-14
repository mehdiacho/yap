#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

import { loadConfig } from "./config.js";
import { loadCredentials, TokenSource } from "./auth.js";
import { SessionStore } from "./firestore.js";
import {
  applyFilters,
  body,
  describeFollowUp,
  isoDate,
  matches,
  summarize,
  toNote,
  type Filters,
  type Note,
} from "./notes.js";

const cfg = loadConfig();
const reader = new SessionStore(cfg, new TokenSource(cfg, loadCredentials(cfg)));

/**
 * One scan of the library, reused for a few seconds.
 *
 * An agent answering a question typically calls two or three of these tools in a row —
 * list, then search, then get. Without the cache that is three full reads of the same
 * collection, billed and waited for each time. Ten seconds is long enough to cover one
 * turn of reasoning and short enough that a note recorded on the phone shows up in the
 * next question rather than after a restart.
 */
let cached: { at: number; notes: Note[] } | null = null;
const CACHE_MS = 10_000;

async function allNotes(): Promise<Note[]> {
  if (cached && Date.now() - cached.at < CACHE_MS) return cached.notes;
  const raw = await reader.query({ limit: cfg.maxScan });
  const notes = raw.map(toNote);
  cached = { at: Date.now(), notes };
  return notes;
}

/** Accepts `2026-09-07` or a full ISO timestamp; returns epoch ms. */
function parseDate(s: string | undefined, endOfDay = false): number | undefined {
  if (!s) return undefined;
  const plain = /^\d{4}-\d{2}-\d{2}$/.test(s);
  const suffix = endOfDay ? "T23:59:59.999Z" : "T00:00:00.000Z";
  const ms = Date.parse(plain ? s + suffix : s);
  if (Number.isNaN(ms)) throw new Error(`Not a date I can read: "${s}". Use YYYY-MM-DD.`);
  return ms;
}

const text = (s: string) => ({ content: [{ type: "text" as const, text: s }] });

const dateFilters = {
  since: z.string().optional().describe("Only notes recorded on or after this date (YYYY-MM-DD)."),
  until: z.string().optional().describe("Only notes recorded on or before this date (YYYY-MM-DD)."),
  category: z.string().optional().describe("Exact category, e.g. Memo or Idea. Case-insensitive."),
  topic: z.string().optional().describe("Exact topic cluster. Case-insensitive."),
  pinnedOnly: z.boolean().optional().describe("Only notes the user pinned."),
  includeTrashed: z.boolean().optional().describe("Include trashed notes. Off by default."),
  followUp: z
    .enum(["none", "any", "pursued", "dropped", "converted"])
    .optional()
    .describe(
      "Filter on the recorded outcome. 'none' = nobody has said what became of the note yet, " +
        "which is the list worth reviewing.",
    ),
};

function filtersOf(a: Record<string, unknown>): Filters {
  return {
    category: a.category as string | undefined,
    topic: a.topic as string | undefined,
    pinnedOnly: a.pinnedOnly as boolean | undefined,
    includeTrashed: a.includeTrashed as boolean | undefined,
    until: parseDate(a.until as string | undefined, true),
    followUp: a.followUp as string | undefined,
  };
}

function windowed(notes: Note[], a: Record<string, unknown>): Note[] {
  const since = parseDate(a.since as string | undefined);
  const inWindow = since === undefined ? notes : notes.filter((n) => n.createdAt >= since);
  return applyFilters(inWindow, filtersOf(a));
}

const server = new McpServer({ name: "yap", version: "0.1.0" });

server.registerTool(
  "list_notes",
  {
    title: "List notes",
    description:
      "List the user's Yap voice notes, newest first, with a short preview of each. " +
      "Use this to see what is there, then get_note to read one in full.",
    inputSchema: {
      ...dateFilters,
      limit: z.number().int().min(1).max(200).optional().describe("Default 25."),
    },
  },
  async (args) => {
    const found = windowed(await allNotes(), args);
    const limit = args.limit ?? 25;
    const page = found.slice(0, limit);
    if (!page.length) return text("No notes match those filters.");
    const more = found.length > limit ? `\n\n(${found.length - limit} more match.)` : "";
    return text(
      `${found.length} note(s) match. Showing ${page.length}:\n\n` +
        page.map(summarize).join("\n\n") +
        more,
    );
  },
);

server.registerTool(
  "search_notes",
  {
    title: "Search notes",
    description:
      "Find notes whose title or text contains a phrase. Matching is a plain " +
      "case-insensitive substring scan over the whole library, with no stemming, fuzzy " +
      "matching or relevance ranking, so try a couple of distinct short phrasings rather " +
      "than one long sentence.",
    inputSchema: {
      query: z.string().min(1).describe("Phrase to look for."),
      ...dateFilters,
      limit: z.number().int().min(1).max(200).optional().describe("Default 25."),
    },
  },
  async (args) => {
    const found = windowed(await allNotes(), args).filter((n) => matches(n, args.query));
    const limit = args.limit ?? 25;
    const page = found.slice(0, limit);
    if (!page.length) {
      return text(
        `Nothing matches "${args.query}". The search is literal substring matching, so a ` +
          "shorter or differently worded phrase may still find it.",
      );
    }
    const more = found.length > limit ? `\n\n(${found.length - limit} more match.)` : "";
    return text(
      `${found.length} note(s) contain "${args.query}". Showing ${page.length}:\n\n` +
        page.map(summarize).join("\n\n") +
        more,
    );
  },
);

server.registerTool(
  "get_note",
  {
    title: "Get one note in full",
    description:
      "Read a single note end to end, by the id shown in square brackets by the other tools.",
    inputSchema: {
      id: z.string().min(1).describe("Note id."),
      transcript: z
        .boolean()
        .optional()
        .describe(
          "Also return the original word-for-word transcript. A formalized note otherwise " +
            "shows only its formatted text, which is a rewrite and can drop what was said.",
        ),
    },
  },
  async ({ id, transcript }) => {
    const raw = await reader.get(id);
    if (!raw) return text(`No note with id ${id}.`);
    const n = toNote(raw);

    const head = [
      `# ${n.title}`,
      "",
      `- id: ${n.id}`,
      `- recorded: ${isoDate(n.createdAt)}`,
      `- category: ${n.category} · topic: ${n.topicCluster}`,
      `- status: ${n.status}${n.formattedText ? "" : " (never formalized — raw transcript below)"}`,
    ];
    if (n.durationMs > 0) head.push(`- audio length: ${Math.round(n.durationMs / 1000)}s`);
    if (n.pinned) head.push("- pinned");
    if (n.trashedAt !== null) head.push(`- in trash since ${isoDate(n.trashedAt)}`);
    if (n.source) head.push(`- source: ${n.source}${n.sourceRef ? ` (${n.sourceRef})` : ""}`);
    head.push(`- follow-up: ${n.followUp ? describeFollowUp(n.followUp) : "none recorded"}`);

    if (n.actionItems.length) {
      head.push("", "## Action items");
      for (const a of n.actionItems) head.push(`- [${a.done ? "x" : " "}] ${a.text}`);
    }

    // Asked for "the transcripts" of five voice notes, this tool could only hand back the
    // rewrites, and the words themselves had to be pulled from Firestore by hand.
    const words =
      transcript && n.formattedText && n.draftText ? `\n\n## Transcript\n\n${n.draftText}` : "";
    return text(`${head.join("\n")}\n\n## Content\n\n${body(n)}${words}`);
  },
);

server.registerTool(
  "list_topics",
  {
    title: "List categories and topics",
    description:
      "Summarise the library: every category and topic cluster, how many notes are in it " +
      "and the dates they span. Use this first to orient before searching blind.",
    inputSchema: { since: dateFilters.since },
  },
  async (args) => {
    const notes = windowed(await allNotes(), args);
    if (!notes.length) return text("No notes yet.");

    const tally = (key: (n: Note) => string) => {
      const m = new Map<string, { count: number; first: number; last: number }>();
      for (const n of notes) {
        const k = key(n);
        const e = m.get(k) ?? { count: 0, first: n.createdAt, last: n.createdAt };
        e.count += 1;
        e.first = Math.min(e.first, n.createdAt);
        e.last = Math.max(e.last, n.createdAt);
        m.set(k, e);
      }
      return [...m.entries()]
        .sort((a, b) => b[1].count - a[1].count)
        .map(([k, e]) => `- ${k} — ${e.count} note(s), ${isoDate(e.first)} to ${isoDate(e.last)}`)
        .join("\n");
    };

    return text(
      `${notes.length} note(s) total.\n\n## Categories\n${tally((n) => n.category)}\n\n` +
        `## Topic clusters\n${tally((n) => n.topicCluster)}`,
    );
  },
);

server.registerTool(
  "list_action_items",
  {
    title: "List action items",
    description:
      "Every action item the transcription pipeline extracted from the notes, with the " +
      "note each came from. Open items only, unless includeDone is set.",
    inputSchema: {
      since: dateFilters.since,
      includeDone: z.boolean().optional().describe("Include ticked-off items too."),
      limit: z.number().int().min(1).max(500).optional().describe("Default 100."),
    },
  },
  async (args) => {
    const notes = windowed(await allNotes(), args);
    const rows: string[] = [];
    for (const n of notes) {
      for (const a of n.actionItems) {
        if (!args.includeDone && a.done) continue;
        const mark = a.done ? "x" : " ";
        rows.push(`- [${mark}] ${a.text}\n    from [${n.id}] ${n.title} (${isoDate(n.createdAt)})`);
      }
    }
    if (!rows.length) {
      return text(args.includeDone ? "No action items at all." : "No open action items.");
    }
    const limit = args.limit ?? 100;
    const more = rows.length > limit ? `\n\n(${rows.length - limit} more.)` : "";
    return text(`${rows.length} action item(s):\n\n${rows.slice(0, limit).join("\n")}${more}`);
  },
);

server.registerTool(
  "set_follow_up",
  {
    title: "Record what became of a note",
    description:
      "Record whether the idea or plan in a note was pursued, dropped, or converted into " +
      "something concrete, with one sentence on what happened. The user sees it on the note " +
      "in the app. This is the only tool that writes, and it writes only this field — the " +
      "note's text, title and everything else are untouched. Use state 'clear' to remove it. " +
      "Record what the user told you or what the notes clearly show; never guess an outcome.",
    inputSchema: {
      id: z.string().min(1).describe("Note id."),
      state: z.enum(["pursued", "dropped", "converted", "clear"]),
      note: z
        .string()
        .max(500)
        .optional()
        .describe("What happened, in a sentence. E.g. 'Became the Tether app'."),
    },
  },
  async ({ id, state, note }) => {
    const value =
      state === "clear"
        ? null
        : { state: state.toUpperCase(), note: note?.trim() || null, updatedAt: Date.now(), by: "agent" };
    const ok = await reader.setFollowUp(id, value);
    if (!ok) return text(`No note with id ${id}. Nothing was written.`);
    cached = null; // the next read must see the write
    return text(value ? `Recorded on ${id}: ${state}${value.note ? ` — ${value.note}` : ""}.` : `Cleared the follow-up on ${id}.`);
  },
);

// stdout belongs to the protocol; anything written there corrupts the stream.
await server.connect(new StdioServerTransport());
console.error("yap-mcp ready");
