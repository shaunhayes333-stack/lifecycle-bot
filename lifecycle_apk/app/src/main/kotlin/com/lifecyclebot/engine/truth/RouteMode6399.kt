package com.lifecyclebot.engine.truth

/**
 * V5.0.6399 — ROUTE MODE (split-brain removal).
 *
 * The single authoritative route decision. Resolved BEFORE ticket
 * creation. A candidate that is denylisted, shadow-only or deferred
 * MUST NOT enter the live executor path.
 *
 *   LIVE              — full live execution eligible
 *   SHADOW_READ_ONLY  — journal only, counterfactual, no live size,
 *                       no lease, no live counters
 *   DEFERRED          — hydration/evidence incomplete
 *   BLOCKED           — hard veto (hard safety, rug, honeypot, etc.)
 */
enum class RouteMode6399 { LIVE, SHADOW_READ_ONLY, DEFERRED, BLOCKED }

/**
 * V5.0.6399 — CANONICAL TERMINAL OUTCOMES.
 *
 * Exactly ONE terminal outcome per evaluationId. All BUY verdicts
 * originate from this single enum — FDG is the sole decision authority.
 */
enum class FdgTerminalOutcome6399 {
    FDG_ALLOW_LIVE,
    FDG_ALLOW_SHADOW,
    FDG_BLOCK_SCORE,
    FDG_BLOCK_HARD_SAFETY,
    FDG_DEFER_HYDRATION,
    FDG_DEFER_COOLDOWN,
}
