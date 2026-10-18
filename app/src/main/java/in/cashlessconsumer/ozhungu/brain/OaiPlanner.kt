package `in`.cashlessconsumer.ozhungu.brain

import `in`.cashlessconsumer.ozhungu.plan.PromptBuilder
import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Any OpenAI-compatible /chat/completions endpoint. Chat is stateless, so continuity
 * is local: this instance replays its own transcript (system + turns) on refinements.
 */
class OaiPlanner(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : Planner {

    private val messages = JSONArray()
    private var call: Call? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun plan(
        prompt: String,
        onStatus: (String?) -> Unit,
        onDelta: (String) -> Unit,
        onDone: (String?) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (messages.length() == 0) {
            messages.put(JSONObject().put("role", "system").put("content", SYSTEM_RULES))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", messages)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        var acc = StringBuilder()
        call = client.newCall(req)
        call!!.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                if (c.isCanceled()) return
                onError(e)
            }

            override fun onResponse(c: Call, resp: Response) {
                resp.use {
                    if (!it.isSuccessful) {
                        val head = runCatching { it.body?.string().orEmpty().take(400) }.getOrDefault("")
                        onError(IOException("HTTP ${it.code}: $head"))
                        return
                    }
                    val src = it.body?.source() ?: return onError(IOException("empty body"))
                    while (true) {
                        val line = try { src.readUtf8Line() } catch (e: IOException) { if (c.isCanceled()) return else throw e }
                            ?: break
                        val piece = Sse.delta(line) ?: continue
                        acc.append(piece)
                        onDelta(piece)
                    }
                    val text = acc.toString()
                    if (text.isBlank()) {
                        onError(IOException("empty completion"))
                        return
                    }
                    messages.put(JSONObject().put("role", "assistant").put("content", text))
                    // Keep the transcript bounded: system + last user + last assistant.
                    while (messages.length() > 3) messages.remove(1)
                    onDone(null)
                }
            }
        })
    }

    override fun cancel() {
        call?.cancel()
        call = null
    }

    /** Lines of an OpenAI SSE stream to plan-delta strings; null = not a content line. */
    object Sse {
        fun delta(line: String): String? {
            if (!line.startsWith("data:")) return null
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") return null
            return try {
                val root = JSONObject(payload)
                val choices = root.optJSONArray("choices") ?: return null
                (0 until choices.length()).asSequence()
                    .map { choices.getJSONObject(it) }
                    .mapNotNull { it.optJSONObject("delta") }
                    .mapNotNull { it.optString("content", null) }
                    .firstOrNull { it.isNotEmpty() }
            } catch (t: Throwable) {
                null
            }
        }
    }

    companion object {
        /** Same contract PromptBuilder ships to Zo; kept identical on purpose. */
        val SYSTEM_RULES = PromptBuilder.SYSTEM_RULES
    }
}
