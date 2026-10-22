package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506
import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8304 §A_SEALED_TICKET_EXECUTES_OR_ENDS_BY_NAME_ONCE.
 *
 * 5.0.8302 live: FDG sealed 43 allows; MOONSHOT 11 tickets -> 2 exec, SHITCOIN 13 -> 0, EXPRESS 2 -> 0. The audit
 * (lifecycle_apk/AUDIT_TICKET_EXEC_8304.md) found four ways a sealed ticket was wasted or misnamed:
 *  1. one coin, two owners — TradeAuthorizer seals the requesting lane's ticket, then LaneExecutionCoordinator bound the
 *     election to the NEWEST executable FDG snapshot of ANY lane for the version (another lane's earlier allow), and
 *     refused the ticket it had just sealed as PREAUTH_LANE_TELEMETRY_ONLY_PRIMARY_<other>. The one live sealed intent
 *     for (mode, mint, version) is now that version's owner ([sealedOwner8304]).
 *  2. a reused seal without its causal feedback stamp — SpecialistPreauthSeal7834 reuses the version's live intent after
 *     a FRESH FDG BUY, but a terminal / superseded reservation had dropped that intent's stamp, so finality refused
 *     MISSING_FEEDBACK_STAMP_REVALIDATE_6715 (and the cooldown it left). The fresh decision now stamps it
 *     ([ensureCausalStamp8304]); the check itself is unchanged.
 *  3. a deterministic chart refusal after the seal — FlatChart8027 (DEAD_RUNNER / FLATLINE) runs in liveBuy; the same
 *     pure verdict now refuses before the seal ([preSealChartRefusal8304]).
 *  4. an advisory size verdict stamped onto a sealed ticket — a trader's pre-FDG re-size (CanonicalSizingBridge6532)
 *     borrowed the live intent's attemptId, so a SHITCOIN advisory ROUTE_MIN_PROMOTION refusal read as a post-seal
 *     sizing drop of a ticket it never touched ([advisorySizeOnSealedTicket8304]).
 * And it counts the conversion: per lane, tickets, executions and every named drop ([line8304]).
 */
object TicketExec8304 {

    private const val USDC_MINT_8304 = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private const val MAX_TICKETED_8304 = 4_000
    private const val TICKET_MEMORY_MS_8304 = 30L * 60_000L
    private const val WALLET_EVIDENCE_MAX_AGE_MS_8304 = 60_000L

    private class LaneBook8304 {
        val tickets = AtomicLong(0L)
        val exec = AtomicLong(0L)
        val dropped = AtomicLong(0L)
        val drops = ConcurrentHashMap<String, AtomicLong>()
    }

    private val books8304 = ConcurrentHashMap<String, LaneBook8304>()
    private val ticketed8304 = ConcurrentHashMap<String, Long>()

    private fun mode8304(): String = try { RuntimeModeAuthority.authority().name } catch (_: Throwable) { "PAPER" }

    private fun lane8304(lane: String): String =
        try { CanonicalLaneIdentity6506.canonical(lane) } catch (_: Throwable) { lane.trim().uppercase() }

    private fun book8304(mode: String, lane: String): LaneBook8304 = books8304.computeIfAbsent("$mode|$lane") { LaneBook8304() }

    /** Pure: the drop reason as a stable counter key (the inner stop after ':' is kept, up to 64 chars). */
    fun reasonKey8304(reason: String): String =
        reason.trim().uppercase().replace(Regex("[^A-Z0-9_:]"), "_").take(64).ifBlank { "UNNAMED" }

    // ── counting ──

    /** ToolkitSignalSheet.recordDeskStage, after its once-per-attempt dedupe: TICKET opens a ticket, EXEC executes it. */
    fun onStage8304(lane: String, stage: String, attemptId: String, nowMs: Long = System.currentTimeMillis()) {
        if (attemptId.isBlank()) return
        val st = stage.uppercase()
        if (st != "TICKET" && st != "EXEC") return
        val l = lane8304(lane)
        if (l.isBlank()) return
        val mode = mode8304()
        val key = "$mode|$l|$attemptId"
        if (st == "TICKET") {
            if (ticketed8304.size > MAX_TICKETED_8304) ticketed8304.entries.removeIf { nowMs - it.value > TICKET_MEMORY_MS_8304 }
            if (ticketed8304.putIfAbsent(key, nowMs) == null) book8304(mode, l).tickets.incrementAndGet()
        } else if (ticketed8304.remove(key) != null) {
            book8304(mode, l).exec.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TICKET_EXECUTED_8304_$l") } catch (_: Throwable) {}
        } else {
            try { PipelineHealthCollector.labelInc("EXEC_WITHOUT_TRACKED_TICKET_8304_$l") } catch (_: Throwable) {}
        }
    }

    /** ToolkitSignalSheet.terminalizeUnexecutedTicket7948: one sealed ticket ended unexecuted, by name. */
    fun onDrop8304(lane: String, attemptId: String, reason: String) {
        val l = lane8304(lane)
        if (l.isBlank()) return
        val mode = mode8304()
        val b = book8304(mode, l)
        val r = reasonKey8304(reason)
        if (b.drops.size > 64) b.drops.clear()
        b.drops.computeIfAbsent(r) { AtomicLong(0L) }.incrementAndGet()
        if (attemptId.isNotBlank() && ticketed8304.remove("$mode|$l|$attemptId") != null) b.dropped.incrementAndGet()
        try { PipelineHealthCollector.labelInc("TICKET_DROPPED_8304_$l") } catch (_: Throwable) {}
    }

    /** Pure: the report line for one lane, or null when the lane saw no ticket and no drop. */
    fun formatLine8304(lane: String, tickets: Long, exec: Long, dropped: Long, drops: Map<String, Long>): String? {
        if (tickets <= 0L && drops.isEmpty()) return null
        val inFlight = (tickets - exec - dropped).coerceAtLeast(0L)
        val conv = if (tickets > 0L) "%.0f%%".format(100.0 * exec / tickets) else "-"
        val top = drops.entries.sortedByDescending { it.value }.take(6).joinToString(",") { "${it.key}=${it.value}" }
        return "$lane ticketExec8304 tickets=$tickets exec=$exec droppedAfterTicket=$dropped inFlight=$inFlight conv=$conv drops=[${top.ifBlank { "NONE" }}]"
    }

    /** The current runtime mode's ticket -> exec line for [lane] (ToolkitSignalSheet causal funnel). */
    fun line8304(lane: String): String? {
        val b = books8304["${mode8304()}|${lane8304(lane)}"] ?: return null
        return formatLine8304(lane8304(lane), b.tickets.get(), b.exec.get(), b.dropped.get(), b.drops.mapValues { it.value.get() })
    }

    // ── 1. one coin, one owner ──

    /** Pure: the version's owner — the lane holding the one live sealed intent, else the FDG snapshot's lane. */
    fun sealedOwner8304(liveIntentLane: String?, snapshotLane: String?): String? {
        val live = liveIntentLane?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val snap = snapshotLane?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        if (live != null && snap != null && live != snap) {
            try { PipelineHealthCollector.labelInc("LANE_OWNER_FROM_LIVE_SEALED_INTENT_8304") } catch (_: Throwable) {}
        }
        return live ?: snap
    }

    /** The canonical lane of the live, sized, FDG-BUY intent sealed for (mode, [mint], [candidateVersion]), else null. */
    fun liveIntentLane8304(mint: String, candidateVersion: Long): String? = try {
        if (mint.isBlank() || candidateVersion <= 0L) null else {
            val mode = if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
            ExecutableOpenGate.activeExecutionIntent6519(mode, mint, candidateVersion)
                ?.takeIf { it.fdgAllowed && it.fdgVerdict == "BUY" && it.hardNoReasons.isEmpty() && it.resolvedSize > 0.0 }
                ?.canonicalLane?.let { lane8304(it) }?.takeIf { it.isNotBlank() }
        }
    } catch (_: Throwable) { null }

    /** ExecutableOpenGate.retireSupersededIntent7819: the retired lane's sealed ticket ends by name. */
    fun onSupersededTicket8304(oldLane: String, oldAttemptId: String, ownerLane: String) {
        if (oldAttemptId.isBlank()) return
        try {
            ToolkitSignalSheet.terminalizeUnexecutedTicket7948(oldLane, oldAttemptId, "RESEALED_TO_PRIMARY_${lane8304(ownerLane)}_7819")
        } catch (_: Throwable) {}
    }

    // ── 2. the fresh decision stamps a reused seal ──

    /**
     * SpecialistPreauthSeal7834.ensure, after a FRESH FDG BUY returned [intent] (reused or re-sealed): when the intent
     * carries no CausalFeedbackAuthority6715 stamp (a terminal / superseded reservation removed it), stamp it now — the
     * decision that is about to execute was just made under the current feedback state. An existing stamp is never
     * replaced (its staleness is still judged at finality). Returns true when a stamp was added.
     */
    fun ensureCausalStamp8304(intent: ExecutableOpenGate.ExecutionIntent, decisionScore: Int): Boolean = try {
        val cfa = com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715
        if (intent.attemptId.isBlank() || cfa.hasStamp8304(intent.attemptId)) false else {
            val score = try {
                com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
                    .currentForMint(intent.mint, intent.candidateVersion, intent.mode)?.score?.toInt()
            } catch (_: Throwable) { null } ?: decisionScore
            val stamped = cfa.stampDecision(intent.attemptId, intent.mint, intent.mode, intent.canonicalLane, score)
            if (stamped) try {
                PipelineHealthCollector.labelInc("CAUSAL_STAMP_ON_FRESH_RESEAL_8304_${lane8304(intent.canonicalLane)}")
                ForensicLogger.lifecycle("CAUSAL_STAMP_ON_FRESH_RESEAL_8304",
                    "attemptId=${intent.attemptId.take(28)} mint=${intent.mint.take(10)} lane=${intent.canonicalLane} version=${intent.candidateVersion} score=$score")
            } catch (_: Throwable) {}
            stamped
        }
    } catch (_: Throwable) { false }

    // ── 3. deterministic chart refusal before the seal ──

    /** TradeAuthorizer, before the seal: the live executor's DEAD_RUNNER / FLATLINE verdict on the current chart. */
    fun preSealChartRefusal8304(ts: com.lifecyclebot.data.TokenState, paper: Boolean): String? {
        if (paper) return null
        val why = try { FlatChart8027.preSealRefusal8304(ts) } catch (_: Throwable) { null } ?: return null
        try {
            PipelineHealthCollector.labelInc("PRESEAL_CHART_REFUSED_8304_$why")
            ForensicLogger.lifecycle("PRESEAL_CHART_REFUSED_8304", "mint=${ts.mint.take(10)} sym=${ts.symbol} why=$why action=refuse_before_ticket")
        } catch (_: Throwable) {}
        return "PRESEAL_${why}_8304"
    }

    // ── 4. advisory sizing never lands on a sealed ticket ──

    /** CanonicalSizingBridge6532: a pre-FDG re-size by [lane] while [ticketLane]'s sealed ticket holds the version. */
    fun advisorySizeOnSealedTicket8304(lane: String, ticketLane: String, executable: Boolean, reason: String) {
        try {
            val l = lane8304(lane)
            PipelineHealthCollector.labelInc("ADVISORY_SIZE_ON_SEALED_TICKET_8304_$l")
            if (!executable) PipelineHealthCollector.labelInc("ADVISORY_SIZE_REFUSAL_NOT_A_TICKET_DROP_8304_${l}_${reasonKey8304(reason).take(48)}")
            if (lane8304(ticketLane) != l) PipelineHealthCollector.labelInc("ADVISORY_SIZE_OTHER_LANE_TICKET_8304_$l")
        } catch (_: Throwable) {}
    }

    // ── 5. FDG sizing: real observed depth, and every zero size named ──

    /** Pure: the tick's liquidity when positive, else the observed (curve reserves / token map) depth, else 0. */
    fun depthUsd8304(tickLiquidityUsd: Double, observedLiquidityUsd: Double): Double = when {
        tickLiquidityUsd.isFinite() && tickLiquidityUsd > 0.0 -> tickLiquidityUsd
        observedLiquidityUsd.isFinite() && observedLiquidityUsd > 0.0 -> observedLiquidityUsd
        else -> 0.0
    }

    /** FinalDecisionGate.resolveExecutableSize7835: the depth the executor's entry snapshot accepts for [ts]. */
    fun sizingDepthUsd8304(ts: com.lifecyclebot.data.TokenState): Double {
        val tick = ts.lastLiquidityUsd
        if (tick.isFinite() && tick > 0.0) return tick
        val observed = try { TokenMapAuthority.observedLiquidityUsd(ts) } catch (_: Throwable) { 0.0 }
        val d = depthUsd8304(tick, observed)
        if (d > 0.0) try { PipelineHealthCollector.labelInc("FDG_SIZE_DEPTH_FROM_OBSERVED_8304") } catch (_: Throwable) {}
        return d
    }

    /** FinalDecisionGate.resolveExecutableSize7835: names why the FDG size is zero (SIZE_NOT_EXECUTABLE_7835); returns 0.0. */
    fun fdgSizeZero8304(lane: String, cause: String): Double {
        try {
            PipelineHealthCollector.labelInc("FDG_SIZE_ZERO_8304_${lane8304(lane)}_${reasonKey8304(cause).take(56)}")
        } catch (_: Throwable) {}
        return 0.0
    }

    // ── reconcile: a complete empty wallet read is a zero-balance proof ──

    /** Pure: [evidenceTokens] (the wallet cache) and the reconciler's [walletMints] (USDC removed) describe the same read. */
    fun sameWalletTokens8304(evidenceTokens: Map<String, CanonicalTokenAmount>, walletMints: Map<String, CanonicalTokenAmount>): Boolean =
        evidenceTokens.filterKeys { it != USDC_MINT_8304 } == walletMints.filterKeys { it != USDC_MINT_8304 }

    /** Pure: may an EMPTY [walletMints] stand as a complete wallet read? */
    fun emptyReadProven8304(walletMints: Map<String, CanonicalTokenAmount>, evidence: WalletAccountCache.Evidence?,
                            owner: String, nowMs: Long): Boolean =
        walletMints.isEmpty() && evidence != null && evidence.complete && owner.isNotBlank() && evidence.owner == owner &&
            nowMs - evidence.observedAtMs in 0L..WALLET_EVIDENCE_MAX_AGE_MS_8304 &&
            sameWalletTokens8304(evidence.tokens, walletMints)

    /** ReconcileRunner8144.noteWalletSnapshot8144: the runtime read of [emptyReadProven8304]. */
    fun completeEmptyWalletRead8304(walletMints: Map<String, CanonicalTokenAmount>, nowMs: Long = System.currentTimeMillis()): Boolean {
        val owner = try { WalletManager.currentPubkey() } catch (_: Throwable) { "" }
        val ok = emptyReadProven8304(walletMints, try { WalletAccountCache.evidence() } catch (_: Throwable) { null }, owner, nowMs)
        try { PipelineHealthCollector.labelInc(if (ok) "RECONCILE_EMPTY_WALLET_COMPLETE_8304" else "RECONCILE_EMPTY_WALLET_UNPROVEN_8304") } catch (_: Throwable) {}
        return ok
    }

    internal fun resetForTests8304() { books8304.clear(); ticketed8304.clear() }
}
