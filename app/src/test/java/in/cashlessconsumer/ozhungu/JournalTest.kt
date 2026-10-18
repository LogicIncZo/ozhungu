package `in`.cashlessconsumer.ozhungu

import `in`.cashlessconsumer.ozhungu.exec.JournalCodec
import `in`.cashlessconsumer.ozhungu.exec.JournalEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalTest {

    @Test
    fun `encode decode roundtrip`() {
        val entries = listOf(
            JournalEntry("move", "a/bill.pdf", "Bills/2026/bill.pdf", "content://x/1", 1_700_000_000_000),
            JournalEntry("rename", "IMG_1.jpg", "receipt.jpg", "content://x/2", 1_700_000_001_000),
        )
        val back = JournalCodec.decode(JournalCodec.encode(entries))
        assertEquals(entries, back)
    }

    @Test
    fun `decode empty array`() {
        assertEquals(0, JournalCodec.decode("[]").size)
    }
}
