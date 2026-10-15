package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.sell.BalanceProofWaitState
import com.lifecyclebot.engine.sell.FrozenHeldUnsellable8141
import com.lifecyclebot.engine.sell.FrozenHoldingGuard8136
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8141 — the live doors of FROZEN_HELD_UNSELLABLE (the pure rules and the registry live in
 * engine/sell/FrozenHeldUnsellable8141).
 *
 *  - [refusesSell8141] Executor.requestSellCore7948: a live position whose token account the issuer froze is settled
 *                      once and never routed (no BALANCE_WAIT_MERGE and no wallet read every exit tick).
 *  - [settle8141]      Executor.frozenHandOff8122 too: blacklist for buys, ONE terminal result (zero recoverable
 *                      proceeds, exit reason FROZEN_HELD_UNSELLABLE_8141), canonical row quarantined (non-protective),
 *                      runtime slot and exposure released. Never a sale, never a zero balance.
 *  - [onThaw8141]      BalanceProofPoller (frozenHold8136 thaw path, and [sweepThaw8141] on its tick): the held position
 *                      comes back, the canonical row becomes protective inventory again and a normal sell is queued.
 */
object FrozenHeldUnsellableLive8141 {
    private const val CAP_8141 = 512
    private const val SWEEP_MS_8141 = 30_000L
    private val RELEASE_LANES_8141 = listOf(
        "SHITCOIN", "MOONSHOT", "BLUECHIP", "QUALITY", "TREASURY", "MANIPULATED", "DIP_HUNTER", "PROJECT_SNIPER",
        "EXPRESS", "CORE",
    )

    /** The runtime TokenState and the position it held when settled, per mint (restored on a thaw). */
    private val held = ConcurrentHashMap<String, Pair<TokenState, Position>>()
    private val lastSweepMs = AtomicLong(0L)
    private val settled = AtomicLong(0L)
    private val thawed = AtomicLong(0L)
    private val refused = AtomicLong(0L)

    private fun label(key: String) { try { PipelineHealthCollector.labelInc(key) } catch (_: Throwable) {} }

    /** Executor.requestSellCore7948: true = this live frozen holding is FROZEN_HELD_UNSELLABLE and no sell may be routed. */
    fun refusesSell8141(ts: TokenState, reason: String): Boolean {
        if (ts.mint.isBlank() || ts.position.isPaperPosition) return false
        val frozen = FrozenHoldingGuard8136.isFrozen8136(ts.mint)
        if (!frozen) return false
        if (ts.position.isOpen) settle8141(ts, reason)
        val refuse = FrozenHeldUnsellable8141.refusesSell8141(ts.mint, frozen)
        if (refuse) {
            refused.incrementAndGet()
            label("SELL_REFUSED_FROZEN_HELD_UNSELLABLE_8141")
        }
        return refuse
    }

    /** The live canonical row this mint's runtime position stands on: an open one first, else the newest frozen quarantine. */
    private fun canonicalRow8141(mint: String): CanonicalPositionAuthority6441.Position? = try {
        CanonicalPositionAuthority6441.openPositions().firstOrNull { it.mint == mint && it.mode.equals("live", true) }
            ?: CanonicalPositionAuthority6441.quarantinedLivePositions7454(mint)
                .filter { it.quarantineReason.startsWith("FROZEN_TOKEN_ACCOUNT_7253") }
                .maxByOrNull { it.lastMutationMs }
    } catch (_: Throwable) { null }

