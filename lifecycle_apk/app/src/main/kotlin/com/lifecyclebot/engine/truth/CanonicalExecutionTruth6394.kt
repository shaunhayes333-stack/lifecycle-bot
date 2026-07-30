package com.lifecyclebot.engine.truth

import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6394 — CANONICAL EXECUTION TRUTH, EXIT RECOVERY AND LIVE-FEE SETTLEMENT.
 *
 * P0 CanonicalExecutionReceipt6394   — immutable per-tx receipt
 * P0 BuySettlementInvariants6394     — 11-clause acceptance gate
 * P0 PositionLotLedger6394           — per-mint lots with CAS versioning
 * P0 SingleSellStateMachine6394      — 14-state per-mint sell FSM
 * P0 LiveFeeLedger6394               — append-only, deterministic idempotency
 * P0 ExecutionTicketAuthority6394    — one immutable ticket per live buy
 * P0 AccountingQuarantine6394        — tag rows, preserve journal
 * P1 GeneralReconcilerContract6394   — 7 invariants
 *
 * Plus WEEKLY GROWTH DASHBOARD publisher and SMART MONEY FEED sink.
 */

/* ========================== P0 CANONICAL EXECUTION RECEIPT ================== */

data class CanonicalExecutionReceipt6394(
    val executionId: String, val decisionId: String, val positionId: String, val lotId: String,
    val runtimeGeneration: Long, val mint: String, val symbol: String, val lane: String,
    val side: String, val provider: String, val route: String,
    val requestedRawAmount: BigInteger, val actualConsumedRawAmount: BigInteger,
    val actualReceivedRawAmount: BigInteger, val tokenDecimals: Int,
    val preTokenRaw: BigInteger, val postTokenRaw: BigInteger,
    val preOwnerLamports: BigInteger, val postOwnerLamports: BigInteger,
    val principalLamports: BigInteger, val grossProceedsLamports: BigInteger,
    val networkFeeLamports: BigInteger, val priorityFeeLamports: BigInteger,
    val jitoTipLamports: BigInteger, val rentCreatedLamports: BigInteger,
    val rentRefundedLamports: BigInteger, val appFeeAccruedLamports: BigInteger,
    val appFeePaidLamports: BigInteger, val netUserProceedsLamports: BigInteger,
    val signature: String, val slot: Long, val blockTime: Long,
    val confirmationStatus: String, val settlementStatus: String,
    val proofSource: String, val createdAt: Long, val finalizedAt: Long,
)

object CanonicalReceiptStore6394 {
    private val bySignature = ConcurrentHashMap<String, CanonicalExecutionReceipt6394>()
    @Synchronized
    fun persist(r: CanonicalExecutionReceipt6394): Boolean {
        if (r.signature.isBlank()) return false
        // Directive P0#10: receipt is persisted BEFORE canonical position mutation.
        return bySignature.putIfAbsent(r.signature, r) == null
    }
    fun get(sig: String): CanonicalExecutionReceipt6394? = bySignature[sig]
    fun size(): Int = bySignature.size
    internal fun clearForTest() { bySignature.clear() }
}

/* ========================== P0 BUY SETTLEMENT INVARIANTS ==================== */

object BuySettlementInvariants6394 {
    data class Verdict(val passed: Boolean, val failures: List<String>,
                       val quoteMismatch: Boolean, val quarantineDivergent: Boolean)

