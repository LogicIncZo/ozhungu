package `in`.cashlessconsumer.ozhungu.brain

import `in`.cashlessconsumer.ozhungu.plan.Plan
import `in`.cashlessconsumer.ozhungu.plan.PlanAction
import `in`.cashlessconsumer.ozhungu.plan.PlanParser
import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure core for the System One (Jev/Clef-compatible) decision-model backend.
 *
 * Mapping: the scan listing is the request "state"; each file becomes one
 * choice question ("which folder?") with a shared candidate vocabulary plus an
 * explicit __keep__ option. Answers come back as calibrated per-option
 * probabilities; argmax >= threshold (and != __keep__) becomes a move action.
 * The plan flows through the same validator/review/execute pipeline as the
 * LLM backends.
 */
object DecisionModel {

    /** Explicit "leave it where it is" choice — decision models always pick an option. */
    const val KEEP = "__keep__"

    /** Workers AI accepts up to 64 questions per request. */
    const val MAX_QUESTIONS = 64

    /** Vocabulary cap; fewer options calibrate better. */
    const val MAX_FOLDERS = 24

    data class DQuestion(val id: String, val instructions: String, val criteria: Map<String, String>)
    data class Batch(val questions: List<DQuestion>)
    data class ChoiceAnswer(val choice: String, val best: Double)

    private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
    private val VIDEO = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v")
    private val AUDIO = setOf("mp3", "m4a", "wav", "ogg", "flac", "opus", "amr", "aac")
    private val DOCS = setOf(
        "pdf", "doc", "docx", "odt", "rtf", "txt", "md", "csv",
        "xls", "xlsx", "ods", "ppt", "pptx", "odp",
    )
    private val ARCHIVES = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    private val MONEY_HINT = Regex("(bill|invoice|receipt|statement|payslip|salary)", RegexOption.IGNORE_CASE)

    private val CANONICAL: Map<String, String> = linkedMapOf(
        "Screenshots" to "phone screenshots (filenames like Screenshot_20240101…)",
        "Pictures" to "photos and images",
        "Documents" to "pdf and office documents",
        "Bills" to "invoices, bills, receipts, statements, payslips",
        "Music" to "audio files",
        "Movies" to "videos",
        "Archives" to "compressed archives (zip, tar…)",
        "APKs" to "android app installers",
        "Misc" to "anything that fits nowhere else",
    )

    val INSTRUCTIONS: String =
        "Destination folder for this file inside the scanned root, by its name and kind. " +
            "Pick the best fit, or $KEEP when unsure."

    /**
     * Ordered candidate vocabulary. [picked] (user-chosen, any depth) comes first —
     * they override the auto set; when [allowAuto] is false the canonical buckets are
     * suppressed entirely (only picked folders + existing root dirs + KEEP). Recursive
     * picked paths ("a/b") are allowed as targets; root-level dirs follow picked entries.
     */
    fun folders(
        scan: ScanResult,
        picked: Set<String> = emptySet(),
        allowAuto: Boolean = true,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        picked.filter { it.isNotBlank() }.sorted().forEach { dir ->
            if (dir !in out) out[dir] = "you picked this folder"
        }
        if (!allowAuto) {
            val capped = out.entries.take(MAX_FOLDERS - 1).associate { it.key to it.value }
            return LinkedHashMap(capped).apply { put(KEEP, "leave the file where it is (no good option / unsure)") }
        }
        scan.dirPaths.filter { !it.contains('/') }.sorted().forEach { dir ->
            if (dir !in out) out[dir] = "already exists in this folder"
        }
        val has = fun(exts: Set<String>) = scan.entries.any { it.relPath.substringAfterLast('.').lowercase() in exts }
        val screenshots = scan.entries.any {
            it.relPath.substringAfterLast('/').startsWith("Screenshot", ignoreCase = true)
        }
        val bills = scan.entries.any {
            val n = it.relPath.substringAfterLast('/')
            MONEY_HINT.containsMatchIn(n)
        }
        if (screenshots) out["Screenshots"] = CANONICAL.getValue("Screenshots")
        if (has(IMAGES)) out["Pictures"] = CANONICAL.getValue("Pictures")
        if (has(DOCS)) out["Documents"] = CANONICAL.getValue("Documents")
        if (bills && has(DOCS)) out["Bills"] = CANONICAL.getValue("Bills")
        if (has(AUDIO)) out["Music"] = CANONICAL.getValue("Music")
        if (has(VIDEO)) out["Movies"] = CANONICAL.getValue("Movies")
        if (has(ARCHIVES)) out["Archives"] = CANONICAL.getValue("Archives")
        if (scan.entries.any { it.relPath.substringAfterLast('.').lowercase() == "apk" }) {
            out["APKs"] = CANONICAL.getValue("APKs")
        }
        out["Misc"] = CANONICAL.getValue("Misc")
        val capped = out.entries.take(MAX_FOLDERS - 1).associate { it.key to it.value }
        return LinkedHashMap(capped).apply { put(KEEP, "leave the file where it is (no good option / unsure)") }
    }

