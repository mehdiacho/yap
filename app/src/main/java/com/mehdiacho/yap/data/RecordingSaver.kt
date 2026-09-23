package com.mehdiacho.yap.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Save a note's recordings where the rest of the phone can see them: Download/Yap.
 *
 * The app's own copy lives in private storage, and until this existed the only way to get
 * the audio out was a data export of everything. Wanting one recording back — to re-run it,
 * to send it to someone, to keep it outside the app — is ordinary, and the note is where
 * people look for it.
 *
 * Every clip is saved: the primary recording and anything added with "Add a recording".
 * Each comes from the local file when this device has it, otherwise from the cloud copy
 * (downloaded into [AudioCache], so playing it afterwards costs nothing).
 */
object RecordingSaver {

    /** What happened, for the one-line notice. */
    data class Outcome(val saved: Int, val missing: Int, val folder: String)

    suspend fun save(context: Context, session: Session): Outcome {
        val clips = buildList {
            add(Clip(session.audioPath, session.storagePath))
            session.extraRecordings.forEach { add(Clip(it.audioPath, it.storagePath)) }
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(session.createdAt))
        val base = "Yap $stamp ${safeName(session.title)}".trim()
        var saved = 0
        var missing = 0
        clips.forEachIndexed { i, clip ->
            val source = sourceFile(context, clip)
            if (source == null) { missing++; return@forEachIndexed }
            // Shared audio keeps its own format; only in-app recordings are WAV.
            val ext = source.extension.lowercase().ifBlank { "wav" }
            val name = if (i == 0) "$base.$ext" else "$base (${i + 1}).$ext"
            writeToDownloads(context, source, name, mimeFor(ext))
            saved++
        }
        return Outcome(saved, missing, FOLDER_LABEL)
    }

    private data class Clip(val localPath: String?, val storagePath: String?)

    private suspend fun sourceFile(context: Context, clip: Clip): File? {
        clip.localPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }?.let { return it }
        // No pointer on the note doesn't mean no cloud copy: upload paths are deterministic,
        // so look for it by the local file's name. Same reasoning as transcribeExistingNote.
        val cloud = clip.storagePath ?: clip.localPath?.substringAfterLast('/')
            ?.let { runCatching { TranscriptionClient.findUploadedAudio(it) }.getOrNull() }
            ?: return null
        return runCatching { AudioCache.download(context, cloud) }.getOrNull()
    }

    private fun mimeFor(ext: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/*"

    private fun writeToDownloads(context: Context, source: File, name: String, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Yap")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create the file in Downloads.")
            try {
                resolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            // Before Android 10 the shared Downloads folder needs a storage permission this
            // app doesn't ask for. The app's own external folder needs none and is still
            // reachable from a file manager (Android/data/<package>/files/Download).
            val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: error("The phone's storage isn't available right now.")
            val dir = File(root, "Yap").apply { mkdirs() }
            source.copyTo(File(dir, name), overwrite = true)
        }
    }

    private val FOLDER_LABEL =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Downloads/Yap" else "the app's Download folder"

    /** A title as a file name: no path separators or characters file systems refuse. */
    private fun safeName(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() && it != "Transcribing…" }
            ?.take(60)
            ?: ""
}