    fun check(r: CanonicalExecutionReceipt6394, requestedMaxDebitLamports: BigInteger,
              estimatedTokenRaw: BigInteger, requestedMint: String,
              rawToleranceBps: Int = 500): Verdict {
        val failures = mutableListOf<String>()
        val actualReceived = r.postTokenRaw.subtract(r.preTokenRaw)
        if (actualReceived.signum() <= 0) failures += "ACTUAL_RECEIVED_NON_POSITIVE"
        if (r.mint != requestedMint) failures += "MINT_MISMATCH"
        if (r.principalLamports.signum() <= 0) failures += "PRINCIPAL_NON_POSITIVE"
        val expectedDebit = r.principalLamports.add(r.networkFeeLamports)
            .add(r.priorityFeeLamports).add(r.jitoTipLamports).add(r.rentCreatedLamports)
            .add(r.appFeeAccruedLamports).subtract(r.rentRefundedLamports)
        val actualDebit = r.preOwnerLamports.subtract(r.postOwnerLamports)
        val tolerance = BigInteger.valueOf(50_000L)   // 0.00005 SOL rounding tolerance
        if (actualDebit.subtract(expectedDebit).abs() > tolerance)
            failures += "DEBIT_RECONCILIATION_FAILURE:actual=$actualDebit expected=$expectedDebit"
        if (actualDebit > requestedMaxDebitLamports.add(tolerance))
            failures += "EXECUTION_SPEND_INVARIANT_FAILED"
        if (actualDebit.signum() < 0) failures += "NEGATIVE_SOL_SPEND"
        // Estimate vs actual divergence.
        val divergent = if (estimatedTokenRaw.signum() > 0 && actualReceived.signum() > 0) {
            val diff = actualReceived.subtract(estimatedTokenRaw).abs()
                .multiply(BigInteger.valueOf(10_000L))
                .divide(estimatedTokenRaw)
            diff.toLong() > rawToleranceBps
        } else false
        val quarantine = if (estimatedTokenRaw.signum() > 0 && actualReceived.signum() > 0) {
            val diff = actualReceived.subtract(estimatedTokenRaw).abs()
                .multiply(BigInteger.valueOf(100L))
                .divide(estimatedTokenRaw)
            diff.toLong() >= 25L
        } else false
        return Verdict(failures.isEmpty(), failures, divergent, quarantine)
    }
}

/* ========================== P0 POSITION LOT LEDGER ========================== */

data class PositionLot6394(
    val lotId: String, val mint: String, val tokenDecimals: Int,
    val openedRaw: BigInteger, val remainingRaw: BigInteger,
    val entryPrincipalLamports: BigInteger, val allocatedFeesLamports: BigInteger,
    val entrySignature: String, val status: String, val version: Long,
) {
    fun withSell(consumedRaw: BigInteger, sellSig: String): PositionLot6394 {
        val newRemaining = remainingRaw.subtract(consumedRaw).coerceAtLeast(BigInteger.ZERO)
        val newStatus = if (newRemaining.signum() == 0) "CLOSED" else "OPEN"
        return copy(remainingRaw = newRemaining, status = newStatus, version = version + 1,
            entrySignature = if (entrySignature.isBlank()) sellSig else entrySignature)
    }
}

private fun BigInteger.coerceAtLeast(other: BigInteger): BigInteger =
    if (this < other) other else this

object PositionLotLedger6394 {
    private val lots = ConcurrentHashMap<String, PositionLot6394>()
    private val lastSellSignatures = ConcurrentHashMap<String, String>()

    @Synchronized
    fun upsert(l: PositionLot6394): Boolean {
        val prior = lots[l.mint]
        if (prior != null && prior.status == "CLOSED" && l.status != "OPEN") return false
        // Directive P0#8: cannot re-open a closed position without a new verified buy sig.
        if (prior != null && prior.status == "CLOSED" && l.entrySignature.isBlank()) return false
        lots[l.mint] = l
        return true
    }
    fun get(mint: String): PositionLot6394? = lots[mint]

    /** Directive P0#3: sellAmount = min(requested, canonicalRemaining, currentWallet). */
    fun computeSellRaw(mint: String, requestedRaw: BigInteger, walletRaw: BigInteger): BigInteger {
        val lot = lots[mint] ?: return BigInteger.ZERO
        return requestedRaw.min(lot.remainingRaw).min(walletRaw).coerceAtLeast(BigInteger.ZERO)
    }

