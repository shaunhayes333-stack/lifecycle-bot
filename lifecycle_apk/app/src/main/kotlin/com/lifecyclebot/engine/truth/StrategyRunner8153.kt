package com.lifecyclebot.engine.truth

/**
 * V5.0.8153 §FALSE_RUNNER_REFUSALS — pure rules behind three live refusals that stopped coins the bot's own evidence
 * qualified, without loosening any hard check (sellability, depth, route, identity, structural risk are untouched).
 *
 *  - NET_EDGE_COIN_TOO_SLOW_8035 capped the lane's textbook move by the coin's last-hour range. That range needed six
 *    prints — two candles — so a launch seen for three minutes "moved 5%" and was refused as slow. The expected move is
 *    now the SELECTED strategy's own executable move when its record is supported (30+ labels net of cost, mean - SE > 0:
 *    mean net + the round trip); else the mandate capped by a DENSE range ([DENSE_MIN_BARS_8153]+ candles over
 *    [DENSE_MIN_SPAN_MS_8153]) or the provider's 1h change; a fresh launch with only a sparse range and no 1h change
 *    defers for evidence ([DEFER_REASON_8153], bounded by CandidateAdmission8136's coalesced window) instead of refusing.
 *  - Watch-first refused a thin cell (under 10 labels — every launch) even when the proven-only door had just admitted the
 *    candidate on its OWN evidence: its strategy valid on its own record, its kind proven, a run forming in a cell proven
 *    to run, or its admission record's supported specific edge. That evidence now releases the THIN cell; a cap band or
 *    cell measured negative still refuses.
 *  - Unread liquidity (0 / non-finite) is UNKNOWN, never LOW: a launch probe is costed without the impact of depth
 *    nobody read ([probeSizeUsd8153]), as NetEdge8030.costPct8030 already does for the entry.
 */
object StrategyMove8153 {
    data class Move8153(val expectedGrossPct: Double, val basis: String, val deferForHistory: Boolean)

    internal const val DENSE_MIN_BARS_8153 = 6
    internal const val DENSE_MIN_SPAN_MS_8153 = 15L * 60_000L
    internal const val FRESH_AGE_MIN_8153 = 60.0
    const val DEFER_REASON_8153 = "DATA_INCOMPLETE_MOVE_HISTORY_8153"
    const val DEFER_FEATURE_8153 = "move:HISTORY_SPARSE"

    /** A DOWN move is never evidence of positive upside (the old abs(1hChange) inverted it). */
    fun positiveHour8153(changePct: Double): Double? = changePct.takeIf { it.isFinite() && it > 0.0 }

    /** Momentum-entry lanes need a proven strategy before treating a falling hour as an upside opportunity. */
    fun momentumLane8153(lane: String): Boolean =
        lane.trim().uppercase() in setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER")

    /** Pure: is the last hour's history dense enough for its range to say how far the coin moves? */
    fun dense8153(bars: Int, spanMs: Long): Boolean = bars >= DENSE_MIN_BARS_8153 && spanMs >= DENSE_MIN_SPAN_MS_8153

    /** Pure: a fresh launch — known age within the hour, or an unknown age (unknown is never old). */
    fun fresh8153(ageMin: Double?): Boolean = ageMin == null || !ageMin.isFinite() || ageMin <= FRESH_AGE_MIN_8153

    /** Pure: the selected strategy's record supports a move estimate — 30+ labels net of cost, mean - SE above zero. */
    internal fun strategySupported8153(n: Int, meanPct: Double, sePct: Double): Boolean =
        n >= StrategyRank8153.SUPPORT_MIN_N_8153 && meanPct.isFinite() && sePct.isFinite() && meanPct - sePct > 0.0

