package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7948 — ACCOUNTING INTEGRITY (diag 5.0.7947 §8).
 *
 * 1. JOURNAL COVERAGE. "5 confirmed LIVE buys vs 1 journaled buy". The success
 *    counter (execLiveBuyOk) counts every canonical LIVE buy commit
 *    (ExecutorCanonicalMirror6442.mirrorBuyFill and the wallet promotion in
 *    LiveCanonicalRecovery6686), but the BUY journal row was written only by the
 *    executor's proof path. A buy promoted from wallet proof (proof never
 *    completed) or one whose proof path threw after the canonical commit was a
 *    real position with no BUY row: its later SELL had no entry leg and
 *    BUY_JOURNALED never moved. Every committed LIVE buy is now tracked here;
 *    one still without a journal row after [GRACE_MS_7948] is journaled from
 *    the canonical fill (raw qty, cost, entry price, decimals, positionId).
 *
 * 2. HOST PROJECTION GAP. "5 canonical LIVE vs 4 host wallet projection". The
 *    dump now names every canonical LIVE mint missing from the host projection
 *    with its tracker status, and [reopenForCanonicalLive7948] lets the wallet
 *    snapshot reopen a tracker row that was closed as stale-unheld while the
 *    canonical book still owns a LIVE position the wallet provably holds.
 */
object AccountingIntegrity7948 {

    /** Executor proof path journals within ~60-90 s; anything later is a gap. */
    internal const val GRACE_MS_7948: Long = 180_000L

    private val committed7948 = ConcurrentHashMap<String, Long>()
    private val journaled7948 = ConcurrentHashMap.newKeySet<String>()
    private val backfilled7948 = AtomicLong(0L)
    private val unbackfillable7948 = AtomicLong(0L)

    /** A canonical LIVE buy was committed (counted as a confirmed buy). */
    fun onLiveBuyCommitted7948(positionId: String, nowMs: Long = System.currentTimeMillis()) {
        if (positionId.isBlank()) return
        if (committed7948.size > 2_000) committed7948.clear()
        committed7948.putIfAbsent(positionId, nowMs)
    }

    /** A LIVE BUY journal row landed for this canonical position. */
    fun onLiveBuyJournaled7948(positionId: String) {
        if (positionId.isBlank()) return
        if (journaled7948.size > 4_000) journaled7948.clear()
        journaled7948.add(positionId)
        committed7948.remove(positionId)
    }

    internal fun dueForBackfill7948(committedAtMs: Long, nowMs: Long, journaled: Boolean): Boolean =
        !journaled && committedAtMs > 0L && nowMs - committedAtMs >= GRACE_MS_7948

    /**
     * The BUY row a committed canonical LIVE position must have, built only from
     * the canonical fill. null when the canonical row cannot price its own entry
     * (no quantity, cost or entry price) — such a row is never invented.
     */
    internal fun backfillBuyTrade7948(p: CanonicalPositionAuthority6441.Position, sig: String): Trade? {
        if (!p.mode.equals("live", true)) return null
        if (p.originalQtyRaw <= BigInteger.ZERO) return null
        if (!(p.entryCostSol.isFinite() && p.entryCostSol > 0.0)) return null
        if (!(p.entryPriceUsd.isFinite() && p.entryPriceUsd > 0.0)) return null
        val scale = p.quantityScale.coerceIn(0, 18)
        val qtyUi = try { p.originalQtyRaw.toBigDecimal().movePointLeft(scale).toDouble() } catch (_: Throwable) { 0.0 }
        if (!(qtyUi.isFinite() && qtyUi > 0.0)) return null
        val ts = p.openedAtMs.takeIf { it > 0L } ?: p.lastMutationMs
        return Trade(
            side = "BUY", mode = "live", sol = p.entryCostSol, price = p.entryPriceUsd, ts = ts,
            reason = "LIVE_BUY_JOURNAL_BACKFILL_7948", sig = sig,
            tradingMode = p.lane.ifBlank { "STANDARD" }, mint = p.mint,
            proofState = if (sig.isNotBlank()) "LIVE_SIG_CONFIRMED" else "LIVE_BALANCE_CONFIRMED",
            positionId = p.positionId, entryTsMs = ts,
            entryPriceSnapshot = p.entryPriceUsd, entryQtyToken = qtyUi, entryCostSol = p.entryCostSol,
            entryDecimals = p.tokenDecimals.coerceAtLeast(0), remainingQtyToken = qtyUi,
            entryRawQty = p.originalQtyRaw, remainingRawQty = p.originalQtyRaw,
            tokenDecimals = p.tokenDecimals, entryPriceSource = p.entryPriceSource,
            entryPoolAddress = p.entryPoolAddress,
        )
    }

