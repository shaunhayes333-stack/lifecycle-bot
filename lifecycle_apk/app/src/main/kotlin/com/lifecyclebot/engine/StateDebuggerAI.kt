package com.lifecyclebot.engine

/** LLM diagnosis contract only. This layer cannot mutate runtime or code. */
object StateDebuggerAI {
    data class Diagnosis(
        val faultCode: String,
        val rootCause: String,
        val confidence: Double,
        val evidence: List<String>,
        val safeMitigation: String,
        val requiredCodeFix: String,
        val rollbackPlan: String,
    )

    data class Context(
        val snapshot: RuntimeStateSnapshot,
        val forensicEvents: List<String>,
        val tradeRows: List<String>,
        val invariantFaults: List<InvariantGuardian.Fault>,
        val configSummary: String,
        val apiHealth: Map<String, RuntimeStateSnapshot.ApiSummary>,
    )

    fun deterministicFallback(ctx: Context): Diagnosis {
        val f = ctx.invariantFaults.firstOrNull()
        return Diagnosis(
            faultCode = f?.code?.name ?: "NO_FAULT",
            rootCause = f?.detail ?: "No deterministic invariant fault currently active.",
            confidence = if (f == null) 0.0 else 0.72,
            evidence = listOfNotNull(f?.detail, "runtimeGeneration=${ctx.snapshot.runtimeGeneration}", "mode=${ctx.snapshot.mode}"),
            safeMitigation = when (f?.code) {
                InvariantGuardian.FaultCode.SELL_RECONCILER_DEAD -> "restart sell reconciler"
                InvariantGuardian.FaultCode.RUNTIME_UI_SPLIT_BRAIN -> "observe UI foreground state only; do not pause runtime"
                InvariantGuardian.FaultCode.LANE_FANOUT_EXPLOSION -> "disable noisiest lane or apply max lane fanout hotfix"
                InvariantGuardian.FaultCode.PAPER_LIVE_CONTAMINATION -> "pause trading and force paper mode"
                else -> "observe or pause trading if severity is HIGH/CRITICAL"
            },
            requiredCodeFix = "Generate patch plan only; do not self-edit live runtime.",
            rollbackPlan = "Revert the patch branch or clear bounded hotfix rule; never enable live automatically.",
        )
    }
}
