package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6725 — §CANONICAL_TOKEN_METRICS.
 *
 * Operator mandate: "all tools traders brains strategies anything involved
 * or invoked during the discovery buy hold or sell should be token metrics
 * aware." The historic pattern in this codebase is that every consumer
 * re-plumbs its own metric extraction from TokenState (`ts.holderGrowthRate`,
 * `ts.lastBuyPressurePct`, ad-hoc `history` volume sums, etc.), which
 * means:
 *   1. Not every surface actually reads them (dead tools like
 *      FluidLearningAI.getDynamicExitParams / shouldExtendHoldTime that
 *      have ZERO external callers survived for months).
 *   2. Different consumers compute the same "volume change %" over
 *      different windows, so decisions disagree with themselves.
 *   3. Adding a new consumer requires 20 lines of extractor boilerplate
 *      before the first metric-aware line of business logic runs.
 *
 * This authority collapses the extraction into a single canonical snapshot.
 * Every tool involved in discovery, buy, hold, or sell — Executor,
 * LifecycleStrategy, FluidLearningAI wire-ups, FinalDecisionGate,
 * profit-lock, TP/SL, entry sizing — reads through `snapshot(ts)` and
 * consumes the same fields. Adding a new metric adds it once here; every
 * consumer instantly picks it up.
 *
 * The snapshot never blocks a decision; it purely surfaces the current
 * per-token metrics in a shape the downstream metric-aware tools already
 * expect. Fail-safe: any extraction throw returns the neutral default for
 * that field (e.g. 0.0 volume change, 50 buy pressure, no whale flag) so
 * a partial data hydration cannot brick the exit path.
 */
object CanonicalTokenMetricsSnapshot6725 {

    data class Snapshot(
        val volumeChangePct: Double,   // rolling 5-candle vs prior-5 volume delta %
        val holderGrowthPct: Double,   // % change in holders over recent candles
        val buyPressurePct: Double,    // 0-100 (50 = neutral)
        val sellPressurePct: Double,   // 0-100 (50 = neutral)
        val momentum: Double,          // signed price momentum (%)
        val volatilityPct: Double,     // range %
        val isWhaleAccumulating: Boolean,  // (buy - sell) >= 20 pt spread proxy
        val isWhaleDumping: Boolean,       // (sell - buy) >= 20 pt spread proxy
        val liquidityUsd: Double,
        val mcapUsd: Double,
        val holderCount: Int,
        val topHolderPct: Double,      // concentration hint (0 if unknown)
        val priceChange1h: Double,     // hourly drift %
        val holderDataResolved: Boolean,   // false = data pending, don't infer from 0
        val healthTier: HealthTier,    // aggregated health verdict
    ) {
        /** Signals a healthy runner — TP widening / extend-hold candidates. */
        fun isHealthyRunner(): Boolean =
            healthTier == HealthTier.HEALTHY_RUNNER

        /** Signals a dying token — SL tightening / early exit candidates. */
        fun isDyingToken(): Boolean =
            healthTier == HealthTier.DYING || healthTier == HealthTier.RUG_LIKE
    }

    enum class HealthTier {
        HEALTHY_RUNNER,   // vol up, holders growing, whale accum, positive momentum
        HEALTHY_STABLE,   // neutral-to-positive metrics
        NEUTRAL,          // insufficient signal
        WEAKENING,        // early distribution signs
        DYING,            // vol death + sell pressure
        RUG_LIKE,         // catastrophic combination (whale dump + vol crash)
    }

    private val NEUTRAL = Snapshot(
        volumeChangePct = 0.0,
        holderGrowthPct = 0.0,
        buyPressurePct = 50.0,
        sellPressurePct = 50.0,
        momentum = 0.0,
        volatilityPct = 0.0,
        isWhaleAccumulating = false,
        isWhaleDumping = false,
        liquidityUsd = 0.0,
        mcapUsd = 0.0,
        holderCount = 0,
        topHolderPct = 0.0,
        priceChange1h = 0.0,
        holderDataResolved = false,
        healthTier = HealthTier.NEUTRAL,
    )

