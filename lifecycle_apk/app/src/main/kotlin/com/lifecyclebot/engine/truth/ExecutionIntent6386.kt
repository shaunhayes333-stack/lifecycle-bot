package com.lifecyclebot.engine.truth

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6386 — CANONICAL EXECUTION INTENT (Section 2 of the directive).
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "Create an immutable ExecutionIntent with: intentId, walletAddress,
 *    mintAddress, side, selectedLane, marketSnapshotId, decisionTimestamp,
 *    requestedLamports or requestedRawTokenAmount, score, FDG verdict,
 *    route requirements, lifecycle state.
 *    Use one atomic map keyed by wallet + mint + side.
 *    Exactly one outstanding BUY intent is permitted per wallet/mint.
 *    All lane evaluation is read-only. Select one winning lane before FDG.
 *    Only the selected winner may create an execution intent or ticket.
 *    Targets: FDG decisions / intake <= 1.20, execution tickets / intake
 *    <= 1.10, executor invocations / accepted intent <= 1.20, duplicate
 *    signed BUY transactions = 0."
 */

enum class IntentSide { BUY, SELL }

/**
 * Immutable — copy-on-transition. Never mutate an existing instance.
 * All strategy-visible fields are frozen at intent creation; only the
 * lifecycle state (ProofState6386) transitions through the machine.
 */
data class ExecutionIntent6386(
    val intentId: String,
    val walletAddress: String,
    val mintAddress: String,
    val side: IntentSide,
    val selectedLane: String,
    val marketSnapshotId: String,
    val decisionTimestamp: Long,
    val requestedLamports: Lamports?,           // for BUY
    val requestedRawTokenAmount: RawTokenAmount?, // for SELL
    val score: Int,
    val fdgVerdict: String,
    val routeRequirements: RouteRequirements,
    val lifecycleState: ProofState6386,
) {
    init {
        require(walletAddress.isNotBlank())
        require(mintAddress.isNotBlank())
        require(selectedLane.isNotBlank())
        when (side) {
            IntentSide.BUY -> require(requestedLamports != null && requestedLamports.isPositive()) {
                "BUY intent must have positive requestedLamports"
            }
            IntentSide.SELL -> require(requestedRawTokenAmount != null && requestedRawTokenAmount.isPositive()) {
                "SELL intent must have positive requestedRawTokenAmount"
            }
        }
    }
    fun withState(newState: ProofState6386): ExecutionIntent6386 = copy(lifecycleState = newState)
}

data class RouteRequirements(
    val requiresJupiterQuote: Boolean = true,
    val maxSlippageBps: Int = 200,          // 2%
    val maxPriceImpactBps: Int = 300,       // 3%
    val requiresSafetyContract: Boolean = true,
)

/**
 * Atomic registry keyed by wallet+mint+side. Enforces:
 *   - Exactly ONE outstanding BUY intent per wallet/mint.
 *   - Terminal states (FinalizedProofComplete, FailedConfirmed, Quarantined)
 *     free the slot. PendingReconciliation does NOT free it.
 */
object ExecutionIntentRegistry6386 {

    private data class Key(val wallet: String, val mint: String, val side: IntentSide)

    private val map = ConcurrentHashMap<Key, AtomicReference<ExecutionIntent6386>>()

    /**
     * Try to reserve an intent slot. Returns null if a competing intent
     * already exists in a non-terminal state on the same wallet+mint+side.
     * The caller MUST NOT proceed to build a transaction if this returns
     * null — that would be exactly the "duplicate signed BUY transactions"
     * failure mode the directive targets.
     */
    fun tryReserve(
        walletAddress: String,
        mintAddress: String,
        side: IntentSide,
        selectedLane: String,
        marketSnapshotId: String,
        requestedLamports: Lamports? = null,
        requestedRawTokenAmount: RawTokenAmount? = null,
        score: Int,
        fdgVerdict: String,
        routeRequirements: RouteRequirements = RouteRequirements(),
    ): ExecutionIntent6386? {
        val key = Key(walletAddress, mintAddress, side)
        val existing = map[key]?.get()
        if (existing != null && !existing.isTerminal()) return null

        val intent = ExecutionIntent6386(
            intentId = UUID.randomUUID().toString(),
            walletAddress = walletAddress,
            mintAddress = mintAddress,
            side = side,
            selectedLane = selectedLane,
            marketSnapshotId = marketSnapshotId,
            decisionTimestamp = System.currentTimeMillis(),
            requestedLamports = requestedLamports,
            requestedRawTokenAmount = requestedRawTokenAmount,
            score = score,
            fdgVerdict = fdgVerdict,
            routeRequirements = routeRequirements,
            lifecycleState = ProofState6386.IntentCreated,
        )
        val ref = map.computeIfAbsent(key) { AtomicReference(intent) }
        // Race: if another thread reserved between our check and computeIfAbsent, reject.
        return if (ref.get().intentId == intent.intentId) intent else null
    }

    fun update(intent: ExecutionIntent6386) {
        val key = Key(intent.walletAddress, intent.mintAddress, intent.side)
        val ref = map[key] ?: return
        ref.set(intent)
        if (intent.isTerminal()) {
            map.remove(key, ref)
        }
    }

    fun current(walletAddress: String, mintAddress: String, side: IntentSide): ExecutionIntent6386? =
        map[Key(walletAddress, mintAddress, side)]?.get()

    fun outstandingCount(): Int = map.size

    // Test hook only — used by unit tests to reset between cases.
    internal fun clearAllForTest() { map.clear() }
}

private fun ExecutionIntent6386.isTerminal(): Boolean = when (lifecycleState) {
    is ProofState6386.FinalizedProofComplete,
    is ProofState6386.FailedConfirmed,
    is ProofState6386.Quarantined -> true
    else -> false
}
