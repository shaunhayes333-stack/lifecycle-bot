package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.sell.BalanceProofWaitState
import com.lifecyclebot.engine.sell.ExitTruthFrozenLedger8144
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
 *  - [settle8141]      Executor.frozenHandOff8122 too: blacklist for buys, canonical row quarantined (non-protective),
 *                      runtime slot and exposure released. Never a sale, never a zero balance.
 *  - [onThaw8141]      BalanceProofPoller (frozenHold8136 thaw path, and [sweepThaw8141] on its tick): the held position
 *                      comes back, the canonical row becomes protective inventory again and a normal sell is queued.
 *
 * V5.0.8144 — economic finality (ExitTruthFrozenLedger8144). 8141 published ONE finalised terminal (a whole-position
 * loss) for tokens still in the wallet, then re-opened the position on a thaw. A frozen account proves present
 * unsellability, not a sale. The holding is now recorded in the persisted frozen-holdings sub-ledger (realised =
 * confirmed fills only; the remainder is unsellable inventory with a recoverable estimate; the write-down is an
 * impairment signalled once to learning, never a close on the finalized trade bus). A terminal is published only on
 * ACTUAL closure (nothing held). The slot release is reversible: a thaw — even after a restart, from the sub-ledger —
 * restores the same position (same positionId and entry time), so the eventual sale is its one outcome.
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
    private val impaired = AtomicLong(0L)
    @Volatile private var rebuilt8144 = false
    /**
     * V5.0.8144 — mints a wallet read has shown FROZEN in this process. The frozen-state authority starts empty after a
     * restart, so "not frozen" proves a thaw only for a mint seen frozen since boot (no false thaw of a restored row).
     */
    private val seenFrozen8144 = ConcurrentHashMap.newKeySet<String>()

    private fun label(key: String) { try { PipelineHealthCollector.labelInc(key) } catch (_: Throwable) {} }

    /**
     * V5.0.8144 — after a restart the in-memory registry is empty; it is rebuilt once from the persisted sub-ledger so a
     * holding frozen before the restart is still refused a sell, swept for a thaw and never settled (impaired) twice.
     */
    private fun rebuild8144() {
        if (rebuilt8144) return
        if (com.lifecyclebot.AATEApp.appContextOrNull() == null) return   // no store yet: try again on the next call
        rebuilt8144 = true
        var n = 0
        for (e in ExitTruthFrozenLedger8144.heldEntries8144()) {
            if (FrozenHeldUnsellable8141.restore8144(e.mint, e.positionId, e.symbol, e.desiredExitReason, e.frozenAtMs)) n++
        }
        if (n > 0) {
            label("FROZEN_HELD_REBUILT_FROM_SUBLEDGER_8144")
            try { ForensicLogger.lifecycle("FROZEN_HELD_REBUILT_FROM_SUBLEDGER_8144", "held=$n action=registry_rebuilt_after_restart") } catch (_: Throwable) {}
        }
    }

    /** Executor.requestSellCore7948: true = this live frozen holding is FROZEN_HELD_UNSELLABLE and no sell may be routed. */
    fun refusesSell8141(ts: TokenState, reason: String): Boolean {
        if (ts.mint.isBlank() || ts.position.isPaperPosition) return false
        val frozen = FrozenHoldingGuard8136.isFrozen8136(ts.mint)
        if (!frozen) return false
        seenFrozen8144.add(ts.mint)
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
     * (or re-settled after a thaw / a restart) it.
     */
    fun settle8141(ts: TokenState, reason: String): Boolean {
        val pos = ts.position
        val mint = ts.mint
        if (mint.isBlank() || pos.isPaperPosition || !pos.isOpen) return false
        rebuild8144()
        seenFrozen8144.add(mint)   // both callers settle only on a frozen read
        val canon = canonicalRow8141(mint)
        val pid = canon?.positionId?.takeIf { it.isNotBlank() } ?: pos.positionId
        val transition = FrozenHeldUnsellable8141.enter8141(mint, pid, ts.symbol, reason)
        if (transition == FrozenHeldUnsellable8141.Transition8141.NONE) return resettleResurrected8144(ts, pos, reason)
        if (held.size < CAP_8141 || held.containsKey(mint)) held[mint] = ts to pos
        try { TokenBlacklist.block(mint, FrozenHeldUnsellable8141.BLACKLIST_REASON_8141) } catch (_: Throwable) {}
        // V5.0.8144 — the holding goes into the persisted sub-ledger first (realised / unsellable / impairment kept apart).
        val step8144 = recordFrozen8144(ts, pos, canon, pid, reason)
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
                    "subLedger=${step8144.name} terminalPublished=$published reason=${reason.take(60)} " +
                    "action=no_sell_no_ghost_exit_slot_released_held_in_subledger_tracked_for_thaw",
            )
        } catch (_: Throwable) {}
        return true
    }

    /**
     * V5.0.8144 — the registry already holds this mint, yet a runtime position for it is open again (a restart or a
     * recovery path resurrected it while the account is still frozen and the sub-ledger still holds it): release the
     * slot again — no second impairment, no terminal.
     */
    private fun resettleResurrected8144(ts: TokenState, pos: Position, reason: String): Boolean {
        val row = ExitTruthFrozenLedger8144.heldForMint8144(ts.mint) ?: return false
        if (pos.entryTime != row.entryTimeMs && pos.positionId != row.positionId) return false
        if (held.size < CAP_8141 || held.containsKey(ts.mint)) held[ts.mint] = ts to pos
        releaseRuntime8141(ts, reason)
        label("FROZEN_HELD_RESURRECTED_RELEASED_8144")
        return true
    }

    /** V5.0.8144 — the frozen holding's row in the sub-ledger; the first freeze of a held position signals ONE impairment. */
    private fun recordFrozen8144(ts: TokenState, pos: Position, canon: CanonicalPositionAuthority6441.Position?, pid: String,
                                 reason: String): ExitTruthFrozenLedger8144.FreezeStep8144 {
        val now = System.currentTimeMillis()
        // Realised = confirmed fills only: the canonical slices plus confirmed legacy-fallback slices (deduplicated).
        val fallback = try { RecoveryFallbackLedger8141.confirmedFallbackSol8141(pid) } catch (_: Throwable) { 0.0 }
        val realised = RecoveryFallbackLedger8141.effectiveProceeds8141(canon?.realizedProceedsSol ?: 0.0, fallback)
        val entryCost = canon?.entryCostSol?.takeIf { it.isFinite() && it > 0.0 } ?: pos.costSol
        // Nothing held = the canonical row was already fully sold by confirmed fills (the runtime row was stale).
        val heldInventory = canon == null || canon.remainingQtyRaw > java.math.BigInteger.ONE
        val snap = try { com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.snapshot(pid) } catch (_: Throwable) { null }
        val lane = snap?.entryLane?.takeIf { it.isNotBlank() } ?: canon?.lane?.takeIf { it.isNotBlank() } ?: pos.tradingMode
        val entry = ExitTruthFrozenLedger8144.Entry8144(
            positionId = pid, mint = ts.mint, symbol = ts.symbol, lane = lane,
            entryStrategy = snap?.let { s -> s.entryTactic.ifBlank { s.entryStrategyPid } }.orEmpty(),
            entryTimeMs = pos.entryTime, entryPrice = pos.entryPrice, qtyToken = pos.qtyToken,
            entryCostSol = entryCost,
            // The runtime cost is the basis still held (canonical and legacy slices both reduce it).
            remainingCostBasisSol = pos.costSol,
            realisedProceedsSol = realised,
            feesSol = canon?.feesSol ?: 0.0,
            // The owner can neither sell, transfer nor close a frozen account: nothing is recoverable while it is frozen.
            recoverableValueSol = 0.0,
            partialSoldPct = pos.partialSoldPct, peakGainPct = pos.peakGainPct,
            entryPriceSource = pos.entryPriceSource, entryPoolAddress = pos.entryPoolAddress, entryDex = pos.entryDex,
            desiredExitReason = reason, frozenAtMs = now,
        )
        val step = ExitTruthFrozenLedger8144.onFreeze8144(entry, heldInventory, now)
        if (step == ExitTruthFrozenLedger8144.FreezeStep8144.FIRST_IMPAIRMENT) {
            impaired.incrementAndGet()
            val split = entry.split
            label("FROZEN_IMPAIRMENT_SIGNAL_8144")
            try {
                ForensicLogger.lifecycle(
                    "FROZEN_IMPAIRMENT_SIGNAL_8144",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} positionId=${pid.take(24)} lane=$lane strategy=${entry.entryStrategy.take(32)} " +
                        "realisedProceedsSol=${"%.6f".format(split.realisedProceedsSol)} realisedPnlSol=${"%.6f".format(split.realisedPnlSol)} " +
                        "unsellableCostSol=${"%.6f".format(split.unsellableCostBasisSol)} recoverableSol=${"%.6f".format(split.recoverableValueSol)} " +
                        "impairmentSol=${"%.6f".format(split.impairmentSol)} action=impairment_not_a_close",
                )
            } catch (_: Throwable) {}
        }
        return step
    }

    /**
     * A terminal result — published only on ACTUAL terminal closure (V5.0.8144): the sub-ledger records nothing held
     * (the canonical row was already fully sold by confirmed fills). A frozen holding still in the wallet publishes
     * nothing here; it is impaired in the sub-ledger, and its terminal is the sale a thaw makes possible.
     */
    private fun publishTerminal8141(ts: TokenState, pos: Position, canon: CanonicalPositionAuthority6441.Position?, pid: String): Boolean {
        if (pid.isBlank()) return false
        if (!ExitTruthFrozenLedger8144.terminalClosed8144(pid)) {
            label("FROZEN_TERMINAL_WITHHELD_INVENTORY_HELD_8144")
            return false
        }
        val fallback8144 = try { RecoveryFallbackLedger8141.confirmedFallbackSol8141(pid) } catch (_: Throwable) { 0.0 }
        val econ = if (canon != null) FrozenHeldUnsellable8141.economics8141(canon.entryCostSol,
                RecoveryFallbackLedger8141.effectiveProceeds8141(canon.realizedProceedsSol, fallback8144), canon.feesSol)
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
                    // Every unit was sold by confirmed fills before the freeze: the economics are realised wallet facts.
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
     * V5.0.8144 — after a restart the settled TokenState is gone: the runtime position is rebuilt from the persisted
     * sub-ledger row (same positionId, entry time, quantity and basis — no new buy, no second lifetime position).
     */
    private fun restoreFromSubLedger8144(mint: String, row: ExitTruthFrozenLedger8144.Entry8144): Boolean {
        if (row.qtyToken <= 0.0 || row.entryTimeMs <= 0L) return false
        val position = Position(
            qtyToken = row.qtyToken, entryPrice = row.entryPrice, entryTime = row.entryTimeMs, costSol = row.remainingCostBasisSol,
            peakGainPct = row.peakGainPct, entryPriceSource = row.entryPriceSource, entryPoolAddress = row.entryPoolAddress,
            entryDex = row.entryDex, isPaperPosition = false, tradingMode = row.lane.ifBlank { "STANDARD" },
            positionId = row.positionId, partialSoldPct = row.partialSoldPct,
        )
        val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null }
            ?: TokenState(mint = mint, symbol = row.symbol, position = position, source = "FROZEN_THAW_RESTORED_8144")
        synchronized(ts) { if (!ts.position.isOpen) ts.position = position }
        try { if (!BotService.status.tokens.containsKey(mint)) BotService.status.tokens[mint] = ts } catch (_: Throwable) {}
        try { PositionPersistence.savePosition(ts) } catch (_: Throwable) {}
        label("FROZEN_HELD_RESTORED_FROM_SUBLEDGER_8144")
        return true
    }

    /**
     * The account thawed: the held position is a normal position again. The runtime position is restored, the canonical
     * row becomes protective inventory (the exit authority manages and sells it) and, when the poller holds no wait that
     * would hand it back itself, a normal sell is queued. The mint stays blacklisted for buys. Idempotent.
     */
    fun onThaw8141(mint: String): Boolean {
        rebuild8144()
        if (FrozenHeldUnsellable8141.stateOf8141(mint) != FrozenHeldUnsellable8141.State8141.FROZEN_HELD_UNSELLABLE) return false
        if (mint !in seenFrozen8144) {
            // V5.0.8144 — restored from the sub-ledger after a restart and no wallet read has shown its account yet.
            label("FROZEN_THAW_UNPROVEN_AFTER_RESTART_8144")
            return false
        }
        val rec = FrozenHeldUnsellable8141.thaw8141(mint) ?: return false
        // V5.0.8144 — the sub-ledger row is restored once (no second lifetime position); the impairment is released.
        val row8144 = try { ExitTruthFrozenLedger8144.onThaw8144(rec.positionId, mint, System.currentTimeMillis()) } catch (_: Throwable) { null }
        val snap = held.remove(mint)
        if (try { ExitTruthFrozenLedger8144.terminalClosed8144(rec.positionId) } catch (_: Throwable) { false }) {
            // Already closed through the normal path (its one terminal): nothing is held, nothing is restored.
            label("FROZEN_THAW_AFTER_CLOSE_NOT_RESTORED_8144")
            return true
        }
        if (snap != null) {
            val ts = snap.first
            synchronized(ts) { if (!ts.position.isOpen) ts.position = snap.second }
            try { if (!BotService.status.tokens.containsKey(mint)) BotService.status.tokens[mint] = ts } catch (_: Throwable) {}
            try { PositionPersistence.savePosition(ts) } catch (_: Throwable) {}
        } else if (row8144 != null) {
            restoreFromSubLedger8144(mint, row8144)
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
        if (row8144 != null) label("FROZEN_IMPAIRMENT_RELEASED_ON_THAW_8144")
        try {
            ForensicLogger.lifecycle(
                "FROZEN_HELD_THAWED_REOPENED_8141",
                "mint=${mint.take(10)} symbol=${rec.symbol} positionId=${rec.positionId.take(24)} restored=${snap != null || row8144 != null} " +
                    "fromSubLedger=${snap == null && row8144 != null} action=reopen_normal_sell_same_position",
            )
        } catch (_: Throwable) {}
        return true
    }

    /**
     * BalanceProofPoller.onZeroRead7733: [mint] is held FROZEN in the sub-ledger (rebuilt after a restart) and no wallet
     * read has shown its account since boot — an absent / zero read is the freeze hiding the account, not an empty wallet.
     */
    fun heldUnproven8144(mint: String): Boolean {
        if (mint.isBlank() || mint in seenFrozen8144) return false
        rebuild8144()
        return FrozenHeldUnsellable8141.stateOf8141(mint) == FrozenHeldUnsellable8141.State8141.FROZEN_HELD_UNSELLABLE
    }

    /**
     * BalanceProofPoller (BALANCE_PROOF_READY): a positive wallet balance for a mint the sub-ledger holds frozen — the
     * wallet read lists only unfrozen accounts, so the holding thawed (possibly while the app was down): restore it
     * before its sell is handed back. True when it thawed here.
     */
    fun onBalanceProven8144(mint: String): Boolean {
        if (mint.isBlank() || FrozenHoldingGuard8136.isFrozen8136(mint)) return false
        rebuild8144()
        if (FrozenHeldUnsellable8141.stateOf8141(mint) != FrozenHeldUnsellable8141.State8141.FROZEN_HELD_UNSELLABLE) return false
        seenFrozen8144.add(mint)
        label("FROZEN_THAW_PROVEN_BY_BALANCE_8144")
        return onThaw8141(mint)
    }

    /** TailBag8034.onClose8034 (canonical terminal fanout): a thawed holding sold — its sub-ledger row is closed. */
    fun onTerminal8144(positionId: String) {
        if (positionId.isBlank()) return
        if (try { ExitTruthFrozenLedger8144.onTerminal8144(positionId) } catch (_: Throwable) { false }) label("FROZEN_SUBLEDGER_CLOSED_BY_SALE_8144")
    }

    /** BalanceProofPoller tick: a settled holding whose account a wallet read has seen unfrozen thaws (bounded, every 30 s). */
    fun sweepThaw8141(nowMs: Long = System.currentTimeMillis()) {
        val last = lastSweepMs.get()
        if (nowMs - last < SWEEP_MS_8141 || !lastSweepMs.compareAndSet(last, nowMs)) return
        rebuild8144()
        for (mint in FrozenHeldUnsellable8141.heldMints8141()) {
            if (FrozenHoldingGuard8136.isFrozen8136(mint)) seenFrozen8144.add(mint) else onThaw8141(mint)
        }
    }

    fun statusLine8141(): String =
        "frozenHeld=${FrozenHeldUnsellable8141.heldCount8141()} settled=${settled.get()} thawed=${thawed.get()} refused=${refused.get()} " +
            "impaired8144=${impaired.get()} " + (try { ExitTruthFrozenLedger8144.statusLine8144() } catch (_: Throwable) { "subLedger[unavailable]" })
}
