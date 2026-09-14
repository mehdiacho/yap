import {
  createPartFromUri,
  FileState,
  GenerateContentParameters,
  GoogleGenAI,
  Part,
  Type,
} from "@google/genai";
import { TimedTranscript, transcribeTimed, WordTiming } from "./transcribeWords";
import { recordModelUsage } from "./costMeter";

// Swap here if you prefer a different tier (e.g. gemini-2.5-pro for accuracy).
const MODEL = "gemini-2.5-flash";

/**
 * Every model call in this file goes through here, so none of them can be billed without the
 * cost meter seeing it.
 *
 * The indirection is about coverage, not convenience. A note costs three or four calls, only
 * one of which is the transcription, so an estimate built from audio length alone misses most
 * of the bill. Adding a bare generateContent call somewhere in this file would silently
 * reopen exactly that gap — so there is one door, and it is this one.
 *
 * The meter write is awaited. It costs tens of milliseconds against a model call measured in
 * seconds, and leaving it dangling past the response risks the runtime freezing the instance
 * before it lands; a meter that quietly drops calls is worse than no meter at all.
 */
async function callModel(ai: GoogleGenAI, op: string, params: GenerateContentParameters) {
  const res = await ai.models.generateContent(params);
  await recordModelUsage(op, String(params.model), (res as any)?.usageMetadata);
  return res;
}

/**
 * Above this, audio goes through the Files API instead of being inlined.
 *
 * Inline data is base64, which inflates by 4/3, and the Gemini API caps TOTAL request
 * size at ~20 MB. 12 MB of audio -> ~16 MB base64, which leaves headroom for the prompt.
 * Past that the request is rejected outright, no matter how much memory the function has.
 *
 * At our canonical 16 kHz mono 16-bit WAV (32 KB/s) this is ~6 minutes. Short clips —
 * the common case — stay inline and skip the upload + processing round-trip.
 */
const INLINE_MAX_BYTES = 12 * 1024 * 1024;

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * Gemini accepts a fixed set of audio MIME types. Pickers and content resolvers hand us
 * common aliases instead — Android's SAF reported `audio/x-wav` for a plain WAV — and an
 * unrecognised type is rejected outright. Normalising here fixes every caller at once,
 * which matters because the alternative is a store release per client.
 */
const MIME_ALIASES: Record<string, string> = {
  "audio/x-wav": "audio/wav",
  "audio/wave": "audio/wav",
  "audio/vnd.wave": "audio/wav",
  "audio/x-pn-wav": "audio/wav",
  "audio/x-aiff": "audio/aiff",
  "audio/x-flac": "audio/flac",
  "audio/mpeg3": "audio/mp3",
  "audio/x-mpeg-3": "audio/mp3",
  "audio/mpeg": "audio/mp3",
  "audio/x-m4a": "audio/mp4",
  "audio/m4a": "audio/mp4",
  "audio/opus": "audio/ogg",
};

export function normalizeAudioMime(mimeType: string): string {
  const base = (mimeType || "").split(";")[0].trim().toLowerCase();
  return MIME_ALIASES[base] ?? base ?? "audio/wav";
}

/**
 * Build the audio part for a request: inline for small clips, Files API for the rest.
 *
 * Returns the part plus an optional cleanup handle. Uploaded files expire on their own
 * after 48h, but deleting them keeps the project tidy and avoids quota creep.
 */
async function audioPart(
  ai: GoogleGenAI,
  audio: Buffer,
  mimeType: string
): Promise<{ part: Part; cleanup: () => Promise<void> }> {
  mimeType = normalizeAudioMime(mimeType);
  if (audio.length <= INLINE_MAX_BYTES) {
    return {
      part: { inlineData: { mimeType, data: audio.toString("base64") } },
      cleanup: async () => {},
    };
  }

  // Uint8Array view avoids a second full copy of the payload when wrapping in a Blob.
  const blob = new Blob([new Uint8Array(audio)], { type: mimeType });
  let file = await ai.files.upload({ file: blob, config: { mimeType } });

  // Audio is processed server-side before it can be referenced; poll until it's ACTIVE.
  const deadline = Date.now() + 120_000;
  while (file.state === FileState.PROCESSING) {
    if (Date.now() > deadline) {
      throw new Error(`Gemini file processing timed out after 120s (${file.name})`);
    }
    await sleep(2000);
    file = await ai.files.get({ name: file.name! });
  }
  if (file.state === FileState.FAILED || !file.uri) {
    throw new Error(`Gemini file processing failed (${file.name}): ${file.state}`);
  }

  const name = file.name!;
  return {
    part: createPartFromUri(file.uri, file.mimeType || mimeType),
    cleanup: async () => {
      try {
        await ai.files.delete({ name });
      } catch {
        // Non-fatal: the file expires by itself in 48h.
      }
    },
  };
}

