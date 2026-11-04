package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Unordered terminal-summary sensitivity research. This is not chronological execution replay or empirical policy proof. */
object CounterfactualReplayEngine {
    enum class AlternativeKind { BANK_25, BANK_50, TRAIL_RUNNER, HARD_STOP_15, HOLD_TO_PEAK_HALF }

    data class ReplayAlternative(
        val kind: AlternativeKind,
        val hypotheticalPnlPct: Double,
        val deltaVsRealizedPct: Double,
        val note: String,
    )

    data class ReplayCase(
        val id: String,
        val lane: String,
        val exitReason: String,
        val realizedPnlPct: Double,
        val peakGainPct: Double,
        val maxLossPct: Double,
        val holdSeconds: Long,
        val alternatives: List<ReplayAlternative>,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    /**
     * Legacy name retained for callers. Coverage is the fraction of summaries containing this arm, not accuracy,
     * confidence, or executable advantage. These cases contain neither event order nor an identified environment.
     */
    data class MctsExitPolicyHint(
        val policy: AlternativeKind,
        val meanHypotheticalDeltaPct: Double,
        val summarySupportShare: Double,
        val sampleCount: Int,
    ) {
        val evidence8527: LaneStrategyEvaluator.Evidence8527 get() = LaneStrategyEvaluator.Evidence8527.SUMMARY_HYPOTHESIS
        val provenance8527: String get() = "UNORDERED_EXTREMA_ENVIRONMENT_UNKNOWN"
    }

    private const val MAX_CASES = 500
    private val cases = CopyOnWriteArrayList<ReplayCase>()

    fun recordTerminalTrade(lane: String, exitReason: String, realizedPnlPct: Double, peakGainPct: Double, maxLossPct: Double = 0.0, holdSeconds: Long = 0L): String {
        if (!realizedPnlPct.isFinite() || !peakGainPct.isFinite() || !maxLossPct.isFinite()) return ""
        val realized = realizedPnlPct.coerceIn(-500.0, 100_000.0)
        val peak = peakGainPct.coerceIn(-100.0, 250_000.0)
        val loss = maxLossPct.coerceIn(-500.0, 0.0)
        val alternatives = buildAlternatives(realized, peak, loss)
        if (alternatives.isEmpty()) return ""
        val id = stableId(lane, exitReason, realized, peak, loss, holdSeconds)
        val replay = ReplayCase(id, lane.uppercase().take(32), exitReason.uppercase().take(96), realized, peak, loss, holdSeconds.coerceAtLeast(0L), alternatives)
        synchronized(cases) {
            cases.removeAll { it.id == id }
            cases.add(replay)
            while (cases.size > MAX_CASES) cases.removeAt(0)
        }
        return id
    }

    fun bestMissedAlternatives(lane: String = "", limit: Int = 12): List<ReplayCase> = synchronized(cases) {
        cases.asSequence()
            .filter { lane.isBlank() || it.lane.equals(lane, ignoreCase = true) }
            .filter { c -> c.alternatives.any { it.deltaVsRealizedPct >= 8.0 } }
            .sortedByDescending { c -> c.alternatives.maxOfOrNull { it.deltaVsRealizedPct } ?: 0.0 }
            .take(limit.coerceIn(1, 50))
            .toList()
    }


    /** Legacy bounded offline MCTS/UCB-style RESEARCH ranking: no simulated tree, event sequence or fill proof. */
    fun mctsExitPolicyHint(lane: String = "", maxCases: Int = 40, rollouts: Int = 96): MctsExitPolicyHint? = synchronized(cases) {
        val sample = cases.asSequence()
            .filter { lane.isBlank() || it.lane.equals(lane, ignoreCase = true) }
            .filter { it.alternatives.isNotEmpty() }
            .toList()
            .takeLast(maxCases.coerceIn(8, 80))
        if (sample.size < 6) return@synchronized null
        val arms = sample.flatMap { it.alternatives }.groupBy { it.kind }
        if (arms.isEmpty()) return@synchronized null
        val total = rollouts.coerceIn(16, 256).toDouble()
        val ranked = arms.map { (kind, alts) ->
            val pulls = alts.size.coerceAtLeast(1).toDouble()
            val avg = alts.map { it.deltaVsRealizedPct }.average()
            val exploration = sqrt(2.0 * kotlin.math.ln(total + 1.0) / pulls)
            val ucb = avg + exploration
            kind to Triple(avg, ucb, alts.size)
        }.sortedByDescending { it.second.second }
        val best = ranked.first()
        val avg = best.second.first.coerceIn(-500.0, 250_000.0)
        val coverage = (best.second.third.toDouble() / sample.size.toDouble()).coerceIn(0.0, 1.0)
        MctsExitPolicyHint(best.first, avg, coverage, sample.size)
    }

    fun policyHints(lane: String = ""): String = synchronized(cases) {
        val sample = bestMissedAlternatives(lane, 20)
        if (sample.isEmpty()) return@synchronized "CounterfactualReplayEngine: no summary hypotheses (no executable replay evidence)"
        val grouped = sample.flatMap { it.alternatives.filter { a -> a.deltaVsRealizedPct >= 8.0 } }
            .groupBy { it.kind }
            .mapValues { (_, alts) -> alts.map { it.deltaVsRealizedPct }.average() }
            .entries.sortedByDescending { it.value }
        val top = grouped.take(3).joinToString { entry -> entry.key.name + ':' + String.format(java.util.Locale.US, "%.1f", entry.value) }
        val mcts = mctsExitPolicyHint(lane)
        val mctsText = if (mcts != null) " summary=${mcts.policy.name}:${String.format(java.util.Locale.US, "%.1f", mcts.meanHypotheticalDeltaPct)} support=${String.format(java.util.Locale.US, "%.2f", mcts.summarySupportShare)}" else ""
        "CounterfactualReplayEngine: cases=${cases.size} hypotheticalTop=$top$mctsText offline_only=true authority=none chronology=missing environment=unknown"
    }

    fun reset() { synchronized(cases) { cases.clear() } }

    fun exportState(): String = synchronized(cases) {
        val arr = JSONArray()
        cases.takeLast(MAX_CASES).forEach { c ->
            val alts = JSONArray()
            c.alternatives.forEach { a -> alts.put(JSONObject().put("kind", a.kind.name).put("hypo", a.hypotheticalPnlPct).put("delta", a.deltaVsRealizedPct).put("note", a.note)) }
            arr.put(JSONObject().put("id", c.id).put("lane", c.lane).put("exitReason", c.exitReason).put("realized", c.realizedPnlPct).put("peak", c.peakGainPct).put("loss", c.maxLossPct).put("holdSeconds", c.holdSeconds).put("createdAt", c.createdAtMs).put("alternatives", alts))
        }
        JSONObject().put("evidence8527", "SUMMARY_HYPOTHESIS").put("cases", arr).toString()
    }

    fun importState(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val arr = JSONObject(raw).optJSONArray("cases") ?: JSONArray()
            val restored = mutableListOf<ReplayCase>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val altsJson = c.optJSONArray("alternatives") ?: JSONArray()
                val alts = mutableListOf<ReplayAlternative>()
                for (j in 0 until altsJson.length()) {
                    val a = altsJson.optJSONObject(j) ?: continue
                    val kind = runCatching { AlternativeKind.valueOf(a.optString("kind")) }.getOrDefault(AlternativeKind.TRAIL_RUNNER)
                    alts.add(ReplayAlternative(kind, a.optDouble("hypo", 0.0), a.optDouble("delta", 0.0), a.optString("note")))
                }
                restored.add(ReplayCase(c.optString("id"), c.optString("lane"), c.optString("exitReason"), c.optDouble("realized", 0.0), c.optDouble("peak", 0.0), c.optDouble("loss", 0.0), c.optLong("holdSeconds", 0L), alts, c.optLong("createdAt", System.currentTimeMillis())))
            }
            synchronized(cases) { cases.clear(); cases.addAll(restored.filter { it.id.isNotBlank() && it.realizedPnlPct.isFinite() && it.peakGainPct.isFinite() &&
                it.maxLossPct.isFinite() && it.alternatives.isNotEmpty() && it.alternatives.all { a ->
                    a.hypotheticalPnlPct.isFinite() && a.deltaVsRealizedPct.isFinite()
                } }.takeLast(MAX_CASES)) }
        } catch (_: Throwable) {}
    }

    private fun buildAlternatives(realized: Double, peak: Double, loss: Double): List<ReplayAlternative> {
        val out = mutableListOf<ReplayAlternative>()
        fun add(kind: AlternativeKind, hypo: Double, note: String) { val h = hypo.coerceIn(-500.0, 250_000.0); out.add(ReplayAlternative(kind, h, h - realized, note)) }
        if (peak >= 25.0) add(AlternativeKind.BANK_25, 25.0, "Hypothesis: full fill at +25%; order, execution and costs unproved")
        if (peak >= 50.0) add(AlternativeKind.BANK_50, 50.0, "Hypothesis: full fill at +50%; order, execution and costs unproved")
        if (peak >= 100.0) add(AlternativeKind.TRAIL_RUNNER, max(35.0, peak * 0.38), "Hypothesis: runner trail; peak-to-exit sequence and fill unproved")
        if (realized < -15.0 || loss <= -15.0) add(AlternativeKind.HARD_STOP_15, -15.0, "Hypothesis: stop fill at -15%; gaps and failed sales unrepresented")
        if (peak > realized + 20.0) add(AlternativeKind.HOLD_TO_PEAK_HALF, min(peak * 0.5, peak - 5.0), "Hypothesis: half-peak capture; no executable exit timestamp")
        return out.sortedByDescending { it.deltaVsRealizedPct }.take(5)
    }

    private fun stableId(vararg parts: Any): String {
        val raw = parts.joinToString("|") { it.toString().take(80) }
        return "cfreplay_" + java.lang.Long.toUnsignedString(raw.hashCode().toLong() xor raw.length.toLong())
    }
}
