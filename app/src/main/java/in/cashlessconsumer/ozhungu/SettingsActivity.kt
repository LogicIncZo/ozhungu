package `in`.cashlessconsumer.ozhungu

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import `in`.cashlessconsumer.ozhungu.brain.DecisionPlanner
import `in`.cashlessconsumer.ozhungu.brain.ModelsDev
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Full-screen settings: backend choice + per-backend credentials, live model
 * pickers, and a connection test per backend. Plain Views, no XML layouts,
 * no new dependencies (deliberately avoids ZoApi's suspend listModels).
 */
class SettingsActivity : Activity() {

    private lateinit var settings: Settings

    private lateinit var radioZo: RadioButton
    private lateinit var radioOai: RadioButton
    private lateinit var radioDecision: RadioButton
    private lateinit var zoPanel: ViewGroup
    private lateinit var oaiPanel: ViewGroup
    private lateinit var decisionPanel: ViewGroup
    private lateinit var zoToken: EditText
    private lateinit var zoModel: EditText
    private lateinit var oaiUrl: EditText
    private lateinit var oaiKey: EditText
    private lateinit var oaiModel: EditText
    private lateinit var dEndpoint: EditText
    private lateinit var dKey: EditText
    private lateinit var dThreshold: EditText
    private lateinit var dFlavorClef: android.widget.RadioButton
    private lateinit var dFlavorOr: android.widget.RadioButton
    private lateinit var dModel: EditText
    private var dModelRowTag: View? = null
    private lateinit var zoStatus: TextView
    private lateinit var oaiStatus: TextView
    private lateinit var decisionStatus: TextView

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var pickerDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val padS = (8 * resources.displayMetrics.density).toInt()

        fun header(text: String): TextView = TextView(this).apply {
            this.text = text
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 16f
            setPadding(0, pad, 0, padS)
        }

        fun caption(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, 0, 0, padS)
        }

        fun label(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 13f
        }

        fun secret(value: String, hint: String): Pair<LinearLayout, EditText> {
            val box = EditText(this).apply {
                this.hint = hint
                setText(value)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val eye = Button(this).apply {
                text = "show"
                setOnClickListener {
                    val hidden = box.transformationMethod != null
                    box.transformationMethod = if (hidden) null
                    else android.text.method.PasswordTransformationMethod.getInstance()
                    text = if (hidden) "hide" else "show"
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(eye)
            }
            return row to box
        }

        val root = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // ── Backend ─────────────────────────────────────────────────────────
        body.addView(header("Backend"))

        fun backendRow(id: Int, title: String, sub: String): RadioButton {
            val radio = RadioButton(this).apply { text = title }
            if (id == ID_ZO) radioZo = radio else radioOai = radio
            radio.setOnClickListener { radio.isChecked = true }
            val cap = caption(sub)
            radio.setOnCheckedChangeListener { _, checked -> if (checked) cap.alpha = 1f }
            return radio
        }

        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val zoSub = caption("api.zo.computer — conversation memory on the server.\nToken: zo.zo.computer → Settings → Advanced.")
        val oaiSub = caption("Any /chat/completions endpoint — OpenRouter, LM Studio,\nllama.cpp server, ollama, vLLM, …")
        val zoRadio = RadioButton(this).apply { text = "Zo agent" }.also { radioZo = it }
        val oaiRadio = RadioButton(this).apply { text = "OpenAI-compatible" }.also { radioOai = it }
        val decisionSub = caption("System One / Jev-style (Cloudflare Clef, self-hosted Clef) —\nanswers typed folder choices with calibrated probabilities.\nNo free-form model output; cheapest and fastest tier.")
        val decisionRadio = RadioButton(this).apply { text = "Decision model (System One)" }.also { radioDecision = it }
        group.addView(zoRadio)
        group.addView(zoSub)
        group.addView(oaiRadio)
        group.addView(oaiSub)
        group.addView(decisionRadio)
        group.addView(decisionSub)
        group.setOnCheckedChangeListener { _, _ -> syncPanels() }
        body.addView(group)

        // ── Zo panel ────────────────────────────────────────────────────────
        zoPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        zoPanel.addView(header("Zo"))
        zoPanel.addView(caption("Stored encrypted on this device (EncryptedSharedPreferences)."))
        zoPanel.addView(label("Access token"))
        val (zoTokenRow, zoTokenBox) = secret(settings.token, "paste Zo access token")
        zoToken = zoTokenBox
        zoPanel.addView(zoTokenRow)
        zoPanel.addView(label("Model (blank = Zo default)"))
        val zoModelBox = EditText(this).apply {
            setText(settings.model)
            hint = "model id"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        zoModel = zoModelBox
        zoPanel.addView(zoModelBox)
        zoPanel.addView(rowOf(
            button("Fetch model list") { fetchZoModels() },
            button("Test connection") { testZo() },
        ))
        zoStatus = caption("")
        zoPanel.addView(zoStatus)
        body.addView(zoPanel)

        // ── OpenAI-compatible panel ─────────────────────────────────────────
        oaiPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        oaiPanel.addView(header("OpenAI-compatible"))
        oaiPanel.addView(caption("Pick a provider from the models.dev catalog, or paste any endpoint."))
        oaiPanel.addView(label("Base URL (usually ends in /v1)"))
        val oaiUrlBox = EditText(this).apply {
            setText(settings.oaiBaseUrl)
            hint = "https://host/v1"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        oaiUrl = oaiUrlBox
        oaiPanel.addView(oaiUrlBox)
        oaiPanel.addView(label("API key"))
        val (oaiKeyRow, oaiKeyBox) = secret(settings.oaiKey, "sk-…")
        oaiKey = oaiKeyBox
        oaiPanel.addView(oaiKeyRow)
        oaiPanel.addView(label("Model id"))
        val oaiModelBox = EditText(this).apply {
            setText(settings.oaiModel)
            hint = "provider/model-id"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        oaiModel = oaiModelBox
        oaiPanel.addView(oaiModelBox)
        oaiPanel.addView(rowOf(
            button("Browse models.dev") { browseCatalog() },
            button("Test connection") { testOai() },
        ))
        oaiStatus = caption("")
        oaiPanel.addView(oaiStatus)
        body.addView(oaiPanel)

        // ── System One decision panel ────────────────────────────────────────
        decisionPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        decisionPanel.addView(header("Decision model (System One)"))
        decisionPanel.addView(caption(
            "Typed choice questions with calibrated probabilities — no free-text " +
                "generation. Two flavours: any Jev-compatible endpoint, or OpenRouter's " +
                "System One catalogue (Jev and friends, billed to your OpenRouter key)."
        ))
        val dRadioClef = android.widget.RadioButton(this).apply { text = "Jev-compatible endpoint (Cloudflare Clef, self-hosted)" ; isChecked = settings.dFlavor != "openrouter" }
        val dRadioOr = android.widget.RadioButton(this).apply { text = "OpenRouter — System One models"; isChecked = settings.dFlavor == "openrouter" }
        dFlavorClef = dRadioClef
        dFlavorOr = dRadioOr
        dRadioClef.setOnClickListener { dRadioOr.isChecked = false; syncDFlavor() }
        dRadioOr.setOnClickListener { dRadioClef.isChecked = false; syncDFlavor() }
        decisionPanel.addView(dRadioClef)
        decisionPanel.addView(dRadioOr)
        decisionPanel.addView(label("Model (OpenRouter flavour)"))
        val dModelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val dModelBox = EditText(this).apply {
            setText(settings.dModel.ifEmpty { DecisionPlanner.DEFAULT_OR_MODEL })
            hint = DecisionPlanner.DEFAULT_OR_MODEL
            inputType = InputType.TYPE_CLASS_TEXT
        }
        dModel = dModelBox
        dModelRow.addView(dModelBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dModelRow.addView(button("Browse") { pickOrModel() })
        dModelRow.visibility = View.GONE
        dModelRowTag = dModelRow
        decisionPanel.addView(dModelRow)
        decisionPanel.addView(label("Endpoint URL (full)"))
        val dUrlBox = EditText(this).apply {
            setText(settings.dEndpoint)
            hint = "https://api.cloudflare.com/client/v4/accounts/<id>/ai/run/@cf/cloudflare/clef-flash"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        dEndpoint = dUrlBox
        decisionPanel.addView(dUrlBox)
        decisionPanel.addView(label("API key (blank for self-hosted)"))
        val (dKeyRow, dKeyBox) = secret(settings.dKey, "workers ai token")
        dKey = dKeyBox
        decisionPanel.addView(dKeyRow)
        decisionPanel.addView(label("Confidence floor (%) — answers below this leave the file in place"))
        val dThrBox = EditText(this).apply {
            setText(settings.dThreshold.toString())
            hint = "60"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        dThreshold = dThrBox
        decisionPanel.addView(dThrBox)
        decisionPanel.addView(rowOf(button("Test connection") { testDecision() }))
        decisionStatus = caption("")
        decisionPanel.addView(decisionStatus)
        syncDFlavor()
        body.addView(decisionPanel)

        // ── Privacy + save ──────────────────────────────────────────────────
        body.addView(header("Privacy"))
        body.addView(caption(
            "Ozhungu talks only to the endpoint configured above. The folder listing goes " +
                "to that endpoint when you press Ask. Nothing else leaves the device; the " +
                "undo journal lives in app-private storage."
        ))

        body.addView(button("Save settings") {
            save()
            finish()
        }.apply { textSize = 18f })

        root.addView(body)
        setContentView(root)

        when (settings.backend) {
            "oai" -> oaiRadio.isChecked = true
            "decision" -> decisionRadio.isChecked = true
            else -> zoRadio.isChecked = true
        }
        syncPanels()
    }

    private fun rowOf(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun syncDFlavor() {
        val or = dFlavorOr.isChecked
        dModelRowTag?.visibility = if (or) View.VISIBLE else View.GONE
        dEndpoint.visibility = if (or) View.GONE else View.VISIBLE
    }

    /** GET openrouter /api/v1/models?output_modalities=decisions — public, no key. */
    private fun pickOrModel() {
        decisionStatus.text = "Loading OpenRouter System One models…"
        Thread {
            try {
                val req = Request.Builder()
                    .url("https://openrouter.ai/api/v1/models?output_modalities=decisions")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data")
                        ?: throw java.io.IOException("no data array")
                    val rows = ArrayList<Triple<String, String, String>>()
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val id = m.optString("id")
                        if (id.isEmpty()) continue
                        val name = m.optString("name", id)
                        val p = m.optJSONObject("pricing")
                        val pin = p?.optString("prompt", "") ?: ""
                        val price = if (pin.isBlank() || pin == "-1") "varies" else "$" + pin + "/M in"
                        rows.add(Triple(id, name, price))
                    }
                    rows.sortBy { it.second.lowercase() }
                    val pinned = rows.firstOrNull { it.first == DecisionPlanner.DEFAULT_OR_MODEL }
                    if (pinned != null) {
                        rows.remove(pinned)
                        rows.add(0, pinned)
                    }
                    runOnUiThread {
                        decisionStatus.text = ""
                        if (rows.isEmpty()) {
                            decisionStatus.text = "No System One models listed right now."
                        } else {
                            pickerDialog?.dismiss()
                            pickerDialog = android.app.AlertDialog.Builder(this)
                                .setTitle("System One models on OpenRouter")
                                .setItems(rows.map { "${it.second}\n${it.first} · ${it.third}" }.toTypedArray()) { _, w ->
                                    dModel.setText(rows[w].first)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { decisionStatus.text = "✗ ${e.message?.take(140)}" }
            }
        }.start()
    }

    private fun syncPanels() {
        zoPanel.visibility = if (radioZo.isChecked) View.VISIBLE else View.GONE
        oaiPanel.visibility = if (radioOai.isChecked) View.VISIBLE else View.GONE
        decisionPanel.visibility = if (radioDecision.isChecked) View.VISIBLE else View.GONE
    }

    private fun save() {
        settings.backend = when {
            radioOai.isChecked -> "oai"
            radioDecision.isChecked -> "decision"
            else -> "zo"
        }
        settings.token = zoToken.text.toString().trim()
        settings.model = zoModel.text.toString().trim()
        settings.oaiBaseUrl = oaiUrl.text.toString().trim().trimEnd('/')
        settings.oaiKey = oaiKey.text.toString().trim()
        settings.oaiModel = oaiModel.text.toString().trim()
        settings.dFlavor = if (dFlavorOr.isChecked) "openrouter" else "clef"
        settings.dModel = dModel.text.toString().trim()
        settings.dEndpoint = if (dFlavorOr.isChecked) DecisionPlanner.OPENROUTER_URL else dEndpoint.text.toString().trim()
        settings.dKey = dKey.text.toString().trim()
        settings.dThreshold = dThreshold.text.toString().trim().toIntOrNull()?.coerceIn(1, 99) ?: 60
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    /** GET /models/available on api.zo.computer (same endpoint ZoApi.listModels uses). */
    private fun zoModelsRequest(token: String): List<Pair<String, String>> {
        val req = Request.Builder()
            .url("https://api.zo.computer/models/available")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("models")
                ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = m.optString("model_name")
                if (name.isEmpty()) null else name to m.optString("label", name)
            }
        }
    }

    private fun fetchZoModels() {
        val token = zoToken.text.toString().trim()
        if (token.isBlank()) { zoStatus.text = "Paste a token first."; return }
        zoStatus.text = "Fetching models…"
        Thread {
            try {
                val models = zoModelsRequest(token)
                runOnUiThread {
                    if (models.isEmpty()) {
                        zoStatus.text = "Connected, but no models listed."
                        return@runOnUiThread
                    }
                    zoStatus.text = "OK — ${models.size} models available."
                    AlertDialog.Builder(this)
                        .setTitle("Zo models")
                        .setItems(models.map { it.second }.toTypedArray()) { _, w ->
                            zoModel.setText(models[w].first)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { zoStatus.text = "Failed: ${e.message?.take(140)}" }
            }
        }.start()
    }

    private fun testZo() {
        val token = zoToken.text.toString().trim()
        if (token.isBlank()) { zoStatus.text = "Paste a token first."; return }
        zoStatus.text = "Testing…"
        Thread {
            try {
                val n = zoModelsRequest(token).size
                runOnUiThread { zoStatus.text = "✓ Connected — $n models available." }
            } catch (e: Exception) {
                runOnUiThread { zoStatus.text = "✗ ${e.message?.take(140)}" }
            }
        }.start()
    }

    /** One-question noul round-trip: proves state+questions+answers wiring works. */
    private fun testDecision() {
        val openrouter = dFlavorOr.isChecked
        val url = if (openrouter) DecisionPlanner.OPENROUTER_URL else dEndpoint.text.toString().trim()
        if (url.isBlank()) { decisionStatus.text = "Paste an endpoint URL first."; return }
        val key = dKey.text.toString().trim()
        if (openrouter && key.isBlank()) { decisionStatus.text = "Paste your OpenRouter API key first."; return }
        decisionStatus.text = "Testing…"
        Thread {
            try {
                val body = JSONObject()
                if (openrouter) body.put("model", dModel.text.toString().trim().ifEmpty { DecisionPlanner.DEFAULT_OR_MODEL })
                body.put("state", "connection test")
                    .put(
                        "questions",
                        JSONObject().put(
                            "t",
                            JSONObject()
                                .put("type", "noul")
                                .put("instructions", "Does 1+1 equal 2?"),
                        ),
                    ).toString()
                val rb = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
                if (key.isNotBlank()) rb.header("Authorization", "Bearer $key")
                http.newCall(rb.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    runOnUiThread {
                        if (!resp.isSuccessful) {
                            decisionStatus.text = "✗ HTTP ${resp.code}: ${text.take(120)}"
                            return@runOnUiThread
                        }
                        val root = runCatching { JSONObject(text) }.getOrNull()
                        val answers = root?.optJSONObject("result")?.optJSONObject("answers")
                            ?: root?.optJSONObject("answers")
                        val p = answers?.optJSONObject("t")?.optDouble("noul")
                        decisionStatus.text = if (p != null && !p.isNaN()) "✓ Connected — p(yes)=$p"
                                              else "⚠ Reachable, but no probabilities in response."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { decisionStatus.text = "✗ ${e.message?.take(140)}" }
            }
        }.start()
    }

    /** GET {base}/models with bearer key — the standard OpenAI-compatible probe. */
    private fun testOai() {
        val base = oaiUrl.text.toString().trim().trimEnd('/')
        val key = oaiKey.text.toString().trim()
        if (base.isBlank()) { oaiStatus.text = "Paste a base URL first."; return }
        oaiStatus.text = "Testing…"
        Thread {
            try {
                val rb = Request.Builder().url("$base/models")
                if (key.isNotBlank()) rb.header("Authorization", "Bearer $key")
                http.newCall(rb.build()).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    runOnUiThread {
                        when {
                            resp.isSuccessful -> {
                                val n = runCatching {
                                    JSONObject(bodyStr).optJSONArray("data")?.length() ?: -1
                                }.getOrDefault(-1)
                                oaiStatus.text = if (n >= 0) "✓ Connected — $n models listed."
                                                 else "✓ Reachable (no model list in response)."
                            }
                            resp.code == 401 || resp.code == 403 ->
                                oaiStatus.text = "✗ Auth rejected (HTTP ${resp.code}) — check the API key."
                            resp.code == 404 ->
                                oaiStatus.text = "⚠ Reachable but /models not found — chat may still work."
                            else -> oaiStatus.text = "✗ HTTP ${resp.code}"
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { oaiStatus.text = "✗ ${e.message?.take(140)}" }
            }
        }.start()
    }

    /** Searchable provider→model picker over the models.dev catalog (ZDR-first order). */
    private fun browseCatalog() {
        oaiStatus.text = "Loading models.dev catalog…"
        Thread {
            val res = ModelsDev.loadCached(this)
            runOnUiThread {
                if (res.isFailure) {
                    oaiStatus.text = "Catalog fetch failed: ${res.exceptionOrNull()?.message?.take(120)}"
                    return@runOnUiThread
                }
                oaiStatus.text = ""
                showPicker(res.getOrThrow())
            }
        }.start()
    }

    private fun showPicker(provsAll: List<ModelsDev.CatProvider>) {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            hint = "Search providers or models"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val listV = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (420 * resources.displayMetrics.density).toInt(),
            )
        }
        container.addView(search)
        container.addView(listV)

        var shown: List<ModelsDev.CatProvider> = provsAll
        var rows: List<ModelsDev.PickerRow> = emptyList()

        fun rerender() {
            shown = ModelsDev.filter(provsAll, search.text.toString())
            rows = ModelsDev.rows(shown)
            val lines = rows.map { r ->
                when (r.kind) {
                    ModelsDev.PickerRow.Kind.PROVIDER ->
                        (if (r.zdr) "★ " else "") + r.title + "  (" + r.subtitle + ")"
                    ModelsDev.PickerRow.Kind.MODEL ->
                        "    " + r.title + (if (r.zdr) "  ★ZDR" else "")
                }
            }
            listV.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        }

        listV.setOnItemClickListener { _, _, pos, _ ->
            val r = rows.getOrNull(pos) ?: return@setOnItemClickListener
            if (r.kind == ModelsDev.PickerRow.Kind.MODEL) {
                oaiUrl.setText(shown[r.provIdx].api)
                oaiModel.setText(r.title)
                pickerDialog?.dismiss()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { rerender() }
        })

        rerender()
        pickerDialog = AlertDialog.Builder(this)
            .setTitle("Providers — ZDR-defaults (★) first")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()
        pickerDialog?.show()
        container.setPadding(pad, pad, pad, 0)
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private companion object {
        const val ID_ZO = 1
        const val ID_OAI = 2
    }
}
