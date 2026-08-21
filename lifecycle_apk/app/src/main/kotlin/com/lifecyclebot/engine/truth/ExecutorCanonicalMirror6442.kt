package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6442 — EXECUTOR → CANONICAL MIGRATION MIRROR.
 *
 * OPERATOR MANDATE (V5.0.6441 next actions):
 *   "Migrate Executor Writers: Retrofit Executor paper/live BUY/SELL/
 *    PARTIAL paths to write through CanonicalPositionAuthority6441 and
 *    delete the sibling mutable stores."
 *
 * SAFE MIGRATION PATTERN (V5.0.6442 phase 1)
 * ───────────────────────────────────────────
 * Rather than surgically re-thread every writer in the 24k line
 * Executor.kt in a single ship, this module is the **shared mirror
 * helper** that lives right next to the existing writer sites. Each
 * call site keeps its legacy writes AND additively mirrors into the
 * canonical authority through the helpers below. Once acceptance
 * telemetry shows canonical == legacy for one full trading window,
 * V5.0.6443 will delete the legacy sibling stores.
 *
 * All helpers are FAILURE-TOLERANT. A canonical mirror error must
 * never break the legacy execution path — the mirror is telemetry
 * first.
 */
object ExecutorCanonicalMirror6442 {

    private val bootMs = System.currentTimeMillis()
    private val runIdHash = (bootMs % 100_000L).toString()

    private val buysMirrored = AtomicLong(0L)
    private val sellsMirrored = AtomicLong(0L)
    private val mirrorFailures = AtomicLong(0L)
    private val positionSeq = AtomicLong(0L)
    private val activePositionIdByMint = ConcurrentHashMap<String, String>()
    private val lastClosedPositionIdByMint = ConcurrentHashMap<String, String>()

    fun canonicalMint(mint: String): String = mint.trim()

    /**
     * Canonical positionId derivation: "$mint#$runIdShort". Stable per
     * run — matches the operator's mandate §3 "runId + positionId +
     * side" idempotency-key structure.
     */
    fun positionIdOf(mint: String): String {
        val cm = canonicalMint(mint)
        return activePositionIdByMint[cm] ?: lastClosedPositionIdByMint[cm] ?: "PAPER:$cm:$runIdHash"
    }

    private fun allocatePositionId(mint: String, paperMode: Boolean): String {
        val cm = canonicalMint(mint)
        val existing = activePositionIdByMint[cm]
        if (!existing.isNullOrBlank()) return existing
        val id = "${if (paperMode) "PAPER" else "LIVE"}:$cm:$runIdHash:${positionSeq.incrementAndGet()}"
        activePositionIdByMint[cm] = id
        return id
    }

    fun buyIdempotencyKey(positionId: String): String = "BUY:$runIdHash:$positionId"
    fun sellIdempotencyKey(positionId: String, generation: Long): String =
        "SELL:$runIdHash:$positionId:$generation"

    /**
     * Mirror a BUY attempt/reservation. Called at buy attempt BEFORE fill —
     * qtyRaw is not known yet, so a PENDING_ENTRY row is created. On fill,
     * call [mirrorBuyFill] to promote to OPEN with the actual qty + cost.
     */
    fun mirrorBuyAttempt(
        mint: String,
        symbol: String,
        lane: String,
        estimatedCostSol: Double,
        estimatedFeesSol: Double,
        paperMode: Boolean,
    ): Boolean {
        return try {
            val positionId = allocatePositionId(mint, paperMode)
            val idem = buyIdempotencyKey(positionId)
            // Reserve in the SQLite idempotency store first so a mid-tx restart
            // cannot resubmit; if the reserve returns DUPLICATE, skip the mirror.
            val reserve = try {
                IdempotencyKeyStore6437.checkAndReserve(idem, if (paperMode) "PAPER" else "LIVE", "buy_attempt")
            } catch (_: Throwable) { IdempotencyKeyStore6437.InsertResult.NEW }
            if (reserve == IdempotencyKeyStore6437.InsertResult.DUPLICATE) {
                try { PipelineHealthCollector.labelInc("EXECUTOR_MIRROR_BUY_DUP_6442") } catch (_: Throwable) {}
                return false
            }
            val result = CanonicalPositionAuthority6441.openPosition(
                idempotencyKey = idem,
                positionId = positionId,
                mint = mint,
                symbol = symbol,
                lane = lane,
                runId = runIdHash,
                entryCostSol = estimatedCostSol,
                openedQtyRaw = BigInteger.ZERO,   // pending fill
                tokenDecimals = 9,                 // provisional; corrected on fill
                feesSol = estimatedFeesSol,
                paperMode = false,
            )
            buysMirrored.incrementAndGet()
            if (result == CanonicalPositionAuthority6441.MutateResult.APPLIED || result == CanonicalPositionAuthority6441.MutateResult.DUPLICATE) {
                try { LaneAttributionLedger6427.recordEntry(positionId, lane, strategy = lane, profile = lane) } catch (_: Throwable) {}
                try { PositionStateLedger6427.registerOpen(canonicalMint(mint)) } catch (_: Throwable) {}
            }
            try { PipelineHealthCollector.labelInc("EXECUTOR_MIRROR_BUY_$result".take(60)) } catch (_: Throwable) {}
            result == CanonicalPositionAuthority6441.MutateResult.APPLIED
        } catch (t: Throwable) {
            mirrorFailures.incrementAndGet()
            try { ForensicLogger.lifecycle("EXECUTOR_MIRROR_BUY_FAIL_6442", "mint=${mint.take(10)} err=${t.message?.take(80)}") } catch (_: Throwable) {}
            false
        }
    }

