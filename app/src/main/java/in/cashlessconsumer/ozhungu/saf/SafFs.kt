package `in`.cashlessconsumer.ozhungu.saf

import `in`.cashlessconsumer.ozhungu.AppResolver
import android.net.Uri
import android.provider.DocumentsContract

/** Thin SAF wrapper for one persisted tree. All relative paths are '/'-separated. */
class SafFs(val treeUri: Uri) {

    data class Doc(
        val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long,
        val mtime: Long,
        val isDir: Boolean,
    )

    private val folderCache = HashMap<String, Uri>()

    fun rootDocId(): String = DocumentsContract.getTreeDocumentId(treeUri)

    fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    fun docIdOf(docUri: Uri): String = DocumentsContract.getDocumentId(docUri)

    fun rootDocUri(): Uri = docUri(rootDocId())

    private fun childUri(parentDocId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

    /** Lists children of a document (root when null). */
    fun children(parentDocId: String? = null): List<Doc> {
        val resolver = AppResolver.query
        val uri = childUri(parentDocId ?: rootDocId())
        val out = ArrayList<Doc>()
        resolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val mime = c.getString(2) ?: ""
                out.add(
                    Doc(
                        uri = docUri(id),
                        name = c.getString(1) ?: id.substringAfterLast(':'),
                        mime = mime,
                        size = if (c.isNull(3)) 0L else c.getLong(3),
                        mtime = if (c.isNull(4)) 0L else c.getLong(4),
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    ),
                )
            }
        }
        return out
    }

    /** First bytes of a document as UTF-8 text, capped at [maxBytes]. */
    fun readTextHead(docUri: Uri, maxBytes: Int = 4096): String? = runCatching {
        AppResolver.query.openInputStream(docUri)?.use { ins ->
            val buf = ByteArray(maxBytes)
            val n = ins.read(buf)
            if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8)
        }
    }.getOrNull()

    /**
     * Resolves (and optionally creates) the folder at [relDir], returning its document URI.
     * Caches known folders for the session.
     */
    fun ensurePath(relDir: String, create: Boolean): Uri? {
        if (relDir.isEmpty()) return rootDocUri()
        val cached = folderCache[relDir]
        if (cached != null) return cached
        val parentRel = relDir.substringBeforeLast('/', missingDelimiterValue = "")
        val parentUri = ensurePath(parentRel, create) ?: return null
        val name = relDir.substringAfterLast('/')
        val parentDocId = DocumentsContract.getDocumentId(parentUri)
        val existing = runCatching { children(parentDocId) }.getOrDefault(emptyList())
            .firstOrNull { it.isDir && it.name == name }
        val result = existing?.uri ?: (if (!create) null else runCatching {
            DocumentsContract.createDocument(
                AppResolver.query, parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR, name,
            )
        }.getOrNull()) ?: return null
        folderCache[relDir] = result
        return result
    }

    /** Moves [srcDocUri] into [dstParentUri]; returns the new document URI. */
    fun move(srcDocUri: Uri, dstParentUri: Uri): Uri? =
        DocumentsContract.moveDocument(AppResolver.query, srcDocUri, parentOf(srcDocUri), dstParentUri)

    fun rename(docUri: Uri, newName: String): Uri? =
        DocumentsContract.renameDocument(AppResolver.query, docUri, newName)

    /** Best-effort parent doc uri for a document inside this tree. */
    fun parentOf(docUri: Uri): Uri {
        val docId = DocumentsContract.getDocumentId(docUri)
        val parentId = docId.substringBeforeLast('/', missingDelimiterValue = docId)
        return docUri(parentId)
    }

    /** Folder that contains [rel] (a file path); root when [rel] has no '/'-parent. */
    fun parentOfRel(rel: String, create: Boolean = false): Uri? =
        ensurePath(rel.substringBeforeLast('/', missingDelimiterValue = ""), create)

    fun nameOf(docUri: Uri): String? = displayNameOf(docUri)

    companion object {
        /** Display name of the tree root ("Downloads", "Internal storage", ...). */
        fun displayName(treeUri: Uri): String? = displayNameOf(
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            ),
        )

        fun displayNameOf(docUri: Uri): String? = try {
            AppResolver.query.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (t: Throwable) {
            null
        }
    }
}
