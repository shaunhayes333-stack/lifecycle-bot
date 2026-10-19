package com.lifecyclebot.engine.truth

/** A successful owner balance read. Cache readers cannot replace its observation time. */
data class WalletSolObservation8239(
    val owner: String,
    val lamports: Long,
    val contextSlot: Long,
    val startedAtMs: Long,
    val observedAtMs: Long,
    val generation: Long,
) {
    val sol: Double get() = lamports / 1_000_000_000.0

    fun usable8239(nowMs: Long, expectedGeneration: Long, maxAgeMs: Long = 30_000L): Boolean =
        owner.isNotBlank() && lamports >= 0L && contextSlot >= 0L && startedAtMs > 0L &&
            observedAtMs >= startedAtMs && observedAtMs <= nowMs && nowMs - startedAtMs in 0L..maxAgeMs &&
            generation == expectedGeneration
}
