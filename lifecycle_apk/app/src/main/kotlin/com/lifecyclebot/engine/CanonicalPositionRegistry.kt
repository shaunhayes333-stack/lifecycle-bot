package com.lifecyclebot.engine

import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6324 — CANONICAL POSITION REGISTRY (operator hotfix §1/§2/§14).
 *
 * Single authoritative record for every live mint. All entries stored
 * per the spec authority ladder so lower-authority telemetry (advisor
 * estimates, Dexscreener, broadcast-only rows) cannot overwrite a
 * confirmed wallet/tx delta.
 *
 * Fields intentionally mirror the operator directive verbatim so the
 * downstream health report can bind directly.
 */
object CanonicalPositionRegistry {

    enum class QuantityAuthority {
        WALLET_TX_DELTA,
        PARSED_TX_DELTA,
        FINALISED_PROVIDER_FILL,
        WALLET_RECONCILIATION,
        EXECUTION_ESTIMATE,
        UNKNOWN,
    }

    enum class CostAuthority {
        CONFIRMED_SOL_DELTA,
        PARSED_TX_SOL_DELTA,
        FINALISED_PROVIDER_FILL,
        EXECUTION_ESTIMATE,
        UNKNOWN,
    }

    enum class BasisStatus { PROVISIONAL, RECONCILED, QUARANTINED, CLOSED }
    enum class ReconciliationStatus { NONE, PENDING, COMPLETED, FAILED }

    /** Rank order used to decide whether an incoming write may replace
     *  the existing quantity. Higher = stronger authority. */
    private fun rank(a: QuantityAuthority): Int = when (a) {
        QuantityAuthority.WALLET_TX_DELTA -> 5
        QuantityAuthority.PARSED_TX_DELTA -> 4
        QuantityAuthority.FINALISED_PROVIDER_FILL -> 3
        QuantityAuthority.WALLET_RECONCILIATION -> 2
        QuantityAuthority.EXECUTION_ESTIMATE -> 1
        QuantityAuthority.UNKNOWN -> 0
    }
    private fun rank(a: CostAuthority): Int = when (a) {
        CostAuthority.CONFIRMED_SOL_DELTA -> 4
        CostAuthority.PARSED_TX_SOL_DELTA -> 3
        CostAuthority.FINALISED_PROVIDER_FILL -> 2
        CostAuthority.EXECUTION_ESTIMATE -> 1
        CostAuthority.UNKNOWN -> 0
    }

    data class FillLot(
        val fillSignature: String,
        val rawAmount: BigInteger,
        val decimals: Int,
        val displayQuantity: Double,
        val solCost: Double,
        val timestamp: Long,
        val source: String,
        val authority: QuantityAuthority,
        val remainingRaw: BigInteger,
    )

    data class CanonicalPosition(
        val mint: String,
        val canonicalPositionId: String,
        val laneAtEntry: String,
        val strategyAtEntry: String,
        val entrySignature: String,
        val entrySlot: Long,
        val entryTimestamp: Long,
        val rawTokenAmountInteger: BigInteger,
        val tokenDecimals: Int,
        val canonicalBoughtQuantity: Double,
        val canonicalRemainingQuantity: Double,
        val canonicalSoldQuantity: Double,
        val canonicalCostSol: Double,
        val canonicalAverageEntryPriceSol: Double,
        val canonicalRealisedProceedsSol: Double,
        val canonicalRealisedPnlSol: Double,
        val canonicalUnrealisedPnlSol: Double,
        val quantityAuthority: QuantityAuthority,
        val costAuthority: CostAuthority,
        val lastWalletBalanceRaw: BigInteger,
        val lastWalletBalanceSlot: Long,
        val basisStatus: BasisStatus,
        val reconciliationStatus: ReconciliationStatus,
        val learningEligibility: LearningEligibility.Eligibility,
        val sourceConfidence: Double,
        val version: Int,
        val updatedAt: Long,
        val fillLots: List<FillLot> = emptyList(),
    )

    private val positions = ConcurrentHashMap<String, CanonicalPosition>()

    fun get(mint: String): CanonicalPosition? = positions[mint]
    fun activeCount(): Int = positions.size

