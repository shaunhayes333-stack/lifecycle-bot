package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TokenState
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
    const val MAX_OPEN_8733 = 2
    const val MAX_PER_DAY_8733 = 8
    private const val MAX_SOL_8733 = 0.05
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

    /** PreTradeHardGate: may [ts]'s unread LP ownership be bought at explore size now? */
    fun eligible8733(ts: TokenState, nowMs: Long): Boolean {
        if (!withinBudget8733(openCount8733(), spentToday8733(nowMs))) return false
        val cell = cellPaying8733(ts, nowMs) ?: return false
        lastCell = cell
        return true
    }

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

    private fun cellPaying8733(ts: TokenState, nowMs: Long): String? = EdgeModel8537.LANES.firstNotNullOfOrNull { lane ->
        try { PayingCell8723.evidence8723(ts, lane, nowMs)?.id } catch (_: Throwable) { null }
    }

    private fun spentToday8733(nowMs: Long): Int = spent.values.count { nowMs - it in 0L..DAY_MS }

    private fun openCount8733(): Int = spent.keys.count { m ->
        try { BotService.status.tokens[m]?.position?.isOpen == true } catch (_: Throwable) { false }
    }

    fun statusLine8733(): String {
        val now = System.currentTimeMillis()
        return "explore8733[buys=${allowed.get()} today=${spentToday8733(now)}/$MAX_PER_DAY_8733 open=${openCount8733()}/$MAX_OPEN_8733 " +
            "maxSol=$MAX_SOL_8733 refusedSize=${refusedSize.get()} refusedBudget=${refusedBudget.get()} lastCell=${lastCell.ifBlank { "-" }} " +
            "rule=unread LP (never unlocked/under-lock) in a paying cell, mint+freeze revoked, holders read, executable exit]"
    }
}
