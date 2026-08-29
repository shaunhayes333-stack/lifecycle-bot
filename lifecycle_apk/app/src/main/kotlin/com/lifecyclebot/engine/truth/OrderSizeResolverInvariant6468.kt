package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6468 §P0 (item 16) — ORDER SIZE RESOLVER INVARIANT AUDIT.
 *
 * OPERATOR MANDATE:
 *   "Audit OrderSizeResolver semantics. Callers that bypass or lie to
 *    the resolver undermine sizing correctness."
 *
 * DESIGN
 * ──────
 * Post-condition guard. Any `Resolution` must satisfy:
 *
 *   1. finalSizeSol >= 0
 *   2. finalSizeSol <= cashCapSol         (never spend more than cash)
 *   3. finalSizeSol <= laneCapSol         (respect lane risk cap)
 *   4. executable == true  ⇒  finalSizeSol > 0
 *   5. executable == false ⇒  reason.isNotBlank()
 *
 * Callers pass every produced Resolution through
 * `OrderSizeResolverInvariant6468.check(res)`. Violations are logged
 * (forensic + telemetry) so drift shows up immediately in CI + runtime.
 */
object OrderSizeResolverInvariant6468 {

    private val checks = AtomicLong(0L)
    private val violations = AtomicLong(0L)
    private val lastViolationReason = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun check(res: OrderSizeResolver6441.Resolution): Boolean {
        checks.incrementAndGet()
        val reasons = mutableListOf<String>()
        if (!res.finalSizeSol.isFinite() || res.finalSizeSol < 0.0) {
            reasons += "final_negative_or_nan"
        }
        // cashCap = 0 sometimes reflects "cash cap unknown" (paper allowed);
        // only enforce when cashCap is a real positive number.
        // V5.0.6600: canonical min-executable promotion and runner-ladder lifts
        // may validly exceed soft requested/risk suggestions. Cash and lane cap are
        // the hard invariants; promotion is reported in Resolution.trace().
        if (res.cashCapSol > 0.0 && res.finalSizeSol > res.cashCapSol + 1e-9) {
            reasons += "final_exceeds_cash_cap"
        }
        if (res.laneCapSol.isFinite() && res.laneCapSol > 0.0 && res.finalSizeSol > res.laneCapSol + 1e-9) {
            reasons += "final_exceeds_lane_cap"
        }
        if (res.executable && res.finalSizeSol <= 0.0) {
            reasons += "executable_with_zero_size"
        }
        if (!res.executable && res.reason.isBlank()) {
            reasons += "not_executable_without_reason"
        }
        if (reasons.isEmpty()) return true
        violations.incrementAndGet()
        val joined = reasons.joinToString(",")
        lastViolationReason.set(joined)
        try {
            ForensicLogger.lifecycle(
                "ORDER_SIZE_RESOLVER_INVARIANT_VIOLATION_6468",
                "reasons=$joined trace=${res.trace()}",
            )
            PipelineHealthCollector.labelInc("ORDER_SIZE_RESOLVER_INVARIANT_VIOLATION_6468")
        } catch (_: Throwable) {}
        return false
    }

    fun statusLine(): String =
        "checks=${checks.get()} violations=${violations.get()} last=${lastViolationReason.get() ?: "-"}"

    internal fun resetForTest() {
        checks.set(0L); violations.set(0L); lastViolationReason.set(null)
    }
}
