package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6620 §MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE §7 (operator directive
 * Feb 2026):
 *
 *   "STANDARD and V3_CORE may remain visibility/scoring/shadow lanes if
 *    that is their current design. They must NEVER call execution when
 *    a MemeTrader specialist owns the candidate. Required invariant:
 *      if (candidate.ownerLane != lane) { telemetryOnly(); return }
 *    There must be one executable owner for each (mint, candidateVersion).
 *    No CORE fallback execution after PROJECT_SNIPER already owns it.
 *    No STANDARD paperBuy attempt after a specialist intent exists."
 *
 * FORENSIC EVIDENCE (V5.0.6619 dump):
 *   owner/FDG    = PROJECT_SNIPER
 *   ticketLane   = PROJECT_SNIPER
 *   ticketVer    = 1788184983843    (wall-clock ms — CanonicalSizingBridge6532)
 *   executor lane= CORE, then STANDARD
 *   currentVer   = 59606166         (LaneExecutionCoordinator bucket)
 *   preFdg       = WATCH, NO_EXECUTION_INTENT
 *
 * Two independent defects fed each other:
 *   [a] Two candidateVersion authorities coexisted — the FDG stamped
 *       one, the executor validated another, so the ticket restore
 *       looked mismatched even for the OWNER'S own attempt.
 *   [b] When layerTag/ts.source were blank, the executor synthesized
 *       "STANDARD" instead of promoting the authority's electionLane.
 *       The specialist's ticket was then unreachable because the
 *       executor was probing for a STANDARD-owned ticket that never
 *       existed. STANDARD then fell into a PROBE_ONLY path and
 *       "stole" the execution slot from PROJECT_SNIPER.
 *
 * This authority is the SOURCE-LEVEL SEAL between the specialist
 * election and the executor entry. It does NOT reclassify — it just
 * counts and surfaces disagreements at the exact causal moment.
 *
 * SPECIALIST LANES (from operator directive §1 + §11 canonical enum):
 *   QUALITY, BLUECHIP, SHITCOIN, CYCLIC, EXPRESS, CORE, MOONSHOT,
 *   PROJECT_SNIPER, DIP_HUNTER, MANIPULATED, TREASURY, CASHGEN
 *
 * Non-executable observer lanes (may score/shadow, never execute
 * when a specialist owns the candidate):
 *   STANDARD, V3_CORE
 */
object MemeOwnershipInvariant6620 {

