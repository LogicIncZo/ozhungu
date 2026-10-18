package `in`.cashlessconsumer.ozhungu

import `in`.cashlessconsumer.ozhungu.brain.OaiPlanner
import `in`.cashlessconsumer.ozhungu.plan.PromptBuilder
import `in`.cashlessconsumer.ozhungu.scan.ScanEntry
import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTranscriptTest {

    @Test
    fun `build includes mime and iso dates for every entry`() {
        val s = ScanResult(
            entries = listOf(
                ScanEntry("bill.pdf", "application/pdf", 2048, 1735689600000L),
                ScanEntry("shot.png", "image/png", 512, 1704067200000L),
            ),
            uriByPath = mapOf("bill.pdf" to "u1", "shot.png" to "u2"),
            dirPaths = setOf("Old"),
            skippedCount = 0,
        )
        val prompt = PromptBuilder.build(s)
        assertTrue(prompt.contains("\"m\":\"application/pdf\""))
        assertTrue(prompt.contains("\"m\":\"image/png\""))
        assertTrue(prompt.contains("\"d\":\"2025-01-01\""))
        assertTrue(prompt.contains("\"d\":\"2024-01-01\""))
        assertTrue(prompt.contains("\"kb\":2"))
    }

    @Test
    fun `build shows snippets when present`() {
        val s = ScanResult(
            entries = listOf(
                ScanEntry("note.txt", "text/plain", 100, 0L, snippet = "water bill march"),
            ),
            uriByPath = mapOf("note.txt" to "u1"),
            dirPaths = emptySet(),
            skippedCount = 1,
        )
        val prompt = PromptBuilder.build(s)
        assertTrue(prompt.contains("water bill march"))
        assertTrue(prompt.contains("1 unreadable skipped"))
        assertTrue(prompt.contains("FOLDERS ALREADY PRESENT: none"))
    }

    @Test
    fun `refine wraps request with conversation context`() {
        val p = PromptBuilder.refine("prior context here", "  also make a Taxes folder  ")
        assertTrue(p.contains("prior context here"))
        assertTrue(p.contains("also make a Taxes folder"))
    }

    @Test
    fun `transcript seeds system then replays user turns`() {
        val t = OaiPlanner.Transcript("RULES")
        t.addUser("first prompt")
        val b1 = t.requestBody("glm-4")
        val msgs1 = org.json.JSONObject(b1).getJSONArray("messages")
        assertEquals(2, msgs1.length())
        assertEquals("system", msgs1.getJSONObject(0).getString("role"))
        assertEquals("RULES", msgs1.getJSONObject(0).getString("content"))
        assertEquals("user", msgs1.getJSONObject(1).getString("role"))
        assertEquals("first prompt", msgs1.getJSONObject(1).getString("content"))

        t.addUser("second prompt")
        val b2 = t.requestBody("glm-4")
        val msgs2 = org.json.JSONObject(b2).getJSONArray("messages")
        assertEquals(3, msgs2.length())
        assertEquals("second prompt", msgs2.getJSONObject(2).getString("content"))
    }

    @Test
    fun `transcript stays bounded after assistant replies`() {
        val t = OaiPlanner.Transcript("RULES")
        t.addUser("q1")
        t.addAssistant("a1")
        assertEquals(3, t.messages.length())
        t.addUser("q2")
        t.addAssistant("a2")
        assertEquals(3, t.messages.length())
        assertEquals("RULES", t.messages.getJSONObject(0).getString("content"))
        assertEquals("q2", t.messages.getJSONObject(1).getString("content"))
        assertEquals("a2", t.messages.getJSONObject(2).getString("content"))
    }

    @Test
    fun `request body carries model stream and messages`() {
        val t = OaiPlanner.Transcript("RULES")
        t.addUser("hello")
        val body = org.json.JSONObject(t.requestBody("glm-4"))
        assertEquals("glm-4", body.getString("model"))
        assertEquals(true, body.getBoolean("stream"))
        assertEquals(2, body.getJSONArray("messages").length())
    }

    @Test
    fun `build reports unreadable-dir count when nonzero`() {
        val s = ScanResult(
            entries = listOf(ScanEntry("a.txt", "text/plain", 10, 0)),
            uriByPath = mapOf("a.txt" to "u"),
            dirPaths = setOf(),
            skippedCount = 3,
        )
        val prompt = PromptBuilder.build(s)
        assertTrue(prompt.contains("3 unreadable skipped"))
        assertTrue(prompt.contains("FOLDERS ALREADY PRESENT: none"))
    }

    @Test
    fun `transcript replay keeps system first and trims to three messages`() {
        val t = OaiPlanner.Transcript("SYS")
        t.addUser("first prompt")
        t.addAssistant("{\"plan\":{\"folders\":[],\"actions\":[]}}")
        t.addUser("tweak: fewer folders")
        val msgs = t.messages
        assertEquals(3, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("tweak: fewer folders", msgs.getJSONObject(2).getString("content"))
        val body = JSONObject(t.requestBody("m1"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("m1", body.getString("model"))
        assertEquals(3, body.getJSONArray("messages").length())
    }
}
