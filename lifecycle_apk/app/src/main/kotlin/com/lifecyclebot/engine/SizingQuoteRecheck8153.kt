package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8153 §PRE_BROADCAST_ECONOMICS — the sealed economics are re-proven on the quote that will execute.
 *
 * FDG seals a live size on admission evidence (LiveRiskPolicy7807: expected setup return, round trip at the size,
 * plan first target, modelled pool impact, the mark at the decision). The executor then quoted, built, simulated and
 * broadcast without ever setting the FINAL quote against that evidence: a pool that thinned, a price that ran between
 * seal and broadcast, or an impact far above what the size was costed at still executed on the stale admission.
 *
 * Now, immediately before the signed buy is sent (Executor.prepareJupiterBuy8090, after simulation and the sign
 * delay, on the quote already in hand — no extra network round trip), [recheck8153] refuses with a named reason when:
 *  - the quote spends more lamports than the ticket (a size inflated after the seal);
 *  - the quote's impact is beyond the sealed impact budget;
 *  - the fresh round trip and the move already made since the seal leave the setup's expected return <= 0, or (with
 *    no measured expectation) leave the plan's remaining move inside LiveRiskPolicy7807's cost-pass ratio.
 * No sealed record, a stale one, or a ticket that is not the sealed notional: no opinion (the ticket gates own those).
 */
object SizingQuoteRecheck8153 {

    /** The economics a live size was sealed on (per mint; the last FDG decision for it, bound to its sealed size). */
    data class SealedEconomics8153(
        val sizeSol: Double,
        val expectedGrossPct: Double,
        val firstTargetPct: Double,
        val costPct: Double,
        val impactOneWayPct: Double,
        val priceUsd: Double,
        val atMs: Long,
        val candidateVersion8198: Long = 0L,
    )

    const val SEAL_TTL_MS_8153 = 10L * 60_000L
    /** The sealed size was capped at this one-way modelled impact (LiveRiskPolicy7807 LIQ_MAX_ONE_WAY_IMPACT_PCT_7807). */
    private const val MAX_ONE_WAY_IMPACT_PCT_8153 = 3.0
    /** A fresh impact may reach twice the sealed one, or the sealed one plus this many points, whichever is larger. */
    private const val IMPACT_HEADROOM_PTS_8153 = 1.5
    /** Price ratios beyond this are not one market moving between seal and broadcast (feed / unit change): no drift read. */
    private const val MAX_COMPARABLE_PRICE_RATIO_8153 = 3.0

    // ── pure ──

    fun lamports8153(sol: Double): Long = if (sol.isFinite() && sol > 0.0) kotlin.math.round(sol * 1_000_000_000.0).toLong() else 0L

    /** Pure: the one-way impact budget a sealed size carries. */
    fun impactBudgetPct8153(sealedImpactOneWayPct: Double): Double {
        val s = if (sealedImpactOneWayPct.isFinite() && sealedImpactOneWayPct > 0.0) sealedImpactOneWayPct else 0.0
        return minOf(MAX_ONE_WAY_IMPACT_PCT_8153, maxOf(2.0 * s, s + IMPACT_HEADROOM_PTS_8153))
    }

    /** Pure: the adverse (upward) move of a buy's price since the seal, percent; 0 when not comparable. */
    fun adverseDriftPct8153(sealPriceUsd: Double, nowPriceUsd: Double): Double {
        if (!(sealPriceUsd > 0.0) || !(nowPriceUsd > 0.0) || !sealPriceUsd.isFinite() || !nowPriceUsd.isFinite()) return 0.0
        val r = nowPriceUsd / sealPriceUsd
        return if (r > 1.0 && r <= MAX_COMPARABLE_PRICE_RATIO_8153) (r - 1.0) * 100.0 else 0.0
    }

    /** Final buy quote price, with observed decimals only; a cheaper quote never cancels a newer adverse mark. */
    internal fun quotePrice8167(inLamports: Long, outRaw: Long, decimals: Int?, solUsd: Double, markUsd: Double): Double {
        val mark = markUsd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        if (inLamports <= 0L || outRaw <= 0L || decimals == null || decimals !in 0..18 || !solUsd.isFinite() || solUsd <= 0.0) return mark
        val quote = (inLamports.toDouble() / 1_000_000_000.0) * solUsd * Math.pow(10.0, decimals.toDouble()) / outRaw.toDouble()
        return if (quote.isFinite() && quote > 0.0) maxOf(mark, quote) else mark
    }

