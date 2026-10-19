package com.lifecyclebot.engine.truth

/**
 * V5.0.8188 §PEAK_IS_A_RECORDED_EVENT — the drawdown high-water mark is an event with provenance, not a number that
 * later reads may revise.
 *
 * 5.0.8186: the same ~15:28 24 h peak read 0.4488 SOL at 18:58 and 0.7385 SOL at 20:34 (same time, +0.2897 SOL), so
 * the 60.8% drawdown that cut every size could not be trusted. The only writer that changes a peak's VALUE while
 * keeping its TIME is the external-flow rebase (CapitalDrawdown7948.rebasedPeak7981): a confirmed "deposit" moves the
 * old peak by the flow. That flow was a RESIDUAL — equity change minus realised minus unrealised — over components read
 * from different sources at different moments (wallet SOL, canonical principal filtered by the wallet tracker, the
 * hero's unrealised P&L with the open leg floored at zero, the cached clean leaderboard). Any component that appeared
 * without a journal write (a canonical row the tracker re-admitted, an unrealised loss deeper than the principal it
 * marks, a realised figure that moved without a trade) read as a deposit after 45 s and raised the past peak; the
 * reverse move was cancelled whenever it coincided with a trade. A ratchet.
 *
 * Rules now:
 *  - A deposit / withdrawal is money that moved through the WALLET: only the part of a residual that the liquid SOL
 *    change corroborates (same sign, at most its size) may rebase the peak. The rest is refused and logged.
 *  - A new peak is RECORDED at the moment it is set — value, time, liquid, open principal, unrealised (gain excluded)
 *    and the mark's provenance — and persisted with every rebase since. Nothing recomputes it from later marks.
 *  - A peak may only be set from verified live components: never in a paper runtime, and principal of a live row the
 *    wallet tracker has never seen (counted only on its entry price) is left out of the candidate.
 * History is kept: a restored peak keeps its value; a peak saved before this change is labelled as having no
 * recorded components rather than being reset.
 */
object PeakLedger8188 {

    const val EVENT_KEY_8188 = "peak_event_8188"
    const val REBASES_KEY_8188 = "peak_rebases_8188"
    const val RECONCILED_KEY_8188 = "peak_reconciled_8188"
    const val ARH_HIGH_KEY_8188 = "arh_high_8188"
    const val ARH_HIGH_AT_KEY_8188 = "arh_high_at_8188"
    const val ORIGIN_RECONCILED_8188 = "RECONCILED_8188"
    const val WITNESS_ARH_8188 = "ANTI_REWARD_HACK_HIGH"
    const val WITNESS_CREED_DAY_START_8188 = "CAPITAL_CREED_DAY_START"
    const val WITNESS_EQUITY_FLOOR_8188 = "VERIFIED_EQUITY_FLOOR"
    const val ORIGIN_NEW_HIGH_8188 = "NEW_HIGH"
    const val ORIGIN_WINDOW_ROLL_8188 = "WINDOW_ROLL"
    const val ORIGIN_SEEDED_8188 = "SEEDED"
    const val ORIGIN_RESTORED_NO_COMPONENTS_8188 = "RESTORED_NO_RECORDED_COMPONENTS"
    const val ORIGIN_REANCHOR_8188 = "EXPLICIT_REANCHOR_8093"
    const val REBASE_APPLIED_8188 = "APPLIED"
    const val REBASE_PARTIAL_8188 = "PARTIAL_LIQUID_CORROBORATED"
    const val REBASE_REFUSED_8188 = "REFUSED_NOT_IN_WALLET"
    private const val MAX_REBASES_8188 = 20
    private const val WINDOW_MS_8188 = 24L * 60L * 60_000L

    data class PeakEvent8188(
        val recordedSol: Double,
        val atMs: Long,
        val liquidSol: Double,
        val openPrincipalSol: Double,
        val unrealisedSol: Double,
        val unverifiedPrincipalSol: Double,
        val origin: String,
        val markProvenance: String,
    )

    data class Rebase8188(
        val atMs: Long,
        val residualSol: Double,
        val liquidDeltaSol: Double,
        val appliedSol: Double,
        val fromSol: Double,
        val toSol: Double,
        val verdict: String,
    )

