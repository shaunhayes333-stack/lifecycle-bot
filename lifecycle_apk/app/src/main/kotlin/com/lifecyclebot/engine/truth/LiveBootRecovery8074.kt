package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8074 — a restart never loses a live position, and the bot never buys what it cannot see.
 *
 * Owner, 2026-10-12: "I manually sold them because the ui showed 0 positions after I updated" — 5 live buys
 * (SI, KILOS, NUTS, Imagine, HYfus) finalized and recorded in the durable event log (economic_events_6486), then an
 * app update restarted the process and the bot showed 0 positions and managed none of them.
 *
 * Two causes, both fixed here:
 *  1. CanonicalPositionAuthority6441 is memory-only and the boot rebuild replays PAPER events only
 *     (rebuildPaperFromEvents6486). A live position comes back only through wallet adoption
 *     (LiveCanonicalRecovery6686), and nothing asked for it: the reconciler waits for a live position it cannot see.
 *     [tick8074] reads the durable log for live bot buys of the last 14 days whose position has no terminal sell and
 *     asks the adoption path for exactly those mints, every minute, until each is adopted or proven gone from the
 *     wallet (the adoption reads the chain: a coin sold by hand is not brought back).
 *  2. Helius ran out of credits and every wallet endpoint refused the token read (helius 429, mainnet-beta 429,
 *     publicnode 403: WALLET_TOKEN_READ_INDETERMINATE). A bot that cannot read its token accounts cannot adopt,
 *     manage or size a position — [blind8074] refuses new live buys until a token read succeeds again.
 */
object LiveBootRecovery8074 {
    private const val LOOKBACK_MS = 14L * 24 * 3_600_000L
    private const val TICK_MS = 60_000L
    private const val BLIND_AFTER_MS = 3L * 60_000L

    @Volatile private var lastTickMs = 0L
    @Volatile private var lastReadOkMs = 0L
    @Volatile private var lastReadFailMs = 0L
    @Volatile private var firstConsecutiveFailMs8268 = 0L
    private val kicks = AtomicLong(0)
    private val refusedBlind = AtomicLong(0)
    @Volatile private var lastCandidates = 0

    /** SolanaWallet: a full wallet token read succeeded / failed. */
    fun noteTokenRead8074(ok: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (ok) {
            lastReadOkMs = nowMs
            lastReadFailMs = 0L
            firstConsecutiveFailMs8268 = 0L
        } else {
            // 8268: zero means NEVER read, not Unix epoch. Repeated 429s must not
            // extend the grace window forever or make it expire immediately.
            if (lastReadFailMs <= lastReadOkMs || firstConsecutiveFailMs8268 == 0L) {
                firstConsecutiveFailMs8268 = nowMs
            }
            lastReadFailMs = nowMs
        }
    }

    /** Pure: a failed-read streak becomes blind only after 3 minutes without an SPL proof. */
    fun blindPure8074(
        lastOkMs: Long,
        lastFailMs: Long,
        nowMs: Long,
        firstFailMs: Long = lastFailMs,
    ): Boolean {
        if (lastFailMs <= lastOkMs) return false
        val proofOrFirstFailureMs = if (lastOkMs > 0L) lastOkMs else firstFailMs
        return proofOrFirstFailureMs > 0L && nowMs - proofOrFirstFailureMs >= BLIND_AFTER_MS
    }

    /** LiveEdgeGate7877: the refusal for a live buy while the wallet cannot be read, else null. */
    fun blind8074(nowMs: Long = System.currentTimeMillis()): String? {
        if (!blindPure8074(lastReadOkMs, lastReadFailMs, nowMs, firstConsecutiveFailMs8268)) return null
        refusedBlind.incrementAndGet()
        try { PipelineHealthCollector.labelInc("WALLET_BLIND_8074") } catch (_: Throwable) {}
        return "WALLET_BLIND_8074"
    }

    /** Pure: the mints with a live buy whose position has no terminal (zero-remaining) sell. */
    fun openLiveMints8074(events: List<EconomicEventSchema6464.Event>, sinceMs: Long): Set<String> {
        val bought = HashMap<String, String>()          // positionId -> mint
        val closed = HashSet<String>()
        for (e in events) {
            if (!e.mode.equals("live", true)) continue
            when (e) {
                is EconomicEventSchema6464.Buy -> if (e.atMs >= sinceMs && e.mint.isNotBlank()) bought[e.positionId] = e.mint
                is EconomicEventSchema6464.Sell -> if (!e.partial || e.remainingQty <= BigInteger.ZERO) closed.add(e.positionId)
                else -> {}
            }
        }
        return bought.filterKeys { it !in closed }.values.toSet()
    }

    /** LiveEdgeGate7877 (every decision; does its work once a minute): ask the adoption path for unmanaged live buys. */
    fun tick8074(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastTickMs < TICK_MS) return
        lastTickMs = nowMs
        try {
            val open = try {
                CanonicalPositionAuthority6441.openPositions().filter { it.mode.equals("live", true) }.map { it.mint }.toSet()
            } catch (_: Throwable) { emptySet() }
            // V5.0.8076 — only coins the wallet actually holds (fresh cached read, no RPC here). 5.0.8075 kicked 118
            // historical mints a minute (unmanagedLiveBuys=118, kicks=82): most were long sold, and each kick for a mint
            // missing from the cache fires its own RPC read — load the rate-limited endpoints could not afford.
            val held = try { com.lifecyclebot.engine.WalletAccountCache.snapshot(ttlMs = 180_000L) } catch (_: Throwable) { null } ?: return
            val want = (openLiveMints8074(EconomicEventSchema6464.snapshot(), nowMs - LOOKBACK_MS) - open)
                .filter { m -> held[m]?.let { it.raw > BigInteger.ONE } == true }.toSet()
            lastCandidates = want.size
            if (want.isEmpty()) return
            com.lifecyclebot.engine.LiveCanonicalRecovery6686.requestAdoptionAsync7718(want)
            kicks.incrementAndGet()
            PipelineHealthCollector.labelInc("LIVE_BOOT_RECOVERY_KICK_8074")
        } catch (_: Throwable) {}
    }

    fun statusLine(nowMs: Long = System.currentTimeMillis()): String =
        "unmanagedLiveBuys=$lastCandidates kicks=${kicks.get()} walletRead[ok=${if (lastReadOkMs > 0) "${(nowMs - lastReadOkMs) / 1000}s ago" else "never"} " +
            "fail=${if (lastReadFailMs > 0) "${(nowMs - lastReadFailMs) / 1000}s ago" else "never"}] blind=${blindPure8074(lastReadOkMs, lastReadFailMs, nowMs, firstConsecutiveFailMs8268)} refusedBlind=${refusedBlind.get()}"
}
