import { FileState, GoogleGenAI } from "@google/genai";

/**
 * Word-level timed transcription, used to caption a shared video.
 *
 * This is the ONLY thing in the backend that talks to `gemini-3.5-transcribe`, and it is
 * deliberately walled off from the rest of the pipeline:
 *
 *  - It is a **preview** model. Nothing that produces an ordinary note may depend on it.
 *  - It is reached over **raw REST** rather than through `@google/genai`. Timed transcription
 *    lives on the Interactions API, whose `transcription_config` only exists in the v2 SDK;
 *    we are on v1. Bumping a major version of the SDK that every other AI call in this
 *    codebase runs through — transcription, formalization, tasks, persona — to reach one
 *    preview feature is a bad trade. One `fetch` keeps the blast radius at this file.
 *  - Every failure path returns `null`. Callers fall back to ordinary transcription, so the
 *    worst outcome is a video note with no captions, never a note that failed to transcribe.
 *
 * If the model graduates and the SDK settles, the swap happens here and nowhere else.
 */

export interface WordTiming {
  text: string;
  startMs: number;
  endMs: number;
}

export interface TimedTranscript {
  rawText: string;
  words: WordTiming[];
}

const MODEL = "gemini-3.5-transcribe";
const INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";

/**
 * Word timestamps are documented as supported up to 30 minutes (plain transcription goes to
 * an hour). Past that the request is refused, so don't spend an upload finding out.
 */
export const MAX_TIMED_SECONDS = 30 * 60;

/**
 * Ceiling on how many words we keep timings for.
 *
 * A Firestore document is capped at 1 MiB and the note's own text, tasks and metadata share
 * it. At roughly 40 bytes a word this stays under ~500 KB in the worst case, which leaves
 * real headroom. Past the cap the captions simply stop; the video still plays and the note
 * is unaffected, which is a far better failure than a write that is rejected outright.
 */
const MAX_WORDS = 12_000;

/** The API reports offsets as duration strings — "12.340s". */
function offsetToMs(raw: unknown): number | null {
  if (typeof raw === "number") return Math.round(raw * 1000);
  if (typeof raw !== "string") return null;
  const n = parseFloat(raw.endsWith("s") ? raw.slice(0, -1) : raw);
  return Number.isFinite(n) ? Math.round(n * 1000) : null;
}

/**
 * Transcribe with word-level timings. Returns null whenever timed transcription can't be
 * had — too long, upload failed, model refused, response shaped differently than documented.
 *
 * `customVocabulary` biases recognition toward terms the user has corrected before. It is
 * the one feature here that meaningfully beats the general model, so it is wired through
 * even though nothing populates it yet.
 */
export async function transcribeTimed(
  audio: Buffer,
  mimeType: string,
  apiKey: string,
  opts: { durationSec?: number; customVocabulary?: string[] } = {}
): Promise<TimedTranscript | null> {
  if (opts.durationSec && opts.durationSec > MAX_TIMED_SECONDS) {
    console.log(`transcribeTimed: skipped, ${Math.round(opts.durationSec)}s exceeds the 30min limit`);
    return null;
  }

  const ai = new GoogleGenAI({ apiKey });
  let fileName: string | null = null;

  try {
    // The Interactions API takes a file URI only — there is no inline path, so even a short
    // clip goes through the Files API.
    const blob = new Blob([new Uint8Array(audio)], { type: mimeType });
    let file = await ai.files.upload({ file: blob, config: { mimeType } });
    fileName = file.name ?? null;

    const deadline = Date.now() + 120_000;
    while (file.state === FileState.PROCESSING) {
      if (Date.now() > deadline) {
        console.warn("transcribeTimed: file processing timed out");
        return null;
      }
      await new Promise((r) => setTimeout(r, 2000));
      file = await ai.files.get({ name: file.name! });
    }
    if (file.state === FileState.FAILED || !file.uri) {
      console.warn(`transcribeTimed: file processing failed (${file.state})`);
      return null;
    }

    const res = await fetch(INTERACTIONS_URL, {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": apiKey },
      body: JSON.stringify({
        model: MODEL,
        input: [{ type: "audio", uri: file.uri, mime_type: file.mimeType || mimeType }],
        generation_config: {
          transcription_config: {
            // Verbatim, not "smart": smart mode cannot be combined with timestamps, and
            // captions should track what was actually said anyway. The polished prose comes
            // from the formalize pass over this same text, so nothing is lost by it.
            mode: { type: "verbatim", timestamp_granularities: ["word"] },
            ...(opts.customVocabulary?.length
              ? { custom_vocabulary: opts.customVocabulary.slice(0, 1000) }
              : {}),
          },
        },
      }),
    });

    if (!res.ok) {
      console.warn(`transcribeTimed: ${res.status} ${(await res.text()).slice(0, 400)}`);
      return null;
    }

    const body: any = await res.json();
    const content = body?.steps?.[0]?.content?.[0];
    const rawText = typeof content?.text === "string" ? content.text.trim() : "";
    if (!rawText) {
      console.warn("transcribeTimed: response carried no transcript");
      return null;
    }

    const words: WordTiming[] = [];
    for (const a of Array.isArray(content?.annotations) ? content.annotations : []) {
      if (a?.type !== "word_info") continue;
      const text = typeof a.text === "string" ? a.text : "";
      const startMs = offsetToMs(a.start_offset);
      const endMs = offsetToMs(a.end_offset);
      if (!text || startMs === null || endMs === null) continue;
      words.push({ text, startMs, endMs: Math.max(endMs, startMs) });
      if (words.length >= MAX_WORDS) break;
    }

    console.log(`transcribeTimed: ${rawText.length} chars, ${words.length} timed words`);
    // Text with no timings is just an ordinary transcript taken the expensive way — let the
    // caller use the cheap path instead.
    return words.length > 0 ? { rawText, words } : null;
  } catch (e: any) {
    console.warn("transcribeTimed failed (non-fatal)", { message: e?.message });
    return null;
  } finally {
    if (fileName) {
      try {
        await ai.files.delete({ name: fileName });
      } catch {
        // Non-fatal: uploads expire on their own after 48h.
      }
    }
  }
}
