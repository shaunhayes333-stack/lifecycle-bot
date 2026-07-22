package com.lifecyclebot.engine

/**
 * V5.0.6324 — HEALTH SNAPSHOT SECTIONS (operator hotfix §Reporting).
 *
 * Assembles the six required snapshot sections into a single text
 * block for the health report:
 *
 *   1. Canonical Position Authority
 *   2. Governor State
 *   3. Provider Authority
 *   4. Exit Coordinator
 *   5. Execution Outcome Classification
 *   6. Decimal & Quantity Integrity
 *
 * All data is pulled from live in-memory registries — no filesystem
 * scans, so it is safe to call from the periodic health cycle.
 */
object HealthSnapshot6324 {

    fun render(): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════ V5.0.6324 SNAPSHOT ═══════════")

        // 1) CANONICAL POSITION AUTHORITY
        sb.appendLine("[1] CANONICAL POSITION AUTHORITY")
        sb.appendLine("    active positions: ${CanonicalPositionRegistry.activeCount()}")
        sb.appendLine("    canonical fills:  ${CanonicalBuyFillRegistry.activeCount()}")
        sb.appendLine("    overwrite rejects (lower authority): ${PipelineHealthCollector.labelCountSnapshot("LOWER_AUTHORITY_OVERWRITE_REJECTED_6324")}")
        sb.appendLine("    invariant rejects: ${PipelineHealthCollector.labelCountSnapshot("CANONICAL_POSITION_INVARIANT_REJECTED_6324")}")
        sb.appendLine("    authority promotions: ${PipelineHealthCollector.labelCountSnapshot("CANONICAL_POSITION_AUTHORITY_PROMOTED_6324")}")

        // 2) GOVERNOR STATE
        sb.appendLine("[2] GOVERNOR STATE")
        val state = LiveEntrySafetyHold.currentGovernorState()
        sb.appendLine("    state: $state")
        sb.appendLine("    sizeMultiplier: ${"%.2f".format(LiveEntrySafetyHold.currentSizeMultiplier())}")
        sb.appendLine("    floorAdjustment: +${"%.1f".format(LiveEntrySafetyHold.currentFloorAdjustment())}")
        sb.appendLine("    transitions logged: ${PipelineHealthCollector.labelCountSnapshot("LIVE_GOVERNOR_STATE_CHANGED_6324")}")

        // 3) PROVIDER AUTHORITY
        sb.appendLine("[3] PROVIDER AUTHORITY")
        val degraded = ProviderAuthority.snapshotDegraded()
        sb.appendLine("    degraded providers: ${if (degraded.isEmpty()) "none" else degraded.joinToString(",")}")
        sb.appendLine("    provider conflicts: ${PipelineHealthCollector.labelCountSnapshot("PROVIDER_AUTHORITY_CONFLICT_6324")}")

        // 4) EXIT COORDINATOR
        sb.appendLine("[4] EXIT COORDINATOR")
        sb.appendLine("    active sweeps: ${ExitCoordinatorHeartbeat.snapshot().size}")
        sb.appendLine("    stale resets: ${ExitCoordinatorHeartbeat.staleResetCount()}")
        sb.appendLine("    justified resets: ${ExitCoordinatorHeartbeat.justifiedResetCount()}")
        sb.appendLine("    false resets prevented: ${ExitCoordinatorHeartbeat.falseResetsPreventedCount()}")
        sb.appendLine("    duplicate sweeps suppressed: ${ExitCoordinatorHeartbeat.duplicateSweepsSuppressedCount()}")

        // 5) EXECUTION OUTCOME CLASSIFICATION
        sb.appendLine("[5] EXECUTION OUTCOME CLASSIFICATION")
        sb.appendLine("    live authorized:      ${PipelineHealthCollector.labelCountSnapshot("BUY_LIVE_AUTHORIZED_6324")}")
        sb.appendLine("    executed (broadcast): ${PipelineHealthCollector.labelCountSnapshot("BUY_EXECUTED_6324")}")
        sb.appendLine("    redirected to shadow: ${PipelineHealthCollector.labelCountSnapshot("BUY_POLICY_REDIRECTED_SHADOW_6324")}")
        sb.appendLine("    security blocked:     ${PipelineHealthCollector.labelCountSnapshot("BUY_SECURITY_BLOCKED_6324")}")
        sb.appendLine("    provider failed:      ${PipelineHealthCollector.labelCountSnapshot("BUY_PROVIDER_FAILED_6324")}")
        val probeCounts = LiveProbeEntry.snapshotCounts()
        sb.appendLine("    probes started:       ${probeCounts.first}")
        sb.appendLine("    probes promoted:      ${probeCounts.second}")
        sb.appendLine("    probes rejected:      ${probeCounts.third}")

        // 6) DECIMAL & QUANTITY INTEGRITY
        sb.appendLine("[6] DECIMAL & QUANTITY INTEGRITY")
        sb.appendLine("    idempotency rejects:  ${PipelineHealthCollector.labelCountSnapshot("ACCOUNTING_IDEMPOTENCY_REJECTED_6324")}")
        sb.appendLine("    idempotency claims:   ${PipelineHealthCollector.labelCountSnapshot("ACCOUNTING_IDEMPOTENCY_CLAIMED_6324")}")
        sb.appendLine("    broadcast suppressed: ${PipelineHealthCollector.labelCountSnapshot("BROADCAST_ACCOUNTING_SUPPRESSED_6324")}")
        sb.appendLine("    sell qty clamps:      ${PipelineHealthCollector.labelCountSnapshot("SELL_QTY_WALLET_CLAMPED_6324")}")
        sb.appendLine("    tactic pivots:        ${PipelineHealthCollector.labelCountSnapshot("TACTIC_BLEED_PIVOT_6324")}")
        sb.appendLine("    catastrophic traces:  ${CatastrophicExitLatency.emittedTraceCount()}")
        return sb.toString()
    }
}
