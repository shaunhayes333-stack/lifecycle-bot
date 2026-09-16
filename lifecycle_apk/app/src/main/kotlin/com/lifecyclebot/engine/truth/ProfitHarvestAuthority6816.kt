package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6816 §PROFIT_HARVEST — operator directive V5.0.6813 P0 task:
 *   "Implement adaptive partial harvesting when a position achieves
 *    meaningful profit (bank fraction, trail remainder). Realised
 *    profit must replenish CASH continuously instead of locking up
 *    open market value."
 *
 * DESIGN — advisory, additive, cannot mutate ledger or send trades.
 *   • Consumers (BotService loop / exit coordinator / partial sell
 *     path) periodically call `evaluate(positionId, unrealisedPct)`.
 *   • The authority tracks the last-observed unrealised return per
 *     position, and stamps a harvest advisory once thresholds are met.
 *   • `pendingHarvest(positionId)` returns the current advice: which
 *     fraction to bank now, and whether the remainder should trail.
 *   • The authority NEVER submits sells itself. It publishes an
 *     advisory that PartialSellSizer (or a future orchestrator) may
 *     consume. This is deliberate — after V5.0.6811 crash trauma
 *     we do not put new work on the hot execution path yet. This
 *     ship wires observability + advisory API only; live consumption
 *     lands in a follow-up ship once GH CI proves the authority is
 *     stable under load.
 *
 * Threshold ladder (bank fraction of REMAINING quantity):
 *     unrealised >=  40% → bank 20%  (secures early profit)
 *     unrealised >=  90% → bank 30%  (recycle mid-run capital)
 *     unrealised >= 180% → bank 40%  (aggressive de-risk)
 *     unrealised >= 400% → bank 50%  (runner de-lever)
 *   Once a threshold fires for a position it does NOT fire again at
 *   the same tier — advancing tiers only. Trail-mode is implicit
 *   after the first harvest: the remainder is left in the position
 *   for the existing exit logic.
 */
object ProfitHarvestAuthority6816 {

    data class HarvestAdvice(
        val positionId: String,
        val unrealisedPct: Double,
        val bankFraction: Double,
        val tier: Int,
        val atMs: Long,
        val trailRemainder: Boolean,
    )

    private data class Tier(val thresholdPct: Double, val bankFraction: Double)

    private val TIERS = listOf(
        Tier(40.0, 0.20),
        Tier(90.0, 0.30),
        Tier(180.0, 0.40),
        Tier(400.0, 0.50),
    )

    private val activeAdvice = ConcurrentHashMap<String, HarvestAdvice>()
    // Tracks the highest tier index we've already advised per positionId,
    // so we do not re-emit the same advice on every tick.
    private val emittedTier = ConcurrentHashMap<String, Int>()
    private val evaluations = AtomicLong(0L)
    private val advised = AtomicLong(0L)
    private val consumed = AtomicLong(0L)

    /**
     * Called on every position mark refresh. Cheap: one map lookup +
     * one arithmetic compare per position. Returns the advice when a
     * NEW tier just fired; null otherwise.
     */
    fun evaluate(
        positionId: String,
        unrealisedPct: Double,
        lane: String = "",
    ): HarvestAdvice? {
        evaluations.incrementAndGet()
        if (positionId.isBlank() || !unrealisedPct.isFinite()) return null
        // Find highest tier the current unrealised meets.
        var tier = -1
        for ((idx, t) in TIERS.withIndex()) {
            if (unrealisedPct >= t.thresholdPct) tier = idx
        }
        if (tier < 0) return null
        val prev = emittedTier[positionId] ?: -1
        if (tier <= prev) return activeAdvice[positionId]
        val advice = HarvestAdvice(
            positionId = positionId,
            unrealisedPct = unrealisedPct,
            bankFraction = TIERS[tier].bankFraction,
            tier = tier,
            atMs = System.currentTimeMillis(),
            trailRemainder = true,
        )
        activeAdvice[positionId] = advice
        emittedTier[positionId] = tier
        advised.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PROFIT_HARVEST_ADVISED_6816")
            PipelineHealthCollector.labelInc("PROFIT_HARVEST_ADVISED_6816_TIER_$tier")
            if (lane.isNotBlank()) {
                PipelineHealthCollector.labelInc(
                    "PROFIT_HARVEST_ADVISED_6816_${lane.uppercase().take(24)}"
                )
            }
            ForensicLogger.lifecycle(
                "PROFIT_HARVEST_ADVISED_6816",
                "positionId=${positionId.take(24)} lane=$lane " +
                    "unrealisedPct=${"%.2f".format(unrealisedPct)} " +
                    "tier=$tier bankFraction=${"%.2f".format(advice.bankFraction)} " +
                    "trailRemainder=${advice.trailRemainder}",
            )
        } catch (_: Throwable) {}
        return advice
    }

    /** Current outstanding advice for a position, if any. */
    fun pendingHarvest(positionId: String): HarvestAdvice? =
        if (positionId.isBlank()) null else activeAdvice[positionId]

    /** Consumer (executor / partial sell) marks advice as taken. */
    fun consumed(positionId: String) {
        if (positionId.isBlank()) return
        if (activeAdvice.remove(positionId) != null) {
            consumed.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PROFIT_HARVEST_CONSUMED_6816")
            } catch (_: Throwable) {}
        }
    }

    /** Called when a position terminally closes — clear book-keeping. */
    fun onPositionClosed(positionId: String) {
        if (positionId.isBlank()) return
        activeAdvice.remove(positionId)
        emittedTier.remove(positionId)
    }

    fun statusLine(): String =
        "evals=${evaluations.get()} advised=${advised.get()} " +
            "consumed=${consumed.get()} outstanding=${activeAdvice.size}"

    internal fun clearForTest() {
        activeAdvice.clear(); emittedTier.clear()
        evaluations.set(0L); advised.set(0L); consumed.set(0L)
    }
}
