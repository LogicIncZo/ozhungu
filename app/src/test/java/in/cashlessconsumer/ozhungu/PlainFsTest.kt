package `in`.cashlessconsumer.ozhungu

import `in`.cashlessconsumer.ozhungu.saf.PlainFs
import `in`.cashlessconsumer.ozhungu.scan.Scanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlainFsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fs(): PlainFs = PlainFs(tmp.root, "Download")

    private fun touch(rel: String, content: String = "hello world"): File {
        val f = File(tmp.root, rel)
        f.parentFile.mkdirs()
        f.writeText(content)
        return f
    }

    @Test
    fun `list returns dirs first with entries and handles`() {
        touch("b.txt")
        touch("a.txt")
        File(tmp.root, "Zdir").mkdirs()
        val kids = fs().list(fs().rootHandle())
        assertEquals(listOf("Zdir", "a.txt", "b.txt"), kids.map { it.name })
        assertTrue(kids[0].isDir)
        assertFalse(kids[1].isDir)
        assertEquals(tmp.root.absolutePath + "/a.txt", kids[1].handle)
        assertEquals(11L, kids[1].size)
    }

    @Test
    fun `readTextHead caps and reads`() {
        touch("big.txt", "x".repeat(500) + "END")
        assertEquals(500, fs().readTextHead(File(tmp.root, "big.txt").absolutePath, 500)!!.length)
        assertNull(fs().readTextHead(File(tmp.root, "missing.txt").absolutePath, 10))
    }

    @Test
    fun `ensureDir resolves existing and creates missing`() {
        File(tmp.root, "exists").mkdirs()
        val fs = fs()
        assertEquals(tmp.root.absolutePath, fs.ensureDir("", create = false))
        assertNotNull(fs.ensureDir("exists", create = false))
        assertNull(fs.ensureDir("nope/deep", create = false))
        val made = fs.ensureDir("nope/deep", create = true)
        assertEquals(tmp.root.absolutePath + "/nope/deep", made)
        assertTrue(File(tmp.root, "nope/deep").isDirectory)
    }

    @Test
    fun `ensureDir refuses when a file occupies the path`() {
        touch("blocker")
        assertNull(fs().ensureDir("blocker/child", create = true))
    }

    @Test
    fun `move renames and refuses overwrites`() {
        val fs = fs()
        val f = touch("old/a.txt")
        val dst = fs.ensureDir("new", create = true)!!
        val moved = fs.move(f.absolutePath, dst)
        assertEquals(tmp.root.absolutePath + "/new/a.txt", moved)
        assertFalse(f.exists())
        assertNull(fs.move(moved!!, dst)) // dst now exists → refused
    }

    @Test
    fun `rename within folder`() {
        val fs = fs()
        val f = touch("a.txt")
        val renamed = fs.rename(f.absolutePath, "b.txt")
        assertEquals(tmp.root.absolutePath + "/b.txt", renamed)
        assertTrue(File(tmp.root, "b.txt").isFile)
        assertFalse(f.exists())
        assertEquals("b.txt", fs.nameOf(renamed!!))
    }

    @Test
    fun `scanner walks plain fs with skips`() {
        touch("Download/keep.pdf")
        touch("Download/.hidden/secret.txt")
        touch("Download/Android/data/com.app/cache.bin")
        touch("Download/Android/media/com.whatsapp/WhatsApp/Media/img.jpg")
        touch("Download/LOST.DIR/junk")
        val result = Scanner.scan(PlainFs(File(tmp.root, "Download"), "Download"))
        assertEquals(listOf("keep.pdf", "Android/media/com.whatsapp/WhatsApp/Media/img.jpg"), result.entries.map { it.relPath })
        assertTrue(result.dirPaths.none { it.startsWith("Android/data") })
        assertTrue(Scanner.skipsDir("Android/data"))
        assertTrue(Scanner.skipsDir("Android/data/x"))
        assertTrue(Scanner.skipsDir(".thumb"))
        assertFalse(Scanner.skipsDir("Android/media"))
        assertFalse(Scanner.skipsDir("Download"))
    }

    @Test
    fun `executor moves files over plain fs end to end`() {
        val fs = fs()
        touch("bill.pdf")
        val result = Scanner.scan(fs)
        val plan = `in`.cashlessconsumer.ozhungu.plan.Plan(
            folders = listOf("Bills"),
            actions = listOf(
                `in`.cashlessconsumer.ozhungu.plan.PlanAction("move", "bill.pdf", "Bills/bill.pdf", "test"),
            ),
        )
        val report = `in`.cashlessconsumer.ozhungu.exec.Executor(fs).apply(plan, result)
        assertEquals(1, report.applied.size)
        assertTrue(File(tmp.root, "Bills/bill.pdf").isFile)
        assertFalse(File(tmp.root, "bill.pdf").exists())
        val (undone, failed) = `in`.cashlessconsumer.ozhungu.exec.Executor(fs).undo(report.applied)
        assertEquals(1, undone)
        assertTrue(failed.isEmpty())
        assertTrue(File(tmp.root, "bill.pdf").isFile)
    }
}
