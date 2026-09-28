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
    enum class Phase {
        PRE_IGNITION,
        IGNITION,
        EXPANDING,
        POST_PUMP_FADE,
        MATURE_OR_UNKNOWN,
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
    ) {
        val early: Boolean get() = phase == Phase.PRE_IGNITION || phase == Phase.IGNITION
        val tooLateForSnipe: Boolean get() = phase == Phase.POST_PUMP_FADE
    }

    fun trueAgeMs(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Long {
        val create = try { PumpCurveKeys7269.createdAtMs7280(ts.mint) } catch (_: Throwable) { null }
        val firstHistory = try { ts.history.firstOrNull()?.ts?.takeIf { it > 0L } } catch (_: Throwable) { null }
        val fallback = ts.addedToWatchlistAt.takeIf { it > 0L }
        val origin = create?.takeIf { it > 0L } ?: firstHistory ?: fallback ?: nowMs
        return (nowMs - origin).coerceAtLeast(0L)
    }

    private fun createMultiple(ts: TokenState): Double? = try {
        val createSol = PumpCurveKeys7269.createPriceSol7280(ts.mint) ?: return null
        val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
        val px = ts.lastPrice
        if (!createSol.isFinite() || createSol <= 0.0 || !solUsd.isFinite() || solUsd <= 0.0 ||
            !px.isFinite() || px <= 0.0) null
        else EconomicUnitInvariant7061.usdToSol(px, solUsd) / createSol
    } catch (_: Throwable) { null }

    fun snapshot(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Snapshot {
        val age = trueAgeMs(ts, nowMs)
        val dev = try { OperatorRegistry.getDevWallet(ts.mint) } catch (_: Throwable) { null }
        val flow = try { WhaleDetector.launchFlow7401(ts.mint, dev, nowMs) }
            catch (_: Throwable) { WhaleDetector.LaunchFlow(0,0,0.0,0.0,0,0,0,0,0,false,50.0) }

        val prices = try { ts.history.toList().map { it.priceUsd }.filter { it.isFinite() && it > 0.0 } }
            catch (_: Throwable) { emptyList() }
        val current = ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: prices.lastOrNull() ?: 0.0
        val recentPeak = prices.takeLast(16).maxOrNull()?.takeIf { it > 0.0 } ?: current
        val peakPos = if (recentPeak > 0.0 && current > 0.0) (current / recentPeak).coerceIn(0.0, 2.0) else 1.0
        val multiple = createMultiple(ts)

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
            devDump || (alreadyExpanded && (sellDominant || rolledOver)) ||
                (lateByAge && (sellDominant || rolledOver)) -> Phase.POST_PUMP_FADE
            ignitionEvidence -> Phase.IGNITION
            earlyInterest -> Phase.PRE_IGNITION
            age <= 180_000L && !sellDominant && (multiple == null || multiple < 2.5) -> Phase.EXPANDING
            else -> Phase.MATURE_OR_UNKNOWN
        }

        val reason = buildString {
            append("ageMs=").append(age)
            append(" mult=").append(multiple?.let { "%.2f".format(it) } ?: "?")
            append(" flow=").append(flow.buyTx60s).append("B/").append(flow.sellTx60s).append("S")
            append(" buyShare=").append("%.0f".format(flow.buySharePct))
            append(" buyers=").append(flow.distinctBuyers60s)
            append(" devB/S=").append(flow.devBuyTx60s).append('/').append(flow.devSellTx60s)
            append(" accel=").append(flow.accelerationRising)
            append(" peakPos=").append("%.2f".format(peakPos))
        }
        try { PipelineHealthCollector.labelInc("LAUNCH_PHASE_7401_${phase.name}") } catch (_: Throwable) {}
        return Snapshot(
            phase, age, multiple, flow.buySharePct, flow.buyTx60s, flow.sellTx60s,
            flow.distinctBuyers60s, flow.devBuyTx60s, flow.devSellTx60s,
            flow.accelerationRising, peakPos, reason,
        )
    }
}
