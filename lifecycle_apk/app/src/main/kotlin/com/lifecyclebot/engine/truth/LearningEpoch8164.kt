package com.lifecyclebot.engine.truth

import android.content.Context

/**
 * V5.0.8164 — RESET ALL LEARNING has a durable start line.
 *
 * 5.0.8162 live, minutes after a full learning reset with zero trades: the Tactic Switcher printed
 * LIVE|SHITCOIN REACCUMULATION currentN=4 historicalEntryN=110, LIVE|MOONSHOT BREAKOUT currentN=7, and candidate
 * admission refused MOONSHOT 896 times and CASHGEN 267 times on TACTIC_RECORD_LOSING_8136. The sweep wiped every
 * learning store, but the journal is (rightly) never wiped, and FinalizedLearningReconciler7423 republished every
 * historical close to every learner at boot. JournalCohort6389 marked the reset only in memory, and nothing on the
 * learning path read it.
 *
 * The epoch is the reset time, persisted in its own prefs file (never in the sweep's list). Learning consumers on the
 * finalized bus skip any close settled before it; the audit trail, dashboard, kill switch and causal feedback still
 * see every close.
 */
object LearningEpoch8164 {
    private const val PREFS_8164 = "aate_learning_epoch_8164"
    private const val KEY_EPOCH_8164 = "epoch_ms"

    @Volatile private var epochMs8164 = 0L

    /** Boot: read the persisted epoch (0 = never reset). */
    fun load8164(ctx: Context) {
        epochMs8164 = try {
            ctx.applicationContext.getSharedPreferences(PREFS_8164, Context.MODE_PRIVATE).getLong(KEY_EPOCH_8164, 0L)
        } catch (_: Throwable) { epochMs8164 }
    }

    /** A learning reset happened at [nowMs]: closes settled before it never reach a learner again. */
    fun mark8164(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        epochMs8164 = nowMs
        try {
            ctx.applicationContext.getSharedPreferences(PREFS_8164, Context.MODE_PRIVATE)
                .edit().putLong(KEY_EPOCH_8164, nowMs).commit()
        } catch (_: Throwable) {}
    }

    fun epochMs8164(): Long = epochMs8164

    /** Pure: a close settled at [settledAtMs] predates the learning epoch [epochMs]. Unknown times are not judged. */
    fun preEpoch8164(settledAtMs: Long, epochMs: Long = epochMs8164): Boolean =
        epochMs > 0L && settledAtMs in 1 until epochMs

    internal fun setForTest8164(epochMs: Long) { epochMs8164 = epochMs }
}
