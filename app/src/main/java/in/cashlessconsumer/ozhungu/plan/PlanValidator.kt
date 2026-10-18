package `in`.cashlessconsumer.ozhungu.plan

import `in`.cashlessconsumer.ozhungu.scan.ScanResult

/** Local, deterministic guardrails. The model may hallucinate; the device decides. */
object PlanValidator {

    fun validate(plan: Plan, scan: ScanResult): ValidatedPlan {
        val seenTargets = HashSet<String>()
        val valid = ArrayList<PlanAction>()
        val invalid = ArrayList<ActionError>()
        for (a in plan.actions) {
            val err = check(a, scan, seenTargets)
            if (err == null) {
                seenTargets.add(a.to)
                valid.add(a)
            } else {
                invalid.add(ActionError(a, err))
            }
        }
        return ValidatedPlan(valid, invalid)
    }

    private fun check(a: PlanAction, scan: ScanResult, seenTargets: MutableSet<String>): String? {
        if (a.op != "move" && a.op != "rename") return "unsupported op '${a.op}'"
        val from = normalizePath(a.from)
            ?: return "from must be relative and stay inside the folder ('..' or absolute paths rejected)"
        if (from !in scan.knownFrom) return "from '$from' was not in the scan"
        val to = normalizePath(a.to)
            ?: return "to must be relative and stay inside the folder ('..' or absolute paths rejected)"
        if (to == from) return "to equals from"
        if (to in scan.knownFrom) return "to would overwrite scanned file '$to'"
        if (to in scan.dirPaths) return "to is an existing folder ('$to')"
        if (to in seenTargets) return "to duplicates an earlier action's target"
        val toDir = to.substringBeforeLast('/', "")
        if (toDir in scan.knownFrom) return "to's parent is a file ('$toDir')"
        if (to.substringAfterLast('/').isBlank()) return "to has an empty file name"
        return null
    }

    /** Returns the normalized relative path, or null when the path escapes the root. */
    fun normalizePath(p: String): String? {
        val cleaned = p.trim().replace('\\', '/')
        if (cleaned.isEmpty()) return null
        if (cleaned.startsWith("/")) return null
        val segs = ArrayList<String>()
        for (seg in cleaned.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> return null
                else -> segs.add(seg)
            }
        }
        if (segs.isEmpty()) return null
        return segs.joinToString("/")
    }
}