export interface TranscriptionResult {
  rawText: string;
  name: string;
  description: string;
  tags: string[];
  category: string;
  topicCluster: string;
  formattedText: string;
  /**
   * Word-level timings, present only for a note that asked for them and got them (a shared
   * video). Absent everywhere else — see transcribeWords.ts for why this never blocks.
   */
  words?: WordTiming[];
}

/**
 * Full pipeline for a single recording: transcribe the audio, then formalize it
 * into a structured note. Ported from the web app's geminiService.ts.
 * (Chunking + 30s bridge-stitch for very long audio can be layered in later;
 * Gemini handles typical idea-length recordings in a single request.)
 */
export async function transcribeAndFormalize(
  audio: Buffer,
  mimeType: string,
  apiKey: string,
  opts: { wordTimings?: boolean; durationSec?: number } = {}
): Promise<TranscriptionResult> {
  const ai = new GoogleGenAI({ apiKey });

  // 1) Transcription.
  //
  // A note that will be played back as video asks for word timings so the transcript can be
  // captioned against the picture. That runs on a different, preview model, so it is tried
  // first and allowed to decline: a null answer drops through to the ordinary path below —
  // the same one every other note in the app takes.
  const timed: TimedTranscript | null = opts.wordTimings
    ? await transcribeTimed(audio, mimeType, apiKey, { durationSec: opts.durationSec })
    : null;

  let rawText: string;
  if (timed) {
    rawText = timed.rawText;
  } else {
    const { part, cleanup } = await audioPart(ai, audio, mimeType);
    try {
      const transcription = await callModel(ai, "transcribe", {
        model: MODEL,
        contents: {
          parts: [
            part,
            {
              text:
                "Precisely transcribe this audio. Return only the transcription text. " +
                "No metadata, no speaker labels, just the words spoken.",
            },
          ],
        },
      });
      rawText = (transcription.text || "").trim();
    } finally {
      await cleanup();
    }
  }
  if (!rawText) {
    return {
      rawText: "",
      name: "Empty recording",
      description: "No speech detected.",
      tags: [],
      category: "Memo",
      topicCluster: "General",
      formattedText: "",
    };
  }

  // 2) Formalization (name / description / tags / category / topicCluster / formatted markdown)
  const meta = await formalize(ai, rawText);
  return { rawText, ...meta, ...(timed ? { words: timed.words } : {}) };
}

/**
 * Append-to-note: transcribe a NEW clip, then integrate it into an EXISTING note
 * without rewriting unrelated content (e.g. new items land on the existing list).
 */
