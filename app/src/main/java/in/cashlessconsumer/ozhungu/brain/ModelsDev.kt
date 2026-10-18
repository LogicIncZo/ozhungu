package `in`.cashlessconsumer.ozhungu.brain

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The models.dev catalog (https://models.dev/api.json), the default source for
 * OpenAI-compatible providers. A provider counts as OpenAI-compatible when it has an
 * `api` base URL and ships via @ai-sdk/openai-compatible. "ZDR" is a text-derived badge:
 * models.dev carries no retention field, so a provider/model is flagged only when its
 * own catalog text mentions zero data retention — the badge is a hint, not a guarantee;
 * verify the provider's policy before trusting it.
 */
object ModelsDev {

    const val CATALOG_URL = "https://models.dev/api.json"
    private const val CACHE_FILE = "modelsdev-cache.json"
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private val NON_OAI_SDKS = setOf("@ai-sdk/anthropic", "@ai-sdk/google", "@ai-sdk/amazon-bedrock")
    private const val MAX_PROVIDERS = 60
    private const val MAX_MODELS_PER_PROVIDER = 40

    data class CatModel(val id: String, val zdr: Boolean)
    data class CatProvider(val id: String, val name: String, val api: String, val zdr: Boolean, val models: List<CatModel>)
    data class Pick(val baseUrl: String, val modelId: String)

    /** Fetches (with a 24 h disk cache) and returns the filtered, sorted provider list. */
    fun load(context: Context): Result<List<CatProvider>> = try {
        val raw = fetchOrCache(context)
        Result.success(parse(raw))
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private fun fetchOrCache(context: Context): String {
        val f = java.io.File(context.filesDir, CACHE_FILE)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_TTL_MS) {
            val cached = runCatching { f.readText() }.getOrNull()
            if (!cached.isNullOrBlank()) return cached
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(CATALOG_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("models.dev HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) throw java.io.IOException("models.dev empty body")
            f.writeText(body)
            return body
        }
    }

    /** Pure: parses raw api.json into ZDR-first, name-sorted OpenAI-compatible providers. */
    fun parse(raw: String): List<CatProvider> = try {
        parseInner(raw)
    } catch (t: Throwable) {
        emptyList()
    }

    private fun parseInner(raw: String): List<CatProvider> {
        val root = JSONObject(raw)
        val out = ArrayList<CatProvider>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val pid = keys.next()
            val p = root.optJSONObject(pid) ?: continue
            val api = p.optString("api", "")
            val npm = p.optString("npm", "")
            if (api.isBlank()) continue
            // Denylist SDKs whose api URL is definitively NOT /chat/completions-shaped;
            // everything else with a base URL passes (gateways like OpenRouter ship
            // bespoke SDK packages yet serve OpenAI-compatible endpoints).
            if (npm in NON_OAI_SDKS) continue
            val modelsObj = p.optJSONObject("models") ?: JSONObject()
            var providerZdr = false
            val models = ArrayList<CatModel>()
            val mk = modelsObj.keys()
            while (mk.hasNext()) {
                val mid = mk.next()
                val m = modelsObj.optJSONObject(mid) ?: continue
                val desc = m.optString("description", "").lowercase()
                val mZdr = desc.contains("zero data retention") || desc.contains("zdr")
                if (mZdr) providerZdr = true
                if (models.size < MAX_MODELS_PER_PROVIDER) models.add(CatModel(mid, mZdr))
            }
            out.add(
                CatProvider(
                    id = pid,
                    name = p.optString("name", pid),
                    api = api.trimEnd('/'),
                    zdr = providerZdr,
                    models = models.sortedWith(compareByDescending<CatModel> { it.zdr }.thenBy { it.id }),
                ),
            )
        }
        return out.sortedWith(compareByDescending<CatProvider> { it.zdr }.thenBy { it.name.lowercase() })
            .take(MAX_PROVIDERS)
    }
}
