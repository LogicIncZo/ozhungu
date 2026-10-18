package `in`.cashlessconsumer.ozhungu.exec

import org.json.JSONArray
import org.json.JSONObject

/** One executed action, enough to reverse it: source rel path + resulting document URI. */
data class JournalEntry(
    val op: String,
    val fromRel: String,
    val toRel: String,
    val resultUri: String,
    val ts: Long,
)

object JournalCodec {
    fun encode(entries: List<JournalEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("op", e.op)
                    .put("from", e.fromRel)
                    .put("to", e.toRel)
                    .put("uri", e.resultUri)
                    .put("ts", e.ts),
            )
        }
        return arr.toString()
    }

    fun decode(json: String): List<JournalEntry> {
        val arr = JSONArray(json)
        val out = ArrayList<JournalEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                JournalEntry(
                    op = o.optString("op", "move"),
                    fromRel = o.optString("from"),
                    toRel = o.optString("to"),
                    resultUri = o.optString("uri"),
                    ts = o.optLong("ts", 0L),
                ),
            )
        }
        return out
    }
}
