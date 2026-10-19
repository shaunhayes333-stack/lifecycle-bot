package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.8236 §A_DEFAULT_IS_NOT_A_READING — across every trader, lane and specialist.
 *
 * 7925 made the rule for the setup classifier (buy pressure 50, change 0, one-point history are defaults, not readings);
 * 8140 made it for liquidity (unread pool defers, never "low"); 8190 for MANIPULATED's mcap. The 8233/8235 forensics
 * show the other native rules still refusing on the same defaults: EXPRESS NO_CONTINUATION "0.0% < 1.0%" (2,197), QUALITY /
 * BLUECHIP / MOONSHOT / DIP_HUNTER "MCAP_TOO_LOW: $0" on an unhydrated cap, DIP_HUNTER "NOT_A_DIP: 0.0%" on no price
 * history, TREASURY "neutral_momentum;balanced_book" on V3 scores nobody computed (701), and the crypto desk scoring
 * bp=50.0 / chg=0.0 provider rows as flat. Each is a terminal refusal of a coin whose input simply has not arrived.
 *
 * One pure mapping, applied where every meme specialist's native verdict is assembled (SpecialistBrainBridge7542) and in
 * the crypto desk: a refusal whose rule fired on an input that is unobserved becomes a DEFER (re-evaluated when the
 * input lands; "DEFER" is non-terminal to RejectTaxonomy / the veto audit). A real reading below the rule's bar still
 * refuses; safety, rugcheck and liquidity rules are never mapped here.
 */
object LaneInputTruth8236 {
    const val MCAP_DEFER = "NATIVE_MCAP_UNHYDRATED_DEFER_8236"
    const val MOMENTUM_DEFER = "NATIVE_MOMENTUM_UNOBSERVED_DEFER_8236"
    private const val BUY_PRESSURE_DEFER = "NATIVE_BUY_PRESSURE_UNOBSERVED_DEFER_8236"
    const val PRICE_HISTORY_DEFER = "NATIVE_PRICE_HISTORY_UNOBSERVED_DEFER_8236"
    const val V3_UNSCORED_DEFER = "NATIVE_V3_UNSCORED_DEFER_8236"
    const val CRYPTO_DEFER = "CRYPTO_INPUT_UNOBSERVED_DEFER_8236"

    /** Which native inputs have actually been observed for a token (metadata, not value guesses). */
    data class Inputs8236(
        val mcap: Boolean,
        val momentum: Boolean,
        val buyPressure: Boolean,
        val priceHistory: Boolean,
        val v3: Boolean,
    )

    fun observed8236(ts: TokenState, historyPoints: Int): Inputs8236 = Inputs8236(
        mcap = ts.lastMcap.isFinite() && ts.lastMcap > 0.0,
        momentum = ts.priceChangeObservedAtMs8084 > 0L || historyPoints >= 2 || ts.momentum != null,
        buyPressure = ts.buyPressureObservedAtMs8084 > 0L,
        priceHistory = historyPoints >= 2,
        v3 = ts.lastV3Score != null,
    )

    private val MCAP_RULES = listOf("MCAP_TOO_LOW", "MCAP TOO LOW", "MCAP_TOO_LOW_", "MCAP=UNKNOWN")
    private val MOMENTUM_RULES = listOf("NO_CONTINUATION", "NO_MOMENTUM", "MOMENTUM_TOO_LOW", "WEAK_MOMENTUM:", "MOM=")
    private val V3_RULES = listOf("NEUTRAL_MOMENTUM", "BALANCED_BOOK", "WEAK_MOMENTUM_", "NO_CONFIRMED_SCALP_SETUP")
    private val BP_RULES = listOf("SELL_PRESSURE", "LOW_BUY_PRESSURE", "BUY_PRESSURE_TOO_LOW", "BUYP=", "BUY_PRESSURE:")
    private val NEVER_MAPPED = listOf("RUG", "SAFETY", "HONEYPOT", "LIQ", "BLOCK", "BLACKLIST", "SELLABILITY")

    /** Pure: the DEFER that replaces a native [reason] fired on an unobserved input, or null when the refusal stands. */
    fun deferFor8236(lane: String, reason: String, inp: Inputs8236): String? {
        val r = reason.uppercase()
        if (r.isBlank() || r.contains("_DEFER") || NEVER_MAPPED.any { r.contains(it) }) return null
        val defer = when {
            !inp.mcap && MCAP_RULES.any { r.contains(it) } && !r.contains("TOO_HIGH") -> MCAP_DEFER
            !inp.priceHistory && r.contains("NOT_A_DIP") -> PRICE_HISTORY_DEFER
            !inp.momentum && !inp.buyPressure && MOMENTUM_RULES.any { r.contains(it) } -> MOMENTUM_DEFER
            !inp.v3 && V3_RULES.any { r.contains(it) } -> V3_UNSCORED_DEFER
            !inp.buyPressure && BP_RULES.any { r.contains(it) } -> BUY_PRESSURE_DEFER
            else -> null
        } ?: return null
        count8236("${defer}_$lane")
        return "$defer:${reason.take(60)}"
    }

    /**
     * The five native input deferrals name the observation their existing reader must refresh.
     * Match the complete leading contract, not an arbitrary DEFER/UNOBSERVED substring. This
     * identifies data; callers still apply token-safety judgement before treating it as retryable.
     * V3 is recomputed by the existing forced-poll path, never supplied as a made-up score.
     */
    internal fun nativeDataInputs8237(reason: String?): List<String> =
        when (reason?.substringBefore(':')?.trim()?.uppercase()) {
            MCAP_DEFER -> listOf("mcap:UNOBSERVED")
            MOMENTUM_DEFER -> listOf("chg:UNOBSERVED")
            BUY_PRESSURE_DEFER -> listOf("bp:UNOBSERVED")
            PRICE_HISTORY_DEFER -> listOf("shape:HISTORY_SPARSE")
            V3_UNSCORED_DEFER -> listOf("v3:UNSCORED")
            else -> emptyList()
        }

    /** Pure: the crypto desk's bp (50 from zero counts) and 24h change (exact 0.0) are defaults when no provider stated them. */
    fun cryptoInputsUnobserved8236(buys24h: Int, sells24h: Int, change24hPct: Double): Boolean =
        buys24h + sells24h <= 0 && (!change24hPct.isFinite() || change24hPct == 0.0)

    private fun count8236(label: String) {
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }
}
