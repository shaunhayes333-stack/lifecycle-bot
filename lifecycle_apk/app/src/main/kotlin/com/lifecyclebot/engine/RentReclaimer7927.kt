package com.lifecyclebot.engine

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7927 — reclaim the rent locked in empty token accounts.
 *
 * Every live buy opens an associated token account: ~0.00204 SOL of rent (more
 * for Token-2022) — about 4.6% of a 0.044 SOL position. A full sell leaves the
 * account empty and the rent locked, and nothing in the bot ever closed one, so
 * each round trip quietly cost ~4.6% on top of fees and slippage, and none of it
 * appeared in the trade ledger (Fee column 0 on every row of the 9 Oct export).
 *
 * Every 15 minutes in LIVE (first pass 2 minutes after start) the wallet's
 * zero-balance token accounts are closed back to the wallet, except for mints the
 * bot holds, is buying or selling, or bought in the last 20 minutes. Closing an
 * account that holds tokens is refused by the token program itself, so this can
 * never destroy a balance; a failed batch is retried one account at a time.
 */
object RentReclaimer7927 {
    private const val FIRST_RUN_DELAY_MS = 2L * 60_000L
    private const val INTERVAL_MS = 15L * 60_000L
    private const val RECENT_BUY_MS = 20L * 60_000L
    private const val BATCH = 12

    private val startedAt = System.currentTimeMillis()
    private val running = AtomicBoolean(false)
    @Volatile private var lastRunMs = 0L
    private val closed = AtomicLong(0)
    private val reclaimedLamports = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val skippedProtected = AtomicLong(0)
    @Volatile private var lastError = ""
    // V5.0.8102 — 5.0.8098: lastError=custom program error: 0x23 every pass. Token-2022 error 35 is
    // AccountHasWithheldTransferFees: the account cannot close until its withheld fees are harvested to the mint, which
    // only the mint's withdraw authority can do. Such an account is skipped for the rest of the process.
    private val unclosable8102 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Pure: does a close failure say the account holds withheld Token-2022 transfer fees (error 0x23)? */
    fun withheldFeesRefusal8102(message: String?): Boolean = message != null && Regex("custom program error: 0x23\\b").containsMatchIn(message)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rent-reclaim-7927").apply { isDaemon = true } }

    /** Pure: is a tracked wallet position still in play (not terminal)? */
    fun activeTrackerStatus(priority: Int, dustIgnored: Boolean): Boolean = !dustIgnored && priority < 8

    private fun protectedMint(mint: String, nowMs: Long): Boolean {
        try {
            val ts = BotService.status.tokens[mint]
            if (ts != null && ts.position.isOpen) return true
        } catch (_: Throwable) { return true }
        return try {
            val e = HostWalletTokenTracker.getEntry(mint) ?: return false
            activeTrackerStatus(e.status.priority, e.status == HostWalletTokenTracker.PositionStatus.DUST_IGNORED) ||
                ((e.buyTimeMs ?: 0L) > 0L && nowMs - (e.buyTimeMs ?: 0L) < RECENT_BUY_MS)
        } catch (_: Throwable) { true }
    }

    // ── V5.0.8151 — close right after a confirmed full exit ──
    // The sweep above runs every 15 min and protects any mint bought in the last 20 min, so a scalp's ~0.00204 SOL
    // (more for Token-2022) sat locked for 20-35 min — on a 0.40 SOL wallet that is a slot's worth of capital across a
    // few positions. A confirmed full exit (LivePositionCloseAuthority.finalizeClosed) now queues its own mint; one
    // minute later its account is closed when CostTrim8151.targetedCloseEligible8151 holds (on-chain amount exactly
    // "0", initialized — never frozen —, no open position, no unresolved signed buy/sell, terminal tracker state).
    private val targetedDue8151 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val targetedRunning8151 = AtomicBoolean(false)
    private val targetedClosed8151 = AtomicLong(0)
    private val targetedLamports8151 = AtomicLong(0)
    private val targetedSkipped8151 = AtomicLong(0)

