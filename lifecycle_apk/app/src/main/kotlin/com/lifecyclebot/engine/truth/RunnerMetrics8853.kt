package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.SafetyTier
import com.lifecyclebot.engine.chart.StructureTracker7962
import org.json.JSONObject

/**
 * Read-only, source-age-checked snapshots for the SELECTED 8852 runner study.
 * These are the actual observed fields feeding the existing brains, NOT a call
 * to an exit executor or a reconstruction of an unobserved exit decision.
 */
internal object RunnerMetrics8853 {
    private const val FLOW_FRESH_MS = 60_000L
    private const val SAFETY_FRESH_MS = 120_000L
    private const val LIQUIDITY_FRESH_MS = 120_000L

    data class Witness(
        val atMs: Long,
        val brokeStructure: Boolean? = null,
        val structureBuyShare: Double? = null,
        val buyerPressurePct: Double? = null,
        val change5mPct: Double? = null,
        val hardSafety: Boolean? = null,
        val liquidityUsd: Double? = null,
        val liquidityPool: String = "",
        val pressureSourceMs: Long = 0L,
        val changeSourceMs: Long = 0L,
        val safetySourceMs: Long = 0L,
        val liquiditySourceMs: Long = 0L,
    ) {
        fun usable(): Boolean = atMs > 0L && listOf(brokeStructure, structureBuyShare, buyerPressurePct,
            change5mPct, hardSafety, liquidityUsd).any { it != null }
        /** Full path coverage requires real source times, not defaults or old cached fields. */
        fun replayCoverage(): Boolean = buyerPressurePct != null && change5mPct != null && hardSafety != null &&
            fresh(atMs, pressureSourceMs, FLOW_FRESH_MS) &&
            fresh(atMs, changeSourceMs, FLOW_FRESH_MS) &&
            fresh(atMs, safetySourceMs, SAFETY_FRESH_MS)
        fun json(): JSONObject = JSONObject().put("at", atMs)
            .put("break", brokeStructure ?: JSONObject.NULL)
            .put("share", structureBuyShare ?: JSONObject.NULL)
            .put("pressure", buyerPressurePct ?: JSONObject.NULL)
            .put("change5m", change5mPct ?: JSONObject.NULL)
            .put("hard", hardSafety ?: JSONObject.NULL)
            .put("liq", liquidityUsd ?: JSONObject.NULL).put("pool", liquidityPool)
            .put("pressureSourceMs", pressureSourceMs.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("changeSourceMs", changeSourceMs.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("safetySourceMs", safetySourceMs.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("liquiditySourceMs", liquiditySourceMs.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("replayCoverage", replayCoverage())
    }

    private fun fresh(nowMs: Long, atMs: Long, maxAgeMs: Long): Boolean =
        atMs > 0L && nowMs - atMs in 0L..maxAgeMs

    /** Low-cost capture is called only after a new, independently attributed market mark. */
    fun capture(mint: String, nowMs: Long): Witness? {
        val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return null
        val flowFresh = fresh(nowMs, ts.buyPressureObservedAtMs8084, FLOW_FRESH_MS)
        val changeFresh = fresh(nowMs, ts.priceChangeObservedAtMs8084, FLOW_FRESH_MS)
        val safeAt = maxOf(ts.safety.checkedAt, ts.lastSafetyCheck)
        val safetyFresh = fresh(nowMs, safeAt, SAFETY_FRESH_MS)
        val liquidityFresh = fresh(nowMs, ts.safety.liquidityObservedAtMs8522, LIQUIDITY_FRESH_MS)
        val structure = try {
            if (StructureTracker7962.hasCurrentEntryMark8506(mint, nowMs)) {
                val (fast, slow) = StructureTracker7962.reads7967(mint, nowMs)
                fast ?: slow
            } else null
        } catch (_: Throwable) { null }
        val pressure = ts.lastBuyPressurePct.takeIf { flowFresh && it.isFinite() && it in 0.0..100.0 }
        val change = ts.lastPriceChange5m.takeIf { changeFresh && it.isFinite() }
        val liq = ts.safety.liquidityObservedUsd8522.takeIf { liquidityFresh && it.isFinite() && it > 0.0 }
        return Witness(nowMs, structure?.brokeStructure,
            structure?.buyShare?.takeIf { it.isFinite() && it in 0.0..1.0 },
            pressure, change, if (safetyFresh) ts.safety.tier == SafetyTier.HARD_BLOCK else null,
            liq, if (liq != null) ts.safety.liquidityObservedPool8522 else "",
            if (pressure != null) ts.buyPressureObservedAtMs8084 else 0L,
            if (change != null) ts.priceChangeObservedAtMs8084 else 0L,
            if (safetyFresh) safeAt else 0L,
            if (liq != null) ts.safety.liquidityObservedAtMs8522 else 0L)
            .takeIf { it.usable() }
    }

    /**
     * An OBSERVED deterioration candidate, not an adopted trading rule. Safety
     * is prioritized. A soft exit requires two independent, fresh market facts.
     * UNKNOWN metrics return null; null never means a safe hold or no sell.
     */
    fun candidateExit(w: Witness, ratio: Double, entryLiquidity: Double?, entryPool: String): String? {
        if (!w.usable() || !ratio.isFinite() || ratio <= 0.0) return null
        if (w.hardSafety == true) return "OBSERVED_SAFETY_HARD_BLOCK"
        if (entryLiquidity != null && entryLiquidity > 0.0 && w.liquidityUsd != null &&
            w.liquidityPool.isNotBlank() && w.liquidityPool == entryPool &&
            w.liquidityUsd <= entryLiquidity * 0.5) return "OBSERVED_SAME_POOL_LIQUIDITY_HALF"
        if (ratio >= 1.2 && w.brokeStructure == true && w.structureBuyShare != null &&
            w.structureBuyShare < 0.45) return "OBSERVED_STRUCTURE_BREAK_WEAK_BUYERS"
        if (ratio >= 1.2 && w.buyerPressurePct != null && w.change5mPct != null &&
            w.buyerPressurePct < 35.0 && w.change5mPct <= -10.0)
            return "OBSERVED_DISTRIBUTION_AND_FALLING_PRICE"
        return null
    }
}
