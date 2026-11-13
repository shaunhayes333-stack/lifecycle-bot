package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.PumpCurveKeys7269
import com.lifecyclebot.network.SwapQuote
import org.json.JSONArray
import org.json.JSONObject

/** The transaction's actual route is checked after building, immediately before signing. */
object BuyRouteSafety8522 {
    private const val MAX_QUOTE_AGE_MS = 30_000L

    data class Route(
        val inputMint: String,
        val outputMint: String,
        val observedAtMs: Long,
        val pools: List<String>,
        val source: String,
        val rfq: Boolean = false,
        val invalid: String = "",
    )

    class Refused(val reason: String, val detail: String, val status: LiquidityOwnership8522.Status) :
        IllegalStateException("$reason: $detail") {
        init { require(status != LiquidityOwnership8522.Status.PROVEN) }
    }

    /** Submitted liability belongs to reconciliation; only an unsigned missing proof may be retried. */
    internal fun unsignedPending8522(error: Throwable, submittedSignature: String?): Boolean =
        submittedSignature.isNullOrBlank() && error is Refused && error.status == LiquidityOwnership8522.Status.PENDING

    internal data class PendingRetry8522(val priorCount: Int, val exhausted: Boolean, val reason: String, val next: List<Long>)

    /** Same three-deferral/five-minute budget as the earlier PreTradeHardGate path. */
    internal fun pendingRetry8522(previous: List<Long>, nowMs: Long): PendingRetry8522 {
        val recent = previous.filter { nowMs - it < 5 * 60_000L }
        val exhausted = recent.size >= 3
        return PendingRetry8522(recent.size, exhausted,
            if (exhausted) "DEFERRED_DATA_SAFETY_PROOF_8270" else "SAFETY_PROOF_PENDING_DEFERRED_8270",
            if (exhausted) recent else recent + nowMs)
    }

    private fun targetPools(plan: JSONArray?, mint: String, nested: Boolean): Pair<List<String>, String> {
        if (plan == null || plan.length() == 0) return emptyList<String>() to "BUY_ROUTE_POOL_PROOF_PENDING_8522"
        val pools = linkedSetOf<String>()
        for (i in 0 until plan.length()) {
            val row = plan.optJSONObject(i) ?: return emptyList<String>() to "BUY_ROUTE_PLAN_INCOMPLETE_8522"
            val hop = if (nested) row.optJSONObject("swapInfo") else row
            if (hop == null) return emptyList<String>() to "BUY_ROUTE_PLAN_INCOMPLETE_8522"
            val input = hop.optString("inputMint", "")
            val output = hop.optString("outputMint", "")
            if (input.isBlank() || output.isBlank()) return emptyList<String>() to "BUY_ROUTE_PLAN_INCOMPLETE_8522"
            // A SOL/stablecoin intermediate pool cannot stand in for this token's ownership.
            if (input == mint || output == mint) {
                val pool = hop.optString(if (nested) "ammKey" else "poolId", "")
                if (pool.isBlank()) return emptyList<String>() to "BUY_ROUTE_POOL_PROOF_PENDING_8522"
                pools.add(pool)
            }
        }
        return pools.toList() to if (pools.isEmpty()) "BUY_ROUTE_POOL_PROOF_PENDING_8522" else ""
    }

    fun jupiter(quote: SwapQuote?): Route {
        if (quote == null) return Route("", "", 0L, emptyList(), "JUPITER", invalid = "BUY_EXECUTION_QUOTE_MISSING_8522")
        val rawInput = quote.raw.optString("inputMint", "")
        val rawOutput = quote.raw.optString("outputMint", "")
        val identityMismatch = (rawInput.isNotBlank() && rawInput != quote.inputMint) ||
            (rawOutput.isNotBlank() && rawOutput != quote.outputMint)
        // A pure RFQ buys maker inventory, not an AMM LP. The token's fresh primary-market
        // ownership still has to pass assess() below; the maker key is not an LP address.
        // The legacy isRfqRoute flag also covered Iris/Dflow/OKX transport handling.
        // Those routers can use AMMs, so that flag is not proof of maker-only liquidity.
        val pureRfq = quote.router.equals("jupiterz", ignoreCase = true)
        val (pools, missing) = if (pureRfq) emptyList<String>() to "" else
            targetPools(quote.raw.optJSONArray("routePlan"), quote.outputMint, nested = true)
        return Route(quote.inputMint, quote.outputMint, quote.receivedAtMs8521, pools,
            "JUPITER_${quote.router}", pureRfq,
            if (identityMismatch) "BUY_ROUTE_MINT_MISMATCH_8522" else missing)
    }

