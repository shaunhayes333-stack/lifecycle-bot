package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P0 — CANONICAL CAPITAL AUTHORITY.
 *
 * OPERATOR MANDATE (V5.0.6450 dump):
 *   PositionStateLedger OPEN=274 vs CanonicalPositions OPEN=42
 *   PaperAccount cash=2.2441 vs Canonical paperCash=2.26394
 *   capital conservation delta=-0.319310
 *
 *   "Establish ONE authoritative PositionId-based lifecycle ledger.
 *    All of these MUST derive from it: paper cash, reserved capital, open
 *    positions, partial positions, closed positions, realized PnL,
 *    unrealized PnL, fees, wallet equity, runner state, learner
 *    finalization, lane/tactic statistics."
 *
 * DESIGN
 * ──────
 * This is the single READ authority for capital state. Underlying stores
 * remain PaperAccountLedger6430 (cash + fees + realized) and
 * CanonicalPositionAuthority6441 (positions). This module *does not*
 * duplicate storage — it computes the 5 canonical surfaces:
 *
 *   CASH                — PaperCapitalAuthority6577.cashSol
 *   RESERVED            — sum of PENDING_ENTRY costSol
 *   OPEN_COST_BASIS     — canonical open cost, excluding reserved
 *   OPEN_MARKET_VALUE   — sum(currentMarkValue) via caller-supplied mark
 *   UNREALIZED_PNL      — OPEN_MARKET_VALUE - OPEN_COST_BASIS
 *   REALIZED_PNL        — PaperCapitalAuthority6577.realizedPnlSol
 *   FEES                — PaperCapitalAuthority6577.feesSol
 *   TOTAL_EQUITY        — CASH + RESERVED + OPEN_MARKET_VALUE
 *
 * The wallet UI MUST NOT display CASH as equity. Callers use snapshot().
 *
 * Invariant (checked every audit tick):
 *   startingCapital + realized - fees ≈ cash + reserved + openCostBasis
 */
object CanonicalCapitalAuthority6450 {

    data class Snapshot(
        val startingCashSol: Double,
        val cashSol: Double,
        val reservedSol: Double,
        val openCostBasisSol: Double,
        val openMarketValueSol: Double,
        val unrealizedPnlSol: Double,
        val realizedPnlSol: Double,
        val feesSol: Double,
        val totalEquitySol: Double,
        val conservationDeltaSol: Double,
        val staleMarkMints: Int = 0,
        val fallbackMarkMints: Int = 0,
        // V5.0.6508 §P0-3 — authoritative subset of openMarketValueSol
        // (fresh marks only; excludes stale/fallback held at basis).
        // Learners/rewards must consume this instead of openMarketValueSol
        // to avoid training on manufactured PnL from fallback marks.
        val authoritativeOpenMarketValueSol: Double = 0.0,
        val authoritativeEquitySol: Double = 0.0,
    )

    private val invariantChecks = AtomicLong(0L)
    private val invariantViolations = AtomicLong(0L)
    private val lastDeltaMicros = AtomicLong(0L) // *1e6, atomic-safe

    // V5.0.6456 §P0-#1 — install a real mark provider once at startup so
    // unrealized/equity/conservation reflect live prices. Consumers (bot
    // service / UI) call installMarkProvider() with a lambda that reads
    // the freshest available price for a mint from an in-memory cache.
    // Absent installation, we still fall back to costBasis to keep
    // unrealized as 0 (never a negative-100% phantom loss).
    private val markProviderRef = java.util.concurrent.atomic.AtomicReference<((String) -> Double)?>(null)
    private data class GoodMark6492(val wholeMintValueSol: Double, val observedAtMs: Long)
    private val lastGoodMark6492 = java.util.concurrent.ConcurrentHashMap<String, GoodMark6492>()

    fun installMarkProvider(provider: (String) -> Double) {
        markProviderRef.set(provider)
        try { PipelineHealthCollector.labelInc("CAPITAL_MARK_PROVIDER_INSTALLED_6456") } catch (_: Throwable) {}
    }