export async function transcribeAndAppend(
  audio: Buffer,
  mimeType: string,
  existingTitle: string,
  existingFormatted: string,
  existingDraft: string,
  apiKey: string
): Promise<{ rawText: string; formattedText: string; name: string }> {
  const ai = new GoogleGenAI({ apiKey });
  const { part, cleanup } = await audioPart(ai, audio, mimeType);

  let rawText: string;
  try {
    const transcription = await callModel(ai, "appendTranscribe", {
      model: MODEL,
      contents: {
        parts: [
          part,
          {
            text:
              "Precisely transcribe this audio. Return only the transcription text. " +
              "No metadata, no speaker labels, just the words spoken.",
          },
        ],
      },
    });
    rawText = (transcription.text || "").trim();
  } finally {
    await cleanup();
  }
  if (!rawText) {
    return { rawText: "", formattedText: existingFormatted, name: existingTitle };
  }

  const prompt = `You are updating an EXISTING note with NEWLY RECORDED content. The user re-opened this note and spoke an addition — integrate it, do not regenerate the document.

### Existing note title:
${existingTitle}

### Existing note (Markdown):
${existingFormatted || existingDraft}

### New transcript to integrate:
${rawText}

### Integration rules (strict):
1. The existing note is the source of truth. Preserve its content, wording, and structure — never rewrite, reorder, or reformat parts the new content doesn't touch.
2. Put the new content where it belongs: if the note is a list and the new content adds entries, append them to that list (skip items already present). If it continues an existing section, extend that section. Only create a new section when nothing fits.
3. Clean the new content (remove filler words) and match the note's existing style and formatting.
4. If the new content corrects or supersedes something in the note, update just that part.
5. Title: keep "${existingTitle}" unless it is a placeholder like "Untitled" or "Recording", in which case propose a better one.
6. Markdown rules: body as plain paragraphs/lists; "## " section headers only where genuinely needed; never put sentences inside headings.

Return ONLY a JSON object: {"name": string, "formattedText": string (the FULL updated note)}.`;

  const res = await callModel(ai, "appendFormalize", {
    model: MODEL,
    contents: prompt,
    config: {
      responseMimeType: "application/json",
      responseSchema: {
        type: Type.OBJECT,
        properties: {
          name: { type: Type.STRING },
          formattedText: { type: Type.STRING },
        },
        required: ["name", "formattedText"],
      },
    },
  });

  try {
    const text = (res.text || "{}").replace(/```json/g, "").replace(/```/g, "").trim();
    const parsed = JSON.parse(text);
    const merged = parsed.formattedText ?? "";
    return {
      rawText,
      // Guard the failure mode where the model returns only the addition: the merged doc
      // should never be dramatically shorter than what it started with.
      formattedText:
        merged.length >= existingFormatted.length * 0.6 && merged.length > 0
          ? merged
          : `${existingFormatted}\n\n${rawText}`,
      name: parsed.name ?? existingTitle,
    };
  } catch {
    return { rawText, formattedText: `${existingFormatted}\n\n${rawText}`, name: existingTitle };
  }
}

export interface ReviewBlock {
  versions: string[];
  activeIndex: number;
}

export interface ReviewedBlock {
  blockIndex: number;
  newText: string;
  flagged: boolean;
  reason?: string;
}

/**
 * Smart Review — ported from the web app's reviewAndPropagateEdits.
 * Takes the user's manual block corrections, propagates them across the whole
 * document, and flags blocks where a correction would shift core meaning.
 */
export async function reviewAndPropagate(
  blocks: ReviewBlock[],
  apiKey: string
): Promise<ReviewedBlock[]> {
  const manualChanges = blocks
    .map((b, i) =>
      b.versions.length > 1
        ? { blockIndex: i, original: b.versions[0], corrected: b.versions[b.versions.length - 1] }
        : null
    )
    .filter((c): c is NonNullable<typeof c> => c !== null);
  if (manualChanges.length === 0) return [];

  const ai = new GoogleGenAI({ apiKey });
  const prompt = `You are an expert AI text review and correction propagation system.
The user has a transcription document split into discrete text blocks (paragraphs, headers, or bullet elements).
The user has manually edited some blocks to fix misspelled words (names, brands, acronyms) or logical slips.

Here is the list of the user's MANUAL CORRECTIONS:
${JSON.stringify(manualChanges, null, 2)}

Here is the entire active document as an array of block index and active text:
${JSON.stringify(blocks.map((b, i) => ({ index: i, text: b.versions[b.activeIndex] })), null, 2)}

Your assignment:
1. Analyze the manual corrections to understand the pattern (spelling corrections, name changes, terminology swaps).
2. Scan the rest of the document's blocks and propagate those corrections wherever relevant.
3. CONTEXTUAL LOGIC AUDIT: if applying a correction would alter core semantic meaning, timeline, financial figures, or logical flow, you MUST flag that block.
4. Return ONLY the blocks you updated (or flagged), with "flagged" true where the user should manually verify, and a helpful "reason".

Return a JSON object: {"updatedBlocks":[{"blockIndex":number,"newText":"string (full corrected block text, markdown preserved)","flagged":boolean,"reason":"string (optional)"}]}
Only pure JSON, no commentary.`;

  const res = await callModel(ai, "review", {
    model: MODEL,
    contents: prompt,
    config: {
      responseMimeType: "application/json",
      responseSchema: {
        type: Type.OBJECT,
        properties: {
          updatedBlocks: {
            type: Type.ARRAY,
            items: {
              type: Type.OBJECT,
              properties: {
                blockIndex: { type: Type.INTEGER },
                newText: { type: Type.STRING },
                flagged: { type: Type.BOOLEAN },
                reason: { type: Type.STRING },
              },
              required: ["blockIndex", "newText", "flagged"],
            },
          },
        },
        required: ["updatedBlocks"],
      },
    },
  });

  try {
    const text = (res.text || "{}").replace(/```json/g, "").replace(/```/g, "").trim();
    return JSON.parse(text).updatedBlocks ?? [];
  } catch {
    return [];
  }
}

