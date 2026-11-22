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

    // ── V5.0.8929 §THE_POOLS_OWN_COUNTS_WHEN_THE_TAPE_IS_DOWN — 22 Nov 04:08 capture: Helius answered "max usage reached" and
    //    refused its socket (429), the PumpPortal trade stream is off (paid per frame), so the bot held no executed print at all
    //    (tape8285 executed=0, buyerFlow8790 accepted=0, emptyReads=268,660) and [flowPasses8928] could never pass. With no
    //    BuyerFlow read, the pool's own DexScreener counts decide instead (PairFlow8169, an answer at most
    //    [PAIR_FRESH_MS_8929] old): buys and transactions in the last 5 minutes against the pool's own rate over the earlier
    //    part of its hour (h1 less m5, over min(age, 60) - 5 minutes), the 5-minute buy share and price move, and holders.
    //    A pool under [PAIR_MIN_AGE_MIN_8929] minutes old has no earlier rate to rise from: it never passes on counts. ──
    internal const val PAIR_FRESH_MS_8929 = 120_000L
    internal const val PAIR_MIN_AGE_MIN_8929 = 10.0

    /** Pure (V5.0.8929): (rate per minute in the last 5 minutes, rate per minute over the earlier part of the hour) of [m5] / [h1] counts in a pool [ageMin] old; null when unknown. */
    internal fun rates8929(m5: Int, h1: Int, ageMin: Double): Pair<Double, Double>? {
        if (m5 < 0 || h1 < 0 || h1 < m5 || !ageMin.isFinite() || ageMin < PAIR_MIN_AGE_MIN_8929) return null
        val earlierMin = minOf(ageMin, 60.0) - 5.0
        if (earlierMin <= 0.0) return null
        return (m5 / 5.0) to ((h1 - m5) / earlierMin)
    }

    /**
     * Pure (V5.0.8929): (positive, negative) signs of a pool's DexScreener counts [f] [ageMin] old and [holderGrowth], each once:
     * transactions rising / falling against the earlier rate; buys rising / falling; the 5-minute buy share (>= 55 % / <= 45 %);
     * the 5-minute price move (> 0 / < 0); holders growing / shrinking. Null when the counts cannot be read.
     */
    internal fun pairSigns8929(f: PairFlow8169.Flow8169, ageMin: Double, holderGrowth: Double?): Pair<Int, Int>? {
        if (f.buysM5 < 0 || f.sellsM5 < 0 || f.buysH1 < 0 || f.sellsH1 < 0) return null
        val tx = rates8929(f.buysM5 + f.sellsM5, f.buysH1 + f.sellsH1, ageMin) ?: return null
        val buys = rates8929(f.buysM5, f.buysH1, ageMin) ?: return null
        var pos = 0; var neg = 0
        fun s(p: Boolean, n: Boolean) { if (p) pos++ else if (n) neg++ }
        s(tx.first > tx.second, tx.first < tx.second)
        s(buys.first > buys.second, buys.first < buys.second)
        val m5 = f.buysM5 + f.sellsM5
        if (m5 > 0) { val share = 100.0 * f.buysM5 / m5; s(share >= 55.0, share <= 45.0) }
        if (f.chg5m.isFinite()) s(f.chg5m > 0.0, f.chg5m < 0.0)
        if (holderGrowth != null && holderGrowth.isFinite()) s(holderGrowth > 0.0, holderGrowth < 0.0)
        return pos to neg
    }

    /** Pure (V5.0.8929): the owner's flow rule on the pool's counts — buying rising, holders growing, more positive signs than negative. */
    internal fun pairFlowPasses8929(f: PairFlow8169.Flow8169?, ageMin: Double, holderGrowth: Double?, nowMs: Long): Boolean {
        if (f == null || nowMs - f.atMs !in 0L..PAIR_FRESH_MS_8929) return false
        if (holderGrowth == null || !holderGrowth.isFinite() || holderGrowth <= 0.0) return false
        val buys = rates8929(f.buysM5, f.buysH1, ageMin) ?: return false
        val (pos, neg) = pairSigns8929(f, ageMin, holderGrowth) ?: return false
        return buys.first > buys.second && pos > neg
    }

    /**
     * Pure (V5.0.8936): why [proof]'s token may not be bought on this path, or null when it may. 22 Nov 13:59 capture (8932, 2.2 h
     * up): 252 of the path's refusals were 8928's classic-only mint refusal and it bought nothing that run. 8928 asked for a
     * classic SPL mint, which the owner never asked for, and the agreement pocket's coins (off pump.fun: Meteora DBC, Bags,
     * LaunchLab, the newer pump.fun mints) are often Token-2022 with only metadata extensions. Now a Token-2022 mint passes
     * when the proof reads nothing that can trap a holder (OnChainMintAuthorityProof7248.hostileExtension8123: permanent
     * delegate, pause, transfer hook, frozen default state, non-transferable), nothing a live authority can still change
     * (mutableExtension8136), and no transfer fee at all; mint and freeze authority revoked as before. Each refusal is named.
     */
    internal fun tokenRefusal8936(proof: OnChainMintAuthorityProof7248.Proof?): String? = when {
        proof == null -> "NO_MINT_PROOF"
        !proof.mintAuthorityDisabled -> "MINT_AUTHORITY_LIVE"
        !proof.freezeAuthorityDisabled -> "FREEZE_AUTHORITY_LIVE"
        proof.classicToken8928 -> null
        proof.hostileExtension8123 != null -> "TOKEN2022_HOSTILE_EXTENSION"
        proof.mutableExtension8136 != null -> "TOKEN2022_MUTABLE_EXTENSION"
        proof.transferFeeBps8936 > 0 -> "TOKEN2022_TRANSFER_FEE"
        else -> null
    }

    /** PreTradeHardGate: may [ts]'s unread LP ownership be bought at explore size now? */
    fun eligible8733(ts: TokenState, nowMs: Long): Boolean {
        if (!ENABLED_8736) return false
        if (!withinBudget8733(openCount8733(), spentToday8733(nowMs))) { why8928("BUDGET"); return false }
        // superseded by V5.0.8939 — the pocket's proven cohort without the coin's V3 (AgreementPocket8874.pocketVerdict8939); a
        // refusal names what it missed (NOT_IN_POCKET_ORIGIN / _CAP / _NO_LANE_EVIDENCE / _FORECAST)
        val (pocketLane8939, pocketWhy8939) = AgreementPocket8874.pocketVerdict8939(ts, CAP_LO_USD_8928, nowMs, requireV3 = false)
        val lane = pocketLane8939 ?: run { why8928("NOT_IN_POCKET_$pocketWhy8939"); return false }
        val proof = try { OnChainMintAuthorityProof7248.resolve(ts.mint, liveGate8094 = true) } catch (_: Throwable) { null }
        tokenRefusal8936(proof)?.let { why8928(it); return false }
        val flow = try { com.lifecyclebot.engine.market.BuyerFlow8666.read(ts.mint, nowMs) } catch (_: Throwable) { null }
        val holders = ts.holderGrowthRate.takeIf { ts.holderDataResolved && it.isFinite() }
        if (flow != null) {
            if (!flowPasses8928(flow, holders)) { why8928("FLOW_OR_HOLDERS"); return false }
        } else {
            // V5.0.8929 — no live tape for the coin: the pool's own counts decide
            val pair = try { PairFlow8169.peek8169(ts.mint) } catch (_: Throwable) { null }
            val created = pair?.pairCreatedAtMs8417?.takeIf { it > 0L } ?: 0L   // the pool's own creation time, as served
            val ageMin = if (created > 0L) (nowMs - created) / 60_000.0 else Double.NaN
            if (!pairFlowPasses8929(pair, ageMin, holders, nowMs)) {
                why8928(if (pair == null || nowMs - pair.atMs !in 0L..PAIR_FRESH_MS_8929) "NO_TAPE_NO_FRESH_POOL_COUNTS"
                    else if (!(ageMin >= PAIR_MIN_AGE_MIN_8929)) "NO_TAPE_POOL_TOO_YOUNG_FOR_COUNTS" else "POOL_COUNTS_OR_HOLDERS")
                return false
            }
            why8928("PASSED_ON_POOL_COUNTS_8929")
        }
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
            "rule=8928: unread LP (never unlocked/under-lock), RugCheck reported, agreement pocket at \$10k-\$20k with a +10 %+ forecast, any V3 (8939), mint + freeze revoked, a classic SPL or clean Token-2022 mint (8936), " +
            "buying rising + holders growing + more positive signs than negative (the live tape, else the pool's DexScreener counts, 8929); 1 open, 6/day, <= 0.05 SOL; LP-pull exit armed]"
    }
}
