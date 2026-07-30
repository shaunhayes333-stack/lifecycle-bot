package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6387 — LIVE_EXIT_ONLY mode (directive P0). Any of the 9 startup
 * conditions being true forces LIVE_EXIT_ONLY. Existing safety holds
 * (CanonicalLedgerParityHold6387, FalseProfitTriggerHold6387) remain
 * additive; LIVE_EXIT_ONLY is the umbrella authority.
 */
object LiveExitOnlyMode6387 {
    data class Conditions(
        val hotExitJobActive: Boolean,
        val botLoopP95Ms: Long,
        val hasConfirmedBuyWithZeroSpend: Boolean,
        val estVsConfirmedQtyDeviationPct: Double,
        val currentPriceZeroOrStaleOnStop: Boolean,
        val canonicalPositionsExceedProvenWallet: Boolean,
        val uniqueCloseCounterMismatch: Boolean,
        val canonicalHasUnprovenOrQuarantinedLot: Boolean,
        val ownerMintDeltaUnresolved: Boolean,
    )

    /** Returns the first failed invariant, or null if all pass. */
    fun evaluate(c: Conditions): String? = when {
        !c.hotExitJobActive -> "HOT_EXIT_JOB_INACTIVE"
        c.botLoopP95Ms > 10_000L -> "BOT_LOOP_P95_GT_10S=${c.botLoopP95Ms}"
        c.hasConfirmedBuyWithZeroSpend -> "CONFIRMED_BUY_ZERO_SPEND"
        c.estVsConfirmedQtyDeviationPct > 5.0 -> "QTY_DEVIATION_GT_5PCT=${c.estVsConfirmedQtyDeviationPct}"
        c.currentPriceZeroOrStaleOnStop -> "STOP_WITH_ZERO_OR_STALE_PRICE"
        c.canonicalPositionsExceedProvenWallet -> "CANONICAL_EXCEEDS_WALLET"
        c.uniqueCloseCounterMismatch -> "UNIQUE_CLOSE_COUNTER_MISMATCH"
        c.canonicalHasUnprovenOrQuarantinedLot -> "CANONICAL_UNPROVEN_LOT"
        c.ownerMintDeltaUnresolved -> "OWNER_MINT_DELTA_UNRESOLVED"
        else -> null
    }

    private val activeReason = AtomicReference<String?>("STARTUP_DEFAULT")
    fun activeReason(): String? = activeReason.get()
    fun isActive(): Boolean = activeReason.get() != null
    fun engage(reason: String) { activeReason.set(reason) }
    fun disengage() { activeReason.set(null) }
    internal fun setTestOverride(v: String?) { activeReason.set(v) }
}

/**
 * V5.0.6387 — PARTIAL EXIT STATE MACHINE (directive P9). Monotonic — a
 * completed partial index can NEVER fire again.
 */
object PartialExitStateMachine6387 {
    enum class Index { NONE, FIRST, SECOND, RUNNER }

    data class State(val index: Index, val positionId: String)

    private val states = java.util.concurrent.ConcurrentHashMap<String, State>()

    fun currentIndex(positionId: String): Index = states[positionId]?.index ?: Index.NONE

    /**
     * Attempt to fire a partial at the given target index. Only valid when
     * moving strictly forward: NONE → FIRST → SECOND → RUNNER.
     * Returns true iff the transition was applied.
     */
    @Synchronized
    fun tryAdvance(positionId: String, target: Index): Boolean {
        val current = states[positionId]?.index ?: Index.NONE
        val allowed = when (current) {
            Index.NONE -> target == Index.FIRST
            Index.FIRST -> target == Index.SECOND
            Index.SECOND -> target == Index.RUNNER
            Index.RUNNER -> false     // terminal — no re-fire
        }
        if (!allowed) return false
        states[positionId] = State(target, positionId)
        return true
    }

    /** Directive P9: never reset the ladder after lane reassignment or wallet recovery. */
    fun neverResetOnLaneReassignment() { /* structural documentation */ }
    internal fun clearAllForTest() { states.clear() }
}

/**
 * V5.0.6387 — FEE-AWARE EXECUTION (directive P8). Round-trip cost must be
 * covered ≥ 2× by expected gross edge AND net edge ≥ 3%.
 */
object FeeAwareExecution6387 {
    data class Inputs(
        val estimatedBuyFeeSol: Double,
        val estimatedSellFeeSol: Double,
        val priorityFeesSol: Double,
        val tipsSol: Double,
        val expectedPriceImpactSol: Double,
        val expectedSlippageSol: Double,
        val routeFeesSol: Double,
        val expectedGrossEdgeSol: Double,
        val expectedNetEdgePct: Double,
        val positionSizeSol: Double,
    )
    data class Verdict(val allowed: Boolean, val reason: String, val roundTripCost: Double)
    fun evaluate(i: Inputs): Verdict {
        val roundTripCost = i.estimatedBuyFeeSol + i.estimatedSellFeeSol +
            i.priorityFeesSol + i.tipsSol + i.expectedPriceImpactSol +
            i.expectedSlippageSol + i.routeFeesSol
        if (i.expectedGrossEdgeSol < roundTripCost * 2.0)
            return Verdict(false, "GROSS_EDGE_LT_2X_COST edge=${i.expectedGrossEdgeSol} cost=$roundTripCost", roundTripCost)
        if (i.expectedNetEdgePct < 3.0)
            return Verdict(false, "NET_EDGE_LT_3PCT pct=${i.expectedNetEdgePct}", roundTripCost)
        // Directive: "Do not spend a fixed 0.0003 SOL per leg when that
        // exceeds 0.5% of position size unless emergency override."
        val fixedFeeCap = i.positionSizeSol * 0.005
        if ((i.estimatedBuyFeeSol > fixedFeeCap || i.estimatedSellFeeSol > fixedFeeCap) &&
            i.positionSizeSol > 0.0) {
            return Verdict(false, "FEE_EXCEEDS_HALF_PCT_OF_POSITION fee=${i.estimatedBuyFeeSol} cap=$fixedFeeCap", roundTripCost)
        }
        return Verdict(true, "OK", roundTripCost)
    }
}