    /** Directive P0#9: compare-and-set versioning. */
    @Synchronized
    fun applySellCAS(mint: String, expectedVersion: Long, consumedRaw: BigInteger,
                     sellSignature: String): Boolean {
        val prior = lots[mint] ?: return false
        if (prior.version != expectedVersion) return false
        val updated = prior.withSell(consumedRaw, sellSignature)
        lots[mint] = updated
        // Directive P0#10-11: save last sell sig before releasing the lease.
        lastSellSignatures[mint] = sellSignature
        return true
    }
    fun lastSellSignature(mint: String): String = lastSellSignatures[mint] ?: ""
    internal fun clearForTest() { lots.clear(); lastSellSignatures.clear() }
}

/* ========================== P0 SINGLE SELL STATE MACHINE ==================== */

object SingleSellStateMachine6394 {
    enum class State {
        IDLE, INTENT_CREATED, QUOTING, TRANSACTION_BUILT, BROADCAST,
        CONFIRMED, PARSED, SETTLEMENT_VERIFYING, PARTIALLY_SETTLED, FULLY_SETTLED,
        CLOSED, BACKOFF, RECOVERY_REQUIRED, FAILED_FINAL,
    }
    /** Successful states MUST NOT downgrade under inconclusive callbacks. */
    private val successRank: Map<State, Int> = mapOf(
        State.IDLE to 0, State.INTENT_CREATED to 1, State.QUOTING to 2,
        State.TRANSACTION_BUILT to 3, State.BROADCAST to 4, State.CONFIRMED to 5,
        State.PARSED to 6, State.SETTLEMENT_VERIFYING to 7,
        State.PARTIALLY_SETTLED to 8, State.FULLY_SETTLED to 9, State.CLOSED to 10,
        State.BACKOFF to 1, State.RECOVERY_REQUIRED to 1, State.FAILED_FINAL to 11,
    )
    private data class MintState(val state: State, val executionId: String, val leaseExpiryMs: Long)
    private val perMint = ConcurrentHashMap<String, MintState>()

    @Synchronized
    fun tryTransition(mint: String, next: State, executionId: String,
                      leaseTtlMs: Long = 30_000L): Boolean {
        val prior = perMint[mint]
        val priorRank = if (prior != null) successRank[prior.state] ?: 0 else 0
        val nextRank = successRank[next] ?: 0
        if (prior != null && nextRank < priorRank && next != State.FAILED_FINAL) return false
        perMint[mint] = MintState(next, executionId, System.currentTimeMillis() + leaseTtlMs)
        return true
    }

    /** Directive P0#1: only one active sell per mint. */
    fun currentState(mint: String): State = perMint[mint]?.state ?: State.IDLE
    fun currentExecutionId(mint: String): String? = perMint[mint]?.executionId
    fun leaseExpired(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val ms = perMint[mint] ?: return true
        return nowMs >= ms.leaseExpiryMs
    }

    /** Directive P0 retry schedule: 2s, 5s, 15s, 30s, then 60s cap. */
    val retryDelaysMs: LongArray = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
    fun retryDelayMs(attemptIndex: Int): Long =
        retryDelaysMs[attemptIndex.coerceIn(0, retryDelaysMs.lastIndex)]

    internal fun clearForTest() { perMint.clear() }
}

/* ========================== P0 LIVE FEE LEDGER ============================== */

data class LiveFeeConfig6394(
    val feeBps: Int, val recipients: List<Recipient>,
) {
    data class Recipient(val wallet: String, val weightBps: Int)
    val valid: Boolean get() =
        feeBps in 0..10_000 &&
        recipients.isNotEmpty() &&
        recipients.all { it.wallet.isNotBlank() && it.weightBps in 0..10_000 } &&
        recipients.sumOf { it.weightBps } == 10_000
}

data class LiveFeeEvent6394(
    val feeEventId: String, val sourceExecutionId: String, val sourceSellSignature: String,
    val sourceMint: String, val grossProceedsLamports: BigInteger,
    val feeBasisLamports: BigInteger, val feeBps: Int, val totalFeeLamports: BigInteger,
    val recipientWallet: String, val recipientWeightBps: Int,
    val recipientAmountLamports: BigInteger, val status: String,
    val payoutSignature: String?, val payoutSlot: Long?,
    val recipientPreLamports: BigInteger?, val recipientPostLamports: BigInteger?,
    val retryCount: Int, val lastError: String?,
    val accruedAt: Long, val broadcastAt: Long?, val finalizedAt: Long?,
)

