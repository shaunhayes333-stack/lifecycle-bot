package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * V5.0.8083 §PARALLEL_SELL_ROUTE_RACING (LIVE).
 *
 * The live sell was strictly serial: PumpPortal-first -> Jupiter quote loop -> up to 5 Jupiter broadcast tiers ->
 * Raydium -> PumpPortal rescue. The Raydium builder only started after every Jupiter tier had failed.
 *
 * Race the ROUTE PREPARATION, not the orders: at the start of an urgent live sell the Raydium builder quotes +
 * builds in the background on the SAME canonical raw quantity while PumpPortal / Jupiter proceed. The build is
 * unsigned. Exactly one transaction is signed per attempt: the Executor uses the prebuilt Raydium tx only when the
 * preceding route did not land (sig == null), so there is no competing second sell, no overlapping quantity and no
 * duplicate fee/learning. The later serial Raydium step reuses a fresh prebuild and is skipped once the race sent it.
 * Transport stays the wallet's: Helius Sender first, the same bytes fanned out to Helius RPC + Alchemy.
 */
object SellRouteRace8083 {
    class Race(val future: CompletableFuture<com.lifecyclebot.network.RaydiumSellRoute7311.Built?>, val startedAtMs: Long,
               val rawAmount: Long, val slipBps: Int) {
        @Volatile var sent: Boolean = false
        fun readyOrNull(waitMs: Long): com.lifecyclebot.network.RaydiumSellRoute7311.Built? = try {
            if (waitMs <= 0L) future.getNow(null) else future.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { null }
        fun fresh(nowMs: Long = System.currentTimeMillis()) = nowMs - startedAtMs <= FRESH_MS
    }

    const val FRESH_MS = 20_000L
    const val DRAIN_WAIT_MS = 1_200L

    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "aate-sell-race-8083").apply { isDaemon = true } }

    fun urgent(reason: String, drain: Boolean, fastLand: Boolean): Boolean {
        if (drain || fastLand) return true
        val r = reason.uppercase()
        return listOf("HARD_FLOOR", "HARD_STOP", "STOP_LOSS", "RUG", "CATASTROPHIC", "LIQUIDITY_COLLAPSE", "EMERGENCY").any { r.contains(it) }
    }

    fun start(build: () -> com.lifecyclebot.network.RaydiumSellRoute7311.Built?, rawAmount: Long, slipBps: Int, mint: String): Race? {
        if (rawAmount <= 0L) return null
        val t0 = System.currentTimeMillis()
        val f = try {
            CompletableFuture.supplyAsync({
                try { build() } catch (_: Throwable) {
                    try { PipelineHealthCollector.labelInc("SELL_RACE_RAYDIUM_BUILD_FAILED_8083") } catch (_: Throwable) {}
                    null
                }
            }, pool)
        } catch (_: Throwable) { return null }
        f.thenAccept { b ->
            if (b != null && b.transactions.isNotEmpty()) try {
                val ms = System.currentTimeMillis() - t0
                PipelineHealthCollector.labelInc("SELL_RACE_RAYDIUM_READY_8083")
                PipelineHealthCollector.labelInc("SELL_RACE_RAYDIUM_READY_MS_" + when { ms < 500 -> "LT500"; ms < 1500 -> "LT1500"; ms < 4000 -> "LT4000"; else -> "GE4000" } + "_8083")
            } catch (_: Throwable) {}
        }
        try {
            PipelineHealthCollector.labelInc("SELL_RACE_STARTED_8083")
            ForensicLogger.lifecycle("SELL_RACE_STARTED_8083", "mint=${mint.take(10)} raw=$rawAmount slipBps=$slipBps routes=RAYDIUM_PREBUILD+PUMPPORTAL+JUPITER")
        } catch (_: Throwable) {}
        return Race(f, t0, rawAmount, slipBps)
    }

    /** Drain exit: first viable executable route. Other urgent exits: only when it pays >= the Jupiter quote. */
    fun preferRaydium(built: com.lifecyclebot.network.RaydiumSellRoute7311.Built?, drain: Boolean, jupiterOutLamports: Long?): Boolean {
        if (built == null || built.transactions.isEmpty() || built.outLamports <= 0L) return false
        if (drain) return true
        val jup = jupiterOutLamports ?: return true
        return jup <= 0L || built.outLamports >= jup
    }

    fun selected(route: String, mint: String, raceAgeMs: Long) {
        try {
            PipelineHealthCollector.labelInc("SELL_RACE_SELECTED_${route}_8083")
            ForensicLogger.lifecycle("SELL_RACE_SELECTED_8083", "mint=${mint.take(10)} route=$route raceAgeMs=$raceAgeMs")
        } catch (_: Throwable) {}
    }
}