    /**
     * V5.0.7060 §8 — A PRICE, NOT A VALUE, AND A REASON TO BELIEVE IT.
     *
     * The Double provider above answers "what is this mint worth in SOL", and
     * it computed that from the DATA-LAYER pos.qtyToken while this file
     * compared the answer against the CANONICAL remainingCostBasisSol. Two
     * quantity sources, one ratio — which is precisely directive §8's "do not
     * mix". After an 87% partial the canonical basis is 13% of the original
     * while the data-layer quantity may still read whole, so the ratio inflates
     * by ~7.7x on arithmetic alone, and repeated rungs compound it. That is a
     * large part of HERO_OPENMV_PER_POSITION_QUARANTINE_6604 firing 584 times
     * in one session: the clamp was measuring its own unit mismatch.
     *
     * This provider returns SOL PER TOKEN instead, so the quantity is supplied
     * here, from the same canonical projection that supplies the cost basis.
     * One source, one scale, no ratio to invent.
     *
     * [MarkQuote7060.corroborated] is the second half. The 6604 clamp treats
     * any mark above 100x cost basis as corrupt, which silently includes every
     * genuine 100-bagger — a direct contradiction of V5.9.1358, and the exact
     * inverse of the inflation it was written to stop. Market cap can tell the
     * two apart: a real 100x carries a 100x market cap, a broken supply divisor
     * does not. CanonicalMarkResolution7059 already makes that judgement, so
     * the caller passes it through and the clamp finally has grounds.
     */
    data class MarkQuote7060(val solPerToken: Double, val corroborated: Boolean)

    private val markQuoteProviderRef =
        java.util.concurrent.atomic.AtomicReference<((String) -> MarkQuote7060?)?>(null)

    fun installMarkQuoteProvider7060(provider: (String) -> MarkQuote7060?) {
        markQuoteProviderRef.set(provider)
        try { PipelineHealthCollector.labelInc("CAPITAL_MARK_QUOTE_PROVIDER_INSTALLED_7060") } catch (_: Throwable) {}
    }

