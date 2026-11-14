# File: geminiService.ts

```typescript

import { GoogleGenAI, GenerateContentResponse, Type } from "@google/genai";

/**
 * Formats seconds into WebVTT timestamp format: HH:MM:SS.mmm
 */
function formatVttTime(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  const ms = Math.floor((seconds % 1) * 1000);
  return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}.${ms.toString().padStart(3, '0')}`;
}

/**
 * Transcribes audio with optional WebVTT formatting and global time offset.
 */
export async function transcribeAudio(
  audioBlob: Blob, 
  isVttMode: boolean = false, 
  timeOffsetSeconds: number = 0
): Promise<string> {
  const apiKey = process.env.API_KEY;
  if (!apiKey) {
    throw new Error("API_KEY_NOT_FOUND");
  }

  const ai = new GoogleGenAI({ apiKey: process.env.API_KEY });
  const base64Data = await blobToBase64(audioBlob);

  const startTimeStr = formatVttTime(timeOffsetSeconds);

  const systemInstruction = isVttMode 
    ? `You are a professional subtitle generator. Transcribe this audio in WebVTT format. 
       IMPORTANT: The very first timestamp for this specific segment MUST begin at or after [${startTimeStr}]. 
       Use a period (.) for milliseconds (e.g., 00:00:00.000). 
       Do not include the 'WEBVTT' header in individual chunk responses; only provide the timestamped cues.
       Follow the standard cue structure: 
       [Optional Index]
       [Start Time] --> [End Time]
       [Text]
       Ensure timestamps are strictly chronological relative to the file start.`
    : "Precisely transcribe this audio. Return only the transcription text. No metadata, no speakers labels, just the words spoken.";

  const response: GenerateContentResponse = await ai.models.generateContent({
    model: 'gemini-3.5-flash',
    contents: {
      parts: [
        {
          inlineData: {
            mimeType: 'audio/wav',
            data: base64Data
          }
        },
        {
          text: systemInstruction
        }
      ]
    },
  });

  return response.text || "[EMPTY_TRANSCRIPTION]";
}

/**
 * Extracts a fitting name, description, and tags from the transcript context.
 */
export async function generateSessionMetadata(transcript: string): Promise<{name: string, description: string, tags: string[]}> {
  const apiKey = process.env.API_KEY || process.env.GEMINI_API_KEY;
  if (!apiKey) {
    throw new Error("API_KEY_NOT_FOUND");
  }

  const ai = new GoogleGenAI({ apiKey });
  const prompt = `Analyze the following transcript and generate a JSON object with:
  - 'name': a short, catchy and descriptive title for this session (max 6 words).
  - 'description': a brief sentence describing the main topic of the text.
  - 'tags': an array of 3 to 5 relevant keyword strings for searchability.

  Only output raw JSON, no markdown code blocks, no explanation.
  Format: {"name": "...", "description": "...", "tags": ["...", "..."]}

  Transcript:
  ${transcript.substring(0, Math.min(transcript.length, 30000))} // limit to roughly 30k chars`;

  try {
    const response = await ai.models.generateContent({
      model: 'gemini-3.5-flash',
      contents: prompt,
    });
    const text = response.text || "{}";
    const cleanJson = text.replace(/```json/g, "").replace(/```/g, "").trim();
    return JSON.parse(cleanJson);
  } catch (err) {
    console.error("Failed to generate metadata", err);
    return { name: "Untitled Session", description: "No description available.", tags: [] };
  }
}

/**
 * Feature 4: Overlap Alignment Algorithm (Anti-Hallucination)
 * Receives Chunk A (Left), Chunk B (Right), and Bridge Chunk C.
 * Slices out repeating phrases, stuttering, and boundary hallucinations.
 */
