package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger

/**
 * V5.0.6677 — persisted CRYPTO_ALT sentinel-entry recovery.
 *
 * A price fingerprint that MarketDataProvenance6471 declares to be a known
 * standalone sentinel can never be a valid economic entry basis. Historical
 * builds nevertheless committed some of those values into canonical paper
 * positions. Replacing the entry with today's market price would invent PnL,
 * while deleting the lot would lose capital history. The only safe repair is a
 * neutral canonical refund at remaining cost basis through the normal typed
 * transaction path, which writes the matching terminal journal event.
 *
 * This object is deliberately narrow:
 *  - PAPER only
 *  - CRYPTO_ALT only
 *  - OPEN/PARTIALLY_CLOSED canonical inventory only
 *  - exact sentinel predicate owned by MarketDataProvenance6471
 *
 * It does not mutate marks, fabricate entry prices, or touch valid low-priced
 * crypto assets.
 */
object CanonicalSentinelEntryRepair6677 {

    data class RepairResult(
        val candidates: Int,
        val repaired: Int,
        val failed: Int,
        val refundedBasisSol: Double,
    )

    @Synchronized
    fun repairOpenPaperCryptoAltSentinels(): RepairResult {
        val candidates = try {
            CanonicalPositionAuthority6441.openPositions()
                .filter { p ->
                    p.mode.equals("paper", true) &&
                        p.assetClass == AssetClass.CRYPTO_ALT &&
                        p.remainingQtyRaw > BigInteger.ZERO &&
                        MarketDataProvenance6471.isKnownStandaloneSentinelPrice6658(p.entryPriceUsd)
                }
        } catch (_: Throwable) {
            emptyList()
        }

        if (candidates.isEmpty()) return RepairResult(0, 0, 0, 0.0)

        var repaired = 0
        var failed = 0
        var refunded = 0.0

        candidates.forEach { pos ->
            val remainingBasis = (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0)
            try {
                // Never let an administrative bad-basis repair become strategy
                // learning. The transaction itself still remains in forensic and
                // durable accounting history.
                PaperLearningEligibility6519.record(
                    pos.mint,
                    pos.positionId,
                    false,
                    "ADMINISTRATIVE_SENTINEL_ENTRY_REFUND_6677",
                )
                CanonicalPerformanceFilter6395.quarantine(
                    pos.positionId,
                    CanonicalPerformanceFilter6395.QuarantineReason.REPLAY_UNIT_MISMATCH,
                )
            } catch (_: Throwable) {}

            val result = try {
                CanonicalPaperTransaction6486.refund(
                    positionId = pos.positionId,
                    mint = pos.mint,
                    symbol = pos.symbol,
                    reason = "ADMINISTRATIVE_SENTINEL_ENTRY_REFUND_6677",
                )
            } catch (_: Throwable) {
                null
            }

            if (result?.applied == true) {
                repaired++
                refunded += remainingBasis
                try {
                    PipelineHealthCollector.labelInc("CRYPTO_SENTINEL_OPEN_REFUNDED_6677")
                    ForensicLogger.lifecycle(
                        "CRYPTO_SENTINEL_OPEN_REFUNDED_6677",
                        "positionId=${pos.positionId.take(32)} asset=${pos.mint.take(32)} symbol=${pos.symbol} entryPrice=${pos.entryPriceUsd} basis=$remainingBasis action=neutral_canonical_refund",
                    )
                } catch (_: Throwable) {}
            } else {
                failed++
                try {
                    PipelineHealthCollector.labelInc("CRYPTO_SENTINEL_OPEN_REFUND_FAILED_6677")
                    ForensicLogger.lifecycle(
                        "CRYPTO_SENTINEL_OPEN_REFUND_FAILED_6677",
                        "positionId=${pos.positionId.take(32)} asset=${pos.mint.take(32)} entryPrice=${pos.entryPriceUsd} result=${result?.reason ?: "EXCEPTION"}",
                    )
                } catch (_: Throwable) {}
            }
        }

        return RepairResult(candidates.size, repaired, failed, refunded)
    }
}
