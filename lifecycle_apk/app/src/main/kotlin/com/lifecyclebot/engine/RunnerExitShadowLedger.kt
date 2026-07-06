package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/** V5.0.4289 — offline runner-exit shadow ledger. Background/report only, no sell authority.
 *  V5.0.6144 — now exposes a bounded lane hold-bias hint for UnifiedExitPolicyHead consumers.
 *  Still no sell authority: it only helps avoid repeating early-runner giveback mistakes.
 */
object RunnerExitShadowLedger {
    data class Shadow(val lane: String, val exitReason: String, val realizedPnlPct: Double, val peakGainPct: Double, val givebackPct: Double, val holdSeconds: Long, val createdAtMs: Long = System.currentTimeMillis())
    private const val MAX_SHADOWS = 320
    private val shadows = ConcurrentLinkedDeque<Shadow>()

    fun recordTerminalExit(lane: String, exitReason: String, realizedPnlPct: Double, peakGainPct: Double, holdSeconds: Long) {
        val peak = peakGainPct.takeIf { it.isFinite() } ?: return
        val realized = realizedPnlPct.takeIf { it.isFinite() } ?: return
        val giveback = (peak - realized).coerceAtLeast(0.0)
        val runner = peak >= 75.0 || exitReason.contains("RUNNER", true) || exitReason.contains("MOON", true)
        if (!runner || giveback < 18.0) return
        shadows.addFirst(Shadow(lane.uppercase().take(32), exitReason.take(72), realized, peak, giveback, holdSeconds.coerceAtLeast(0L)))
        while (shadows.size > MAX_SHADOWS) shadows.pollLast()
        try {
            ForensicLogger.lifecycle("RUNNER_EXIT_SHADOW_LEDGER_4289", "lane=$lane reason=${exitReason.take(48)} realized=${realized.fmtLocal(2)} peak=${peak.fmtLocal(2)} giveback=${giveback.fmtLocal(2)} holdSec=$holdSeconds offline_only=true")
            PipelineHealthCollector.labelInc("RUNNER_EXIT_SHADOW_LEDGER_4289")
        } catch (_: Throwable) {}
    }


    fun laneHoldBias(lane: String, peakGainPct: Double, rawPnlPct: Double): Double {
        val laneKey = lane.uppercase().take(32)
        val peak = peakGainPct.takeIf { it.isFinite() } ?: return 1.0
        val raw = rawPnlPct.takeIf { it.isFinite() } ?: return 1.0
        if (peak < 35.0 || raw < -2.0) return 1.0
        val snap = shadows.filter { it.lane == laneKey }.take(40)
        if (snap.size < 2) return 1.0
        val avgGiveback = snap.map { it.givebackPct }.average().takeIf { it.isFinite() } ?: return 1.0
        val bias = when {
            avgGiveback >= 85.0 -> 1.16
            avgGiveback >= 55.0 -> 1.12
            avgGiveback >= 32.0 -> 1.08
            avgGiveback >= 18.0 -> 1.04
            else -> 1.0
        }
        return bias.coerceIn(1.0, 1.16)
    }

    fun summary(): String {
        val snap = shadows.take(120)
        if (snap.isEmpty()) return "RunnerExitShadowLedger: no shadow runner exits"
        val avgGiveback = snap.map { it.givebackPct }.average()
        val avgPeak = snap.map { it.peakGainPct }.average()
        val byLane = snap.groupBy { it.lane }.entries.sortedByDescending { it.value.size }.take(6).joinToString(",") { "${it.key}:${it.value.size}" }
        return "RunnerExitShadowLedger: cases=${snap.size} avgGiveback=${avgGiveback.fmtLocal(2)} avgPeak=${avgPeak.fmtLocal(2)} lanes=$byLane offline_only=true"
    }

    fun exportState(): String = JSONArray().also { a -> shadows.take(MAX_SHADOWS).forEach { s -> a.put(JSONObject().put("lane", s.lane).put("reason", s.exitReason).put("realized", s.realizedPnlPct).put("peak", s.peakGainPct).put("giveback", s.givebackPct).put("hold", s.holdSeconds).put("createdAt", s.createdAtMs)) } }.toString()
    fun importState(raw: String?) {
        if (raw.isNullOrBlank()) return
        try { val a = JSONArray(raw); shadows.clear(); for (i in 0 until a.length().coerceAtMost(MAX_SHADOWS)) { val o = a.optJSONObject(i) ?: continue; shadows.add(Shadow(o.optString("lane"), o.optString("reason"), o.optDouble("realized"), o.optDouble("peak"), o.optDouble("giveback"), o.optLong("hold"), o.optLong("createdAt"))) } } catch (_: Throwable) {}
    }
    fun reset() { shadows.clear() }
}

private fun Double.fmtLocal(decimals: Int): String = try { java.lang.String.format(java.util.Locale.US, "%.${decimals}f", this) } catch (_: Throwable) { this.toString() }
