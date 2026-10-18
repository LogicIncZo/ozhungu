package `in`.cashlessconsumer.ozhungu

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Secrets + backend config in EncryptedSharedPreferences (same pattern as zai-widget). */
class Settings(context: Context) {
    private val sp = EncryptedSharedPreferences.create(
        context,
        "ozhungu_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var token: String
        get() = sp.getString(KEY_TOKEN, "").orEmpty()
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_MODEL, v).apply()

    /** "zo" (default), "oai" (OpenAI-compatible endpoint), or "decision" (System One / Jev). */
    var backend: String
        get() = sp.getString(KEY_BACKEND, "zo").orEmpty().ifBlank { "zo" }
        set(v) = sp.edit().putString(KEY_BACKEND, v).apply()

    /** System One decision endpoint — full run URL, e.g. Workers AI
     *  https://api.cloudflare.com/client/v4/accounts/<acct>/ai/run/@cf/cloudflare/clef-flash */
    var dEndpoint: String
        get() = sp.getString(KEY_D_ENDPOINT, "").orEmpty()
        set(v) = sp.edit().putString(KEY_D_ENDPOINT, v).apply()

    var dKey: String
        get() = sp.getString(KEY_D_KEY, "").orEmpty()
        set(v) = sp.edit().putString(KEY_D_KEY, v).apply()

    /** Minimum calibrated probability (1–99) required to act; below → file left in place. */
    var dThreshold: Int
        get() = sp.getInt(KEY_D_THRESHOLD, 60)
        set(v) = sp.edit().putInt(KEY_D_THRESHOLD, v).apply()
    /** Decision backend flavor: "clef" (raw Jev-compatible endpoint) or "openrouter". */
    var dFlavor: String
        get() = sp.getString(KEY_D_FLAVOR, "clef").orEmpty()
        set(v) = sp.edit().putString(KEY_D_FLAVOR, v).apply()

    /** Decision model id (openrouter flavor only), e.g. ~typesafe/jev-latest. */
    var dModel: String
        get() = sp.getString(KEY_D_MODEL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_D_MODEL, v).apply()

    /** OpenAI-compatible base URL, e.g. https://openrouter.ai/api/v1 (no trailing slash). */
    var oaiBaseUrl: String
        get() = sp.getString(KEY_OAI_BASE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_OAI_BASE, v).apply()

    var oaiKey: String
        get() = sp.getString(KEY_OAI_KEY, "").orEmpty()
        set(v) = sp.edit().putString(KEY_OAI_KEY, v).apply()

    var oaiModel: String
        get() = sp.getString(KEY_OAI_MODEL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_OAI_MODEL, v).apply()

    var treeUri: String
        get() = sp.getString(KEY_TREE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_TREE, v).apply()

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_MODEL = "model"
        private const val KEY_TREE = "tree_uri"
        private const val KEY_BACKEND = "backend"
        private const val KEY_OAI_BASE = "oai_base_url"
        private const val KEY_OAI_KEY = "oai_api_key"
        private const val KEY_OAI_MODEL = "oai_model"
        private const val KEY_D_ENDPOINT = "decision_endpoint"
        private const val KEY_D_KEY = "decision_key"
        private const val KEY_D_THRESHOLD = "decision_threshold"
        private const val KEY_D_FLAVOR = "decision_flavor"
        private const val KEY_D_MODEL = "decision_model"
    }
}
