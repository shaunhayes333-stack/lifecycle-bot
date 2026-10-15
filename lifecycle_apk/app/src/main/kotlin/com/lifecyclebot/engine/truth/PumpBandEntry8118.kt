package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8118 §ADMIT_THE_LAUNCH_WHEN_IT_ENTERS_THE_PROVEN_BAND.
 *
 * The forward labels' best cells are PUMP_PORTAL_WS|<lane>|MC_10K_100K|AGE_LT15M (+460..+708%, n40-78) while the same
 * source under $10k loses (-30..-35%). In live mode a fresh create is parked in probation (cold pump base: no volume,
 * curve depth under $1.5k) with no token row, and nothing re-admitted it when it crossed $10k: trade frames for a mint
 * without a row were dropped (applyPumpTradeMark7278 `status.tokens[mint] ?: return`), probation PRICE_UP has no
 * callers, and the launch-tape re-admit carried no cap. "Intake by source" showed 4 PumpPortal intakes per session.
 *
 * A PumpPortal launch with no token row whose trade frame puts it in [BAND_LO_USD, BAND_HI_USD) while it is under
 * [MAX_AGE_MS] old is admitted once, under its true source (PUMP_PORTAL_WS), with the curve's real depth. Every intake,
 * safety, FDG and buy-door gate still runs.
 */
object PumpBandEntry8118 {
    private const val BAND_LO_USD = 10_000.0
    private const val BAND_HI_USD = 100_000.0
    private const val MAX_AGE_MS = 15L * 60_000L
    private const val MAX_TRACKED = 3_000

    data class Meta(val symbol: String, val name: String)

    private val meta = ConcurrentHashMap<String, Meta>()
    private val fired = ConcurrentHashMap.newKeySet<String>()

    /** PumpFunWS create frame (before the throttle): remember the launch's name for a later band entry. */
    fun noteCreate8118(mint: String, symbol: String, name: String) {
        if (mint.isBlank()) return
        if (meta.size >= MAX_TRACKED) meta.keys.take(MAX_TRACKED / 10).forEach { meta.remove(it) }
        meta.putIfAbsent(mint, Meta(symbol.ifBlank { mint.take(6) }, name.ifBlank { symbol }))
    }

    /** Pure: does a launch at [capUsd], [ageMs] old, enter the proven band now? */
    fun inBand8118(capUsd: Double, ageMs: Long): Boolean =
        capUsd.isFinite() && capUsd >= BAND_LO_USD && capUsd < BAND_HI_USD && ageMs in 0L until MAX_AGE_MS

    /** BotService trade frame: the launch's name when it enters the band for the first time (claims it), else null. */
    fun claim8118(mint: String, capUsd: Double, ageMs: Long): Meta? {
        if (!inBand8118(capUsd, ageMs)) return null
        val m = meta[mint] ?: return null
        if (!fired.add(mint)) return null
        if (fired.size > MAX_TRACKED) fired.clear()
        return m
    }
}
