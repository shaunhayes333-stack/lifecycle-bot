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

    /** ExitRegret7752.tick clock: schedule a pass when due (never blocks the caller). */
    fun maybeRun(nowMs: Long = System.currentTimeMillis()) {
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
            lastError = "close:${t.message?.take(80) ?: t.javaClass.simpleName}"
            try { PipelineHealthCollector.labelInc("RENT_RECLAIM_7927_FAILED") } catch (_: Throwable) {}
            false
        }

    fun statusLine(): String =
        "closed=${closed.get()} reclaimedSol=${"%.5f".format(reclaimedLamports.get() / 1e9)} failed=${failed.get()} " +
            "protectedSkips=${skippedProtected.get()} lastRunAgoSec=${if (lastRunMs > 0) (System.currentTimeMillis() - lastRunMs) / 1000 else -1} " +
            "lastError=${lastError.ifBlank { "-" }}"
}
