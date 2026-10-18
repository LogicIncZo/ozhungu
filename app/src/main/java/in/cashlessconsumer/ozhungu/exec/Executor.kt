package `in`.cashlessconsumer.ozhungu.exec

import `in`.cashlessconsumer.ozhungu.plan.Plan
import `in`.cashlessconsumer.ozhungu.plan.PlanAction
import `in`.cashlessconsumer.ozhungu.plan.PlanValidator
import `in`.cashlessconsumer.ozhungu.saf.Fs
import `in`.cashlessconsumer.ozhungu.scan.ScanResult

/** Applies a validated plan over any [Fs] backend, journaling every success for undo. */
class Executor(private val fs: Fs) {

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
                val dstParent = fs.ensureDir(toDir, create = true)
                if (dstParent == null) {
                    failed.add(a to "could not create destination folder '$toDir'")
                    continue
                }
                var result = fs.move(src, dstParent)
                if (result != null && fs.nameOf(result) != toName) {
                    result = fs.rename(result, toName) ?: result
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
                        resultUri = result,
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
                val toParent = fs.ensureDir(e.toRel.substringBeforeLast('/', missingDelimiterValue = ""), create = false)
                    ?: throw IllegalStateException("destination folder gone")
                if (fs.nameOf(e.resultUri) == null) throw IllegalStateException("moved file no longer exists")
                val fromParent = fs.ensureDir(e.fromRel.substringBeforeLast('/', missingDelimiterValue = ""), create = true)
                    ?: throw IllegalStateException("original folder gone")
                val moved = fs.move(e.resultUri, fromParent)
                    ?: throw IllegalStateException("move back refused")
                val fromName = e.fromRel.substringAfterLast('/')
                if (fs.nameOf(moved) != fromName) {
                    fs.rename(moved, fromName)
                }
                undone++
            } catch (t: Throwable) {
                failed.add("${e.toRel}: ${t.message ?: t.javaClass.simpleName}")
            }
        }
        return undone to failed
    }
}
