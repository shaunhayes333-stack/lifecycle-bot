package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6342 — LANE ENTRY CONTRACT (single authoritative live-entry choke).
 *
 * OPERATOR DIRECTIVE (verbatim excerpts):
 *   "Make AATE trade competently from the first live trade using a
 *    deterministic, preloaded trading policy and evidence available
 *    before entry. Do not use early live losses as training material
 *    required to discover basic trading behaviour."
 *   "BLUECHIP must represent established, liquid assets. A Pump.fun
 *    token cannot be BLUECHIP."
 *   "QUALITY must require: Real pool address, not MINT_ROUTE."
 *   "When governor is HOLD: Create no BUY execution ticket."
 *
 * This module ships the minimum viable subset of V5.0.6342 in a single
 * self-contained file so CI can verify it in one build:
 *
 *   1. Lane-identity contract per operator spec (BLUECHIP / QUALITY).
 *   2. Governor HOLD hard-veto of BUY (redirect to SHADOW).
 *   3. Single authoritative check callable from every buy path.
 *
 * OUT OF SCOPE for this initial push (staged for 6343-6349):
 *   - Full immutable FillLotLedger keyed by wallet+mint+buyTxSig
 *   - Strong unit types (SolAmount / UsdAmount / TokenQuantity)
 *   - Foundation-policy pre-entry evidence contract
 *   - Executable-price stop preflight
 *   - Scanner/hydration queue separation
 *   - New FIRST-TRADE READINESS health block
 *
 * INVARIANTS PRESERVED:
 *   - Never disables a lane; failed candidates go to SHADOW/PROBE.
 *   - Never affects paper mode (learning path unchanged).
 *   - No changes to existing lane math, scoring or exit logic.
 */
object LaneEntryContract6342 {

    enum class Verdict {
        /** Live authority granted — proceed to execution. */
        ALLOW_LIVE,
        /** Contract failed — candidate must be routed to SHADOW / PROBE. */
        REDIRECT_SHADOW,
        /** Governor HOLD in effect — no live BUY may issue. */
        GOVERNOR_HOLD_VETO,
    }

    data class Assessment(
        val verdict: Verdict,
        val laneRequested: String,
        val laneCanonical: String,
        val reasons: List<String>,
    ) {
        val allowsLive: Boolean get() = verdict == Verdict.ALLOW_LIVE
    }

    /** Pump.fun mints always end with "pump" (canonical Pump.fun suffix). */
    private fun isPumpFunMint(mint: String): Boolean =
        mint.isNotBlank() && mint.endsWith("pump", ignoreCase = true)

    /** MINT_ROUTE placeholder means no real pool address is known yet. */
    private fun isMintRoutePlaceholder(pool: String?): Boolean {
        val p = pool ?: return true
        return p.isBlank() ||
            p.equals("MINT_ROUTE", ignoreCase = true) ||
            p.contains("MINT_ROUTE", ignoreCase = true)
    }

    /**
     * Single authoritative live-entry check. Every live buy path
     * (direct scanner entry, restored registry, probation, warmup,
     * re-entry, BLUECHIP path, QUALITY bypass, V3 path, reconciler-
     * triggered entry, lane-specific shortcut) MUST call this before
     * creating an execution ticket or acquiring a buy lease.
     */
    fun assessEntry(ts: TokenState, laneRequested: String): Assessment {
        val reasons = mutableListOf<String>()
        val lane = laneRequested.uppercase()

        // 1. Governor HOLD hard-veto — no live BUY tickets while HOLD.
        val govState = try { LiveEntrySafetyHold.currentGovernorState().name } catch (_: Throwable) { "BASELINE" }
        if (govState == "HOLD") {
            reasons += "GOVERNOR_HOLD_VETO_6342"
            try {
                PipelineHealthCollector.labelInc("LIVE_BUY_REDIRECTED_GOVERNOR_HOLD_6342")
                ForensicLogger.lifecycle(
                    "LANE_ENTRY_CONTRACT_GOVERNOR_HOLD_6342",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$lane govState=$govState action=redirect_shadow",
                )
            } catch (_: Throwable) {}
            return Assessment(Verdict.GOVERNOR_HOLD_VETO, laneRequested, "SHADOW", reasons)
        }

        // 2. BLUECHIP identity contract — no Pump.fun mints allowed.
        //    BLUECHIP must represent established, liquid assets.
        if (lane == "BLUECHIP" || lane == "BLUE_CHIP") {
            if (isPumpFunMint(ts.mint)) {
                reasons += "BLUECHIP_REJECTS_PUMPFUN_MINT_6342"
                try {
                    PipelineHealthCollector.labelInc("LANE_ENTRY_BLUECHIP_PUMPFUN_REJECTED_6342")
                    ForensicLogger.lifecycle(
                        "LANE_ENTRY_BLUECHIP_PUMPFUN_REJECTED_6342",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} action=route_to_meme_lane_shadow",
                    )
                } catch (_: Throwable) {}
                return Assessment(Verdict.REDIRECT_SHADOW, laneRequested, "MOONSHOT_OR_SHITCOIN", reasons)
            }
        }

        // 3. QUALITY identity contract — no MINT_ROUTE placeholders.
        //    QUALITY must require a real, verified pool address.
        if (lane == "QUALITY") {
            val pool = try { ts.tokenMap.pairAddress.ifBlank { ts.tokenMap.poolAddress } } catch (_: Throwable) { null }
            if (isMintRoutePlaceholder(pool)) {
                reasons += "QUALITY_REJECTS_MINT_ROUTE_6342"
                try {
                    PipelineHealthCollector.labelInc("LANE_ENTRY_QUALITY_MINT_ROUTE_REJECTED_6342")
                    ForensicLogger.lifecycle(
                        "LANE_ENTRY_QUALITY_MINT_ROUTE_REJECTED_6342",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} pool=${pool ?: "null"} action=redirect_shadow",
                    )
                } catch (_: Throwable) {}
                return Assessment(Verdict.REDIRECT_SHADOW, laneRequested, "SHADOW", reasons)
            }
        }

        // Contract passed — live authority granted.
        try { PipelineHealthCollector.labelInc("LANE_ENTRY_CONTRACT_ALLOWED_6342") } catch (_: Throwable) {}
        return Assessment(Verdict.ALLOW_LIVE, laneRequested, lane, listOf("CONTRACT_PASSED"))
    }
}
