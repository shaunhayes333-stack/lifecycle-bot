package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7229 §CANONICAL_FILL_BASIS_SEAL — operator diagnosis Feb 2026.
 *
 * SMOKING GUN — EYMBTN:
 *   BUY at 18:39   entry = 0.00014420  cost = 0.0428 SOL
 *   EXIT at 19:30  entry = 0.00089389  current = 0.0001421
 *                  worstPnl = -84.10% -> CATASTROPHIC_HARD_BACKSTOP_-25
 *                                     -> TICK_CATASTROPHIC_CONFIRMED_-84PCT
 *
 *   The exit engine received an entry basis 6.19x larger than the real
 *   fill.  Real move was ~-1.5%; the panic-sell was economic damage
 *   manufactured by a corrupted basis, and it went straight into the
 *   Forward Outcome Model / lane expectancy / policy head as a
 *   catastrophic loss for PROJECT_SNIPER.
 *
 * RULES (from the operator 7227 directive):
 *   1. Once BUY finality/wallet proof lands, seal:
 *      mint, raw quantity, decimals, executedSol, feeAdjustedSol,
 *      executedTokenPrice, entryUsd, entryMarketCap, buy signature.
 *   2. The sealed fill lot is the ONLY cost-basis authority.
 *   3. No token metadata refresh, lane transition, wallet recovery,
 *      DexScreener hydration, token-map refresh or mark refresh may
 *      overwrite the canonical entry price/cost.
 *   4. If a live exit basis differs materially from canonical fill
 *      basis, refuse normal/catastrophic PNL-based exit and repair
 *      basis first.
 *
 * SCOPE — additive.  Existing entry-price fields keep their shape;
 * this authority is a parallel immutable source of truth and a veto
 * on economic exit decisions that reference an off-seal basis.
 * §8 preservations from 6845 remain untouched (canonical accounting,
 * mark sanity, reconciler, FDG sealing, terminal idempotency, capital
 * conservation, stale-ticket protection).
 */
object CanonicalFillBasisSeal7229 {

    /** Two basis values are treated as "materially different" once the
     *  relative delta exceeds this bound.  A 1% price wiggle from a
     *  post-fill DexScreener hydration should not fire the veto; a
     *  6.19x replacement (EYMBTN) trivially does. */
    private const val MATERIAL_DELTA_REL = 0.05  // 5%

    data class Seal(
        val mint: String,
        val positionId: String,
        val entryPrice: Double,          // executed token price (USD or quote)
        val executedSol: Double,
        val feeAdjustedSol: Double,
        val rawQuantity: Double,         // raw token units
        val decimals: Int,
        val entryUsd: Double,
        val entryMarketCap: Double,
        val buySignature: String,
        val sealedAtMs: Long,
    )

    private val seals = ConcurrentHashMap<String, Seal>()

    private val sealsCreated = AtomicLong(0L)
    private val overwriteRefused = AtomicLong(0L)
    private val idempotentAccept = AtomicLong(0L)
    private val exitBasisMismatch = AtomicLong(0L)
    private val exitBasisAgree = AtomicLong(0L)

    private fun keyOf(mint: String, positionId: String): String =
        "${mint.trim().take(32)}::${positionId.trim().take(32)}"

