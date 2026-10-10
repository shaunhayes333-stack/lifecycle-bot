package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8024 — nothing the bot bought is the owner's.
 *
 * 5.0.8018 live, owner: "all of those positions were bought by the bot". The wallet held Altai (31,567
 * tokens, $29.59, running), Pepper, Switched, Baton Factory, Compute and Claudia while the bot managed
 * none of them. Two rules took them away from the bot:
 *  - OwnerManualHoldings7976 calls a tracker row the owner's hand buy when its signature is not in
 *    FillLotLedger6344 and the bot holds no lot — the crypto lanes' buys never wrote a fill lot, so
 *    every coin they bought (7536qC FACTORY was tagged OWNER_MANUAL_7976) was "the owner's";
 *  - wallet recovery adopts only with tracker / coverage evidence, which expires (coverage after 6 h)
 *    or never existed for those lanes, so their leftovers were "left alone".
 *
 * The bot's own trade journal is the evidence that survives all of that: a mint with any LIVE BUY or
 * SELL row in TradeHistoryStore was traded by the bot. Such a mint is bot inventory — adopted at the
 * observed mark and managed by the normal exits — and never the owner's manual holding. A coin the
 * journal has never seen live stays the owner's.
 */
object BotJournalMints8024 {
    private const val CACHE_MS = 60_000L
    @Volatile private var cache: Pair<Long, Set<String>> = 0L to emptySet()
    private val attributed = AtomicLong(0)

    /** Pure: the mints with a live BUY or SELL row in [trades]. */
    fun liveMints8024(trades: List<com.lifecyclebot.data.Trade>): Set<String> =
        trades.asSequence()
            .filter { it.mode.equals("live", ignoreCase = true) && it.mint.isNotBlank() &&
                (it.side.equals("BUY", true) || it.side.equals("SELL", true)) }
            .map { it.mint }
            .toHashSet()

    /** Has the bot's journal ever recorded a live trade of [mint]? Cached for a minute. */
    fun botTraded8024(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        val (at, set) = cache
        val mints = if (nowMs - at in 0L..CACHE_MS) set else try {
            liveMints8024(TradeHistoryStore.getAllTradesIncludingInvalidForensics()).also { cache = nowMs to it }
        } catch (_: Throwable) { set }
        val hit = mint in mints
        if (hit) attributed.incrementAndGet()
        return hit
    }

    fun statusLine(): String = "journalLiveMints=${cache.second.size} attributedReads=${attributed.get()}"
}
