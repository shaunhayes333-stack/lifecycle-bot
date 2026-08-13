package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
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

    /**
     * Canonical positionId derivation: "$mint#$runIdShort". Stable per
     * run — matches the operator's mandate §3 "runId + positionId +
     * side" idempotency-key structure.
     */
    fun positionIdOf(mint: String): String = "${mint.take(24)}#$runIdHash"

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
    ) {
        try {
            val positionId = positionIdOf(mint)
            val idem = buyIdempotencyKey(positionId)
            // Reserve in the SQLite idempotency store first so a mid-tx restart
            // cannot resubmit; if the reserve returns DUPLICATE, skip the mirror.
            val reserve = try {
                IdempotencyKeyStore6437.checkAndReserve(idem, if (paperMode) "PAPER" else "LIVE", "buy_attempt")
            } catch (_: Throwable) { IdempotencyKeyStore6437.InsertResult.NEW }
            if (reserve == IdempotencyKeyStore6437.InsertResult.DUPLICATE) {
                try { PipelineHealthCollector.labelInc("EXECUTOR_MIRROR_BUY_DUP_6442") } catch (_: Throwable) {}
                return
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
                paperMode = paperMode,
            )
            buysMirrored.incrementAndGet()
            try { PipelineHealthCollector.labelInc("EXECUTOR_MIRROR_BUY_$result".take(60)) } catch (_: Throwable) {}
        } catch (t: Throwable) {
            mirrorFailures.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "EXECUTOR_MIRROR_BUY_FAIL_6442",
                    "mint=${mint.take(10)} err=${t.message?.take(80)}",
                )
            } catch (_: Throwable) {}
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
    ) {
        try {
            val positionId = positionIdOf(mint)
            CanonicalPositionAuthority6441.promotePendingToOpen(
                positionId = positionId,
                actualQtyRaw = actualQtyRaw,
                actualEntryCostSol = actualCostSol,
                actualFeesSol = actualFeesSol,
                tokenDecimals = tokenDecimals,
                paperMode = paperMode,
            )
        } catch (t: Throwable) {
            mirrorFailures.incrementAndGet()
        }
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
            val result = CanonicalPositionAuthority6441.partialSell(
                idempotencyKey = idem,
                positionId = positionId,
                soldQtyRaw = soldQtyRaw,
                proceedsSol = proceedsSol,
                soldCostBasisSol = soldCostBasisSol,
                feesSol = feesSol,
                paperMode = paperMode,
            )
            sellsMirrored.incrementAndGet()
            try { PipelineHealthCollector.labelInc("EXECUTOR_MIRROR_SELL_$result".take(60)) } catch (_: Throwable) {}
        } catch (t: Throwable) {
            mirrorFailures.incrementAndGet()
        }
    }

    fun statusLine(): String =
        "buysMirrored=${buysMirrored.get()} sellsMirrored=${sellsMirrored.get()} " +
            "failures=${mirrorFailures.get()} runId=$runIdHash"
}
