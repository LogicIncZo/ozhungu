package `in`.cashlessconsumer.ozhungu.exec

import `in`.cashlessconsumer.ozhungu.plan.Plan
import `in`.cashlessconsumer.ozhungu.plan.PlanAction
import `in`.cashlessconsumer.ozhungu.plan.PlanValidator
import `in`.cashlessconsumer.ozhungu.saf.SafFs
import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import android.net.Uri

/** Applies a validated plan over SAF, journaling every success for undo. */
class Executor(treeUri: Uri) {

    private val saf = SafFs(treeUri)

    data class Report(
        val applied: List<JournalEntry>,
        val failed: List<Pair<PlanAction, String>>,
    )

    fun apply(plan: Plan, scan: ScanResult): Report {
        val validated = PlanValidator.validate(plan, scan)
        val applied = ArrayList<JournalEntry>()
        val failed = ArrayList<Pair<PlanAction, String>>()

        for (a in validated.valid) {
            val src = scan.uriByPath[a.from]
            if (src == null) {
                failed.add(a to "source missing (stale scan?)")
                continue
            }
            try {
                val toDir = a.to.substringBeforeLast('/', missingDelimiterValue = "")
                val toName = a.to.substringAfterLast('/')
                val dstParent = if (toDir.isEmpty()) saf.rootDocUri() else saf.ensurePath(toDir, create = true)
                if (dstParent == null) {
                    failed.add(a to "could not create destination folder '$toDir'")
                    continue
                }
                val doc = Uri.parse(src)
                var result = saf.move(doc, dstParent)
                if (result != null && saf.nameOf(result) != toName) {
                    result = saf.rename(result, toName) ?: result
                }
                if (result == null) {
                    failed.add(a to "provider refused the move")
                    continue
                }
                applied.add(
                    JournalEntry(
                        op = a.op,
                        fromRel = a.from,
                        toRel = a.to,
                        resultUri = result.toString(),
                        ts = System.currentTimeMillis(),
                    ),
                )
            } catch (t: Throwable) {
                failed.add(a to (t.message ?: t.javaClass.simpleName))
            }
        }
        return Report(applied, failed)
    }

    /** Reverses the journal newest-first. Returns (undone count, failed descriptions). */
    fun undo(entries: List<JournalEntry>): Pair<Int, List<String>> {
        var undone = 0
        val failed = ArrayList<String>()
        for (e in entries.reversed()) {
            try {
                val doc = Uri.parse(e.resultUri)
                val toParent = saf.parentOfRel(e.toRel, create = false)
                    ?: throw IllegalStateException("destination folder gone")
                val fromParent = saf.parentOfRel(e.fromRel, create = true)
                    ?: throw IllegalStateException("original folder gone")
                val moved = saf.move(doc, fromParent)
                    ?: throw IllegalStateException("move back refused")
                val fromName = e.fromRel.substringAfterLast('/')
                if (saf.nameOf(moved) != fromName) {
                    saf.rename(moved, fromName)
                }
                undone++
            } catch (t: Throwable) {
                failed.add("${e.toRel}: ${t.message ?: t.javaClass.simpleName}")
            }
        }
        return undone to failed
    }
}
