package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.sell.StopLatencyCore8153.HeldProof8153
import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import com.lifecyclebot.network.SignedTxLedger8085
import com.lifecyclebot.network.SolanaWallet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8153 — STOP LATENCY wiring: StopLatencyCore8153's decisions applied to the live sell path and the prewarm.
 *
 *  - [hotPathBalances8153]: liveSell's sold-mint read and tryPumpPortalSell's 95% / pre-sell read. An emergency
 *    answers from the proven holding (no read, no multi-provider corroboration) or from one bounded PARALLEL read
 *    (single-mint ladder ‖ multi-provider owner+mint); null keeps the caller's legacy read.
 *  - [provenHolding8153]: the newest usable proof — the 8153 proof cache, the 8144 multi-provider cache, or a young
 *    wallet snapshot entry (a POSITIVE entry proves a holding even in a partial snapshot; only absence needs a
 *    complete one). Spent by any order signed or resolved for the mint after the read.
 *  - [startPrewarm8153]: every [StopLatencyCore8153.PREWARM_TICK_MS_8153] the open live positions get their balance
 *    proof and confirmed venue refreshed on the low-priority prewarm lane, latched exits first.
 */
object StopLatencyWiring8153 {

    /** A snapshot is stamped when its (up to ~11 s) whole-wallet read FINISHED: its data is treated as that much older. */
    private const val SNAPSHOT_READ_SPAN_MS_8153 = 12_000L
    /** The 8144 multi-provider cache is stamped at the end of its (<= 3 s) read. */
    private const val MULTI_PROVIDER_READ_SPAN_MS_8153 = 3_000L

    /** True when the exit for [mint] is an emergency (its reason, or the open sell spine). */
    fun emergencyNow8153(mint: String, reason: String?): Boolean =
        (try { ProtectiveExitClass7807.isEmergency(reason) } catch (_: Throwable) { false }) ||
            (try { SellRouteSpine8144.emergencyOpen8153(mint) } catch (_: Throwable) { false })

    /** (last time any order for [mint] was signed or changed state, a signed sell is unresolved). */
    private fun ledgerState8153(mint: String): Pair<Long, Boolean> = try {
        val rows = SignedTxLedger8085.historyFor8090(mint)
        val last = rows.maxOfOrNull { maxOf(it.createdAtMs, it.updatedAtMs) } ?: 0L
        last to rows.any { it.side == "SELL" && it.unresolved }
    } catch (_: Throwable) { Long.MAX_VALUE to true }

    /** The newest usable proven holding for [mint], or null. Never reads the network. */
    fun provenHolding8153(mint: String, nowMs: Long = System.currentTimeMillis()): HeldProof8153? {
        if (mint.isBlank()) return null
        val (lastTouch, unresolved) = ledgerState8153(mint)
        if (unresolved) return null
        val multi = try {
            SellRouteProbe8144.cachedBalanceAt8153(mint, nowMs)?.takeIf { SellRouteProbe8144.held8144(it.first) }
                ?.let { HeldProof8153(it.first.raw, it.first.decimals, "MULTI_PROVIDER_8144", it.second - MULTI_PROVIDER_READ_SPAN_MS_8153) }
        } catch (_: Throwable) { null }
        val snap = try {
            val at = com.lifecyclebot.engine.WalletAccountCache.snapshotAtMs8094()
            com.lifecyclebot.engine.WalletAccountCache.snapshot(ttlMs = StopLatencyCore8153.HOT_PROOF_TTL_MS_8153)?.get(mint)
                ?.let { HeldProof8153(it.raw, it.decimals, "WALLET_SNAPSHOT", at - SNAPSHOT_READ_SPAN_MS_8153) }
        } catch (_: Throwable) { null }
        return StopLatencyCore8153.newestUsable8153(listOf(StopLatencyCore8153.proof8153(mint), multi, snap), nowMs, lastTouch, false)
    }