    /**
     * Settle a live frozen holding as FROZEN_HELD_UNSELLABLE (idempotent per mint). Returns true when this call settled
     * (or re-settled after a thaw) it.
     */
    fun settle8141(ts: TokenState, reason: String): Boolean {
        val pos = ts.position
        val mint = ts.mint
        if (mint.isBlank() || pos.isPaperPosition || !pos.isOpen) return false
        val canon = canonicalRow8141(mint)
        val pid = canon?.positionId?.takeIf { it.isNotBlank() } ?: pos.positionId
        val transition = FrozenHeldUnsellable8141.enter8141(mint, pid, ts.symbol, reason)
        if (transition == FrozenHeldUnsellable8141.Transition8141.NONE) return false
        if (held.size < CAP_8141 || held.containsKey(mint)) held[mint] = ts to pos
        try { TokenBlacklist.block(mint, FrozenHeldUnsellable8141.BLACKLIST_REASON_8141) } catch (_: Throwable) {}
        val published = transition == FrozenHeldUnsellable8141.Transition8141.SETTLE && publishTerminal8141(ts, pos, canon, pid)
        if (canon != null) {
            try {
                CanonicalPositionAuthority6441.quarantine(canon.positionId, FrozenHeldUnsellable8141.quarantineReasonFor8141(canon.quarantineReason))
            } catch (_: Throwable) {}
        }
        releaseRuntime8141(ts, reason)
        // The poller keeps the slow frozen re-check that sees a thaw; the wait is never a sell worker.
        try { if (!BalanceProofWaitState.isWaiting(mint)) BalanceProofWaitState.markWaiting(mint, ts.symbol, reason) } catch (_: Throwable) {}
        settled.incrementAndGet()
        label("FROZEN_HELD_UNSELLABLE_8141_${transition.name}")
        try {
            ForensicLogger.lifecycle(
                "FROZEN_HELD_UNSELLABLE_8141",
                "mint=${mint.take(10)} symbol=${ts.symbol} positionId=${pid.take(24)} transition=${transition.name} " +
                    "terminalPublished=$published reason=${reason.take(60)} action=no_sell_no_ghost_exit_slot_released_tracked_for_thaw",
            )
        } catch (_: Throwable) {}
        return true
    }

    /** ONE terminal result: realised proceeds so far, zero recoverable for the frozen remainder (canonical bus dedupes per position). */
    private fun publishTerminal8141(ts: TokenState, pos: Position, canon: CanonicalPositionAuthority6441.Position?, pid: String): Boolean {
        if (pid.isBlank()) return false
        val econ = if (canon != null) FrozenHeldUnsellable8141.economics8141(canon.entryCostSol, canon.realizedProceedsSol, canon.feesSol)
            else FrozenHeldUnsellable8141.economics8141(pos.costSol, 0.0, 0.0)
        if (econ == null) return false
        return try {
            val now = System.currentTimeMillis()
            val snap = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.snapshot(pid)
            val startedAt = snap?.entryTimestampMs ?: pos.entryTime
            CanonicalTradeFinalizedBus6450.publish(
                CanonicalTradeFinalizedBus6450.Event(
                    positionId = pid,
                    mint = ts.mint,
                    outcome = CanonicalTradeFinalizedBus6450.Outcome.valueOf(FrozenHeldUnsellable8141.outcomeOf8141(econ.netReturnPct)),
                    netRealizedPnlSol = econ.netPnlSol,
                    grossRealizedPnlSol = econ.grossPnlSol,
                    returnFraction = econ.returnFraction,
                    netReturnPct = econ.netReturnPct,
                    feesSol = econ.feesSol,
                    entryLane = snap?.entryLane?.takeIf { it.isNotBlank() } ?: canon?.lane?.takeIf { it.isNotBlank() } ?: pos.tradingMode,
                    entryStrategyPid = snap?.entryStrategyPid ?: "",
                    entryTactic = snap?.entryTactic ?: "",
                    exitReason = FrozenHeldUnsellable8141.EXIT_REASON_8141,
                    holdingTimeMs = if (startedAt > 0L) (now - startedAt).coerceAtLeast(0L) else 0L,
                    dataQuality = FrozenHeldUnsellable8141.DATA_QUALITY_8141,
                    priceIntegrity = FrozenHeldUnsellable8141.PRICE_INTEGRITY_8141,
                    mode = "live",
                    settledAtMs = now,
                    economicEventId = "${FrozenHeldUnsellable8141.EXIT_REASON_8141}:$pid",
                    // The parsed token account (state=frozen) proves the recoverable value is zero: a reconciled wallet fact.
                    economicFinality = "LIVE_RECONCILED",
                ),
            )
        } catch (_: Throwable) { false }
    }

