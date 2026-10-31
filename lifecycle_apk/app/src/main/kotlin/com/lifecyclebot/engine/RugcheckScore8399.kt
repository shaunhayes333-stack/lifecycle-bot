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
 *
 * V5.0.8471 read the provider's number the other way (high = good). superseded by V5.0.8472 — RESTORED to the risk
 * reading above on the owner's own live data: the 8471 log (rugcheck8471 line, 97 min) shows reports with NO findings
 * averaging score_normalised 1.0 and reports with 2+ danger findings averaging 65.5, and the flipped reading hard-blocked
 * 20 coins as confirmed rugs (SAFETY/HARD=Rugcheck=20) — clean coins scoring ~1. Kept from 8471: the report's own findings
 * cap the number ([findingsSafety8471]: `rugged` 0, danger / warn ceilings), whatever it says.
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
            json.has("score") -> rawRisk8418(json.optDouble("score", Double.NaN))
            else -> Double.NaN
        }
        return if (r.isNaN() || r < 0) Double.NaN else r.coerceIn(0.0, 100.0)
    }

    /**
     * Pure (V5.0.8418 §RAW_SCORE_BANDS): the provider's raw (unnormalised, uncapped) risk SUM placed on the 0..100 risk scale
     * by RugCheck's own raw bands (< 1000 good, 1000..2500 low, 2500..5000 elevated, >= 5000 danger — the ExternalAlphaFeeds
     * bands of 6411), not by a linear divide: raw / 100 read raw 5000 (danger) as risk 50 = safety 50, the app's NEUTRAL
     * default. Raw >= 5000 lands at the top of the app's very-risky band (risk 98 = safety 2: refused by MOONSHOT, penalised
     * by the ladders); it never reaches the confirmed ceiling (99) — only `rugged` or a complete normalised report does.
     * NaN for absent / negative.
     */
    private fun rawRisk8418(raw: Double): Double = when {
        raw.isNaN() || raw < 0.0 -> Double.NaN
        raw < 1_000.0 -> raw / RAW_SCORE_DIVISOR_8399                                      // 0..10
        raw < 2_500.0 -> 10.0 + (raw - 1_000.0) / 1_500.0 * 20.0                           // 10..30
        raw < RAW_DANGER_8418 -> 30.0 + (raw - 2_500.0) / 2_500.0 * 30.0                   // 30..60
        else -> RAW_ONLY_MAX_RISK_8418                                                       // 98 = safety 2
    }
    private const val RAW_DANGER_8418 = 5_000.0

    /** The app's safety score: -1 unfetched, 0 confirmed rug, 2..100 otherwise (1 is reserved as the pending marker). */
    fun safety8399(risk: Double): Int = when {
        risk.isNaN() -> -1
        risk >= CONFIRMED_RISK_8399 -> 0
        else -> (100.0 - risk).toInt().coerceIn(2, 100)
    }

    fun safety8399(json: JSONObject?): Int {
        val risk = risk8399(json)
        // V5.0.8471 — the report's own danger / warn findings cap the number (scanner, pipeline, creator history read this).
        val s = safety8399(risk).let { v -> findingCounts8471(json)?.let { (d, w) -> if (v >= 0) minOf(v, findingsSafety8471(d, w)) else v } ?: v }
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
        if (s == 0 && !risksPresent) return 2
        // V5.0.8471 — the report's own findings cap the number: a coin named with danger-level risks is never read as clean.
        val counts = findingCounts8471(json) ?: return s
        noteDirection8471(json, counts.first, counts.second)
        return if (s < 0) s else minOf(s, findingsSafety8471(counts.first, counts.second))
    }

    /** `rugged: true` from the provider is a hard, mode-independent fact regardless of the score. */
    fun ruggedFlag8399(json: JSONObject?): Boolean = json?.optString("rugged", "")?.lowercase().let { it == "true" || it == "yes" }

    // ── V5.0.8471: the report's findings, a fresh mint RugCheck has not indexed, and the number beside the findings ──

    /** Pure: the safety ceiling (0..100, higher = safer) RugCheck's own findings allow: [danger] / [warn] risk counts. */
    fun findingsSafety8471(danger: Int, warn: Int): Int = when {
        danger >= 3 -> 8
        danger == 2 -> 20
        danger == 1 -> 35
        warn >= 3 -> 60
        warn >= 1 -> 75
        else -> 100
    }

    /** Pure: (danger, warn) counts of a report's `risks`, or null when the report carries no risk inventory. */
    fun findingCounts8471(json: JSONObject?): Pair<Int, Int>? {
        val risks = json?.optJSONArray("risks") ?: return null
        var danger = 0
        var warn = 0
        for (i in 0 until risks.length()) {
            val level = risks.optJSONObject(i)?.optString("level", "")?.lowercase() ?: continue
            if (level.contains("danger")) danger++ else if (level.contains("warn")) warn++
        }
        return danger to warn
    }

    /** Pure: RugCheck's answer for a mint it has not indexed yet (a fresh launch): 404, or 400 {"error":"not found"}. */
    fun notIndexed8471(httpCode: Int, body: String?): Boolean =
        httpCode == 404 || (httpCode == 400 && body?.contains("not found", ignoreCase = true) == true)

    /** A not-indexed mint is asked again after this long, not on every safety pass (it cannot be indexed on demand). */
    const val NOT_INDEXED_REASK_MS_8471 = 60_000L
    private val notIndexedAt8471 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val notIndexedN8471 = java.util.concurrent.atomic.AtomicLong(0)

    fun noteNotIndexed8471(mint: String, nowMs: Long) {
        if (notIndexedAt8471.size > 5_000) notIndexedAt8471.clear()
        notIndexedAt8471[mint] = nowMs
        notIndexedN8471.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RUGCHECK_NOT_INDEXED_8471") } catch (_: Throwable) {}
    }

    fun notIndexedRecently8471(mint: String, nowMs: Long): Boolean =
        notIndexedAt8471[mint]?.let { nowMs - it in 0L until NOT_INDEXED_REASK_MS_8471 } == true

    // The provider's number beside its findings, for the log: mean score_normalised of clean reports vs 2+ danger reports.
    private val findingsN8471 = java.util.concurrent.atomic.AtomicLong(0)
    private var cleanN8471 = 0L
    private var dangerN8471 = 0L
    private var cleanSum8471 = 0.0
    private var dangerSum8471 = 0.0

    private fun noteDirection8471(json: JSONObject, danger: Int, warn: Int) {
        findingsN8471.incrementAndGet()
        val n = json.optDouble("score_normalised", Double.NaN).takeIf { it.isFinite() } ?: return
        synchronized(this) {
            if (danger == 0 && warn == 0) { cleanN8471++; cleanSum8471 += n }
            else if (danger >= 2) { dangerN8471++; dangerSum8471 += n }
        }
    }

    fun statusLine8471(): String = synchronized(this) {
        val cm = if (cleanN8471 > 0) "%.1f".format(cleanSum8471 / cleanN8471) else "-"
        val dm = if (dangerN8471 > 0) "%.1f".format(dangerSum8471 / dangerN8471) else "-"
        "rugcheck8471[reports=${findingsN8471.get()} notIndexed=${notIndexedN8471.get()} scoreNormalised clean=n$cleanN8471/$cm " +
            "danger2+=n$dangerN8471/$dm read=high-is-risk]"
    }
}
