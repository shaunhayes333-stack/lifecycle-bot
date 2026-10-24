package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState

/**
 * V5.0.8360 §SHADOW_PRICES_ON_ANY_OBSERVED_MARK — owner: "we cant block every trade, especially on the shadow trader."
 *
 * The shadow books (ExploreShadow8347, EvidenceShadow8344) refused an entry whenever `ts.lastPriceUpdate` was older than
 * 45 s, although the tick that marks them reads other observed prices too. This is that one mark read, for the open AND
 * the tick, in a fixed order, each an OBSERVATION under [MAX_AGE_MS_8360]:
 *   TICK    — the token's own tick price (ts.lastPrice at ts.lastPriceUpdate);
 *   LABELER — ForwardReturnLabeler7731.markFor7948 (the freshest registry mark of any purpose, the off-watch mark, an
 *             active curve's reserves; its own 45 s bar);
 *   CURVE   — an active pump.fun curve's observed reserves (CurveReserves7951) x the observed SOL price;
 *   TAPE    — the newest print on the coin's tape (TradeTape8100: an executed trade or a pool-state price) x the SOL price.
 * Nothing is interpolated or carried forward: no observed mark under the bar = no mark (the caller skips, counted).
 */
object ShadowMark8360 {
    const val MAX_AGE_MS_8360 = 60_000L
    const val SRC_TICK_8360 = "TICK"
    const val SRC_LABELER_8360 = "LABELER"
    const val SRC_CURVE_8360 = "CURVE"
    const val SRC_TAPE_8360 = "TAPE"

    private fun ok(px: Double?): Boolean = px != null && px.isFinite() && px > 0.0

    /** Cached observation, not a newly invented mark at the time of this read. */
    data class Observation8372(val priceUsd: Double, val observedAtMs: Long, val source: String)

    /**
     * V5.0.8372 — timestamped version for executable shadow fills. The freshest
     * qualifying observation wins so an old TICK cannot hide a post-trigger tape
     * or registry mark. All inputs are existing caches; no network work occurs.
     * The legacy pair-returning API remains unchanged for its existing readers.
     */
    fun observedTimed8372(ts: TokenState?, mint: String, nowMs: Long = System.currentTimeMillis()): Observation8372? {
        if (mint.isBlank()) return null
        val candidates = ArrayList<Observation8372>(4)
        if (ts != null) candidates += Observation8372(ts.lastPrice, ts.lastPriceUpdate, SRC_TICK_8360)
        try {
            ForwardReturnLabeler7731.observedMark8372(mint, nowMs)?.let {
                candidates += Observation8372(it.first, it.second, SRC_LABELER_8360)
            }
        } catch (_: Throwable) {}
        val solUsd = try { com.lifecyclebot.engine.WalletManager.freshSolPrice8163(nowMs) } catch (_: Throwable) { 0.0 }
        if (solUsd.isFinite() && solUsd > 0.0) {
            try {
                CurveReserves7951.activeExitObservation(mint, nowMs, MAX_AGE_MS_8360)?.let {
                    val px = CurveReserves7951.priceSol7951(it.virtualSol, it.virtualTokens) * solUsd
                    candidates += Observation8372(px, it.observedAtMs, SRC_CURVE_8360)
                }
            } catch (_: Throwable) {}
            try {
                TradeTape8100.lastPrint8360(mint)?.let {
                    candidates += Observation8372(it.first * solUsd, it.second, SRC_TAPE_8360)
                }
            } catch (_: Throwable) {}
        }
        return candidates.filter { ok(it.priceUsd) && it.observedAtMs > 0L &&
            nowMs - it.observedAtMs in 0L..MAX_AGE_MS_8360 }.maxByOrNull { it.observedAtMs }
    }

    /**
     * Pure: the first observed mark in the order tick, labeler, curve, tape. [tickAtMs] / [tapeAtMs] are the observation
     * times (accepted only inside [maxAgeMs], never in the future); [labelerPx] and [curvePx] were already read inside their
     * own age bar (null = none). Null when nothing observed qualifies.
     */
    fun pick8360(
        tickPx: Double, tickAtMs: Long, labelerPx: Double?, curvePx: Double?, tapePx: Double?, tapeAtMs: Long, nowMs: Long,
        maxAgeMs: Long = MAX_AGE_MS_8360,
    ): Pair<Double, String>? {
        if (ok(tickPx) && tickAtMs > 0L && nowMs - tickAtMs in 0L..maxAgeMs) return tickPx to SRC_TICK_8360
        if (labelerPx != null && ok(labelerPx)) return labelerPx to SRC_LABELER_8360
        if (curvePx != null && ok(curvePx)) return curvePx to SRC_CURVE_8360
        if (tapePx != null && ok(tapePx) && tapeAtMs > 0L && nowMs - tapeAtMs in 0L..maxAgeMs) return tapePx to SRC_TAPE_8360
        return null
    }

    /** The observed mark (USD per token) for [mint] and the source that priced it; [ts] may be null (no tick price). */
    fun observed8360(ts: TokenState?, mint: String, nowMs: Long = System.currentTimeMillis()): Pair<Double, String>? {
        if (mint.isBlank()) return null
        val tickPx = ts?.lastPrice ?: 0.0
        val tickAt = ts?.lastPriceUpdate ?: 0L
        pick8360(tickPx, tickAt, null, null, null, 0L, nowMs)?.let { return it }
        return fallback8360(mint, nowMs)
    }

    /** The observed mark without the tick price: labeler, curve, tape (EvidenceShadow8344's 8345 fallback, extended). */
    private fun fallback8360(mint: String, nowMs: Long = System.currentTimeMillis()): Pair<Double, String>? {
        if (mint.isBlank()) return null
        val lab = try { ForwardReturnLabeler7731.markFor7948(mint, { null }, nowMs) } catch (_: Throwable) { null }
        pick8360(0.0, 0L, lab, null, null, 0L, nowMs)?.let { return it }
        val solUsd = try { com.lifecyclebot.engine.WalletManager.freshSolPrice8163(nowMs) } catch (_: Throwable) { 0.0 }
        if (!(solUsd.isFinite() && solUsd > 0.0)) return null
        val curveSol = try { CurveReserves7951.priceSolFor7951(mint, nowMs, MAX_AGE_MS_8360) } catch (_: Throwable) { 0.0 }
        val curve = if (curveSol.isFinite() && curveSol > 0.0) curveSol * solUsd else null
        pick8360(0.0, 0L, null, curve, null, 0L, nowMs)?.let { return it }
        val print = (try { TradeTape8100.lastPrint8360(mint) } catch (_: Throwable) { null }) ?: return null
        return pick8360(0.0, 0L, null, null, print.first * solUsd, print.second, nowMs)
    }
}
