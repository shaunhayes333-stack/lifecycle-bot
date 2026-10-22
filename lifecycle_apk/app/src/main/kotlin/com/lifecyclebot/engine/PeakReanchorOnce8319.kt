package com.lifecyclebot.engine

/**
 * V5.0.8319 §DROP_THE_INFLATED_PEAK_ONCE — before 8319 a confirmed inflow rebased the 24 h peak upward (CapitalDrawdown7948);
 * 8316 read peak24h 0.4888 against the recorded NEW_HIGH 0.3217 (six "deposits", +0.1671 SOL) and a 33% drawdown on a flat
 * book. 8319 stops inflow rebases; this re-anchors the already inflated peak exactly once, live only, through
 * KillSwitch.reanchorExplicitProofRun8093 (marked live equity; chain losses, loss streak, halts and learning untouched).
 * The flag is written only after a successful re-anchor (it returns false until the wallet has been read).
 */
object PeakReanchorOnce8319 {
    const val PREFS_8319 = "aate_migrations_8319"
    const val FLAG_8319 = "peak_reanchor_done"

    /** Pure: should the one-shot run now? */
    fun due8319(alreadyDone: Boolean, live: Boolean): Boolean = !alreadyDone && live

    /** BotService loop (every 12 loops): re-anchors once; true only on the call that did it. */
    fun maybeRun8319(context: android.content.Context, loopCount: Int): Boolean {
        if (loopCount % 12 != 0) return false
        return try {
            val p8319 = context.getSharedPreferences(PREFS_8319, android.content.Context.MODE_PRIVATE)
            if (due8319(p8319.getBoolean(FLAG_8319, false), RuntimeModeAuthority.isLive()) &&
                KillSwitch.reanchorExplicitProofRun8093(context)) {
                p8319.edit().putBoolean(FLAG_8319, true).apply()
                PipelineHealthCollector.labelInc("PEAK_REANCHORED_ONCE_8319")
                true
            } else false
        } catch (_: Throwable) { false }
    }
}