export interface OccurrenceVerdict {
  index: number;
  /** True when this occurrence looks like the word was MEANT — i.e. do not change it. */
  intentional: boolean;
  /** Short reason, shown only when intentional. */
  reason: string;
}

/**
 * Given one hand-made correction and every remaining occurrence of the old word (each as a
 * short context snippet), say which ones look deliberate.
 *
 * Purely advisory. The client finds occurrences itself with a literal scan — offline,
 * instant, deterministic — and this only annotates them. If it fails, times out, or returns
 * nonsense, the user still gets the full list and decides; nothing here is on the critical
 * path. That ordering matters: fixing a typo should never depend on a model round trip.
 *
 * It is asked to flag ONLY the clear cases, because a false caution on a genuine mistake is
 * worse than no caution at all — it makes the user second-guess a fix they already know is
 * right, which is the whole reason they used this feature.
 */
export async function judgeCorrectionOccurrences(
  from: string,
  to: string,
  snippets: string[],
  apiKey: string
): Promise<OccurrenceVerdict[]> {
  if (snippets.length === 0) return [];
  const ai = new GoogleGenAI({ apiKey });

  const prompt = `A user corrected a speech-transcription error: the word "${from}" should have been "${to}".

They want to apply the same fix elsewhere in the document. Below are the remaining places "${from}" appears, with surrounding context.

For each one, decide whether "${from}" was ACTUALLY INTENDED there — i.e. changing it to "${to}" would be WRONG.

${snippets.map((s, i) => `[${i}] …${s}…`).join("\n")}

Rules:
- Default to intentional: false. The user already knows this transcription error is systematic.
- Only set intentional: true when the context makes the original word clearly correct — a different, well-established meaning ("cloud storage", "cloud computing", weather).
- A false alarm is costly: it makes the user doubt a fix they know is right. When unsure, say false.
- reason: at most 8 words, and only when intentional is true. Otherwise "".

Return ONLY JSON: {"verdicts":[{"index":number,"intentional":boolean,"reason":string}]}`;

  const res = await callModel(ai, "correctionJudge", {
    model: MODEL,
    contents: prompt,
    config: {
      responseMimeType: "application/json",
      responseSchema: {
        type: Type.OBJECT,
        properties: {
          verdicts: {
            type: Type.ARRAY,
            items: {
              type: Type.OBJECT,
              properties: {
                index: { type: Type.INTEGER },
                intentional: { type: Type.BOOLEAN },
                reason: { type: Type.STRING },
              },
              required: ["index", "intentional", "reason"],
            },
          },
        },
        required: ["verdicts"],
      },
    },
  });

  try {
    const text = (res.text || "{}").replace(/```json/g, "").replace(/```/g, "").trim();
    const parsed = JSON.parse(text).verdicts;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (v: any) =>
        typeof v?.index === "number" && v.index >= 0 && v.index < snippets.length
    );
  } catch {
    return [];
  }
}