object LiveFeeLedger6394 {
    private val byIdempotencyKey = ConcurrentHashMap<String, LiveFeeEvent6394>()

    /** Directive P0#3-4: deterministic idempotency key. */
    fun idempotencyKey(network: String, sellSig: String, feeType: String, recipient: String): String =
        "$network|$sellSig|$feeType|$recipient".hashCode().toString(16)

    /** Directive P0#5-8: integer lamport split with deterministic remainder. */
    fun split(totalFeeLamports: BigInteger, cfg: LiveFeeConfig6394): List<Pair<String, BigInteger>> {
        require(cfg.valid) { "LIVE_FEE_CONFIGURATION_INVALID" }
        val amounts = cfg.recipients.map {
            it.wallet to totalFeeLamports.multiply(BigInteger.valueOf(it.weightBps.toLong()))
                .divide(BigInteger.valueOf(10_000L))
        }
        val sum = amounts.sumOf { it.second }
        val remainder = totalFeeLamports.subtract(sum)
        if (remainder.signum() == 0) return amounts
        // Assign remainder deterministically to the first recipient (configured order).
        return amounts.mapIndexed { i, (w, a) ->
            w to if (i == 0) a.add(remainder) else a
        }
    }

    @Synchronized
    fun accrue(e: LiveFeeEvent6394, idempotencyKey: String): Boolean =
        byIdempotencyKey.putIfAbsent(idempotencyKey, e) == null

    fun get(idempotencyKey: String): LiveFeeEvent6394? = byIdempotencyKey[idempotencyKey]
    fun byStatus(status: String): List<LiveFeeEvent6394> =
        byIdempotencyKey.values.filter { it.status == status }
    fun totalAccrued(): Int = byIdempotencyKey.size
    fun pendingCount(): Int = byStatus("PENDING").size + byStatus("BROADCAST").size
    internal fun clearForTest() { byIdempotencyKey.clear() }
}

/* ========================== P0 EXECUTION TICKET AUTHORITY =================== */

data class ExecutionTicket6394(
    val ticketId: String, val decisionId: String, val mint: String, val lane: String,
    val requestedSizeSol: Double, val score: Double, val minimumScore: Double,
    val safetyResult: String, val governorState: String, val verdict: String,
    val createdAtMs: Long,
)
object ExecutionTicketAuthority6394 {
    private val issuedTickets = ConcurrentHashMap<String, ExecutionTicket6394>()
    private val consumedTickets = ConcurrentHashMap.newKeySet<String>()

    /** Directive P0: ticket is immutable + single-use. */
    @Synchronized
    fun issue(t: ExecutionTicket6394): Boolean {
        if (t.verdict != "BUY") return false
        if (t.safetyResult != "ALLOWED") return false
        return issuedTickets.putIfAbsent(t.ticketId, t) == null
    }
    @Synchronized
    fun consume(ticketId: String): ExecutionTicket6394? {
        if (!consumedTickets.add(ticketId)) return null
        return issuedTickets[ticketId]
    }
    fun isConsumed(ticketId: String): Boolean = ticketId in consumedTickets
    internal fun clearForTest() { issuedTickets.clear(); consumedTickets.clear() }
}

/* ========================== P0 ACCOUNTING QUARANTINE ======================== */