    /**
     * The full canonical MemeTrader specialist set that the operator
     * has mandated stay ALIVE (per §1 + §16 "do not disable"). Ownership
     * of a candidate by any of these lanes means STANDARD/V3_CORE
     * must NOT independently attempt execution.
     */
    val SPECIALIST_LANES: Set<String> = setOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS",
        "CORE", "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER",
        "MANIPULATED", "TREASURY", "CASHGEN",
    )

    /**
     * Lanes that must NEVER take an execution slot away from a
     * specialist. Note CORE is deliberately NOT in this set — it is
     * itself a MemeTrader specialist per operator directive §11
     * ("Do NOT automatically alias CORE/STANDARD/V3_CORE together.
     * Preserve their actual design semantics").
     */
    val OBSERVER_ONLY_LANES: Set<String> = setOf("STANDARD", "V3_CORE")

    private val invariantChecks = AtomicLong(0L)
    private val specialistOwnedPromotions = AtomicLong(0L)
    private val standardBlockedOnSpecialistOwned = AtomicLong(0L)
    private val laneMismatchByExecutor = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Called at every executor entry (paperBuy / liveBuy) to prove
     * the caller's chosen lane matches the authority's owner. Returns
     * the ACTUAL lane the executor should use — never rewrites lane
     * silently, just resolves and counts.
     *
     * When authorityOwnerLane is non-null and disagrees with the
     * caller's derivedLane, we PROMOTE the authority's lane (the
     * specialist that legitimately elected this candidate). When the
     * caller's derivedLane was an observer lane (STANDARD/V3_CORE)
     * and the authority is a specialist, this is a "theft attempt" —
     * emit the theft counter and refuse.
     */
    data class OwnershipResolution(
        val lane: String,
        val promoted: Boolean,
        val observerBlocked: Boolean,
        val reason: String,
    )

    fun resolveExecutorLane6620(
        mint: String,
        symbol: String,
        derivedLane: String,
        authorityOwnerLane: String?,
    ): OwnershipResolution {
        invariantChecks.incrementAndGet()
        val d = derivedLane.uppercase().trim()
        val a = authorityOwnerLane?.uppercase()?.trim().orEmpty()
        // No authority yet — nothing to reconcile against; use derived.
        if (a.isBlank()) {
            return OwnershipResolution(d.ifBlank { "STANDARD" }, promoted = false,
                observerBlocked = false, reason = "no_authority_owner_yet")
        }
        // Perfect match — happy path.
        if (a == d) {
            return OwnershipResolution(a, promoted = false,
                observerBlocked = false, reason = "authority_matches_caller")
        }
        // Authority is a specialist and caller was an observer lane —
        // this is the exact "STANDARD steals PROJECT_SNIPER" defect
        // the operator flagged. Block the observer, promote the owner.
        if (a in SPECIALIST_LANES && d in OBSERVER_ONLY_LANES) {
            standardBlockedOnSpecialistOwned.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PAPER_BUY_STANDARD_ON_SPECIALIST_OWNED_6620")
                PipelineHealthCollector.labelInc("LANE_EXEC_WITHOUT_SAME_LANE_CANONICAL_INTENT_6620")
                ForensicLogger.lifecycle(
                    "PAPER_BUY_STANDARD_ON_SPECIALIST_OWNED_6620",
                    "mint=${mint.take(10)} symbol=$symbol observer=$d " +
                        "authorityOwner=$a action=block_observer_promote_specialist",
                )
            } catch (_: Throwable) {}
            return OwnershipResolution(a, promoted = true,
                observerBlocked = true, reason = "specialist_owned_observer_blocked")
        }
        // Authority is a specialist and caller was ALSO a specialist —
        // but a DIFFERENT one. This is a cross-lane rewrite attempt.
        // Refuse to silently swap; return the authority's owner and
        // count the disagreement so the operator can trace which
        // specialist pair collides.
        if (a in SPECIALIST_LANES && d in SPECIALIST_LANES) {
            specialistOwnedPromotions.incrementAndGet()
            laneMismatchByExecutor.computeIfAbsent("${d}_vs_$a") { AtomicLong(0L) }.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("LANE_EXEC_CROSS_SPECIALIST_REWRITE_6620")
                PipelineHealthCollector.labelInc("LANE_EXEC_WITHOUT_SAME_LANE_CANONICAL_INTENT_6620")
                ForensicLogger.lifecycle(
                    "LANE_EXEC_CROSS_SPECIALIST_REWRITE_6620",
                    "mint=${mint.take(10)} symbol=$symbol callerSpecialist=$d " +
                        "authoritySpecialist=$a action=promote_authority_owner",
                )
            } catch (_: Throwable) {}
            return OwnershipResolution(a, promoted = true,
                observerBlocked = false, reason = "cross_specialist_promoted_to_authority")
        }
        // Fall-through (authority is observer, caller is anything) —
        // observer authority should not exist; log and use caller.
        try { PipelineHealthCollector.labelInc("LANE_EXEC_AUTHORITY_OBSERVER_UNEXPECTED_6620") } catch (_: Throwable) {}
        return OwnershipResolution(d.ifBlank { a }, promoted = false,
            observerBlocked = false, reason = "authority_observer_unexpected")
    }

    fun statusLine(): String {
        val topMismatch = laneMismatchByExecutor.entries
            .sortedByDescending { it.value.get() }
            .take(4)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        return "checks=${invariantChecks.get()} " +
            "standardBlocked=${standardBlockedOnSpecialistOwned.get()} " +
            "specialistPromotions=${specialistOwnedPromotions.get()} " +
            (if (topMismatch.isBlank()) "topMismatch=none" else "topMismatch=[$topMismatch]")
    }

    internal fun resetForTest() {
        invariantChecks.set(0L)
        specialistOwnedPromotions.set(0L)
        standardBlockedOnSpecialistOwned.set(0L)
        laneMismatchByExecutor.clear()
    }
}