    /** Keep a live owner+mint read as a proof (stamped at the read's START, so a sell signed meanwhile spends it). */
    private fun noteHolding8153(mint: String, raw: java.math.BigInteger, decimals: Int, source: String, readStartedAtMs: Long) {
        try { StopLatencyCore8153.record8153(mint, HeldProof8153(raw, decimals, source, readStartedAtMs)) } catch (_: Throwable) {}
    }

    /** getSingleMintBalanceBounded7868 answers from a wallet snapshot younger than this before it reads. */
    private const val SINGLE_MINT_SNAPSHOT_TTL_MS_8153 = 5_000L

    /** When the single-mint answer may date from: its read start, or the young snapshot it may have been served from. */
    private fun singleMintDataAt8153(startedAtMs: Long): Long {
        val snapAt = try { com.lifecyclebot.engine.WalletAccountCache.snapshotAtMs8094() } catch (_: Throwable) { 0L }
        return if (snapAt > 0L && startedAtMs - snapAt < SINGLE_MINT_SNAPSHOT_TTL_MS_8153) minOf(startedAtMs, snapAt - SNAPSHOT_READ_SPAN_MS_8153)
        else startedAtMs
    }

    /**
     * The two independent owner+mint reads an emergency races: the wallet's single-mint ladder and a cache-free
     * multi-provider read. An answer whose data may predate the last order touching the mint is discarded.
     */
    private fun readers8153(wallet: SolanaWallet, mint: String, startedAtMs: Long, lastTouchMs: Long): List<() -> HeldProof8153?> {
        fun fresh(p: HeldProof8153?): HeldProof8153? =
            p?.takeIf { StopLatencyCore8153.usable8153(it, System.currentTimeMillis(), lastTouchMs, false) }
        return listOf(
            {
                fresh(wallet.getSingleMintBalanceBounded7868(mint, StopLatencyCore8153.EMERGENCY_READ_BUDGET_MS_8153)
                    ?.let { HeldProof8153(it.raw, it.decimals, "SINGLE_MINT_7868", singleMintDataAt8153(startedAtMs)) })
            },
            {
                fresh(SellRouteProbe8144.balanceFresh8153(wallet, mint).takeIf { SellRouteProbe8144.held8144(it) }
                    ?.let { HeldProof8153(it.raw, it.decimals, "MULTI_PROVIDER_8144", startedAtMs) })
            },
        )
    }

    /**
     * Emergency holding for [mint]: proven, else one bounded parallel read (recorded). V5.0.8288 — the read is bounded at
     * StopFast8288.STOP_READ_BUDGET_MS_8288; unanswered, the canonical held quantity (min the last wallet read) sizes the
     * stop (StopFast8288.fallbackProof8288). Null: no positive answer, or the account is proven absent ([stopAbsent8288]).
     */
    fun emergencyHolding8153(wallet: SolanaWallet, mint: String): HeldProof8153? = stopHolding8288(wallet, mint).proof

