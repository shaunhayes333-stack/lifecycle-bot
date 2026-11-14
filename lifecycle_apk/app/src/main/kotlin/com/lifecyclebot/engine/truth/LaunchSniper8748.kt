package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8748 §THE_LAUNCH_SNIPER — owner: "we need the bot to see these developing at 10k ... whether it be the latency on
 * decision timing the actual decisions themselves".
 *
 * The bot decides on an 8-second loop over its watchlist; a launch crosses $10k in seconds. This reads EVERY live pump.fun
 * print as it is decoded (HeliusCurveTape8354's program-wide stream, before its care filter, and TradeTape8100's live
 * prints) and fires the moment a coin meets the launch replay's rule: curve cap past the rule's cap with the rule's number
 * of buys in the last 60 s — the same trigger LaunchReplay8744 graded, on the same feed.
 *
 *  - SHADOW (no rule proven): fires on the replay's best tradeable rule are counted and named, nothing else.
 *  - ARMED (LaunchReplay8744.proven8748 names a rule): a fire adds the coin to the watchlist at once and, for
 *    [EVIDENCE_TTL_MS] after it, CandidateAdmission8136 reads [evidence8748] as the coin's market-cell evidence (the replay
 *    rule's ex-top-3 mean and SE, net of cost). That evidence answers the generic data waits exactly as a proven paying
 *    cell does (8723 precedence); it never answers hard safety, rug structure, sellability (BuyRouteSafety8522), the
 *    economics at the quoted size or the lane's realised live losses.
 * Nothing here buys. Nothing here is a hand threshold: the cap, the buys, the ladder and the latency are the replay's.
 */
object LaunchSniper8748 {
    /** How long a fire stands as admission evidence (the bot's loop must reach the coin within it). */
    private const val EVIDENCE_TTL_MS = 120_000L
    private const val MAX_TRACKED = 30_000
    private const val HOUR = 3_600_000L

    private class St(val bornMs: Long) {
        val buys = ArrayDeque<Long>()
        val sigs = ArrayDeque<String>()
        var fired = false
        var lastSeen = 0L
        // V5.0.8754 — the base family's trigger for this coin: the very code BaseFamily8754 graded, fed every print
        var baseTrigger: BaseFamily8754.Trigger? = null
        var base: BaseFamily8754.State? = null
    }
    /** V5.0.8754 — a fire carries its rule's graded record (launch or base family), not one family's result type. */
    data class Fire(val mint: String, val atMs: Long, val ageMs: Long, val capUsd: Double, val ruleId: String, val n: Int,
                    val exTop3: Double, val exTop3Se: Double, val tenX: Double, val armed: Boolean, val triggerEventMs: Long = atMs)

    /**
     * V5.0.8749 §THE_LATENCY_THE_BOT_ACTUALLY_HAS — the owner's own launch export (968 launch paths, 582 seen from birth):
     * entering a $10-15k launch 10 s after the trigger averaged +26..+50 % (carried by 6-10x runners); entering 30 s after
     * averaged -4..-6 %, and -23 % without the three best. The delay decides the strategy, so the sniper trades only a
     * rule graded at a delay the bot really has:
     *   - the tape's own lag, chain time -> this process, measured on every pump.fun print ([LAG_EDGES_S]);
     *   - plus the bot's decide-and-send time: [DECIDE_SEND_MS_8749] until armed buys land, then the measured lag from the
     *     trigger print's chain time to the buy's on-chain confirmation (p75 of the last [MAX_LANDED_8749]).
     * [requiredDelayMs8749] is that sum (never under LaunchReplay8744's 10 s floor); LaunchReplay8744 arms only rules graded
     * at or beyond it.
     */
    private val LAG_EDGES_S = longArrayOf(1, 2, 3, 5, 8, 12, 15, 20, 30, 60, 120)
    private val lagHist = java.util.concurrent.atomic.AtomicLongArray(LAG_EDGES_S.size + 1)
    private const val DECIDE_SEND_MS_8749 = 8_000L
    private const val MAX_LANDED_8749 = 50
    private val landed = java.util.ArrayDeque<Long>()

    /** Pure: the upper edge (ms) of the bucket holding the [q] quantile of [hist] over [edgesS]; null when empty. */
    fun quantileMs8749(hist: LongArray, edgesS: LongArray, q: Double): Long? {
        val total = hist.sum(); if (total <= 0L) return null
        var acc = 0L
        for (i in hist.indices) { acc += hist[i]; if (acc >= q * total) return if (i < edgesS.size) edgesS[i] * 1_000L else edgesS.last() * 2_000L }
        return edgesS.last() * 2_000L
    }

    private fun lagHistSnapshot(): LongArray = LongArray(lagHist.length()) { lagHist.get(it) }

