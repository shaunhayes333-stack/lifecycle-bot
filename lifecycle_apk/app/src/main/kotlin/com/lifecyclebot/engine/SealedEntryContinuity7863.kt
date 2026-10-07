package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/** Read the exact approved observation even after discovery replaces its cache slot. */
internal object SealedEntryContinuity7863 {
    fun marketSnapshot(
        ts: TokenState,
        intent: ExecutableOpenGate.ExecutionIntent?,
        nowMs: Long = System.currentTimeMillis(),
        cached: MintEntryMarketSnapshot? = null,
    ): MintEntryMarketSnapshot? {
        if (intent == null || intent.mint != ts.mint || intent.mode != "LIVE" ||
            !intent.fdgAllowed || intent.fdgVerdict != "BUY" || intent.hardNoReasons.isNotEmpty() ||
            intent.finalDecision6613 != ExecutableOpenGate.CanonicalFinalDecision6613.BUY ||
            (intent.expiresAtMs6613 > 0L && nowMs >= intent.expiresAtMs6613)) return null
        val observedAt = intent.executableMarkTimestampMs6613
        if (observedAt <= 0L || nowMs - observedAt !in -5_000L..120_000L) return null
        if (cached != null && cached.valid && cached.capturedAtMs == observedAt &&
            cached.priceUsd == intent.executableMarkPriceUsd6613 && cached.liquidityUsd == intent.liquidityUsd &&
            cached.priceSource == intent.executableMarkSource6613) return cached
        return MintEntryMarketSnapshot(
            priceUsd = intent.executableMarkPriceUsd6613,
            marketCapUsd = 0.0, // Optional metadata; do not splice a newer candidate's cap into the seal.
            liquidityUsd = intent.liquidityUsd,
            poolAddress = "MINT_ROUTE:${intent.mint}",
            priceSource = intent.executableMarkSource6613,
            dex = "UNKNOWN",
            capturedAtMs = observedAt,
        ).takeIf { it.valid }
    }
}
