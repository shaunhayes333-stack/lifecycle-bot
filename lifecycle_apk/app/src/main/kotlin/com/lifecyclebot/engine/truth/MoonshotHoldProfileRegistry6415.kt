package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6415 §B — MOONSHOT HOLD PROFILE REGISTRY.
 *
 * OPERATOR DIRECTIVE (Feb 2026):
 * "buy a good sized chunk and hold for huge profits."
 *
 * DESIGN
 * ──────
 * A per-mint registry of exit-behaviour overrides. When
 * [EarlyMoonshotHunter6415] rates a candidate ELITE it stamps the
 * mint here with PATIENT_HOLD_MOONSHOT_6415. Downstream exit code
 * consults `profile(mint)` before firing SL / TP:
 *
 *   ELITE_MOONSHOT_PATIENT_HOLD  minSlTrigger=-40% minTpTrigger=+400%
 *                                  trailPct=30% (from peak)
 *   STRONG_MOONSHOT_STANDARD     normal profile (no override)
 *   NONE                          default profile
 *
 * shouldSuppressSl(mint, pnlPct) returns true when the position is
 * an elite moonshot AND pnl > -40% (i.e. still within the patient-
 * hold band). Same for shouldSuppressTp / trailStopPct.
 *
 * Records auto-expire 24h after stamping so a stale entry can't
 * pin a position forever.
 */
object MoonshotHoldProfileRegistry6415 {

    enum class Profile { NONE, STRONG_MOONSHOT_STANDARD, ELITE_MOONSHOT_PATIENT_HOLD }

    data class Record(val profile: Profile, val stampedAtMs: Long, val ttlMs: Long = 24L * 60 * 60_000L)

    private val records = ConcurrentHashMap<String, Record>()

    fun registerElite(mint: String, symbol: String, reason: String) {
        records[mint] = Record(Profile.ELITE_MOONSHOT_PATIENT_HOLD, System.currentTimeMillis())
        try {
            ForensicLogger.lifecycle(
                "MOONSHOT_HOLD_PROFILE_ELITE_6415",
                "mint=${mint.take(10)} sym=$symbol profile=ELITE_MOONSHOT_PATIENT_HOLD reason=${reason.take(80)}",
            )
            PipelineHealthCollector.labelInc("MOONSHOT_HOLD_PROFILE_ELITE_6415")
        } catch (_: Throwable) {}
    }

    fun registerStrong(mint: String, symbol: String, reason: String) {
        records[mint] = Record(Profile.STRONG_MOONSHOT_STANDARD, System.currentTimeMillis())
        try {
            ForensicLogger.lifecycle(
                "MOONSHOT_HOLD_PROFILE_STRONG_6415",
                "mint=${mint.take(10)} sym=$symbol profile=STRONG_MOONSHOT_STANDARD reason=${reason.take(80)}",
            )
            PipelineHealthCollector.labelInc("MOONSHOT_HOLD_PROFILE_STRONG_6415")
        } catch (_: Throwable) {}
    }

    fun profile(mint: String): Profile {
        val r = records[mint] ?: return Profile.NONE
        if (System.currentTimeMillis() - r.stampedAtMs > r.ttlMs) {
            records.remove(mint, r)
            return Profile.NONE
        }
        return r.profile
    }

    /**
     * When elite, refuse to trigger SL until pnl < -40%. Preserves
     * the 26x runway a moonshot needs to breathe through -20% mid-run
     * pullbacks.
     */
    fun shouldSuppressSl(mint: String, pnlPct: Double): Boolean {
        if (profile(mint) != Profile.ELITE_MOONSHOT_PATIENT_HOLD) return false
        if (pnlPct.isFinite() && pnlPct <= -40.0) return false
        try { PipelineHealthCollector.labelInc("MOONSHOT_SL_SUPPRESSED_6415") } catch (_: Throwable) {}
        return true
    }

    /**
     * When elite, refuse to trigger TP until pnl > +400% (5x). Lets
     * the 26x runners actually run instead of clipping at the usual
     * mid-run TP ladder.
     */
    fun shouldSuppressTp(mint: String, pnlPct: Double): Boolean {
        if (profile(mint) != Profile.ELITE_MOONSHOT_PATIENT_HOLD) return false
        if (pnlPct.isFinite() && pnlPct >= 400.0) return false
        try { PipelineHealthCollector.labelInc("MOONSHOT_TP_SUPPRESSED_6415") } catch (_: Throwable) {}
        return true
    }

    /**
     * Trailing-stop percent from peak once TP suppression releases
     * (i.e. after +400% is reached). Wider than the standard trail
     * so a 26x has room to consolidate mid-run.
     */
    fun trailStopPctFromPeak(mint: String): Double? {
        return if (profile(mint) == Profile.ELITE_MOONSHOT_PATIENT_HOLD) 30.0 else null
    }

    fun statusLine(): String {
        val elite = records.values.count { it.profile == Profile.ELITE_MOONSHOT_PATIENT_HOLD }
        val strong = records.values.count { it.profile == Profile.STRONG_MOONSHOT_STANDARD }
        return "elite=$elite strong=$strong total=${records.size}"
    }

    internal fun resetForTest() { records.clear() }
}