    fun batches(scan: ScanResult, folders: Map<String, String>): List<Batch> {
        val qs = scan.entries.mapIndexed { i, _ ->
            DQuestion("q$i", INSTRUCTIONS, folders)
        }
        if (qs.isEmpty()) return emptyList()
        return qs.chunked(MAX_QUESTIONS).map { Batch(it) }
    }

    /** The state is the same listing JSON the LLM backends see. */
    fun stateJson(scan: ScanResult): String = PlanParser.planToJsonForPrompt(scan)

    /** Raw Jev-compatible envelope (Cloudflare Workers AI, self-hosted). */
    fun requestJson(batch: Batch, state: String): String =
        envelope(null, batch, state)

    /** OpenRouter Decisions API envelope: adds the required "model" field. */
    fun openRouterRequestJson(model: String, batch: Batch, state: String): String =
        envelope(model, batch, state)

    private fun envelope(model: String?, batch: Batch, state: String): String {
        val questions = JSONObject()
        for (q in batch.questions) {
            questions.put(
                q.id,
                JSONObject()
                    .put("type", "choice")
                    .put("instructions", q.instructions)
                    .put("criteria", JSONObject(q.criteria)),
            )
        }
        val body = JSONObject().put("state", state).put("questions", questions)
        if (!model.isNullOrBlank()) body.put("model", model)
        return body.toString()
    }

    /** Tolerant parse: bare Jev `answers` or Workers-AI `{result:{answers}}` envelope. */
    fun parseAnswers(raw: String): Map<String, ChoiceAnswer> {
        var root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        if (root.has("result")) {
            val inner = root.optJSONObject("result") ?: return emptyMap()
            root = inner.optJSONObject("result") ?: inner
        }
        val answers = root.optJSONObject("answers") ?: return emptyMap()
        val out = HashMap<String, ChoiceAnswer>()
        val keys = answers.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val a = answers.optJSONObject(id) ?: continue
            if (a.optString("type") != "choice") continue
            val choice = a.optString("choice")
            if (choice.isEmpty()) continue
            val probs = a.optJSONObject("probabilities")
            val best = (probs?.optDouble(choice)?.takeIf { !it.isNaN() })
                ?: (a.optDouble("confidence").takeIf { !it.isNaN() && it in 0.0..1.0 })
                ?: -1.0
            out[id] = ChoiceAnswer(choice, best)
        }
        return out
    }

    /** @return move actions (deduped targets) + count of files deliberately left in place. */
    fun decide(
        scan: ScanResult,
        answers: Map<String, ChoiceAnswer>,
        threshold: Double,
    ): Pair<List<PlanAction>, Int> {
        val actions = ArrayList<PlanAction>()
        var skipped = 0
        val used = HashSet<String>()
        for ((i, e) in scan.entries.withIndex()) {
            val a = answers["q$i"]
            val dir = cleanPath(a?.choice ?: KEEP)
            val currentDir = e.relPath.substringBeforeLast('/', "")
            if (a == null || a.choice == KEEP || dir == null || dir == currentDir || a.best < threshold) {
                skipped++
                continue
            }
            val name = e.relPath.substringAfterLast('/')
            var target = "$dir/$name"
            var n = 2
            while (!used.add(target.lowercase())) {
                target = "$dir/${dedupeName(name, n)}"
                n++
            }
            actions.add(
                PlanAction(
                    op = "move",
                    from = e.relPath,
                    to = target,
                    reason = "decision model: ${a.choice} p=${"%.2f".format(a.best)}",
                ),
            )
        }
        return actions to skipped
    }

    fun planJson(actions: List<PlanAction>): String {
        val arr = JSONArray()
        for (a in actions) {
            arr.put(
                JSONObject()
                    .put("op", a.op)
                    .put("from", a.from)
                    .put("to", a.to)
                    .put("reason", a.reason),
            )
        }
        return JSONObject().put("folders", JSONArray()).put("actions", arr).toString()
    }

    fun toPlan(actions: List<PlanAction>): Plan = Plan(emptyList(), actions)

    /** Same path grammar the validator enforces: relative, no .., no empties. */
    fun cleanPath(p: String): String? {
        val segs = ArrayList<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> return null
                else -> segs.add(seg)
            }
        }
        if (segs.isEmpty()) return null
        return segs.joinToString("/")
    }

    private fun dedupeName(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }
}
