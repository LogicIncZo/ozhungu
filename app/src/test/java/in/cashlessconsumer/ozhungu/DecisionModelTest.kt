package `in`.cashlessconsumer.ozhungu

import `in`.cashlessconsumer.ozhungu.brain.DecisionModel
import `in`.cashlessconsumer.ozhungu.plan.PlanParser
import `in`.cashlessconsumer.ozhungu.plan.PlanValidator
import `in`.cashlessconsumer.ozhungu.scan.ScanEntry
import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionModelTest {

    private fun scan(
        files: List<String> = listOf("bill.pdf", "Screenshot_20240101_090000.png", "song.mp3", "clip.mp4", "app.apk"),
        dirs: Set<String> = setOf("notes", "a/b", "Receipts"),
    ) = ScanResult(
        entries = files.map { ScanEntry(it, "application/octet-stream", 10, 1_700_000_000_000) },
        uriByPath = files.associateWith { "content://t/$it" },
        dirPaths = dirs,
        skippedCount = 0,
    )

    @Test
    fun `vocabulary puts existing dirs first canonical next and keep last`() {
        val f = DecisionModel.folders(scan())
        val keys = f.keys.toList()
        assertEquals(DecisionModel.KEEP, keys.last())
        // existing root dirs come first (sorted), nested dirs excluded
        assertEquals(listOf("Receipts", "notes"), keys.take(2))
        assertTrue(keys.containsAll(listOf("Screenshots", "Pictures", "Bills", "Music", "Movies", "APKs", "Misc", "Documents")))
        assertFalse(f.containsKey("a"))
    }

    @Test
    fun `vocabulary caps at max folders with keep always last`() {
        val many = (1..40).map { "d$it" }.toSet()
        val files = (1..10).map { "f$it.txt" }
        val s = scan(files = files, dirs = many)
        val f = DecisionModel.folders(s)
        assertEquals(DecisionModel.MAX_FOLDERS, f.size)
        assertEquals(DecisionModel.KEEP, f.keys.toList().last())
    }

    @Test
    fun `batches cap at 64 questions with stable ids`() {
        val files = (0 until 130).map { "file$it.txt" }
        val s = scan(files = files)
        val batches = DecisionModel.batches(s, DecisionModel.folders(s))
        assertEquals(listOf(64, 64, 2), batches.map { it.questions.size })
        val ids = batches.flatMap { it.questions.map { q -> q.id } }
        assertEquals((0 until 130).map { "q$it" }, ids)
        assertTrue(batches.all { b -> b.questions.all { it.criteria.containsKey(DecisionModel.KEEP) } })
    }

    @Test
    fun `request json matches the system one schema`() {
        val s = scan(files = listOf("x.pdf"))
        val folders = DecisionModel.folders(s)
        val req = JSONObject(DecisionModel.requestJson(DecisionModel.batches(s, folders).first(), "{\"listing\":true}"))
        assertEquals("{\"listing\":true}", req.getString("state"))
        val q = req.getJSONObject("questions").getJSONObject("q0")
        assertEquals("choice", q.getString("type"))
        assertTrue(q.getString("instructions").contains(DecisionModel.KEEP))
        assertEquals(folders, q.getJSONObject("criteria").let { c ->
            val m = LinkedHashMap<String, String>()
            val k = c.keys(); while (k.hasNext()) { val key = k.next(); m[key] = c.getString(key) }
            m
        })
    }

    @Test
    fun `parse answers accepts bare and workers-ai envelopes`() {
        val one = JSONObject()
            .put("type", "choice")
            .put("choice", "Bills")
            .put("probabilities", JSONObject().put("Bills", 0.91).put("Misc", 0.09))
        val answers = JSONObject().put("q0", one)
        val bare = JSONObject().put("answers", answers).toString()
        val wrapped = JSONObject()
            .put("result", JSONObject().put("model", "clef").put("answers", answers))
            .put("success", true)
            .toString()

        for (raw in listOf(bare, wrapped)) {
            val out = DecisionModel.parseAnswers(raw)
            assertEquals(1, out.size)
            assertEquals("Bills", out["q0"]!!.choice)
            assertEquals(0.91, out["q0"]!!.best, 1e-9)
        }
        assertTrue(DecisionModel.parseAnswers("garbage").isEmpty())
        assertTrue(DecisionModel.parseAnswers("{\"answers\":{\"q0\":{\"type\":\"noul\",\"noul\":0.5}}}").isEmpty())
    }

    @Test
    fun `decide acts above threshold and skips keep low-confidence and same-dir`() {
        val s = scan(files = listOf("a.pdf", "b.pdf", "c.pdf", "d.pdf"))
        val answers = mapOf(
            "q0" to DecisionModel.ChoiceAnswer("Bills", 0.93),           // move
            "q1" to DecisionModel.ChoiceAnswer(DecisionModel.KEEP, 0.99), // keep → skip
            "q2" to DecisionModel.ChoiceAnswer("Misc", 0.40),            // below 0.6 → skip
            "q3" to DecisionModel.ChoiceAnswer("Documents", 0.80),       // d.pdf is at root → move
        )
        val (actions, skipped) = DecisionModel.decide(s, answers, 0.6)
        assertEquals(2, actions.size)
        assertEquals(2, skipped)
        assertEquals("a.pdf", actions[0].from)
        assertEquals("Bills/a.pdf", actions[0].to)
        assertEquals("Documents/d.pdf", actions[1].to)
        assertTrue(actions.all { it.reason.contains("p=") })
    }

    @Test
    fun `decide dedupes colliding targets`() {
        val s = scan(files = listOf("x/photo.jpg", "y/photo.jpg"))
        val answers = mapOf(
            "q0" to DecisionModel.ChoiceAnswer("Pictures", 0.9),
            "q1" to DecisionModel.ChoiceAnswer("Pictures", 0.9),
        )
        val (actions, _) = DecisionModel.decide(s, answers, 0.6)
        assertEquals(2, actions.size)
        assertEquals("Pictures/photo.jpg", actions[0].to)
        assertEquals("Pictures/photo (2).jpg", actions[1].to)
    }

    @Test
    fun `plan json round-trips through the real parser and validator`() {
        val s = scan(files = listOf("a.pdf"))
        val (actions, _) = DecisionModel.decide(s, mapOf("q0" to DecisionModel.ChoiceAnswer("Bills", 0.95)), 0.6)
        val v = PlanValidator.validate(PlanParser.parse(DecisionModel.planJson(actions)), s)
        assertEquals(1, v.valid.size)
        assertEquals(0, v.invalid.size)
    }

    @Test
    fun `cleanPath follows the validator grammar`() {
        assertEquals("a/b", DecisionModel.cleanPath("/a/./b/"))
        assertNull(DecisionModel.cleanPath("../escape"))
        assertNull(DecisionModel.cleanPath(""))
    }

    @Test
    fun `answers keyed by unknown ids are ignored safely`() {
        val s = scan(files = listOf("a.pdf"))
        val raw = JSONObject().put(
            "answers",
            JSONObject().put("zzz", JSONObject().put("type", "choice").put("choice", "Misc")
                .put("probabilities", JSONObject().put("Misc", 0.9))),
        ).toString()
        val (actions, skipped) = DecisionModel.decide(s, DecisionModel.parseAnswers(raw), 0.6)
        assertEquals(0, actions.size)
        assertEquals(1, skipped)
    }
}
