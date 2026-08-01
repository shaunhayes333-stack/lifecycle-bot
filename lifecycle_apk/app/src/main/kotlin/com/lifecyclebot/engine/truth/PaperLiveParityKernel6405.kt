package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6405 §8 — PAPER / LIVE PARITY (single accounting kernel).
 *
 * Both PAPER and LIVE lanes flow through the identical arithmetic:
 *   pnl_lamports = recovered_lamports - spent_lamports
 * with the raw-qty invariant sum(sold_raw) <= entry_raw enforced by
 * [DecimalIntegrityAuthority6405].
 *
 * This authority owns the parity check itself: given a canonical
 * fold (from [CanonicalEventStream6405]) it produces the SAME
 * PnLLamports value regardless of lane. The bot's UI, journal and
 * reconciler read from here so paper and live can never diverge.
 */
object PaperLiveParityKernel6405 {

    data class PositionKey(val wallet: String, val mint: String, val positionGeneration: Long)

    data class PnLReport(
        val positionKey: PositionKey,
        val rawBought: BigInteger,
        val rawSold: BigInteger,
        val lamportsSpent: BigInteger,
        val lamportsRecovered: BigInteger,
        val realisedLamports: BigInteger,
        val isPaper: Boolean,
    ) {
        val fullyExited: Boolean get() = rawSold >= rawBought && rawBought.signum() > 0
    }

    private val laneFlag = ConcurrentHashMap<String, Boolean>() // key → isPaper

    fun setLane(key: PositionKey, isPaper: Boolean) {
        laneFlag["${key.wallet}|${key.mint}|${key.positionGeneration}"] = isPaper
    }

    fun compute(key: PositionKey): PnLReport {
        val fold = CanonicalEventStream6405.fold(key.mint, key.positionGeneration, key.wallet)
        val isPaper = laneFlag["${key.wallet}|${key.mint}|${key.positionGeneration}"] ?: false
        val realised = fold.lamportsRecovered.subtract(fold.lamportsSpent)
        val report = PnLReport(
            positionKey = key,
            rawBought = fold.rawBought,
            rawSold = fold.rawSold,
            lamportsSpent = fold.lamportsSpent,
            lamportsRecovered = fold.lamportsRecovered,
            realisedLamports = realised,
            isPaper = isPaper,
        )
        try {
            ForensicLogger.lifecycle(
                "PAPER_LIVE_PARITY_6405",
                "wallet=${key.wallet.take(6)} mint=${key.mint.take(10)} gen=${key.positionGeneration} " +
                    "paper=$isPaper rawBought=${fold.rawBought} rawSold=${fold.rawSold} " +
                    "spent=${fold.lamportsSpent} recovered=${fold.lamportsRecovered} realised=$realised",
            )
            PipelineHealthCollector.labelInc("PAPER_LIVE_PARITY_6405")
        } catch (_: Throwable) {}
        return report
    }

    internal fun clearForTest() { laneFlag.clear() }
}
