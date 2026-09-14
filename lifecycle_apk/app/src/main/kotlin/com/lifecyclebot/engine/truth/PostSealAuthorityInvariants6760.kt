package com.lifecyclebot.engine.truth

/**
 * V5.0.6760 §POST_SEAL_AUTHORITY_INVARIANTS.
 *
 * Central allowlist for gates that may block execution AFTER an FDG allow /
 * `EXEC_INTENT_SEALED` / `EXEC_GATE_ALLOW` / `EXEC_TICKET_CREATED` has fired.
 * Operator spec (V5.0.6759 DIRECT SOURCE REPAIR §7):
 *
 *   "Any older patch/gate that re-decides score / liquidity quality / lane
 *    eligibility / WAIT vs BUY / regime floor / learning quality / lane
 *    ownership must either run BEFORE authoritative sealing, or become
 *    advisory only. After sealing, ONLY canonical hard-safety/finality
 *    invariants may block. Do not stack another bypass over these gates.
 *    Remove or relocate the contradictory authority."
 *
 * Callsites that fire post-FDG use this authority to decide: block or
 * emit-advisory-only. Hard-safety reasons ALWAYS block; every other legacy
 * reason emits a `POST_SEAL_ADVISORY_ONLY_6760` telemetry line and falls
 * through so the sealed ticket path proceeds. Learning/telemetry is
 * unaffected — the advisory outcome is still stamped on the causal record
 * so downstream training can weigh it.
 */
object PostSealAuthorityInvariants6760 {

    /**
     * Reasons that remain authoritative post-seal. These are hard-safety /
     * finality invariants — a fully sealed trade may still be refused for
     * these BECAUSE they are safety, duplicate-execution, or true stale-
     * economic-validity vetoes.
     */
    private val HARD_SAFETY_SUBSTRINGS = setOf(
        // Portfolio-wide inventory ceiling. Sanity cap, honoured for every lane.
        "POSITION_HARD_CAP_EXIT_THROUGHPUT_6727",
        // Genuine cash-below-minimum-executable (V5.0.6760 §2 refactor).
        "CASH_STARVED_EXIT_THROUGHPUT_6727",
        // Duplicate execution / terminal position state / stale economic validity.
        "DUPLICATE_EXECUTION",
        "TERMINAL_POSITION_STATE",
        "STALE_ECONOMIC_VALIDITY",
        // Token-safety hard vetos — rug, honeypot, freeze authority, etc.
        "SAFETY_HARD_VETO",
        "TOKEN_SAFETY_HARD_VETO",
        "RUG_DETECTED_HARD_VETO",
        "HONEYPOT_HARD_VETO",
        // Finality invariants — canonical position/economic authorities.
        "CANONICAL_FINALITY_",
    )

    /**
     * @return true if [reason] should still block execution post-seal.
     *         false if the reason is a legacy pre-seal quality/regime/
     *         eligibility/feedback re-veto that must be demoted to
     *         advisory-only per §7.
     *
     * Fail-safe: unknown reasons return `false` — the sealed authority
     * wins, the caller emits an advisory telemetry line and falls
     * through. This is the SAFE default: an unknown legacy gate must
     * NOT retain silent authority over sealed FDG decisions.
     */
    fun mayBlockAfterFdgAllow(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.uppercase()
        // Any explicit safety prefix keeps authority.
        if (r.startsWith("SAFETY_") || r.startsWith("CANONICAL_FINALITY_")) return true
        return HARD_SAFETY_SUBSTRINGS.any { r.contains(it) }
    }

    /**
     * Emit the advisory-only telemetry marker so operators can see when a
     * post-seal legacy gate WOULD have blocked but was demoted. Callsite
     * usage:
     *
     *     if (!PostSealAuthorityInvariants6760.mayBlockAfterFdgAllow(reason)) {
     *         PostSealAuthorityInvariants6760.emitAdvisory6760(reason, extra)
     *         // fall through — sealed path proceeds
     *     } else {
     *         return blockedVerdict  // hard-safety still authoritative
     *     }
     */
    fun emitAdvisory6760(reason: String, extra: String = "") {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("POST_SEAL_ADVISORY_ONLY_6760")
            val trimmed = reason.take(64)
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                "POST_SEAL_ADVISORY_ONLY_6760|$trimmed",
            )
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "POST_SEAL_ADVISORY_ONLY_6760",
                "reason=$trimmed extra=${extra.take(240)}",
            )
        } catch (_: Throwable) {}
    }
}