    /**
     * Pure: the expected executable gross move for the entry test (NetEdge8030.entryClears8030 against [costPct]).
     * [strategyN] / [strategyMeanPct] / [strategySePct]: the selected strategy's own record (labels net of cost; n 0 = none).
     */
    fun expected8153(
        mandatePct: Double,
        rangePct: Double?,
        rangeDense: Boolean,
        h1Pct: Double?,
        strategyN: Int,
        strategyMeanPct: Double,
        strategySePct: Double,
        costPct: Double,
        freshLaunch: Boolean,
        signedHourPct: Double? = null,
        momentumEntry: Boolean = false,
        inLaunchWindow8418: Boolean = false,
        runupFromHourLowPct8529: Double? = null,
        roomToHourHighPct8529: Double? = null,
    ): Move8153 {
        val m = expectedRaw8153(mandatePct, rangePct, rangeDense, h1Pct, strategyN, strategyMeanPct, strategySePct, costPct,
            freshLaunch, signedHourPct, momentumEntry, inLaunchWindow8418)
        if (m.basis !in SPENT_BASES_8529 || inLaunchWindow8418) return m
        val left = remaining8529(m.expectedGrossPct, runupFromHourLowPct8529, roomToHourHighPct8529) ?: return m
        return m.copy(expectedGrossPct = left)
    }

    /**
     * V5.0.8529 §EXPECTED_MOVE_IS_WHAT_IS_LEFT — the range / 1h / mandate read measures how far the coin moved this hour, not
     * how far it will move after the buy. 8527 live: STONKS bought after +70 % in the hour (expected = the full lane
     * mandate), 5 of 13 losses fell from the fill inside 75 s, admitted forward -12.7 % vs refused -0.5 %. What is left is
     * the hour's move minus the part already run from the hour's low, and never less than the room back to the hour's high.
     * A setup with its own supported record (basis STRATEGY) and a coin inside its launch window keep their reads.
     * Null (no change) when either price position is unknown.
     */
    fun remaining8529(expectedPct: Double, runupFromLowPct: Double?, roomToHighPct: Double?): Double? {
        if (!expectedPct.isFinite() || runupFromLowPct == null || roomToHighPct == null) return null
        if (!runupFromLowPct.isFinite() || !roomToHighPct.isFinite() || runupFromLowPct < 0.0 || roomToHighPct < 0.0) return null
        return minOf(expectedPct, maxOf(expectedPct - runupFromLowPct, roomToHighPct))
    }

    /** Pure: the current price's run from the hour's low and room to the hour's high (%), or null without a usable hour. */
    fun hourPosition8529(lowUsd: Double, highUsd: Double, priceUsd: Double): Pair<Double, Double>? {
        if (!(lowUsd > 0.0) || !(highUsd >= lowUsd) || !(priceUsd > 0.0) || !lowUsd.isFinite() || !highUsd.isFinite() || !priceUsd.isFinite()) return null
        val run = (priceUsd / lowUsd - 1.0) * 100.0
        val room = (highUsd / priceUsd - 1.0) * 100.0
        return run.coerceAtLeast(0.0) to room.coerceAtLeast(0.0)
    }

    private val SPENT_BASES_8529 = setOf("MANDATE", "RANGE", "H1", "RANGE_SPARSE")