    /** Release the runtime slot and exposure (the 8094 retirement shape) without recording a sale or a zero balance. */
    private fun releaseRuntime8141(ts: TokenState, reason: String) {
        val now = System.currentTimeMillis()
        synchronized(ts) {
            ts.position = Position()
            if (ts.lastExitTs < now) ts.lastExitTs = now
        }
        try { PositionPersistence.savePosition(ts) } catch (_: Throwable) {}
        try { BotService.recentlyClosedMs[ts.mint] = now } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.ExitTelemetryStamper6732.clearTrigger7807(ts.mint) } catch (_: Throwable) {}
        for (lane in RELEASE_LANES_8141) {
            try { LaneExecutionCoordinator.releaseIfPrimary(ts.mint, lane, "FROZEN_HELD_UNSELLABLE_8141") } catch (_: Throwable) {}
        }
        try {
            com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.reconcileActiveFromCanonical6489(CanonicalPositionAuthority6441.openPositions())
        } catch (_: Throwable) {}
        label("FROZEN_HELD_SLOT_RELEASED_8141")
    }

    /**
     * The account thawed: the held position is a normal position again. The runtime position is restored, the canonical
     * row becomes protective inventory (the exit authority manages and sells it) and, when the poller holds no wait that
     * would hand it back itself, a normal sell is queued. The mint stays blacklisted for buys. Idempotent.
     */
    fun onThaw8141(mint: String): Boolean {
        val rec = FrozenHeldUnsellable8141.thaw8141(mint) ?: return false
        val snap = held.remove(mint)
        if (snap != null) {
            val ts = snap.first
            synchronized(ts) { if (!ts.position.isOpen) ts.position = snap.second }
            try { if (!BotService.status.tokens.containsKey(mint)) BotService.status.tokens[mint] = ts } catch (_: Throwable) {}
            try { PositionPersistence.savePosition(ts) } catch (_: Throwable) {}
        }
        try {
            CanonicalPositionAuthority6441.quarantinedLivePositions7454(mint)
                .filter { it.quarantineReason.startsWith("FROZEN_TOKEN_ACCOUNT_7253") }
                .forEach { CanonicalPositionAuthority6441.quarantine(it.positionId, FrozenHeldUnsellable8141.THAW_QUARANTINE_REASON_8141) }
        } catch (_: Throwable) {}
        try {
            if (!BalanceProofWaitState.isWaiting(mint)) {
                PendingSellQueue.add(mint, rec.symbol, FrozenHeldUnsellable8141.thawExitReason8141(rec.desiredExitReason))
            }
        } catch (_: Throwable) {}
        thawed.incrementAndGet()
        label("FROZEN_HELD_THAWED_REOPENED_8141")
        try {
            ForensicLogger.lifecycle(
                "FROZEN_HELD_THAWED_REOPENED_8141",
                "mint=${mint.take(10)} symbol=${rec.symbol} positionId=${rec.positionId.take(24)} restored=${snap != null} " +
                    "action=reopen_normal_sell",
            )
        } catch (_: Throwable) {}
        return true
    }

    /** BalanceProofPoller tick: a settled holding whose account a wallet read has seen unfrozen thaws (bounded, every 30 s). */
    fun sweepThaw8141(nowMs: Long = System.currentTimeMillis()) {
        val last = lastSweepMs.get()
        if (nowMs - last < SWEEP_MS_8141 || !lastSweepMs.compareAndSet(last, nowMs)) return
        for (mint in FrozenHeldUnsellable8141.heldMints8141()) {
            if (!FrozenHoldingGuard8136.isFrozen8136(mint)) onThaw8141(mint)
        }
    }

    fun statusLine8141(): String =
        "frozenHeld=${FrozenHeldUnsellable8141.heldCount8141()} settled=${settled.get()} thawed=${thawed.get()} refused=${refused.get()}"
}
