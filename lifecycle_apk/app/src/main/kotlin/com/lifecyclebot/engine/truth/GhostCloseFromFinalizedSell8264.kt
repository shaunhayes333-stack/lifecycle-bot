package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger

/**
 * V5.0.8264 — A FINALIZED FULL SELL CLOSES ITS CANONICAL ROW, EVEN LATE.
 *
 * 8261 runtime: GOMO 9XKzy4 (CATASTROPHIC_HARD_BACKSTOP), MINI Ax5dAa (DEAD_TOKEN_NO_PRICE_EXIT) and UHI 8PdoiB (MANUAL)
 * each had a LIVE_FINALIZED sell of the row's whole quantity in the journal, a complete wallet enumeration without the
 * mint, and a canonical row still carrying the full raw quantity: CANONICAL_NOT_ON_CHAIN ×3, fundedQuarantined=3,
 * protect=ARMED on holdings that no longer exist. The reconciler can only retire QUARANTINED rows and only after two
 * distinct complete zero snapshots; an OPEN row had no path at all.
 *
 * Pure rule: a canonical lot whose mint a COMPLETE chain read proves absent, with a journaled LIVE sell of that mint
 * that is signed, not failed, chain-proven, dated after the lot opened, and whose confirmed quantity covers the lot's
 * remaining quantity, is closed with THAT sell's economics. Nothing is invented: no sell row, no close.
 */
object GhostCloseFromFinalizedSell8264 {
    /** Confirmed quantity must cover this share of the remaining raw (rounding of a ui quantity to raw). */
    private const val COVER_FRAC_8264 = 0.999

    /** Pure: the journaled sell that proves [lot] is gone, or null. */
    fun provenSell8264(
        lot: ReconcileClassifier8144.Lot8144,
        openedAtMs: Long,
        scale: Int,
        sells: List<ReconcileClassifier8144.Row8144>,
        failedSigs: Set<String>,
        chainComplete: Boolean,
        chainRaw: BigInteger?,
    ): ReconcileClassifier8144.Row8144? {
        if (!chainComplete || (chainRaw != null && chainRaw.signum() > 0)) return null
        if (lot.remainingRaw.signum() <= 0 || scale !in 0..18) return null
        val need = lot.remainingRaw.toBigDecimal().multiply(java.math.BigDecimal(COVER_FRAC_8264))
        return sells.asSequence()
            .filter { it.mint == lot.mint && ReconcileClassifier8144.isSellLike8144(it.side) }
            .filter { it.sig.isNotBlank() && it.sig !in failedSigs }
            .filter { AccountingIntegrity8169.chainProvenProofState8169(it.proofState) }
            .filter { it.ts > openedAtMs }
            .filter { covers8264(it, need, scale) }
            .maxByOrNull { it.ts }
    }

    private fun covers8264(s: ReconcileClassifier8144.Row8144, needRaw: java.math.BigDecimal, scale: Int): Boolean {
        if (s.consumedRaw.signum() > 0) return s.consumedRaw.toBigDecimal() >= needRaw
        if (!(s.soldQty.isFinite() && s.soldQty > 0.0)) return false
        val soldRaw = java.math.BigDecimal(s.soldQty).movePointRight(scale)
        return soldRaw >= needRaw
    }

    /** Pure: the proceeds a journal sell row carries (gross when recorded, else the row's SOL). */
    fun proceeds8264(s: ReconcileClassifier8144.Row8144): Double =
        if (s.grossProceedsSol.isFinite() && s.grossProceedsSol > 0.0) s.grossProceedsSol
        else if (s.costSol.isFinite() && s.costSol > 0.0) s.costSol else 0.0

    /**
     * ReconcileRunner8144: close every CANONICAL_NOT_ON_CHAIN lot a finalized sell proves gone. Returns the mints closed.
     */
    fun closeProven8264(
        exceptions: List<ReconcileClassifier8144.Exception8144>,
        lots: List<ReconcileClassifier8144.Lot8144>,
        sells: List<ReconcileClassifier8144.Row8144>,
        failedSigs: Set<String>,
        chain: Map<String, BigInteger>?,
        chainComplete: Boolean,
    ): List<String> {
        val ghosts = exceptions.filter { it.cause == ReconcileClassifier8144.Cause8144.CANONICAL_NOT_ON_CHAIN }.mapTo(HashSet()) { it.mint }
        if (ghosts.isEmpty()) return emptyList()
        val closed = ArrayList<String>(ghosts.size)
        for (lot in lots) {
            if (lot.mint !in ghosts) continue
            val pos = try { CanonicalPositionAuthority6441.getPosition(lot.positionId) } catch (_: Throwable) { null } ?: continue
            val sell = provenSell8264(lot, pos.openedAtMs, pos.quantityScale, sells, failedSigs, chainComplete, chain?.get(lot.mint)) ?: continue
            val done = try {
                if (pos.lifecycle == CanonicalPositionAuthority6441.Lifecycle.QUARANTINED) {
                    CanonicalPositionAuthority6441.applyProtectiveSellFill7807(lot.mint, lot.remainingRaw, true, "ReconcileRunner8144.finalizedSellProof8264")
                } else {
                    val share = if (pos.originalQtyRaw.signum() > 0)
                        lot.remainingRaw.toBigDecimal().divide(pos.originalQtyRaw.toBigDecimal(), 12, java.math.RoundingMode.HALF_EVEN).toDouble()
                    else 1.0
                    CanonicalPositionAuthority6441.partialSell(
                        idempotencyKey = "GHOST_CLOSE_8264|${lot.positionId}|${sell.sig}",
                        positionId = lot.positionId, soldQtyRaw = lot.remainingRaw,
                        proceedsSol = proceeds8264(sell), soldCostBasisSol = pos.entryCostSol * share, feesSol = 0.0, paperMode = false,
                    ) == CanonicalPositionAuthority6441.MutateResult.APPLIED
                }
            } catch (_: Throwable) { false }
            if (!done) continue
            closed += lot.mint
            try {
                PipelineHealthCollector.labelInc("GHOST_ROW_CLOSED_BY_FINALIZED_SELL_8264")
                ForensicLogger.lifecycle("GHOST_ROW_CLOSED_BY_FINALIZED_SELL_8264",
                    "positionId=${lot.positionId.take(40)} mint=${lot.mint.take(12)} remainingRaw=${lot.remainingRaw} sig=${sell.sig.take(16)} " +
                        "reason=${sell.reason.take(40)} proceeds=${"%.6f".format(proceeds8264(sell))} lifecycleWas=${pos.lifecycle} action=canonical_row_closed_on_chain_absence_plus_finalized_sell")
            } catch (_: Throwable) {}
        }
        return closed
    }
}
