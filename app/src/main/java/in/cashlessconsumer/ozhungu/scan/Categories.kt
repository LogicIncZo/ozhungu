package `in`.cashlessconsumer.ozhungu.scan

/** Files-by-Google-style category buckets over a scan. Pure, unit-tested. */
object Categories {

    enum class Cat(val label: String, val emoji: String, val color: Long) {
        IMAGES("Images", "🖼", 0x1A73E8),
        VIDEOS("Videos", "🎬", 0xD93025),
        AUDIO("Audio", "🎵", 0xF9AB00),
        DOCUMENTS("Documents", "📄", 0x188038),
        ARCHIVES("Archives", "🗜", 0x9334E6),
        APPS("Apps", "🤖", 0x12B5CB),
        OTHER("Other", "📁", 0x5F6368),
    }

    private val EXT: Map<String, Cat> = buildMap {
        fun put(cat: Cat, vararg exts: String) = exts.forEach { put(it, cat) }
        put(Cat.IMAGES, "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
        put(Cat.VIDEOS, "mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v")
        put(Cat.AUDIO, "mp3", "m4a", "wav", "ogg", "flac", "opus", "amr", "aac", "mid")
        put(
            Cat.DOCUMENTS, "pdf", "doc", "docx", "odt", "rtf", "txt", "md", "csv",
            "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub",
        )
        put(Cat.ARCHIVES, "zip", "rar", "7z", "tar", "gz", "bz2", "xz")
        put(Cat.APPS, "apk")
    }

    fun categorize(relPath: String, mime: String = ""): Cat {
        val ext = relPath.substringAfterLast('.', "").lowercase()
        return EXT[ext]
            ?: when {
                mime.startsWith("image/") -> Cat.IMAGES
                mime.startsWith("video/") -> Cat.VIDEOS
                mime.startsWith("audio/") -> Cat.AUDIO
                else -> Cat.OTHER
            }
    }

    data class Summary(val cat: Cat, val count: Int, val bytes: Long)

    /** Non-empty categories, biggest first. */
    fun summarize(scan: ScanResult): List<Summary> {
        val acc = HashMap<Cat, IntArray>() // [count, bytes-lo, bytes-hi] not worth it; use two maps
        val counts = HashMap<Cat, Int>()
        val bytes = HashMap<Cat, Long>()
        for (e in scan.entries) {
            val c = categorize(e.relPath, e.mime)
            counts[c] = (counts[c] ?: 0) + 1
            bytes[c] = (bytes[c] ?: 0L) + e.size
        }
        return counts.entries
            .map { Summary(it.key, it.value, bytes[it.key] ?: 0L) }
            .sortedWith(compareByDescending<Summary> { it.count }.thenByDescending { it.bytes })
    }

    fun entriesOf(scan: ScanResult, cat: Cat): List<ScanEntry> =
        scan.entries.filter { categorize(it.relPath, it.mime) == cat }

    /** Newest-first slice for the "Recent" list. */
    fun recent(scan: ScanResult, n: Int): List<ScanEntry> =
        scan.entries.sortedByDescending { it.mtime }.take(n)

    /** Copy of the scan restricted to [paths] — scope for a selection-driven plan. */
    fun restrict(scan: ScanResult, paths: Set<String>): ScanResult {
        if (paths.isEmpty()) return scan
        val keep = scan.entries.filter { it.relPath in paths }
        return scan.copy(
            entries = keep,
            uriByPath = scan.uriByPath.filterKeys { it in paths },
            skippedCount = 0,
        )
    }
}