    /**
     * Called at BUY finality with the wallet-proven fill parameters.
     * Idempotent - re-sealing with the same buy signature is a no-op.
     * A DIFFERENT seal for the same (mint, positionId) is refused
     * (that is what corrupted EYMBTN).
     *
     * Returns the effective seal.  Callers should NOT overwrite entry
     * price/cost after this returns.
     */
    fun sealBuyFinality(
        mint: String,
        positionId: String,
        entryPrice: Double,
        executedSol: Double,
        feeAdjustedSol: Double,
        rawQuantity: Double,
        decimals: Int,
        entryUsd: Double,
        entryMarketCap: Double,
        buySignature: String,
    ): Seal {
        val k = keyOf(mint, positionId)
        val existing = seals[k]
        if (existing != null) {
            // Idempotency: same signature -> accept.
            if (existing.buySignature.isNotBlank() &&
                buySignature.isNotBlank() &&
                existing.buySignature == buySignature
            ) {
                idempotentAccept.incrementAndGet()
                return existing
            }
            // Different signature or empty vs filled -> refuse the
            // rewrite, keep the earlier seal.
            overwriteRefused.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("CANONICAL_FILL_BASIS_OVERWRITE_REFUSED_7229")
                ForensicLogger.lifecycle(
                    "CANONICAL_FILL_BASIS_OVERWRITE_REFUSED_7229",
                    "mint=${mint.take(10)} positionId=${positionId.take(12)} " +
                        "kept_entry=${existing.entryPrice} " +
                        "attempted_entry=$entryPrice " +
                        "kept_sig=${existing.buySignature.take(10)} " +
                        "attempted_sig=${buySignature.take(10)}",
                )
            } catch (_: Throwable) {}
            return existing
        }
        val seal = Seal(
            mint = mint, positionId = positionId,
            entryPrice = entryPrice,
            executedSol = executedSol, feeAdjustedSol = feeAdjustedSol,
            rawQuantity = rawQuantity, decimals = decimals,
            entryUsd = entryUsd, entryMarketCap = entryMarketCap,
            buySignature = buySignature,
            sealedAtMs = System.currentTimeMillis(),
        )
        seals[k] = seal
        sealsCreated.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("CANONICAL_FILL_BASIS_SEALED_7229")
            ForensicLogger.lifecycle(
                "CANONICAL_FILL_BASIS_SEALED_7229",
                "mint=${mint.take(10)} positionId=${positionId.take(12)} " +
                    "entryPrice=$entryPrice executedSol=$executedSol " +
                    "qty=$rawQuantity sig=${buySignature.take(10)}",
            )
        } catch (_: Throwable) {}
        return seal
    }

    /** Read the sealed basis (or null if not yet sealed - e.g. paper /
     *  wallet-recovered position with unknown basis). */
    fun seal(mint: String, positionId: String): Seal? = seals[keyOf(mint, positionId)]

    /**
     * V5.0.7234 - true if ANY seal exists for the given mint,
     * regardless of positionId.  Used by the reconciler classifier
     * hook when the caller does not yet know the canonical positionId
     * for a wallet-observed mint.
     */
    fun anySealForMint(mint: String): Boolean {
        val prefix = "${mint.trim().take(32)}::"
        return seals.keys.any { it.startsWith(prefix) }
    }

    data class Verdict(
        val allow: Boolean,
        val reason7229: String,
        val sealedEntry: Double,
        val proposedEntry: Double,
        val relativeDelta: Double,
    )

    /**
     * Called before firing catastrophic / normal-stop exits that
     * compute PnL from an entry basis.  If the exit engine's basis
     * disagrees materially with the sealed fill basis, refuse the
     * economic exit and let a basis-repair sweep run first.
     *
     * A position with no seal (paper, wallet-recovered basis-unknown)
     * always passes - it is the caller's responsibility to route
     * unknown-basis positions to the classification path, not this
     * authority's.  Never manufacture PnL from an unknown basis.
     */
    fun exitBasisAllowed(
        mint: String,
        positionId: String,
        proposedEntryPrice: Double,
        exitReason: String,
    ): Verdict {
        val s = seals[keyOf(mint, positionId)]
        // Not sealed: pass through, count as agree so telemetry doesn't
        // spike on paper positions.
        if (s == null) {
            exitBasisAgree.incrementAndGet()
            return Verdict(true, "UNSEALED_PASSTHROUGH", 0.0, proposedEntryPrice, 0.0)
        }
        if (!proposedEntryPrice.isFinite() || proposedEntryPrice <= 0.0) {
            exitBasisMismatch.incrementAndGet()
            emitVeto(mint, positionId, exitReason,
                "PROPOSED_ENTRY_NONFINITE", s.entryPrice, proposedEntryPrice, Double.NaN)
            return Verdict(false, "PROPOSED_ENTRY_NONFINITE", s.entryPrice, proposedEntryPrice, Double.NaN)
        }
        val ref = s.entryPrice
        if (!ref.isFinite() || ref <= 0.0) {
            // Seal was created with a zero entry - defensive.  Allow
            // and log.
            exitBasisAgree.incrementAndGet()
            return Verdict(true, "SEAL_ENTRY_NONFINITE_ALLOW", ref, proposedEntryPrice, 0.0)
        }
        val denom = kotlin.math.max(kotlin.math.abs(ref), kotlin.math.abs(proposedEntryPrice))
        val delta = kotlin.math.abs(proposedEntryPrice - ref) / denom
        if (delta > MATERIAL_DELTA_REL) {
            exitBasisMismatch.incrementAndGet()
            emitVeto(mint, positionId, exitReason,
                "BASIS_MATERIAL_DELTA", s.entryPrice, proposedEntryPrice, delta)
            return Verdict(false, "BASIS_MATERIAL_DELTA_${(delta * 100.0).toInt()}pct",
                ref, proposedEntryPrice, delta)
        }
        exitBasisAgree.incrementAndGet()
        return Verdict(true, "BASIS_AGREE", ref, proposedEntryPrice, delta)
    }

    private fun emitVeto(
        mint: String, positionId: String,
        exitReason: String, category: String,
        sealed: Double, proposed: Double, delta: Double,
    ) {
        try {
            PipelineHealthCollector.labelInc("CANONICAL_FILL_BASIS_EXIT_VETO_7229")
            PipelineHealthCollector.labelInc("CANONICAL_FILL_BASIS_EXIT_VETO_7229_$category")
            ForensicLogger.lifecycle(
                "CANONICAL_FILL_BASIS_EXIT_VETO_7229",
                "mint=${mint.take(10)} positionId=${positionId.take(12)} " +
                    "reason=$exitReason category=$category " +
                    "sealed=$sealed proposed=$proposed delta=${"%.4f".format(delta)} " +
                    "action=refuse_exit_until_basis_repaired",
            )
        } catch (_: Throwable) {}
    }

    data class Summary(
        val sealsCreated: Long,
        val overwriteRefused: Long,
        val idempotentAccept: Long,
        val exitBasisMismatch: Long,
        val exitBasisAgree: Long,
    )

    fun summary(): Summary = Summary(
        sealsCreated = sealsCreated.get(),
        overwriteRefused = overwriteRefused.get(),
        idempotentAccept = idempotentAccept.get(),
        exitBasisMismatch = exitBasisMismatch.get(),
        exitBasisAgree = exitBasisAgree.get(),
    )

    fun statusLine(): String {
        val s = summary()
        return "CanonicalFillBasisSeal7229 sealed=${s.sealsCreated} " +
            "overwriteRefused=${s.overwriteRefused} idempotent=${s.idempotentAccept} " +
            "exitMismatch=${s.exitBasisMismatch} exitAgree=${s.exitBasisAgree}"
    }

    internal fun clearForTest() {
        seals.clear()
        sealsCreated.set(0L); overwriteRefused.set(0L); idempotentAccept.set(0L)
        exitBasisMismatch.set(0L); exitBasisAgree.set(0L)
    }
}