    /** Called when the BUY fill is known — promotes PENDING_ENTRY → OPEN. */
    fun mirrorBuyFill(
        mint: String,
        actualQtyRaw: BigInteger,
        actualCostSol: Double,
        actualFeesSol: Double,
        tokenDecimals: Int,
        paperMode: Boolean,
    ): Boolean {
        return try {
            val positionId = positionIdOf(mint)
            val result = CanonicalPositionAuthority6441.promotePendingToOpen(
                positionId = positionId,
                actualQtyRaw = actualQtyRaw,
                actualEntryCostSol = actualCostSol,
                actualFeesSol = actualFeesSol,
                tokenDecimals = tokenDecimals,
                paperMode = false,
            )
            if (result == CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                try { IdempotencyKeyStore6437.markTerminal(buyIdempotencyKey(positionId), "BUY_CONFIRMED") } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("CANONICAL_BUY_CONFIRMED_OPEN_6448") } catch (_: Throwable) {}
            }
            result == CanonicalPositionAuthority6441.MutateResult.APPLIED
        } catch (t: Throwable) {
            mirrorFailures.incrementAndGet()
            false
        }
    }

    fun abortBuy6485(mint: String, reason: String) {
        val cm = canonicalMint(mint)
        val positionId = activePositionIdByMint.remove(cm) ?: return
        try { CanonicalPositionAuthority6441.abortEntry6485(positionId, refundPaperFacade = false, reason = reason) } catch (_: Throwable) {}
        try { IdempotencyKeyStore6437.markTerminal(buyIdempotencyKey(positionId), "BUY_ABORTED_6485") } catch (_: Throwable) {}
    }

    /**
     * Mirror a PARTIAL or FULL SELL. `soldQtyRaw` is BigInteger raw qty
     * being sold; the canonical authority auto-transitions to CLOSED if
     * remaining hits zero.
     */
    fun mirrorSell(
        mint: String,
        generation: Long,
        soldQtyRaw: BigInteger,
        proceedsSol: Double,
        soldCostBasisSol: Double,
        feesSol: Double,
        paperMode: Boolean,
        terminal: Boolean = true,
        lane: String = "",
        reason: String = "SELL_CONFIRMED",
    ) {
        try {
            val positionId = positionIdOf(mint)
            val idem = sellIdempotencyKey(positionId, generation)
            val reserve = try {
                IdempotencyKeyStore6437.checkAndReserve(idem, if (paperMode) "PAPER" else "LIVE", "sell")
            } catch (_: Throwable) { IdempotencyKeyStore6437.InsertResult.NEW }
            if (reserve == IdempotencyKeyStore6437.InsertResult.DUPLICATE) {
                try { PipelineHealthCollector.labelInc("EXECUTOR_MIRROR_SELL_DUP_6442") } catch (_: Throwable) {}
                return
            }
            val posBefore = CanonicalPositionAuthority6441.getPosition(positionId)
            val qtyToSell = if (terminal && posBefore != null && posBefore.remainingQtyRaw > BigInteger.ZERO) posBefore.remainingQtyRaw else soldQtyRaw
            val result = CanonicalPositionAuthority6441.partialSell(
                idempotencyKey = idem,
                positionId = positionId,
                soldQtyRaw = qtyToSell,
                proceedsSol = proceedsSol,
                soldCostBasisSol = soldCostBasisSol,
                feesSol = feesSol,
                paperMode = paperMode,
            )
            sellsMirrored.incrementAndGet()
            val posAfter = CanonicalPositionAuthority6441.getPosition(positionId)
            if (result == CanonicalPositionAuthority6441.MutateResult.APPLIED && posAfter != null) {
                if (posAfter.lifecycle == CanonicalPositionAuthority6441.Lifecycle.CLOSED) {
                    try { PositionStateLedger6427.confirmTerminalSell(canonicalMint(mint)) } catch (_: Throwable) {}
                    try { LaneAttributionLedger6427.recordExitPolicy(positionId, lane.ifBlank { posAfter.lane }, reason, if (paperMode) "PAPER" else "LIVE", "ExecutorCanonicalMirror6448") } catch (_: Throwable) {}
                    try { IdempotencyKeyStore6437.markTerminal(idem, "SELL_CONFIRMED") } catch (_: Throwable) {}
                    try { RewardPurityGate6441.acceptFinalizedClose(positionId, posAfter.realizedPnlSol) } catch (_: Throwable) {}
                    lastClosedPositionIdByMint[canonicalMint(mint)] = positionId
                    activePositionIdByMint.remove(canonicalMint(mint), positionId)
                    try { PipelineHealthCollector.labelInc("CANONICAL_SELL_CONFIRMED_CLOSED_6448") } catch (_: Throwable) {}
                } else {
                    try { PositionStateLedger6427.markPartial(canonicalMint(mint)) } catch (_: Throwable) {}
                    try { PipelineHealthCollector.labelInc("CANONICAL_PARTIAL_SELL_CONFIRMED_6448") } catch (_: Throwable) {}
                }
            }
            try { PipelineHealthCollector.labelInc("EXECUTOR_MIRROR_SELL_$result".take(60)) } catch (_: Throwable) {}
        } catch (t: Throwable) {
            mirrorFailures.incrementAndGet()
        }
    }

    fun statusLine(): String =
        "buysMirrored=${buysMirrored.get()} sellsMirrored=${sellsMirrored.get()} " +
            "failures=${mirrorFailures.get()} runId=$runIdHash"
}
