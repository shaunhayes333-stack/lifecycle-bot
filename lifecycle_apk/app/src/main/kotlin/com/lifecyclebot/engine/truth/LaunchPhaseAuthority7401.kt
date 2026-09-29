package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.OperatorRegistry
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.WhaleDetector
import com.lifecyclebot.network.PumpCurveKeys7269

/**
 * V5.0.7401 — launch timing authority.
 *
 * A watchlist timestamp is not a token birth timestamp. A token first noticed
 * after a pump must never be relabelled "fresh" merely because it just entered
 * this process. This authority anchors pump.fun launches to the create frame
 * and separates pre-pump ignition from post-pump/fade.
 *
 * No network calls. No hard safety decisions. It is phase truth used by routing
 * and scanners so an old/fading launch is not handed to PROJECT_SNIPER.
 */
object LaunchPhaseAuthority7401 {
    // V5.0.7408 — lane/tool fan-out was recalculating the same launch truth
    // hundreds of times per bot cycle. Cache only across the current observation
    // burst; 750ms is short enough for launch trading while collapsing duplicate
    // reads from V3/specialists/tools.
    private data class Cached7408(val atMs: Long, val snapshot: Snapshot)
    private data class PhaseEmit7408(val phase: Phase, val atMs: Long)
    private val cache7408 = java.util.concurrent.ConcurrentHashMap<String, Cached7408>()
    private val phaseEmit7408 = java.util.concurrent.ConcurrentHashMap<String, PhaseEmit7408>()
    // V5.0.7425 — session high-water survives local history-window churn. A
    // watchlist/history refresh must not erase the fact that a token already pumped.
    private val observedPeak7425 = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private const val SNAPSHOT_TTL_MS_7408 = 750L
    private const val SAME_PHASE_EMIT_MS_7408 = 5_000L

    enum class Phase {
        PRE_IGNITION,
        IGNITION,
        EXPANDING,
        POST_PUMP_FADE,
        MATURE_OR_UNKNOWN,
        METADATA_HYDRATING,
    }

    data class Snapshot(
        val phase: Phase,
        val ageMs: Long,
        val createMultiple: Double?,
        val buySharePct: Double,
        val buyTx60s: Int,
        val sellTx60s: Int,
        val distinctBuyers60s: Int,
        val devBuyTx60s: Int,
        val devSellTx60s: Int,
        val accelerationRising: Boolean,
        val currentVsRecentPeak: Double,
        val reason: String,
        val birthResolved: Boolean = true,
        val birthSource: String = "",
    ) {
        val early: Boolean get() = phase == Phase.PRE_IGNITION || phase == Phase.IGNITION
        val tooLateForSnipe: Boolean get() = phase == Phase.POST_PUMP_FADE
    }