    // ── V5.0.8188 — reconciling a legacy peak at load (not a reset) ──
    //
    // A peak saved before 8188 carries no components and may have absorbed rebases the wallet rule now refuses
    // (5.0.8186: 0.7625 SOL while the anti-reward-hack 24 h high, cash + open cost, read 0.3596). It is reconciled against
    // independent witnesses: the legacy value less the refused flows is only a CEILING (it is the unverified number
    // itself); the verified witnesses (the anti-reward-hack high, persisted beside the peak, and the capital creed's day start) inside
    // the 24 h window are the evidence. Reconciled = max(witnesses), never below verified equity, never above the
    // ceiling. No witness: the legacy peak stays. The legacy value and the reason are kept in history and on the report.

    data class Witness8188(val name: String, val sol: Double, val atMs: Long)

    data class Reconciled8188(
        val legacySol: Double,
        val reconciledSol: Double,
        val by: String,
        val refusedFlowsSol: Double,
        val atMs: Long,
        val witnesses: String,
    )

    /** Pure: the reconciled peak, or null when the legacy peak stays (no in-window witness, or none lower). */
    fun reconcile8188(legacySol: Double, refusedFlowsSol: Double, witnesses: List<Witness8188>,
                      verifiedEquitySol: Double, nowMs: Long): Reconciled8188? {
        if (!legacySol.isFinite() || legacySol <= 0.0) return null
        val valid = witnesses.filter { it.sol.isFinite() && it.sol > 0.0 && it.atMs > 0L && nowMs - it.atMs in 0L..WINDOW_MS_8188 }
        if (valid.isEmpty()) return null
        val refused = if (refusedFlowsSol.isFinite() && refusedFlowsSol > 0.0) refusedFlowsSol else 0.0
        val ceiling = (legacySol - refused).coerceAtMost(legacySol)
        val best = valid.maxByOrNull { it.sol }!!
        val eq = if (verifiedEquitySol.isFinite() && verifiedEquitySol > 0.0) verifiedEquitySol else 0.0
        val floorBinds = eq > best.sol
        val value = minOf(ceiling, maxOf(best.sol, eq)).coerceAtLeast(eq).coerceAtMost(legacySol)
        if (value >= legacySol - 1e-9) return null
        return Reconciled8188(legacySol, value, if (floorBinds) WITNESS_EQUITY_FLOOR_8188 else best.name, refused, nowMs,
            valid.joinToString(" ") { "${it.name}=${"%.4f".format(it.sol)}" })
    }

    fun encodeReconciled8188(r: Reconciled8188): String =
        listOf("v1", r.legacySol, r.reconciledSol, clean8188(r.by), r.refusedFlowsSol, r.atMs, clean8188(r.witnesses)).joinToString("|")

    fun decodeReconciled8188(s: String?): Reconciled8188? {
        val f = s?.split("|") ?: return null
        if (f.size != 7 || f[0] != "v1") return null
        return Reconciled8188(f[1].toDoubleOrNull() ?: return null, f[2].toDoubleOrNull() ?: return null, f[3],
            f[4].toDoubleOrNull() ?: 0.0, f[5].toLongOrNull() ?: 0L, f[6])
    }

    // ── pure ────────────────────────────────────────────────────────────────

    /** Open principal may move this much (SOL) across a flow's window and the flow still be a wallet deposit / withdrawal. */
    const val PRINCIPAL_STILL_SOL_8188 = 0.001

    /**
     * Pure: the part of an unexplained equity change [residualSol] the wallet's liquid SOL change corroborates. A deposit
     * or withdrawal moves liquid SOL while open principal stands still; when principal moved too ([principalDeltaSol]: a
     * buy, a sell, a row the tracker dropped or re-admitted) the residual is a trade or a valuation artefact, not a flow.
     */
    fun liquidCorroboratedFlow8188(residualSol: Double, liquidDeltaSol: Double, principalDeltaSol: Double = 0.0): Double {
        if (!residualSol.isFinite() || !liquidDeltaSol.isFinite() || residualSol == 0.0 || liquidDeltaSol == 0.0) return 0.0
        if (!principalDeltaSol.isFinite() || kotlin.math.abs(principalDeltaSol) > PRINCIPAL_STILL_SOL_8188) return 0.0
        if ((residualSol > 0.0) != (liquidDeltaSol > 0.0)) return 0.0
        return if (residualSol > 0.0) minOf(residualSol, liquidDeltaSol) else maxOf(residualSol, liquidDeltaSol)
    }

