package com.lifecyclebot.engine

import org.json.JSONObject

/**
 * V5.0.8399 §RUGCHECK_SEMANTICS — one place where the provider's RISK score becomes this app's SAFETY score.
 *
 * RugCheck (`/v1/tokens/{mint}/report`) returns `score_normalised` 0..100 as a RISK score: 0 = no risks detected,
 * ≤ 30 "Good", 30..60 caution, ≥ 60 danger; raw `score` is the unnormalised risk sum (< 1000 ≈ Good). Every consumer in
 * this app — TokenSafetyChecker's ladder, MoonshotTraderAI's floor, FinalDecisionGate, LifecycleStrategy, HardRugPreFilter,
 * PreTradeHardGate, QuarantineStore, SecondScorer, DataPipeline, the lane adapters' `?: 100` defaults — reads
 * `rugcheckScore` as a SAFETY score: 0 = confirmed rug (hard, both modes), 1 = the app's own PENDING marker, 2..14 risky,
 * 50 neutral default, 100 clean. The raw provider value had been consumed as-is at three ingestion points, so the cleanest
 * launches (risk 0) were hard-blocked as CONFIRMED RUG, risk 1 became "pending", risk 2..14 (RugCheck "Good") was refused as
 * "very risky", and only risk ≥ 15 passed the Moonshot floor — the lane preferred the riskier end of its universe
 * (5.0.8398: LANE_RUGCHECK=1129 of 1689 MOONSHOT pre-intent refusals in ten minutes).
 *
 * safety = 100 - risk; risk ≥ 99 -> 0 (confirmed), so the value 1 never comes from the provider and stays the pending
 * marker; absent / malformed -> -1 (unfetched = pending downstream, never a refusal).
 */
object RugcheckScore8399 {
    const val CONFIRMED_RISK_8399 = 99.0
    const val RAW_SCORE_DIVISOR_8399 = 100.0   // raw < 1000 ≈ Good -> risk < 10
    /** V5.0.8418 — the highest risk a raw-only (unnormalised) report can carry: very risky (safety 2), not confirmed. */
    const val RAW_ONLY_MAX_RISK_8418 = 98.0

    /** The provider's risk 0..100 (NaN when absent), from `score_normalised`, `scoreNormalized`, or raw `score`. */
    fun risk8399(json: JSONObject?): Double {
        if (json == null) return Double.NaN
        val r = when {
            json.has("score_normalised") -> json.optDouble("score_normalised", Double.NaN)
            json.has("scoreNormalized") -> json.optDouble("scoreNormalized", Double.NaN)
            // V5.0.8418 — the raw score is an uncapped risk SUM: /100 is an approximation of the 0..100 scale, so a raw-only
            // report can read very risky but never reaches the confirmed ceiling (raw 20,000 was a "confirmed rug").
            json.has("score") -> json.optDouble("score", Double.NaN).let { if (it.isNaN() || it < 0) it else minOf(it / RAW_SCORE_DIVISOR_8399, RAW_ONLY_MAX_RISK_8418) }
            else -> Double.NaN
        }
        return if (r.isNaN() || r < 0) Double.NaN else r.coerceIn(0.0, 100.0)
    }

    /** The app's safety score: -1 unfetched, 0 confirmed rug, 2..100 otherwise (1 is reserved as the pending marker). */
    fun safety8399(risk: Double): Int = when {
        risk.isNaN() -> -1
        risk >= CONFIRMED_RISK_8399 -> 0
        else -> (100.0 - risk).toInt().coerceIn(2, 100)
    }

    fun safety8399(json: JSONObject?): Int {
        val risk = risk8399(json)
        val s = safety8399(risk)
        try {
            PipelineHealthCollector.labelInc("RUGCHECK_RISK_BAND_8399_" + when {
                risk.isNaN() -> "UNFETCHED"; risk >= CONFIRMED_RISK_8399 -> "CONFIRMED"; risk >= 60.0 -> "DANGER"; risk >= 30.0 -> "CAUTION"; else -> "GOOD"
            })
        } catch (_: Throwable) {}
        return s
    }

    /**
     * V5.0.8418 — TokenSafetyChecker's safety score for one /report response about [requestMint]. A report for another mint
     * (echo mismatch) is no evidence about this one (-1, unfetched). `rugged: true` is the confirmed rug (0). The score's
     * confirmed ceiling counts only from a COMPLETE report (risk inventory present, 8374): a score-only answer at the
     * ceiling stays very risky (2) — its live spend is already deferred until the inventory lands — instead of a
     * permanent "CONFIRMED RUG" ban on a response that cannot name a single finding.
     */
    fun reportSafety8418(json: JSONObject?, requestMint: String): Int {
        if (json == null) return -1
        val echoed = json.optString("mint", "")
        if (echoed.isNotBlank() && requestMint.isNotBlank() && echoed != requestMint) return -1
        if (ruggedFlag8399(json)) return 0
        val s = safety8399(json)
        val risksPresent = json.optJSONArray("risks") != null
        return if (s == 0 && !risksPresent) 2 else s
    }

    /** `rugged: true` from the provider is a hard, mode-independent fact regardless of the score. */
    fun ruggedFlag8399(json: JSONObject?): Boolean = json?.optString("rugged", "")?.lowercase().let { it == "true" || it == "yes" }
}