    /** Executor's final SOL-to-token quote: no price inferred from the cached mark or guessed decimal scale. */
    fun preBroadcastQuoteRefusal8167(ts: com.lifecyclebot.data.TokenState, executedSol: Double, quote: com.lifecyclebot.network.SwapQuote, solUsd: Double = WalletManager.freshSolPrice8163(), intent8198: ExecutableOpenGate.ExecutionIntent? = null): String? {
        if (quote.inputMint != com.lifecyclebot.network.JupiterApi.SOL_MINT || quote.outputMint != ts.mint) return "PRE_BROADCAST_QUOTE_IDENTITY_MISMATCH_8198"
        if (quote.inAmount <= 0L || quote.outAmount <= 0L) return "PRE_BROADCAST_QUOTE_AMOUNT_INVALID_8198"
        val price = if (quote.inputMint == com.lifecyclebot.network.JupiterApi.SOL_MINT && quote.outputMint == ts.mint)
            quotePrice8167(quote.inAmount, quote.outAmount, ts.tokenMap.decimals, solUsd, ts.lastPrice)
        else ts.lastPrice
        return preBroadcastRefusal8153(ts.mint, executedSol, quote.inAmount, quote.priceImpactPct, ts.lastPrice, price, intent8198)
    }

    /**
     * Pure: the refusal for executing [executedSol] on a quote spending [quotedInLamports] at [freshImpactPct] (the
     * app's percent reading; 0 / non-finite = not surfaced) with the token now at [priceNowUsd], or null.
     */
    fun recheck8153(
        seal: SealedEconomics8153?, executedSol: Double, quotedInLamports: Long,
        freshImpactPct: Double, priceNowUsd: Double, nowMs: Long, executionPriceUsd: Double = Double.NaN,
    ): String? {
        val ticket = lamports8153(executedSol)
        if (ticket > 0L && quotedInLamports > ticket + 1L) return "PRE_BROADCAST_SIZE_INFLATED_AFTER_SEAL_8153"
        if (seal == null || nowMs - seal.atMs !in 0L..SEAL_TTL_MS_8153) return null
        val sealed = lamports8153(seal.sizeSol)
        if (sealed <= 0L || kotlin.math.abs(ticket - sealed) > 1L) return null
        // An upward discontinuity is not zero drift. Require a new admission instead of buying on incomparable evidence.
        if (seal.priceUsd.isFinite() && seal.priceUsd > 0.0 && priceNowUsd.isFinite() &&
            (priceNowUsd / seal.priceUsd > MAX_COMPARABLE_PRICE_RATIO_8153 ||
                executionPriceUsd / seal.priceUsd > MAX_COMPARABLE_PRICE_RATIO_8153)) return "PRE_BROADCAST_PRICE_DISCONTINUITY_8167"
        val impact = if (freshImpactPct.isFinite() && freshImpactPct > 0.0) freshImpactPct else Double.NaN
        // Unknown depth at the seal (NaN): the size was never depth-capped and its cost carried no impact, so the budget
        // is the generic depth envelope and the whole fresh impact is extra round trip.
        val sealedKnown = seal.impactOneWayPct.isFinite() && seal.impactOneWayPct >= 0.0
        val sealedImpact = if (sealedKnown) seal.impactOneWayPct else 0.0
        val budget = if (sealedKnown) impactBudgetPct8153(sealedImpact) else MAX_ONE_WAY_IMPACT_PCT_8153
        if (impact.isFinite() && impact > budget + 1e-9) return "PRE_BROADCAST_IMPACT_BEYOND_SEALED_BUDGET_8153"
        // Only a worsening counts: a cheaper fill is not credited against the sealed evidence.
        val extraRoundTrip = if (impact.isFinite()) 2.0 * (impact - sealedImpact).coerceAtLeast(0.0) else 0.0
        val costFresh = (if (seal.costPct.isFinite()) seal.costPct.coerceAtLeast(0.0) else 0.0) + extraRoundTrip
        val drift = adverseDriftPct8153(seal.priceUsd, priceNowUsd)
        // Jupiter outAmount already includes AMM/platform fees and pool impact. Do not charge that buy drag
        // again as both drift and modelled round-trip cost. Quote drag is a lower bound on total drag; the
        // full-cost-plus-market-move check still applies. Without a sell quote, do not invent a cost split.
        val quoteDrag = adverseDriftPct8153(seal.priceUsd, executionPriceUsd)
        val effectiveCost = maxOf(costFresh, (quoteDrag - drift).coerceAtLeast(0.0))
        if (seal.expectedGrossPct.isFinite()) {
            if (seal.expectedGrossPct - drift - effectiveCost <= 0.0) return "PRE_BROADCAST_NET_EV_NONPOSITIVE_8153"
        } else if (seal.firstTargetPct.isFinite() && seal.firstTargetPct > 0.0 && effectiveCost > 0.0) {
            if (com.lifecyclebot.engine.truth.LiveRiskPolicy7807.costVerdict(seal.firstTargetPct - drift, effectiveCost) ==
                com.lifecyclebot.engine.truth.LiveRiskPolicy7807.CostVerdict.PASS) return "PRE_BROADCAST_COST_CONSUMES_MOVE_8153"
        }
        return null
    }

