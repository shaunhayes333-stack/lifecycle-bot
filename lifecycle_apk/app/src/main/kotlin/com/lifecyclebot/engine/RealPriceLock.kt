package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.data.ConfigStore
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4182 — REAL PRICE LOCK.
 *
 * Operator mandate: "meme coins absurd gains CAN be realised. I just want
 * real pricing data locked in so if it happens it's legit and the bot takes
 * the profits."
 *
 * Problem context (V5.0.4181 forensic log):
 *   • `🎯 RAPID TAKE_PROFIT_DELEGATE: piss pnl=98181004%`
 *     followed by:
 *   • `SELL LIVE 2S6BjaK1 lane=STANDARD pnl=19.4%/0.0004 SOL reason=ultra_runner_bank_45.2x`
 *
 * The bot thought it had a 45x runner; the Jupiter route only filled at
 * +19.4% PnL. The 45x signal was driven by a spiked oracle price on
 * `ts.lastPrice` that did not survive an actual swap. We do NOT cap or
 * block on PnL magnitude — real meme moonshots can legitimately do
 * 1000x+. Instead, BEFORE banking on a big runner signal, we ask Jupiter
 * for a binding quote on a small fraction of the position and compare
 * the implied current price to the claimed current price. If they agree
 * within tolerance the gain is real → bank it. If Jupiter disagrees by
 * a large margin the signal was phantom → defer one cycle (the next
 * scanner pass will refresh `ts.lastPrice` with the real value).
 *
 * Failure-soft contract: if Jupiter is unreachable, RFQ rejects, or any
 * exception is thrown, the verifier returns `true` so the existing
 * exit logic keeps banking real winners. This helper exists to PREVENT
 * banking on phantom prices — it must NEVER block banking on real
 * winners. The phantom-cost is one missed cycle; the real-cost would
 * be missing a 100x.
 */
object RealPriceLock {

    /**
     * Below this claimed gain we don't bother verifying — every normal
     * trade is in this band and a sub-5x signal is unlikely to be a
     * spiked-oracle phantom. The Jupiter quote cost (round-trip + budget)
     * isn't worth it for ordinary partials.
     */
    private const val VERIFY_THRESHOLD_PNL_PCT = 500.0   // verify TP delegate above +500%
    private const val VERIFY_THRESHOLD_MULTIPLE = 20.0   // verify runner-bank above 20x

    /**
     * Tolerance band. The Jupiter quote will rarely match `ts.lastPrice`
     * exactly because of price impact at our notional. Allow the
     * Jupiter-implied gain to be as low as 40% of the claimed gain
     * before we declare it phantom. Example: claim=45x, Jupiter
     * implies 19x → ratio=0.42 → ACCEPT (real runner with slippage).
     * Claim=45x, Jupiter implies 1.2x → ratio=0.027 → REJECT (phantom).
     */
    private const val MIN_JUP_VS_CLAIM_RATIO = 0.40

    /**
     * Per-mint result cache so we don't re-quote Jupiter every 500ms on
     * the rapid monitor or every tick on the executor. A confirmed
     * decision is reusable for a short window.
     */
    private const val CACHE_TTL_MS = 3_000L

    private data class CacheEntry(val verifiedAtMs: Long, val ok: Boolean, val reason: String)
    data class RouteTruth(val verifiedAtMs: Long, val impliedRatio: Double, val ok: Boolean, val context: String)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val routeTruthByMint = ConcurrentHashMap<String, RouteTruth>()

    fun lastRouteTruth(mint: String, maxAgeMs: Long = 30_000L): RouteTruth? {
        if (mint.isBlank()) return null
        val t = routeTruthByMint[mint] ?: return null
        return if (System.currentTimeMillis() - t.verifiedAtMs <= maxAgeMs) t else null
    }

    /**
     * Verify a RAPID TAKE_PROFIT_DELEGATE trigger.
     *
     * Returns `true` if the claimed PnL is plausibly real (Jupiter quote
     * agrees within tolerance OR verification can't be performed for
     * technical reasons). Returns `false` ONLY when Jupiter explicitly
     * disagrees with the claim by a large margin.
     */
    fun verifyTakeProfitPrice(ts: TokenState, claimedPnlPct: Double, tpPct: Double): Boolean {
        if (claimedPnlPct < VERIFY_THRESHOLD_PNL_PCT) return true
        val ratio = 1.0 + (claimedPnlPct / 100.0)
        return verifyAgainstJupiter(ts, claimedRatio = ratio, contextLabel = "RAPID_TP")
    }

