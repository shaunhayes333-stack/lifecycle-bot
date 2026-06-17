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
        val state: String = faultCode,
        val subsystem: String = "runtime",
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
        val f = ctx.invariantFaults
            .filterNot { it.code == InvariantGuardian.FaultCode.SCANNER_INACTIVE && ctx.snapshot.scannerActive }
            .firstOrNull()
        if (f != null) {
            return Diagnosis(
                faultCode = f.code.name,
                state = "MECHANICAL_FAULT",
                subsystem = subsystemFor(f.code),
                rootCause = f.detail,
                confidence = 0.72,
                evidence = listOf(f.detail, "runtimeGeneration=${ctx.snapshot.runtimeGeneration}", "mode=${ctx.snapshot.mode}"),
                safeMitigation = when (f.code) {
                    InvariantGuardian.FaultCode.SELL_RECONCILER_DEAD -> "restart sell reconciler"
                    InvariantGuardian.FaultCode.RUNTIME_UI_SPLIT_BRAIN -> "observe UI foreground state only; do not pause runtime"
                    InvariantGuardian.FaultCode.LANE_FANOUT_EXPLOSION -> "bounded fanout mitigation only; never disable lanes permanently"
                    InvariantGuardian.FaultCode.PAPER_LIVE_CONTAMINATION -> "pause trading and force paper mode"
                    else -> "bounded subsystem reconcile/throttle only if severity is HIGH/CRITICAL"
                },
                requiredCodeFix = "Generate patch plan only; do not self-edit live runtime.",
                rollbackPlan = "Revert the patch branch or clear bounded hotfix rule; never enable live automatically.",
            )
        }

        // V5.0.3824 — autonomy audit classification. Invariant faults answer
        // "is the machine broken?" but they did not answer "is the running bot
        // degraded or bleeding?" Runtime 3822 showed Doctor=NO_FAULT while WR was
        // below bootstrap floor and ANR sentinels were severe. Classify those as
        // bounded diagnoses without publishing destructive mitigations.
        val closed = ctx.tradeRows.mapNotNull { parseClosedPnl(it) }
        val n = closed.size
        val wins = closed.count { it > 0.0 }
        val wr = if (n > 0) wins * 100.0 / n else 0.0
        val net = closed.sum()
        val anrBad = ctx.snapshot.anrHints >= 3
        val apiBad = ctx.apiHealth.any { (_, h) -> h.successRatePct < 50 && h.failures >= 3 }
        val bleed = n >= 20 && wr < 20.0
        val evidence = mutableListOf(
            "runtimeGeneration=${ctx.snapshot.runtimeGeneration}",
            "mode=${ctx.snapshot.mode}",
            "closedWindow=$n wr=${"%.1f".format(wr)}% netPnlPct=${"%+.1f".format(net)}",
            "anrHints=${ctx.snapshot.anrHints}",
        )
        if (apiBad) evidence += "apiDegraded=" + ctx.apiHealth.filter { it.value.successRatePct < 50 && it.value.failures >= 3 }.keys.joinToString(",")

        return when {
            anrBad -> Diagnosis(
                faultCode = "MECHANICAL_FAULT",
                state = "MECHANICAL_FAULT",
                subsystem = "ui/reporting",
                rootCause = "Main-thread stalls/ANR hints active while runtime is trading; UI/report rendering can steal cycles and distort performance diagnosis.",
                confidence = 0.66,
                evidence = evidence,
                safeMitigation = "offload/throttle UI rendering and reports; do not alter FDG/live safety to hide stalls",
                requiredCodeFix = "Patch UI/report rendering source path; keep trading policy unchanged.",
                rollbackPlan = "Revert UI/report patch if render data disappears; never weaken execution safety.",
            )
            apiBad -> Diagnosis(
                faultCode = "DEGRADED",
                state = "DEGRADED",
                subsystem = "api/providers",
                rootCause = "One or more provider layers are degraded; keep fail-open where safe and avoid hot-path LLM/API dependency.",
                confidence = 0.58,
                evidence = evidence,
                safeMitigation = "prefer healthy scanner/provider paths; no global trade pause unless core execution proof is affected",
                requiredCodeFix = "Provider-specific fallback/backoff only.",
                rollbackPlan = "Remove provider fallback if it suppresses healthy sources.",
            )
            bleed -> Diagnosis(
                faultCode = "STRATEGY_BLEED",
                state = "STRATEGY_BLEED",
                subsystem = "strategy/learning",
                rootCause = "Closed-trade window is below bootstrap WR floor; mechanics may be green but strategy quality/admission/exits are bleeding.",
                confidence = 0.63,
                evidence = evidence,
                safeMitigation = "pivot tactics, seed lab paper experiments, micro-size via learned soft-shaping; do not disable lanes or weaken safety",
                requiredCodeFix = "Audit source admission/scoring/exits and learned specialist weighting; preserve journal truth.",
                rollbackPlan = "Revert strategy-shaping patch if throughput collapses or live/paper parity diverges.",
            )
            else -> Diagnosis(
                faultCode = "HEALTHY",
                state = "HEALTHY",
                subsystem = "runtime",
                rootCause = "No deterministic invariant, degradation, or strategy-bleed condition currently active.",
                confidence = 0.40,
                evidence = evidence,
                safeMitigation = "observe",
                requiredCodeFix = "none",
                rollbackPlan = "not applicable",
            )
        }
    }

    private fun parseClosedPnl(row: String): Double? {
        return try {
            val parts = row.split(":")
            val side = parts.getOrNull(0)?.uppercase() ?: return null
            if (side == "BUY") return null
            parts.getOrNull(3)?.toDoubleOrNull()
        } catch (_: Throwable) { null }
    }

    private fun subsystemFor(code: InvariantGuardian.FaultCode): String = when (code) {
        InvariantGuardian.FaultCode.SCANNER_INACTIVE,
        InvariantGuardian.FaultCode.SCANNER_RESTORE_POISONING -> "scanner"
        InvariantGuardian.FaultCode.FDG_FANOUT_EXPLOSION,
        InvariantGuardian.FaultCode.FDG_SIGNAL_BYPASS -> "fdg"
        InvariantGuardian.FaultCode.SELL_RECONCILER_DEAD,
        InvariantGuardian.FaultCode.RECONCILER_STALLED,
        InvariantGuardian.FaultCode.RECONCILER_BLIND_CRITICAL -> "wallet/reconciler"
        InvariantGuardian.FaultCode.LIVE_SELL_NO_FINALITY,
        InvariantGuardian.FaultCode.SELL_RETRY_STORM,
        InvariantGuardian.FaultCode.BUY_PENDING_BALANCE_PROOF_STALE,
        InvariantGuardian.FaultCode.BALANCE_AUTHORITY_FALSE_ZERO_CRITICAL -> "executor/finality"
        InvariantGuardian.FaultCode.MAIN_THREAD_STALL,
        InvariantGuardian.FaultCode.RUNTIME_UI_SPLIT_BRAIN -> "ui/reporting"
        InvariantGuardian.FaultCode.LEARNING_LEDGER_DUPLICATION -> "learning"
        else -> "runtime"
    }
}
