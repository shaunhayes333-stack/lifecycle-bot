package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** V5.0.4313 — report-only sell decision matrix at Executor requestSell/doSell choke points. */
object SellDecisionMatrixReport {
    private val intents = AtomicLong(0)
    private val preSellDefers = AtomicLong(0)
    private val doSellHandoffs = AtomicLong(0)
    private val byReason = ConcurrentHashMap<String, AtomicLong>()
    private val byStage = ConcurrentHashMap<String, AtomicLong>()

    private fun inc(map: ConcurrentHashMap<String, AtomicLong>, key: String) { map.computeIfAbsent(key.take(96).ifBlank { "UNKNOWN" }) { AtomicLong(0) }.incrementAndGet() }

    fun recordIntent(mint: String, symbol: String, reason: String, isPaper: Boolean, lane: String) {
        intents.incrementAndGet(); inc(byReason, reason.substringBefore('_').substringBefore(':')); inc(byStage, "${if (isPaper) "PAPER" else "LIVE"}/$lane")
        try { PipelineHealthCollector.labelInc("SELL_MATRIX_INTENT_4313") } catch (_: Throwable) {}
    }

    fun recordPreSellDefer(mint: String, symbol: String, reason: String, stage: String) {
        preSellDefers.incrementAndGet(); inc(byStage, stage); inc(byReason, reason.substringBefore('_').substringBefore(':'))
        try { PipelineHealthCollector.labelInc("SELL_MATRIX_DEFER_4313/$stage") } catch (_: Throwable) {}
    }

    fun recordDoSellHandoff(mint: String, symbol: String, reason: String, isPaper: Boolean) {
        doSellHandoffs.incrementAndGet(); inc(byStage, "DO_SELL/${if (isPaper) "PAPER" else "LIVE"}"); inc(byReason, reason.substringBefore('_').substringBefore(':'))
        try { PipelineHealthCollector.labelInc("SELL_MATRIX_DOSELL_HANDOFF_4313") } catch (_: Throwable) {}
    }

    fun status(): String {
        val topReasons = byReason.entries.sortedByDescending { it.value.get() }.take(8).joinToString("|") { "${it.key}:${it.value.get()}" }
        val topStages = byStage.entries.sortedByDescending { it.value.get() }.take(8).joinToString("|") { "${it.key}:${it.value.get()}" }
        return "SELL_DECISION_MATRIX_4313 intents=${intents.get()} defers=${preSellDefers.get()} doSellHandoffs=${doSellHandoffs.get()} reasons=$topReasons stages=$topStages report_only=true no_sell_authority=true"
    }
}
