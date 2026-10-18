package `in`.cashlessconsumer.ozhungu

import `in`.cashlessconsumer.ozhungu.brain.DecisionPlanner
import `in`.cashlessconsumer.ozhungu.brain.ModelsDev
import `in`.cashlessconsumer.ozhungu.brain.OaiPlanner
import `in`.cashlessconsumer.ozhungu.brain.Planner
import `in`.cashlessconsumer.ozhungu.brain.ZoPlanner
import `in`.cashlessconsumer.ozhungu.exec.Executor
import `in`.cashlessconsumer.ozhungu.exec.JournalCodec
import `in`.cashlessconsumer.ozhungu.exec.JournalEntry
import `in`.cashlessconsumer.ozhungu.plan.Plan
import `in`.cashlessconsumer.ozhungu.plan.PlanParser
import `in`.cashlessconsumer.ozhungu.plan.PlanValidator
import `in`.cashlessconsumer.ozhungu.plan.PromptBuilder
import `in`.cashlessconsumer.ozhungu.plan.ValidatedPlan
import `in`.cashlessconsumer.ozhungu.saf.SafFs
import `in`.cashlessconsumer.ozhungu.scan.Categories
import `in`.cashlessconsumer.ozhungu.scan.ScanEntry
import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import `in`.cashlessconsumer.ozhungu.scan.Scanner
import `in`.cashlessconsumer.ozhungu.ui.UiKit
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Files-by-Google-style shell: Browse tab (categories + recent) and Clean tab
 *  (the agentic organise flow). All moves stay behind PlanValidator + journal. */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private var treeUri: Uri? = null
    private var scan: ScanResult? = null
    private var lastPrompt: String? = null
    private var vp: ValidatedPlan? = null
    private var planner: Planner? = null
    private var catalog: List<ModelsDev.CatProvider>? = null
    private var journal: List<JournalEntry> = emptyList()
    private val selection = LinkedHashSet<String>()

    private lateinit var content: FrameLayout
    private lateinit var browsePage: ScrollView
    private lateinit var catPage: ScrollView
    private lateinit var cleanPage: ScrollView
    private lateinit var catGrid: LinearLayout
    private lateinit var recentWrap: LinearLayout
    private lateinit var catListWrap: LinearLayout
    private lateinit var catTitle: TextView
    private lateinit var cleanWrap: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var status: TextView
    private lateinit var toolbarTitle: TextView
    private lateinit var applyBtn: Button
    private lateinit var undoBtn: Button
    private lateinit var bottomBar: LinearLayout
    private lateinit var tabBrowse: TextView
    private lateinit var tabClean: TextView
    private lateinit var refreshBtn: View
    private lateinit var refineBox: EditText

    private var rows: List<Row> = emptyList()
    private var showingCat: Categories.Cat? = null

    private data class Row(val op: String, val from: String, val to: String, val note: String, val valid: Boolean, var checked: Boolean)

    private fun dp(v: Int): Int = UiKit.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppResolver.init(application)
        settings = Settings(this)

        settings.treeUri.takeIf { it.isNotBlank() }?.let { treeUri = Uri.parse(it) }

        buildUi()
        setContentView(content)
        refreshBackendLabel()
        showTab(0)
        renderBrowse()
        renderClean()
        status("Nothing moves until you tap Apply. Every apply can be undone.")
    }

    // ── UI construction ─────────────────────────────────────────────────────────

    private fun buildUi() {
        toolbarTitle = UiKit.label(this, "Ozhungu", 20f, UiKit.INK, bold = true)

        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        refreshBtn = TextView(this).apply {
            text = "⟳"
            textSize = 20f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { doScan(silent = false) }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(4), dp(10))
            addView(toolbarTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(refreshBtn)
            addView(gear)
        }

        buildBrowsePage()
        buildCatPage()
        buildCleanPage()

        content = FrameLayout(this).apply { setBackgroundColor(UiKit.PAGE_BG) }
        content.addView(browsePage)
        content.addView(catPage)
        content.addView(cleanPage)

        status = UiKit.label(this, "", 12f, UiKit.DIM)
        status.setPadding(dp(16), dp(6), dp(16), dp(6))

        undoBtn = UiKit.ghostButton(this, "Undo")
        applyBtn = UiKit.primaryButton(this, "Apply")
        applyBtn.setOnClickListener { doApply() }
        undoBtn.setOnClickListener { doUndo() }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(12), dp(4), dp(12), dp(10))
            addView(undoBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(applyBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(8), 0, 0, 0)
            })
        }

        tabBrowse = tabLabel("Browse")
        tabClean = tabLabel("Clean up")
        tabBrowse.setOnClickListener { showTab(0) }
        tabClean.setOnClickListener { showTab(1) }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(10))
            addView(tabBrowse, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tabClean, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.PAGE_BG)
            addView(topBar)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status)
            addView(UiKit.divider(this@MainActivity))
            addView(bottomBar)
            addView(tabBar)
        }
        content = FrameLayout(this).apply {
            setBackgroundColor(UiKit.PAGE_BG)
            addView(page)
        }
        bottomBar.visibility = View.GONE
    }

    private fun tabLabel(t: String): TextView = UiKit.label(this, t, 14f, UiKit.DIM, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun buildBrowsePage() {
        catGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        selCount = UiKit.label(this, "", 13f, UiKit.PRIMARY, bold = true)
        val selGo = UiKit.chip(this, "Clean up ▸")
        selGo.setOnClickListener { showTab(1); renderClean() }
        val selClear = UiKit.label(this, "Clear", 13f, UiKit.DIM)
        selClear.setOnClickListener { selection.clear(); renderBrowse() }
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.card(this@MainActivity, 14f, UiKit.PRIMARY_DIM)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(selCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(selClear)
            addView(selGo)
        }
        selBar.visibility = View.GONE

        recentWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val recentCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.card(this@MainActivity)
            addView(recentWrap)
        }

        browsePage = ScrollView(this).apply {
            setBackgroundColor(UiKit.PAGE_BG)
            setPadding(dp(12), dp(4), dp(12), dp(12))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(selBar)
                    addView(sectionTitle("Categories"))
                    addView(UiKit.cardShell(this@MainActivity, catGrid, padDp = 8f))
                    addView(sectionTitle("Recent files"))
                    addView(recentCard)
                },
            )
        }
    }

    private fun buildCatPage() {
        val back = UiKit.label(this, "←  Browse", 14f, UiKit.PRIMARY, bold = true)
        back.setPadding(dp(4), dp(10), dp(4), dp(10))
        back.setOnClickListener { showTab(0); showingCat = null }
        catTitle = UiKit.label(this, "", 18f, UiKit.INK, bold = true)
        catListWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.card(this@MainActivity)
            addView(catListWrap)
        }
        catPage = ScrollView(this).apply {
            setBackgroundColor(UiKit.PAGE_BG)
            setPadding(dp(12), dp(4), dp(12), dp(12))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(back)
                    addView(catTitle)
                    addView(card)
                },
            )
        }
    }

    private fun buildCleanPage() {
        refineBox = EditText(this).apply {
            hint = "Refine — e.g. put invoices from March under Bills/2026"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 14f
            background = UiKit.card(this@MainActivity, 12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        cleanWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cleanPage = ScrollView(this).apply {
            setBackgroundColor(UiKit.PAGE_BG)
            setPadding(dp(12), dp(4), dp(12), dp(96))
            addView(cleanWrap)
        }
    }

    private fun sectionTitle(t: String): TextView =
        UiKit.label(this, t, 16f, UiKit.INK, bold = true).apply {
            setPadding(dp(4), dp(14), dp(4), dp(8))
        }

    private fun showTab(tab: Int) {
        browsePage.visibility = if (tab == 0 && showingCat == null) View.VISIBLE else View.GONE
        catPage.visibility = if (tab == 0 && showingCat != null) View.VISIBLE else View.GONE
        cleanPage.visibility = if (tab == 1) View.VISIBLE else View.GONE
        bottomBar.visibility = if (tab == 1) View.VISIBLE else View.GONE
        tabBrowse.setTextColor(if (tab == 0) UiKit.PRIMARY else UiKit.DIM)
        tabClean.setTextColor(if (tab == 1) UiKit.PRIMARY else UiKit.DIM)
    }

    // ── Browse rendering ────────────────────────────────────────────────────────

    private fun renderBrowse() {
        val s = scan
        selBar.visibility = if (selection.isEmpty()) View.GONE else View.VISIBLE
        selCount.text = "${selection.size} selected"
        catGrid.removeAllViews()
        val counts = s?.let { Categories.summarize(it) }?.associate { it.cat to it } ?: emptyMap()
        Categories.Cat.entries.chunked(3).forEach { triple ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            triple.forEach { cat ->
                val sum = counts[cat]
                val n = sum?.count ?: 0
                val tint = (0x1A shl 24) or cat.color.toInt()
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = UiKit.card(this@MainActivity, 12f)
                    setPadding(dp(6), dp(12), dp(6), dp(12))
                    addView(
                        UiKit.circle(this@MainActivity, cat.emoji, tint, 42).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                                setMargins(0, 0, 0, dp(6))
                            }
                        },
                    )
                    addView(UiKit.label(this@MainActivity, n.toString(), 15f, if (n == 0) UiKit.DIM else UiKit.INK, bold = true).apply { gravity = Gravity.CENTER })
                    addView(UiKit.label(this@MainActivity, cat.label, 11f, UiKit.DIM).apply { gravity = Gravity.CENTER })
                    setOnClickListener { openCategory(cat) }
                }
                row.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            catGrid.addView(row)
        }

        recentWrap.removeAllViews()
        val recent = s?.let { Categories.recent(it, 30) } ?: emptyList()
        if (recent.isEmpty()) {
            val empty = UiKit.label(this, if (s == null) "Pick a folder to see files here." else "No files found.", 13f, UiKit.DIM)
            empty.setPadding(dp(16), dp(18), dp(16), dp(18))
            recentWrap.addView(empty)
        } else {
            recent.forEachIndexed { i, e -> recentWrap.addView(recentRow(e)); if (i < recent.size - 1) recentWrap.addView(UiKit.divider(this)) }
        }
    }

    private fun recentRow(e: ScanEntry): LinearLayout {
        val cat = Categories.categorize(e.relPath, e.mime)
        val tint = (0x1A shl 24) or cat.color.toInt()
        val date = SimpleDateFormat("d MMM yy", Locale.getDefault()).format(Date(e.mtime))
        val row = UiKit.fileRow(this, cat.emoji, tint, e.relPath.substringAfterLast('/'),
            "${cat.label} · ${UiKit.humanBytes(e.size)} · $date")
        fun paint() {
            row.background = if (e.relPath in selection) UiKit.card(this, 4f, UiKit.PRIMARY_DIM) else null
        }
        paint()
        row.setOnClickListener {
            if (selection.remove(e.relPath)) paint() else { selection.add(e.relPath); paint() }
            selBar.visibility = if (selection.isEmpty()) View.GONE else View.VISIBLE
            selCount.text = "${selection.size} selected"
        }
        return row
    }

    private fun openCategory(cat: Categories.Cat) {
        val s = scan ?: return
        showingCat = cat
        catTitle.text = cat.label
        catListWrap.removeAllViews()
        val items = Categories.entriesOf(s, cat).sortedByDescending { it.mtime }
        if (items.isEmpty()) {
            val empty = UiKit.label(this, "No ${cat.label.lowercase()} files.", 13f, UiKit.DIM)
            empty.setPadding(dp(16), dp(18), dp(16), dp(18))
            catListWrap.addView(empty)
        } else {
            items.forEachIndexed { i, e ->
                val tint = (0x1A shl 24) or cat.color.toInt()
                val date = SimpleDateFormat("d MMM yy", Locale.getDefault()).format(Date(e.mtime))
                catListWrap.addView(UiKit.fileRow(this, cat.emoji, tint, e.relPath.substringAfterLast('/'),
                    "${UiKit.humanBytes(e.size)} · $date · ${e.relPath.substringBeforeLast('/', "")}"))
                if (i < items.size - 1) catListWrap.addView(UiKit.divider(this))
            }
        }
        showTab(0)
    }

    // ── Clean rendering ─────────────────────────────────────────────────────────

    private fun renderClean() {
        cleanWrap.removeAllViews()
        if (treeUri == null) {
            val hero = UiKit.label(this, "Organise your files", 22f, UiKit.INK, bold = true)
            val sub = UiKit.label(this, "Ozhungu scans a folder, asks your model for a move plan, and applies only what you approve. Every move is undoable.", 14f, UiKit.DIM)
            val pick = UiKit.primaryButton(this, "Choose a folder") { pickFolder() }
            pick.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            cleanWrap.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(40), 0, dp(8)) })
            cleanWrap.addView(sub)
            cleanWrap.addView(pick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(24), 0, 0) })
            setBusy(false)
            return
        }
        val s = scan
        if (s == null) {
            val card = UiKit.label(this, "Folder: ${rootDisplayName()}\n\nScan it to see what can be organised.", 14f, UiKit.INK)
            card.setPadding(dp(16), dp(16), dp(16), dp(16))
            cleanWrap.addView(UiKit.cardShell(this, card))
            val scanBtn = UiKit.primaryButton(this, "Scan folder") { doScan(silent = false) }
            scanBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            cleanWrap.addView(scanBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) })
            setBusy(false)
            return
        }
        if (vp == null || rows.isEmpty()) {
            val title = UiKit.label(this, "${s.entries.size} files${if (selection.isEmpty()) "" else " · ${selection.size} selected"}", 18f, UiKit.INK, bold = true)
            val sub = UiKit.label(this, backendLabel() + if (selection.isEmpty()) "" else "\nSuggestions will cover the selected files only.", 13f, UiKit.DIM)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, 0) })
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            cleanWrap.addView(UiKit.cardShell(this, card))
            val planBtn = UiKit.primaryButton(this, "Get suggestions") { doPlan() }
            planBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            cleanWrap.addView(planBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) })
            val note = UiKit.label(this, "Moves and renames only. Nothing moves until you tap Apply — and every apply can be undone.", 12f, UiKit.DIM)
            note.setPadding(dp(4), dp(10), dp(4), 0)
            cleanWrap.addView(note)
            setBusy(false)
            return
        }
        renderPlan()
    }

    private fun renderPlan() {
        val v = vp ?: return
        val head = UiKit.label(this, "Suggested moves — ${v.valid.size} valid, ${v.invalid.size} rejected", 16f, UiKit.INK, bold = true)
        head.setPadding(dp(4), dp(2), dp(4), dp(8))
        cleanWrap.addView(head)

        val refineCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        refineCard.addView(refineBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val refineBtn = UiKit.ghostButton(this, "Refine plan") { doRefine() }
        refineBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        refineCard.addView(refineBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) })
        cleanWrap.addView(refineCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(12)) })

        rows.forEach { row -> cleanWrap.addView(planRow(row)) }
        syncApply()
        setBusy(false)
    }

    private fun planRow(row: Row): View {
        val box = CheckBox(this).apply {
            isChecked = row.checked && row.valid
            isEnabled = row.valid
            setOnCheckedChangeListener { _, c -> row.checked = c; syncApply() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        val title = UiKit.label(this, "${row.op.uppercase()}  ${row.from}", 13f, if (row.valid) UiKit.INK else UiKit.BAD, bold = row.valid)
        val sub = UiKit.label(this, if (row.valid) "→  ${row.to}" else "rejected: ${row.note}", 12f, if (row.valid) UiKit.DIM else UiKit.BAD)
        val reason = if (row.valid) UiKit.label(this, row.note, 11f, UiKit.DIM) else null
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(sub)
            if (reason != null) addView(reason)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val row0 = "row"
        return LinearLayout(this).apply {
            tag = row0
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            background = UiKit.card(this@MainActivity, 12f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(box)
            addView(col)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(8))
            }
        }
    }

    private fun syncApply() {
        val n = rows.count { it.checked && it.valid }
        applyBtn.text = if (n == 0) "Apply" else "Apply $n"
        applyBtn.isEnabled = n > 0 && !busy
        undoBtn.isEnabled = journal.isNotEmpty() && !busy
    }

    private var busy = false

    // ── Actions (unchanged pipeline) ────────────────────────────────────────────

    private fun status(msg: String) { status.text = msg }

    private fun rootDisplayName(): String = try {
        SafFs.displayName(treeUri!!) ?: "folder"
    } catch (t: Throwable) {
        t.message ?: "folder"
    }

    private fun backendLabel(): String = when (settings.backend) {
        "oai" -> "Backend: OpenAI-compatible · ${settings.oaiModel.ifBlank { "(no model yet)" }}"
        "decision" -> "Backend: System One decision · floor ${settings.dThreshold}%"
        else -> "Backend: Zo agent · ${settings.model.ifBlank { "default model" }}"
    }

    private fun refreshBackendLabel() { /* backend chip is drawn in renderClean() */ }

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
            scan = null; vp = null; rows = emptyList(); selection.clear()
            showTab(1)
            renderBrowse(); renderClean()
            doScan(silent = false)
        }
    }

    private fun doScan(silent: Boolean) {
        val t = treeUri ?: run { toast("Pick a folder first"); return }
        if (!silent) { setBusy(true); status("Scanning…") }
        Thread {
            val result = try {
                Scanner.scan(t) { n -> runOnUiThread { status("Scanning… $n files") } }
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false); status("Scan failed: ${e.message}") }
                return@Thread
            }
            runOnUiThread {
                scan = result
                selection.clear()
                vp = null; rows = emptyList()
                renderBrowse()
                renderClean()
                setBusy(false)
                status("${result.entries.size} files" + (if (result.skippedCount > 0) " (${result.skippedCount} unreadable dirs)" else ""))
            }
        }.start()
    }

    /** The plan covers the selection (if any) — everything otherwise. */
    private fun scopedScan(): ScanResult = Categories.restrict(scan!!, selection)

    private fun doPlan() {
        val s = scopedScan()
        if (s.entries.isEmpty()) { toast("Nothing selected"); return }
        val p = try { buildPlanner(fresh = true, scan = s) } catch (e: IllegalStateException) { toast(e.message ?: ""); return }
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
    private fun buildPlanner(fresh: Boolean, scan: ScanResult? = null): Planner {
        val existing = planner
        if (!fresh && existing != null) return existing
        val p: Planner = when (settings.backend) {
            "oai" -> {
                if (settings.oaiBaseUrl.isBlank() || settings.oaiModel.isBlank())
                    throw IllegalStateException("Pick an OpenAI-compatible provider and model (⚙)")
                OaiPlanner(settings.oaiBaseUrl, settings.oaiKey, settings.oaiModel)
            }
            "decision" -> {
                if (settings.dEndpoint.isBlank())
                    throw IllegalStateException("Set the decision endpoint (⚙)")
                DecisionPlanner(settings.dEndpoint, settings.dKey, settings.dThreshold,
                    scan ?: throw IllegalStateException("Scan first"))
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
        status(when (settings.backend) {
            "oai" -> "${settings.oaiModel} is thinking…"
            "decision" -> "Asking the decision model…"
            else -> "Zo is thinking…"
        })
        var streamed = StringBuilder()
        p.plan(prompt,
            onStatus = { s -> runOnUiThread { status(s ?: "Working…") } },
            onDelta = { d -> streamed.append(d) },
            onDone = { _ ->
                runOnUiThread {
                    try {
                        showPlan(PlanParser.parse(streamed.toString()))
                    } catch (e: Throwable) {
                        status("Plan parse failed: ${e.message}")
                        setBusy(false)
                    }
                }
            },
            onError = { e ->
                runOnUiThread { setBusy(false); status("Planner error: ${e.message}") }
            },
        )
    }

    private fun showPlan(plan: Plan) {
        val s = scopedScan()
        val v = PlanValidator.validate(plan, s)
        vp = v
        rows = v.valid.map { Row(it.op, it.from, it.to, it.reason, true, true) } +
            v.invalid.map { Row(it.action.op, it.action.from, it.action.to, it.error, false, false) }
        showTab(1)
        renderPlan()
        status("Plan: ${v.valid.size} valid, ${v.invalid.size} rejected. Uncheck anything you don't want, then Apply.")
    }

    private fun doApply() {
        val v = vp ?: return
        val s = scopedScan()
        val t = treeUri ?: return
        val chosen = v.valid.filterIndexed { idx, _ -> rows.getOrNull(idx)?.checked == true }
        if (chosen.isEmpty()) { toast("Nothing checked"); return }
        setBusy(true)
        status("Applying 0/${chosen.size}…")
        Thread {
            val report = try {
                Executor(t).apply(Plan(emptyList(), chosen), s)
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false); status("Apply failed: ${e.message}") }
                return@Thread
            }
            journal = report.applied
            try {
                File(filesDir, "last_journal.json").writeText(JournalCodec.encode(journal))
            } catch (_: Throwable) {
            }
            runOnUiThread {
                setBusy(false)
                status("Applied ${report.applied.size}, failed ${report.failed.size}." +
                    report.failed.take(3).joinToString(" | ") { "${it.first.from}: ${it.second}" })
                undoBtn.isEnabled = journal.isNotEmpty()
                doScan(silent = true)
            }
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
                runOnUiThread { setBusy(false); status("Undo failed: ${e.message}") }
                return@Thread
            }
            runOnUiThread {
                journal = emptyList()
                setBusy(false)
                status("Undone $undone, failed $failed")
                undoBtn.isEnabled = false
                doScan(silent = true)
            }
        }.start()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        refreshBtn.isEnabled = !b
        applyBtn.isEnabled = !b && rows.any { it.checked && it.valid }
        undoBtn.isEnabled = !b && journal.isNotEmpty()
    }

    override fun onResume() {
        super.onResume()
        if (planner != null && loadedBackend != settings.backend) planner = null
        loadedBackend = settings.backend
        renderBrowse(); renderClean()
    }

    private var loadedBackend: String = ""

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_TREE = 41
    }
}
