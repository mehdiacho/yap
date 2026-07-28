import { doc, setDoc, updateDoc } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import { ref, uploadBytesResumable } from "firebase/storage";
import { db, functions, storage } from "../lib/firebase";

/*
 * Import an audio file and turn it into a note.
 *
 * Mirrors the Android path (MainViewModel.importSharedAudio -> startTranscription ->
 * TranscriptionClient.transcribe) so both clients produce identical documents:
 *
 *   1. upload to Cloud Storage at users/{uid}/audio/{name}
 *   2. write users/{uid}/sessions/{id} with status TRANSCRIBING (the note appears at once)
 *   3. call the `transcribeSession` callable with the storage path
 *   4. write the result back and flip status to DONE
 *
 * Desktop deliberately skips Android's inline-base64 fallback: it exists there for tiny
 * clips when a Storage upload fails, and silently switching transports would reintroduce
 * exactly the size ceiling that stranded a recording in the first place.
 */

export type ImportStage = "reading" | "uploading" | "transcribing" | "done" | "error";

export interface ImportProgress {
  stage: ImportStage;
  /** 0–100 during upload; undefined otherwise. */
  percent?: number;
  message?: string;
}

export interface TranscribeResponse {
  rawText?: string;
  name?: string;
  description?: string;
  tags?: string[];
  category?: string;
  topicCluster?: string;
  formattedText?: string;
}

/** Files the backend can actually transcribe. Kept permissive — the server decides. */
export const ACCEPTED_AUDIO =
  "audio/*,video/mp4,video/webm,.wav,.mp3,.m4a,.aac,.ogg,.opus,.flac,.mp4,.webm";

const MAX_IMPORT_BYTES = 500 * 1024 * 1024; // sanity bound; Storage itself allows far more

/**
 * Duration via a throwaway <audio> element. Browsers report Infinity for some streamed
 * containers, so anything non-finite becomes 0 — the note then shows no duration rather
 * than a wrong one, and the server still gets the real audio.
 */
async function readDurationMs(file: File): Promise<number> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file);
    const el = new Audio();
    const done = (ms: number) => {
      URL.revokeObjectURL(url);
      resolve(ms);
    };
    el.preload = "metadata";
    el.onloadedmetadata = () => {
      const d = el.duration;
      done(Number.isFinite(d) && d > 0 ? Math.round(d * 1000) : 0);
    };
    el.onerror = () => done(0);
    el.src = url;
  });
}

function extensionFor(file: File): string {
  const fromName = file.name.includes(".") ? file.name.split(".").pop() : "";
  if (fromName && fromName.length <= 5) return fromName.toLowerCase();
  const fromType = file.type.split("/")[1];
  return (fromType || "bin").split(";")[0].toLowerCase();
}

export async function importAudioFile(
  uid: string,
  file: File,
  onProgress: (p: ImportProgress) => void,
): Promise<string> {
  if (file.size === 0) throw new Error("That file is empty.");
  if (file.size > MAX_IMPORT_BYTES) {
    throw new Error(`That file is larger than ${MAX_IMPORT_BYTES / (1024 * 1024)} MB.`);
  }

  onProgress({ stage: "reading" });
  const durationMs = await readDurationMs(file);

  const sessionId = crypto.randomUUID();
  // Named like Android's share imports so both clients' audio sorts together in Storage.
  const storageName = `import_${Date.now()}.${extensionFor(file)}`;
  const storagePath = `users/${uid}/audio/${storageName}`;
  const mimeType = file.type || "audio/wav";

  // The note exists before transcription starts, so a failure mid-flight leaves a visible
  // FAILED note rather than an invisible orphan — the lesson from the Android bug.
  const sessionRef = doc(db, "users", uid, "sessions", sessionId);
  await setDoc(sessionRef, {
    title: "Transcribing…",
    draftText: "",
    formattedText: null,
    audioPath: null,
    storagePath,
    status: "TRANSCRIBING",
    createdAt: Date.now(),
    durationMs,
    category: "Memo",
    topicCluster: "General",
    trashedAt: null,
    pinned: false,
    bookmarks: [],
    extraRecordings: [],
  });

  try {
    onProgress({ stage: "uploading", percent: 0 });
    await new Promise<void>((resolve, reject) => {
      const task = uploadBytesResumable(ref(storage, storagePath), file, {
        contentType: mimeType,
      });
      task.on(
        "state_changed",
        (snap) =>
          onProgress({
            stage: "uploading",
            percent: Math.round((snap.bytesTransferred / snap.totalBytes) * 100),
          }),
        reject,
        () => resolve(),
      );
    });

    onProgress({ stage: "transcribing" });
    const call = httpsCallable<
      { storagePath: string; mimeType: string; durationMs: number },
      TranscribeResponse
    >(functions, "transcribeSession");
    const { data } = await call({ storagePath, mimeType, durationMs });

    await updateDoc(sessionRef, {
      title: data?.name?.trim() || file.name.replace(/\.[^.]+$/, "") || "Recording",
      draftText: data?.rawText ?? "",
      formattedText: data?.formattedText ?? null,
      category: data?.category?.trim() || "Memo",
      topicCluster: data?.topicCluster?.trim() || "General",
      status: "DONE",
    });

    onProgress({ stage: "done" });
    return sessionId;
  } catch (err) {
    // Quota is a distinct, non-error outcome the phone surfaces as its own status.
    const code = (err as { code?: string })?.code ?? "";
    const quota = code.includes("resource-exhausted");
    await updateDoc(sessionRef, {
      title: quota ? "Monthly limit reached" : file.name.replace(/\.[^.]+$/, "") || "Recording",
      status: quota ? "QUOTA" : "FAILED",
    }).catch(() => {
      /* the note already exists with TRANSCRIBING; leave it rather than masking the error */
    });

    const message = quota
      ? "You've hit this month's cloud limit — the file is saved and can be polished later."
      : (err as Error)?.message || "Transcription failed.";
    onProgress({ stage: "error", message });
    throw new Error(message);
  }
}
