package com.lifecyclebot.engine

/**
 * V5.0.4416 — CQ1-CQ5 source-contract sentinel.
 *
 * Report-only contract bundle that the next behavioral patches must satisfy.
 * This protects live safety while allowing high-throughput remediation work to
 * move in batches instead of single-file drips.
 */
object OperatorChokeSourceContractSentinel {
    data class Contract(
        val id: String,
        val invariant: String,
        val protectedButterflies: List<String>
    )

    private val contracts = listOf(
        Contract(
            id = "CQ1_ZERO_SIZE_CONTRACT",
            invariant = "non-safety zero sizing must become route-visible or bounded-probe shaped; capital reserve and price-trust zeroes stay hard/neutral",
            protectedButterflies = listOf("SmartSizer", "FDG", "Executor", "journal", "learning", "KPI volume")
        ),
        Contract(
            id = "CQ2_FALSE_RETURN_CONTRACT",
            invariant = "hard-safety false returns stay hard; advisory/pending/telemetry false returns require release visibility and route taxonomy",
            protectedButterflies = listOf("FEP", "LaneExecutionCoordinator", "TradeAuthorizer", "release path", "route verdict")
        ),
        Contract(
            id = "CQ3_PAUSE_RECOVERY_CONTRACT",
            invariant = "global catastrophic pauses remain hard; lane-local pause/cold-streak gates may only suppress via bounded recovery-probe policy with telemetry",
            protectedButterflies = listOf("AutoMode", "SecurityGuard", "KillSwitch", "SmartSizer", "suppressor counters")
        ),
        Contract(
            id = "CQ4_REJECT_TAXONOMY_CONTRACT",
            invariant = "reject labels must map to hard-safety, cost-reject, penalty, low-liq size-reduction, or advisory; no ambiguous veto labels",
            protectedButterflies = listOf("scanner hard reject", "FDG verdict", "executor skip", "journal reason", "canonical learning")
        ),
        Contract(
            id = "CQ5_PROVIDER_HOT_PATH_CONTRACT",
            invariant = "scanner/executor hot paths use cache-first/background provider reads; quote rejects never become global outages unless network/provider class proves it",
            protectedButterflies = listOf("source balance", "ApiHealthMonitor", "BirdeyeBudgetGate", "Jupiter quote", "wallet trust")
        )
    )

    fun status(): String {
        val ids = contracts.joinToString(",") { it.id }
        val butterflies = contracts.joinToString(";") { "${it.id}=${it.protectedButterflies.joinToString("|")}" }
        return "OPERATOR_CHOKE_SOURCE_CONTRACT_SENTINEL_4416 contracts=${contracts.size} ids=[$ids] butterflies=[$butterflies] report_only=true no_execution_authority=true no_gate_change=true hard_safety_preserved=true release_visibility_required=true fep_false_return_route_visible_4416=true lane_release_false_visible_4419=true choke_visibility_bus_4421=true cache_first_required=true reject_taxonomy_required=true golden_tape_required=true"
    }
}
