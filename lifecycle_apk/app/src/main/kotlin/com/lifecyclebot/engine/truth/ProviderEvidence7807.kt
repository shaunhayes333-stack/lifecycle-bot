package com.lifecyclebot.engine.truth

/**
 * V5.0.7807 §A_FAILED_FETCH_IS_NOT_A_BEARISH_PRINT.
 *
 * Field Manual L190: "Treat stale, conflicting, or dimensionally inconsistent
 * supply, market cap, holder, price, and volume data as unknown. Unknown is
 * not zero risk." L357: missing evidence is uncertainty, not evidence of
 * positive or negative expectancy.
 *
 * Provider reads arrive as bare Doubles, and a failed or absent fetch looks
 * exactly like a real 0.0 — which liquidity / volume / holder consumers then
 * read as a drained pool or a dead token. This object names the four states
 * so consumers can tell them apart, and owns the one wording for a candidate
 * whose ESSENTIAL evidence is missing (per-candidate WAIT naming the
 * dependency — never a bearish or bullish verdict).
 */
object ProviderEvidence7807 {

    enum class Evidence7807 { NEGATIVE_MARKET_EVIDENCE, DATA_UNKNOWN, PROVIDER_FAILURE, STALE_DATA, OBSERVED }

    /**
     * Pure classification of one numeric provider read.
     * [fetchOk] false = the call failed / timed out; [ageMs] the read's age;
     * [confirmedZero] = an authority positively proved the zero (e.g. token map
     * TRUE_ZERO_LIQUIDITY / NO_ROUTE after its provider attempts).
     */
    fun classify7807(
        value: Double,
        fetchOk: Boolean,
        ageMs: Long,
        staleAfterMs: Long,
        confirmedZero: Boolean = false,
    ): Evidence7807 = when {
        !fetchOk -> Evidence7807.PROVIDER_FAILURE
        !value.isFinite() -> Evidence7807.DATA_UNKNOWN
        ageMs > staleAfterMs -> Evidence7807.STALE_DATA
        value <= 0.0 && confirmedZero -> Evidence7807.NEGATIVE_MARKET_EVIDENCE
        value <= 0.0 -> Evidence7807.DATA_UNKNOWN
        else -> Evidence7807.OBSERVED
    }

    /** True when the token-map route authority positively proved no liquidity/route. */
    private fun isConfirmedZeroLiquidity7807(routeStatus: String?): Boolean {
        val u = routeStatus?.trim()?.uppercase().orEmpty()
        return u == "TRUE_ZERO_LIQUIDITY" || u == "NO_ROUTE"
    }

    /** Per-candidate WAIT label naming the missing essential dependency. */
    private fun missingEssential7807(dependency: String, detail: String): String =
        "WAIT_MISSING_EVIDENCE_7807:${dependency.uppercase()}_${detail.uppercase()}"

    /**
     * FinalDecisionGate's liq<=0 block reason. A proved zero keeps the hard
     * label; an unknown reading still blocks this candidate (exitability is
     * essential evidence) but says it is a WAIT on missing data. Both keep the
     * "ZERO_LIQUIDITY" token so RejectTaxonomy still files them as
     * non-trainable hard safety, never as a learnable bearish rejection.
     */
    fun zeroLiquidityBlockReason7807(routeStatus: String?): String =
        if (isConfirmedZeroLiquidity7807(routeStatus)) "HARD_BLOCK_ZERO_LIQUIDITY"
        else "HARD_BLOCK_ZERO_LIQUIDITY|" + missingEssential7807("EXITABILITY", "LIQUIDITY_UNKNOWN")
}