    /** A full exit of [mint] was confirmed (sell signature + zero balance): close its token account soon. */
    fun noteFullExit8151(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        targetedDue8151[mint] = nowMs + CostTrim8151.TARGETED_CLOSE_DELAY_MS_8151
        if (targetedDue8151.size > 200) targetedDue8151.entries.removeIf { nowMs - it.value > 30L * 60_000L }
    }

    /** Share of empty accounts the reclaimer could not close (Token-2022 withheld fees): rent that is really spent. */
    fun rentLossRate8151(): Double {
        val lost = unclosable8102.size.toDouble()
        val total = lost + closed.get().toDouble()
        return if (total <= 0.0) 0.0 else (lost / total).coerceIn(0.0, 1.0)
    }

    private fun maybeRunTargeted8151(nowMs: Long) {
        if (targetedDue8151.isEmpty() || targetedDue8151.values.none { nowMs >= it }) return
        val live = try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        if (!live || !targetedRunning8151.compareAndSet(false, true)) return
        try { worker.execute { try { runTargeted8151() } finally { targetedRunning8151.set(false) } } } catch (_: Throwable) { targetedRunning8151.set(false) }
    }

    private fun runTargeted8151() {
        val wallet = try { WalletManager.getWallet() } catch (_: Throwable) { null } ?: return
        val now = System.currentTimeMillis()
        val due = targetedDue8151.filterValues { now >= it }.keys
        if (due.isEmpty()) return
        val empties = try { wallet.emptyTokenAccounts7927() } catch (t: Throwable) { lastError = "targeted:${t.javaClass.simpleName}"; return }
        val byMint = empties.filter { it.mint in due && it.address !in unclosable8102 }.groupBy { it.mint }
        for (mint in due) {
            val accounts = byMint[mint]
            if (accounts.isNullOrEmpty()) { targetedDue8151.remove(mint); continue }   // nothing empty (closed in-tx, or not zero)
            if (!targetedEligible8151(mint, now, targetedDue8151[mint] ?: now)) {
                targetedSkipped8151.incrementAndGet()
                // Re-check later while the window lasts; the periodic sweep remains the backstop.
                if (now - (targetedDue8151[mint] ?: now) > 10L * 60_000L) targetedDue8151.remove(mint)
                continue
            }
            targetedDue8151.remove(mint)
            if (closeBatch(wallet, accounts)) {
                targetedClosed8151.addAndGet(accounts.size.toLong())
                targetedLamports8151.addAndGet(accounts.sumOf { it.lamports })
                try { PipelineHealthCollector.labelInc("RENT_RECLAIM_TARGETED_8151") } catch (_: Throwable) {}
            }
        }
    }

    /** The live facts [CostTrim8151.targetedCloseEligible8151] needs for [mint] (the account is already empty + initialized). */
    private fun targetedEligible8151(mint: String, nowMs: Long, dueAtMs: Long): Boolean {
        val exitAtMs = dueAtMs - CostTrim8151.TARGETED_CLOSE_DELAY_MS_8151
        val open = try { BotService.status.tokens[mint]?.position?.isOpen == true } catch (_: Throwable) { true }
        val unresolved = try {
            com.lifecyclebot.network.SignedTxLedger8085.unresolvedFor(mint, "SELL") != null ||
                com.lifecyclebot.network.SignedTxLedger8085.unresolvedFor(mint, "BUY") != null
        } catch (_: Throwable) { true }
        val entry = try { HostWalletTokenTracker.getEntry(mint) } catch (_: Throwable) { null }
        val dust = entry?.status == HostWalletTokenTracker.PositionStatus.DUST_IGNORED
        // A buy of the same mint after the exit (re-entry) owns the account again: never close under it.
        val rebought = (entry?.buyTimeMs ?: 0L) > exitAtMs
        // emptyTokenAccounts7927 only lists accounts whose amount is exactly "0" and whose state is "initialized".
        return CostTrim8151.targetedCloseEligible8151("0", "initialized", open || rebought, unresolved, entry?.status?.priority, dust, dueAtMs, nowMs)
    }

