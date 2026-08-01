package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6405 §10 — GLOBAL ENTRY POLICY (single gate before any buy).
 *
 * Before a buy authorisation reaches the executor it must pass every
 * gate here. All gates are read-only pure checks with deterministic
 * REASON codes so operators can grep the journal for any refusal.
 *
 * Gates
 * ─────
 *   1. Price integrity              (PriceIntegrityAuthority6405)
 *   2. Decimal resolvability        (DecimalIntegrityAuthority6405)
 *   3. No prior terminal duplicate  (TerminalFinalityAuthority6405)
 *   4. Per-mint cooldown            (this authority)
 *   5. Global entry pause flag      (this authority)
 */
object GlobalEntryPolicy6405 {

    data class Decision(val allow: Boolean, val reason: String)

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()
    @Volatile private var globalPause: Boolean = false
    @Volatile private var globalPauseReason: String = ""

    fun setGlobalPause(paused: Boolean, reason: String = "") {
        globalPause = paused
        globalPauseReason = reason
        try {
            ForensicLogger.lifecycle(
                "GLOBAL_ENTRY_PAUSE_6405",
                "paused=$paused reason=$reason",
            )
            PipelineHealthCollector.labelInc("GLOBAL_ENTRY_PAUSE_6405")
        } catch (_: Throwable) {}
    }

    fun setCooldownMs(mint: String, cooldownMs: Long) {
        if (mint.isBlank() || cooldownMs <= 0L) return
        cooldownUntilMs[mint] = System.currentTimeMillis() + cooldownMs
    }

    /** Evaluate every gate; short-circuits on the first refusal. */
    fun evaluate(
        mint: String,
        positionGenerationForRebuy: Long,
        priceUsd: Double?,
        priceSource: PriceIntegrityAuthority6405.PriceSource,
    ): Decision {
        if (globalPause) return Decision(false, "GLOBAL_PAUSE:$globalPauseReason")

        val cd = cooldownUntilMs[mint]
        if (cd != null && cd > System.currentTimeMillis()) {
            return Decision(false, "COOLDOWN_${cd - System.currentTimeMillis()}MS")
        }

        // Duplicate re-buy of a terminal generation is refused; caller
        // must advance positionGeneration for a legit fresh entry.
        if (TerminalFinalityAuthority6405
                .isTerminal(mint, positionGenerationForRebuy)
        ) {
            return Decision(false, "TERMINAL_GENERATION_DUPLICATE_BUY")
        }

        val priceVerdict = PriceIntegrityAuthority6405
            .evaluatePrice(mint, priceUsd, priceSource)
        if (priceVerdict is PriceIntegrityAuthority6405.Verdict.Reject) {
            return Decision(false, priceVerdict.reason)
        }

        val pairVerdict = PriceIntegrityAuthority6405.evaluatePair(mint)
        if (pairVerdict is PriceIntegrityAuthority6405.Verdict.Reject) {
            return Decision(false, pairVerdict.reason)
        }

        return Decision(true, "OK")
    }

    internal fun clearForTest() {
        cooldownUntilMs.clear()
        globalPause = false
        globalPauseReason = ""
    }
}