    /** Pure: the verdict on a rebase of [residualSol] of which [corroboratedSol] is in the wallet. */
    fun rebaseVerdict8188(residualSol: Double, corroboratedSol: Double): String = when {
        !corroboratedSol.isFinite() || corroboratedSol == 0.0 -> REBASE_REFUSED_8188
        kotlin.math.abs(corroboratedSol) + 1e-12 < kotlin.math.abs(residualSol) -> REBASE_PARTIAL_8188
        else -> REBASE_APPLIED_8188
    }

    private const val REBASE_TRADE_SETTLEMENT_8223 = "REFUSED_TRADE_SETTLEMENT_LAG"
    /** A bot leg's SOL may land in the wallet read this long after its journal row (settlement + balance poll lag). */
    const val SETTLEMENT_LAG_MS_8223 = 10L * 60_000L

    /**
     * Pure (V5.0.8223): is a wallet-corroborated flow explained by the bot's own recent live legs? A sell journaled
     * BEFORE the wallet poll saw its proceeds moved realised P&L and principal in one observation and liquid SOL in a
     * later one: at that later observation the residual is +proceeds, liquid rose by +proceeds, principal stood still,
     * so 8188's wallet rule corroborated it and the past peak rose by a sale the bot made (5.0.8220: 9.4% drawdown
     * against a peak no equity read had reached). [netLegsSol] = sells' SOL − buys' SOL journaled live inside the lag
     * window. A flow of the same sign not larger than those legs (within [toleranceSol]) is settlement, not a deposit.
     */
    fun settlementExplains8223(flowSol: Double, netLegsSol: Double, toleranceSol: Double = 0.002): Boolean {
        if (!flowSol.isFinite() || !netLegsSol.isFinite() || flowSol == 0.0 || netLegsSol == 0.0) return false
        if ((flowSol > 0.0) != (netLegsSol > 0.0)) return false
        return kotlin.math.abs(flowSol) <= kotlin.math.abs(netLegsSol) + toleranceSol
    }