    // ── runtime ──

    private val pending = ConcurrentHashMap<String, SealedEconomics8153>()
    private val sealed = ConcurrentHashMap<String, SealedEconomics8153>()
    private val checks = AtomicLong(0)
    private val refusals = ConcurrentHashMap<String, AtomicLong>()

    /** LiveRiskPolicy7807.record (FDG, open): the economics this decision was made on. */
    fun noteDecision8153(mint: String, econ: SealedEconomics8153) {
        if (mint.isBlank()) return
        pending[mint] = econ
        if (pending.size > 2_000) pending.entries.removeIf { econ.atMs - it.value.atMs > SEAL_TTL_MS_8153 }
    }

    /** WalletCapacitySeal7868.record: bind the decision's economics to the exact sealed size. */
    fun bindSeal8153(mint: String, sizeSol: Double, nowMs: Long = System.currentTimeMillis(), candidateVersion8198: Long = 0L) {
        val p = pending.remove(mint) ?: return
        if (nowMs - p.atMs !in 0L..SEAL_TTL_MS_8153 || !sizeSol.isFinite() || sizeSol <= 0.0) return
        sealed[mint] = p.copy(sizeSol = sizeSol, atMs = nowMs, candidateVersion8198 = candidateVersion8198)
        if (sealed.size > 2_000) sealed.entries.removeIf { nowMs - it.value.atMs > SEAL_TTL_MS_8153 }
    }

    /** Executor, immediately before broadcast: the named refusal, or null to send. Fail-open on an internal fault. */
    fun preBroadcastRefusal8153(mint: String, executedSol: Double, quotedInLamports: Long, freshImpactPct: Double, priceNowUsd: Double, executionPriceUsd: Double = Double.NaN, intent8198: ExecutableOpenGate.ExecutionIntent? = null): String? {
        val observed8198 = sealed[mint]
        if (intent8198 != null && observed8198 != null && observed8198.candidateVersion8198 != intent8198.candidateVersion) return "PRE_BROADCAST_SEAL_GENERATION_CHANGED_8198"
        val r = try {
            checks.incrementAndGet()
            recheck8153(observed8198, executedSol, quotedInLamports, freshImpactPct, priceNowUsd, System.currentTimeMillis(), executionPriceUsd)
        } catch (_: Throwable) { null } ?: return null
        refusals.computeIfAbsent(r) { AtomicLong(0) }.incrementAndGet()
        try {
            val s = sealed[mint]
            PipelineHealthCollector.labelInc(r)
            ForensicLogger.lifecycle("PRE_BROADCAST_ECONOMICS_REFUSED_8153",
                "mint=${mint.take(10)} reason=$r sol=${"%.6f".format(executedSol)} quotedLamports=$quotedInLamports impact=${"%.3f".format(freshImpactPct)} " +
                    "sealImpact=${s?.impactOneWayPct?.let { "%.3f".format(it) } ?: "-"} sealCost=${s?.costPct?.let { "%.2f".format(it) } ?: "-"} " +
                    "sealGross=${s?.expectedGrossPct?.let { "%.2f".format(it) } ?: "-"} sealTarget=${s?.firstTargetPct?.let { "%.1f".format(it) } ?: "-"} " +
                    "drift=${"%.2f".format(adverseDriftPct8153(s?.priceUsd ?: 0.0, priceNowUsd))}")
        } catch (_: Throwable) {}
        if (observed8198 != null) sealed.remove(mint, observed8198)
        if (intent8198 != null) WalletCapacitySeal7868.release(mint, attemptId = intent8198.attemptId)
        return r
    }

    fun statusLine8153(): String =
        "preBroadcastChecks=${checks.get()} refused=[${refusals.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }}] sealed=${sealed.size}"
}
