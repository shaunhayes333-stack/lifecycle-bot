package com.lifecyclebot.engine.truth

/** A terminal close with no recoverable entry features stays in financial P&L, outside entry-feature training. */
object EntryLearningExclusion8239 {
    fun reason8239(consumer: String, positionId: String, mint: String, mode: String, ageMs: Long,
                     persistenceReady: Boolean, readFailed: Boolean, entry: EntryStrategySnapshot6450.Snapshot?): String? {
        if (consumer != "MemeCausalLearning6568" || !persistenceReady || readFailed || ageMs < 120_000L) return null
        return when {
            entry == null -> "ENTRY_FEATURES_UNOBSERVED_8239"
            entry.positionId != positionId || entry.mint != mint -> "ENTRY_FEATURE_IDENTITY_MISMATCH_8239"
            entry.mode8090 in setOf("live", "LIVE", "paper", "PAPER") && !entry.mode8090.equals(mode, true) -> "ENTRY_FEATURE_MODE_MISMATCH_8239"
            else -> null
        }
    }
}
