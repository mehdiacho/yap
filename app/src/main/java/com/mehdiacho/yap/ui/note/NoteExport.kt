package com.mehdiacho.yap.ui.note

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Getting a note back out.
 *
 * The note view no longer renders Markdown, it renders the app's own layout — so the Markdown
 * has to be somewhere the user can reach on purpose. It stays the source of truth on disk;
 * this is the door to it.
 *
 * Two shapes, because they answer different questions. **Plain text** is what you want when
 * you're pasting into a message and syntax characters would be noise. **Markdown** is what you
 * want when the structure has to survive — into an editor, a repo, another notes app.
 */
object NoteExport {

    /** The rendered note as text: no `##`, no `**`, bullets as bullets. */
    fun asPlainText(markdown: String): String = parseNote(markdown).toPlainText()

    /**
     * Writes `<title>.md` and hands back a share intent for it.
     *
     * A file rather than a text/plain extra with the Markdown in it: a receiving app decides
     * what to do from the MIME type and the extension, and pasted-in Markdown arrives as a
     * paragraph of literal asterisks in every one of them.
     */
    fun shareMarkdown(context: Context, title: String, markdown: String): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${safeFileName(title)}.md")
        // The title isn't in the body — it lives on the note, and the formalize prompt is told
        // not to emit a title heading — so a standalone file needs one or it opens headless.
        file.writeText("# $title\n\n${markdown.trim()}\n")

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * A filename that survives every filesystem an export might land on.
     *
     * Note titles are model-generated from speech, so they routinely contain `:` and `/` and
     * run long. Windows and Android both reject some of those outright, and a share that fails
     * silently at the write is worse than a truncated name.
     */
    private fun safeFileName(title: String): String {
        val cleaned = title.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(60)
            .trimEnd('.', ' ')
        return cleaned.ifBlank { "Note" }
    }
}
