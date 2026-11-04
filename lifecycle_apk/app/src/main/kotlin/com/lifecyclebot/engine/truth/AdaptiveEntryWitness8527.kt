package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.TradeEnvironment
import org.json.JSONObject

/**
 * Entry-only observations for AdaptiveLearningEngine. JSON is immutable and finite-only; missing keys are absent,
 * never zero, a bucket centre, a volume proxy or a later close-time observation. Volume is explicitly trailing24hUSD;
 * age is canonical token birth minutes; curve, pressure, concentration and rug cleanliness are percentages0..100.
 */
object AdaptiveEntryWitness8527 {
    const val PREFIX = "ale8527."
    private val emaStates = listOf("FLAT", "BULL_FLAT", "BULL_FAN", "BEAR_FLAT", "BEAR_FAN")

    fun capture(ts: TokenState, nowMs: Long): String {
        if (nowMs <= 0L) return ""
        val observations = linkedMapOf<String, Double>()
        fun put(key: String, value: Double?, valid: (Double) -> Boolean = { it >= 0.0 }) {
            if (value != null && value.isFinite() && valid(value)) observations[key] = value
        }
        val tm = ts.tokenMap
        put("mcap", tm.marketCap ?: ts.lastMcap.takeIf { it > 0.0 }) { it > 0.0 }
        put("liquidity", tm.liquidityUsd ?: ts.lastLiquidityUsd.takeIf { it > 0.0 })
        put("volume24hUsd", tm.volume24hUsd)
        CanonicalTokenBirthTime7440.peekBirthMs8418(ts.mint, nowMs)?.takeIf { it in 1L..nowMs }?.let {
            put("tokenAge", (nowMs - it) / 60_000.0)
        }
        put("buyRatio", com.lifecyclebot.engine.LaneInputTruth8236.buyPressure8491(ts, nowMs)) { it in 0.0..100.0 }
        put("holderConc", ts.topHolderPct ?: tm.topHolderConcentrationPct) { it in 0.0..100.0 }
        val safety = ts.safety
        if ("holderConc" !in observations && safety.topHolderObservedAtMs8522 in 1L..nowMs) {
            put("holderConc", safety.topHolderPct) { it in 0.0..100.0 }
        }
        if (safety.checkedAt in 1L..nowMs) put("rugcheck", safety.rugcheckScore.toDouble()) { it in 0.0..100.0 && it != 1.0 }
        // These scalar fields have no zero-observed timestamp. Preserve only their positively witnessed values.
        if (ts.holderDataResolved) {
            put("holderCount", ts.history.lastOrNull()?.holderCount?.takeIf { it > 0 }?.toDouble())
            if (ts.history.count { it.holderCount > 0 } >= 2) put("holderGrowth", ts.holderGrowthRate) { true }
        }
        val meta = ts.meta
        // Tracker progress can be market-cap derived, not a measured percentage. Only explicit graduation is exact here.
        if (tm.migratedOrGraduated) put("bondingCurve", 100.0)
        val ema = meta.emafanAlignment.uppercase(java.util.Locale.US)
        if (meta.ema8.isFinite() && meta.ema8 > 0.0 && ema in emaStates) {
            observations["emaFan"] = emaStates.indexOf(ema).toDouble()
        }
        put("entryScore", ts.entryScore) { it in 0.0..100.0 }
        // No developer ownership or ATH distance witness exists on this producer: both stay absent.
        return encode(ts.mint, nowMs, observations)
    }

    internal fun encode(mint: String, observedAtMs: Long, observations: Map<String, Double>): String =
        JSONObject().put("schema", 8527).put("mint", mint).put("at", observedAtMs).put("values",
            JSONObject().apply { observations.forEach { (key, value) -> if (value.isFinite()) put(key, value) } }
        ).toString()

    fun canonicalFeatures8527(
        snap: EntryStrategySnapshot6450.Snapshot?, mint: String, environment: TradeEnvironment, entryTimeMs: Long
    ): Map<String, Double> {
        if (snap == null || snap.mint != mint || snap.entryTimestampMs != entryTimeMs ||
            !snap.mode8090.equals(environment.name, true)) return emptyMap()
        return decodeForEntry8527(snap.adaptiveEntry8527, mint, entryTimeMs)
    }

    internal fun decodeForEntry8527(raw: String, mint: String, entryTimeMs: Long): Map<String, Double> = try {
        val j = JSONObject(raw)
        val at = j.optLong("at", 0L)
        if (j.optInt("schema", 0) != 8527 || j.optString("mint") != mint || at <= 0L || at > entryTimeMs) emptyMap()
        else {
            val values = j.optJSONObject("values")
            if (values == null) emptyMap() else buildMap {
                values.keys().forEach { key ->
                    val value = values.optDouble(key, Double.NaN)
                    if (value.isFinite()) put(PREFIX + key, value)
                }
            }
        }
    } catch (_: Exception) { emptyMap() }

    fun emaName8527(value: Double?): String =
        value?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()?.let { emaStates.getOrNull(it) }.orEmpty()
}