    /**
     * Idempotent-ish upsert. Higher-authority writes REPLACE existing
     * quantity/cost fields; equal-or-lower authority writes are rejected
     * and emit [LOWER_AUTHORITY_OVERWRITE_REJECTED_6324]. Invariants:
     *   • bought/sold/remaining >= 0
     *   • sold + remaining ≈ bought (tolerance 1e-9 UI units)
     *   • decimals stable per mint
     */
    fun upsertQuantity(
        mint: String,
        rawAmount: BigInteger,
        decimals: Int,
        newAuthority: QuantityAuthority,
        signature: String,
        source: String,
    ): CanonicalPosition? {
        if (mint.isBlank() || rawAmount.signum() < 0) {
            emitInvariant(mint, "NEGATIVE_OR_BLANK_QTY", "-", rawAmount.toString(), "-", newAuthority.name, signature, decimals, "reject")
            return null
        }
        val existing = positions[mint]
        if (existing != null) {
            if (existing.tokenDecimals >= 0 && decimals >= 0 && existing.tokenDecimals != decimals) {
                emitInvariant(mint, "DECIMAL_MISMATCH", existing.tokenDecimals.toString(), decimals.toString(), existing.quantityAuthority.name, newAuthority.name, signature, decimals, "reject")
                return existing
            }
            if (rank(newAuthority) <= rank(existing.quantityAuthority) &&
                existing.rawTokenAmountInteger != rawAmount) {
                emitOverwriteRejected(mint, existing.quantityAuthority, newAuthority, signature)
                return existing
            }
        }
        val display = if (decimals >= 0) BigDecimal(rawAmount).movePointLeft(decimals).toDouble() else 0.0
        val updated = (existing?.copy(
            rawTokenAmountInteger = rawAmount,
            tokenDecimals = decimals,
            canonicalBoughtQuantity = display,
            canonicalRemainingQuantity = (display - existing.canonicalSoldQuantity).coerceAtLeast(0.0),
            quantityAuthority = newAuthority,
            version = existing.version + 1,
            updatedAt = System.currentTimeMillis(),
        )) ?: CanonicalPosition(
            mint = mint,
            canonicalPositionId = "$mint#${System.currentTimeMillis()}",
            laneAtEntry = "",
            strategyAtEntry = "",
            entrySignature = signature,
            entrySlot = 0L,
            entryTimestamp = System.currentTimeMillis(),
            rawTokenAmountInteger = rawAmount,
            tokenDecimals = decimals,
            canonicalBoughtQuantity = display,
            canonicalRemainingQuantity = display,
            canonicalSoldQuantity = 0.0,
            canonicalCostSol = 0.0,
            canonicalAverageEntryPriceSol = 0.0,
            canonicalRealisedProceedsSol = 0.0,
            canonicalRealisedPnlSol = 0.0,
            canonicalUnrealisedPnlSol = 0.0,
            quantityAuthority = newAuthority,
            costAuthority = CostAuthority.UNKNOWN,
            lastWalletBalanceRaw = rawAmount,
            lastWalletBalanceSlot = 0L,
            basisStatus = BasisStatus.PROVISIONAL,
            reconciliationStatus = ReconciliationStatus.NONE,
            learningEligibility = LearningEligibility.Eligibility.PENDING_FINALITY,
            sourceConfidence = 1.0,
            version = 1,
            updatedAt = System.currentTimeMillis(),
        )
        positions[mint] = updated
        try {
            ForensicLogger.lifecycle(
                "CANONICAL_POSITION_AUTHORITY_PROMOTED_6324",
                "mint=${mint.take(10)} rawAmount=$rawAmount decimals=$decimals authority=$newAuthority previousAuthority=${existing?.quantityAuthority} sig=${signature.take(16)} source=$source",
            )
            PipelineHealthCollector.labelInc("CANONICAL_POSITION_AUTHORITY_PROMOTED_6324")
        } catch (_: Throwable) {}
        return updated
    }

    fun recordSold(mint: String, soldRawDelta: BigInteger, proceedsSol: Double, signature: String) {
        val cur = positions[mint] ?: return
        val soldDisplay = if (cur.tokenDecimals >= 0) BigDecimal(soldRawDelta).movePointLeft(cur.tokenDecimals).toDouble() else 0.0
        val newSold = (cur.canonicalSoldQuantity + soldDisplay).coerceAtLeast(0.0)
        if (newSold > cur.canonicalBoughtQuantity + 1e-9) {
            emitInvariant(mint, "SOLD_EXCEEDS_BOUGHT", cur.canonicalBoughtQuantity.toString(), newSold.toString(), cur.quantityAuthority.name, cur.quantityAuthority.name, signature, cur.tokenDecimals, "reject")
            return
        }
        val newRemaining = (cur.canonicalBoughtQuantity - newSold).coerceAtLeast(0.0)
        val newProceeds = cur.canonicalRealisedProceedsSol + proceedsSol
        val closed = newRemaining <= 1e-9
        positions[mint] = cur.copy(
            canonicalSoldQuantity = newSold,
            canonicalRemainingQuantity = newRemaining,
            canonicalRealisedProceedsSol = newProceeds,
            canonicalRealisedPnlSol = newProceeds - cur.canonicalCostSol,
            basisStatus = if (closed) BasisStatus.CLOSED else cur.basisStatus,
            version = cur.version + 1,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun clear(mint: String, reason: String) {
        if (positions.remove(mint) != null) {
            try {
                ForensicLogger.lifecycle("CANONICAL_POSITION_CLEARED_6324", "mint=${mint.take(10)} reason=$reason")
                PipelineHealthCollector.labelInc("CANONICAL_POSITION_CLEARED_6324")
            } catch (_: Throwable) {}
        }
    }

    private fun emitInvariant(
        mint: String, invariant: String, existingValue: String, incomingValue: String,
        existingAuthority: String, incomingAuthority: String, signature: String,
        decimals: Int, action: String,
    ) {
        try {
            ForensicLogger.lifecycle(
                "CANONICAL_POSITION_INVARIANT_REJECTED_6324",
                "mint=${mint.take(10)} invariant=$invariant existing=$existingValue incoming=$incomingValue existingAuthority=$existingAuthority incomingAuthority=$incomingAuthority sig=${signature.take(16)} decimals=$decimals action=$action",
            )
            PipelineHealthCollector.labelInc("CANONICAL_POSITION_INVARIANT_REJECTED_6324")
        } catch (_: Throwable) {}
    }

    private fun emitOverwriteRejected(
        mint: String, existing: QuantityAuthority, incoming: QuantityAuthority, signature: String,
    ) {
        try {
            ForensicLogger.lifecycle(
                "LOWER_AUTHORITY_OVERWRITE_REJECTED_6324",
                "mint=${mint.take(10)} existingAuthority=$existing incomingAuthority=$incoming sig=${signature.take(16)}",
            )
            PipelineHealthCollector.labelInc("LOWER_AUTHORITY_OVERWRITE_REJECTED_6324")
        } catch (_: Throwable) {}
    }
}
