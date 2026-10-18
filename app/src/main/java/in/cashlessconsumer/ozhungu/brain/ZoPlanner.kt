package `in`.cashlessconsumer.ozhungu.brain

import `in`.cashlessconsumer.ozhungu.Settings
import `in`.cashlessconsumer.ozhungu.plan.Plan
import `in`.cashlessconsumer.ozhungu.plan.PlanParser
import dev.zocomputer.ask.ZoApi

/** Talks to the Zo agent: first call plans, follow-ups refine the same conversation. */
object ZoPlanner {

    fun plan(
        settings: Settings,
        prompt: String,
        conversationId: String?,
        onStatus: (String?) -> Unit,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        ZoApi.ask(
            opts = ZoApi.Options(token = settings.token, model = settings.model, persona = ""),
            input = prompt,
            conversationId = conversationId,
            onDelta = onDelta,
            onStatus = onStatus,
            onDone = onDone,
            onError = onError,
        )
    }

    fun parsePlan(text: String): Plan = PlanParser.parse(text)
}
