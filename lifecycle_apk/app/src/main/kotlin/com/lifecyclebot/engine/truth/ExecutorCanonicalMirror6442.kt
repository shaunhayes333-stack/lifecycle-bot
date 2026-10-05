package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.SlotHealthGate
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
    // V5.0.6686 — canonical identity is mode + mint. PAPER shadow trades and
    // LIVE trades may legally coexist for the same mint; a mint-only cache
    // can cross-promote or cross-close real capital.
    private val activePositionIdByModeMint = ConcurrentHashMap<String, String>()
    private val lastClosedPositionIdByModeMint = ConcurrentHashMap<String, String>()

    private fun modeName(paperMode: Boolean): String = if (paperMode) "paper" else "live"
    private fun modeKey(mint: String, paperMode: Boolean): String =
        "${modeName(paperMode)}|${canonicalMint(mint)}"

    fun canonicalMint(mint: String): String = mint.trim()

    /**
     * Canonical positionId derivation: "$mint#$runIdShort". Stable per
     * run — matches the operator's mandate §3 "runId + positionId +
     * side" idempotency-key structure.
     */
    fun positionIdOf(mint: String, paperMode: Boolean? = null): String {
        val cm = canonicalMint(mint)
        val opens = try {
            CanonicalPositionAuthority6441.openPositions().filter { it.mint == cm }
        } catch (_: Throwable) {
            emptyList()
        }

        if (paperMode != null) {
            val mode = modeName(paperMode)
            val key = modeKey(cm, paperMode)
            val restored = opens.firstOrNull { it.mode.equals(mode, ignoreCase = true) }?.positionId
            if (!restored.isNullOrBlank()) activePositionIdByModeMint[key] = restored
            return activePositionIdByModeMint[key]
                ?: lastClosedPositionIdByModeMint[key]
                ?: "${mode.uppercase()}:$cm:$runIdHash"
        }

        // Compatibility for older callers that do not carry mode yet. If one
        // canonical position exists, it is unambiguous. If both modes exist,
        // prefer LIVE and emit a forensic marker rather than silently selecting
        // a PAPER shadow position for a real-capital path.
        if (opens.size == 1) {
            val only = opens.first()
            val isPaper = only.mode.equals("paper", ignoreCase = true)
            activePositionIdByModeMint[modeKey(cm, isPaper)] = only.positionId
            return only.positionId
        }
        if (opens.size > 1) {
            val live = opens.firstOrNull { it.mode.equals("live", ignoreCase = true) }
            if (live != null) {
                activePositionIdByModeMint[modeKey(cm, false)] = live.positionId
                try {
                    ForensicLogger.lifecycle(
                        "CANONICAL_POSITION_MODE_AMBIGUITY_6686",
                        "mint=${cm.take(10)} opens=${opens.size} action=compat_prefer_live",
                    )
                    PipelineHealthCollector.labelInc("CANONICAL_POSITION_MODE_AMBIGUITY_6686")
                } catch (_: Throwable) {}
                return live.positionId
            }
            return opens.first().positionId
        }

        val liveKey = modeKey(cm, false)
        val paperKey = modeKey(cm, true)
        return activePositionIdByModeMint[liveKey]
            ?: activePositionIdByModeMint[paperKey]
            ?: lastClosedPositionIdByModeMint[liveKey]
            ?: lastClosedPositionIdByModeMint[paperKey]
            ?: "PAPER:$cm:$runIdHash"
    }

    private fun allocatePositionId(mint: String, paperMode: Boolean): String {
        val cm = canonicalMint(mint)
        val key = modeKey(cm, paperMode)
        val existing = activePositionIdByModeMint[key]
        if (!existing.isNullOrBlank()) {
            // V5.0.7807 — STALE ACTIVE POSITION-ID. Field Manual L252: held
            // positions reconcile against authoritative fills. The active map
            // is cleared only by mirrorSell/abortBuy6485; a position closed or
            // quarantined by any other canonical path left its id here, and the
            // next BUY reused it — openPosition() then saw the already-used
            // idempotency key (DUPLICATE, no PENDING_ENTRY row) and the landed
            // fill was later refused (LIVE_BUY_CANONICAL_COMMIT_REJECTED_6486).
            // Reuse only an id whose canonical row is still live-cycle.
            val lc7807 = try { CanonicalPositionAuthority6441.getPosition(existing)?.lifecycle } catch (_: Throwable) { null }
            if (lc7807 == CanonicalPositionAuthority6441.Lifecycle.PENDING_ENTRY ||
                lc7807 == CanonicalPositionAuthority6441.Lifecycle.OPEN ||
                lc7807 == CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED
            ) return existing
            activePositionIdByModeMint.remove(key, existing)
            try {
                PipelineHealthCollector.labelInc("CANONICAL_STALE_ACTIVE_POSITION_ID_REALLOCATED_7807")
                ForensicLogger.lifecycle(
                    "CANONICAL_STALE_ACTIVE_POSITION_ID_REALLOCATED_7807",
                    "mode=${modeName(paperMode)} mint=${cm.take(10)} staleId=${existing.take(28)} lifecycle=${lc7807?.name ?: "ABSENT"}",
                )
            } catch (_: Throwable) {}
        }
        val id = "${if (paperMode) "PAPER" else "LIVE"}:$cm:$runIdHash:${positionSeq.incrementAndGet()}"
        activePositionIdByModeMint[key] = id
        return id
    }

    fun buyIdempotencyKey(positionId: String): String = "BUY:$runIdHash:$positionId"
    fun sellIdempotencyKey(positionId: String, generation: Long): String =
        "SELL:$runIdHash:$positionId:$generation"

    /**
     * Mirror a BUY attempt/reservation. Called at buy attempt BEFORE fill —
     * qtyRaw is not known yet, so a PENDING_ENTRY row is created. On fill,
     * call [mirrorBuyFill] to promote to OPEN with the actual qty + cost.
     *
     * V5.0.6689 — this function is synchronized because the Meme turnover
     * ceiling has to cover OPEN + PENDING_ENTRY as one atomic admission domain.
     * Without the writer lock, multiple specialist lanes can all observe 23
     * opens and concurrently manufacture several pending entries past the cap.
     */
    @Synchronized
    fun mirrorBuyAttempt(
        mint: String,
        symbol: String,
        lane: String,
        estimatedCostSol: Double,
        estimatedFeesSol: Double,
        paperMode: Boolean,
        tokenDecimals: Int = 9,
        attemptId: String = "",
        entryPriceUsd: Double = 0.0,
        entryPriceSource: String = "",
        entryPoolAddress: String = "",
        entryDex: String = "",
        quantityScale: Int = tokenDecimals,
    ): Boolean {
        return try {
            if (SlotHealthGate.isMemeLane6689(lane)) {
                val mode6689 = modeName(paperMode)
                val open6689 = SlotHealthGate.canonicalMemeOpenCount6689(mode6689).coerceAtLeast(0)
                val pending6689 = try {
                    CanonicalPositionAuthority6441.pendingEntryPositions6461().count { p ->
                        p.mode.equals(mode6689, ignoreCase = true) && SlotHealthGate.isMemeLane6689(p.lane)
                    }
                } catch (_: Throwable) { 0 }
                val totalReserved6689 = open6689 + pending6689
                if (totalReserved6689 >= SlotHealthGate.memeTurnoverAbsoluteCap6689()) {
                    try {
                        PipelineHealthCollector.labelInc("MEME_CANONICAL_ADMISSION_CAP_6689")
                        PipelineHealthCollector.labelInc(
                            if (paperMode) "MEME_CANONICAL_ADMISSION_CAP_PAPER_6689"
                            else "MEME_CANONICAL_ADMISSION_CAP_LIVE_6689",
                        )
                        ForensicLogger.lifecycle(
                            "MEME_CANONICAL_ADMISSION_CAP_6689",
                            "mode=${mode6689.uppercase()} mint=${mint.take(10)} lane=$lane open=$open6689 pending=$pending6689 " +
                                "reserved=$totalReserved6689 cap=${SlotHealthGate.memeTurnoverAbsoluteCap6689()} " +
                                "action=refuse_new_pending_entry_until_confirmed_exit",
                        )
                    } catch (_: Throwable) {}
                    return false
                }
            }

            val positionId = allocatePositionId(mint, paperMode)
            val idem = buyIdempotencyKey(positionId)
            // Reserve in the SQLite idempotency store first so a mid-tx restart
            // cannot resubmit; if the reserve returns DUPLICATE, skip the mirror.
            val reserve = try {
                IdempotencyKeyStore6437.checkAndReserve(idem, if (paperMode) "PAPER" else "LIVE", "buy_attempt:$attemptId")
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
                tokenDecimals = tokenDecimals,     // actual mint metadata; may be -1 for PAPER
                quantityScale = quantityScale,       // decimal-neutral accounting representation
                feesSol = estimatedFeesSol,
                paperMode = paperMode,
                entryPriceUsd = entryPriceUsd,
                entryPriceSource = entryPriceSource,
                entryPoolAddress = entryPoolAddress,
                entryDex = entryDex,
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
        quantityScale: Int = tokenDecimals,
        actualEntryPriceUsd: Double = 0.0,
        actualEntryPriceSource: String = "",
        actualEntryPoolAddress: String = "",
        actualEntryDex: String = "",
        recoveryLane: String = "",
        recoverySymbol: String = "",
    ): Boolean {
        return try {
            val requestedPositionId7807 = positionIdOf(mint, paperMode)
            val promoted7807 = CanonicalPositionAuthority6441.promotePendingToOpen(
                positionId = requestedPositionId7807,
                actualQtyRaw = actualQtyRaw,
                actualEntryCostSol = actualCostSol,
                actualFeesSol = actualFeesSol,
                tokenDecimals = tokenDecimals,
                paperMode = paperMode,
                quantityScale = quantityScale,
                actualEntryPriceUsd = actualEntryPriceUsd,
                actualEntryPriceSource = actualEntryPriceSource,
                actualEntryPoolAddress = actualEntryPoolAddress,
                actualEntryDex = actualEntryDex,
            )
            // V5.0.7807 — a LIVE fill that the wallet/finality proof says landed
            // must not stay uncommitted because the PENDING_ENTRY reservation was
            // lost (TTL-quarantined, never created, or a stale closed id). Field
            // Manual L39/L252: confirm actual fills and reconcile held positions
            // against authoritative fills. PAPER keeps the strict refusal.
            val recovered7807 = if (!paperMode &&
                (promoted7807 == CanonicalPositionAuthority6441.MutateResult.UNKNOWN_POSITION ||
                    promoted7807 == CanonicalPositionAuthority6441.MutateResult.LIFECYCLE_FORBIDDEN)
            ) recoverLiveBuyCommit7807(
                mint = mint, staleId = requestedPositionId7807, actualQtyRaw = actualQtyRaw,
                actualCostSol = actualCostSol, actualFeesSol = actualFeesSol, tokenDecimals = tokenDecimals,
                quantityScale = quantityScale, actualEntryPriceUsd = actualEntryPriceUsd,
                actualEntryPriceSource = actualEntryPriceSource, actualEntryPoolAddress = actualEntryPoolAddress,
                actualEntryDex = actualEntryDex, recoveryLane = recoveryLane, recoverySymbol = recoverySymbol,
                originalResult = promoted7807,
            ) else null
            val positionId = recovered7807?.second ?: requestedPositionId7807
            val result = recovered7807?.first ?: promoted7807
            if (result == CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                try { IdempotencyKeyStore6437.markTerminal(buyIdempotencyKey(positionId), "BUY_CONFIRMED") } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("CANONICAL_BUY_CONFIRMED_OPEN_6448") } catch (_: Throwable) {}
                // V5.0.6742 §PILLAR_7_WIRE — the canonical BUY commit
                // is the real production entry checkpoint for round-trip
                // verification. Not a manual test helper. Lane is looked
                // up from LaneAttributionLedger6427 (stamped at pending
                // registration by registerPendingBuy) — mirrorBuyFill
                // does not accept a lane parameter directly.
                try {
                    val laneAt = try { LaneAttributionLedger6427.getEntryLane(positionId) } catch (_: Throwable) { null } ?: ""
                    CanonicalRoundTripReconciler6738.record(
                        positionId = positionId, stage = CanonicalRoundTripReconciler6738.Stage.BUY_COMMITTED,
                        lane = laneAt, mode = if (paperMode) "PAPER" else "LIVE",
                    )
                } catch (_: Throwable) {}
            }
            result == CanonicalPositionAuthority6441.MutateResult.APPLIED
        } catch (t: Throwable) {
            mirrorFailures.incrementAndGet()
            false
        }
    }

    /**
     * V5.0.7807 — LIVE BUY COMMIT RECOVERY (Field Manual L252, L416).
     * Order: (1) a live PENDING_ENTRY for this mint under another id is
     * promoted; (2) a TTL/basis-quarantined live row with no realised sell
     * economics is recovered through the narrow 7454 surface; (3) otherwise a
     * fresh canonical OPEN is sealed directly from the finalized proof (exact
     * raw qty, actual cost/fees, real lane). An existing live OPEN with qty for
     * the mint is never duplicated (openPosition's same-mode-mint guard).
     * Returns (result, positionId actually committed).
     */
    @Synchronized
    private fun recoverLiveBuyCommit7807(
        mint: String,
        staleId: String,
        actualQtyRaw: BigInteger,
        actualCostSol: Double,
        actualFeesSol: Double,
        tokenDecimals: Int,
        quantityScale: Int,
        actualEntryPriceUsd: Double,
        actualEntryPriceSource: String,
        actualEntryPoolAddress: String,
        actualEntryDex: String,
        recoveryLane: String,
        recoverySymbol: String,
        originalResult: CanonicalPositionAuthority6441.MutateResult,
    ): Pair<CanonicalPositionAuthority6441.MutateResult, String> {
        val cm = canonicalMint(mint)
        val key = modeKey(cm, false)
        if (actualQtyRaw.signum() <= 0 || !actualCostSol.isFinite() || actualCostSol < 0.0) {
            return originalResult to staleId
        }
        val lane7807 = recoveryLane.trim().ifBlank {
            (try { LaneAttributionLedger6427.getEntryLane(staleId) } catch (_: Throwable) { null }).orEmpty()
        }.ifBlank { "LIVE_STANDARD" }
        fun note(path: String, id: String, r: CanonicalPositionAuthority6441.MutateResult) {
            try {
                PipelineHealthCollector.labelInc("LIVE_BUY_COMMIT_RECOVERY_${path}_${r.name}_7807".take(80))
                ForensicLogger.lifecycle(
                    "LIVE_BUY_COMMIT_RECOVERY_7807",
                    "mint=${cm.take(10)} path=$path staleId=${staleId.take(28)} committedId=${id.take(28)} " +
                        "original=${originalResult.name} result=${r.name} qtyRaw=$actualQtyRaw cost=$actualCostSol lane=$lane7807",
                )
            } catch (_: Throwable) {}
        }
        // (1) live pending reservation under a different id.
        val pending7807 = try {
            CanonicalPositionAuthority6441.pendingEntryPositions6461().firstOrNull {
                it.mode.equals("live", true) && it.mint == cm && it.positionId != staleId
            }
        } catch (_: Throwable) { null }
        if (pending7807 != null) {
            val r = CanonicalPositionAuthority6441.promotePendingToOpen(
                positionId = pending7807.positionId, actualQtyRaw = actualQtyRaw,
                actualEntryCostSol = actualCostSol, actualFeesSol = actualFeesSol,
                tokenDecimals = tokenDecimals, paperMode = false, quantityScale = quantityScale,
                actualEntryPriceUsd = actualEntryPriceUsd, actualEntryPriceSource = actualEntryPriceSource,
                actualEntryPoolAddress = actualEntryPoolAddress, actualEntryDex = actualEntryDex,
            )
            note("PENDING", pending7807.positionId, r)
            if (r == CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                activePositionIdByModeMint[key] = pending7807.positionId
                return r to pending7807.positionId
            }
        }
        // (2) recoverable quarantined live row (prefer the requested id).
        val quarantined7807 = try {
            CanonicalPositionAuthority6441.quarantinedLivePositions7454(cm)
                .filter {
                    it.quarantineReason == "PENDING_ENTRY_TTL_CANCELLED_6461" &&
                        it.soldCostBasisSol <= 1e-12 && it.realizedProceedsSol <= 1e-12
                }
                .sortedWith(compareByDescending<CanonicalPositionAuthority6441.Position> { it.positionId == staleId }
                    .thenByDescending { it.lastMutationMs })
                .firstOrNull()
        } catch (_: Throwable) { null }
        if (quarantined7807 != null && actualEntryPriceUsd.isFinite() && actualEntryPriceUsd > 0.0 && actualCostSol > 0.0) {
            val r = CanonicalPositionAuthority6441.recoverQuarantinedLivePosition7454(
                positionId = quarantined7807.positionId, actualQtyRaw = actualQtyRaw,
                actualEntryCostSol = actualCostSol, tokenDecimals = tokenDecimals,
                quantityScale = quantityScale, actualEntryPriceUsd = actualEntryPriceUsd,
                actualEntryPriceSource = actualEntryPriceSource.ifBlank { "LIVE_BUY_PROOF_RECOVERY_7807" },
                recoveredLane = recoveryLane.trim().ifBlank { quarantined7807.lane.ifBlank { lane7807 } },
                recoveredAssetClass = quarantined7807.assetClass.takeIf { it != AssetClass.UNKNOWN } ?: AssetClass.SOLANA_TOKEN,
                actualEntryPoolAddress = actualEntryPoolAddress, actualEntryDex = actualEntryDex,
            )
            note("QUARANTINE", quarantined7807.positionId, r)
            if (r == CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                activePositionIdByModeMint[key] = quarantined7807.positionId
                return r to quarantined7807.positionId
            }
        }
        // (3) seal a fresh canonical OPEN from the finalized buy proof.
        val freshId = allocatePositionId(cm, false)
        val r = CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = buyIdempotencyKey(freshId),
            positionId = freshId,
            mint = cm,
            symbol = recoverySymbol.ifBlank { cm.take(6) },
            lane = lane7807,
            runId = runIdHash,
            entryCostSol = actualCostSol,
            openedQtyRaw = actualQtyRaw,
            tokenDecimals = tokenDecimals,
            feesSol = actualFeesSol,
            paperMode = false,
            entryPriceUsd = actualEntryPriceUsd,
            entryPriceSource = actualEntryPriceSource.ifBlank { "LIVE_BUY_PROOF_RECOVERY_7807" },
            entryPoolAddress = actualEntryPoolAddress,
            entryDex = actualEntryDex,
            quantityScale = quantityScale,
        )
        note("FRESH_OPEN", freshId, r)
        if (r == CanonicalPositionAuthority6441.MutateResult.APPLIED) {
            try { LaneAttributionLedger6427.recordEntry(freshId, lane7807, strategy = lane7807, profile = lane7807) } catch (_: Throwable) {}
            try { PositionStateLedger6427.registerOpen(cm) } catch (_: Throwable) {}
            return r to freshId
        }
        if (CanonicalPositionAuthority6441.getPosition(freshId) == null) activePositionIdByModeMint.remove(key, freshId)
        return r to staleId
    }

    fun abortBuy6485(mint: String, reason: String, paperMode: Boolean? = null) {
        val cm = canonicalMint(mint)
        val key = if (paperMode != null) {
            modeKey(cm, paperMode)
        } else {
            val matches = activePositionIdByModeMint.keys.filter { it.endsWith("|$cm") }
            if (matches.size != 1) {
                if (matches.size > 1) {
                    try {
                        ForensicLogger.lifecycle(
                            "CANONICAL_ABORT_MODE_AMBIGUITY_6686",
                            "mint=${cm.take(10)} activeModes=${matches.size} action=no_cross_mode_abort",
                        )
                        PipelineHealthCollector.labelInc("CANONICAL_ABORT_MODE_AMBIGUITY_6686")
                    } catch (_: Throwable) {}
                }
                return
            }
            matches.first()
        }
        val positionId = activePositionIdByModeMint.remove(key) ?: return
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
    ): Boolean {
        return try {
            val positionId = positionIdOf(mint, paperMode)
            val idem = sellIdempotencyKey(positionId, generation)
            val reserve = try {
                IdempotencyKeyStore6437.checkAndReserve(idem, if (paperMode) "PAPER" else "LIVE", "sell")
            } catch (_: Throwable) { IdempotencyKeyStore6437.InsertResult.NEW }
            if (reserve == IdempotencyKeyStore6437.InsertResult.DUPLICATE) {
                try { PipelineHealthCollector.labelInc("EXECUTOR_MIRROR_SELL_DUP_6442") } catch (_: Throwable) {}
                return false
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
                    // V5.0.6732 §EXIT_TELEMETRY_STAMPER — 6731 dump showed
                    // 33 real paper sells but exit-gate allow/block=0/0 and
                    // every StopLatencyClasses6464 bucket at n=0. The classes
                    // existed but nothing invoked `record`. Wire the terminal
                    // sell here so per-class latency and terminal counters
                    // actually populate. Idempotency reservation above guards
                    // against duplicate stamping.
                    try { ExitTelemetryStamper6732.noteExitCompleted(positionId, reason) } catch (_: Throwable) {}
                    // V5.0.6742 §PILLAR_7_WIRE — canonical SELL_CONFIRMED
                    // is the real production terminal checkpoint. Partial
                    // sells route through the else branch below with the
                    // dedicated PARTIAL_SELL stage marker (they must NOT
                    // count as a completed round trip per directive).
                    try {
                        CanonicalRoundTripReconciler6738.record(
                            positionId = positionId, stage = CanonicalRoundTripReconciler6738.Stage.SELL_COMMITTED,
                            lane = lane.ifBlank { posAfter.lane }, mode = if (paperMode) "PAPER" else "LIVE",
                        )
                    } catch (_: Throwable) {}
                    // V5.0.6651 — reward purity is delivered only after the
                    // exact canonical economic event reaches COMMITTED. The
                    // mirror runs before journal durability and must not race it.
                    val modeMintKey6686 = modeKey(mint, paperMode)
                    lastClosedPositionIdByModeMint[modeMintKey6686] = positionId
                    activePositionIdByModeMint.remove(modeMintKey6686, positionId)
                    try { PipelineHealthCollector.labelInc("CANONICAL_SELL_CONFIRMED_CLOSED_6448") } catch (_: Throwable) {}
                } else {
                    try { PositionStateLedger6427.markPartial(canonicalMint(mint)) } catch (_: Throwable) {}
                    try { PipelineHealthCollector.labelInc("CANONICAL_PARTIAL_SELL_CONFIRMED_6448") } catch (_: Throwable) {}
                    // V5.0.6742 §PILLAR_7_WIRE — partial sells stamp
                    // EXIT_DECIDED (exit-decision recorded) but must NOT
                    // stamp SELL_COMMITTED — directive: "Partial sells
                    // must not count as completed round trips."
                    try {
                        CanonicalRoundTripReconciler6738.record(
                            positionId = positionId, stage = CanonicalRoundTripReconciler6738.Stage.EXIT_DECIDED,
                            lane = lane.ifBlank { posAfter.lane }, mode = if (paperMode) "PAPER" else "LIVE",
                        )
                    } catch (_: Throwable) {}
                }
            }
            try { PipelineHealthCollector.labelInc("EXECUTOR_MIRROR_SELL_$result".take(60)) } catch (_: Throwable) {}
            result == CanonicalPositionAuthority6441.MutateResult.APPLIED
        } catch (t: Throwable) {
            mirrorFailures.incrementAndGet()
            false
        }
    }

    fun statusLine(): String =
        "buysMirrored=${buysMirrored.get()} sellsMirrored=${sellsMirrored.get()} " +
            "failures=${mirrorFailures.get()} runId=$runIdHash"
}