    /** The delay (ms) a rule must be graded at to be traded: tape lag p75 + decide-and-send, or measured trigger->landed p75. */
    fun requiredDelayMs8749(): Long {
        val measured = synchronized(landed) { if (landed.size >= 3) landed.sorted()[(landed.size * 3) / 4] else null }
        val need = measured ?: ((quantileMs8749(lagHistSnapshot(), LAG_EDGES_S, 0.75) ?: 2_000L) + DECIDE_SEND_MS_8749)
        return maxOf(10_000L, need)
    }

    /** SignedTxLedger8085 (via LaunchCohort8173): a BUY of [mint] confirmed on chain at [nowMs]. */
    fun noteBuyConfirmed8749(mint: String, nowMs: Long) {
        val f = fires[mint]?.takeIf { it.armed } ?: return
        val ms = nowMs - f.triggerEventMs
        if (ms !in 0L..600_000L) return
        synchronized(landed) { landed.addLast(ms); while (landed.size > MAX_LANDED_8749) landed.removeFirst() }
        try { PipelineHealthCollector.labelInc("LAUNCH_SNIPER_BUY_LANDED_8749") } catch (_: Throwable) {}
    }

    private val st = ConcurrentHashMap<String, St>()
    private val fires = ConcurrentHashMap<String, Fire>()
    private val livePrints = AtomicLong(); private val latePrints = AtomicLong(); private val unborn = AtomicLong()
    private val shadowFires = AtomicLong(); private val armedFires = AtomicLong(); private val watchAdds = AtomicLong()
    private val evidenceServed = AtomicLong()
    @Volatile private var lastFire: Fire? = null

    /** Pure: does a coin at [capUsd] with [buys60s] live buys in the last minute meet [rule]'s trigger? */
    fun triggers8748(rule: LaunchReplay8744.Rule, capUsd: Double, buys60s: Int): Boolean =
        capUsd.isFinite() && capUsd >= rule.capUsd && buys60s >= rule.minBuys