    /** ExitRegret7752.tick clock: schedule a pass when due (never blocks the caller). */
    fun maybeRun(nowMs: Long = System.currentTimeMillis()) {
        maybeRunTargeted8151(nowMs)  // V5.0.8151
        if (nowMs - startedAt < FIRST_RUN_DELAY_MS || nowMs - lastRunMs < INTERVAL_MS) return
        val live = try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        if (!live || !running.compareAndSet(false, true)) return
        lastRunMs = nowMs
        try { worker.execute { try { runPass() } finally { running.set(false) } } } catch (_: Throwable) { running.set(false) }
    }

    private fun runPass() {
        val wallet = try { WalletManager.getWallet() } catch (_: Throwable) { null } ?: return
        val now = System.currentTimeMillis()
        val empties = try { wallet.emptyTokenAccounts7927() } catch (t: Throwable) {
            lastError = "list:${t.javaClass.simpleName}"; failed.incrementAndGet(); return
        }
        val eligible = empties.filter { a ->
            if (a.address in unclosable8102) return@filter false
            val p = protectedMint(a.mint, now)
            if (p) skippedProtected.incrementAndGet()
            !p
        }
        if (eligible.isEmpty()) return
        for (batch in eligible.groupBy { it.programId }.values.flatMap { it.chunked(BATCH) }) {
            if (!closeBatch(wallet, batch)) {
                // One bad account (e.g. Token-2022 withheld fees) fails the whole batch: go one by one.
                for (one in batch) closeBatch(wallet, listOf(one))
            }
        }
    }

    private fun closeBatch(wallet: com.lifecyclebot.network.SolanaWallet, batch: List<com.lifecyclebot.network.SolanaWallet.EmptyTokenAccount7927>): Boolean =
        try {
            val sig = wallet.closeTokenAccounts7927(batch)
            val lamports = batch.sumOf { it.lamports }
            closed.addAndGet(batch.size.toLong())
            reclaimedLamports.addAndGet(lamports)
            try {
                PipelineHealthCollector.labelInc("RENT_RECLAIM_7927_CLOSED")
                ForensicLogger.lifecycle(
                    "RENT_RECLAIM_7927",
                    "closed=${batch.size} reclaimedSol=${"%.6f".format(lamports / 1e9)} sig=${sig.take(16)} mints=${batch.joinToString(",") { it.mint.take(6) }}",
                )
            } catch (_: Throwable) {}
            true
        } catch (t: Throwable) {
            failed.incrementAndGet()
            // V5.0.8095 — keep the END of the message: the instruction error code is what names the cause.
            lastError = "close:${t.message?.takeLast(90) ?: t.javaClass.simpleName}"
            if (batch.size == 1 && withheldFeesRefusal8102(t.message)) unclosable8102.add(batch[0].address)
            try { PipelineHealthCollector.labelInc("RENT_RECLAIM_7927_FAILED") } catch (_: Throwable) {}
            false
        }

    fun statusLine(): String =
        "closed=${closed.get()} reclaimedSol=${"%.5f".format(reclaimedLamports.get() / 1e9)} failed=${failed.get()} " +
            "protectedSkips=${skippedProtected.get()} lastRunAgoSec=${if (lastRunMs > 0) (System.currentTimeMillis() - lastRunMs) / 1000 else -1} " +
            "lastError=${lastError.ifBlank { "-" }} withheldFeeSkips8102=${unclosable8102.size} " +
            "afterExit8151=${targetedClosed8151.get()} afterExitSol8151=${"%.5f".format(targetedLamports8151.get() / 1e9)} " +
            "afterExitWaiting8151=${targetedSkipped8151.get()} queued8151=${targetedDue8151.size}"
}
