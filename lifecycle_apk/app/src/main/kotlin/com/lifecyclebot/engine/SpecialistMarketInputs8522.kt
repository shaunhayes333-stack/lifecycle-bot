package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AdmissionBirth8144

/** One units-preserving market read for native qualification and the real specialist entry door. */
object SpecialistMarketInputs8522 {
    data class Read(
        val history: NativeHistory8521.Read,
        val age: NativeLaneAge8521.Evidence,
        val price: Double,
        val buyPressurePct: Double?,
        val change5mPct: Double?,
        val momentumPct: Double?,
        val continuation: ContinuationRead8348.Continuation8348,
        val v3Score: Int,
        val v3Confidence: Int,
    )

    /** Entry scoring consumes exactly the observations which produced this cycle's native qualification. */
    fun forCycle8522(sheet: ToolkitSignalSheet.Sheet, ts: TokenState): Read =
        sheet.marketInputs8522 ?: read8522(ts)

    fun read8522(ts: TokenState, nowMs: Long = System.currentTimeMillis(),
                 birth: AdmissionBirth8144.Read8144? = try { AdmissionBirth8144.readFor8144(ts, nowMs) } catch (_: Throwable) { null },
                 history: NativeHistory8521.Read = NativeHistory8521.read8521(ts, nowMs),
                 wave: com.lifecyclebot.engine.market.KineticFlow8506.Vector? = com.lifecyclebot.engine.market.KineticFlow8506.current(ts.mint, nowMs)): Read {
        val age = NativeLaneAge8521.evidence8521(birth)
        val recent = history.recentPrices
        val historyPct = historyMomentum8522(recent)
        val continuation = ContinuationRead8348.read8348(ts, historyPct ?: 0.0, recent.size,
            age.inputMinutes, if (recent.size < 2) ExpressMomentum8190.sinceLaunchPct8190(ts, age.inputMinutes) else null, nowMs, wave)
        val change = LaneInputTruth8236.change5m8491(ts, nowMs)
        val momentum = signedMomentum8522(change, historyPct, continuation)
        return Read(history, age,
            ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: history.prices.lastOrNull() ?: 0.0,
            LaneInputTruth8236.buyPressure8491(ts, nowMs), change, momentum, continuation,
            (ts.lastV3Score ?: ts.entryScore.takeIf { it.isFinite() }?.toInt() ?: 0).coerceIn(0, 100),
            (ts.lastV3Confidence ?: 0).coerceIn(0, 100))
    }

    internal fun historyMomentum8522(prices: List<Double>): Double? {
        if (prices.size < 2) return null
        val first = prices.first()
        val last = prices.last()
        if (!first.isFinite() || first <= 0.0 || !last.isFinite() || last <= 0.0) return null
        return ((last / first - 1.0) * 100.0).takeIf { it.isFinite() }
    }

    /** The legacy momentum field has no observation stamp/window; native and entry reads use the same dated change. */
    internal fun signedMomentum8522(change5m: Double?, historyPct: Double?,
                                   continuation: ContinuationRead8348.Continuation8348): Double? =
        continuation.recentSwapMovePct?.takeIf { it.isFinite() }
            ?: change5m?.takeIf { it.isFinite() }
            ?: historyPct?.takeIf { it.isFinite() }
            ?: continuation.takeIf { it.source == ContinuationRead8348.Source8348.FROM_TAPE && it.pct.isFinite() }?.pct

    /** A newly observed risk must invalidate qualification even when the price and check timestamp did not move. */
    internal fun riskFingerprint8522(ts: TokenState): Int = listOf(
        ts.safety, ts.holderDataResolved, ts.topHolderPct, ts.tokenMap.topHolderConcentrationPct,
    ).hashCode()

    /** Only missing-data verdicts request hydration. A real low-liquidity, cap or safety refusal stands. */
    internal fun missingNativeInputs8522(opinions: Collection<SpecialistBrainBridge7542.Opinion>): List<String> =
        opinions.asSequence().filter { it.authoritative && !it.eligible && NativeLaneConstraints8521.missingData8521(it.reason) }
            .mapNotNull { opinion ->
                val reason = opinion.reason.uppercase()
                when {
                    reason.contains("PRICE_HISTORY") || reason.contains("HISTORY_SPARSE") -> "shape:HISTORY_SPARSE"
                    reason.contains("BUY_PRESSURE") -> "bp:UNKNOWN"
                    reason.contains("MOMENTUM") -> "chg:UNKNOWN"
                    reason.contains("MCAP") -> "mcap:UNKNOWN"
                    reason.contains("LIQ") -> "liq:UNKNOWN"
                    reason.contains("AGE") || reason.contains("BIRTH_METADATA") -> "age:UNKNOWN"
                    reason.contains("PRICE") -> "price:UNKNOWN"
                    else -> null
                }
            }.distinctBy { it.substringBefore(':') }.toList()
}
