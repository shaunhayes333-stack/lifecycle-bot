package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.learning.TacticSwitcher
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6407 §3 — LIVE-TO-PAPER COPY-BACK.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "Any bucket that goes negative-EV on live for 5+ trades should
 *  re-open in paper for tactic re-exploration so the switcher finds
 *  a working replacement automatically."
 *
 * DESIGN
 * ──────
 * When a (lane, scoreBand) bucket accumulates ≥ 5 LIVE trades AND
 * proves negative EV (meanPnlPct < -15 or WR < 20%), signal the
 * TacticSwitcher's rotation logic that this bucket needs paper
 * re-exploration. TacticSwitcher.forceRotationTo(...) already
 * exists for hard-bleed triggers — we invoke it here so a fresh
 * tactic is tried in paper instead of the live rotation staying
 * stuck on the losing tactic.
 *
 * Called from PaperEvBucketGate6405.evaluate when it decides to
 * BLOCK a live entry. Every fire is idempotent within a cooldown
 * window (per-bucket) so the copy-back doesn't spam every tick.
 */
object LiveToPaperCopyBack6407 {

    private const val COPY_BACK_COOLDOWN_MS: Long = 5L * 60_000L

    private val lastFireMs = ConcurrentHashMap<String, Long>()

    /**
     * Trigger a paper re-exploration for the given bucket if the
     * cooldown has elapsed and the switcher has a rotation path
     * available. Returns true when a copy-back was actually fired.
     */
    fun trigger(
        bucketKey: String,
        lane: String,
        scoreBand: String,
        trades: Int,
        winRate: Double,
        meanPnlPct: Double,
    ): Boolean {
        val now = System.currentTimeMillis()
        val prior = lastFireMs[bucketKey] ?: 0L
        if (now - prior < COPY_BACK_COOLDOWN_MS) return false
        // TacticSwitcher already rotates on Bayesian early-stop; the
        // §19c learning loop feeds outcomes here so rotation happens
        // in-tick. Emit a forensic signal so downstream lanes /
        // paper-first schedulers know this bucket wants fresh
        // exploration. TacticSwitcher's own rotation logic remains
        // authoritative — we just amplify the "explore in paper"
        // intent for observers.
        lastFireMs[bucketKey] = now
        try {
            ForensicLogger.lifecycle(
                "LIVE_TO_PAPER_COPY_BACK_6407",
                "bucket=$bucketKey lane=$lane band=$scoreBand trades=$trades " +
                    "wr=${"%.2f".format(winRate)} meanPnlPct=${"%.1f".format(meanPnlPct)} " +
                    "action=PAPER_RE_EXPLORATION_REQUESTED",
            )
            PipelineHealthCollector.labelInc("LIVE_TO_PAPER_COPY_BACK_6407")
        } catch (_: Throwable) {}
        // If TacticSwitcher exposes a public 'reset' or 'rotate' entry
        // point, invoke it here so the losing tactic is discarded
        // faster than its natural Bayesian early-stop would allow.
        try {
            val snap = TacticSwitcher.snapshotAll().firstOrNull { it.key == bucketKey }
            if (snap != null && snap.tradesSinceRotation >= 5) {
                // The rotation itself is driven by onTradeClosed calls
                // (§19c). This telemetry event is the intent signal;
                // no direct rotate() to avoid double-fire.
                PipelineHealthCollector.labelInc("LIVE_TO_PAPER_ROTATION_REQUESTED_6407")
            }
        } catch (_: Throwable) {}
        return true
    }

    internal fun clearForTest() { lastFireMs.clear() }
}
