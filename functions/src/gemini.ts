import { GoogleGenAI, Type } from "@google/genai";

// Swap here if you prefer a different tier (e.g. gemini-2.5-pro for accuracy).
const MODEL = "gemini-2.5-flash";

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
  const base64 = audio.toString("base64");

  // 1) Transcription
  const transcription = await ai.models.generateContent({
    model: MODEL,
    contents: {
      parts: [
        { inlineData: { mimeType, data: base64 } },
        {
          text:
            "Precisely transcribe this audio. Return only the transcription text. " +
            "No metadata, no speaker labels, just the words spoken.",
        },
      ],
    },
  });
  const rawText = (transcription.text || "").trim();
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

  try {
    const text = (res.text || "{}").replace(/```json/g, "").replace(/```/g, "").trim();
    const parsed = JSON.parse(text);
    return {
      name: parsed.name ?? "Untitled Note",
      description: parsed.description ?? "",
      tags: Array.isArray(parsed.tags) ? parsed.tags : [],
      category: parsed.category ?? "Memo",
      topicCluster: parsed.topicCluster ?? "General",
      formattedText: parsed.formattedText ?? rawText,
    };
  } catch {
    return {
      name: "Untitled Note",
      description: "",
      tags: [],
      category: "Memo",
      topicCluster: "General",
      formattedText: rawText,
    };
  }
}
