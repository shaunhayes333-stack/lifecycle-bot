package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8418 §ENTRY_CONTINUITY — at the moment the live buy is built, the order must be the EXACT approved decision.
 *
 * Compared against the sealed ticket (ExecutableOpenGate.ExecutionIntent) that FDG approved:
 *  - STRATEGY: the ticket in hand is still the live ticket for its attempt (same attempt, candidate generation, lane,
 *    BUY decision) — a re-seal between approval and build is a different decision;
 *  - SIZE: the lamports the order spends equal the sealed size (a planner clamp or an inflation is a mismatch);
 *  - QUOTE: the executable quote is for this mint; when the quote was refreshed after approval (slippage requote) the
 *    refreshed one keeps the approved quote's router, its out amount inside the approved slippage, and its impact
 *    within IMPACT_HEADROOM_PTS of the approved impact;
 *  - PRICE: the execution price stays inside the chase bound (CHASE_BOUND_FRAC above the approved mark).
 * Any mismatch refuses ENTRY_CONTINUITY_8418_<what>. Approved and executed values are recorded side by side per attempt
 * (and per mint for the position the attempt opens).
 */
object EntryContinuity8418 {
    const val PREFIX = "ENTRY_CONTINUITY_8418_"
    /** Same band SealedEntryContinuity7863 revalidates a sealed mark with. */
    private const val CHASE_BOUND_FRAC_8418 = SealedEntryContinuity7863.REVALIDATE_MAX_MOVE_FRAC_7868
    private const val IMPACT_HEADROOM_PTS_8418 = 1.5

    data class Quote8418(val inLamports: Long, val outRaw: Long, val impactPct: Double, val router: String, val outputMint: String, val slippageBps: Int)

    data class Record8418(
        val attemptId: String, val mint: String, val lane: String,
        val approvedSizeSol: Double, val executedSizeSol: Double, val executedInLamports: Long,
        val approvedMarkUsd: Double, val executedPriceUsd: Double,
        val approvedRouter: String, val executedRouter: String,
        val approvedOutRaw: Long, val executedOutRaw: Long,
        val approvedImpactPct: Double, val executedImpactPct: Double,
        val refusal: String?, val atMs: Long,
    )

    private fun lamports8418(sol: Double): Long = if (sol.isFinite() && sol > 0.0) kotlin.math.round(sol * 1_000_000_000.0).toLong() else 0L

    /** Pure: the order spends the sealed amount (1 lamport or 0.05%, whichever is larger, absorbs rounding only). */
    fun sizeMatches8418(sealedSol: Double, spentLamports: Long): Boolean {
        val sealed = lamports8418(sealedSol)
        if (sealed <= 0L || spentLamports <= 0L) return false
        return kotlin.math.abs(spentLamports - sealed) <= maxOf(1L, sealed / 2_000L)
    }

    /** Pure: the named mismatch between the approved ticket and the order being built, or null when continuous. */
    fun refusal8418(
        approved: ExecutableOpenGate.ExecutionIntent?,
        current: ExecutableOpenGate.ExecutionIntent?,
        mint: String,
        spentLamports: Long,
        executionPriceUsd: Double,
        approvedQuote: Quote8418?,
        executedQuote: Quote8418?,
    ): String? {
        if (approved == null) return PREFIX + "NO_APPROVAL"
        if (approved.mint != mint || (executedQuote != null && executedQuote.outputMint != mint)) return PREFIX + "MINT"
        if (current == null) return PREFIX + "APPROVAL_GONE"
        if (approved.finalDecision6613 != ExecutableOpenGate.CanonicalFinalDecision6613.BUY || approved.fdgVerdict != "BUY" ||
            !approved.fdgAllowed || current.attemptId != approved.attemptId ||
            current.candidateVersion != approved.candidateVersion ||
            LaneAlias.normalize(current.canonicalLane) != LaneAlias.normalize(approved.canonicalLane) ||
            current.resolvedSize != approved.resolvedSize) return PREFIX + "STRATEGY"
        if (!sizeMatches8418(approved.resolvedSize, spentLamports)) return PREFIX + "SIZE"
        if (executedQuote != null && !sizeMatches8418(approved.resolvedSize, executedQuote.inLamports)) return PREFIX + "SIZE"
        if (approvedQuote != null && executedQuote != null && approvedQuote != executedQuote) {
            if (!approvedQuote.router.equals(executedQuote.router, true)) return PREFIX + "ROUTE"
            val tol = approvedQuote.slippageBps.coerceIn(0, 10_000).toDouble() / 10_000.0
            if (approvedQuote.outRaw > 0L && executedQuote.outRaw < approvedQuote.outRaw * (1.0 - tol)) return PREFIX + "OUT_AMOUNT"
            val ai = if (approvedQuote.impactPct.isFinite()) approvedQuote.impactPct.coerceAtLeast(0.0) else 0.0
            if (executedQuote.impactPct.isFinite() && executedQuote.impactPct > ai + IMPACT_HEADROOM_PTS_8418) return PREFIX + "IMPACT"
        }
        val mark = approved.executableMarkPriceUsd6613
        if (!mark.isFinite() || mark <= 0.0) return PREFIX + "PRICE_UNAPPROVED"
        if (executionPriceUsd.isFinite() && executionPriceUsd > mark * (1.0 + CHASE_BOUND_FRAC_8418)) return PREFIX + "PRICE"
        return null
    }

