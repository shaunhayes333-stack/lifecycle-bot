package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7776 §LIVE_EDUCATION_AUDIT.
 *
 * Operator: "when I start live i need to see tokens in the bad token memory,
 * patterns being learnt, the full education system being fed. live trading
 * needs auditing as far as learning, behaviour, education, the super meta stack."
 *
 * One report line per live session that says which learners a LIVE close
 * actually reached: the finalized-trade bus (delivered vs excluded, and why),
 * the bad-token / pattern memory (TradingMemory), BotBrain, and live closes
 * finished on the resume path (which skip the liveSell education fan-out). A
 * zero beside a non-zero live close count is a learner live is not teaching.
 * Counters only; nothing here decides a trade.
 */
object LiveEducationAudit7776 {
    private val busLive = AtomicLong(0)
    private val busLiveExcluded = ConcurrentHashMap<String, AtomicLong>()
    private val tradingMemoryLoss = AtomicLong(0)
    private val tradingMemoryWin = AtomicLong(0)
    private val brainLive = AtomicLong(0)
    private val resumePathCloses = AtomicLong(0)

    fun onBusLive(excludedReason: String?) {
        busLive.incrementAndGet()
        if (!excludedReason.isNullOrBlank()) busLiveExcluded.computeIfAbsent(excludedReason.take(32)) { AtomicLong(0) }.incrementAndGet()
    }
    fun onTradingMemoryLive(win: Boolean) { if (win) tradingMemoryWin.incrementAndGet() else tradingMemoryLoss.incrementAndGet() }
    fun onBrainLive() { brainLive.incrementAndGet() }
    fun onResumePathClose() { resumePathCloses.incrementAndGet() }

    fun statusLine(): String {
        val excluded = busLiveExcluded.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }
        val memory = try { com.lifecyclebot.engine.TradingMemory.getStats() } catch (_: Throwable) { "TradingMemory: unavailable" }
        return "liveBusCloses=${busLive.get()} excluded[$excluded] tradingMemory[loss=${tradingMemoryLoss.get()} win=${tradingMemoryWin.get()}] " +
            "botBrain=${brainLive.get()} resumePathAttempts=${resumePathCloses.get()} | $memory"
    }
}
