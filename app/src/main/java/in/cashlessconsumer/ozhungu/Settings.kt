package `in`.cashlessconsumer.ozhungu

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Token + model stored in EncryptedSharedPreferences (same pattern as zai-widget). */
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

    var treeUri: String
        get() = sp.getString(KEY_TREE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_TREE, v).apply()

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_MODEL = "model"
        private const val KEY_TREE = "tree_uri"
    }
}