    /** V5.0.8288 — the stop's balance answer with its route (ABSENT: two providers prove the token account gone). */
    fun stopHolding8288(wallet: SolanaWallet, mint: String): StopFast8288.Answer8288 {
        val started = System.currentTimeMillis()
        val none = StopFast8288.Answer8288(null, StopFast8288.Route8288.NONE, 0L, 0)
        val (lastTouch, unresolved) = ledgerState8153(mint)
        // A signed sell still unresolved: its own confirmation decides (pendingSellSigToReuse8144); no new sizing here.
        if (unresolved) return none
        if (try { com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.isFrozen(mint) } catch (_: Throwable) { true }) return none
        val a = StopFast8288.holdingForStop8288(
            proven = { provenHolding8153(mint, started) ?: StopFast8288.cachedFallback8288(mint, started, lastTouch) },
            readers = readers8153(wallet, mint, started, lastTouch),
            absentNow = { absentSince8288(mint, started) },
            fallback = { canonicalFallback8288(mint, started) },
            onLate = { p -> if (p.atMs > ledgerState8153(mint).first) StopLatencyCore8153.record8153(mint, p) },
        )
        val proof = a.proof
        if (proof != null && a.route == StopFast8288.Route8288.PARALLEL_READ) StopLatencyCore8153.record8153(mint, proof)
        if (proof != null && a.route == StopFast8288.Route8288.CANONICAL_FALLBACK) StopFast8288.rememberFallback8288(mint, proof)
        try {
            val legacy = when (a.route) {
                StopFast8288.Route8288.PROVEN_HOLDING -> "PROVEN_HOLDING"
                StopFast8288.Route8288.PARALLEL_READ, StopFast8288.Route8288.NONE -> "PARALLEL_READ"
                else -> ""
            }
            if (legacy.isNotEmpty()) com.lifecyclebot.engine.PipelineHealthCollector.labelInc("STOP_LATENCY_BALANCE_${legacy}_${if (proof != null) "HELD" else "NONE"}_8153")
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("STOP_FAST_BALANCE_${a.route.name}_8288")
            if (a.route != StopFast8288.Route8288.PROVEN_HOLDING) {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "STOP_FAST_BALANCE_8288",
                    "mint=${mint.take(10)} route=${a.route.name} held=${proof?.raw ?: "none"} source=${proof?.source ?: "-"} elapsedMs=${a.elapsedMs} reads=${a.readsIssued}",
                )
            }
        } catch (_: Throwable) {}
        return a
    }

    /** V5.0.8288 — true when two providers proved [mint]'s token account absent in a read started at/after [sinceMs]. */
    private fun absentSince8288(mint: String, sinceMs: Long): Boolean {
        val c = try { SellRouteProbe8144.cachedBalanceAt8153(mint) } catch (_: Throwable) { null } ?: return false
        return c.first.verdict == SellRouteFinality8144.Balance8144.CONFIRMED_ABSENT && c.second >= sinceMs
    }

    /** V5.0.8288 — the canonical held quantity of [mint] (min the newest wallet read), when safe to size a stop with. */
    private fun canonicalFallback8288(mint: String, nowMs: Long): HeldProof8153? {
        val canonical = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
                .filter { it.mint == mint && it.mode.equals("live", ignoreCase = true) }
                .maxByOrNull { it.lastMutationMs }
        } catch (_: Throwable) { null } ?: return null
        val rows = try { SignedTxLedger8085.historyFor8090(mint).filter { it.side == "SELL" } } catch (_: Throwable) { return null }
        val lastSellTouch = rows.maxOfOrNull { maxOf(it.createdAtMs, it.updatedAtMs) } ?: 0L
        val lastConfirmed = rows.filter { it.state == SignedTxLedger8085.State.CONFIRMED }.maxOfOrNull { it.updatedAtMs } ?: 0L
        val snap = try {
            val at = com.lifecyclebot.engine.WalletAccountCache.snapshotAtMs8094()
            com.lifecyclebot.engine.WalletAccountCache.snapshot(ttlMs = 10L * 60_000L)?.get(mint)
                ?.let { HeldProof8153(it.raw, it.decimals, "WALLET_SNAPSHOT", at - SNAPSHOT_READ_SPAN_MS_8153) }
        } catch (_: Throwable) { null }
        val observed = listOfNotNull(StopLatencyCore8153.proof8153(mint), snap)
            .filter { StopLatencyCore8153.positive8153(it) }.maxByOrNull { it.atMs }
        return StopFast8288.fallbackProof8288(
            canonicalRaw = canonical.remainingQtyRaw, canonicalScale = canonical.quantityScale,
            canonicalMutatedAtMs = canonical.lastMutationMs, observed = observed, lastSellTouchMs = lastSellTouch,
            lastConfirmedSellAtMs = lastConfirmed, signedSellUnresolved = rows.any { it.unresolved }, frozen = false, nowMs = nowMs,
        )
    }

    /** V5.0.8288 — Executor.requestSellCore7948: an emergency whose token account two providers prove absent. */
    fun stopAbsent8288(wallet: SolanaWallet, mint: String): Boolean =
        mint.isNotBlank() && stopHolding8288(wallet, mint).route == StopFast8288.Route8288.ABSENT

    /**
     * liveSell / tryPumpPortalSell: the sold mint's balance map for an EMERGENCY exit (proven or parallel-read), or
     * null for a non-emergency exit or when nothing answered positive — the caller then keeps its legacy read.
     */
    fun hotPathBalances8153(wallet: SolanaWallet, mint: String, reason: String? = null): Map<String, CanonicalTokenAmount>? {
        if (mint.isBlank() || !emergencyNow8153(mint, reason)) return null
        // An issuer-frozen account is never routed from here: the legacy path's frozen hand-off owns it.
        if (try { com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.isFrozen(mint) } catch (_: Throwable) { true }) return null
        val p = emergencyHolding8153(wallet, mint) ?: return null
        return try { mapOf(mint to CanonicalTokenAmount(p.raw, p.decimals)) } catch (_: Throwable) { null }
    }

    // ── prewarm ─────────────────────────────────────────────────────────────

    private val started8153 = AtomicBoolean(false)
    @Volatile private var walletSource8153: (() -> SolanaWallet?)? = null
    @Volatile private var active8153: (() -> Boolean)? = null
    private val timer8153 by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AATE-StopLatency-tick-8153").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    /** BotService: keep every open live position's balance proof and venue warm. Idempotent (the latest sources win). */
    fun startPrewarm8153(wallet: () -> SolanaWallet?, isActive: () -> Boolean) {
        walletSource8153 = wallet
        active8153 = isActive
        if (!started8153.compareAndSet(false, true)) return
        try {
            timer8153.scheduleWithFixedDelay({ try { tick8153() } catch (_: Throwable) {} },
                StopLatencyCore8153.PREWARM_TICK_MS_8153, StopLatencyCore8153.PREWARM_TICK_MS_8153, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { started8153.set(false) }
    }

    private fun tick8153() {
        if (active8153?.invoke() != true) return
        if (try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }) return
        val w = walletSource8153?.invoke() ?: return
        val now = System.currentTimeMillis()
        val open = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
            .filter { it.mode.equals("live", ignoreCase = true) && it.remainingQtyRaw.signum() > 0 && it.mint.isNotBlank() }
        StopLatencyCore8153.retain8153(open.map { it.mint }.toSet())
        if (open.isEmpty()) return
        val targets = open.map {
            StopLatencyCore8153.PrewarmTarget8153(it.mint,
                try { com.lifecyclebot.engine.truth.ProtectiveExitScheduler6450.isTriggered(it.positionId) } catch (_: Throwable) { false })
        }
        val pending = try { SellRouteSpine8144.emergencyPending8153(now) } catch (_: Throwable) { 0 }
        val budget = StopLatencyCore8153.prewarmBudget8153(pending)
        val lane = StopLatencyCore8153.backgroundLane8153
        for (t in targets.sortedBy { if (it.latched) 0 else 1 }.take(budget)) {
            try {
                SellRouteVenueProbe8144.refreshAhead8153(w, t.mint, now, StopLatencyCore8153.VENUE_REFRESH_AHEAD_MS_8153, lane)
            } catch (_: Throwable) {}
        }
        // A young snapshot's positive entries are proofs at no RPC cost; only the rest are read.
        for (t in targets) {
            val p = provenHolding8153(t.mint, now) ?: continue
            if (p.source == "WALLET_SNAPSHOT") StopLatencyCore8153.record8153(t.mint, p)
        }
        val picks = StopLatencyCore8153.prewarmPick8153(targets, StopLatencyCore8153.refreshedAt8153(), now, budget)
        for (mint in picks) {
            StopLatencyCore8153.noteRefresh8153(mint, now)
            try {
                lane.execute(Runnable {
                    val dataAt = singleMintDataAt8153(System.currentTimeMillis())
                    val one = try { w.getSingleMintBalanceBounded7868(mint) } catch (_: Throwable) { null }
                    if (one != null) {
                        noteHolding8153(mint, one.raw, one.decimals, "PREWARM_SINGLE_MINT", dataAt)
                    }
                })
            } catch (_: Throwable) {}
        }
        if (picks.isNotEmpty()) try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("STOP_LATENCY_PREWARM_READS_8153") } catch (_: Throwable) {}
    }
}