export async function stitchWithBridge(
  textA: string,
  textB: string,
  textBridge: string
): Promise<string> {
  const apiKey = process.env.API_KEY || process.env.GEMINI_API_KEY;
  if (!apiKey) {
    throw new Error("API_KEY_NOT_FOUND");
  }

  const ai = new GoogleGenAI({ apiKey });
  
  const isVttFormat = textA.includes('-->') || textBridge.includes('-->');
  const vttInstruction = isVttFormat
    ? `IMPORTANT: The input texts are in WebVTT cue format. The final output MUST preserve the WebVTT cue block structure, containing sequential cue indexes, chronological timestamps in 'HH:MM:SS.mmm --> HH:MM:SS.mmm' format (always use period dot for millisecond separator, e.g. 00:04:15.200), and the associated cue text lines. Remove duplicated, cut-off cues, align timestamps perfectly chronologically, and resolve overlapping subtitles around the 4:30 boundary seamlessly using Part Bridge. Do not output the top-level 'WEBVTT' header in individual stitches; just output the raw stitched cues.`
    : `Strip repeat filler text, incomplete sentences, fade-out/fade-in stutters, or boundary hallucinations at the tail of Part A and the head of Part B. Seamlessly stitch the continuous content of Part Bridge across the boundary, then output the fully aligned text.`;

  const prompt = `You are an expert audio transcription alignment system. Your job is to seamlessly stitch together two sequential transcriptions (Part A and Part B) by using a "bridge" transcription (Part Bridge) that spans the exact boundary between them.

The boundary is exactly where Part A cuts off and Part B begins. Because of this artificial cutoff, the tail of Part A and the head of Part B often contain repeating phrases, cut-off words, or hallucinated filler text due to silence/fade-out/fade-in.

Part Bridge represents a completely continuous, fade-free, single transcription of the same audio spanning from 30 seconds before the boundary to 30 seconds after the boundary. It is highly accurate and continuous, but only covers a 60-second window centered at the boundary.

### Input Data:
1. **PART A (Left Chunk)**: Ends at the boundary.
"${textA}"

2. **PART B (Right Chunk)**: Starts at the boundary.
"${textB}"

3. **PART BRIDGE (The continuous 60-second bridge across the boundary)**:
"${textBridge}"

### Rules:
- The beginning of PART BRIDGE overlaps with the tail of PART A.
- The end of PART BRIDGE overlaps with the head of PART B.
- Use PART BRIDGE to bridge the transition seamlessly.
- ${vttInstruction}
- The final output should flow beautifully and naturally as a single continuous transcription without any missing content, stuttering, duplicates, or commentary.
- Return ONLY the final stitched, continuous transcript. Do not output JSON, do not include warnings or introduction, just the raw plain content. Do not wrap in markdown or code blocks.`;

  try {
    const response = await ai.models.generateContent({
      model: 'gemini-3.5-flash',
      contents: prompt,
    });
    return response.text?.trim() || `${textA}\n\n${textB}`;
  } catch (err) {
    console.error("Error in stitching transcripts with bridge:", err);
    return `${textA}\n\n${textB}`;
  }
}

/**
 * Feature 1 & 2: Intelligent Categorization & Dynamic Structuring LLM Pipeline
 * Analyzes raw transcript text to output: name, description, tags, category, topicCluster, formatted_text
 */