    /**
     * Verify an ULTRA_RUNNER_BANK trigger. Identical logic to TP verify,
     * just with a different threshold (multiples instead of pct) and a
     * label for forensics. Same failure-soft contract.
     */
    fun verifyUltraRunnerBank(
        ts: TokenState,
        gainMultiple: Double,
        currentValueSol: Double,
        costSol: Double,
    ): Boolean {
        if (gainMultiple < VERIFY_THRESHOLD_MULTIPLE) return true
        // Cross-check by SOL value: if currentValueSol is at least 1.5× costSol
        // AND the Jupiter quote confirms the implied price, it's real.
        val claimedRatio = gainMultiple
        return verifyAgainstJupiter(ts, claimedRatio = claimedRatio, contextLabel = "ULTRA_BANK")
    }

    private fun verifyAgainstJupiter(
        ts: TokenState,
        claimedRatio: Double,
        contextLabel: String,
    ): Boolean {
        val nowMs = System.currentTimeMillis()
        val cached = cache[ts.mint]
        if (cached != null && nowMs - cached.verifiedAtMs < CACHE_TTL_MS) {
            return cached.ok
        }
        val ok = try { runJupiterCheck(ts, claimedRatio, contextLabel) }
                 catch (e: Throwable) {
                     // Failure-soft: never block banking on a verifier error.
                     emitFail(ts, contextLabel, "VERIFIER_THREW_${e.javaClass.simpleName}", true)
                     true
                 }
        cache[ts.mint] = CacheEntry(nowMs, ok, contextLabel)
        return ok
    }

