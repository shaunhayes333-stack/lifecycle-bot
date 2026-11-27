package com.lifecyclebot.engine.truth

/** Shared evidence semantics; sampled extrema are never held-price witnesses. */
internal object RunnerResearchContract9050 {
    const val ENDPOINT_ONLY = "SAMPLED_ENDPOINT_ONLY"
    fun sampledEndpoint(reason: String, pathKind: String = ""): Boolean =
        pathKind == ENDPOINT_ONLY || reason.startsWith("JUP_8884:") ||
            reason.startsWith("DEX_9015:") || reason.startsWith("CHAIN_8993:") ||
            reason.startsWith("NO_PRICE_JUP_8884:") || reason.startsWith("LATE_READ_8884:") ||
            reason.startsWith("IMPLAUSIBLE_RATIO_8923:") || reason.startsWith("LOSTMARK8983/")

    fun heldPeak(reason: String, peakHeld: Double, pathKind: String = ""): Double =
        if (sampledEndpoint(reason, pathKind)) Double.NaN else peakHeld

    /** A training label must have matured strictly before the evaluated launch's birth. */
    fun maturedBefore(bornMs: Long, horizonMs: Long, boundaryMs: Long): Boolean =
        bornMs > 0L && horizonMs > 0L && boundaryMs > bornMs && boundaryMs - bornMs > horizonMs

    /** A model refitted after a launch began cannot produce a pre-launch forward prediction. */
    fun causalForward(modelAtMs: Long, latestTrainingBornMs: Long, horizonMs: Long, launchBornMs: Long): Boolean =
        modelAtMs > 0L && modelAtMs < launchBornMs &&
            maturedBefore(latestTrainingBornMs, horizonMs, modelAtMs)

    /** Model scores and source/receive clocks must describe the actual crossing, not a later replay. */
    fun validCross(bornMs: Long, eventMs: Long, receivedMs: Long, modelAtMs: Long,
                   priceSol: Double, score: Double, threshold: Double, requiredDelayMs: Long): Boolean =
        bornMs > 0L && eventMs >= bornMs && eventMs - bornMs <= 3_600_000L &&
            receivedMs >= eventMs && receivedMs - eventMs <= 15_000L &&
            modelAtMs > 0L && modelAtMs <= receivedMs &&
            priceSol.isFinite() && priceSol > 0.0 && score.isFinite() && threshold.isFinite() &&
            requiredDelayMs >= maxOf(10_000L, receivedMs - eventMs)
}
