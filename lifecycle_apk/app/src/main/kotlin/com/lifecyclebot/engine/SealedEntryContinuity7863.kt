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
        if (observedAt <= 0L) return null
        // V5.0.7868 — a sealed mark past its 120 s execution freshness is
        // REVALIDATED, not dropped: a fresh canonical mark within
        // REVALIDATE_MAX_MOVE_FRAC_7868 of the sealed price carries the entry; a
        // material move refuses by name (5.0.7867 ENTRY_MARKET_SNAPSHOT_MISSING_DEFERRED=3).
        if (nowMs - observedAt !in -5_000L..120_000L) return revalidated7868(ts, intent, nowMs)
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

    internal const val REVALIDATE_MAX_MOVE_FRAC_7868 = 0.15

    /** Pure: does a fresh price revalidate the sealed one? */
    internal fun withinRevalidationBand7868(sealedPx: Double, freshPx: Double): Boolean =
        sealedPx.isFinite() && sealedPx > 0.0 && freshPx.isFinite() && freshPx > 0.0 &&
            kotlin.math.abs(freshPx / sealedPx - 1.0) <= REVALIDATE_MAX_MOVE_FRAC_7868

    private fun revalidated7868(ts: TokenState, intent: ExecutableOpenGate.ExecutionIntent, nowMs: Long): MintEntryMarketSnapshot? {
        val fresh = listOf(
            com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE,
            com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570.OBSERVATION_SCORING,
        ).firstNotNullOfOrNull { purpose ->
            try { com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.getFresh6734(ts.mint, purpose, nowMs) } catch (_: Throwable) { null }
        } ?: return runtimeRevalidated7960(ts, intent, nowMs)
        val freshPx = fresh.priceUsd.value.toDouble()
        if (!withinRevalidationBand7868(intent.executableMarkPriceUsd6613, freshPx)) {
            try { PipelineHealthCollector.labelInc("ENTRY_SNAPSHOT_STALE_PRICE_MOVED_7868") } catch (_: Throwable) {}
            return null
        }
        val snap = MintEntryMarketSnapshot.fromCanonicalMark6735(
            ts.mint, fresh, ts.lastMcap, ts.lastPriceDex.ifBlank { "UNKNOWN" }, nowMs,
            observedLiquidityUsd7321 = intent.liquidityUsd,
        ) ?: return null
        try { PipelineHealthCollector.labelInc("ENTRY_SNAPSHOT_REVALIDATED_7868") } catch (_: Throwable) {}
        return snap
    }

    /**
     * V5.0.7960 — no fresh canonical mark: the token's own runtime price, observed within
     * [RUNTIME_MAX_AGE_MS_7960] (pump trade stream / scanner), revalidates the sealed price
     * inside the same 15% band. 5.0.7958 live: QUALITY approvals died MARK_CHOKED
     * (ENTRY_MARKET_SNAPSHOT_MISSING_DEFERRED) with a live tape still printing.
     */
    private fun runtimeRevalidated7960(ts: TokenState, intent: ExecutableOpenGate.ExecutionIntent, nowMs: Long): MintEntryMarketSnapshot? {
        val px = ts.lastPrice
        val at = ts.lastPriceUpdate
        if (!runtimeUsable7960(intent.executableMarkPriceUsd6613, px, at, nowMs)) return null
        val snap = MintEntryMarketSnapshot(
            priceUsd = px,
            marketCapUsd = 0.0,
            liquidityUsd = intent.liquidityUsd,
            poolAddress = "MINT_ROUTE:${intent.mint}",
            priceSource = "RUNTIME_REVALIDATED_7960:" + ts.lastPriceSource.ifBlank { "runtime" }.take(40),
            dex = "UNKNOWN",
            capturedAtMs = at,
        ).takeIf { it.valid } ?: return null
        try { PipelineHealthCollector.labelInc("ENTRY_SNAPSHOT_RUNTIME_REVALIDATED_7960") } catch (_: Throwable) {}
        return snap
    }

    internal const val RUNTIME_MAX_AGE_MS_7960 = 20_000L

    /** Pure: a runtime price fresh enough and within the band of the sealed price. */
    internal fun runtimeUsable7960(sealedPx: Double, px: Double, atMs: Long, nowMs: Long): Boolean =
        atMs > 0L && nowMs - atMs in -5_000L..RUNTIME_MAX_AGE_MS_7960 && withinRevalidationBand7868(sealedPx, px)
}
