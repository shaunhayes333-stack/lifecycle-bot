package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7959 — a buy receipt pays for ONE position.
 *
 * 5.0.7954/7958 live tape: one 0.0454 SOL buy (7kEtLE) was "sold" and finalized
 * ten times in an hour, each time booking another -0.019 SOL realised loss
 * against the same cost. After every full close the wallet still held the
 * tokens, wallet recovery re-adopted them with the original receipt (fill
 * registry / signed-buy tracker row / journal — sources that never asked whether
 * that receipt had already been closed), and the next stop booked the same
 * cost again. Those ghost losses drove the live win rate, drawdown, kill switch,
 * defensive pause and the Cortex's proven-negative refusals.
 *
 * Rule: a receipt opened at or before the mint's last live FULL close is spent.
 * Tokens still in the wallet after that close are re-adopted once at the
 * observed mark (P&L from now, no cost re-charged); if a second full close
 * still leaves them in the wallet, the sell is not moving them and the mint is
 * parked (left alone) instead of looping.
 */
object LiveReceiptSpent7959 {
    /** Re-adoptions of a spent receipt allowed per mint before it is parked. */
    private const val MAX_READOPTIONS = 1

    private val readoptions = ConcurrentHashMap<String, Int>()

    /** Pure: is a receipt opened at [receiptOpenedAtMs] already realised by a full close? */
    fun isSpent(receiptOpenedAtMs: Long, lastFullCloseMs: Long?): Boolean =
        lastFullCloseMs != null && lastFullCloseMs > 0L && receiptOpenedAtMs <= lastFullCloseMs

    /** Newest live full-close time for [mint] in the durable economic events, or null. */
    fun lastLiveFullCloseMs(mint: String): Long? = try {
        EconomicEventSchema6464.snapshot()
            .filterIsInstance<EconomicEventSchema6464.Sell>()
            .filter { it.mode == "live" && it.mint == mint && (!it.partial || it.remainingQty.signum() == 0) }
            .maxOfOrNull { it.atMs }
    } catch (_: Throwable) { null }

    /**
     * Record one re-adoption of a spent receipt for [mint]; true when the mint has
     * already used its re-adoption (park it rather than loop).
     */
    fun shouldPark(mint: String): Boolean {
        val n = readoptions.merge(mint, 1) { a, b -> a + b } ?: 1
        if (readoptions.size > 512) readoptions.clear()
        return n > MAX_READOPTIONS
    }

    fun status(): String = "readopted=${readoptions.size} parked=${readoptions.values.count { it > MAX_READOPTIONS }}"

    internal fun resetForTest() = readoptions.clear()
}
