package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8733 §SMALL_LIVE_BUYS_IN_THE_PAYING_CELL — owner, 8732 capture: "3 yes" (minimum-size live buys in the best cell,
 * every hard safety check on) and "dont buy unlocked drain coins".
 *
 * 8732 live: 26 coins of a proven paying cell (not pump.fun, under 5 min, pool $10k-50k) sat PENDING at the buy door on
 * RugCheck not having indexed the mint and on the pool having no ownership proof. The on-chain RugCheck substitute
 * (PreTradeHardGate.chainProvenRugcheckSubstitute8471) needed LP ownership PROVEN, and that proof also comes only from
 * RugCheck: a fresh non-pump coin could never pass. Paper cannot say what these coins cost to buy and sell for real.
 *
 * Here an LP ownership that is UNREAD (PENDING) — never one reported unlocked or under the 70 % lock (BLOCKED, refused
 * upstream) — may be bought at explore size when:
 *  - some lane's paying cell holds this coin now (PayingCell8723, mean above zero by two SE);
 *  - mint authority revoked, freeze revoked, holder concentration read and under the fatal limit and an executable exit
 *    (the 8471 substitute's own requirements, unchanged);
 *  - at most [MAX_OPEN_8733] explore positions open, [MAX_PER_DAY_8733] explore buys a day, each at most [MAX_SOL_8733].
 * Everything else the door refuses it still refuses; exits are the lane's own.
 */
object ExploreLive8733 {
    const val LP_EXPLORE_8733 = "LP_UNREAD_EXPLORE_8733"

    /**
     * V5.0.8736 — OFF. Owner, 8735 capture: "your now letting it buy tokens that cant be sold at all". The explore path
     * let a coin through on chain substitutes for RugCheck, and the bot's "executable exit" is pool depth, not a sell:
     * nothing proved the token itself could be sold (RugCheck's own findings — Token-2022 transfer hooks, permanent
     * delegate, pausable, non-transferable, transfer fees — are what the substitute skipped). The one explore buy that
     * landed (Cw1CLZ, 0.039 SOL) then failed every sell quote. No buy is let through on unread proofs again until a real
     * token->SOL sell is proven for the coin before the buy.
     */
    // ── V5.0.8928 §THE_POCKET_CAREFULLY — owner, 22 Nov 02:52, on the agreement pocket waiting on an unread LP proof: "1 yup
    //    2 it has to pass 10k - 15k and actually show forward volume holder growth and more positive signs than negative. and
    //    if it does buy maybe be very very careful". 20-22 Nov research rows, one row per coin at its first decision: not
    //    pump.fun, forecast +10 % or more, cap $10k-$20k: n85, +34.6 % +-6.3 net at the hour (both halves positive), 5 of 85
    //    fell 60 %+ (pulled), 7 doubled; $5k-$10k: n22 with 4 pulled, so the floor is $10k. Back ON, only for that pocket, and
    //    only when every one of these holds (8736's reason for OFF answered by the third):
    //  - the coin is in the agreement pocket (AgreementPocket8874: not pump.fun, V3 >= 23, a proven lane's forecast >= +10 %)
    //    at a cap of [CAP_LO_USD_8928] up to the pocket's $20k ceiling;
    //  - RugCheck has REPORTED (no chain substitute for it on this path) and its findings did not refuse; the LP is UNREAD,
    //    never reported unlocked or under the lock (those are BLOCKED before this);
    //  - the mint is a classic SPL Token mint (no Token-2022 extension can trap a sell), mint and freeze revoked;
    //  - forward flow and holders: buying rising (buy transactions this 15 s over the 15 s before, or accelerating volume),
    //    holder count growing, and more positive signs than negative ([signs8928]);
    //  - one explore position at a time, [MAX_PER_DAY_8733] a day, at most [MAX_SOL_8733] SOL each; the LP-pull exit (8926)
    //    watches it from the first tick. ──
    private const val ENABLED_8736 = true
    const val MAX_OPEN_8733 = 1
    const val MAX_PER_DAY_8733 = 6
    private const val MAX_SOL_8733 = 0.05
    internal const val CAP_LO_USD_8928 = 10_000.0
    private const val DAY_MS = 24L * 60L * 60_000L

    private val spent = ConcurrentHashMap<String, Long>()
    private val allowed = AtomicLong(0L)
    private val refusedSize = AtomicLong(0L)
    private val refusedBudget = AtomicLong(0L)
    @Volatile private var lastCell = ""

    /** Pure: [pending] with an unread LP ownership relabelled to the explore item, only once no RugCheck item is pending. */
    fun relabel8733(pending: List<String>): List<String> {
        if (pending.any { it.startsWith("RUGCHECK_PENDING_OR_UNKNOWN:") }) return pending
        return pending.map { if (it.startsWith("LP_OWNERSHIP_PROOF_PENDING_8522")) "$LP_EXPLORE_8733:" + it.substringAfter(':') else it }
    }

    /** Pure: within the explore budget given [openNow] explore positions and [spentToday] explore buys today. */
    fun withinBudget8733(openNow: Int, spentToday: Int): Boolean = openNow < MAX_OPEN_8733 && spentToday < MAX_PER_DAY_8733

    /**
     * Pure (V5.0.8928): (positive, negative) signs of a coin's live flow [v] and holder growth [holderGrowth] (% over the
     * last candles; null unread). Each sign counts once, positive or negative or neither:
     * buy transactions this 15 s vs the 15 s before; volume acceleration (>= 55 / <= 45 of 100); buy share of trades
     * (>= 55 % / <= 45 %); net SOL bought in the last minute (> 0 / < 0); new buyers in the last 15 s (>= 2 / 0);
     * holders growing / shrinking; sustained demand (positive only).
     */
    internal fun signs8928(v: com.lifecyclebot.engine.market.BuyerFlow8666.View, holderGrowth: Double?): Pair<Int, Int> {
        var pos = 0; var neg = 0
        fun s(p: Boolean, n: Boolean) { if (p) pos++ else if (n) neg++ }
        s(v.buyTx15s > v.buyTxPrev15s, v.buyTx15s < v.buyTxPrev15s)
        s(v.volumeAccelScore >= 55.0, v.volumeAccelScore <= 45.0)
        s(v.buySharePct >= 55.0, v.buySharePct <= 45.0)
        s(v.netSol60s > 0.0, v.netSol60s < 0.0)
        s(v.newBuyers15s >= 2, v.newBuyers15s == 0)
        if (holderGrowth != null && holderGrowth.isFinite()) s(holderGrowth > 0.0, holderGrowth < 0.0)
        if (v.sustainedDemand) pos++
        return pos to neg
    }

    /** Pure (V5.0.8928): the owner's flow rule — buying rising, holders growing, and more positive signs than negative. */
    internal fun flowPasses8928(v: com.lifecyclebot.engine.market.BuyerFlow8666.View?, holderGrowth: Double?): Boolean {
        if (v == null || holderGrowth == null || !holderGrowth.isFinite() || holderGrowth <= 0.0) return false
        val rising = v.buyTx15s > v.buyTxPrev15s || v.volumeAccelScore >= 55.0
        val (pos, neg) = signs8928(v, holderGrowth)
        return rising && pos > neg
    }

    /** PreTradeHardGate: may [ts]'s unread LP ownership be bought at explore size now? */
    fun eligible8733(ts: TokenState, nowMs: Long): Boolean {
        if (!ENABLED_8736) return false
        if (!withinBudget8733(openCount8733(), spentToday8733(nowMs))) { why8928("BUDGET"); return false }
        val lane = AgreementPocket8874.provenLaneFor8928(ts, CAP_LO_USD_8928, nowMs) ?: run { why8928("NOT_IN_POCKET"); return false }
        val proof = try { OnChainMintAuthorityProof7248.resolve(ts.mint, liveGate8094 = true) } catch (_: Throwable) { null }
        if (proof == null || !proof.classicToken8928 || !proof.mintAuthorityDisabled || !proof.freezeAuthorityDisabled) { why8928("NOT_CLASSIC_OR_AUTHORITY"); return false }
        val flow = try { com.lifecyclebot.engine.market.BuyerFlow8666.read(ts.mint, nowMs) } catch (_: Throwable) { null }
        val holders = ts.holderGrowthRate.takeIf { ts.holderDataResolved && it.isFinite() }
        if (!flowPasses8928(flow, holders)) { why8928("FLOW_OR_HOLDERS"); return false }
        lastCell = "AGREE:$lane"
        why8928("ELIGIBLE")
        return true
    }

    private val why8928 = ConcurrentHashMap<String, AtomicLong>()
    private fun why8928(k: String) { why8928.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet() }

    /** Executor (live buy, explore verdict): the refusal of a buy of [sol], or null when it may go. */
    fun spendRefusal8733(sol: Double, nowMs: Long): String? = when {
        !(sol.isFinite() && sol > 0.0 && sol <= MAX_SOL_8733) -> { refusedSize.incrementAndGet(); "EXPLORE_SIZE_ABOVE_CAP_8733" }
        !withinBudget8733(openCount8733(), spentToday8733(nowMs)) -> { refusedBudget.incrementAndGet(); "EXPLORE_BUDGET_SPENT_8733" }
        else -> null
    }

    fun noteSpend8733(mint: String, nowMs: Long) {
        spent[mint] = nowMs
        allowed.incrementAndGet()
        spent.entries.removeIf { nowMs - it.value > DAY_MS }
        try { PipelineHealthCollector.labelInc("EXPLORE_LIVE_BUY_8733") } catch (_: Throwable) {}
    }

    private fun spentToday8733(nowMs: Long): Int = spent.values.count { nowMs - it in 0L..DAY_MS }

    private fun openCount8733(): Int = spent.keys.count { m ->
        try { BotService.status.tokens[m]?.position?.isOpen == true } catch (_: Throwable) { false }
    }

    fun statusLine8733(): String {
        val now = System.currentTimeMillis()
        return "explore8733[buys=${allowed.get()} today=${spentToday8733(now)}/$MAX_PER_DAY_8733 open=${openCount8733()}/$MAX_OPEN_8733 " +
            "maxSol=$MAX_SOL_8733 refusedSize=${refusedSize.get()} refusedBudget=${refusedBudget.get()} lastCell=${lastCell.ifBlank { "-" }} " +
            "asked{${why8928.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} " +
            "rule=8928: unread LP (never unlocked/under-lock), RugCheck reported, agreement pocket at \$10k-\$20k, classic SPL mint, " +
            "buying rising + holders growing + more positive signs than negative; 1 open, 6/day, <= 0.05 SOL; LP-pull exit armed]"
    }
}