    private val byAttempt = ConcurrentHashMap<String, Record8418>()

    /**
     * The approved and executed values side by side, for the entry snapshot that binds to the canonical position
     * (Executor.pendingEntrySnapshot7871 -> LivePendingAttempt7868 -> the position's EntryStrategySnapshot6450). "" if none.
     */
    fun summary8418(attemptId: String): String {
        val r = byAttempt[attemptId] ?: return ""
        return ";continuity8418=approved[sol=${r.approvedSizeSol},mark=${r.approvedMarkUsd},router=${r.approvedRouter.ifBlank { "-" }}," +
            "out=${r.approvedOutRaw},impact=${r.approvedImpactPct}]executed[sol=${r.executedSizeSol},lamports=${r.executedInLamports}," +
            "px=${r.executedPriceUsd},router=${r.executedRouter.ifBlank { "-" }},out=${r.executedOutRaw},impact=${r.executedImpactPct}]" +
            "verdict=${r.refusal ?: "CONTINUOUS"}"
    }

    private fun quoteOf8418(q: com.lifecyclebot.network.SwapQuote?, fallbackSlippageBps: Int): Quote8418? =
        if (q == null) null else Quote8418(q.inAmount, q.outAmount, q.priceImpactPct, q.router, q.outputMint,
            q.raw.optInt("slippageBps", fallbackSlippageBps))

    /** Executor.prepareJupiterBuy8090, after the final quote is in hand: refusal (recorded) or null to send. */
    fun jupiterRefusal8418(
        ts: TokenState, intent: ExecutableOpenGate.ExecutionIntent,
        approvedQuote: com.lifecyclebot.network.SwapQuote?, executedQuote: com.lifecyclebot.network.SwapQuote, slippageBps: Int,
    ): String? {
        val price = try {
            SizingQuoteRecheck8153.quotePrice8167(executedQuote.inAmount, executedQuote.outAmount, ts.tokenMap.decimals,
                WalletManager.freshSolPrice8163(), ts.lastPrice)
        } catch (_: Throwable) { ts.lastPrice }
        return check8418(ts.mint, intent, executedQuote.inAmount, price, quoteOf8418(approvedQuote, slippageBps), quoteOf8418(executedQuote, slippageBps))
    }

    /** Executor.tryPumpPortalBuy (entry, no quote; liveBuy already ran the 8329 curve-mark check): size / strategy / price. */
    fun pumpRefusal8418(ts: TokenState, attemptId: String, plannedSol: Double): String? {
        val approved = if (attemptId.isBlank()) null else try { ExecutableOpenGate.ticketForAttempt(attemptId) } catch (_: Throwable) { null }
        return check8418(ts.mint, approved, lamports8418(plannedSol), ts.lastPrice, null, null)
    }

    private fun check8418(mint: String, intent: ExecutableOpenGate.ExecutionIntent?, spentLamports: Long, priceUsd: Double,
                          approvedQuote: Quote8418?, executedQuote: Quote8418?): String? {
        val current = if (intent == null) null else try { ExecutableOpenGate.ticketForAttempt(intent.attemptId) } catch (_: Throwable) { null }
        val r = try { refusal8418(intent, current, mint, spentLamports, priceUsd, approvedQuote, executedQuote) } catch (_: Throwable) { PREFIX + "CHECK_FAULT" }
        if (intent != null) {
            val rec = Record8418(intent.attemptId, mint, intent.canonicalLane, intent.resolvedSize, spentLamports / 1_000_000_000.0,
                spentLamports, intent.executableMarkPriceUsd6613, priceUsd,
                approvedQuote?.router.orEmpty(), executedQuote?.router.orEmpty(), approvedQuote?.outRaw ?: 0L, executedQuote?.outRaw ?: 0L,
                approvedQuote?.impactPct ?: Double.NaN, executedQuote?.impactPct ?: Double.NaN, r, System.currentTimeMillis())
            if (byAttempt.size > 1_000) byAttempt.clear()
            byAttempt[intent.attemptId] = rec
        }
        try {
            PipelineHealthCollector.labelInc(r ?: "ENTRY_CONTINUITY_8418_OK")
            if (r != null) ForensicLogger.lifecycle("ENTRY_CONTINUITY_REFUSED_8418",
                "mint=${mint.take(10)} attempt=${intent?.attemptId?.take(28)} reason=$r sealedSol=${intent?.resolvedSize} spentLamports=$spentLamports " +
                    "mark=${intent?.executableMarkPriceUsd6613} px=$priceUsd router=${approvedQuote?.router}->${executedQuote?.router} " +
                    "out=${approvedQuote?.outRaw}->${executedQuote?.outRaw} impact=${approvedQuote?.impactPct}->${executedQuote?.impactPct}")
        } catch (_: Throwable) {}
        return r
    }
}
