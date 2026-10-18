package `in`.cashlessconsumer.ozhungu.scan

import `in`.cashlessconsumer.ozhungu.saf.Fs

/** Walks the granted root once (SAF tree or plain folder) and produces the listing Zo plans over. */
object Scanner {

    const val MAX_FILES = 600
    const val MAX_DEPTH = 6
    const val SNIPPET_CHARS = 1200
    private const val SNIPPET_MAX_FILE = 1_000_000

    private val SKIP_DIR_NAMES = setOf("LOST.DIR", ".Trash", ".Trashed")
    private val SKIP_SUBTREES = setOf("Android/data", "Android/obb")

    private val TEXT_EXT = setOf(
        "txt", "md", "csv", "json", "log", "xml", "html", "htm", "yaml", "yml", "ini", "conf",
    )

    /** True when [rel] names a directory we never walk (dot-dirs, sandboxed app data, trash). */
    fun skipsDir(rel: String): Boolean =
        rel.substringAfterLast('/') in SKIP_DIR_NAMES ||
            rel.substringAfterLast('/').startsWith(".") ||
            rel in SKIP_SUBTREES ||
            SKIP_SUBTREES.any { rel.startsWith("$it/") }

    /** Returns [ScanResult]; [onProgress] fires on the calling thread every ~25 files. */
    fun scan(fs: Fs, onProgress: (Int) -> Unit = {}): ScanResult {
        val entries = ArrayList<ScanEntry>()
        val uriByPath = HashMap<String, String>()
        val dirPaths = LinkedHashSet<String>()
        var skipped = 0

        data class Job(val dirHandle: String, val rel: String, val depth: Int)

        val queue = ArrayDeque<Job>()
        queue.add(Job(fs.rootHandle(), "", 0))

        while (queue.isNotEmpty() && entries.size < MAX_FILES) {
            val job = queue.removeFirst()
            val kids = runCatching { fs.list(job.dirHandle) }.getOrDefault(emptyList())
            for (d in kids) {
                val rel = if (job.rel.isEmpty()) d.name else "${job.rel}/${d.name}"
                if (d.isDir) {
                    if (skipsDir(rel)) continue
                    dirPaths.add(rel)
                    if (job.depth + 1 < MAX_DEPTH) queue.add(Job(d.handle, rel, job.depth + 1))
                    continue
                }
                if (rel in uriByPath) continue
                if (entries.size >= MAX_FILES) { skipped++; continue }
                val mime = d.mime.ifBlank { guessMime(d.name) }
                uriByPath[rel] = d.handle
                entries.add(
                    ScanEntry(
                        relPath = rel,
                        mime = mime,
                        size = d.size,
                        mtime = d.mtime,
                        snippet = snippetOf(fs, d, mime),
                    ),
                )
                if (entries.size % 25 == 0) onProgress(entries.size)
            }
        }
        onProgress(entries.size)
        return ScanResult(entries, uriByPath, dirPaths, skipped)
    }

    private fun snippetOf(fs: Fs, d: Fs.Entry, mime: String): String? {
        val textish = mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ||
            d.name.substringAfterLast('.', "").lowercase() in TEXT_EXT
        if (!textish || d.size <= 0 || d.size > SNIPPET_MAX_FILE) return null
        val head = runCatching { fs.readTextHead(d.handle, SNIPPET_CHARS) }.getOrNull() ?: return null
        return head.filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(SNIPPET_CHARS)
            .ifEmpty { null }
    }

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MIME_BY_EXT[ext] ?: "application/octet-stream"
    }

    private val MIME_BY_EXT = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "svg" to "image/svg+xml", "bmp" to "image/bmp",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "mov" to "video/quicktime",
        "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "opus" to "audio/opus", "wav" to "audio/x-wav",
        "m4a" to "audio/mp4", "flac" to "audio/flac", "amr" to "audio/amr",
        "pdf" to "application/pdf",
        "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar", "gz" to "application/gzip", "apk" to "application/vnd.android.package-archive",
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "html" to "text/html",
        "htm" to "text/html", "xml" to "application/xml", "json" to "application/json",
        "yaml" to "application/yaml", "yml" to "application/yaml", "log" to "text/plain",
        "doc" to "application/msword", "xls" to "application/vnd.ms-excel",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ics" to "text/calendar", "vcf" to "text/vcard", "srt" to "application/x-subrip",
    )
}