    /** One decoded print of [mint] (chain time [eventMs], received [recvMs], signature [sig]). Cheap; never throws. */
    fun onPrint8748(mint: String, eventMs: Long, priceSol: Double, isBuy: Boolean, recvMs: Long, sig: String) {
        try {
            if (!com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint) || !(priceSol > 0.0) || !priceSol.isFinite()) return
            val lagS = (recvMs - eventMs).coerceAtLeast(0L) / 1_000L
            lagHist.incrementAndGet(LAG_EDGES_S.indexOfFirst { lagS < it }.let { if (it < 0) LAG_EDGES_S.size else it })
            val live = recvMs - eventMs in -5_000L..LaunchReplay8744.LIVE_LAG_MS_8748
            if (live) livePrints.incrementAndGet() else latePrints.incrementAndGet()
            // V5.0.8755 — a rule the Lab promoted (StrategyLab8755) is traded when the replays have not proven their own
            val proven = LaunchReplay8744.proven8748() ?: com.lifecyclebot.engine.lab.StrategyLab8755.launchProven8755()
            val result = proven ?: LaunchReplay8744.shadow8748()
            val baseProven = BaseFamily8754.proven8754() ?: com.lifecyclebot.engine.lab.StrategyLab8755.baseProven8755()
            val baseRule = baseProven ?: BaseFamily8754.shadow8754()
            if (result == null && baseRule == null) return
            var s = st[mint]
            if (s == null) {
                val born = CanonicalTokenBirthTime7440.peekBirthMs8418(mint, eventMs)
                if (born == null) { unborn.incrementAndGet(); return }
                if (st.size >= MAX_TRACKED) st.entries.removeIf { recvMs - it.value.lastSeen > 10 * 60_000L || recvMs - it.value.bornMs > HOUR }
                s = st.computeIfAbsent(mint) { St(born) }
            }
            if (eventMs - s.bornMs !in 0L..HOUR) return
            val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            if (!(solUsd > 0.0)) return
            val cap = priceSol * 1.0e9 * solUsd
            val fires = ArrayList<Fire>(2)
            synchronized(s) {
                if (sig.isNotBlank()) { if (sig in s.sigs) return; s.sigs.addLast(sig); if (s.sigs.size > 16) s.sigs.removeFirst() }
                s.lastSeen = recvMs
                // the launch family: live prints only, the cap / buys trigger
                if (live && result != null && !s.fired) {
                    if (isBuy) s.buys.addLast(eventMs)
                    while (s.buys.isNotEmpty() && s.buys.first() < eventMs - 60_000L) s.buys.removeFirst()
                    if (triggers8748(result.rule, cap, s.buys.size)) {
                        s.fired = true
                        fires.add(Fire(mint, recvMs, eventMs - s.bornMs, cap, result.rule.id, result.n, result.exTop3, result.exTop3Se, result.tenX,
                            armed = proven != null, triggerEventMs = eventMs))
                    }
                }
                // the base family: every print shapes its peak and window; only a live one can fire (as in the replay)
                if (baseRule != null) {
                    val tr = baseRule.rule.trigger
                    if (s.baseTrigger != tr) { s.baseTrigger = tr; s.base = BaseFamily8754.State(tr) }
                    val bs = s.base
                    if (bs != null && !bs.fired && bs.onPrint(eventMs - s.bornMs, priceSol, isBuy, live, solUsd) != null)
                        fires.add(Fire(mint, recvMs, eventMs - s.bornMs, cap, baseRule.rule.id, baseRule.n, baseRule.exTop3, baseRule.exTop3Se,
                            baseRule.tenX, armed = baseProven != null, triggerEventMs = eventMs))
                }
            }
            fires.forEach { onFire(it) }
        } catch (_: Throwable) {}
    }

    private fun onFire(f: Fire) {
        lastFire = f
        if (!f.armed) { shadowFires.incrementAndGet(); try { PipelineHealthCollector.labelInc("LAUNCH_SNIPER_SHADOW_FIRE_8748") } catch (_: Throwable) {}; return }
        armedFires.incrementAndGet()
        if (fires.size > 5_000) fires.entries.removeIf { f.atMs - it.value.atMs > EVIDENCE_TTL_MS }
        fires[f.mint] = f
        try { PipelineHealthCollector.labelInc("LAUNCH_SNIPER_ARMED_FIRE_8748") } catch (_: Throwable) {}
        try { com.lifecyclebot.network.HeliusCurveTape8354.ask8354(f.mint, f.atMs) } catch (_: Throwable) {}
        try {
            val added = com.lifecyclebot.engine.GlobalTradeRegistry.addToWatchlist(f.mint, f.mint.take(6), "LAUNCH_SNIPER_8748",
                initialMcap = f.capUsd)
            if (added.added) watchAdds.incrementAndGet()
        } catch (_: Throwable) {}
    }

    /** CandidateAdmission8136: the armed fire on [mint] as market-cell evidence, or null. */
    fun evidence8748(mint: String, nowMs: Long): CandidateAdmission8136.Evidence8136? {
        val f = fires[mint]?.takeIf { it.armed && nowMs - it.atMs in 0L..EVIDENCE_TTL_MS } ?: return null
        evidenceServed.incrementAndGet()
        return CandidateAdmission8136.Evidence8136("LAUNCH_RULE_8748", f.ruleId, f.n, f.exTop3, f.exTop3Se,
            runnerRate = f.tenX, netOfCost = true)
    }

    fun statusLine8748(): String {
        val p = LaunchReplay8744.proven8748(); val sh = LaunchReplay8744.shadow8748()
        val mode = when { p != null -> "ARMED on ${p.rule.id}"; sh != null -> "SHADOW on ${sh.rule.id}"; else -> "IDLE (no replay rule with n>=50 yet)" }
        val bp = BaseFamily8754.proven8754(); val bsh = BaseFamily8754.shadow8754()
        val baseMode = when { bp != null -> "ARMED on ${bp.rule.id}"; bsh != null -> "SHADOW on ${bsh.rule.id}"; else -> "IDLE" }
        val lf = lastFire?.let { "${it.mint.take(6)} age=${it.ageMs / 1000}s cap=\$${"%.0f".format(it.capUsd)} ${if (it.armed) "armed" else "shadow"} ${it.ruleId.take(40)}" } ?: "-"
        val h = lagHistSnapshot()
        val lag = "p50=${quantileMs8749(h, LAG_EDGES_S, 0.5)?.div(1000) ?: "-"}s p75=${quantileMs8749(h, LAG_EDGES_S, 0.75)?.div(1000) ?: "-"}s " +
            "p90=${quantileMs8749(h, LAG_EDGES_S, 0.9)?.div(1000) ?: "-"}s"
        val landedLine = synchronized(landed) { if (landed.isEmpty()) "-" else "n${landed.size} p50=${landed.sorted()[landed.size / 2] / 1000}s" }
        return "sniper8748[launch:$mode base:$baseMode needDelay=${requiredDelayMs8749() / 1000}s tapeLag{$lag} armedLanded{$landedLine} tracked=${st.size} livePrints=${livePrints.get()} latePrints=${latePrints.get()} noBirth=${unborn.get()} " +
            "fires{shadow=${shadowFires.get()} armed=${armedFires.get()}} watchAdds=${watchAdds.get()} evidenceServed=${evidenceServed.get()} " +
            "lastFire=$lf rule=every live pump.fun print checked against the replay's own trigger; buys only through admission + every safety door]"
    }
}