    /**
     * Journal every committed LIVE buy still missing its BUY row after the grace
     * window. Called from the wallet reconcile cadence. Returns rows written.
     */
    fun sweepLiveBuyJournal7948(nowMs: Long = System.currentTimeMillis()): Int {
        var written = 0
        for ((pid, at) in committed7948.entries.toList()) {
            if (!dueForBackfill7948(at, nowMs, pid in journaled7948)) continue
            committed7948.remove(pid)
            val pos = try { CanonicalPositionAuthority6441.getPosition(pid) } catch (_: Throwable) { null }
            if (pos == null) { unbackfillable7948.incrementAndGet(); continue }
            val sig = try {
                com.lifecyclebot.engine.HostWalletTokenTracker.getEntry(pos.mint)?.buySignature.orEmpty()
            } catch (_: Throwable) { "" }
            val existing = try {
                com.lifecyclebot.engine.TradeHistoryStore.liveRowsForMint7370(pos.mint).any {
                    it.side.equals("BUY", true) && (it.positionId == pid || (sig.isNotBlank() && it.sig == sig))
                }
            } catch (_: Throwable) { false }
            if (existing) { journaled7948.add(pid); continue }
            val trade = backfillBuyTrade7948(pos, sig)
            if (trade == null) {
                unbackfillable7948.incrementAndGet()
                try { PipelineHealthCollector.labelInc("LIVE_BUY_JOURNAL_BACKFILL_UNPRICED_7948") } catch (_: Throwable) {}
                continue
            }
            // Same idempotency key the executor's recordTrade claims, so a late
            // executor row for the same signature cannot double-journal the buy.
            if (sig.isNotBlank() && !com.lifecyclebot.engine.AccountingIdempotencyRegistry.claim(
                    sig, pos.mint, "BUY", "journalBackfill7948")) { journaled7948.add(pid); continue }
            try {
                com.lifecyclebot.engine.TradeHistoryStore.recordTrade(trade)
                journaled7948.add(pid)
                backfilled7948.incrementAndGet()
                written++
                PipelineHealthCollector.labelInc("BUY_JOURNALED")
                PipelineHealthCollector.labelInc("LIVE_BUY_JOURNAL_BACKFILLED_7948")
                ForensicLogger.lifecycle(
                    "LIVE_BUY_JOURNAL_BACKFILLED_7948",
                    "pid=${pid.take(32)} mint=${pos.mint.take(10)} cost=${pos.entryCostSol} raw=${pos.originalQtyRaw} " +
                        "entryUsd=${pos.entryPriceUsd} sig=${sig.take(16)} reason=confirmed_buy_without_journal_row",
                )
            } catch (_: Throwable) {}
        }
        return written
    }

    /** One-line verdict for the Buy terminal block. */
    fun coverageLine7948(confirmedBuys: Long, journaledBuys: Long): String {
        val missing = (confirmedBuys - journaledBuys).coerceAtLeast(0L)
        val verdict = if (missing == 0L) "OK" else "GAP"
        return "$verdict confirmed=$confirmedBuys journaled=$journaledBuys missing=$missing " +
            "awaitingJournal=${committed7948.size} backfilled=${backfilled7948.get()} unpriced=${unbackfillable7948.get()} " +
            "graceS=${GRACE_MS_7948 / 1000}"
    }

    /**
     * Canonical LIVE mints the host wallet projection does not count, each with
     * the tracker status that excluded it, plus host-only mints.
     */
    fun projectionGap7948(canonicalLive: Set<String>, hostProjection: Set<String>, trackerStatus: (String) -> String?): String {
        val canonicalOnly = (canonicalLive - hostProjection).sorted()
        val hostOnly = (hostProjection - canonicalLive).sorted()
        if (canonicalOnly.isEmpty() && hostOnly.isEmpty()) return "none (canonical=${canonicalLive.size} host=${hostProjection.size})"
        val c = canonicalOnly.joinToString(",") { "${it.take(6)}:${trackerStatus(it) ?: "NO_TRACKER_ROW"}" }
        val h = hostOnly.joinToString(",") { "${it.take(6)}:${trackerStatus(it) ?: "?"}" }
        return "canonicalOnly=[$c] hostOnly=[$h]"
    }

    /**
     * A tracker row closed as stale-unheld must reopen when the wallet now holds
     * a tradable balance and the canonical book still owns a LIVE position on
     * that mint — "no held proof" was an RPC gap, not a sale.
     */
    fun reopenForCanonicalLive7948(trackerStatus: String, walletHasTradableRaw: Boolean, canonicalLiveOpen: Boolean): Boolean =
        walletHasTradableRaw && canonicalLiveOpen && trackerStatus == "CLOSED_STALE_RECOVERY_UNHELD"

    /** HostWalletTokenTracker.recordSellConfirmed ignores only reasons that say PAPER. */
    fun paperTrackerReason7948(reason: String): String =
        if (reason.contains("PAPER", ignoreCase = true)) reason else "PAPER_EXIT:$reason"

    fun canonicalLiveOpen7948(mint: String): Boolean = try {
        CanonicalPositionAuthority6441.activeMintProjections6490("live").any { it.mint == mint }
    } catch (_: Throwable) { false }

    internal fun resetForTest7948() {
        committed7948.clear(); journaled7948.clear(); backfilled7948.set(0L); unbackfillable7948.set(0L)
    }
}