    /** CapitalDrawdown7948: a corroborated flow the bot's own legs explain is refused and recorded (peak unchanged). */
    @Synchronized fun refuseSettlement8223(residualSol: Double, liquidDeltaSol: Double, netLegsSol: Double, peakSol: Double, nowMs: Long) {
        refused8188.incrementAndGet()
        addRebase8188(Rebase8188(nowMs, residualSol, liquidDeltaSol, 0.0, peakSol, peakSol, REBASE_TRADE_SETTLEMENT_8223))
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("DRAWDOWN_FLOW_IS_TRADE_SETTLEMENT_REFUSED_8223")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("DRAWDOWN_FLOW_IS_TRADE_SETTLEMENT_REFUSED_8223",
                "residual=${"%+.4f".format(residualSol)} liquidDelta=${"%+.4f".format(liquidDeltaSol)} netLegs=${"%+.4f".format(netLegsSol)} peak=${"%.4f".format(peakSol)} action=peak_unchanged")
        } catch (_: Throwable) {}
        persistQuietly8188()
    }

    /** Cash timing is unresolved: do not relabel a bot sale, or a possible mixed transfer, as a deposit. */
    @Synchronized fun refuseUnresolvedCashFlow8237(residualSol: Double, liquidDeltaSol: Double, peakSol: Double,
                                                  nowMs: Long, evidence: String) {
        refused8188.incrementAndGet()
        addRebase8188(Rebase8188(nowMs, residualSol, liquidDeltaSol, 0.0, peakSol, peakSol, "UNRESOLVED_TRADE_CASHFLOW_8237"))
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("DRAWDOWN_TRADE_CASHFLOW_UNRESOLVED_8237")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("DRAWDOWN_TRADE_CASHFLOW_UNRESOLVED_8237",
                "residual=${"%+.4f".format(residualSol)} liquidDelta=${"%+.4f".format(liquidDeltaSol)} " +
                    "peak=${"%.4f".format(peakSol)} $evidence action=peak_unchanged")
        } catch (_: Throwable) {}
        persistQuietly8188()
    }

    /** Pure: a peak candidate on verified components only — [candidateSol] less principal no wallet read confirms; NaN in paper. */
    fun verifiedCandidate8188(candidateSol: Double, unverifiedPrincipalSol: Double, paperRuntime: Boolean): Double {
        if (paperRuntime || !candidateSol.isFinite()) return Double.NaN
        val u = if (unverifiedPrincipalSol.isFinite() && unverifiedPrincipalSol > 0.0) unverifiedPrincipalSol else 0.0
        return candidateSol - u
    }

    /** Pure: why the peak moved from [before] to [after] (null = it did not move to a new time). */
    fun moveOrigin8188(beforeSol: Double, beforeAtMs: Long, afterSol: Double, afterAtMs: Long, nowMs: Long): String? = when {
        afterAtMs == beforeAtMs -> null
        beforeSol <= 0.0 || nowMs < beforeAtMs || nowMs - beforeAtMs > WINDOW_MS_8188 || afterSol < beforeSol -> ORIGIN_WINDOW_ROLL_8188
        else -> ORIGIN_NEW_HIGH_8188
    }

    /** Pure: one event as a persisted string (no separators inside the provenance). */
    fun encodeEvent8188(e: PeakEvent8188): String = listOf(
        "v1", e.recordedSol.toString(), e.atMs.toString(), e.liquidSol.toString(), e.openPrincipalSol.toString(),
        e.unrealisedSol.toString(), e.unverifiedPrincipalSol.toString(), clean8188(e.origin), clean8188(e.markProvenance),
    ).joinToString("|")

    /** Pure: the event back from [s]; null when absent or unreadable. */
    fun decodeEvent8188(s: String?): PeakEvent8188? {
        val f = s?.split("|") ?: return null
        if (f.size != 9 || f[0] != "v1") return null
        val v = f[1].toDoubleOrNull() ?: return null
        val at = f[2].toLongOrNull() ?: return null
        return PeakEvent8188(v, at, f[3].toDoubleOrNull() ?: Double.NaN, f[4].toDoubleOrNull() ?: Double.NaN,
            f[5].toDoubleOrNull() ?: Double.NaN, f[6].toDoubleOrNull() ?: Double.NaN, f[7], f[8])
    }

    fun encodeRebases8188(rs: List<Rebase8188>): String = rs.joinToString(";") {
        listOf(it.atMs, it.residualSol, it.liquidDeltaSol, it.appliedSol, it.fromSol, it.toSol, clean8188(it.verdict)).joinToString(",")
    }

    fun decodeRebases8188(s: String?): List<Rebase8188> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(";").mapNotNull { r ->
            val f = r.split(",")
            if (f.size != 7) null else {
                val at = f[0].toLongOrNull()
                val nums = f.subList(1, 6).map { it.toDoubleOrNull() }
                if (at == null || nums.any { it == null }) null
                else Rebase8188(at, nums[0]!!, nums[1]!!, nums[2]!!, nums[3]!!, nums[4]!!, f[6])
            }
        }.takeLast(MAX_REBASES_8188)
    }

    private fun clean8188(s: String): String = s.replace('|', '_').replace(';', '_').replace(',', '_')

    // ── runtime ledger (live only; CapitalDrawdown7948 holds the effective peak) ──

    @Volatile private var event8188: PeakEvent8188? = null
    @Volatile private var reconciled8188: Reconciled8188? = null
    private val rebases8188 = java.util.concurrent.ConcurrentLinkedDeque<Rebase8188>()
    private val refused8188 = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var restoredRebaseTimes8237: Set<Long> = emptySet()
    @Volatile private var unverifiedCache8188: Pair<Long, Double>? = null

    /** KillSwitch: writes the ledger next to the peak it describes (set at live init). */
    @Volatile var persist8188: (() -> Unit)? = null

    fun currentEvent8188(): PeakEvent8188? = event8188
    fun rebases8188(): List<Rebase8188> = rebases8188.toList()
    fun encodedEvent8188(): String = event8188?.let { encodeEvent8188(it) } ?: ""
    fun encodedRebases8188(): String = encodeRebases8188(rebases8188.toList())
    fun encodedReconciled8188(): String = reconciled8188?.let { encodeReconciled8188(it) } ?: ""
    fun currentReconciled8188(): Reconciled8188? = reconciled8188

    /** The anti-reward-hack 24 h high and when it was set (persisted beside the peak as a witness); null when unset. */
    fun antiRewardHigh8188(): Pair<Double, Long>? = try {
        val h = AntiRewardHackingGuard6439.currentHigh()
        val at = AntiRewardHackingGuard6439.currentHighAtMs8188()
        if (h.isFinite() && h > 0.0 && at > 0L) h to at else null
    } catch (_: Throwable) { null }

    /**
     * KillSwitch init, after the peak was seeded: a legacy peak (no recorded components) is reconciled against the
     * witnesses. Returns the reconciliation when the peak changed (the caller re-seeds the value at the SAME time).
     * Pre-8188 builds kept no per-flow record across restarts, so the refused-flow sum known at load is the refused
     * rebases this ledger recorded after the legacy peak (normally none); the witnesses carry the reconciliation.
     */
    @Synchronized fun reconcileLegacyAtLoad8188(persistedArhSol: Double, persistedArhAtMs: Long, currentEquitySol: Double,
                                                nowMs: Long = System.currentTimeMillis()): Reconciled8188? {
        val e = event8188 ?: return null
        if (e.origin != ORIGIN_RESTORED_NO_COMPONENTS_8188) return null
        val w = ArrayList<Witness8188>(4)
        w += Witness8188(WITNESS_ARH_8188, persistedArhSol, persistedArhAtMs)
        antiRewardHigh8188()?.let { (h, at) -> w += Witness8188(WITNESS_ARH_8188, h, at) }
        creedDayStart8188()?.let { w += it }
        val refused = rebases8188.filter { it.verdict == REBASE_REFUSED_8188 && it.atMs >= e.atMs && it.residualSol > 0.0 }.sumOf { it.residualSol }
        val upnl = try { HeroSnapshotAuthority6503.current()?.liveTotalUnrealizedSol ?: 0.0 } catch (_: Throwable) { 0.0 }
        val eq = verifiedPeakCandidate8188(CapitalDrawdown7948.gainFreeEquity8130(currentEquitySol, upnl))
        val r = reconcile8188(e.recordedSol, refused, w, eq, nowMs) ?: return null
        reconciled8188 = r
        event8188 = PeakEvent8188(r.reconciledSol, e.atMs, Double.NaN, Double.NaN, Double.NaN, Double.NaN, ORIGIN_RECONCILED_8188,
            "legacy=${"%.4f".format(r.legacySol)} by=${r.by} refusedFlows=${"%+.4f".format(r.refusedFlowsSol)} witnesses=${r.witnesses}")
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("DRAWDOWN_LEGACY_PEAK_RECONCILED_8188")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("DRAWDOWN_LEGACY_PEAK_RECONCILED_8188",
                "legacy=${"%.4f".format(r.legacySol)} reconciled=${"%.4f".format(r.reconciledSol)} by=${r.by} refusedFlows=${"%+.4f".format(r.refusedFlowsSol)} witnesses=${r.witnesses}")
        } catch (_: Throwable) {}
        return r
    }

    /** The capital creed's UTC day-start equity (LiveRiskPolicy7807.noteEquity8057: wallet + principal, gain-free). */
    private fun creedDayStart8188(): Witness8188? = try {
        val f = com.lifecyclebot.engine.LearningPersistence.load("day_start_equity_8057")?.split(',')
        val v = f?.getOrNull(1)?.toDoubleOrNull()
        val at = f?.getOrNull(0)?.let { d ->
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(d)?.time
        }
        if (v != null && at != null) Witness8188(WITNESS_CREED_DAY_START_8188, v, at) else null
    } catch (_: Throwable) { null }

    /** KillSwitch init, before the peak is seeded: the persisted event and its rebases. */
    @Synchronized fun restore8188(eventStr: String?, rebasesStr: String?, reconciledStr: String? = null) {
        event8188 = decodeEvent8188(eventStr)
        reconciled8188 = decodeReconciled8188(reconciledStr)
        rebases8188.clear()
        rebases8188.addAll(decodeRebases8188(rebasesStr))
        // The old persisted schema contains amounts/times, but no external-transfer signature.
        // Preserve those values and disclose the missing proof; never silently reset drawdown to current cash.
        restoredRebaseTimes8237 = rebases8188.filter { it.appliedSol != 0.0 }.mapTo(HashSet()) { it.atMs }
    }

    /** CapitalDrawdown7948.seed7948: keep the restored event when it describes this peak; never revalue the peak. */
    @Synchronized fun noteSeed8188(peakSol: Double, atMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val e = event8188
        if (e != null && e.atMs == atMs) return
        if (e != null && e.atMs != atMs) rebases8188.clear()
        event8188 = if (atMs > 0L && nowMs - atMs in 0L..60_000L) capture8188(peakSol, atMs, ORIGIN_SEEDED_8188)
        else PeakEvent8188(peakSol, atMs, Double.NaN, Double.NaN, Double.NaN, Double.NaN, ORIGIN_RESTORED_NO_COMPONENTS_8188,
            "saved before 8188; prior event ${e?.let { "${it.origin}@${it.atMs}" } ?: "none"}")
        persistQuietly8188()
    }

    /** CapitalDrawdown7948, after the peak rolled: a peak at a new time is recorded with its components now. */
    @Synchronized fun noteMove8188(before: CapitalDrawdown7948.Peak7948, after: CapitalDrawdown7948.Peak7948, nowMs: Long) {
        val origin = moveOrigin8188(before.peakSol, before.atMs, after.peakSol, after.atMs, nowMs) ?: return
        event8188 = capture8188(after.peakSol, after.atMs, origin)
        rebases8188.clear()
        persistQuietly8188()
    }

    /** An explicit proof-run re-anchor (8093) is its own event. */
    @Synchronized fun noteReanchor8188(peakSol: Double, atMs: Long) {
        event8188 = capture8188(peakSol, atMs, ORIGIN_REANCHOR_8188)
        rebases8188.clear()
        persistQuietly8188()
    }

    /** CapitalDrawdown7948: the corroborated part of [residualSol]; the uncorroborated rest is refused and logged. */
    @Synchronized fun corroborate8188(residualSol: Double, liquidDeltaSol: Double, principalDeltaSol: Double, peakSol: Double, nowMs: Long,
                                      external: (Double) -> Boolean): Double {
        val flow = liquidCorroboratedFlow8188(residualSol, liquidDeltaSol, principalDeltaSol)
        if (external(residualSol) && !external(flow)) {
            refused8188.incrementAndGet()
            addRebase8188(Rebase8188(nowMs, residualSol, liquidDeltaSol, 0.0, peakSol, peakSol, REBASE_REFUSED_8188))
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("DRAWDOWN_FLOW_NOT_IN_WALLET_REFUSED_8188")
                com.lifecyclebot.engine.ForensicLogger.lifecycle("DRAWDOWN_FLOW_NOT_IN_WALLET_REFUSED_8188",
                    "residual=${"%+.4f".format(residualSol)} liquidDelta=${"%+.4f".format(liquidDeltaSol)} principalDelta=${"%+.4f".format(principalDeltaSol)} peak=${"%.4f".format(peakSol)} action=peak_unchanged")
            } catch (_: Throwable) {}
            persistQuietly8188()
        }
        return flow
    }

    /** CapitalDrawdown7948: a rebase that was applied (value moved, time kept) is recorded beside the event. */
    @Synchronized fun noteRebase8188(from: CapitalDrawdown7948.Peak7948, to: CapitalDrawdown7948.Peak7948, residualSol: Double,
                                     appliedSol: Double, liquidDeltaSol: Double, nowMs: Long) {
        addRebase8188(Rebase8188(nowMs, residualSol, liquidDeltaSol, appliedSol, from.peakSol, to.peakSol, rebaseVerdict8188(residualSol, appliedSol)))
        if (to.atMs != from.atMs) event8188 = capture8188(to.peakSol, to.atMs, ORIGIN_NEW_HIGH_8188)
        persistQuietly8188()
    }

    private fun addRebase8188(r: Rebase8188) {
        rebases8188.addLast(r)
        while (rebases8188.size > MAX_REBASES_8188) rebases8188.pollFirst()
    }

    private fun persistQuietly8188() { try { persist8188?.invoke() } catch (_: Throwable) {} }

    /** Principal of open live rows the wallet tracker has never seen (counted only on an entry price). Cached 5 s. */
    fun unverifiedPrincipalSol8188(nowMs: Long = System.currentTimeMillis()): Double {
        unverifiedCache8188?.let { (at, v) -> if (nowMs - at in 0L..5_000L) return v }
        val v = try {
            CanonicalPositionAuthority6441.protectiveInventory7807("live")
                .filter { com.lifecyclebot.engine.HostWalletTokenTracker.getEntry(it.mint) == null && it.entryPriceUsd.isFinite() && it.entryPriceUsd > 0.0 }
                .sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
        } catch (_: Throwable) { 0.0 }
        unverifiedCache8188 = nowMs to v
        return v
    }

    /** The peak candidate CapitalDrawdown7948 may use: verified components only, never in paper. */
    fun verifiedPeakCandidate8188(candidateSol: Double): Double {
        val paper = try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        if (paper || !candidateSol.isFinite()) return verifiedCandidate8188(candidateSol, 0.0, paper)
        return verifiedCandidate8188(candidateSol, unverifiedPrincipalSol8188(), false)
    }

    private fun capture8188(peakSol: Double, atMs: Long, origin: String): PeakEvent8188 {
        val liquid = try { com.lifecyclebot.engine.BotService.status.walletSol } catch (_: Throwable) { Double.NaN }
        val principal = try { LiveRiskPolicy7807.liveEquitySol(0.0) } catch (_: Throwable) { Double.NaN }
        val hero = try { HeroSnapshotAuthority6503.current() } catch (_: Throwable) { null }
        val upnl = hero?.liveTotalUnrealizedSol ?: Double.NaN
        val provenance = if (hero == null) "hero6503=none" else
            "hero6503:${hero.source} ageMs=${(atMs - hero.atMs).coerceAtLeast(0L)} rev=${hero.journalRevision} liveOpen=${hero.liveOpenCount} " +
                (if (upnl.isFinite() && upnl > 0.0) "gainExcluded" else "lossIncluded")
        return PeakEvent8188(peakSol, atMs, liquid, principal, upnl, unverifiedPrincipalSol8188(atMs), origin, provenance)
    }

    /** The report fragment: the peak event's provenance and every rebase since it was set. */
    fun line8188(effective: CapitalDrawdown7948.Peak7948, nowMs: Long = System.currentTimeMillis()): String {
        val e = event8188
        fun f(v: Double) = if (v.isFinite()) "%.4f".format(v) else "-"
        val ev = if (e == null) "peakEvent8188=[none]" else
            "peakEvent8188=[recorded=${f(e.recordedSol)} ageMin=${if (e.atMs > 0L) ((nowMs - e.atMs) / 60_000L).toString() else "-"} " +
                "origin=${e.origin} liquid=${f(e.liquidSol)} principal=${f(e.openPrincipalSol)} upnl=${f(e.unrealisedSol)} " +
                "unverifiedPrincipalExcluded=${f(e.unverifiedPrincipalSol)} marks=${e.markProvenance}" +
                (if (e.atMs != effective.atMs) " STALE_EVENT_effectiveAt=${effective.atMs}" else "") + "]"
        val rs = rebases8188.toList()
        val applied = rs.filter { it.appliedSol != 0.0 }
        val unverified8237 = applied.filter { it.atMs in restoredRebaseTimes8237 }
        val last = rs.lastOrNull()
        return "$ev rebases8188=[applied=${applied.size} net=${"%+.4f".format(applied.sumOf { it.toSol - it.fromSol })} " +
            "refusedNotInWallet=${rs.count { it.verdict == REBASE_REFUSED_8188 }} refusedSettlement8223=${rs.count { it.verdict == REBASE_TRADE_SETTLEMENT_8223 }} totalRefused=${refused8188.get()} effective=${f(effective.peakSol)}" +
            " legacyRebasesUnverified8237=${unverified8237.size} legacyNetUnverifiedSol8237=${"%+.4f".format(unverified8237.sumOf { it.toSol - it.fromSol })}" +
            (if (unverified8237.isNotEmpty()) " legacyCorrection8237=UNRESOLVED_EXTERNAL_TRANSFER_PROOF" else "") +
            (if (last != null) " last=${last.verdict}:residual=${"%+.4f".format(last.residualSol)},liquid=${"%+.4f".format(last.liquidDeltaSol)}," +
                "${f(last.fromSol)}->${f(last.toSol)},ageMin=${(nowMs - last.atMs) / 60_000L}" else "") + "]" +
            (reconciled8188?.let { r -> " reconciled8188=[legacy=${f(r.legacySol)} -> ${f(r.reconciledSol)} by=${r.by} " +
                "refusedFlows=${"%+.4f".format(r.refusedFlowsSol)} witnesses=${r.witnesses} ageMin=${(nowMs - r.atMs) / 60_000L}]" } ?: "")
    }

    internal fun resetForTests8188() { event8188 = null; reconciled8188 = null; rebases8188.clear(); restoredRebaseTimes8237 = emptySet(); refused8188.set(0); unverifiedCache8188 = null; persist8188 = null }
}
