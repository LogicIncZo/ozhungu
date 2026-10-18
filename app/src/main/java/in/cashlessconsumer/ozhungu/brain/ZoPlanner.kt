package `in`.cashlessconsumer.ozhungu.brain

import `in`.cashlessconsumer.ozhungu.Settings
import dev.zocomputer.ask.ZoApi
import okhttp3.Call

/** Zo backend: one planner instance per session; follow-up turns ride the same conversation. */
class ZoPlanner(private val settings: Settings) : Planner {

    private var convId: String? = null
    private var call: Call? = null

    override fun plan(
        prompt: String,
        onStatus: (String?) -> Unit,
        onDelta: (String) -> Unit,
        onDone: (String?) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        call = ZoApi.ask(
            opts = ZoApi.Options(token = settings.token, model = settings.model, persona = ""),
            input = prompt,
            conversationId = convId,
            onDelta = onDelta,
            onStatus = onStatus,
            onDone = { cid -> convId = cid; onDone(cid) },
            onError = onError,
        )
    }

    override fun cancel() {
        call?.cancel()
        call = null
    }
}
