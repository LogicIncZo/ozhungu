package `in`.cashlessconsumer.ozhungu.saf

/**
 * Storage backend for one granted root: a SAF tree or a plain folder
 * (All-files-access mode). All [Fs.Entry.handle] / [path] values are opaque
 * strings — document URIs for SAF, absolute file paths for plain.
 */
interface Fs {
    /** Human label of the granted root ("Downloads", "Internal storage", ...). */
    val rootLabel: String

    /** Handle of the root directory. */
    fun rootHandle(): String

    /** Children of the directory at [dirHandle]. Best effort — failures yield empty. */
    fun list(dirHandle: String): List<Entry>

    /** First bytes of the file at [handle] as UTF-8 text, capped at [maxBytes]. */
    fun readTextHead(handle: String, maxBytes: Int): String?

    /**
     * Resolves (and optionally creates) the folder at relative [relDir],
     * returning its handle; null when missing/uncreatable.
     */
    fun ensureDir(relDir: String, create: Boolean): String?

    /** Moves the file at [handle] into the directory [dstDirHandle]; returns the new handle. */
    fun move(handle: String, dstDirHandle: String): String?

    /** Renames the item at [handle] to [newName]; returns the new handle. */
    fun rename(handle: String, newName: String): String?

    /** Display name of the item at [handle]. */
    fun nameOf(handle: String): String?

    data class Entry(
        val name: String,
        val mime: String,
        val size: Long,
        val mtime: Long,
        val isDir: Boolean,
        val handle: String,
    )
}