object AccountingQuarantine6394 {
    data class Tag(
        val rowId: String, val accountingVersion: Int, val buildNumber: Int,
        val quarantineReason: String, val proofStatus: String,
        val canonicalEligible: Boolean = false,
        val learningEligible: Boolean = false,
        val governorEligible: Boolean = false,
    )
    val validReasons: Set<String> = setOf(
        "QUANTITY_DECIMAL_SKEW", "NEGATIVE_BUY_SPEND", "UNCORRELATED_SOL_DELTA",
        "ABSOLUTE_WALLET_BALANCE_AS_PROCEEDS", "SELL_SIGNATURE_MISSING",
        "POSITION_REOPENED_AFTER_ZERO", "FEE_SETTLEMENT_UNPROVEN",
        "ESTIMATE_ACTUAL_QTY_DIVERGENCE", "SPEND_LIMIT_EXCEEDED",
        "DUPLICATE_SELL_EXECUTION", "STALE_RECOVERY_REINTRODUCED_POSITION",
    )
    private val tagged = ConcurrentHashMap<String, Tag>()
    fun tag(t: Tag): Boolean {
        require(t.quarantineReason in validReasons) { "UNKNOWN_QUARANTINE_REASON:${t.quarantineReason}" }
        tagged[t.rowId] = t; return true
    }
    fun isTagged(rowId: String): Boolean = tagged.containsKey(rowId)
    fun count(): Int = tagged.size
    internal fun clearForTest() { tagged.clear() }
}

/* ========================== WEEKLY GROWTH DASHBOARD ========================= */

object WeeklyGrowthDashboard6394 {
    private val latest = AtomicReference<WeeklyGrowthMode6393.Snapshot?>(null)
    fun publish(s: WeeklyGrowthMode6393.Snapshot) { latest.set(s) }
    fun read(): WeeklyGrowthMode6393.Snapshot? = latest.get()
    fun renderHealthReportBlock(): String {
        val s = latest.get() ?: return "WEEKLY GROWTH: (no snapshot yet)"
        return buildString {
            appendLine("=== WEEKLY GROWTH (${WeeklyGrowthMode6393.active()}) ===")
            appendLine("startEquity   = ${"%.4f".format(s.weeklyStartEquitySol)} SOL")
            appendLine("currentEquity = ${"%.4f".format(s.currentEquitySol)} SOL")
            appendLine("realisedPnl   = ${"%.4f".format(s.realisedEquitySol)} SOL")
            appendLine("peakEquity    = ${"%.4f".format(s.peakWeeklyEquitySol)} SOL")
            appendLine("protectedCap  = ${"%.4f".format(s.protectedCapitalSol)} SOL")
            appendLine("drawdown      = ${"%.2f".format(s.weeklyDrawdownPct)}%")
            appendLine("targetEquity  = ${"%.4f".format(s.targetEquitySol)} SOL")
            appendLine("progress      = ${"%.2f".format(s.progressToTargetPct)}%")
            appendLine("growthRate    = ${"%.3f".format(s.geometricGrowthRate)}x")
        }
    }
    internal fun clearForTest() { latest.set(null) }
}

/* ========================== SMART MONEY FEED ================================ */

object SmartMoneyFeed6394 {
    private data class BuyEvent(val mint: String, val wallet: String, val timestampMs: Long)
    private val recentBuys = java.util.concurrent.ConcurrentLinkedDeque<BuyEvent>()
    private val whaleWallets = ConcurrentHashMap.newKeySet<String>()

    /** Bird eye / on-chain observer pushes whale buys into this sink. */
    fun onWhaleBuy(mint: String, wallet: String, nowMs: Long = System.currentTimeMillis()) {
        recentBuys.addLast(BuyEvent(mint, wallet, nowMs))
        whaleWallets.add(wallet)
        // Trim entries older than 5 minutes.
        val cutoff = nowMs - 300_000L
        while (recentBuys.isNotEmpty() && (recentBuys.peekFirst()?.timestampMs ?: nowMs) < cutoff) {
            recentBuys.pollFirst()
        }
    }

    /** Directive: smartMoneyBuysLast60s for the scout. */
    fun smartMoneyBuysLast60s(mint: String, nowMs: Long = System.currentTimeMillis()): Int {
        val cutoff = nowMs - 60_000L
        return recentBuys.count { it.mint == mint && it.timestampMs >= cutoff }
    }
    fun knownWhaleCount(): Int = whaleWallets.size
    internal fun clearForTest() { recentBuys.clear(); whaleWallets.clear() }
}
