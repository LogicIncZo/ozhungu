package `in`.cashlessconsumer.ozhungu.scan

import `in`.cashlessconsumer.ozhungu.saf.SafFs
import android.net.Uri
import android.webkit.MimeTypeMap

/** Walks the picked SAF tree once and produces the listing Zo plans over. */
object Scanner {

    const val MAX_FILES = 600
    const val MAX_DEPTH = 4
    const val SNIPPET_CHARS = 1200

    private val SKIP_DIRS = setOf("Android", "LOST.DIR", ".Trash", ".Trashed")

    private val TEXT_EXT = setOf(
        "txt", "md", "csv", "json", "log", "xml", "html", "htm", "yaml", "yml", "ini", "conf",
    )

    /** Returns [ScanResult]; [onProgress] fires on the calling thread every ~25 files. */
    fun scan(treeUri: Uri, onProgress: (Int) -> Unit = {}): ScanResult {
        val saf = SafFs(treeUri)
        val entries = ArrayList<ScanEntry>()
        val uriByPath = HashMap<String, String>()
        val dirPaths = LinkedHashSet<String>()
        var skipped = 0

        data class Job(val docId: String, val rel: String, val depth: Int)

        val queue = ArrayDeque<Job>()
        queue.add(Job(saf.rootDocId(), "", 0))
        while (queue.isNotEmpty() && entries.size < MAX_FILES) {
            val job = queue.removeFirst()
            val kids = try {
                saf.children(job.docId)
            } catch (t: Throwable) {
                skipped++
                continue
            }
            for (d in kids) {
                val rel = if (job.rel.isEmpty()) d.name else "${job.rel}/${d.name}"
                if (d.isDir) {
                    dirPaths.add(rel)
                    if (d.name in SKIP_DIRS || d.name.startsWith(".")) continue
                    if (job.depth < MAX_DEPTH) queue.add(Job(saf.docIdOf(d.uri), rel, job.depth + 1))
                } else {
                    if (entries.size >= MAX_FILES) {
                        skipped++
                        continue
                    }
                    val mime = d.mime.ifBlank { guessMime(d.name) }
                    entries.add(ScanEntry(rel, mime, d.size, d.mtime, snippet(saf, d, mime)))
                    uriByPath[rel] = d.uri.toString()
                    if (entries.size % 25 == 0) onProgress(entries.size)
                }
            }
        }
        return ScanResult(entries, uriByPath, dirPaths, skipped)
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** First bytes of text-ish files, control chars stripped, for content-based naming. */
    private fun snippet(saf: SafFs, d: SafFs.Doc, mime: String): String? {
        val textish = mime.startsWith("text/") ||
            mime.contains("json") || mime.contains("xml") ||
            d.name.substringAfterLast('.', "").lowercase() in TEXT_EXT
        if (!textish || d.size <= 0 || d.size > 1_000_000) return null
        val head = saf.readTextHead(d.uri, SNIPPET_CHARS) ?: return null
        return head.filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(SNIPPET_CHARS)
            .ifEmpty { null }
    }
}
