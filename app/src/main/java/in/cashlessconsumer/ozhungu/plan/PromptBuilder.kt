package `in`.cashlessconsumer.ozhungu.plan

import `in`.cashlessconsumer.ozhungu.scan.ScanResult
import kotlin.math.min

/** Builds the planning prompt for the Zo agent. Pure JVM, unit-tested. */
object PromptBuilder {

    const val MAX_PROMPT_ENTRIES = 300

    val SYSTEM_RULES = """
        You are the planning half of a file-organizer app on Android. The device scans a
        folder the user picked and sends the listing. You return a move/rename plan as
        JSON. The device executes it with strict local validation, so follow the contract.

        CONTRACT — reply with ONLY this JSON object, no prose, no markdown fences:
        {"plan":{"folders":["relative/folder"],"actions":[{"op":"move","from":"rel/old","to":"rel/new/path/name.ext","reason":"short why"}]}}

        RULES:
        - from MUST be copied exactly from a "p" value in the listing. Never invent paths.
        - to is the full relative destination file path (folders + final file name). Use '/' separators.
        - Never use "..", absolute paths, or leading '/'. Never move files outside this folder.
        - to must NOT be an existing path; only new destinations. No chaining (a "to" can never be another action's "from").
        - op is "move" (change folder, keep or change name) or "rename" (same folder, new name). Use plain "move" when both folder and name change.
        - Skip files that are already well organized. An empty actions array is a valid answer.
        - Group by document type and purpose (invoices, receipts, tickets, statements, screenshots, notes, media). Prefer merging into folders that already exist in the listing.
        - Derive names from visible content: text snippets ("x"), dates, mime types. Keep extensions. For cryptic names like IMG_20240101_123.jpg, rename only when content or date gives a clearly better name; keep it short, use-hyphens-lowercase.
        - Prefer fewer, broader folders over deep nesting.
        - Do not delete anything. Moves only. Every action needs a one-line reason.
    """.trimIndent()

    fun build(scan: ScanResult): String {
        val entries = scan.entries.take(MAX_PROMPT_ENTRIES)
        val listing = PlanParser.planToJsonForPrompt(scan.copy(entries = entries))
        return buildString {
            append(SYSTEM_RULES).append("\n\nFOLDER: root\n")
            append("FILES: ").append(scan.entries.size)
            val shown = min(scan.entries.size, MAX_PROMPT_ENTRIES)
            if (shown < scan.entries.size) {
                append(" (").append(scan.entries.size - shown).append(" more not shown)")
            }
            if (scan.skippedCount > 0) {
                append(" (").append(scan.skippedCount).append(" unreadable skipped)")
            }
            append("\nFOLDERS ALREADY PRESENT: ")
                .append(scan.dirPaths.joinToString(", ").ifEmpty { "none" })
            append("\n\nLISTING (JSON):\n").append(listing)
        }
    }

    fun refine(conversationContext: String, request: String): String =
        "Send the complete updated plan JSON now, same contract as before. " +
            "Updated request: ${request.trim()}. $conversationContext"
}

/** Small helper: caps a string for prompts. */
internal fun String.cap(n: Int): String = if (length <= n) this else take(n)