    private fun expectedRaw8153(
        mandatePct: Double, rangePct: Double?, rangeDense: Boolean, h1Pct: Double?, strategyN: Int, strategyMeanPct: Double,
        strategySePct: Double, costPct: Double, freshLaunch: Boolean, signedHourPct: Double?, momentumEntry: Boolean,
        inLaunchWindow8418: Boolean,
    ): Move8153 {
        if (strategySupported8153(strategyN, strategyMeanPct, strategySePct) && costPct.isFinite() && costPct >= 0.0) {
            return Move8153(strategyMeanPct + costPct, "STRATEGY", false)
        }
        // A falling hour cannot become a +30% runner prediction just because its
        // absolute change or high-low range is large. Re-observe until a bounce/setup
        // has its own supported edge; DIP_HUNTER and other non-momentum lanes are exempt.
        // V5.0.8418 — never on hour direction alone inside a fresh launch's window (known age <= FRESH_AGE_MIN_8153): a
        // launch younger than its hour has no "falling hour", only its first pullback; the range / mandate read below judges it.
        if (momentumEntry && !inLaunchWindow8418 && signedHourPct != null && signedHourPct.isFinite() && signedHourPct < 0.0) {
            return Move8153(0.0, "FALLING_HOUR_UNPROVEN", true)
        }
        val range = rangePct?.takeIf { it.isFinite() && it >= 0.0 }
        val h1 = h1Pct?.takeIf { it.isFinite() && it > 0.0 }
        // V5.0.8418 §SPARSE_RANGE_IS_PRE_HISTORY — a sparse range (under DENSE_MIN_BARS over DENSE_MIN_SPAN) is the few minutes
        // the bot happened to watch, not the coin's hour: it defers for history at ANY age, never caps the move. 8416: an older
        // coin seen for two candles "moved 2%" and was refused EXECUTION_ECONOMICS / COIN_TOO_SLOW on that pre-history read.
        if (range != null && !rangeDense && h1 == null && (freshLaunch || SPARSE_DEFERS_AT_ANY_AGE_8418)) {
            return Move8153(NetEdge8030.expectedMove8035(mandatePct, range), "RANGE_SPARSE", true)
        }
        val realized = if (range != null && h1 != null) maxOf(range, h1) else range ?: h1
            ?: return Move8153(Double.NaN, "HISTORY_UNOBSERVED", true)
        val basis = when {
            realized >= mandatePct -> "MANDATE"
            range != null && (h1 == null || range >= h1) -> if (rangeDense) "RANGE" else "RANGE_SPARSE"
            else -> "H1"
        }
        return Move8153(NetEdge8030.expectedMove8035(mandatePct, realized), basis, false)
    }

    /** V5.0.8418 — a sparse range defers at any age (see [expected8153]); the pre-8418 rule deferred fresh launches only. */
    internal const val SPARSE_DEFERS_AT_ANY_AGE_8418 = true

    /** Pure: defer for history only when the sparse-range move would refuse (a move that clears needs no wait). */
    fun needsDefer8153(m: Move8153, costPct: Double, margin: Double = NetEdge8030.EDGE_MULT): Boolean =
        m.deferForHistory && !NetEdge8030.entryClears8030(m.expectedGrossPct, costPct, margin)

    /** Pure: the probe ticket a launch read is costed at — impact only on depth that was actually read. */
    fun probeSizeUsd8153(liquidityUsd: Double): Double = if (liquidityUsd.isFinite() && liquidityUsd > 0.0) 10.0 else 0.0

    /** Pure: an unread pool is UNKNOWN, never LOW. */
    fun liquidityRead8153(liquidityUsd: Double): String = if (liquidityUsd.isFinite() && liquidityUsd > 0.0) "KNOWN" else "UNKNOWN"
}

/** V5.0.8153 — watch-first releases a THIN cell on candidate-specific evidence (see [StrategyMove8153] doc). */
object StrategyWatch8153 {
    const val THIN_CELL_8153 = "WATCH_FIRST_7994_THIN_CELL"

    /**
     * Pure: the evidence that releases watch-first's [why], or null. Only a THIN cell is released — a cap band or cell
     * measured negative is a measurement and still refuses.
     */
    fun release8153(
        why: String,
        selectedTier: StrategyRank8153.Tier8153?,
        kindProven: Boolean,
        runnerForming: Boolean,
        shadowAudit: Boolean,
        specificAdmit: Boolean,
    ): String? {
        if (why != THIN_CELL_8153) return null
        return when {
            StrategyRank8153.evidenced8153(selectedTier) -> "STRATEGY_${selectedTier?.name}"
            kindProven -> "KIND_PROVEN"
            runnerForming -> "RUNNER_FORMING"
            shadowAudit -> "SHADOW_AUDIT"
            specificAdmit -> "ADMISSION_SPECIFIC_EDGE"
            else -> null
        }
    }
}