export async function processSessionMetadataAndFormatting(
  rawText: string,
  recentSessions: Array<{ name: string; topicCluster?: string; category?: string }>
): Promise<{
  name: string;
  description: string;
  tags: string[];
  category: string;
  topicCluster: string;
  formatted_text: string;
}> {
  const apiKey = process.env.API_KEY || process.env.GEMINI_API_KEY;
  if (!apiKey) {
    throw new Error("API_KEY_NOT_FOUND");
  }

  const ai = new GoogleGenAI({ apiKey });

  // Format recent sessions as context to match continuous threads/themes
  const recentSessionsContext = recentSessions && recentSessions.length > 0
    ? recentSessions.map(s => `- Session: "${s.name}" | Category: "${s.category || 'N/A'}" | Topic/Cluster: "${s.topicCluster || 'None'}"`).join('\n')
    : "No recent sessions in databank.";

  const prompt = `You are an advanced transcription analysis system. Analyze the following RAW TRANSCRIPT and generate a cohesive, structured JSON response.

### Raw Transcript:
${rawText}

### Context - Recently Saved Sessions:
${recentSessionsContext}

### Tasks:
1. **Name**: Generate a short, catchy, and highly descriptive title for this session (maximum 6 words).
2. **Description**: Generate a brief sentence describing the main topic of this session.
3. **Tags**: Generate an array of 3 to 5 relevant keyword strings for searchability.
4. **Category**: Assign a single optimal category representing the document type based on a dynamic taxonomy. Examples: "Memo", "Brainstorm", "Call Log", "Action Items", "Meeting Notes", "Interview", "Lecture", "Journal". Determine the absolute best category for this specific transcript based on the context.
5. **TopicCluster**: Analyze if the core topic or project mentioned in this raw transcript matches any continuous thread or theme among the Recently Saved Sessions (e.g. if there are multiple recordings over several days about the same project, homework, meeting series, or client).
   - If a match is found, use the *exact* same topicCluster name from the matching session so they are linked in the UI database.
   - If it represents a new project/topic, generate a new shared topic cluster name (e.g. "SpaceX Launch Engine").
   - If it's a completely random, isolated, or generic discussion, return "General".
6. **FormattedText (Formalization & Structuring)**:
   - Clean up filler words (e.g. "um", "like", "you know"), pauses, and banter.
   - Produce a highly structured and formalized version of the text using Markdown.
   - Take total creative liberties to optimize readability based on the content's nature:
     - If it's a talk or phone conversation with multiple speakers, format it as a beautiful chat/dialogue log (e.g., **Speaker A**: "...", **Speaker B**: "...").
     - If the content discusses budgets, numbers, timelines, pros/cons, comparisons, or tasks with attributes, output a clean Markdown table.
     - If it's a list of steps, action lists, or notes, structure them with beautiful headers, bullet points, sub-lists, and bold key terms.
     - Include horizontal rules, highlight blockquotes, and organize nicely.

### JSON Output Format:
Your response must be a single, valid JSON object with the exact following schema:
{
  "name": "string",
  "description": "string",
  "tags": ["string", "string"],
  "category": "string",
  "topicCluster": "string",
  "formatted_text": "string"
}

Do not include any other text, explanations, or backticks around JSON. Output only the pure JSON.`;

  try {
    const response = await ai.models.generateContent({
      model: 'gemini-3.5-flash',
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
            formatted_text: { type: Type.STRING }
          },
          required: ["name", "description", "tags", "category", "topicCluster", "formatted_text"]
        }
      }
    });

    const text = response.text || "{}";
    const cleanJson = text.replace(/```json/g, "").replace(/```/g, "").trim();
    return JSON.parse(cleanJson);
  } catch (err) {
    console.error("Failed to generate metadata and formatting:", err);
    return {
      name: "Untitled Session",
      description: "No description available.",
      tags: [],
      category: "Memo",
      topicCluster: "General",
      formatted_text: rawText
    };
  }
}

export async function groupSessionsContextually(
  sessionsText: string[]
): Promise<{
  name: string;
  description: string;
  tags: string[];
  category: string;
  topicCluster: string;
  formatted_text: string;
}> {
  const apiKey = process.env.API_KEY || process.env.GEMINI_API_KEY;
  if (!apiKey) {
    throw new Error("API_KEY_NOT_FOUND");
  }

  const ai = new GoogleGenAI({ apiKey });

  const prompt = `You are an expert transcription synthesizer. You have been given multiple separate transcript documents that the user wants to group together contextually into a single coherent document.

### Input Transcripts (separated by '---'):
${sessionsText.join('\n\n---\n\n')}

### Tasks:
1. Synthesize these texts into a single, unified, and elegantly formatted markdown document. Organize the information logically, removing redundancies while preserving all the key details, action items, and data points from all inputs.
2. Generate a cohesive new "name", "description", "tags", "category", and "topicCluster" summarizing the combined body of work.

### JSON Output Format:
Your response must be a single, valid JSON object with the exact following schema:
{
  "name": "string",
  "description": "string",
  "tags": ["string", "string"],
  "category": "string",
  "topicCluster": "string",
  "formatted_text": "string (the fully merged and structured markdown)"
}

Do not include any other text, explanations, or backticks around JSON. Output only the pure JSON.`;

  try {
    const response = await ai.models.generateContent({
      model: 'gemini-3.5-flash',
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
            formatted_text: { type: Type.STRING }
          },
          required: ["name", "description", "tags", "category", "topicCluster", "formatted_text"]
        }
      }
    });

    const text = response.text || "{}";
    const cleanJson = text.replace(/```json/g, "").replace(/```/g, "").trim();
    return JSON.parse(cleanJson);
  } catch (err) {
    console.error("Failed to group sessions:", err);
    throw err;
  }
}

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      const result = reader.result as string;
      const base64 = result.split(',')[1];
      resolve(base64);
    };
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}

