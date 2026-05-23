package com.lifecyclebot.engine

/** Patch planner contract. May create a branch/PR externally, never merge/deploy without approval. */
object PatchWriterAI {
    data class PatchPlan(
        val patchSummary: String,
        val targetFiles: List<String>,
        val gitDiff: String,
        val tests: List<String>,
        val smokeTestChecklist: List<String>,
        val rollbackPlan: String,
        val mayCreateBranchOrPr: Boolean = true,
        val mayMergeOrDeploy: Boolean = false,
    )

    fun planFromDiagnosis(d: StateDebuggerAI.Diagnosis): PatchPlan = PatchPlan(
        patchSummary = "Repair ${d.faultCode}: ${d.requiredCodeFix}",
        targetFiles = emptyList(),
        gitDiff = "",
        tests = listOf("add invariant smoke test for ${d.faultCode}"),
        smokeTestChecklist = listOf("build release", "runtime smoke", "verify regression_guard_summary", "verify rollback path"),
        rollbackPlan = d.rollbackPlan,
    )
}