/**
 * V5.0.6387 — RECONCILER ZERO-PROOF RULES (directive P4).
 * Three independent finalised zero proofs across at least two RPC endpoints
 * are required to close an unproven position. Any failure resets the count.
 */
object ReconcilerZeroProof6387 {
    data class ProofSample(val rpcEndpoint: String, val slot: Long, val finalised: Boolean, val emptyResponse: Boolean)
    data class State(val samples: List<ProofSample>, val minContextSlot: Long)
    fun isConfirmedZero(state: State): Boolean {
        if (state.samples.size < 3) return false
        val successful = state.samples.filter { it.finalised && !it.emptyResponse && it.slot >= state.minContextSlot }
        if (successful.size < 3) return false
        val endpoints = successful.map { it.rpcEndpoint }.toSet()
        return endpoints.size >= 2
    }
    /** Directive: "any RPC error, timeout, empty incomplete response or stale context resets the sequence." */
    fun requiresReset(sample: ProofSample, minContextSlot: Long): Boolean =
        !sample.finalised || sample.emptyResponse || sample.slot < minContextSlot
    /** Directive: zero balance without an identified tx becomes CLOSED_BALANCE_ZERO_UNATTRIBUTED. */
    const val UNATTRIBUTED_CLOSE_REASON: String = "CLOSED_BALANCE_ZERO_UNATTRIBUTED"
}

/**
 * V5.0.6387 — PRICE INTEGRITY AUTHORITY (directive P5).
 * Order: quote → websocket → REST → last valid mark.
 */
object PriceIntegrityAuthority6387 {
    enum class Source { EXECUTABLE_QUOTE, WS_PAIR, REST_PAIR, LAST_MARK, NONE }
    fun rankSources(quoteAvailable: Boolean, wsFresh: Boolean, restFresh: Boolean, markFresh: Boolean): Source = when {
        quoteAvailable -> Source.EXECUTABLE_QUOTE
        wsFresh -> Source.WS_PAIR
        restFresh -> Source.REST_PAIR
        markFresh -> Source.LAST_MARK
        else -> Source.NONE
    }
    /** Directive: "price zero means PRICE_UNKNOWN; stale price means request an executable quote;
     *  no quote means hold under emergency observation, not record a 100% loss." */
    fun classifyForStop(value: Double, isStale: Boolean, hasExecutableQuote: Boolean): String = when {
        value <= 0.0 -> "PRICE_UNKNOWN"
        isStale && !hasExecutableQuote -> "STALE_HOLD_UNDER_OBSERVATION"
        isStale && hasExecutableQuote -> "REFRESH_VIA_EXECUTABLE_QUOTE"
        else -> "OK"
    }
}

/**
 * V5.0.6387 — HOT EXIT SUPERVISOR CONTRACT (directive P6).
 * Interface + heartbeat state. Implementation lives on Dispatchers.IO in
 * the runtime; this module exposes the invariants only.
 */
object HotExitSupervisorContract6387 {
    @Volatile private var lastHeartbeatMs: Long = 0L
    @Volatile private var missedHeartbeats: Int = 0
    @Volatile private var jobActive: Boolean = false

    const val HEARTBEAT_TTL_MS: Long = 2_000L
    const val UNIVERSAL_STOP_P95_TRIGGER_TO_BROADCAST_MS: Long = 3_000L
    const val MAX_MISSED_HEARTBEATS_BEFORE_EXIT_ONLY: Int = 3

    fun heartbeat() {
        lastHeartbeatMs = System.currentTimeMillis()
        missedHeartbeats = 0
        jobActive = true
    }

    fun evaluateHealth(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lastHeartbeatMs == 0L) return false
        val gap = nowMs - lastHeartbeatMs
        if (gap > HEARTBEAT_TTL_MS) {
            missedHeartbeats++
            if (missedHeartbeats >= MAX_MISSED_HEARTBEATS_BEFORE_EXIT_ONLY) {
                LiveExitOnlyMode6387.engage("HOT_EXIT_MISSED_${missedHeartbeats}_HEARTBEATS")
                jobActive = false
                return false
            }
        }
        return true
    }
    fun isJobActive(): Boolean = jobActive
    internal fun resetForTest() { lastHeartbeatMs = 0L; missedHeartbeats = 0; jobActive = false }
}