async function formalize(
  ai: GoogleGenAI,
  rawText: string
): Promise<Omit<TranscriptionResult, "rawText">> {
  const prompt = `You are an advanced transcription analysis system. Analyze the following RAW TRANSCRIPT and generate a cohesive, structured JSON response.

### Raw Transcript:
${rawText}

### Tasks:
1. **Name**: A short, catchy, highly descriptive title (max 6 words).
2. **Description**: One brief sentence describing the main topic.
3. **Tags**: 3 to 5 relevant keyword strings for searchability.
4. **Category**: The single best document type. Examples: "Memo", "Brainstorm", "Call Log", "Action Items", "Meeting Notes", "Interview", "Lecture", "Journal".
5. **TopicCluster**: A short shared project/theme name if the content clearly belongs to one; otherwise "General".
6. **FormattedText**: A cleaned, formalized Markdown version. Remove filler words (um, like, you know). Structure for readability: use headers, bullet points, bold key terms, tables for comparisons/numbers, blockquotes and dialogue formatting where appropriate.

### FormattedText markdown rules (strict):
- Do NOT include a document title heading — the Name field is the title. Start with body content.
- Section headers use exactly "## " (H2), sparingly — only when the content genuinely has sections. Short notes need no headers at all.
- Headings are single short lines. NEVER put body sentences, lists, or whole paragraphs inside a heading.
- The bulk of the output must be plain paragraphs and lists — if most lines start with '#', the output is wrong.
- For dialogue, use "**SPEAKER:** line" formatting. For action items, use a bullet list.

Return ONLY a valid JSON object matching the schema. No markdown fences, no commentary.`;

  const res = await callModel(ai, "formalize", {
    model: MODEL,
    contents: prompt,
    config: {
      responseMimeType: "application/json",
      responseSchema: {
        type: Type.OBJECT,
        properties: {
          name: { type: Type.STRING },
          description: { type: Type.STRING },
          tags: { type: Type.ARRAY, items: { type: Type.STRING } },
          category: { type: Type.STRING },
          topicCluster: { type: Type.STRING },
          formattedText: { type: Type.STRING },
        },
        required: ["name", "description", "tags", "category", "topicCluster", "formattedText"],
      },
    },
  });

  const first = parseFormalizeResponse(res.text, rawText);

  // Format verification: when heading structure is clearly broken (the "everything is a
  // heading" failure mode), do exactly one corrective repair pass on the markdown.
  if (markdownLooksBroken(first.formattedText)) {
    console.warn("formalize: broken markdown detected, running repair pass");
    try {
      const repair = await callModel(ai, "formalizeRepair", {
        model: MODEL,
        contents:
          `The following Markdown misuses headings (body text inside '#' lines, or nearly ` +
          `every line is a heading). Rewrite it with correct structure: body content as plain ` +
          `paragraphs and lists; at most a few short "## " section headers; no title heading. ` +
          `Preserve the meaning and all content. Return ONLY the fixed Markdown.\n\n` +
          first.formattedText,
      });
      const fixed = (repair.text || "").trim();
      if (fixed && !markdownLooksBroken(fixed)) {
        return { ...first, formattedText: fixed };
      }
    } catch (e: any) {
      console.warn("formalize repair pass failed", { message: e?.message });
    }
  }
  return first;
}

function parseFormalizeResponse(
  raw: string | undefined,
  fallbackText: string
): Omit<TranscriptionResult, "rawText"> {
  try {
    const text = (raw || "{}").replace(/```json/g, "").replace(/```/g, "").trim();
    const parsed = JSON.parse(text);
    return {
      name: parsed.name ?? "Untitled Note",
      description: parsed.description ?? "",
      tags: Array.isArray(parsed.tags) ? parsed.tags : [],
      category: parsed.category ?? "Memo",
      topicCluster: parsed.topicCluster ?? "General",
      formattedText: parsed.formattedText ?? fallbackText,
    };
  } catch {
    return {
      name: "Untitled Note",
      description: "",
      tags: [],
      category: "Memo",
      topicCluster: "General",
      formattedText: fallbackText,
    };
  }
}

