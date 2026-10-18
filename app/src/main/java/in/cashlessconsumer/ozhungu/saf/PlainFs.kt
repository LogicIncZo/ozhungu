package `in`.cashlessconsumer.ozhungu.saf

import java.io.File

/**
 * java.io.File backend used with All-files-access (MANAGE_EXTERNAL_STORAGE).
 * Reaches folders SAF refuses to grant (the Downloads root, storage-volume
 * roots) and walks Android/media without per-document provider round-trips.
 * Pure java.io/kotlin so the JVM test suite exercises it directly.
 */
class PlainFs(private val root: File, override val rootLabel: String) : Fs {

    init { require(root.isDirectory) { "not a directory: $root" } }

    private fun fileFor(relDir: String, name: String): File {
        val base = if (relDir.isEmpty()) root else File(root, relDir)
        return File(base, name)
    }

    override fun rootHandle(): String = root.absolutePath

    override fun list(dirHandle: String): List<Fs.Entry> {
        val dir = if (dirHandle == rootHandle()) root else File(dirHandle)
        val kids = dir.listFiles() ?: return emptyList()
        return kids.map { f ->
            Fs.Entry(
                name = f.name,
                mime = if (f.isDirectory) "vnd.android.document/directory" else "",
                size = if (f.isFile) f.length() else 0L,
                mtime = f.lastModified(),
                isDir = f.isDirectory,
                handle = f.absolutePath,
            )
        }.sortedWith(compareByDescending<Fs.Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override fun readTextHead(handle: String, maxBytes: Int): String? = runCatching {
        val f = File(handle)
        if (!f.isFile) return@runCatching null
        f.inputStream().use { ins ->
            val buf = ByteArray(maxBytes)
            val n = ins.read(buf)
            if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8)
        }
    }.getOrNull()

    override fun ensureDir(relDir: String, create: Boolean): String? {
        if (relDir.isEmpty()) return rootHandle()
        var cur = root
        for (seg in relDir.split('/').filter { it.isNotBlank() }) {
            val next = File(cur, seg)
            if (next.isDirectory) { cur = next; continue }
            if (next.isFile) return null
            if (!create) return null
            if (!next.mkdir() && !next.isDirectory) return null
            cur = next
        }
        return cur.absolutePath
    }

    override fun move(handle: String, dstDirHandle: String): String? = runCatching {
        val src = File(handle)
        val dstDir = if (dstDirHandle == rootHandle()) root else File(dstDirHandle)
        val dst = File(dstDir, src.name)
        if (dst.exists()) return@runCatching null
        if (src.renameTo(dst) && dst.isFile) return@runCatching dst.absolutePath
        // Cross-volume fallback: copy + delete.
        if (!src.isFile) return@runCatching null
        src.inputStream().use { ins -> dst.outputStream().use { ins.copyTo(it) } }
        if (dst.length() != src.length()) { dst.delete(); return@runCatching null }
        src.delete()
        dst.absolutePath
    }.getOrNull()

    override fun rename(handle: String, newName: String): String? = runCatching {
        val src = File(handle)
        val dst = File(src.parentFile, newName)
        if (dst.exists()) return@runCatching null
        if (!src.renameTo(dst)) return@runCatching null
        dst.absolutePath
    }.getOrNull()

    override fun nameOf(handle: String): String = File(handle).name
}
