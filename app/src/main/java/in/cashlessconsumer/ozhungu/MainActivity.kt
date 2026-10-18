package `in`.cashlessconsumer.ozhungu

import `in`.cashlessconsumer.ozhungu.brain.ModelsDev
import `in`.cashlessconsumer.ozhungu.brain.OaiPlanner
import `in`.cashlessconsumer.ozhungu.brain.Planner
import `in`.cashlessconsumer.ozhungu.brain.ZoPlanner
import `in`.cashlessconsumer.ozhungu.exec.Executor
import `in`.cashlessconsumer.ozhungu.exec.JournalCodec
import `in`.cashlessconsumer.ozhungu.exec.JournalEntry
import `in`.cashlessconsumer.ozhungu.plan.PlanParser
import `in`.cashlessconsumer.ozhungu.plan.Plan
import `in`.cashlessconsumer.ozhungu.plan.ValidatedPlan
import `in`.cashlessconsumer.ozhungu.plan.PlanValidator
import `in`.cashlessconsumer.ozhungu.plan.PromptBuilder
import `in`.cashlessconsumer.ozhungu.saf.SafFs
import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import `in`.cashlessconsumer.ozhungu.scan.Scanner
import android.app.Activity
import android.content.Context
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private var treeUri: Uri? = null
    private var scan: ScanResult? = null
    private var lastPrompt: String? = null
    private var vp: ValidatedPlan? = null
    private var planner: Planner? = null
    private var catalog: List<ModelsDev.CatProvider>? = null
    private var journal: List<JournalEntry> = emptyList()

    private lateinit var status: TextView
    private lateinit var rootName: TextView
    private lateinit var list: ListView
    private lateinit var pickBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var planBtn: Button
    private lateinit var applyBtn: Button
    private lateinit var undoBtn: Button
    private lateinit var refineBox: EditText
    private lateinit var refineBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var backendBtn: Button

    private var rows: List<Row> = emptyList()

    private data class Row(val text: String, val valid: Boolean, val checked: Boolean)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val box = (convertView as? CheckBox) ?: CheckBox(this@MainActivity)
            box.text = row.text
            box.isEnabled = row.valid
            box.isChecked = row.checked && row.valid
            box.setOnCheckedChangeListener { _, checked ->
                rows = rows.toMutableList().also { it[position] = row.copy(checked = checked) }
            }
            return box
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppResolver.init(application)
        settings = Settings(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        rootName = TextView(this)
        status = TextView(this)
        list = ListView(this)

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
        pickBtn = button("1. Pick folder") { pickFolder() }
        scanBtn = button("2. Scan") { doScan() }
        planBtn = button("3. Ask Zo for a plan") { doPlan() }
        applyBtn = button("4. Apply checked") { doApply() }
        undoBtn = button("Undo last apply") { doUndo() }
        backendBtn = button("Backend: Zo") { toggleBackend() }
        settingsBtn = button("⚙ Tokens / models / folder") { editSettings() }
        refineBox = EditText(this).apply {
            hint = "Refine: e.g. put invoices from March under Bills/2026"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        refineBtn = button("Refine plan") { doRefine() }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(pickBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(scanBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(planBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(applyBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(undoBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        list.adapter = adapter
        list.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        root.addView(rootName)
        root.addView(status)
        root.addView(backendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(settingsBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row1)
        root.addView(row2)
        root.addView(refineBox)
        root.addView(refineBtn)
        root.addView(list)

        setContentView(root)
        setBusy(false)

        settings.treeUri.takeIf { it.isNotBlank() }?.let {
            treeUri = Uri.parse(it)
            showRootName()
        }
        if (settings.token.isBlank()) editSettings()
        status.text = "Token and folder set? Then Scan → Ask Zo → Apply. Nothing moves until you tap Apply."
    }

    private fun showRootName() {
        val t = treeUri ?: return
        rootName.text = "Root: " + try {
            SafFs.displayName(t)
        } catch (t: Throwable) {
            t.message
        }
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        startActivityForResult(i, REQ_TREE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            treeUri = uri
            settings.treeUri = uri.toString()
            scan = null
            showRootName()
            status.text = "Folder picked. Tap Scan."
        }
    }

    private fun doScan() {
        val t = treeUri ?: run { toast("Pick a folder first"); return }
        setBusy(true)
        status.text = "Scanning…"
        Thread {
            val result = try {
                Scanner.scan(t) { n -> runOnUiThread { status.text = "Scanning… $n files" } }
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false); status.text = "Scan failed: ${e.message}" }
                return@Thread
            }
            runOnUiThread {
                scan = result
                setBusy(false)
                status.text = "${result.entries.size} files" + (if (result.skippedCount > 0) " (${result.skippedCount} unreadable dirs)" else "") + ". Ask Zo for a plan."
            }
        }.start()
    }

    private fun doPlan() {
        val s = scan ?: run { toast("Scan first"); return }
        val p = try { buildPlanner(fresh = true) } catch (e: IllegalStateException) { toast(e.message ?: ""); return }
        val prompt = PromptBuilder.build(s)
        lastPrompt = prompt
        runPlan(p, prompt)
    }

    private fun doRefine() {
        val text = refineBox.text.toString().trim()
        val base = lastPrompt ?: run { toast("No plan yet"); return }
        if (text.isEmpty()) { toast("Type a refinement first"); return }
        val p = planner ?: run { toast("No plan session yet"); return }
        runPlan(p, "$base\n\nUser refinement: $text\n\nRe-send ONLY the complete updated JSON plan (same schema), superseding the previous one.")
    }

    /** Fresh=true starts a new session (new Zo conversation / empty local history). */
    private fun buildPlanner(fresh: Boolean): Planner {
        val existing = planner
        if (!fresh && existing != null) return existing
        val p: Planner = when (settings.backend) {
            "oai" -> {
                if (settings.oaiBaseUrl.isBlank() || settings.oaiModel.isBlank())
                    throw IllegalStateException("Pick an OpenAI-compatible provider and model (⚙)")
                OaiPlanner(settings.oaiBaseUrl, settings.oaiKey, settings.oaiModel)
            }
            else -> {
                if (settings.token.isBlank()) throw IllegalStateException("Set your Zo token (⚙)")
                ZoPlanner(settings)
            }
        }
        planner = p
        return p
    }

    private fun runPlan(p: Planner, prompt: String) {
        setBusy(true)
        status.text = if (settings.backend == "oai") "${settings.oaiModel} is thinking…" else "Zo is thinking…"
        var streamed = StringBuilder()
        p.plan(prompt,
            onStatus = { s -> runOnUiThread { status.text = s ?: "Working…" } },
            onDelta = { d -> streamed.append(d) },
            onDone = { _ ->
                runOnUiThread {
                    try {
                        val plan = PlanParser.parse(streamed.toString())
                        showPlan(plan)
                    } catch (e: Throwable) {
                        status.text = "Plan parse failed: ${e.message}"
                        setBusy(false)
                    }
                }
            },
            onError = { e ->
                runOnUiThread { setBusy(false); status.text = "Planner error: ${e.message}" }
            },
        )
    }

    private fun toggleBackend() {
        settings.backend = if (settings.backend == "zo") "oai" else "zo"
        planner = null
        backendBtn.text = if (settings.backend == "zo") "Backend: Zo" else "Backend: OpenAI-compatible"
    }

    private fun showPlan(plan: `in`.cashlessconsumer.ozhungu.plan.Plan) {
        val s = scan ?: return
        val v = PlanValidator.validate(plan, s)
        vp = v
        rows = v.valid.map { Row("✓ ${it.op.uppercase()}  ${it.from}\n→ ${it.to}  (${it.reason})", true, true) } +
            v.invalid.map { Row("✗ ${it.action.from}\n→ ${it.action.to}\nrejected: ${it.error}", false, false) }
        adapter.notifyDataSetChanged()
        setBusy(false)
        status.text = "Plan: ${v.valid.size} valid, ${v.invalid.size} rejected. Uncheck anything you don't want, then Apply."
    }

    private fun doApply() {
        val v = vp ?: run { toast("No plan"); return }
        val s = scan ?: return
        val t = treeUri ?: return
        val chosen = v.valid.filterIndexed { idx, _ -> rows.getOrNull(idx)?.checked == true }
        if (chosen.isEmpty()) { toast("Nothing checked"); return }
        setBusy(true)
        status.text = "Applying 0/${chosen.size}…"
        Thread {
            val report = try {
                Executor(t).apply(Plan(emptyList(), chosen), s)
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false); status.text = "Apply failed: ${e.message}" }
                return@Thread
            }
            journal = report.applied
            try {
                File(filesDir, "last_journal.json").writeText(JournalCodec.encode(journal))
            } catch (_: Throwable) {
            }
            runOnUiThread {
                setBusy(false)
                status.text = "Applied ${report.applied.size}, failed ${report.failed.size}." +
                    report.failed.take(3).joinToString(" | ") { "${it.first.from}: ${it.second}" }
                rows = rows.map { it.copy(checked = false) }
                adapter.notifyDataSetChanged()
                undoBtn.isEnabled = journal.isNotEmpty()
                doScanSilently()
            }
        }.start()
    }

    private fun doScanSilently() {
        val t = treeUri ?: return
        Thread {
            val result = try {
                Scanner.scan(t) { }
            } catch (_: Throwable) {
                return@Thread
            }
            runOnUiThread { scan = result }
        }.start()
    }

    private fun doUndo() {
        val t = treeUri ?: return
        if (journal.isEmpty()) { toast("Nothing to undo"); return }
        setBusy(true)
        Thread {
            val (undone, failed) = try {
                Executor(t).undo(journal)
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false); status.text = "Undo failed: ${e.message}" }
                return@Thread
            }
            runOnUiThread {
                journal = emptyList()
                setBusy(false)
                status.text = "Undone $undone, failed $failed"
                undoBtn.isEnabled = false
                doScanSilently()
            }
        }.start()
    }

    private fun editSettings() {
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val backendBtn = Button(ctx).apply {
            text = "Backend: " + if (settings.backend == "oai") "OpenAI-compatible" else "Zo (/zo/ask)"
            setOnClickListener {
                val next = if (settings.backend == "oai") "zo" else "oai"
                settings.backend = next
                text = "Backend: " + if (next == "oai") "OpenAI-compatible" else "Zo (/zo/ask)"
                toast("Backend: $next")
            }
        }
        val tokenBox = EditText(ctx).apply {
            hint = "Zo access token (Settings ▸ Advanced)"
            setText(settings.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelBox = EditText(ctx).apply {
            hint = "Model (blank = API default)"
            setText(settings.model)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val oaiUrlBox = EditText(ctx).apply {
            hint = "OpenAI-compat base URL (…/v1)"
            setText(settings.oaiBaseUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val oaiKeyBox = EditText(ctx).apply {
            hint = "OpenAI-compat API key"
            setText(settings.oaiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val oaiModelBox = EditText(ctx).apply {
            hint = "OpenAI-compat model id"
            setText(settings.oaiModel)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val pickBtn = Button(ctx).apply {
            text = "Pick model from models.dev"
            setOnClickListener { pickFromModelsDev(ctx) { oaiUrlBox.setText(it.baseUrl); oaiModelBox.setText(it.modelId) } }
        }
        listOf(backendBtn, tokenBox, modelBox, oaiUrlBox, oaiKeyBox, oaiModelBox, pickBtn).forEach { layout.addView(it) }
        AlertDialog.Builder(ctx)
            .setTitle("Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                settings.token = tokenBox.text.toString().trim()
                settings.model = modelBox.text.toString().trim()
                settings.oaiBaseUrl = oaiUrlBox.text.toString().trim().trimEnd('/')
                settings.oaiKey = oaiKeyBox.text.toString().trim()
                settings.oaiModel = oaiModelBox.text.toString().trim()
                toast("Saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Fetches models.dev, filters OpenAI-compatible providers, ZDR-defaults first. */
    private fun pickFromModelsDev(ctx: Context, onPick: (ModelsDev.Pick) -> Unit) {
        status.text = "Fetching models.dev…"
        Thread {
            val res = ModelsDev.load(ctx)
            runOnUiThread {
                if (res.isFailure) {
                    status.text = "models.dev fetch failed: ${(res.exceptionOrNull()?.message ?: "").take(120)}"
                    return@runOnUiThread
                }
                val provs = res.getOrThrow()
                val lines = provs.mapIndexed { i, pr ->
                    (if (pr.zdr) "★ " else "") + "${pr.name}  (${pr.models.size} models)"
                }.toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle("Providers — ZDR-defaults (★) first")
                    .setItems(lines) { _, w ->
                        val pr = provs[w]
                        val mLines = pr.models.map { "${it.id}${if (it.zdr) "  ★ZDR" else ""}" }.toTypedArray()
                        AlertDialog.Builder(ctx)
                            .setTitle("${pr.name} — models")
                            .setItems(mLines) { _, m ->
                                onPick(ModelsDev.Pick(baseUrl = pr.api, modelId = pr.models[m].id))
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun setBusy(b: Boolean) {
        listOf(pickBtn, scanBtn, planBtn, applyBtn, undoBtn, refineBtn).forEach { it.isEnabled = !b }
        if (!b) undoBtn.isEnabled = journal.isNotEmpty()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_TREE = 41
    }
}
