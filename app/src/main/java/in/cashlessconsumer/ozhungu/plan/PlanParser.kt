package `in`.cashlessconsumer.ozhungu.plan

import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import org.json.JSONArray
import org.json.JSONObject

/** Tolerant JSON plan parser: strips code fences, accepts {plan:{...}} or flat {...}. */
object PlanParser {

    fun parse(text: String): Plan {
        val json = extractJsonObject(text)
            ?: throw IllegalArgumentException("No JSON object found in model output")
        val root = JSONObject(json)
        val obj = root.optJSONObject("plan") ?: root
        val folders = ArrayList<String>()
        val foldersArr = obj.optJSONArray("folders")
        if (foldersArr != null) {
            for (i in 0 until foldersArr.length()) {
                val f = foldersArr.optString(i, "").trim()
                if (f.isNotEmpty()) folders.add(f)
            }
        }
        val actions = ArrayList<PlanAction>()
        val actionsArr = obj.optJSONArray("actions")
            ?: throw IllegalArgumentException("Plan JSON has no actions array")
        for (i in 0 until actionsArr.length()) {
            val a = actionsArr.optJSONObject(i) ?: continue
            actions.add(
                PlanAction(
                    op = a.optString("op", "move").trim().lowercase(),
                    from = a.optString("from").trim(),
                    to = a.optString("to").trim(),
                    reason = a.optString("reason").trim(),
                ),
            )
        }
        return Plan(folders, actions)
    }

    /** Extracts the first balanced {...} block, ignoring braces inside strings. */
    fun extractJsonObject(text: String): String? {
        val cleaned = text.replace("```json", "```")
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return cleaned.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun planToJsonForPrompt(scan: ScanResult): String {
        val arr = JSONArray()
        for (e in scan.entries) {
            val o = JSONObject()
                .put("p", e.relPath)
                .put("m", e.mime)
                .put("kb", e.size / 1024)
                .put("d", e.isoDate)
            if (!e.snippet.isNullOrBlank()) o.put("x", e.snippet)
            arr.put(o)
        }
        return arr.toString()
    }
}