    /**
     * Compute the canonical snapshot. Caller supplies a mark provider that
     * returns current SOL market value for a mint (0.0 = mark unknown, use
     * costBasis fallback so unrealized reads as 0 rather than -100%).
     */
    fun snapshot(markProvider: (String) -> Double = markProviderRef.get() ?: { 0.0 }): Snapshot {
        // V5.0.6487 — PaperAccountLedger is the sole capital read authority.
        // Replay is parity diagnostics only and may never replace wallet surfaces.
        val startingCash = PaperCapitalAuthority6577.startingCashSol()
        val cash = PaperCapitalAuthority6577.cashSol()
        val realized = PaperCapitalAuthority6577.realizedPnlSol()
        val fees = PaperCapitalAuthority6577.feesSol()
        // V5.0.6489 — the mark provider returns WHOLE-MINT market value from
        // TokenState.position. Canonical storage may contain multiple economic lots
        // for one mint, so value each mint once; summing one provider value per lot
        // multiplied equity whenever historical same-mint lots coexisted.
        val activeMints = try { CanonicalPositionAuthority6441.activeMintProjections6490("paper") } catch (_: Throwable) { emptyList() }
        val reserved = 0.0 // no reserved event currently exists; remains explicit
        val openCost = PaperCapitalAuthority6577.openCostBasisSol()
        var staleMarkMints6492 = 0
        var fallbackMarkMints6492 = 0
        // V5.0.6508 §P0-3 — TRACK AUTHORITATIVE MARK VALUE SEPARATELY.
        // Operator mandate: fallback/stale marks MUST NOT manufacture
        // PnL for learning/reward. Sum only the fresh-marked slice so
        // downstream consumers can gate WR/EV/tactic training on
        // authoritativeOpenMv rather than the fallback-inflated total.
        var authoritativeOpenMv6508 = 0.0
        var authoritativeOpenCost6508 = 0.0
        val activeMintSet6492 = activeMints.map { it.mint }.toSet()
        lastGoodMark6492.keys.removeIf { it !in activeMintSet6492 }
        val markedValue6492 = activeMints.sumOf { aggregate ->
            // V5.0.7060 §8 — prefer the per-token quote and supply the quantity
            // from THIS projection, so market value and cost basis are derived
            // from one canonical row. Falls back to the whole-mint provider
            // when no quote provider is installed, so nothing depends on
            // install order.
            val quote7060 = try { markQuoteProviderRef.get()?.invoke(aggregate.mint) } catch (_: Throwable) { null }
            val corroborated7060 = quote7060?.corroborated == true
            val fresh = if (quote7060 != null && quote7060.solPerToken.isFinite() && quote7060.solPerToken > 0.0) {
                // V5.0.7258 — old cross-asset rows were opened with the
                // synthetic quantity 1.000. Multiplying that by a stock's
                // current unit price turned a ~$1 allocation into a full
                // $300-$1,700 share and inflated the hero to ~$9.7k. For
                // non-Solana spot assets, quantity is not needed to value the
                // exposure: remainingCost * currentUsd / entryUsd is exact and
                // also repairs those already-durable legacy rows immediately.
                val v7060 = if (aggregate.assetClass in setOf(
                        AssetClass.STOCK, AssetClass.FOREX, AssetClass.COMMODITY,
                        AssetClass.METAL, AssetClass.CRYPTO_ALT,
                    ) && aggregate.costBasisPerEntryUsd > 0.0
                ) {
                    val solUsd7258 = try {
                        com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf {
                            it.isFinite() && it in 20.0..5_000.0
                        } ?: 0.0
                    } catch (_: Throwable) { 0.0 }
                    val currentPriceUsd7258 = quote7060.solPerToken * solUsd7258
                    aggregate.costBasisPerEntryUsd * currentPriceUsd7258
                } else if (aggregate.assetClass == AssetClass.PERPS) {
                    // Leveraged PnL needs its typed sandbox receipt; unit-price
                    // multiplication is not a valid substitute. Hold at basis.
                    aggregate.remainingCostBasisSol
                } else {
                    val qtyTokens7060 = try {
                        java.math.BigDecimal(aggregate.remainingQtyRaw)
                            .movePointLeft(aggregate.quantityScale.coerceIn(0, 18))
                            .toDouble()
                    } catch (_: Throwable) { 0.0 }
                    quote7060.solPerToken * qtyTokens7060
                }
                if (v7060.isFinite() && v7060 > 0.0) v7060 else 0.0
            } else {
                // The legacy whole-mint provider owns its own quantity. That
                // quantity is exactly the contaminated `1.000` cross-asset
                // sentinel fixed by 7258, so it cannot safely value an old
                // stock/FX/metal/alt row. Until a per-unit quote arrives,
                // leave it unpriced and let the cost-basis fallback below
                // preserve capital without manufacturing profit.
                if (aggregate.assetClass != AssetClass.SOLANA_TOKEN) 0.0
                else try { markProvider(aggregate.mint) } catch (_: Throwable) { 0.0 }
            }
            // V5.0.6604 §PER_POSITION_MARK_QUARANTINE (operator P1 fix).
            //   The 6602 aggregate clamp masked the inflation but never
            //   located WHICH position's mark was corrupt. Add a per-mint
            //   forensic quarantine: if a single fresh mark exceeds the
            //   position's remainingCostBasis by more than SANITY_MULT_6602
            //   (100×), treat that mint as fallback (hold at cost basis),
            //   emit HERO_OPENMV_PER_POSITION_QUARANTINE_6604 so operator
            //   can see the mint / raw mark / ratio, and count it as a
            //   fallback mark rather than authoritative. Rotation-safe:
            //   the next tick reads the mark again — if it comes back
            //   sane, position resumes authoritative marking.
            val costBasis6604 = aggregate.remainingCostBasisSol
            val SANITY_MULT_6604 = 100.0
            val perPositionInflated6604 = fresh.isFinite() && fresh > 0.0 &&
                costBasis6604 > 0.0 && fresh > costBasis6604 * SANITY_MULT_6604
            // V5.0.7060 §RUNNER_CAPTURE_IS_NOT_NEGOTIABLE (V5.9.1358).
            //
            // This clamp reads "above 100x cost basis" as "corrupt mark". That
            // is also the definition of a 100-bagger, which is the single
            // outcome this bot exists to capture — and when one arrived, the
            // hero showed it at cost basis, i.e. +0%. The clamp could not tell
            // a real runner from a broken supply divisor because it only ever
            // looked at one number.
            //
            // Market cap tells them apart: a genuine 100x carries a 100x market
            // cap, a corrupt price does not. CanonicalMarkResolution7059 makes
            // that call upstream and the quote provider carries the verdict, so
            // a corroborated mark is no longer quarantined at any magnitude.
            // An uncorroborated one still is — nothing was loosened, the
            // decision was simply given evidence it never had.
            if (perPositionInflated6604 && corroborated7060) {
                try {
                    PipelineHealthCollector.labelInc("HERO_RUNNER_CORROBORATED_BY_MCAP_7060")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "HERO_RUNNER_CORROBORATED_BY_MCAP_7060",
                        "mint=${aggregate.mint.take(10)} costBasis=${"%.6f".format(costBasis6604)} " +
                            "markSol=${"%.6f".format(fresh)} ratio=${"%.1f".format(fresh / costBasis6604)}x " +
                            "action=real_runner_market_cap_agrees_do_not_clamp",
                    )
                } catch (_: Throwable) {}
            }
            if (perPositionInflated6604 && !corroborated7060) {
                try {
                    // V5.0.7098 §UNKNOWN_IS_NOT_REFUTED — 5.0.7091 reports this
                    // counter 468 times and the number cannot be investigated,
                    // because `!corroborated7060` collapses two unrelated facts:
                    //
                    //   REFUTED    a quote arrived and the market cap did NOT
                    //              agree with it — real evidence of a corrupt
                    //              mark, and the clamp is doing its job.
                    //   UNEVALUATED  no quote arrived at all. The installed
                    //              7060 provider returns null on six separate
                    //              conditions, several of which say nothing
                    //              whatever about this mark's truth (position
                    //              not runtime-open-eligible, 6496 not
                    //              authoritative, SOL/USD outside its sanity
                    //              band). Corroboration was never asked.
                    //
                    // In the UNEVALUATED case `fresh` also came from a DIFFERENT
                    // source — the whole-mint markProvider fallback at the top of
                    // this block — so the clamp is judging one source's number
                    // with a verdict about a mark it never received. That is the
                    // same shape as every other defect in this codebase where two
                    // facts share one field.
                    //
                    // The clamp itself is unchanged in both cases: holding at cost
                    // basis is the conservative choice for HERO accounting and a
                    // 4570x uncorroborated mark must not enter openMv. What
                    // changes is that the 468 now says which of the two it is, so
                    // a real runner being held at +0% is distinguishable from a
                    // corrupt mark being contained.
                    val quoteEvaluated7098 = quote7060 != null
                    PipelineHealthCollector.labelInc("HERO_OPENMV_PER_POSITION_QUARANTINE_6604")
                    PipelineHealthCollector.labelInc(
                        if (quoteEvaluated7098) "HERO_OPENMV_QUARANTINE_MCAP_REFUTED_7098"
                        else "HERO_OPENMV_QUARANTINE_UNEVALUATED_7098"
                    )
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "HERO_OPENMV_PER_POSITION_QUARANTINE_6604",
                        "mint=${aggregate.mint.take(10)} costBasis=${"%.6f".format(costBasis6604)} " +
                            "rawMark=${"%.6f".format(fresh)} ratio=${"%.1f".format(fresh / costBasis6604)}x " +
                            "corroboration=${if (quoteEvaluated7098) "REFUTED" else "UNEVALUATED_NO_QUOTE"} " +
                            "markSource=${if (quoteEvaluated7098) "quote_provider_7060" else "whole_mint_markProvider"} " +
                            "action=treat_as_fallback_mark",
                    )
                } catch (_: Throwable) {}
                fallbackMarkMints6492++
                return@sumOf costBasis6604
            }
            when {
                fresh.isFinite() && fresh > 0.0 -> {
                    lastGoodMark6492[aggregate.mint] = GoodMark6492(fresh, System.currentTimeMillis())
                    authoritativeOpenMv6508 += fresh
                    authoritativeOpenCost6508 += aggregate.remainingCostBasisSol
                    fresh
                }
                lastGoodMark6492[aggregate.mint] != null -> {
                    staleMarkMints6492++
                    try { PipelineHealthCollector.labelInc("PAPER_MARK_STALE_LAST_GOOD_6508") } catch (_: Throwable) {}
                    // V5.0.7160 §A STALE MARK MAY NOT MANUFACTURE PROFIT.
                    //
                    // Forty lines below, this same function states the rule it
                    // is meant to enforce:
                    //
                    //   "Only fresh, authoritative marks may produce unrealized
                    //    profit. Stale/fallback positions remain UNPRICED COST
                    //    and contribute zero to growth, compounding, sizing, or
                    //    learning rewards."
                    //
                    // This branch returned the last-good mark UNBOUNDED. It is
                    // excluded from authoritativeOpenMv6508, so it never shows
                    // up in `unrealized` — but it flows into markedValue6492,
                    // then openMv, then equity = cash + reserved + openMv. So a
                    // position last seen at +500% carries that +500% in equity
                    // for as long as it stays stale, invisible to the very
                    // figure meant to report unrealized gain.
                    //
                    // Operator's 5.0.7155, and the arithmetic that exposed it:
                    //
                    //   openCost=4.3455  openMV=10.5279  unrealized=+0.1769
                    //   staleMarks=5  fallbackMarks=8  worstDivergence=107x
                    //
                    // openMV - openCost = 6.18 SOL of apparent gain. `unrealized`
                    // accounts for 0.18 of it. The remaining ~6.0 is stale
                    // last-good marks inflating equity, against this file's own
                    // doctrine, with a mark resolver reporting a worst-case
                    // divergence of 107x.
                    //
                    // That direction is the dangerous one for a compounding
                    // system. Equity feeds the hero, the runner health gate and
                    // the growth ladder; overstate it and the bot sizes up on
                    // money it does not have. "Live money printer, 0 exceptions"
                    // starts with the printer knowing what it actually holds.
                    //
                    // So a stale mark is capped at cost: it can no longer create
                    // profit, and a stale mark BELOW cost still passes through
                    // at its loss, because hiding a loss is the same defect
                    // pointed the other way. Strictly more conservative — this
                    // can only ever lower equity, never raise it.
                    val lastGood7160 = lastGoodMark6492.getValue(aggregate.mint).wholeMintValueSol
                    val cost7160 = aggregate.remainingCostBasisSol
                    if (lastGood7160 > cost7160 && cost7160 > 0.0) {
                        try {
                            PipelineHealthCollector.labelInc("STALE_MARK_PROFIT_WITHHELD_7160")
                            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                                "STALE_MARK_PROFIT_WITHHELD_7160",
                                "mint=${aggregate.mint.take(10)} lastGood=${"%.6f".format(lastGood7160)} " +
                                    "cost=${"%.6f".format(cost7160)} " +
                                    "withheldSol=${"%.6f".format(lastGood7160 - cost7160)} " +
                                    "action=hold_at_cost_stale_may_not_create_profit",
                            )
                        } catch (_: Throwable) {}
                        cost7160
                    } else {
                        lastGood7160
                    }
                }
                else -> {
                    fallbackMarkMints6492++
                    // Position held at entry basis, UNPRICED authoritatively.
                    try { PipelineHealthCollector.labelInc("PAPER_MARK_UNPRICED_6508") } catch (_: Throwable) {}
                    aggregate.remainingCostBasisSol
                }
            }
        }
        // A non-zero paper open cost with no paper position projection is an
        // explicit lifecycle mismatch, not a real -100% mark. Keep equity at
        // basis while the reconciler restores carry positions and surface it.
        val openMvRaw6602 = if (activeMints.isEmpty() && openCost > 0.0) {
            fallbackMarkMints6492++
            try { PipelineHealthCollector.labelInc("CAPITAL_MARK_FALLBACK_NO_CANON_POSITION_6492") } catch (_: Throwable) {}
            openCost
        } else markedValue6492
        // V5.0.6602 §HERO_OPENMV_SANITY_CLAMP — operator directive Feb 2026:
        // hero was showing $9,595 equity on a wallet with journal +$33.57
        // realized P&L on ~0.5 SOL of cost basis (~200× inflation). Trail
        // stops fire at peak +25% so no legitimate paper position could
        // sustain 100×+ market value vs cost basis before exiting. When
        // openMv exceeds openCost by more than a sane meme-run ceiling
        // (100×), the mark provider is misreporting — clamp to openCost and
        // emit HERO_OPENMV_SANITY_CLAMP_6602 so operator can see the raw
        // divergence in a pipeline dump. UI shows honest cost-basis equity
        // rather than a fantasy $9k figure.
        val SANITY_MULT_6602 = 100.0
        val openMv = if (openCost > 0.0 && openMvRaw6602 > openCost * SANITY_MULT_6602) {
            try {
                PipelineHealthCollector.labelInc("HERO_OPENMV_SANITY_CLAMP_6602")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "HERO_OPENMV_SANITY_CLAMP_6602",
                    "openCost=${"%.4f".format(openCost)} openMvRaw=${"%.4f".format(openMvRaw6602)} " +
                        "ratio=${"%.1f".format(openMvRaw6602 / openCost)}x mints=${activeMints.size} " +
                        "action=clamp_to_costBasis",
                )
            } catch (_: Throwable) {}
            openCost
        } else openMvRaw6602
        if (staleMarkMints6492 > 0) try { PipelineHealthCollector.labelInc("CAPITAL_STALE_LAST_GOOD_MARK_6492") } catch (_: Throwable) {}
        // Only fresh, authoritative marks may produce unrealized profit.
        // Stale/fallback positions remain UNPRICED COST and contribute zero
        // to growth, compounding, sizing, or learning rewards.
        val unrealized = authoritativeOpenMv6508 - authoritativeOpenCost6508
        // V5.0.7294 — the paper treasury is owned (equity) but not tradeable
        // (not cash); it closes the identity alongside reserved and open cost.
        val treasury7294 = PaperCapitalAuthority6577.treasurySol7294()
        val equity = cash + reserved + openMv + treasury7294
        val expected = startingCash + realized - fees
        val actual = cash + reserved + openCost + treasury7294
        return Snapshot(
            startingCashSol = startingCash,
            cashSol = cash,
            reservedSol = reserved,
            openCostBasisSol = openCost,
            openMarketValueSol = openMv,
            unrealizedPnlSol = unrealized,
            realizedPnlSol = realized,
            feesSol = fees,
            totalEquitySol = equity,
            conservationDeltaSol = actual - expected,
            staleMarkMints = staleMarkMints6492,
            fallbackMarkMints = fallbackMarkMints6492,
            authoritativeOpenMarketValueSol = authoritativeOpenMv6508,
            // V5.0.6508a — authoritative equity: cash + reserved +
            // AUTHORITATIVE openMV only (excludes stale/fallback marks).
            // Main UI hero uses this to avoid the +28400% start
            // impossibility that stale entry-basis marks manufactured.
            authoritativeEquitySol = cash + reserved + authoritativeOpenMv6508 + treasury7294,
        )
    }

    fun assertInvariant(toleranceSol: Double = 1e-4): Double {
        invariantChecks.incrementAndGet()
        val s = snapshot()
        lastDeltaMicros.set((s.conservationDeltaSol * 1_000_000.0).toLong())
        if (kotlin.math.abs(s.conservationDeltaSol) > toleranceSol) {
            invariantViolations.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_CAPITAL_INVARIANT_VIOLATION_6450",
                    "delta=${"%.6f".format(s.conservationDeltaSol)} " +
                        "startCash=${"%.6f".format(s.startingCashSol)} " +
                        "cash=${"%.6f".format(s.cashSol)} " +
                        "reserved=${"%.6f".format(s.reservedSol)} " +
                        "openCost=${"%.6f".format(s.openCostBasisSol)} " +
                        "realized=${"%.6f".format(s.realizedPnlSol)} " +
                        "fees=${"%.6f".format(s.feesSol)} " +
                        "equity=${"%.6f".format(s.totalEquitySol)}",
                )
                PipelineHealthCollector.labelInc("CANONICAL_CAPITAL_INVARIANT_VIOLATION_6450")
            } catch (_: Throwable) {}
        }
        return s.conservationDeltaSol
    }

    fun statusLine(): String {
        val s = snapshot()
        return "cash=${"%.4f".format(s.cashSol)} reserved=${"%.4f".format(s.reservedSol)} " +
            "openMV=${"%.4f".format(s.openMarketValueSol)} unrealized=${"%.4f".format(s.unrealizedPnlSol)} " +
            "realized=${"%.4f".format(s.realizedPnlSol)} fees=${"%.4f".format(s.feesSol)} " +
            "equity=${"%.4f".format(s.totalEquitySol)} delta=${"%.6f".format(s.conservationDeltaSol)} " +
            "staleMarks=${s.staleMarkMints} fallbackMarks=${s.fallbackMarkMints} " +
            "checks=${invariantChecks.get()} violations=${invariantViolations.get()}"
    }
}