    fun snapshot(ts: TokenState?): Snapshot {
        if (ts == null) return NEUTRAL
        return try {
            val volumeChangePct = try {
                val hist = ts.history
                if (hist.size >= 10) {
                    val recent = hist.takeLast(5).sumOf { it.vol }
                    val prior = hist.toList().dropLast(5).takeLast(5).sumOf { it.vol }
                    if (prior > 0.0) (recent - prior) / prior * 100.0 else 0.0
                } else 0.0
            } catch (_: Throwable) { 0.0 }
            val buyP = try { ts.lastBuyPressurePct.coerceIn(0.0, 100.0) } catch (_: Throwable) { 50.0 }
            val sellP = try { ts.lastSellPressurePct.coerceIn(0.0, 100.0) } catch (_: Throwable) { 50.0 }
            val spread = buyP - sellP
            val holderGrowth = try { ts.holderGrowthRate } catch (_: Throwable) { 0.0 }
            val momentum = try { ts.momentum ?: 0.0 } catch (_: Throwable) { 0.0 }
            val volatility = try { ts.meta.rangePct } catch (_: Throwable) { 0.0 }
            val holderResolved = try { ts.holderDataResolved } catch (_: Throwable) { false }
            val holderCount = try { ts.peakHolderCount } catch (_: Throwable) { 0 }
            val topHolder = try { ts.topHolderPct ?: 0.0 } catch (_: Throwable) { 0.0 }
            val liq = try { ts.lastLiquidityUsd } catch (_: Throwable) { 0.0 }
            val mcap = try { ts.lastMcap } catch (_: Throwable) { 0.0 }
            val priceChg1h = try { ts.lastPriceChange1h } catch (_: Throwable) { 0.0 }

            val whaleAcc = spread >= 20.0 && volumeChangePct >= 20.0
            val whaleDmp = spread <= -20.0 && volumeChangePct <= -20.0

            // Health tiering — cascading strongest-first so a rug-like
            // pattern short-circuits before the healthy-runner check.
            val tier = when {
                whaleDmp && volumeChangePct <= -50.0 -> HealthTier.RUG_LIKE
                volumeChangePct <= -50.0 && sellP >= 65.0 -> HealthTier.DYING
                whaleAcc && holderGrowth >= 10.0 && momentum >= 5.0 -> HealthTier.HEALTHY_RUNNER
                volumeChangePct >= 50.0 && buyP >= 60.0 && momentum >= 0.0 -> HealthTier.HEALTHY_RUNNER
                volumeChangePct >= 20.0 && holderGrowth >= 0.0 -> HealthTier.HEALTHY_STABLE
                buyP >= 55.0 && sellP <= 50.0 -> HealthTier.HEALTHY_STABLE
                volumeChangePct <= -20.0 || sellP >= 60.0 -> HealthTier.WEAKENING
                else -> HealthTier.NEUTRAL
            }

            Snapshot(
                volumeChangePct = volumeChangePct,
                holderGrowthPct = holderGrowth,
                buyPressurePct = buyP,
                sellPressurePct = sellP,
                momentum = momentum,
                volatilityPct = volatility,
                isWhaleAccumulating = whaleAcc,
                isWhaleDumping = whaleDmp,
                liquidityUsd = liq,
                mcapUsd = mcap,
                holderCount = holderCount,
                topHolderPct = topHolder,
                priceChange1h = priceChg1h,
                holderDataResolved = holderResolved,
                healthTier = tier,
            )
        } catch (_: Throwable) {
            NEUTRAL
        }
    }

    /** Convenience — returns a compact one-line diagnostic for a snapshot. */
    fun toDiagnostic(s: Snapshot): String =
        "tier=${s.healthTier.name} vol=${String.format("%.0f", s.volumeChangePct)}% hg=${String.format("%.0f", s.holderGrowthPct)}% bp=${String.format("%.0f", s.buyPressurePct)} sp=${String.format("%.0f", s.sellPressurePct)} mom=${String.format("%.1f", s.momentum)} whaleAcc=${s.isWhaleAccumulating} whaleDmp=${s.isWhaleDumping}"
}
