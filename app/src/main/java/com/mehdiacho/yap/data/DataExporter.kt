package com.mehdiacho.yap.data

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Export my data" (Privacy & data): bundles every session as JSON plus any audio available
 * on this device (original recordings + cached downloads) into a zip, then hands back a
 * share intent via FileProvider. Cloud-only audio that was never downloaded is listed in
 * the JSON (storagePath) but not fetched — export shouldn't silently burn mobile data.
 */
object DataExporter {

    suspend fun buildExportZip(context: Context): File = withContext(Dispatchers.IO) {
        val sessions = FirestoreSessionRepository().allSessions().first()

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zipFile = File(exportDir, "yap-export.zip")
        if (zipFile.exists()) zipFile.delete()

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            // notes.json — full session metadata + text.
            val arr = JSONArray()
            sessions.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("draftText", s.draftText)
                    put("formattedText", s.formattedText ?: JSONObject.NULL)
                    put("status", s.status)
                    put("category", s.category)
                    put("topicCluster", s.topicCluster)
                    put("createdAt", s.createdAt)
                    put("durationMs", s.durationMs)
                    put("storagePath", s.storagePath ?: JSONObject.NULL)
                    put("audioIncluded", localAudioFor(context, s) != null)
                })
            }
            zip.putNextEntry(ZipEntry("notes.json"))
            zip.write(arr.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // audio/<id>.<ext> — whatever is available on this device.
            sessions.forEach { s ->
                val src = localAudioFor(context, s) ?: return@forEach
                val ext = src.name.substringAfterLast('.', "wav")
                zip.putNextEntry(ZipEntry("audio/${s.id}.$ext"))
                FileInputStream(src).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        zipFile
    }

    /** Original recording if it still exists locally, else the cached cloud download. */
    private fun localAudioFor(context: Context, s: Session): File? {
        s.audioPath?.let { p -> File(p).takeIf { it.exists() }?.let { return it } }
        if (s.storagePath != null && AudioCache.isCached(context, s.storagePath)) {
            return AudioCache.fileFor(context, s.storagePath).takeIf { it.exists() }
        }
        return null
    }

    /** Share intent for the zip (FileProvider-backed). */
    fun shareIntent(context: Context, zip: File): Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Yap data export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
