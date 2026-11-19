package com.lifecyclebot.engine.cortex

import org.json.JSONObject

/** The small setup decision exists even when the expensive voter prediction was not sampled. */
data class LanePlaybookDecision8871(
    val decisionId: String,
    val admitted: Boolean,
    val executable: Boolean,
    val staleMark: Boolean,
    val latencyMeasured: Boolean,
    val cohort: String,
    val targetKind: String,
    val priceUsd: Double,
    val priceObservedAtMs: Long,
) {
    fun json(): JSONObject = JSONObject().put("v", 1).put("epoch", CortexForwardEpoch8521.EPOCH)
        .put("id", decisionId).put("admitted", admitted).put("executable", executable).put("stale", staleMark)
        .put("latencyMeasured", latencyMeasured).put("cohort", cohort).put("target", targetKind)
        .put("price", priceUsd.takeIf { it.isFinite() } ?: JSONObject.NULL).put("priceAt", priceObservedAtMs)

    /** Existing source-time, fill, target and plausibility rules; no prediction or model authority is invented here. */
    internal fun exclusion(d: ForwardOutcomeDelivery8404, nowMs: Long): String? {
        if (d.id != decisionId) return "IDENTITY_MISMATCH"
        if (!priceUsd.isFinite() || priceUsd <= 0.0 || priceObservedAtMs <= 0L ||
            d.atMs - priceObservedAtMs !in 0L..CortexLabelHygiene8144.DECISION_MARK_STALE_MS_8871)
            return "INVALID_DECISION_PRICE"
        if (!executable || staleMark) return if (staleMark) "STALE_DECISION_MARK" else "UNEXECUTABLE"
        val proof = d.proof ?: return "UNSTAMPED_OUTCOME"
        if (!proof.matches8395(d.id, proof.horizonMs, proof.observedAtMs, d.net, d.gross)) return "VALUE_MISMATCH"
        if (!d.net.isFinite() || !d.gross.isFinite() || proof.observedAtMs > nowMs) return "INVALID_OUTCOME"
        if (!CortexForwardEpoch8521.matchesTarget(targetKind, d.atMs, proof.horizonMs, proof.observedAtMs))
            return "OUTSIDE_FROZEN_TARGET"
        if (!proof.sourceStamped8521(d.atMs)) return "UNQUALIFIED_SOURCE_OR_FILL"
        if (d.flat) return "FLAT_MARK"
        val executablePct = CortexLabelHygiene8144.executablePct8144(d.net, latencyMeasured)
        val cap = com.lifecyclebot.engine.truth.RobustLabelRead8185.capForKey8185(cohort)
        if (!proof.witnessed8395() && cap.isFinite() && cap > 0.0 &&
            executablePct > cap * CortexGradeBook8144.IMPLAUSIBLE_CAP_MULT_8388) return "IMPLAUSIBLE_UNWITNESSED"
        return null
    }

    companion object {
        fun decode(j: JSONObject): LanePlaybookDecision8871? = try {
            if (j.getInt("v") != 1 || j.getInt("epoch") != CortexForwardEpoch8521.EPOCH) null
            else LanePlaybookDecision8871(j.getString("id"), j.getBoolean("admitted"), j.getBoolean("executable"),
                j.getBoolean("stale"), j.getBoolean("latencyMeasured"), j.getString("cohort"), j.getString("target"),
                j.optDouble("price", Double.NaN), j.getLong("priceAt")).takeIf {
                it.decisionId.isNotBlank() && CortexForwardEpoch8521.frozenTarget(it.targetKind)
            }
        } catch (_: Exception) { null }
    }
}