    fun raydium(quote: JSONObject, requestedMint: String, observedAtMs: Long): Route {
        val data = quote.optJSONObject("data")
        val (pools, missing) = targetPools(data?.optJSONArray("routePlan"), requestedMint, nested = false)
        return Route(data?.optString("inputMint", "").orEmpty(), data?.optString("outputMint", "").orEmpty(),
            observedAtMs, pools, "RAYDIUM", invalid = missing)
    }

    fun pumpCurve(mint: String, curve: String, buildStartedAtMs: Long): Route =
        Route(JupiterApi.SOL_MINT, mint, buildStartedAtMs, listOf(curve), "PUMP_CURVE",
            invalid = if (curve.isBlank()) "BUY_ROUTE_POOL_PROOF_PENDING_8522" else "")

    /** Pure policy used by every final-send wrapper and by route regressions. */
    internal fun assess(
        mint: String, report: SafetyReport, route: Route, activeCurve: Boolean,
        curveAddress: String, whitelisted: Boolean, nowMs: Long,
        exploreLp8735: Boolean = false,
    ): LiquidityOwnership8522.Result {
        fun pending(reason: String, detail: String) = LiquidityOwnership8522.Result(LiquidityOwnership8522.Status.PENDING, reason, detail)
        if (route.inputMint != JupiterApi.SOL_MINT || route.outputMint != mint)
            return pending("BUY_ROUTE_MINT_MISMATCH_8522", "input=${route.inputMint} output=${route.outputMint} expected=$mint")
        if (report.tier == SafetyTier.HARD_BLOCK || report.confirmedWalletRiskCluster8269 || report.rugcheckScore == 0)
            return LiquidityOwnership8522.Result(LiquidityOwnership8522.Status.BLOCKED, "SAFETY_HARD_BLOCK", report.summary)
        if (report.evaluatedForPaper8522 != false || report.checkedAt <= 0L || nowMs - report.checkedAt !in 0L..120_000L)
            return pending("BUY_FINAL_SAFETY_REPORT_PENDING_8522", "fresh LIVE policy required")
        if (report.mintAuthorityDisabled == false || report.freezeAuthorityDisabled == false)
            return LiquidityOwnership8522.Result(LiquidityOwnership8522.Status.BLOCKED, "BUY_FINAL_AUTHORITY_ACTIVE_8522",
                "mintRevoked=${report.mintAuthorityDisabled} freezeRevoked=${report.freezeAuthorityDisabled}")
        if (report.mintAuthorityDisabled != true || report.freezeAuthorityDisabled != true)
            return pending("BUY_FINAL_AUTHORITY_PROOF_PENDING_8522", "mint and freeze authorities must both be proven revoked")
        if (route.invalid.isNotBlank()) return pending(route.invalid, "route=${route.source}")
        if (route.observedAtMs <= 0L || nowMs - route.observedAtMs !in 0L..MAX_QUOTE_AGE_MS)
            return pending("BUY_ROUTE_PROOF_STALE_8522", "route=${route.source} ageMs=${nowMs - route.observedAtMs}")
        if (route.pools.isEmpty() && !route.rfq)
            return pending("BUY_ROUTE_POOL_PROOF_PENDING_8522", "route=${route.source}")
        val pools = if (route.rfq) listOf("") else route.pools
        var accepted: LiquidityOwnership8522.Result? = null
        for (pool in pools) {
            val proof = LiquidityOwnership8522.assess(report, pool, activeCurve, whitelisted,
                curveAddress, nowMs, expectedMint = mint)
            if (proof.status != LiquidityOwnership8522.Status.PROVEN) {
                // V5.0.8735 — 8734 live: the one explore buy (ExploreLive8733) passed the buy door and was refused here,
                // because this route check asked the same unread LP ownership to be PROVEN again. When the door's own
                // verdict was the explore one, an UNREAD (PENDING) pool is accepted here too; a BLOCKED pool (reported
                // unlocked, under the lock) still refuses, and an observed depth under the exit floor still blocks.
                if (!(exploreLp8735 && proof.status == LiquidityOwnership8522.Status.PENDING)) return proof
                val seen = LiquidityOwnership8522.observedPoolDepth8522(report, pool, nowMs, expectedMint = mint)
                if (seen != null && seen < 500.0) return LiquidityOwnership8522.Result(LiquidityOwnership8522.Status.BLOCKED,
                    "BUY_ROUTE_LIQUIDITY_BELOW_EXIT_FLOOR_8522", "pool=$pool liquidityUsd=$seen requiredUsd=500.0")
                accepted = LiquidityOwnership8522.Result(LiquidityOwnership8522.Status.PROVEN,
                    com.lifecyclebot.engine.truth.ExploreLive8733.LP_EXPLORE_8733, proof.detail)
                continue
            }
            if (proof.reason != "ACTIVE_CURVE_LP_NOT_APPLICABLE_8522") {
                val depth = LiquidityOwnership8522.observedPoolDepth8522(report, pool, nowMs, expectedMint = mint)
                    ?: return pending("BUY_ROUTE_LIQUIDITY_PROOF_PENDING_8522", "pool=$pool has no fresh observed depth")
                if (depth < 500.0) return LiquidityOwnership8522.Result(LiquidityOwnership8522.Status.BLOCKED,
                    "BUY_ROUTE_LIQUIDITY_BELOW_EXIT_FLOOR_8522", "pool=$pool liquidityUsd=$depth requiredUsd=500.0")
            }
            accepted = proof
        }
        return accepted ?: pending("BUY_ROUTE_POOL_PROOF_PENDING_8522", "route=${route.source}")
    }