/**
 * Smart Review Propagation Engine
 * Compares edited block versions against original versions, extracts the logical changes,
 * and feeds them to Gemini. Gemini then reviews the whole document, propagates matches,
 * and flags blocks that require semantic validation if meaning has shifted.
 */
export async function reviewAndPropagateEdits(
  blocks: Array<{ versions: string[]; activeIndex: number }>
): Promise<Array<{ blockIndex: number; newText: string; flagged: boolean; reason?: string }>> {
  const apiKey = process.env.API_KEY || process.env.GEMINI_API_KEY;
  if (!apiKey) {
    throw new Error("API_KEY_NOT_FOUND");
  }

  const ai = new GoogleGenAI({ apiKey });

  // Gather corrected/changed blocks (versions.length > 1)
  const manualChanges = blocks
    .map((b, i) => {
      if (b.versions.length > 1) {
        return {
          blockIndex: i,
          original: b.versions[0],
          corrected: b.versions[b.versions.length - 1]
        };
      }
      return null;
    })
    .filter((change): change is NonNullable<typeof change> => change !== null);

  if (manualChanges.length === 0) {
    return [];
  }

  const prompt = `You are an expert AI text review and correction propagation system.
The user has a parsed transcription document split into discrete text blocks (paragraphs, headers, or bullet elements).
The user has physically edited some blocks of text to replace misspelled words (like names, brands, acronyms) or logical slips.

Here is a list of the user's MANUAL CORRECTIONS:
${JSON.stringify(manualChanges, null, 2)}

Here is the entire active document, represented as an array of block index and active text:
${JSON.stringify(blocks.map((b, i) => ({ index: i, text: b.versions[b.activeIndex] })), null, 2)}

Your assignment:
1. Analyze the manual corrections to understand the pattern (e.g. spelling corrections, name changes, terminology swaps).
2. Scan the rest of the active document's text blocks. Automatically propagate those corrections where relevant, correcting misspellings or logical mistakes.
3. CONTEXTUAL LOGIC AUDIT: Carefully evaluate if applying a correction alters the core semantic meaning, timeline, financial figures, or logical flow of a sentence. For instance, if correcting a word changes an outcome from a positive "revenue of" to a negative "deficit of", or makes an assertion factually different based on context, you MUST flag that block!
4. Return an array of ONLY the blocks that you updated, including those you corrected automatically OR those where a semantic warning applies.
   - Set "flagged" to true if a block contains a critical shift in core meaning that the user should manually verify.
   - Provide a helpful "reason" string describing what logical shift was flagged.

Response Schema:
You must return a single JSON object in this exact schema format:
{
  "updatedBlocks": [
    {
      "blockIndex": number,
      "newText": "string (the fully corrected and polished version of the entire block text, retaining its markdown styling)",
      "flagged": boolean,
      "reason": "string (optional explanation/verification warning if flagged is true)"
    }
  ]
}

Only return pure JSON. Do not write explanations outside of JSON.`;

  try {
    const response = await ai.models.generateContent({
      model: 'gemini-3.5-flash',
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
                  reason: { type: Type.STRING }
                },
                required: ["blockIndex", "newText", "flagged"]
              }
            }
          },
          required: ["updatedBlocks"]
        }
      }
    });

    const text = response.text || "{}";
    const cleanJson = text.replace(/```json/g, "").replace(/```/g, "").trim();
    const result = JSON.parse(cleanJson);
    return result.updatedBlocks || [];
  } catch (err) {
    console.error("Error in reviewAndPropagateEdits LLM service:", err);
    throw err;
  }
}

```

