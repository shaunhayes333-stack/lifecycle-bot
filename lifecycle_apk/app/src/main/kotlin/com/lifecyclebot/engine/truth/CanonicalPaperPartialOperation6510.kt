package com.lifecyclebot.engine.truth

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** V5.0.6510 — one claim, one canonical paper partial economic mutation. */
object CanonicalPaperPartialOperation6510 {
    enum class TierState6613 { NONE, REQUESTED, QUANTITY_RESERVED, EXECUTING, CONFIRMED, ACCOUNTED, COMPLETE }
    private val tierStates6613 = ConcurrentHashMap<String, TierState6613>()
    private val tierUpdatedAt6613 = ConcurrentHashMap<String, Long>()

    private fun normalizedTier6613(reason: String): String {
        val r = reason.uppercase()
        return when {
            r.contains("PROTECTIVE_PEAK") -> "PROTECTIVE_PEAK_25"
            r.contains("CAPITAL_RECOVERY") -> "CAPITAL_RECOVERY"
            r.contains("RAPID_INSTANT") -> "RAPID_INSTANT_PROFIT"
            r.contains("ULTRA_RUNNER") -> "ULTRA_RUNNER"
            r.contains("WALLET_GROWTH_HARVEST") -> "WALLET_GROWTH_HARVEST"
            r.contains("PROFIT_LOCK") -> "PROFIT_LOCK_${Regex("[0-9]+").find(r)?.value ?: "GENERIC"}"
            r.startsWith("PARTIAL_") -> r.substringBefore("PCT") + "PCT"
            else -> r.replace(Regex("[0-9]+(?:\\.[0-9]+)?"), "N").take(80)
        }
    }

    private val sequences = ConcurrentHashMap<String, AtomicLong>()
    private val requestSequences = ConcurrentHashMap<String, Long>()

    private fun nextSequence(positionId: String, requestKey: String): Long {
        return requestSequences.computeIfAbsent("$positionId|$requestKey") {
            val seq = sequences.computeIfAbsent(positionId) {
            // Restart-safe seed: persisted economic events are authoritative.
            val prior = EconomicEventSchema6464.snapshot().count { e ->
                e is EconomicEventSchema6464.Sell && e.positionId == positionId && e.partial
            }.toLong()
                AtomicLong(prior)
            }
            seq.incrementAndGet()
        }
    }

    data class Receipt(
        val applied: Boolean, val duplicate: Boolean, val reason: String,
        val positionId: String, val operationId: String, val partialSequence: Long,
        val preQty: BigInteger, val soldQty: BigInteger, val postQty: BigInteger,
        val preCost: Double, val soldCostBasis: Double, val postCost: Double,
        val grossProceeds: Double, val fees: Double, val realizedPnl: Double,
    )

    fun commit(positionId: String, mint: String, symbol: String, fraction: Double,
               grossProceeds: Double, fees: Double, exitReason: String): Receipt {
        val pre = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return empty(positionId, "", 0L, "UNKNOWN_POSITION")
        if (pre.mode != "paper" || fraction <= 0.0 || fraction > 1.0 || pre.remainingQtyRaw <= BigInteger.ZERO)
            return empty(positionId, "", 0L, "INVALID_PARTIAL")
        // V5.0.6613 — canonical entitlement is position + original lot qty +
        // normalized tier. Changing peak text cannot manufacture a new partial;
        // a real top-up changes originalQtyRaw and explicitly rearms a new lot epoch.
        val tier = normalizedTier6613(exitReason)
        val tierKey = "$positionId|${pre.originalQtyRaw}|$tier"
        val priorState = tierStates6613.putIfAbsent(tierKey, TierState6613.REQUESTED)
        if (priorState != null) {
            return empty(positionId, "", 0L, "PARTIAL_TIER_${priorState.name}").copy(duplicate = true)
        }
        tierUpdatedAt6613[tierKey] = System.currentTimeMillis()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PAPER_PARTIAL_CLOSE_REQUESTED") } catch (_: Throwable) {}
        // V5.0.6566 — operation identity is position-local and monotonic.
        val sequence = nextSequence(positionId, tierKey)
        val operationId = "$positionId:$sequence"
        tierStates6613[tierKey] = TierState6613.QUANTITY_RESERVED
        val soldRaw = pre.remainingQtyRaw.toBigDecimal().multiply(BigDecimal.valueOf(fraction))
            .setScale(0, RoundingMode.HALF_UP).toBigInteger().coerceIn(BigInteger.ONE, pre.remainingQtyRaw)
        val preCost = (pre.entryCostSol - pre.soldCostBasisSol).coerceAtLeast(0.0)
        val soldBasis = (preCost * soldRaw.toBigDecimal().divide(pre.remainingQtyRaw.toBigDecimal(), 18, RoundingMode.HALF_UP).toDouble()).coerceIn(0.0, preCost)
        tierStates6613[tierKey] = TierState6613.EXECUTING
        val r = CanonicalPaperTerminalBridge6469.finalizeSell(
            positionId, mint, symbol, pre.openedAtMs, operationId, soldRaw, pre.remainingQtyRaw,
            preCost, grossProceeds.coerceAtLeast(0.0), soldBasis, fees.coerceAtLeast(0.0), pre.lane,
            exitReason, soldRaw >= pre.remainingQtyRaw, directPositionMutation6486 = true,
        )
        val post = CanonicalPositionAuthority6441.getPosition(positionId) ?: pre
        if (r.applied) {
            tierStates6613[tierKey] = TierState6613.CONFIRMED
            tierStates6613[tierKey] = TierState6613.ACCOUNTED
            tierStates6613[tierKey] = TierState6613.COMPLETE
            tierUpdatedAt6613[tierKey] = System.currentTimeMillis()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PAPER_PARTIAL_CLOSE_DONE") } catch (_: Throwable) {}
        } else {
            // Failed operations release entitlement and reserved quantity for a clean retry.
            tierStates6613.remove(tierKey)
            tierUpdatedAt6613.remove(tierKey)
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PAPER_PARTIAL_CLOSE_FAILED|${r.reason.take(80)}") } catch (_: Throwable) {}
        }
        return Receipt(r.applied, !r.applied && r.reason.contains("DUPLICATE", true), r.reason,
            positionId, operationId, sequence, pre.remainingQtyRaw, soldRaw, post.remainingQtyRaw,
            preCost, soldBasis, (post.entryCostSol - post.soldCostBasisSol).coerceAtLeast(0.0),
            grossProceeds, fees, grossProceeds - soldBasis - fees)
    }

    fun tierState6613(positionId: String, originalQtyRaw: BigInteger, reason: String): TierState6613 =
        tierStates6613["$positionId|$originalQtyRaw|${normalizedTier6613(reason)}"] ?: TierState6613.NONE

    internal fun resetForTest6613() { tierStates6613.clear(); tierUpdatedAt6613.clear(); sequences.clear(); requestSequences.clear() }

    private fun empty(pid: String, op: String, seq: Long, reason: String) = Receipt(false, false, reason, pid, op, seq,
        BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
}
