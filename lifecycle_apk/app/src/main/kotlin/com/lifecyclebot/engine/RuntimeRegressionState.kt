package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.9.1533 — lightweight runtime counters for the new sell-safety / balance-authority
 * / venue / learning regression guards. These are incremented at the exact code sites
 * that would constitute a doctrine violation, and read by ForensicReportExporter when
 * it builds RuntimeRegressionGuards.Input. Counters are session-scoped and monotonic;
 * a clean run leaves every one at zero (=> guards pass 21/21).
 */
object RuntimeRegressionState {
    private val broadcastOnUnconfirmedBalance = AtomicInteger(0)
    private val liveSellAboveSlippageCap = AtomicInteger(0)
    private val pumpRouteInvalidNotReResolved = AtomicInteger(0)
    private val learningFromUnconfirmedClose = AtomicInteger(0)

    fun bumpBroadcastOnUnconfirmedBalance() { broadcastOnUnconfirmedBalance.incrementAndGet() }
    fun bumpLiveSellAboveSlippageCap() { liveSellAboveSlippageCap.incrementAndGet() }
    fun bumpPumpRouteInvalidNotReResolved() { pumpRouteInvalidNotReResolved.incrementAndGet() }
    fun bumpLearningFromUnconfirmedClose() { learningFromUnconfirmedClose.incrementAndGet() }

    fun broadcastOnUnconfirmedBalanceCount(): Int = broadcastOnUnconfirmedBalance.get()
    fun liveSellAboveSlippageCapCount(): Int = liveSellAboveSlippageCap.get()
    fun pumpRouteInvalidNotReResolvedCount(): Int = pumpRouteInvalidNotReResolved.get()
    fun learningFromUnconfirmedCloseCount(): Int = learningFromUnconfirmedClose.get()

    /** Operator-facing reset (e.g. on a fresh session/run). */
    fun resetAll() {
        broadcastOnUnconfirmedBalance.set(0)
        liveSellAboveSlippageCap.set(0)
        pumpRouteInvalidNotReResolved.set(0)
        learningFromUnconfirmedClose.set(0)
    }
}