    fun trueAgeMs(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Long =
        CanonicalTokenBirthTime7440.resolvedAgeMs(ts, nowMs) ?: Long.MAX_VALUE

    fun resolvedAgeMs(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Long? =
        CanonicalTokenBirthTime7440.resolvedAgeMs(ts, nowMs)


    // V5.0.7402 — CI's kotlin_expression_body_return gate (and the real
    // Kotlin compiler behind it) rejects a bare `return` inside an
    // expression-body function's `= try { ... }`. Converted to block body;
    // logic/behavior unchanged from the 7401 version.
    private fun createMultiple(ts: TokenState): Double? {
        return try {
            val createSol = PumpCurveKeys7269.createPriceSol7280(ts.mint) ?: return null
            val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            val px = ts.lastPrice
            if (!createSol.isFinite() || createSol <= 0.0 || !solUsd.isFinite() || solUsd <= 0.0 ||
                !px.isFinite() || px <= 0.0) null
            else EconomicUnitInvariant7061.usdToSol(px, solUsd) / createSol
        } catch (_: Throwable) { null }
    }

    fun snapshot(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Snapshot {
        val mintKey7408 = ts.mint
        if (mintKey7408.isNotBlank()) {
            val cached7408 = cache7408[mintKey7408]
            if (cached7408 != null && nowMs - cached7408.atMs in 0L..SNAPSHOT_TTL_MS_7408) {
                return cached7408.snapshot
            }
        }
        val birth7440 = CanonicalTokenBirthTime7440.resolve(ts.mint, nowMs)
        val age = birth7440?.let { (nowMs - it.birthMs).coerceAtLeast(0L) } ?: Long.MAX_VALUE
        val dev = try { OperatorRegistry.getDevWallet(ts.mint) } catch (_: Throwable) { null }
        val flow = try { WhaleDetector.launchFlow7401(ts.mint, dev, nowMs) }
            catch (_: Throwable) { WhaleDetector.LaunchFlow(0,0,0.0,0.0,0,0,0,0,0,false,50.0) }

        val prices = try { ts.history.toList().map { it.priceUsd }.filter { it.isFinite() && it > 0.0 } }
            catch (_: Throwable) { emptyList() }
        val current = ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: prices.lastOrNull() ?: 0.0
        val recentPeak = prices.takeLast(16).maxOrNull()?.takeIf { it > 0.0 } ?: current
        val sessionPeak7425 = if (mintKey7408.isNotBlank() && current > 0.0) {
            observedPeak7425.merge(mintKey7408, maxOf(recentPeak, current)) { a, b -> maxOf(a, b) } ?: maxOf(recentPeak, current)
        } else maxOf(recentPeak, current)
        val effectivePeak7425 = maxOf(recentPeak, sessionPeak7425)
        val peakPos = if (effectivePeak7425 > 0.0 && current > 0.0) (current / effectivePeak7425).coerceIn(0.0, 2.0) else 1.0
        val multiple = createMultiple(ts)
        val acute5m7425 = ts.lastPriceChange5m.takeIf { it.isFinite() } ?: 0.0
        val acuteCascade7425 = acute5m7425 <= -18.0
        val pumpedThenReversed7425 = ts.lastPriceChange1h >= 80.0 && acute5m7425 <= -8.0
        val observedPeakBreak7425 = prices.size >= 4 && peakPos < 0.78

        val sellDominant = flow.sellTx60s >= 3 &&
            (flow.buySharePct < 45.0 || flow.sellTx60s > flow.buyTx60s)
        val devDump = flow.devSellTx60s > 0
        val alreadyExpanded = multiple != null && multiple >= 2.5
        val rolledOver = peakPos < 0.80
        val lateByAge = age > 180_000L

        val creatorKnown = !dev.isNullOrBlank()
        // Dev participation is additive evidence, not a prerequisite. The
        // create socket can be ahead of wallet-enriched trade callbacks; lack
        // of a dev trade sample must not make us wait until the pump is obvious.
        val ignitionEvidence =
            age <= 90_000L &&
            !devDump &&
            flow.buyTx60s >= 3 &&
            flow.distinctBuyers60s >= 2 &&
            flow.buySharePct >= 60.0 &&
            flow.accelerationRising &&
            (multiple == null || multiple < 1.8) &&
            (creatorKnown || flow.distinctBuyers60s >= 4)

        val earlyInterest =
            age <= 120_000L &&
            !devDump &&
            flow.buyTx60s >= 2 &&
            flow.buySharePct >= 55.0 &&
            (multiple == null || multiple < 1.5)

        val phase = when {
            birth7440 == null -> Phase.METADATA_HYDRATING
            // V5.0.7425 — direction outranks youth. A token down sharply in the
            // current 5m window, or materially below an already observed peak,
            // cannot be called EXPANDING merely because createMultiple is absent
            // or the bot noticed it less than three minutes ago.
            devDump || acuteCascade7425 || pumpedThenReversed7425 || observedPeakBreak7425 ||
                (alreadyExpanded && (sellDominant || rolledOver)) ||
                (lateByAge && (sellDominant || rolledOver)) -> Phase.POST_PUMP_FADE
            ignitionEvidence -> Phase.IGNITION
            earlyInterest -> Phase.PRE_IGNITION
            age <= 180_000L && !sellDominant && (multiple == null || multiple < 2.5) -> Phase.EXPANDING
            else -> Phase.MATURE_OR_UNKNOWN
        }

        val reason = buildString {
            append("ageMs=").append(if (birth7440 != null) age else -1L)
            append(" birth=").append(birth7440?.source?.name ?: "HYDRATING")
            append(" mult=").append(multiple?.let { "%.2f".format(it) } ?: "?")
            append(" flow=").append(flow.buyTx60s).append("B/").append(flow.sellTx60s).append("S")
            append(" buyShare=").append("%.0f".format(flow.buySharePct))
            append(" buyers=").append(flow.distinctBuyers60s)
            append(" devB/S=").append(flow.devBuyTx60s).append('/').append(flow.devSellTx60s)
            append(" accel=").append(flow.accelerationRising)
            append(" peakPos=").append("%.2f".format(peakPos))
            append(" chg5m=").append("%.1f".format(acute5m7425))
            append(" chg1h=").append("%.1f".format(ts.lastPriceChange1h))
            append(" acuteFade=").append(acuteCascade7425 || pumpedThenReversed7425 || observedPeakBreak7425)
        }
        val out7408 = Snapshot(
            phase, age, multiple, flow.buySharePct, flow.buyTx60s, flow.sellTx60s,
            flow.distinctBuyers60s, flow.devBuyTx60s, flow.devSellTx60s,
            flow.accelerationRising, peakPos, reason,
            birthResolved = birth7440 != null,
            birthSource = birth7440?.source?.name ?: "",
        )
        if (mintKey7408.isNotBlank()) {
            cache7408[mintKey7408] = Cached7408(nowMs, out7408)
            val prior7408 = phaseEmit7408[mintKey7408]
            if (prior7408 == null || prior7408.phase != phase || nowMs - prior7408.atMs >= SAME_PHASE_EMIT_MS_7408) {
                phaseEmit7408[mintKey7408] = PhaseEmit7408(phase, nowMs)
                try { PipelineHealthCollector.labelInc("LAUNCH_PHASE_7401_${phase.name}") } catch (_: Throwable) {}
            }
            if (cache7408.size > 20_000) {
                val cutoff7408 = nowMs - 60_000L
                try { cache7408.entries.removeIf { it.value.atMs < cutoff7408 } } catch (_: Throwable) {}
                try { phaseEmit7408.entries.removeIf { it.value.atMs < cutoff7408 } } catch (_: Throwable) {}
                // Keep peak memory only while the mint remains recently observed.
                val liveKeys7425 = cache7408.keys
                try { observedPeak7425.keys.removeIf { it !in liveKeys7425 } } catch (_: Throwable) {}
            }
        } else {
            try { PipelineHealthCollector.labelInc("LAUNCH_PHASE_7401_${phase.name}") } catch (_: Throwable) {}
        }
        return out7408
    }
}
