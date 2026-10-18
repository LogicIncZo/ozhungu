package `in`.cashlessconsumer.ozhungu

import `in`.cashlessconsumer.ozhungu.plan.Plan
import `in`.cashlessconsumer.ozhungu.plan.PlanAction
import `in`.cashlessconsumer.ozhungu.plan.PlanParser
import `in`.cashlessconsumer.ozhungu.plan.PlanValidator
import `in`.cashlessconsumer.ozhungu.plan.PromptBuilder
import `in`.cashlessconsumer.ozhungu.scan.ScanEntry
import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {

    private fun scan(
        files: List<String> = listOf(
            "bill.pdf", "IMG_20240101_900.jpg", "notes/idea.txt", "Receipts/receipt-march.pdf",
        ),
        dirs: Set<String> = setOf("notes", "Receipts"),
    ) = ScanResult(
        entries = files.map { ScanEntry(it, "application/pdf", 1024, 0) },
        uriByPath = files.associateWith { "content://test/$it" },
        dirPaths = dirs,
        skippedCount = 0,
    )

    @Test
    fun `parses flat and wrapped plan json`() {
        val flat = """{"folders":["Bills"],"actions":[{"op":"move","from":"bill.pdf","to":"Bills/2026/bill.pdf","reason":"invoice"}]}"""
        val wrapped = """{"plan":{"folders":["Bills"],"actions":[{"op":"move","from":"bill.pdf","to":"Bills/2026/bill.pdf","reason":"invoice"}]}}"""
        for (json in listOf(flat, wrapped)) {
            val p = PlanParser.parse("noise before ```json\n$json\n``` noise after")
            assertEquals(1, p.actions.size)
            assertEquals("Bills/2026/bill.pdf", p.actions[0].to)
        }
    }

    @Test
    fun `extracts first balanced json object ignoring braces in strings`() {
        val text = """junk {"plan":{"note":"has } brace","actions":[]}} trailing"""
        val json = PlanParser.extractJsonObject(text)
        assertNotNull(json)
        assertTrue(json!!.endsWith("}"))
        assertEquals(0, PlanParser.parse(json).actions.size)
    }

    @Test
    fun `no json throws`() {
        try {
            PlanParser.parse("the model rambled but produced no json")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("No JSON"))
        }
    }

    @Test
    fun `accepts a good plan`() {
        val plan = Plan(
            listOf("Bills"),
            listOf(PlanAction("move", "bill.pdf", "Bills/bill.pdf", "invoice")),
        )
        val v = PlanValidator.validate(plan, scan())
        assertTrue(v.allOk)
        assertEquals(1, v.valid.size)
    }

    @Test
    fun `rejects unknown source and escaping targets`() {
        val plan = Plan(
            emptyList(),
            listOf(
                PlanAction("move", "ghost.pdf", "Bills/ghost.pdf", "hallucinated source"),
                PlanAction("move", "bill.pdf", "../evil/bill.pdf", "escape"),
                PlanAction("move", "bill.pdf", "/abs/bill.pdf", "absolute"),
                PlanAction("move", "bill.pdf", "notes", "target is an existing folder"),
                PlanAction("move", "bill.pdf", "bill.pdf", "no-op"),
                PlanAction("move", "bill.pdf", "notes/idea.txt", "overwrite existing file"),
                PlanAction("delete", "bill.pdf", "notes/x.pdf", "unsupported op"),
                PlanAction("move", "notes/idea.txt", "notes/", "empty file name"),
            ),
        )
        val v = PlanValidator.validate(plan, scan())
        assertFalse(v.allOk)
        assertEquals(0, v.valid.size)
        assertEquals(8, v.invalid.size)
        assertTrue(v.invalid.any { it.error.contains("was not in the scan") })
        assertTrue(v.invalid.any { it.error.contains("existing folder") })
        assertTrue(v.invalid.any { it.error.contains("overwrite") })
    }

    @Test
    fun `rejects duplicate targets and chaining`() {
        val plan = Plan(
            emptyList(),
            listOf(
                PlanAction("move", "bill.pdf", "Bills/bill.pdf", "first"),
                PlanAction("move", "IMG_20240101_900.jpg", "Bills/bill.pdf", "duplicate target"),
            ),
        )
        val v = PlanValidator.validate(plan, scan())
        assertEquals(1, v.valid.size)
        assertEquals(1, v.invalid.size)
        assertTrue(v.invalid[0].error.contains("duplicates"))

        val chain = Plan(
            emptyList(),
            listOf(PlanAction("move", "bill.pdf", "notes/idea.txt", "from equals a later source").let { it }),
        )
        val v2 = PlanValidator.validate(chain, scan())
        assertEquals(0, v2.valid.size)

        val chainedPlan = Plan(
            emptyList(),
            listOf(
                PlanAction("move", "bill.pdf", "Receipts/receipt-march-2.pdf", "ok"),
            ),
        )
        val v3 = PlanValidator.validate(chainedPlan, scan())
        assertTrue(v3.allOk)
    }

    @Test
    fun `normalizePath rejects escapes and accepts plain relatives`() {
        assertNull(PlanValidator.normalizePath(".."))
        assertNull(PlanValidator.normalizePath("a/../.."))
        assertNull(PlanValidator.normalizePath("/abs"))
        assertNull(PlanValidator.normalizePath("  "))
        assertEquals("a/b.txt", PlanValidator.normalizePath("a/./b.txt"))
        assertEquals("a/b.txt", PlanValidator.normalizePath("a\\b.txt"))
    }

    @Test
    fun `prompt contains contract and capped listing`() {
        val s = scan(files = (1..400).map { "f$it.txt" }, dirs = setOf())
        val prompt = PromptBuilder.build(s)
        assertTrue(prompt.contains("\"plan\""))
        assertTrue(prompt.contains("Never move files outside this folder"))
        assertTrue(prompt.contains("Do not delete anything"))
        assertTrue(prompt.contains("FOLDERS ALREADY PRESENT: none"))
        assertTrue(prompt.contains("(100 more not shown)"))
        assertFalse(prompt.contains("f350.txt"))
        assertTrue(prompt.contains("f299.txt"))
    }
    @Test
    fun `parser tolerates prose fences and non-object action rows`() {
        val noisy = "Sure! Here is the plan:\n```json\n{\"plan\":{\"folders\":[\"A\"],\"actions\":[{\"op\":\"move\",\"from\":\"bill.pdf\",\"to\":\"A/bill.pdf\",\"reason\":\"r\"},\"garbage\",null]}}\n```\nThanks."
        val p = PlanParser.parse(noisy)
        assertEquals(listOf("A"), p.folders)
        assertEquals(1, p.actions.size)
        assertEquals("A/bill.pdf", p.actions[0].to)

        // A string containing an escaped quote and a brace must not confuse the extractor.
        val escaped = "{\"plan\":{\"folders\":[],\"actions\":[{\"op\":\"move\",\"from\":\"a\\\"{.txt\",\"to\":\"B/a\\\"{.txt\",\"reason\":\"r\"}]}}"
        val p2 = PlanParser.parse(PlanParser.extractJsonObject(escaped)!!)
        assertEquals("B/a\"{.txt", p2.actions[0].to)
    }

    @Test
    fun `validator rejects ops other than move and rename`() {
        val plan = Plan(emptyList(), listOf(PlanAction("copy", "bill.pdf", "Bills/bill.pdf", "copy op")))
        val v = PlanValidator.validate(plan, scan())
        assertEquals(0, v.valid.size)
        assertTrue(v.invalid[0].error.contains("op"))
    }
}