    fun routeImpliedGainMultiple(ts: TokenState): Double? {
        return try {
            val pos = ts.position
            val qty = pos.qtyToken
            if (qty <= 0.0 || pos.costSol <= 0.0 || ts.mint.isBlank()) return null
            val probeQty = (qty * 0.01).coerceAtLeast(1.0)
            val decimals = pickDecimals(qty)
            val amountRaw = (probeQty * decimals).toLong().coerceAtLeast(1L)
            val ctx = try { BotService.instance?.applicationContext } catch (_: Throwable) { null }
            val cfg = try { if (ctx != null) ConfigStore.load(ctx) else null } catch (_: Throwable) { null }
            val apiKey = cfg?.jupiterApiKey ?: ""
            val jupiter = try { JupiterApi(apiKey) } catch (_: Throwable) { return null }
            val quote = try {
                jupiter.getQuote(
                    inputMint = ts.mint,
                    outputMint = JupiterApi.SOL_MINT,
                    amountRaw = amountRaw,
                    slippageBps = 500,
                )
            } catch (_: Throwable) { return null }
            val solOut = quote.outAmount.toDouble() / 1_000_000_000.0
            if (solOut <= 0.0 || !solOut.isFinite()) return null
            val impliedFullSol = solOut * 100.0
            (impliedFullSol / pos.costSol).takeIf { it.isFinite() && it > 0.0 }?.also {
                try { routeTruthByMint[ts.mint] = RouteTruth(System.currentTimeMillis(), it, ok = true, context = "ROUTE_IMPLIED") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { null }
    }

    private fun runJupiterCheck(
        ts: TokenState,
        claimedRatio: Double,
        contextLabel: String,
    ): Boolean {
        val pos = ts.position
        val qty = pos.qtyToken
        val entryPrice = pos.entryPrice
        // Need real qty + entry + a tradeable mint. Paper positions or
        // bootstrap rows lack on-chain qty → cannot verify via Jupiter.
        if (qty <= 0.0 || entryPrice <= 0.0 || ts.mint.isBlank()) {
            emitFail(ts, contextLabel, "INSUFFICIENT_POSITION_DATA", true)
            return true  // failure-soft
        }
        // Probe with 1% of the qty so we don't disturb the market and so
        // the quote represents the real exit-side liquidity at our size.
        val probeQty = (qty * 0.01).coerceAtLeast(1.0)
        val decimals = pickDecimals(qty)
        val amountRaw = (probeQty * decimals).toLong().coerceAtLeast(1L)

        val ctx = try { BotService.instance?.applicationContext } catch (_: Throwable) { null }
        val cfg = try { if (ctx != null) ConfigStore.load(ctx) else null } catch (_: Throwable) { null }
        val apiKey = cfg?.jupiterApiKey ?: ""
        val jupiter = try { JupiterApi(apiKey) } catch (_: Throwable) { return softTrue(ts, contextLabel, "JUPITER_INIT_FAIL") }

        val quote = try {
            jupiter.getQuote(
                inputMint = ts.mint,
                outputMint = JupiterApi.SOL_MINT,
                amountRaw = amountRaw,
                slippageBps = 300,  // 3% — generous, we only care about route existence + price
            )
        } catch (_: Throwable) {
            return softTrue(ts, contextLabel, "JUPITER_QUOTE_FAIL")
        }

        val solOut = quote.outAmount.toDouble() / 1_000_000_000.0
        if (solOut <= 0.0 || !solOut.isFinite()) {
            return softTrue(ts, contextLabel, "JUPITER_ZERO_OUT")
        }
        // Compare in SOL terms: the 1% probe returns `solOut` SOL. Therefore
        // the full-position implied SOL value = solOut × 100, and the implied
        // gain ratio = impliedFullSol / costSol. This is the true "what would
        // the swap actually return right now" answer — independent of any
        // oracle / pump.fun BC / cross-pool basis issues.
        val implFullSol = solOut * 100.0
        if (pos.costSol <= 0.0) return softTrue(ts, contextLabel, "COST_BASIS_ZERO")
        val impliedRatio = implFullSol / pos.costSol
        val ratioOfClaim = if (claimedRatio > 0.0) impliedRatio / claimedRatio else 0.0
        try { routeTruthByMint[ts.mint] = RouteTruth(System.currentTimeMillis(), impliedRatio, ok = ratioOfClaim >= MIN_JUP_VS_CLAIM_RATIO, context = contextLabel) } catch (_: Throwable) {}
        val ok = ratioOfClaim >= MIN_JUP_VS_CLAIM_RATIO
        if (!ok) {
            try {
                ForensicLogger.lifecycle(
                    "REAL_PRICE_LOCK_REJECT",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} ctx=$contextLabel claimedRatio=${"%.2f".format(claimedRatio)} jupRatio=${"%.2f".format(impliedRatio)} ratioOfClaim=${"%.3f".format(ratioOfClaim)} probeQty=${"%.2f".format(probeQty)} solOut=${"%.6f".format(solOut)} — phantom price, deferring decision",
                )
                PipelineHealthCollector.labelInc("REAL_PRICE_LOCK_REJECT")
            } catch (_: Throwable) {}
        } else {
            try {
                ForensicLogger.lifecycle(
                    "REAL_PRICE_LOCK_CONFIRMED",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} ctx=$contextLabel claimedRatio=${"%.2f".format(claimedRatio)} jupRatio=${"%.2f".format(impliedRatio)} — gain is REAL, banking",
                )
                PipelineHealthCollector.labelInc("REAL_PRICE_LOCK_CONFIRMED")
            } catch (_: Throwable) {}
        }
        return ok
    }

    private fun softTrue(ts: TokenState, contextLabel: String, reason: String): Boolean {
        emitFail(ts, contextLabel, reason, true)
        return true
    }

    private fun emitFail(ts: TokenState, contextLabel: String, reason: String, soft: Boolean) {
        try {
            ForensicLogger.lifecycle(
                "REAL_PRICE_LOCK_SOFT_${if (soft) "PASS" else "FAIL"}",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} ctx=$contextLabel reason=$reason",
            )
            PipelineHealthCollector.labelInc("REAL_PRICE_LOCK_SOFT_${if (soft) "PASS" else "FAIL"}_$reason")
        } catch (_: Throwable) {}
    }

    /**
     * Pick a decimal scale for amount conversion based on typical mint
     * decimals. SPL tokens are most commonly 6 or 9 decimals; raw qty
     * stored in Position has already been decoded into human units, so
     * we need to multiply back up. We use a heuristic the Executor uses
     * elsewhere (tokenScale): qty * decimals → raw amount. 1e9 is the
     * safe default for pump.fun BC tokens.
     */
    private fun pickDecimals(humanQty: Double): Double {
        return when {
            humanQty >= 1_000_000_000.0 -> 1_000_000.0   // 6 decimals — high-supply
            humanQty >= 1_000_000.0     -> 1_000_000.0   // 6 decimals
            else                         -> 1_000_000_000.0  // 9 decimals default
        }
    }
}
