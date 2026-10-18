package `in`.cashlessconsumer.ozhungu.scan

data class ScanEntry(
    val relPath: String,
    val mime: String,
    val size: Long,
    val mtime: Long,
    val snippet: String? = null,
) {
    val isoDate: String
        get() = java.time.Instant.ofEpochMilli(mtime).toString().take(10)
}

data class ScanResult(
    val entries: List<ScanEntry>,
    val uriByPath: Map<String, String>,
    val dirPaths: Set<String>,
    val skippedCount: Int,
) {
    val knownFrom: Set<String> get() = entries.map { it.relPath }.toSet()
}
