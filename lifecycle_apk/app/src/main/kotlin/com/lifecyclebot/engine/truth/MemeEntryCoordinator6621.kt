package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6621 §MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE §4 + §6 (operator
 * directive Feb 2026):
 *
 *   §4 REMOVE SPECIALIST-TO-WRONG-EXECUTOR ALIASING
 *       "Current/historical source contains semantic shortcuts such as
 *          QUALITY → blueChipBuy()
 *          MANIPULATED → shitCoinBuy()
 *          EXPRESS → shitCoinBuy()
 *        Delete this architecture. QUALITY is not BLUECHIP. EXPRESS
 *        is not SHITCOIN. MANIPULATED is not SHITCOIN. Implement one
 *        canonical executor entry: executeMemeBuy(intent). Specialist
 *        personality affects sizing, TP/SL, tactics and learning
 *        metadata. It must NOT change execution identity."
 *
 *   §6 ONE OWNER → ONE FDG → ONE INTENT → ONE TICKET → ONE EXECUTION
 *       "Individual specialist AIs produce proposals/signals. They do
 *        not independently open positions."
 *
 * Slice 2 delivers this authority as the RECEIVER — every specialist
 * entry site MUST route through submitMemeSpecialistEntry6621 in
 * Slice 3 rollout. Today the receiver seals an intent (via
 * MemeExecutionIntent6621.seal6621) and returns it to the caller, who
 * still invokes the existing paperBuy/liveBuy. The aliasing counter
 * fires whenever a specialist lane is routed to a wrong-executor
 * wrapper — telemetry-only in Slice 2, hard-block in Slice 3.
 */
object MemeEntryCoordinator6621 {

    /**
     * The single canonical entry funnel for MemeTrader specialist BUY
     * proposals. The caller (specialist AI) has:
     *   - already scored/decided BUY
     *   - already run FDG (verdict passed in)
     *   - already sized (requestedSol) via CanonicalSizingBridge6532
     *
     * The coordinator seals a MemeExecutionIntent, journals the seal,
     * and returns the intent for the caller to hand to the executor.
     * Downstream (Slice 3): the executor accepts ONLY sealed intents.
     */
    fun submitMemeSpecialistEntry6621(
        specialistLane: String,
        candidateId: String,
        mint: String,
        symbol: String,
        mode: MemeExecutionIntent6621.ExecutionMode,
        fdgVerdict: String,
        requestedSol: Double,
        sealedSol: Double,
    ): MemeExecutionIntent6621.Intent {
        submits.incrementAndGet()
        val intent = MemeExecutionIntent6621.seal6621(
            candidateId = candidateId,
            mint = mint,
            symbol = symbol,
            rawLane = specialistLane,
            mode = mode,
            side = MemeExecutionIntent6621.Side.BUY,
            fdgVerdict = fdgVerdict,
            requestedSol = requestedSol,
            sealedSol = sealedSol,
        )
        try {
            PipelineHealthCollector.labelInc("MEME_ENTRY_COORDINATOR_SUBMIT_6621")
            PipelineHealthCollector.labelInc(
                "MEME_ENTRY_COORDINATOR_SUBMIT_${intent.lane}_6621"
            )
        } catch (_: Throwable) {}
        return intent
    }

    /**
     * §4 aliasing detector. Called by every specialist wrapper (or
     * the coordinator's Slice-3 dispatcher). If specialistLane belongs
     * to the SPECIALIST set but the executor wrapper doesn't own it
     * (e.g., QUALITY routed through blueChipBuy), the counter fires.
     * Steady-state target = 0 after Slice 3 rollout.
     *
     * Known historical aliasing pairs (from operator §4):
     *   QUALITY      → blueChipBuy   (WRONG — QUALITY is not BLUECHIP)
     *   MANIPULATED  → shitCoinBuy   (WRONG — MANIPULATED is not SHITCOIN)
     *   EXPRESS      → shitCoinBuy   (WRONG — EXPRESS is not SHITCOIN)
     *   PROJECT_SNIPER → SNIPE/CORE/STANDARD paths (WRONG)
     */
    fun probeAliasing6621(specialistLane: String, executorWrapper: String) {
        val s = specialistLane.uppercase().trim()
        val w = executorWrapper.uppercase().trim()
        val aliasingPair = when {
            s == "QUALITY" && w == "BLUECHIPBUY" -> true
            s == "MANIPULATED" && w == "SHITCOINBUY" -> true
            s == "EXPRESS" && w == "SHITCOINBUY" -> true
            s == "PROJECT_SNIPER" && (w == "STANDARDBUY" || w == "COREBUY" || w == "SNIPEBUY") -> true
            else -> false
        }
        if (aliasingPair) {
            aliasingAttempts.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("SPECIALIST_EXECUTOR_ALIAS_ATTEMPT_6621")
                PipelineHealthCollector.labelInc(
                    "SPECIALIST_EXECUTOR_ALIAS_ATTEMPT_${s}_TO_${w}_6621"
                )
                ForensicLogger.lifecycle(
                    "SPECIALIST_EXECUTOR_ALIAS_ATTEMPT_6621",
                    "specialistLane=$s executorWrapper=$w " +
                        "action=telemetry_only_slice2_hard_block_slice3 " +
                        "correction=specialist_owns_own_executor_route",
                )
            } catch (_: Throwable) {}
        }
    }

    private val submits = AtomicLong(0L)
    private val aliasingAttempts = AtomicLong(0L)

    fun statusLine(): String =
        "submits=${submits.get()} aliasingAttempts=${aliasingAttempts.get()}"

    internal fun resetForTest() {
        submits.set(0L); aliasingAttempts.set(0L)
    }
}
