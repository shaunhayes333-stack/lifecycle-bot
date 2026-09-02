package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6405 §18 — ENTRY PRICE INTEGRITY (root-cause fix for the
 * +22 944.5 % phantom-PnL rows and the QUICK_RUNNER_10X false exits).
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "its pulling either the wrong price basis, token metrics or has a
 *  decimal or 0.00000000000 issue. its making the bot think its up
 *  huge and then sells as a loss." Screenshot showed NUCWAR at
 *  Entry: $0.00000000265 with size 0.0254 SOL / 702.12K tokens — the
 *  true tx-derived entry price is (0.0254 / 702 120) × $180 ≈ $6.5e-6.
 *  The stamped value is off by ~2 500× — matching the ratio of SOL to
 *  USD, i.e. DexScreener's `priceNative` (SOL) was mistaken for
 *  `priceUsd` (USD) at the provisional stamp.
 *
 * PROBLEM
 * ───────
 * Executor's LIVE_ENTRY_PRICE_FROM_PROOF branch already recomputes the
 * economic entry via (sol / qtyUi) × solUsd — but only when
 * WalletManager.lastKnownSolPrice > 0. During the first minute after
 * launch (or when the SOL-price feed is stale), solUsd = 0 and the
 * branch takes the DEFERRED path, leaving the wrong provisional
 * price (priceNative-mistaken-for-priceUsd) in place. Downstream PnL
 * / TP / SL / 10× runner all read that phantom value.
 *
 * FIX (root cause, no bandaid)
 * ────────────────────────────
 * 1. deriveTrustedEntryUsd() requires an event-time SOL/USD witness.
 *    Missing price truth returns null and keeps the position pending;
 *    a fixed fallback must never become cost basis.
 * 2. detectBasisDivergence() flags positions whose entryPrice diverges
 *    from the tx-derived truth by > 100× either direction — the
 *    signature of the priceNative-vs-priceUsd wire cross. Callers
 *    invalidate the entry, stamp the trusted value, and refuse to
 *    fire profit-locked exits until the basis is proven.
 * 3. isTrustworthyForRunnerExit() short-circuits the QUICK_RUNNER_10X
 *    logic when the current PnL depends on an untrustworthy basis.
 */
object EntryPriceIntegrityAuthority6405 {

    /** Ratio at which we're certain the basis was captured in the wrong quote. */
    const val BASIS_DIVERGENCE_THRESHOLD: Double = 100.0

    /** Labels the executor stamps when the entry is proven from the tx. */
    private val TRUSTED_SOURCES = setOf(
        "LIVE_PROOF_COST_BASIS",
        "LIVE_PROOF_COST_BASIS_EVENT_WITNESS_6637",
        "WALLET_TX_DELTA",
        "CANONICAL_BUY_FILL",
    )

    data class TrustedEntry(val usdPerToken: Double, val source: String, val solUsdUsed: Double)

    /**
     * Compute canonical USD/token from verified tx economics and an
     * immutable event-time SOL/USD witness. Missing oracle truth is not
     * recoverable by inventing a price.
     */
    fun deriveTrustedEntryUsd(
        costSol: Double,
        qtyUi: Double,
        knownSolUsd: Double,
    ): TrustedEntry? {
        if (costSol <= 0.0 || !costSol.isFinite()) return null
        if (qtyUi <= 0.0 || !qtyUi.isFinite()) return null
        if (!knownSolUsd.isFinite() || knownSolUsd !in 5.0..10_000.0) return null
        val solUsd = knownSolUsd
        val usdPerToken = (costSol / qtyUi) * solUsd
        if (!usdPerToken.isFinite() || usdPerToken <= 0.0) return null
        return TrustedEntry(
            usdPerToken,
            "LIVE_PROOF_COST_BASIS_EVENT_WITNESS_6637",
            solUsd,
        )
    }

    /**
     * Returns true when the stored entryPrice differs from the tx-
     * derived truth by more than BASIS_DIVERGENCE_THRESHOLD× (either
     * direction). This is the priceNative-vs-priceUsd wire-cross
     * signature.
     */
    fun detectBasisDivergence(
        stampedEntryUsd: Double,
        trustedEntryUsd: Double,
    ): Boolean {
        if (stampedEntryUsd <= 0.0 || trustedEntryUsd <= 0.0) return false
        if (!stampedEntryUsd.isFinite() || !trustedEntryUsd.isFinite()) return false
        val hi = maxOf(stampedEntryUsd, trustedEntryUsd)
        val lo = minOf(stampedEntryUsd, trustedEntryUsd)
        return (hi / lo) >= BASIS_DIVERGENCE_THRESHOLD
    }

    /**
     * The 10× runner and other profit-locked exits MUST NOT fire on
     * an untrustworthy basis. Trust criteria:
     *   • entrySource is one of TRUSTED_SOURCES
     *   • no divergence between stampedEntry and tx-derived reconstruction
     */
    fun isTrustworthyForRunnerExit(
        mint: String,
        symbol: String,
        stampedEntryUsd: Double,
        entrySource: String,
        costSol: Double,
        qtyUi: Double,
        knownSolUsd: Double,
    ): Boolean {
        val srcOk = entrySource in TRUSTED_SOURCES
        val trusted = deriveTrustedEntryUsd(costSol, qtyUi, knownSolUsd)
        val divergent = trusted != null && detectBasisDivergence(stampedEntryUsd, trusted.usdPerToken)
        val ok = srcOk && !divergent && stampedEntryUsd > 0.0
        if (!ok) {
            try {
                ForensicLogger.lifecycle(
                    "RUNNER_EXIT_BASIS_UNTRUSTED_6405",
                    "mint=${mint.take(10)} sym=$symbol stampedEntry=$stampedEntryUsd " +
                        "entrySrc=$entrySource costSol=$costSol qtyUi=$qtyUi " +
                        "trustedEntry=${trusted?.usdPerToken ?: "null"} " +
                        "divergent=$divergent srcOk=$srcOk",
                )
                PipelineHealthCollector.labelInc("RUNNER_EXIT_BASIS_UNTRUSTED_6405")
            } catch (_: Throwable) {}
        }
        return ok
    }
}
