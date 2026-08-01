package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6409 §1 — GROWTH DASHBOARD SNAPSHOT TILE.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "Add a snapshot tile showing GROWTH_RUNNER_CAP_RELAX_6408 +
 *  RUNNER_FLOW_BOOST_ELITE_6407 + LOSER_BUCKET_COOLDOWN_APPLIED_6405
 *  counts so operators eyeball the compounding vs defence balance
 *  in one glance."
 *
 * DESIGN
 * ──────
 * Report-only. Reads label counts already recorded by
 * PipelineHealthCollector and returns a compact tile string:
 *
 *   [growth6409 capRelax=<n> eliteBoost=<n> flowBoost=<n>
 *    loserCooldown=<n> evGate=<n> rollup=<n>]
 *
 * Consumed by OperatorAuxiliaryStatusDigest so the operator sees
 * runner-compounding vs bucket-defence balance in every status
 * dump. Zero authority over sizing/gates/execution.
 */
object GrowthDashboardSnapshot6409 {

    private fun snap(key: String): Long = try {
        PipelineHealthCollector.labelCountSnapshot(key)
    } catch (_: Throwable) { 0L }

    fun tile(): String {
        val capRelax = snap("GROWTH_RUNNER_CAP_RELAX_6408")
        val eliteBoost = snap("RUNNER_FLOW_BOOST_ELITE_6407")
        val flowBoost = snap("RUNNER_FLOW_BOOST_6405")
        val loserCooldown = snap("LOSER_BUCKET_COOLDOWN_APPLIED_6405")
        val evGate = snap("PAPER_EV_BUCKET_HARD_BLOCK_6405")
        val rollup = snap("REALISED_EV_ROLLUP_6409_EMIT")
        val turbo = snap("SMALL_WALLET_TURBO_6409")
        val rollLine = try {
            RealisedEvRollUp6409.statusLine()
        } catch (_: Throwable) { "rollup_unavailable" }
        return "growth6409 capRelax=$capRelax eliteBoost=$eliteBoost flowBoost=$flowBoost " +
            "loserCooldown=$loserCooldown evGate=$evGate rollup=$rollup smallWalletTurbo=$turbo " +
            "trajectory=[$rollLine]"
    }
}
