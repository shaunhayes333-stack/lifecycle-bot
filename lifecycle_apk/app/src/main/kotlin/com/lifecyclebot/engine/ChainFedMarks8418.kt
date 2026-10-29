package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §A_POLL_NEVER_OVERWRITES_THE_CHAIN — which held mints are on a chain feed right now (a curve account tick,
 * a pool vault / pool-state tick, a decoded curve print: everything that reaches BotService.applyPumpTradeMark7278).
 * While a mint is chain-fed, the slower poll paths (the open-position DexScreener batch, HeldHotMarkAuthority7419's
 * locked-venue / fan-out answers) keep publishing their own registry marks but never overwrite the row's price with an
 * older or off-basis number: the chain state IS the market, and a stop or rung judged on it is on the position's basis.
 * A feed quiet for [CHAIN_FED_MS_8418] (no trade, no vault change) hands the row back to the polls.
 */
object ChainFedMarks8418 {
    private const val CHAIN_FED_MS_8418 = 60_000L
    private const val MAX_8418 = 4_096
    private val at = ConcurrentHashMap<String, Long>()
    private val skipped = AtomicLong(0)

    fun note8418(mint: String, nowMs: Long) {
        if (mint.isBlank()) return
        at[mint] = nowMs
        if (at.size > MAX_8418) at.entries.removeIf { nowMs - it.value > CHAIN_FED_MS_8418 }
    }

    /** Pure over the recorded tick: is [mint] chain-fed at [nowMs]? */
    fun chainFed8418(mint: String, nowMs: Long): Boolean {
        val t = at[mint] ?: return false
        return nowMs - t in 0L..CHAIN_FED_MS_8418
    }

    /** Pure: may a poll mark taken at [pollMs] replace a chain tick taken at [chainMs]? Only once the feed went quiet. */
    fun pollMayWrite8418(chainMs: Long, pollMs: Long): Boolean = chainMs <= 0L || pollMs - chainMs > CHAIN_FED_MS_8418

    fun pollSkipped8418() { skipped.incrementAndGet(); try { PipelineHealthCollector.labelInc("POLL_MARK_SKIPPED_CHAIN_FED_8418") } catch (_: Throwable) {} }

    fun statusLine8418(nowMs: Long = System.currentTimeMillis()): String =
        "chainFed8418[mints=${at.count { nowMs - it.value in 0L..CHAIN_FED_MS_8418 }} pollSkipped=${skipped.get()} window=${CHAIN_FED_MS_8418 / 1000}s]"

    internal fun resetForTest8418() { at.clear(); skipped.set(0) }
}
