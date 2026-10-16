package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 — Helius-decoded swaps reach the executed-trade tape.
 *
 * TradeTape8100 (buy pressure / change / shape, and LocalTapeBars8128's keyless one-minute bars) was written only by
 * the PumpPortal trade stream. When PumpPortal refused or dropped a mint, the Helius websocket still decoded the same
 * pump.fun TradeEvent with exact SOL and token amounts — it priced held positions (7787) and fed launch flow (7773),
 * but the tape stayed empty and the local bar rebuild had nothing to build from: a gap with real prints on the wire.
 *
 * A decoded swap is admitted only when it carries a real executed price (SOL / tokens, both observed and positive),
 * PumpPortal has not delivered the mint inside [PUMPPORTAL_PRIORITY_MS_8144] (one feed per mint, no double count), and
 * its signature was not already taped. Nothing is interpolated: a minute nobody traded stays empty.
 */
object ProofDataSwapTape8144 {
    private const val PUMPPORTAL_PRIORITY_MS_8144 = 20_000L
    private const val SEEN_CAP_8144 = 20_000

    private val seen8144 = java.util.Collections.synchronizedMap(object : java.util.LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > SEEN_CAP_8144
    })
    private val taped = AtomicLong(0L)
    private val skippedPumpPortal = AtomicLong(0L)
    private val skippedDuplicate = AtomicLong(0L)
    private val skippedUnpriced = AtomicLong(0L)

    /** Pure: may this decoded swap enter the tape? [pumpPortalAgeMs] is the time since PumpPortal last delivered the mint. */
    fun admit8144(sol: Double, tokenAmount: Double, pumpPortalAgeMs: Long): Boolean =
        sol.isFinite() && sol > 0.0 && tokenAmount.isFinite() && tokenAmount > 0.0 &&
            pumpPortalAgeMs >= PUMPPORTAL_PRIORITY_MS_8144

    /**
     * DataOrchestrator's Helius swap callback: tape one decoded executed swap. [pumpPortalLastMs] is when PumpPortal last
     * delivered [mint] (0 = never). Returns true when the print was taped.
     */
    fun noteHelius8144(
        mint: String, signature: String, sol: Double, tokenAmount: Double, isBuy: Boolean,
        pumpPortalLastMs: Long, nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (mint.isBlank()) return false
        if (!(sol.isFinite() && sol > 0.0 && tokenAmount.isFinite() && tokenAmount > 0.0)) { skippedUnpriced.incrementAndGet(); return false }
        if (!admit8144(sol, tokenAmount, nowMs - pumpPortalLastMs)) { skippedPumpPortal.incrementAndGet(); return false }
        if (signature.isNotBlank() && seen8144.put("$mint|$signature|$isBuy", true) != null) { skippedDuplicate.incrementAndGet(); return false }
        TradeTape8100.note8100(mint, sol / tokenAmount, sol, isBuy, nowMs)
        taped.incrementAndGet()
        return true
    }

    fun statusLine8144(): String =
        "heliusTape8144 taped=${taped.get()} skippedPumpPortal=${skippedPumpPortal.get()} " +
            "skippedDuplicate=${skippedDuplicate.get()} skippedUnpriced=${skippedUnpriced.get()}"

    internal fun clearForTest8144() {
        seen8144.clear(); taped.set(0L); skippedPumpPortal.set(0L); skippedDuplicate.set(0L); skippedUnpriced.set(0L)
    }
}