/** Heuristic for the observed failure mode: heading-dominated or run-on-heading markdown. */
function markdownLooksBroken(md: string): boolean {
  const lines = md.split("\n").map((l) => l.trim()).filter((l) => l.length > 0);
  if (lines.length < 3) return false;
  const headings = lines.filter((l) => /^#{1,6}\s/.test(l));
  if (headings.length === 0) return false;
  const dominated = headings.length / lines.length > 0.5;
  const runOn = headings.some((l) => l.length > 120);
  return dominated || runOn;
}

/** Text-only refine for "Polish now" on draft-only notes (no audio round-trip). */
export async function formalizeText(
  rawText: string,
  apiKey: string
): Promise<Omit<TranscriptionResult, "rawText">> {
  const ai = new GoogleGenAI({ apiKey });
  return formalize(ai, rawText);
}

/** One note going into a merge. [createdAt] is epoch ms; the order it was recorded matters. */
export interface NoteToGroup {
  id: string;
  title: string;
  createdAt: number;
  text: string;
}

/**
 * Weave several notes into one document — the Library's "Group" action.
 *
 * This is a merge, not a summary. Someone who recorded the same project four times across a
 * week is asking for the one document they would have written if they had thought of
 * everything at once; handing them a précis throws away the detail they recorded it for. So
 * the prompt's first duty is to keep every substantive point, and its second is to stop the
 * result reading like four notes stapled together.
 *
 * The sources are left untouched — grouping creates a new note and destroys nothing. That is
 * a deliberate asymmetry: a merge that consumed its inputs would be the one action in the
 * app a user could not undo by hand.
 *
 * Returns the same shape as [formalize], so the client stores a merged note exactly the way
 * it stores a refined one.
 */
export async function groupNotes(
  notes: NoteToGroup[],
  apiKey: string
): Promise<Omit<TranscriptionResult, "rawText">> {
  const ai = new GoogleGenAI({ apiKey });

  // Oldest first: "later supersedes earlier" is only a usable instruction if the model can
  // see which is which, and recording order is the one piece of context the text itself lost.
  const ordered = [...notes].sort((a, b) => a.createdAt - b.createdAt);
  const sources = ordered
    .map((n, i) => {
      const when = new Date(n.createdAt).toISOString().slice(0, 10);
      return `### Source ${i + 1} — "${n.title}" (${when})\n${n.text}`;
    })
    .join("\n\n");

  const prompt = `You are merging several of one person's notes into a single document. They are all by the same author and were captured at different times.

${sources}

### What to produce
ONE document that reads as though the author wrote it in a single sitting, knowing everything across all ${ordered.length} sources.

### Rules — these matter more than brevity
1. **This is a merge, not a summary.** Keep every substantive point, decision, number, name and open question. Length is not a problem; losing content is. Only filler, repetition and restatement may go.
2. **Organise by subject, not by source.** Points about the same thing belong together even when they were recorded days apart. Never write "in the first note…" or reproduce the sources as separate sections.
3. **Consolidate overlap.** Where sources repeat a point, state it once, in its fullest form.
4. **Later supersedes earlier.** When a later source revises an earlier one, follow the later one. If the change is a real reversal worth knowing about, say so briefly in the prose ("originally X, later settled on Y") rather than silently dropping either.
5. **Contradictions that are not resolved by time stay visible.** Present both and note the tension — don't pick a winner the author didn't pick.
6. **Add nothing.** No invented conclusions, next steps or connective claims the sources don't support.

### Fields
1. **Name**: A short, descriptive title for the merged document (max 6 words).
2. **Description**: One sentence describing what the merged document covers.
3. **Tags**: 3 to 5 keyword strings drawn from across all sources.
4. **Category**: The single best document type for the result. Examples: "Memo", "Brainstorm", "Call Log", "Action Items", "Meeting Notes", "Interview", "Lecture", "Journal".
5. **TopicCluster**: The shared project/theme these notes belong to — grouping them is itself evidence there is one. Use "General" only if they genuinely have nothing in common.
6. **FormattedText**: The merged document, in Markdown.

### FormattedText markdown rules (strict):
- Do NOT include a document title heading — the Name field is the title. Start with body content.
- Section headers use exactly "## " (H2), sparingly — only when the content genuinely has sections.
- Headings are single short lines. NEVER put body sentences, lists, or whole paragraphs inside a heading.
- The bulk of the output must be plain paragraphs and lists — if most lines start with '#', the output is wrong.
- For dialogue, use "**SPEAKER:** line" formatting. For action items, use a bullet list.

Return ONLY a valid JSON object matching the schema. No markdown fences, no commentary.`;

  const res = await callModel(ai, "group", {
    model: MODEL,
    contents: prompt,
    config: {
      responseMimeType: "application/json",
      responseSchema: {
        type: Type.OBJECT,
        properties: {
          name: { type: Type.STRING },
          description: { type: Type.STRING },
          tags: { type: Type.ARRAY, items: { type: Type.STRING } },
          category: { type: Type.STRING },
          topicCluster: { type: Type.STRING },
          formattedText: { type: Type.STRING },
        },
        required: ["name", "description", "tags", "category", "topicCluster", "formattedText"],
      },
    },
  });

  // Falling back to the concatenated sources rather than to one note's text: if the model
  // returns nothing parseable, the user should still get everything they selected, badly
  // formatted, instead of a note that silently contains a fraction of what they merged.
  const fallback = ordered.map((n) => n.text).join("\n\n---\n\n");
  const first = parseFormalizeResponse(res.text, fallback);

  if (markdownLooksBroken(first.formattedText)) {
    console.warn("group: broken markdown detected, running repair pass");
    try {
      const repair = await callModel(ai, "groupRepair", {
        model: MODEL,
        contents:
          `The following Markdown misuses headings (body text inside '#' lines, or nearly ` +
          `every line is a heading). Rewrite it with correct structure: body content as plain ` +
          `paragraphs and lists; at most a few short "## " section headers; no title heading. ` +
          `Preserve the meaning and all content. Return ONLY the fixed Markdown.\n\n` +
          first.formattedText,
      });
      const fixed = (repair.text || "").trim();
      if (fixed && !markdownLooksBroken(fixed)) {
        return { ...first, formattedText: fixed };
      }
    } catch (e: any) {
      console.warn("group repair pass failed", { message: e?.message });
    }
  }
  return first;
}

/**
 * Pull the actionable tasks out of a note.
 *
 * Separate from `formalize` on purpose: this has to run over the thousands of words of notes
 * that already exist, long after they were transcribed, without re-doing transcription or
 * touching their prose. One entry point serves both the fresh-recording path and the backfill
 * (see CURRENT_ENRICH_VERSION on the client), so there is exactly one definition of what
 * counts as a task.
 *
 * Returns [] freely. Most notes are not to-do lists, and inventing tasks from a rambling idea
 * memo would make the feature noise — a user who sees one bogus checkbox stops trusting all
 * of them.
 */
export async function extractActionItems(text: string, apiKey: string): Promise<string[]> {
  const trimmed = (text || "").trim();
  if (trimmed.length < 20) return [];
  const ai = new GoogleGenAI({ apiKey });

  const prompt = `Read this note and list ONLY the concrete, actionable tasks it contains.

### Note:
${trimmed.slice(0, 40000)}

Rules:
- A task is something a person could put on a to-do list and later tick off.
- Ignore observations, opinions, background, and decisions already made.
- Rewrite each as a short imperative starting with a verb ("Email Sam the deck"), max 12 words.
- Keep the note's own nouns and names; do not generalise them away.
- Do NOT invent tasks. Most notes have none — returning an empty list is the correct answer
  far more often than not.
- At most 20, in the order they appear.

Return ONLY JSON: {"tasks":["...", "..."]}`;

  const res = await callModel(ai, "actionItems", {
    model: MODEL,
    contents: prompt,
    config: {
      responseMimeType: "application/json",
      responseSchema: {
        type: Type.OBJECT,
        properties: { tasks: { type: Type.ARRAY, items: { type: Type.STRING } } },
        required: ["tasks"],
      },
    },
  });

  try {
    const raw = (res.text || "{}").replace(/```json/g, "").replace(/```/g, "").trim();
    const tasks = JSON.parse(raw).tasks;
    if (!Array.isArray(tasks)) return [];
    return tasks
      .filter((t: unknown): t is string => typeof t === "string" && t.trim().length > 0)
      .map((t) => t.trim().slice(0, 200))
      .slice(0, 20);
  } catch {
    return [];
  }
}
