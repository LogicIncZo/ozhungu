package `in`.cashlessconsumer.ozhungu.brain

import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * System One backend (TypeSafe Jev API shape; Cloudflare Clef / any Jev-compatible
 * endpoint). One request per ≤64-file batch: state = listing JSON, questions =
 * one folder choice per file. Calibrated probabilities become move actions via
 * [DecisionModel.decide]; the plan JSON streams through the same review pipeline.
 */
class DecisionPlanner(
    private val endpoint: String,
    private val apiKey: String,
    private val thresholdPct: Int,
    private val scan: ScanResult,
    private val flavor: String = "clef",
    private val model: String = "",
    private val pickedFolders: Set<String> = emptySet(),
    private val allowAutoFolders: Boolean = true,
) : Planner {

    companion object {
        /** OpenRouter's System One Decisions API (state+questions, not chat). */
        const val OPENROUTER_URL = "https://openrouter.ai/api/alpha/decisions"

        /** OpenRouter's rolling alias — always the newest Jev. */
        const val DEFAULT_OR_MODEL = "~typesafe/jev-latest"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private var call: Call? = null

    override fun plan(
        prompt: String,
        onStatus: (String?) -> Unit,
        onDelta: (String) -> Unit,
        onDone: (String?) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        Thread {
            try {
                val folders = DecisionModel.folders(scan, pickedFolders, allowAutoFolders)
                val state = DecisionModel.stateJson(scan)
                val batches = DecisionModel.batches(scan, folders)
                val openRouter = flavor == "openrouter"
                val answers = HashMap<String, DecisionModel.ChoiceAnswer>()
                batches.forEachIndexed { k, b ->
                    onStatus("Asking decision model — batch ${k + 1}/${batches.size}…")
                    val req = if (openRouter) {
                        DecisionModel.openRouterRequestJson(
                            model.ifBlank { "~typesafe/jev-latest" }, b, state,
                        )
                    } else {
                        DecisionModel.requestJson(b, state)
                    }
                    answers += ask(req, openRouter)
                }
                val (actions, skipped) = DecisionModel.decide(scan, answers, thresholdPct / 100.0)
                onStatus(null)
                onDelta(DecisionModel.planJson(actions))
                onDone("${actions.size} moves proposed, $skipped left in place")
            } catch (t: Throwable) {
                onError(t)
            }
        }.start()
    }

    private fun ask(
        requestJson: String,
        openRouter: Boolean,
    ): Map<String, DecisionModel.ChoiceAnswer> {
        val body = requestJson.toRequestBody("application/json".toMediaType())
        if (openRouter && apiKey.isBlank()) {
            throw IOException("OpenRouter needs an API key — add one in Settings")
        }
        val url = if (openRouter) OPENROUTER_URL else endpoint
        val rb = Request.Builder().url(url).post(body)
        if (apiKey.isNotBlank()) rb.header("Authorization", "Bearer $apiKey")
        val c = client.newCall(rb.build())
        call = c
        val resp = c.execute()
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${text.take(160)}")
            val root = runCatching { JSONObject(text) }.getOrNull()
                ?: throw IOException("Decision endpoint returned non-JSON")
            val success = if (root.has("success")) root.optBoolean("success") else true
            if (!success) {
                val errs = root.optJSONArray("errors")
                val msg = if (errs == null) "unknown" else (0 until errs.length())
                    .mapNotNull { i -> errs.optJSONObject(i)?.optString("message")?.ifBlank { null } }
                    .joinToString("; ").ifBlank { "unknown" }
                throw IOException("Decision endpoint error: $msg".take(200))
            }
            return DecisionModel.parseAnswers(text)
        }
    }

    override fun cancel() {
        call?.cancel()
    }
}
