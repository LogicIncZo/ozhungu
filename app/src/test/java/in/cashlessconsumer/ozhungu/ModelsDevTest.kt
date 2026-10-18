package `in`.cashlessconsumer.ozhungu

import `in`.cashlessconsumer.ozhungu.brain.ModelsDev
import `in`.cashlessconsumer.ozhungu.brain.OaiPlanner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsDevTest {

    private fun catalog() = """
    {
      "openrouter": {
        "id": "openrouter", "name": "OpenRouter", "npm": "@ai-sdk/openai-compatible",
        "api": "https://openrouter.ai/api/v1",
        "models": {
          "zdr-model": {"id": "zdr-model", "name": "Zero Data Retention variant",
                        "description": "Zero data retention endpoint."},
          "glm-5": {"id": "glm-5", "name": "GLM 5"},
          "glm-6": {"id": "glm-6", "name": "GLM 6"},
          "plain": {"id": "plain", "name": "Plain"}
        }
      },
      "lmstudio": {
        "id": "lmstudio", "name": "LM Studio", "npm": "@ai-sdk/openai-compatible",
        "api": "http://localhost:1234/v1/",
        "models": {"local-1": {"id": "local-1", "name": "Local"}}
      },
      "azure": {
        "id": "azure", "name": "Azure", "npm": "@ai-sdk/azure",
        "api": "https://x.openai.azure.com/v1",
        "models": {"gpt": {}}
      },
      "noapi": {
        "id": "noapi", "name": "NoApi", "npm": "@ai-sdk/openai-compatible",
        "models": {"m": {}}
      }
    }
    """.trimIndent()

    @Test
    fun `keeps api-backed providers, drops no-api ones, denylists non-chat SDKs`() {
        val out = ModelsDev.parse(catalog())
        // zdr-bearing openrouter sorts first; azure's @ai-sdk/azure still serves
        // OAI-shaped /chat/completions so it stays; noapi has no api base → dropped.
        assertEquals(listOf("openrouter", "azure", "lmstudio"), out.map { it.id })
        assertFalse(out.any { it.id == "noapi" })
        assertTrue(out.none { it.id == "anthropic" })
    }

    @Test
    fun `zdr providers sort first and badge models`() {
        val out = ModelsDev.parse(catalog())
        val or = out.first { it.id == "openrouter" }
        assertTrue(or.zdr)
        assertTrue(or.models.first().zdr)
        assertEquals("zdr-model", or.models.first().id)
        val lm = out.first { it.id == "lmstudio" }
        assertFalse(lm.zdr)
    }
    @Test
    fun `filter matches provider id name and model ids only`() {
        val provs = ModelsDev.parse(catalog())
        assertEquals(3, ModelsDev.filter(provs, "").size)
        assertEquals(listOf("openrouter"), ModelsDev.filter(provs, "openrouter").map { it.id })
        assertEquals(listOf("lmstudio"), ModelsDev.filter(provs, "studio").map { it.id })
        val byModel = ModelsDev.filter(provs, "glm")
        assertEquals(listOf("openrouter"), byModel.map { it.id })
        assertEquals(listOf("glm-5", "glm-6"), byModel[0].models.map { it.id })
        assertTrue(ModelsDev.filter(provs, "nonexistent").isEmpty())
    }

    @Test
    fun `picker rows interleave provider headers and model entries`() {
        val rows = ModelsDev.rows(ModelsDev.filter(ModelsDev.parse(catalog()), "openrouter"))
        assertEquals(ModelsDev.PickerRow.Kind.PROVIDER, rows[0].kind)
        assertTrue(rows[0].zdr)
        assertEquals("https://openrouter.ai/api/v1", rows[0].subtitle)
        val models = rows.drop(1)
        assertEquals(listOf("zdr-model", "glm-5", "glm-6", "plain"), models.map { it.title })
        assertEquals(listOf(true, false, false, false), models.map { it.zdr })
        models.forEach {
            assertEquals(ModelsDev.PickerRow.Kind.MODEL, it.kind)
            assertEquals(rows[0].provIdx, it.provIdx)
        }
    }

    @Test
    fun `trailing slash is trimmed from api base`() {
        val out = ModelsDev.parse(catalog())
        assertEquals("http://localhost:1234/v1", out.first { it.id == "lmstudio" }.api)
    }

    @Test
    fun `garbage input fails without throwing`() {
        assertTrue(ModelsDev.parse("not json").isEmpty())
        assertTrue(ModelsDev.parse("{}").isEmpty())
        assertTrue(ModelsDev.parse("[1,2]").isEmpty())
    }
}

class OaiSseTest {

    private fun frame(content: String?): String {
        val delta = if (content == null) JSONObject() else JSONObject().put("content", content)
        return JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("delta", delta)))
            .toString()
    }

    @Test
    fun `extracts content deltas from data lines`() {
        assertEquals("hel", OaiPlanner.Sse.delta("data: " + frame("hel")))
        assertEquals("lo", OaiPlanner.Sse.delta("data:" + frame("lo")))
    }

    @Test
    fun `ignores done comments and non-content lines`() {
        assertNull(OaiPlanner.Sse.delta("data: [DONE]"))
        assertNull(OaiPlanner.Sse.delta(": keep-alive"))
        assertNull(OaiPlanner.Sse.delta("event: ping"))
        assertNull(OaiPlanner.Sse.delta("data: " + frame(null)))
        assertNull(OaiPlanner.Sse.delta("data: {broken json"))
    }

    @Test
    fun `empty content deltas are dropped`() {
        assertNull(OaiPlanner.Sse.delta("data: " + frame("")))
    }
}

class OaiTranscriptTest {

    private fun roles(t: OaiPlanner.Transcript): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until t.messages.length()) out.add(t.messages.getJSONObject(i).getString("role"))
        return out
    }

    private fun contents(t: OaiPlanner.Transcript): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until t.messages.length()) out.add(t.messages.getJSONObject(i).getString("content"))
        return out
    }

    @Test
    fun `seeds system once then keeps bounded window`() {
        val t = OaiPlanner.Transcript("RULES")
        t.addUser("plan this folder")
        t.addAssistant("{\"plan\":{}}")
        assertEquals(listOf("system", "user", "assistant"), roles(t))

        // Refinement: user turn appended, old user turn drops, system stays.
        t.addUser("put invoices under Bills")
        t.addAssistant("{\"plan\":{}}")
        assertEquals(listOf("system", "user", "assistant"), roles(t))
        assertEquals("put invoices under Bills", contents(t)[1])
    }

    @Test
    fun `request body carries model, stream and the transcript`() {
        val t = OaiPlanner.Transcript("RULES")
        t.addUser("p")
        val body = JSONObject(t.requestBody("glm-5"))
        assertEquals("glm-5", body.getString("model"))
        assertEquals(true, body.getBoolean("stream"))
        assertEquals(2, body.getJSONArray("messages").length())
    }}
