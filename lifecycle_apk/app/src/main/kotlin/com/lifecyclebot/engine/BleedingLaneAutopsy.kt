package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6121 — BleedingLaneAutopsy
 *
 * OPERATOR DIRECTIVE: "how can a paused lane prove recovery if it isnt
 * trading? shadow engine? llm lab? we have to make sure the llm strategy
 * lab is able to actually do this."
 *
 * When GreenEvLaneGovernor pauses a lane, the Lab needs SPECIFIC forensic
 * detail about the failure signature — not just "lane is bleeding". This
 * module runs a post-mortem on the 20 losing sells and emits a rich
 * hint packet for the Lab prompt.
 *
 * Failure signature dimensions:
 *   • RUG_HEAVY         — > 40% of losses had pnlPct < -50%
 *   • SL_CHURN          — > 60% of losses hit SL at exactly -6% to -10%
 *   • CHOP_SCALP_MISS   — > 50% of losses had 0 < pnlPct < 5% (small red)
 *   • REGIME_MISMATCH   — > 50% of losses in one specific regime
 *   • FAST_DEATH        — > 50% of losses closed within 5 min of entry
 *   • SLOW_BLEED        — > 50% of losses held > 45 min
 *
 * Signature is packed as constraint string and injected into the
 * LabRecoveryHintQueue's next hint for the paused lane. Lab strategy
 * prompt applies these as HARD constraints: "avoid SL churn — SL should
 * be either < -14% or use trailing" for SL_CHURN, etc.
 *
 * Fail-open: any error yields empty signature list → no extra constraint
 * (the base recovery hint still fires).
 */
object BleedingLaneAutopsy {

    // ── Tunables ────────────────────────────────────────────────────────
    private const val RUG_LOSS_THRESHOLD_PCT = -50.0
    private const val SL_LOW_PCT = -10.0
    private const val SL_HIGH_PCT = -6.0
    private const val CHOP_SCALP_MAX_PCT = 5.0
    private const val FAST_DEATH_MAX_HOLD_MS = 5L * 60L * 1000L
    private const val SLOW_BLEED_MIN_HOLD_MS = 45L * 60L * 1000L

    private const val SIGNATURE_TRIGGER_FRAC = 0.4  // any dim > 40% dominance flags it
    private const val REGIME_TRIGGER_FRAC = 0.5

    // ── State ───────────────────────────────────────────────────────────
    data class Autopsy(
        val lane: String,
        val n: Int,
        val avgLossPct: Double,
        val signatures: List<String>,   // e.g. ["RUG_HEAVY","FAST_DEATH"]
        val constraintForLab: String,   // pre-formatted string to append to Lab prompt
    )

    private val lastAutopsyByLane = ConcurrentHashMap<String, Long>()

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Called by GreenEvLaneGovernor.delegateToLab. Returns a rich autopsy
     * that the Lab can incorporate into its next strategy invention.
     *
     * @param lane paused lane name
     * @param recentSells the 20 window trades for that lane
     */
    fun autopsy(lane: String, recentSells: List<Trade>): Autopsy {
        return try {
            val losses = recentSells.filter { it.pnlPct <= 0.0 }
            if (losses.isEmpty()) {
                return Autopsy(lane, 0, 0.0, emptyList(), "")
            }
            val n = losses.size.toDouble()
            val avgLossPct = losses.map { it.pnlPct }.filter { it.isFinite() }.average()

            val signatures = mutableListOf<String>()

            // Dimension 1: rug-heavy
            val rugs = losses.count { it.pnlPct <= RUG_LOSS_THRESHOLD_PCT }
            if (rugs / n > SIGNATURE_TRIGGER_FRAC) signatures.add("RUG_HEAVY")

            // Dimension 2: SL churn — losses cluster in the -10..-6 band
            val slHits = losses.count { it.pnlPct in SL_LOW_PCT..SL_HIGH_PCT }
            if (slHits / n > 0.6) signatures.add("SL_CHURN")

            // Dimension 3: chop scalp miss — many tiny reds (0 to -5%)
            val choppies = losses.count { it.pnlPct in -CHOP_SCALP_MAX_PCT..0.0 }
            if (choppies / n > 0.5) signatures.add("CHOP_SCALP_MISS")

            // Dimension 4: fast death (< 5 min hold)
            val fastDeaths = losses.count { holdMs(it) in 1..FAST_DEATH_MAX_HOLD_MS }
            if (fastDeaths / n > 0.5) signatures.add("FAST_DEATH")

            // Dimension 5: slow bleed (> 45 min hold)
            val slowBleeds = losses.count { holdMs(it) >= SLOW_BLEED_MIN_HOLD_MS }
            if (slowBleeds / n > 0.5) signatures.add("SLOW_BLEED")

            // Compose Lab-usable constraint string
            val constraint = buildConstraintString(signatures)

            if (signatures.isNotEmpty()) {
                try {
                    ForensicLogger.lifecycle(
                        "BLEEDING_LANE_AUTOPSY_6121",
                        "lane=$lane n=${losses.size} avgLoss=${"%.2f".format(avgLossPct)}% " +
                        "signatures=${signatures.joinToString(",")}",
                    )
                    PipelineHealthCollector.labelInc("BLEEDING_LANE_AUTOPSY_6121")
                } catch (_: Throwable) {}
            }
            lastAutopsyByLane[lane] = System.currentTimeMillis()

            Autopsy(lane, losses.size, avgLossPct, signatures, constraint)
        } catch (_: Throwable) {
            Autopsy(lane, 0, 0.0, emptyList(), "")
        }
    }

    // ── Internal ────────────────────────────────────────────────────────
    private fun holdMs(t: Trade): Long {
        val entry = t.entryTsMs
        val exit = t.ts
        return if (entry > 0L && exit > entry) exit - entry else 0L
    }

    private fun buildConstraintString(signatures: List<String>): String {
        if (signatures.isEmpty()) return ""
        val sb = StringBuilder("\nAUTOPSY CONSTRAINTS (must incorporate):\n")
        for (sig in signatures) {
            sb.append("  • $sig: ")
            sb.append(when (sig) {
                "RUG_HEAVY" ->
                    "prior lane suffered high-severity rugs (< -50%). Add pre-buy rug filter: " +
                    "insist safety.holderCount >= 40, safety.lpUsd >= 8000, dev-wallet-holds < 5%. " +
                    "SL should be tighter (-6% to -8%) to escape rug candles."
                "SL_CHURN" ->
                    "prior lane was chopped by SL at -6..-10%. Move SL further out (< -12%) OR use " +
                    "trailing SL that widens when price is +10% from entry. Add ATR-based dynamic SL."
                "CHOP_SCALP_MISS" ->
                    "prior lane died in tiny reds — signals fired then reversed. Raise entryScoreMin to 75+ " +
                    "and require regime != CHOP. Take earlier profit (TP 8-12% instead of 20+)."
                "FAST_DEATH" ->
                    "prior lane closed within 5 min of entry (dead-on-arrival). Add a 60-second post-entry " +
                    "warmup where SL is disabled unless price drops > 8%. Filter early-death setups."
                "SLOW_BLEED" ->
                    "prior lane held > 45 min then closed red. Impose maxHoldMins <= 30 and add a " +
                    "'no-progress' rule: if pnl < +3% at 15min mark, hard exit."
                else -> "unrecognized signature — no specific constraint."
            })
            sb.append("\n")
        }
        return sb.toString()
    }
}
