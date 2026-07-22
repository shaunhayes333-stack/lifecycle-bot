package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6324 — LIVE PROBE ENTRY ORCHESTRATOR (operator hotfix §7).
 *
 * Under SOFT_TIGHT or weak provider confidence, live entries execute
 * in two stages:
 *
 *   Stage 1 PROBE     ~20-35% of the reduced intended size
 *   Stage 2 PROMOTION only when transaction fill + price + liquidity
 *                     + sell pressure + holder + route health all
 *                     pass the validation window.
 *
 * The promotion ALWAYS augments the existing canonical position for
 * the same mint — never opens a second position.
 */
object LiveProbeEntry {

    private const val VALIDATION_WINDOW_MS: Long = 25_000L
    private const val PROBE_FAILURE_PRICE_PCT: Double = -15.0
    private const val PROBE_FAILURE_LIQUIDITY_PCT: Double = -20.0

    data class ProbeContext(
        val mint: String,
        val symbol: String,
        val lane: String,
        val tactic: String,
        val fullIntendedSize: Double,
        val probeSize: Double,
        val probeStartMs: Long,
        val entryPriceAtProbe: Double,
        val liquidityAtProbe: Double,
    )

    private val activeProbes = ConcurrentHashMap<String, ProbeContext>()
    private val probesStarted = AtomicLong(0L)
    private val probesPromoted = AtomicLong(0L)
    private val probesRejected = AtomicLong(0L)

    /** Compute the probe size: 20-35% of the (already governor-reduced) size. */
    fun probeSizeFor(reducedIntendedSize: Double, fraction: Double = 0.25): Double {
        val f = fraction.coerceIn(0.20, 0.35)
        return (reducedIntendedSize * f).coerceAtLeast(0.0)
    }

    fun start(ctx: ProbeContext) {
        activeProbes[ctx.mint] = ctx
        probesStarted.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "LIVE_PROBE_STARTED_6324",
                "mint=${ctx.mint.take(10)} sym=${ctx.symbol} lane=${ctx.lane} tactic=${ctx.tactic} intended=${"%.4f".format(ctx.fullIntendedSize)} probeSize=${"%.4f".format(ctx.probeSize)} entryPrice=${ctx.entryPriceAtProbe} liquidity=${ctx.liquidityAtProbe}",
            )
            PipelineHealthCollector.labelInc("LIVE_PROBE_STARTED_6324")
        } catch (_: Throwable) {}
    }

    data class ValidationInput(
        val actualFillConfirmed: Boolean,
        val currentPrice: Double,
        val currentLiquidity: Double,
        val sellPressureRatio: Double,     // 0..1 where >0.65 means selling accelerating
        val topHolderMovementBad: Boolean,
        val routeHealthy: Boolean,
        val mintSecurityDegraded: Boolean,
        val canonicalQtyReconciled: Boolean,
    )

    /**
     * Evaluate the probe validation window. Returns one of:
     *   PROMOTE  — safe to complete the full-size buy on top of the probe
     *   REJECT   — do NOT promote; hold or exit per exit strategy
     *   WAIT     — inside the validation window, no decision yet
     */
    enum class Decision { PROMOTE, REJECT, WAIT }

    fun evaluate(mint: String, input: ValidationInput, nowMs: Long = System.currentTimeMillis()): Decision {
        val ctx = activeProbes[mint] ?: return Decision.WAIT
        val elapsed = nowMs - ctx.probeStartMs
        // Hard-reject paths first — no need to wait out the window.
        if (!input.actualFillConfirmed) return Decision.WAIT
        if (input.mintSecurityDegraded) return reject(mint, "MINT_SECURITY_DEGRADED")
        if (!input.routeHealthy) return reject(mint, "ROUTE_UNHEALTHY")
        if (input.topHolderMovementBad) return reject(mint, "TOP_HOLDER_MOVEMENT_BAD")
        val pricePct = if (ctx.entryPriceAtProbe > 0.0) (input.currentPrice / ctx.entryPriceAtProbe - 1.0) * 100.0 else 0.0
        val liqPct = if (ctx.liquidityAtProbe > 0.0) (input.currentLiquidity / ctx.liquidityAtProbe - 1.0) * 100.0 else 0.0
        if (pricePct <= PROBE_FAILURE_PRICE_PCT) return reject(mint, "PRICE_COLLAPSE_${pricePct.toInt()}pct")
        if (liqPct <= PROBE_FAILURE_LIQUIDITY_PCT) return reject(mint, "LIQ_WITHDRAW_${liqPct.toInt()}pct")
        if (input.sellPressureRatio > 0.65) return reject(mint, "SELL_PRESSURE_${(input.sellPressureRatio*100).toInt()}")

        if (elapsed < VALIDATION_WINDOW_MS) return Decision.WAIT
        if (!input.canonicalQtyReconciled) return Decision.WAIT

        // All checks pass → promote.
        activeProbes.remove(mint)
        probesPromoted.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "LIVE_PROBE_PROMOTED_6324",
                "mint=${mint.take(10)} sym=${ctx.symbol} lane=${ctx.lane} tactic=${ctx.tactic} pricePct=${"%.1f".format(pricePct)} liqPct=${"%.1f".format(liqPct)} sellPress=${input.sellPressureRatio} elapsedMs=$elapsed",
            )
            PipelineHealthCollector.labelInc("LIVE_PROBE_PROMOTED_6324")
        } catch (_: Throwable) {}
        return Decision.PROMOTE
    }

    fun cancel(mint: String, reason: String) {
        val ctx = activeProbes.remove(mint) ?: return
        probesRejected.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "LIVE_PROBE_REJECTED_6324",
                "mint=${mint.take(10)} sym=${ctx.symbol} lane=${ctx.lane} tactic=${ctx.tactic} reason=$reason",
            )
            PipelineHealthCollector.labelInc("LIVE_PROBE_REJECTED_6324")
        } catch (_: Throwable) {}
    }

    private fun reject(mint: String, reason: String): Decision {
        cancel(mint, reason)
        return Decision.REJECT
    }

    fun snapshotCounts(): Triple<Long, Long, Long> =
        Triple(probesStarted.get(), probesPromoted.get(), probesRejected.get())

    fun activeProbeCount(): Int = activeProbes.size
    fun hasActiveProbe(mint: String): Boolean = activeProbes.containsKey(mint)
}