    private fun checkedReport(ts: TokenState, route: Route, callSite: String): Pair<SafetyReport, Boolean> {
        repeat(2) {
            val before = ts.safety
            val gate = PreTradeHardGate.requireLiveBuyAllowed(ts, callSite,
                selectedPoolAddressOverride8522 = if (route.rfq) "" else route.pools.firstOrNull())
            if (!gate.allowed) throw Refused(gate.reason, gate.detail,
                if (gate.reason == "DEFER_SAFETY_PROOF") LiquidityOwnership8522.Status.PENDING else LiquidityOwnership8522.Status.BLOCKED)
            val after = ts.safety
            // The gate may legitimately add its just-read mint/freeze account proof. Any
            // other concurrent replacement (including PAPER policy) needs another full gate.
            if (before.copy(mintAuthorityDisabled = after.mintAuthorityDisabled,
                    freezeAuthorityDisabled = after.freezeAuthorityDisabled) == after)
                return after to gate.detail.contains(com.lifecyclebot.engine.truth.ExploreLive8733.LP_EXPLORE_8733)
        }
        throw Refused("BUY_FINAL_SAFETY_CHANGED_8522", "safety changed during both final evaluations; retry with current evidence",
            LiquidityOwnership8522.Status.PENDING)
    }

    /**
     * V5.0.8736 §NO_BUY_WITHOUT_A_SELL — owner, 8735: "your now letting it buy tokens that cant be sold at all".
     * Before any Jupiter buy is signed, the exact quantity it buys must be quotable back to SOL now, and that sell must
     * return at least [MIN_ROUND_TRIP_8736] of the SOL spent. No sell route, a failed quote, or a round trip that loses
     * more than half (transfer tax, hook, honeypot, no depth) refuses the buy. Never waived by any lane, cell or explore.
     */
    fun requireSellable8736(ts: TokenState, buyQuote: SwapQuote?, sellQuote: (Long) -> SwapQuote, callSite: String) {
        val why = sellableRefusal8736(buyQuote, try { buyQuote?.takeIf { it.outAmount > 0L }?.let { sellQuote(it.outAmount) } }
            catch (e: Exception) { null })
        if (why == null) { try { PipelineHealthCollector.labelInc("BUY_SELL_PROVEN_8736") } catch (_: Throwable) {}; return }
        try {
            PipelineHealthCollector.labelInc(why.first)
            ForensicLogger.lifecycle("BUY_UNSELLABLE_REFUSED_8736", "mint=${ts.mint} site=$callSite reason=${why.first} ${why.second}")
        } catch (_: Throwable) {}
        throw Refused(why.first, why.second, LiquidityOwnership8522.Status.BLOCKED)
    }

