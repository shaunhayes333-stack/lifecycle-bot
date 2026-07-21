package com.lifecyclebot.engine

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * SOL PERPS LANE — V5.0.6305 Phase 1 skeleton
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Phase 1 goal (per PRD): a leveraged SOL Perps lane that runs in SHADOW-PAPER
 * mode alongside the existing spot lanes so the AI can learn perp-shape
 * (funding, MFE-vs-liquidation asymmetry) without any live capital risk.
 *
 * This class is intentionally minimal — a gate + registry so the rest of the
 * codebase can begin referencing PerpsLaneGate without pulling in the full
 * perps execution stack. Phase 2 will bring:
 *   • Drift/Zeta perps ingestion pipeline
 *   • Isolated-margin position sizing per liquidation buffer
 *   • Funding-rate expectancy tracker
 *   • Cross-learning bridge back to spot lanes (Neural bridge)
 *
 * Doctrine: perps stays in SHADOW-PAPER until at least 200 closed-loop
 * shadow trades demonstrate WR>=40% and EV>=+15%. Then LLM-Lab shadow-proof
 * (same gate protocol as any promoted strategy) is required before it can
 * mint real signals. NEVER auto-live-trade from this class.
 */
object PerpsLaneGate {

    private const val TAG = "PerpsLaneGate"
    private const val LANE_KEY = "PERPS_SOL"

    // Phase 1: gate is OFF by default. Turned on manually via
    // GlobalTradeRegistry.perpsShadowEnabled = true or the operator toggle
    // in MainActivity settings once the shadow ingest pipeline lands.
    @Volatile private var enabled: Boolean = false

    /** Called by MainActivity settings toggle. In-memory only for Phase 1;
     *  Phase 2 will add SharedPreferences persistence once the ingest
     *  pipeline lands and shadow survival across restarts matters. */
    fun setEnabled(on: Boolean) {
        enabled = on
        try {
            ForensicLogger.lifecycle(
                "PERPS_LANE_GATE_$LANE_KEY",
                "enabled=$on phase=1_shadow_paper_only mode=learning_only",
            )
            PipelineHealthCollector.labelInc("PERPS_LANE_GATE_" + if (on) "ENABLED" else "DISABLED")
        } catch (_: Throwable) {}
    }

    /** Universal check — TradeAuthorizer / FDG can call this to enrich telemetry
     *  without forcing a hard block until Phase 2 lands the actual perp signal
     *  path. In Phase 1, always returns false (never admits a perps trade). */
    fun canAdmitPerpsTrade(): Boolean {
        if (!enabled) return false
        // Phase 1 hard-block: even when the gate is "enabled", we do NOT admit
        // real perps trades — the shadow-paper ingest and the LLM-Lab shadow
        // proof must both land in Phase 2 first.
        return false
    }

    fun isShadowEnabled(): Boolean = enabled

    /** Diagnostic string for the unified operational report. */
    fun statusLine(): String {
        val on = enabled
        return "PerpsLaneGate: enabled=$on phase=1_skeleton admit=${canAdmitPerpsTrade()} note=shadow_only_until_phase2_learn_gate_lands"
    }
}
