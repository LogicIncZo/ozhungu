package `in`.cashlessconsumer.ozhungu.plan

/**
 * Plan grammar shared between the Zo planner and the on-device executor.
 * All paths are relative to the SAF root, '/'-separated, no leading slash,
 * no ".." segments. `from` must exist in the scan; `to` must not.
 */
data class PlanAction(val op: String, val from: String, val to: String, val reason: String)

data class Plan(val folders: List<String>, val actions: List<PlanAction>)

data class ActionError(val action: PlanAction, val error: String)

data class ValidatedPlan(val valid: List<PlanAction>, val invalid: List<ActionError>) {
    val allOk: Boolean get() = invalid.isEmpty()
}
