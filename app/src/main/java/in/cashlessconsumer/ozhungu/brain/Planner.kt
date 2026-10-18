package `in`.cashlessconsumer.ozhungu.brain

/** A planning backend: Zo (/zo/ask) or any OpenAI-compatible endpoint. */
interface Planner {
    /** Sends a prompt; continuity is backend-internal (Zo conversation id / OAI local history). */
    fun plan(
        prompt: String,
        onStatus: (String?) -> Unit,
        onDelta: (String) -> Unit,
        onDone: (String?) -> Unit,
        onError: (Throwable) -> Unit,
    )

    /** Cancels the in-flight call, if the backend supports it. */
    fun cancel()
}
