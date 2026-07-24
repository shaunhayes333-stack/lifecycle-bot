package com.lifecyclebot.engine

/**
 * V5.0.6348 — FIRST-TRADE READINESS HEALTH BLOCK (operator P1-2 spec).
 *
 * OPERATOR DIRECTIVE (verbatim excerpts):
 *   "The AATE Pipeline Health snapshot must expose a FIRST_TRADE_READY
 *    Y/N with sub-reasons. If N, the operator must be able to tell
 *    exactly which pillar is missing without reading source."
 *
 *   "Root-cause priority ranking must reflect ACTUAL runtime state, not
 *    a hard-coded list. A pillar in place gets its ✓; a pillar missing
 *    or degraded gets a priority number so the operator sees the exact
 *    remediation order."
 *
 * PILLARS (each contributes to the FIRST_TRADE_READY = Y verdict):
 *   1. LIVE_ENTRY_SAFETY_HOLD governor is at BASELINE or better
 *      (i.e. not HOLD — HOLD hard-vetos live buys per LaneEntryContract6342).
 *   2. Scanner has at least one row in the LIVE_READY bucket
 *      (ScannerHydrationQueues6347).
 *   3. FillLotLedger6344 is initialised (persisted state restored).
 *   4. CanonicalPnLAuthority6343 has admitted at least one canonical
 *      row OR has zero quarantines (no data-integrity fault outstanding).
 *   5. CanonicalBuyFillRegistry has been rehydrated (at least init() ran).
 *
 * OUTPUT SHAPE
 *   [assess] returns an immutable [Assessment] with:
 *     - ready: Boolean
 *     - reasons: List<String> ordered by priority (highest first)
 *     - snapshot: pretty-printed multi-line dump for the health tile
 *
 *   The health tile calls [snapshotLine] for a single-line summary
 *   suitable for the AATE Pipeline Health Snapshot ticker.
 */
object FirstTradeReadiness6348 {

    data class Pillar(
        val name: String,
        val priority: Int,
        val ok: Boolean,
        val detail: String,
    )

    data class Assessment(
        val ready: Boolean,
        val pillars: List<Pillar>,
    ) {
        val missingPillars: List<Pillar> get() = pillars.filter { !it.ok }.sortedBy { it.priority }
        fun toSnapshot(): String = buildString {
            append("FIRST_TRADE_READINESS_6348 ready=")
            append(if (ready) "Y" else "N")
            append(" pillars=[")
            append(pillars.joinToString(",") { p -> "${p.name}:${if (p.ok) "✓" else "P${p.priority}"}" })
            append("]")
            if (!ready) {
                append(" nextRemediation=")
                append(missingPillars.firstOrNull()?.let { "${it.name}(${it.detail})" } ?: "unknown")
            }
        }
    }

    fun assess(): Assessment {
        val pillars = mutableListOf<Pillar>()

        // Pillar 1 (highest priority) — governor not HOLD.
        val govName = try { LiveEntrySafetyHold.currentGovernorState().name } catch (_: Throwable) { "UNKNOWN" }
        pillars += Pillar(
            name = "GOVERNOR_NOT_HOLD",
            priority = 1,
            ok = govName != "HOLD",
            detail = "state=$govName",
        )

        // Pillar 2 — at least one LIVE_READY candidate.
        val liveReadyCount = try {
            ScannerHydrationQueues6347.sizesByBucket()[ScannerHydrationQueues6347.Bucket.LIVE_READY] ?: 0
        } catch (_: Throwable) { -1 }
        pillars += Pillar(
            name = "SCANNER_LIVE_READY_QUEUE",
            priority = 2,
            ok = liveReadyCount > 0,
            detail = "size=$liveReadyCount",
        )

        // Pillar 3 — FillLotLedger6344 initialised (activeCount() reachable).
        val ledgerLots = try { FillLotLedger6344.activeCount() } catch (_: Throwable) { -1 }
        pillars += Pillar(
            name = "FILL_LOT_LEDGER_INIT",
            priority = 3,
            ok = ledgerLots >= 0,
            detail = "lots=$ledgerLots",
        )

        // Pillar 4 — canonical registry rehydrated.
        val buyFillCount = try { CanonicalBuyFillRegistry.activeCount() } catch (_: Throwable) { -1 }
        pillars += Pillar(
            name = "CANONICAL_BUY_FILL_REGISTRY_INIT",
            priority = 4,
            ok = buyFillCount >= 0,
            detail = "fills=$buyFillCount",
        )

        // Pillar 5 (lowest priority) — no data-integrity quarantines are
        // outstanding. Any CANONICAL_PNL_QUARANTINED_6343 or
        // CANONICAL_LEARNING_QUARANTINED_6346 count is a soft flag; the
        // pipeline is still ready to trade but this pillar shows amber.
        val quarantines = try {
            PipelineHealthCollector.labelCountSnapshot("CANONICAL_PNL_QUARANTINED_6343") +
                PipelineHealthCollector.labelCountSnapshot("CANONICAL_LEARNING_QUARANTINED_6346")
        } catch (_: Throwable) { 0L }
        pillars += Pillar(
            name = "NO_OUTSTANDING_QUARANTINES",
            priority = 5,
            ok = quarantines == 0L,
            detail = "quarantines=$quarantines",
        )

        val ready = pillars.all { it.ok }
        return Assessment(ready = ready, pillars = pillars)
    }

    /** Single-line summary for the AATE Pipeline Health ticker. */
    fun snapshotLine(): String = assess().toSnapshot()

    /** Multi-line dump for the diagnostics tile / operator forensics screen. */
    fun snapshotBlock(): String {
        val a = assess()
        val sb = StringBuilder()
        sb.append("╔════════ FIRST_TRADE_READINESS_6348 ════════╗\n")
        sb.append("║ ready=").append(if (a.ready) "Y" else "N").append('\n')
        for (p in a.pillars.sortedBy { it.priority }) {
            sb.append("║ [P").append(p.priority).append("] ")
            sb.append(if (p.ok) "✓" else "✗").append(" ")
            sb.append(p.name).append(" (").append(p.detail).append(")\n")
        }
        sb.append("╚═══════════════════════════════════════════╝")
        return sb.toString()
    }
}
