package com.lifecyclebot.engine.truth

/**
 * V5.0.6431 §I — EXPLICIT LIVE AUTHORITY STATE (no more "hold=✅ open").
 *
 * OPERATOR (V5.0.6424 §I):
 *   'Replace ambiguous "hold=✅ open" with:
 *      LIVE_AUTHORITY:
 *        state=OPEN
 *        reason=NONE
 *        governor=BASELINE
 *      or
 *      state=BLOCKED
 *        reason=<specific reason>
 *      Never print contradictory HOLD/OPEN wording.'
 *
 * DESIGN
 * ──────
 * Pure formatting helper. Callers pass the raw armed / governor /
 * blockReasons / hcAge / hcFailed values from LiveEntrySafetyHold /
 * BotStatus, and this returns a canonical multi-line block.
 */
object LiveAuthorityRenderer6431 {

    enum class State { OPEN, BLOCKED }

    fun render(
        armed: Boolean,
        governor: String,
        minScore: Int,
        canonN: Int,
        healthCheckAgeS: Long,
        healthCheckFailed: List<String>,
        blockReasons: List<String>,
    ): String {
        val state = if (armed || blockReasons.isNotEmpty() || healthCheckFailed.isNotEmpty()) State.BLOCKED else State.OPEN
        val reason = when {
            state == State.OPEN -> "NONE"
            healthCheckFailed.isNotEmpty() -> "HEALTH_CHECK_FAILED:${healthCheckFailed.joinToString(",")}"
            blockReasons.isNotEmpty() -> blockReasons.joinToString(",")
            armed -> "SAFETY_HOLD_ARMED"
            else -> "UNKNOWN"
        }
        return buildString {
            append("  LIVE_AUTHORITY:\n")
            append("    state=").append(state).append('\n')
            append("    reason=").append(reason).append('\n')
            append("    governor=").append(governor).append('\n')
            append("    minScore=").append(minScore).append('\n')
            append("    canonN=").append(canonN).append('\n')
            append("    hcAge=").append(healthCheckAgeS).append("s\n")
        }.trimEnd()
    }
}
