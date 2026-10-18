package `in`.cashlessconsumer.ozhungu

import `in`.cashlessconsumer.ozhungu.scan.Categories
import `in`.cashlessconsumer.ozhungu.scan.ScanEntry
import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {

    private fun e(path: String, size: Long = 10, mime: String = "", mtime: Long = 0) =
        ScanEntry(path, mime, size, mtime)

    private fun scan(vararg entries: ScanEntry) = ScanResult(
        entries = entries.toList(),
        uriByPath = entries.associate { it.relPath to "content://t/${it.relPath}" },
        dirPaths = setOf("notes"),
        skippedCount = 0,
    )

    @Test
    fun `extension wins over mime`() {
        assertEquals(Categories.Cat.ARCHIVES, Categories.categorize("a.zip", "application/zip"))
        assertEquals(Categories.Cat.IMAGES, Categories.categorize("b.jpg", ""))
        // unknown ext with image mime falls back to mime
        assertEquals(Categories.Cat.IMAGES, Categories.categorize("c.raw", "image/raw"))
    }

    @Test
    fun `unknown ext without mime is other`() {
        assertEquals(Categories.Cat.OTHER, Categories.categorize("d.xyz"))
        assertEquals(Categories.Cat.OTHER, Categories.categorize("noext"))
        // uppercase extensions normalize
        assertEquals(Categories.Cat.DOCUMENTS, Categories.categorize("E.PDF"))
    }

    @Test
    fun `summarize counts and sorts by count desc`() {
        val s = scan(
            e("a.jpg", 100), e("b.jpg", 50), e("c.png", 10),
            e("d.pdf", 500),
            e("e.zip", 5),
        )
        val sums = Categories.summarize(s)
        assertEquals(listOf("Images", "Documents", "Archives"), sums.map { it.cat.label })
        assertEquals(3, sums[0].count)
        assertEquals(160L, sums[0].bytes)
        assertEquals(500L, sums[1].bytes)
    }

    @Test
    fun `entriesOf filters by category`() {
        val s = scan(e("a.jpg"), e("b.mp3"), e("c.jpg"))
        assertEquals(listOf("a.jpg", "c.jpg"), Categories.entriesOf(s, Categories.Cat.IMAGES).map { it.relPath })
        assertTrue(Categories.entriesOf(s, Categories.Cat.APPS).isEmpty())
    }

    @Test
    fun `recent is newest first capped at n`() {
        val s = scan(e("old.txt", mtime = 1), e("new.txt", mtime = 9), e("mid.txt", mtime = 5))
        assertEquals(listOf("new.txt", "mid.txt"), Categories.recent(s, 2).map { it.relPath })
        assertEquals(listOf("new.txt", "mid.txt", "old.txt"), Categories.recent(s, 10).map { it.relPath })
    }

    @Test
    fun `restrict returns same scan for empty selection and copy otherwise`() {
        val s = scan(e("a.jpg", 1), e("b.mp3", 2), e("c.pdf", 3))
        assertSame(s, Categories.restrict(s, emptySet()))
        val r = Categories.restrict(s, setOf("a.jpg", "c.pdf"))
        assertNotSame(s, r)
        assertEquals(listOf("a.jpg", "c.pdf"), r.entries.map { it.relPath })
        assertEquals(setOf("a.jpg", "c.pdf"), r.uriByPath.keys)
        assertEquals(0, r.skippedCount)
        // untouched fields survive the copy
        assertEquals(setOf("notes"), r.dirPaths)
    }
}
