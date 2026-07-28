import { createPartFromUri, FileState, GoogleGenAI, Part, Type } from "@google/genai";

// Swap here if you prefer a different tier (e.g. gemini-2.5-pro for accuracy).
const MODEL = "gemini-2.5-flash";

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
  apiKey: string
): Promise<TranscriptionResult> {
  const ai = new GoogleGenAI({ apiKey });
  const { part, cleanup } = await audioPart(ai, audio, mimeType);

  // 1) Transcription
  let rawText: string;
  try {
    const transcription = await ai.models.generateContent({
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
  return { rawText, ...meta };
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
    const transcription = await ai.models.generateContent({
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

  const res = await ai.models.generateContent({
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

  const res = await ai.models.generateContent({
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

  const res = await ai.models.generateContent({
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
      const repair = await ai.models.generateContent({
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
