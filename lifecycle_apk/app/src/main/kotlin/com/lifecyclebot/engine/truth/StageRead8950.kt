package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8950 §AN_UNKNOWN_STAGE_IS_READ_FROM_THE_CHAIN_NOW
 *
 * Owner, 22 Nov 23:53 / 23:56: "we are reading coins totally wrong ... anything flagged UNKNOWN must be confirmed on chain
 * asap". The owner's research rows (21-22 Nov, 37,658 hour reads): the stage was UNKNOWN on 64.0 % of the meme lanes' reads
 * and 83.6 % of the crypto lanes'. Joined with the dataset's decision-time features (4,488 coins), the UNKNOWN coins had no
 * price path (run-up, distance from peak, drawdown) on 52 % of reads and no 5 m / 1 h change or buy / sell split on ~30 %:
 * TokenMetricStageRouter's tests cannot fire without them. A near-decision coin's on-chain swap history waited 20 s for the
 * free sources first (HeliusCandleGate8418 FREE_GRACE 323 on the 23:03 capture) and was skipped on the per-minute budget 164
 * times, with the day's Helius credits at 26 % of budget. Among the unread coins: off pump.fun, under 5 minutes old, 545
 * coins at +21.0 % mean, ex-top-3 +14.8 %, halves +13.0 / +29.0 an hour on, which no stage rule could name.
 *
 * Now every LIVE stage reading that comes back UNKNOWN ([onStage8950], from TokenMetricStageRouter.snapshot) asks the chain
 * at once for the coin's own swap history (HeliusSwapCandles7819: no free-source grace for it, the near-decision budget
 * tier, one 100-credit page inside SWAP_CANDLES' paced share — never the decision reserve or the priority lease), at most
 * once per [ASK_GAP_MS_8950] per coin. The read fills the coin's 1 m bars and its trade tape; when it lands, the coin's full
 * pipeline runs again at once ([direct8950] -> BotService fast lane) so the stage is read on the chain's own prints.
 * Nothing is admitted here and no stage rule changes. [statusLine8950] shows how many UNKNOWN readings were asked, read,
 * and what each re-read stage became.
 */
object StageRead8950 {
    const val WINDOW_MS_8950 = 90_000L
    private const val ASK_GAP_MS_8950 = 60_000L
    private const val CAP_8950 = 4_000
    private const val MAX_STAGES_8950 = 16

    /** BotService: run [mint]'s full pipeline pass now (the fast lane), keyed by [slot]. */
    @Volatile var direct8950: ((mint: String, slot: Long) -> Unit)? = null

    private val askedAt = ConcurrentHashMap<String, Long>()     // mint -> when the chain was last asked for it
    private val mergedAt = ConcurrentHashMap<String, Long>()    // mint -> when its chain read landed (awaiting the re-read stage)
    private val seen = AtomicLong(0L)
    private val asked = AtomicLong(0L)
    private val merged = AtomicLong(0L)
    private val restaged = ConcurrentHashMap<String, AtomicLong>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "stage-read-8950").apply { isDaemon = true } }

    /** Pure: may the chain be asked for a coin last asked at [lastAskMs] (0 = never)? */
    internal fun askDue8950(lastAskMs: Long, nowMs: Long): Boolean = lastAskMs <= 0L || nowMs - lastAskMs >= ASK_GAP_MS_8950

    /** HeliusSwapCandles7819: is [mint] an UNKNOWN reading asked within [WINDOW_MS_8950] (its read skips the free grace)? */
    fun pending8950(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        askedAt[mint]?.let { nowMs - it in 0L..WINDOW_MS_8950 } == true && !mergedAt.containsKey(mint)

    private fun live8950(): Boolean = try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }

    /** TokenMetricStageRouter.snapshot: the stage [stage] was just read for [ts] (LIVE): an UNKNOWN asks the chain. */
    fun onStage8950(ts: TokenState, stage: String, nowMs: Long = System.currentTimeMillis()) {
        val mint = ts.mint
        if (mint.length !in 32..44) return
        // a coin whose chain read landed: file what its stage now reads as (once)
        mergedAt.remove(mint)?.let { at -> if (nowMs - at in 0L..WINDOW_MS_8950) count8950(stage) }
        if (stage != "UNKNOWN" || !live8950()) return
        seen.incrementAndGet()
        // the history read (and the price / depth read beside it) goes through the one chain-confirm router
        ChainConfirm8950.confirm8950(ts, listOf("STAGE", "PRICE"), "STAGE", nowMs)
    }

    /** ChainConfirm8950 (HISTORY): read [ts]'s own swap history from the chain now (no free grace), once per [ASK_GAP_MS_8950]. */
    fun askChain8950(ts: TokenState, nowMs: Long = System.currentTimeMillis()) {
        val mint = ts.mint
        if (mint.length !in 32..44) return
        if (!askDue8950(askedAt[mint] ?: 0L, nowMs)) return
        if (askedAt.size > CAP_8950) askedAt.entries.removeIf { nowMs - it.value > WINDOW_MS_8950 }
        askedAt[mint] = nowMs
        asked.incrementAndGet()
        io.execute { try { HeliusSwapCandles7819.request7819(ts, System.currentTimeMillis()) } catch (_: Throwable) {} }
    }

    /** HeliusSwapCandles7819: bars landed for [mint]; an asked UNKNOWN coin's pipeline runs again now. */
    fun onMerged8950(mint: String, nowMs: Long = System.currentTimeMillis()) {
        val at = askedAt[mint] ?: return
        if (nowMs - at !in 0L..WINDOW_MS_8950) return
        askedAt.remove(mint)
        if (mergedAt.size > CAP_8950) mergedAt.entries.removeIf { nowMs - it.value > WINDOW_MS_8950 }
        mergedAt[mint] = nowMs
        merged.incrementAndGet()
        try { direct8950?.invoke(mint, nowMs / ASK_GAP_MS_8950) } catch (_: Throwable) {}
    }

    private fun count8950(stage: String) {
        if (restaged.size < MAX_STAGES_8950 || restaged.containsKey(stage)) restaged.computeIfAbsent(stage) { AtomicLong(0L) }.incrementAndGet()
    }

    /** Brief: UNKNOWN readings seen, chain reads asked, reads landed, and what the re-read stage became. */
    fun statusLine8950(): String =
        "stageRead8950[unknownSeen=${seen.get()} chainAsked=${asked.get()} chainRead=${merged.get()} restaged{" +
            restaged.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
            "} candles=${try { HeliusSwapCandles7819.refusalLine8950() } catch (_: Throwable) { "-" }} rule=every LIVE UNKNOWN stage asks the " +
            "chain for the coin's own swap history at once (no free-source grace, one 100-credit page, near-decision budget tier, paced " +
            "share), once per ${ASK_GAP_MS_8950 / 1000}s per coin; when it lands the coin's pipeline runs again; no stage rule changes]"

    internal fun resetForTest8950() {
        askedAt.clear(); mergedAt.clear(); seen.set(0L); asked.set(0L); merged.set(0L); restaged.clear()
    }

    internal fun askForTest8950(mint: String, nowMs: Long) { askedAt[mint] = nowMs }
}