    /** Pure: why a buy of [buy] whose tokens quote back as [sell] is refused, or null when the round trip is proven. */
    fun sellableRefusal8736(buy: SwapQuote?, sell: SwapQuote?): Pair<String, String>? {
        val spent = buy?.inAmount ?: 0L
        return when {
            buy == null || spent <= 0L || buy.outAmount <= 0L -> "BUY_SELL_UNPROVEN_NO_BUY_QUOTE_8736" to "buy quote missing"
            sell == null -> "BUY_SELL_UNPROVEN_NO_SELL_ROUTE_8736" to "no sell quote for ${buy.outAmount} raw"
            sell.outAmount <= 0L -> "BUY_SELL_UNPROVEN_ZERO_OUT_8736" to "sell quote returns 0"
            sell.outAmount.toDouble() / spent.toDouble() < MIN_ROUND_TRIP_8736 ->
                "BUY_ROUND_TRIP_LOSES_TOO_MUCH_8736" to "spent=$spent sellBack=${sell.outAmount}"
            else -> null
        }
    }

    private const val MIN_ROUND_TRIP_8736 = 0.5

    fun requireAllowed(ts: TokenState, route: Route, callSite: String) {
        // Deliberately read current safety after all asynchronous quote/build/freshen work.
        // Top-ups and route fallbacks have the same live prerequisite as the first buy.
        val (report, explore8735) = checkedReport(ts, route, callSite)
        val now = System.currentTimeMillis()
        val active = !ts.tokenMap.migratedOrGraduated && LiquidityOwnership8522.activeCurveVerified(ts.mint, now)
        val curve = if (active) try { PumpCurveKeys7269.canonicalCurveKey7392(ts.mint).orEmpty() } catch (_: Throwable) { "" } else ""
        val verdict = assess(ts.mint, report, route, active, curve, TokenSafetyChecker.isWhitelisted(ts.mint), now,
            exploreLp8735 = explore8735)
        if (verdict.status != LiquidityOwnership8522.Status.PROVEN) {
            if (verdict.status == LiquidityOwnership8522.Status.PENDING) {
                try { SafetyRefreshQueue.request(ts.mint) } catch (_: Throwable) {}
                try { SafetyProofPriority8310.noteDeferred8310(ts.mint, verdict.reason) } catch (_: Throwable) {}
            }
            try {
                PipelineHealthCollector.labelInc(verdict.reason)
                ForensicLogger.lifecycle("BUY_FINAL_SAFETY_REFUSED_8522",
                    "mint=${ts.mint} site=$callSite route=${route.source} reason=${verdict.reason} detail=${verdict.detail}")
            } catch (_: Throwable) {}
            throw Refused(verdict.reason, verdict.detail, verdict.status)
        }
        if (ts.safety !== report || RuntimeModeAuthority.isPaper())
            throw Refused("BUY_FINAL_SAFETY_CHANGED_8522", "report or runtime mode changed before signing",
                LiquidityOwnership8522.Status.PENDING)
        try {
            ForensicLogger.lifecycle("BUY_FINAL_SAFETY_PROVEN_8522",
                "mint=${ts.mint} site=$callSite route=${route.source} pools=${route.pools.joinToString(",")} " +
                    "reportAt=${report.checkedAt} reportAgeMs=${now - report.checkedAt} livePolicy=${report.evaluatedForPaper8522 == false} " +
                    "lp=${report.lpLockPct} topHolder=${report.topHolderPct} proof=${verdict.reason}")
        } catch (_: Throwable) {}
    }
}
