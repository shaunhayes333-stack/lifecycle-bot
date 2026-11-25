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
 *  - SIGNALS (V5.0.8828): the owner's signals (SignalTape8828) read on every first-hour coin's prints; the one SignalReplay8828
 *    proved (or watches) fires like a launch rule — armed fires reach the watchlist and admission as evidence.
 *  - ATTENTION (V5.0.8825, LaunchReplay8744.attention8825 names the cumulative family's best graded trigger): a coin meeting
 *    it is put on the watchlist so the decision loop judges and labels the $10k crossers, armed or not. No evidence.
 * Nothing here buys. Nothing here is a hand threshold: the cap, the buys, the ladder and the latency are the replay's.
 */
object LaunchSniper8748 {
    const val KIND_8748 = "LAUNCH_RULE_8748"

    /**
     * V5.0.8949 §BUY_THE_RULES_FIRES_AND_HOLD_THEM — owner, 22 Nov 23:43: "we need to be capturing the handful of runners on
     * pump.fun first ... we now know how to find them correctly it has to buy them and hold them. this is a directive."
     * The replay's proven rule (23:03 capture: cap$5k buys>=3 tp5x stop35% DOCTRINE +20s, n3104, mean +7.1 %, ex-top-3
     * +6.5 % +-2.2, 2x+ 7 %) fired armed 28 times in 31 minutes; its coins reached the admission 50 times and were admitted
     * once. Three things stood between a fire and a held position:
     *  - the fire only put the coin on the watchlist, so the decision waited for the 8.9 s loop's rotation (PUMP_PORTAL_WS
     *    decide p50 34 s) on a rule graded at a 20 s entry: an armed fire now runs the coin's full pipeline at once
     *    ([onArmed8949] -> BotService fast lane; a coin not watched goes through the protected intake first, every gate);
     *  - the lane-wide entry priors (LIVE_STAGE_LOSES_8626 on the LAUNCH stage of every launch, the origin cohort, buys-the-
     *    high, stage unread) refused the rule's narrower proven group (LaneAuthority8577.PRIOR_ANSWERING_KINDS_8874 now
     *    holds [KIND_8748]); hard safety, structure, sellability, economics and the coin's own shapes still refuse;
     *  - a bought fire was stopped on its lane's ordinary stop (SHITCOIN 8 %, EXPRESS 10 %) where the rule was graded at
     *    35 %: its position now holds on the rule's own stop, kept inside the ordinary maximum (25 %; the hard floor 30 %
     *    and the catastrophe line stay below it) ([ruleStopMag8949] -> StopAuthority7887).
     */
    @Volatile var onArmed8949: ((mint: String) -> Unit)? = null
    private const val ENTRY_MATCH_MS_8949 = 10L * 60_000L
    private const val ORDINARY_STOP_MAX_8949 = 25.0
    private val admitted8949 = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Long>>()   // mint -> (rule stop %, admitted at)
    private val runs8949 = java.util.concurrent.atomic.AtomicLong(0L)
    private val admits8949 = java.util.concurrent.atomic.AtomicLong(0L)
    private val held8949 = java.util.concurrent.atomic.AtomicLong(0L)

    /** CandidateAdmission8136 (LIVE): a coin carrying an armed fire of the proven rule was ADMITTED: its position holds on the rule's stop. */
    fun noteAdmission8949(mint: String, kind: String, terminal: String, nowMs: Long = System.currentTimeMillis()) {
        if (kind != KIND_8748 || terminal != "ADMIT" || mint.isBlank()) return
        val f = fires[mint]?.takeIf { liveFire8959(it, nowMs) } ?: return
        // V5.0.9008 — a proven tape signal's fire holds the stop its record was graded at, as the launch rule's does: until
        // now only the cap-cross rule matched here, so a position bought on a signal took the lane's stop instead
        val stop9008 = LaunchReplay8744.proven8748()?.rule?.takeIf { it.id == f.ruleId }?.stopPct
            ?: signalStop9008(f.ruleId, SignalReplay8828.proven8828()) ?: return
        if (admitted8949.size > 2_000) admitted8949.entries.removeIf { nowMs - it.value.second > ENTRY_MATCH_MS_8949 }
        admitted8949[mint] = stop9008 to nowMs
        admits8949.incrementAndGet()
    }

    /** Pure: the graded stop of the proven tape signal [rec] when [ruleId] is its fire ("SIGNAL8828:<id> stop<N>%"), else null. */
    internal fun signalStop9008(ruleId: String, rec: SignalReplay8828.Rec?): Double? =
        rec?.takeIf { ruleId == "SIGNAL8828:${it.key}" }?.stopPct

    /** Pure: the ordinary stop magnitude a position entered at [entryMs] holds when its rule (graded at [ruleStopPct]) was admitted at [admittedMs]. */
    internal fun ruleStop8949(ruleStopPct: Double, admittedMs: Long, entryMs: Long): Double? =
        if (!(ruleStopPct.isFinite() && ruleStopPct > 0.0) || entryMs - admittedMs !in -60_000L..ENTRY_MATCH_MS_8949) null
        else ruleStopPct.coerceAtMost(ORDINARY_STOP_MAX_8949)

    /** StopAuthority7887: the rule's stop for the position on [mint] entered at [entryMs], or null when it was not a rule fire. */
    fun ruleStopMag8949(mint: String, entryMs: Long): Double? {
        val (stop, at) = admitted8949[mint] ?: return null
        return ruleStop8949(stop, at, entryMs)?.also { held8949.incrementAndGet() }
    }

    /** How long a fire stands as admission evidence (the bot's loop must reach the coin within it). */
    private const val EVIDENCE_TTL_MS = 120_000L
    private const val MAX_TRACKED = 30_000
    private const val HOUR = 3_600_000L

    private class St(val bornMs: Long) {
        val buys = ArrayDeque<Long>()
        // V5.0.8824 — the live buys since this coin was first heard (LaunchReplay8744.Rule.totalBuys)
        var totalBuys = 0
        val sigs = ArrayDeque<String>()
        var fired = false
        var watched = false   // V5.0.8825 — put on the watchlist by the attention trigger
        var lastSeen = 0L
        // V5.0.8754 — the base family's trigger for this coin: the very code BaseFamily8754 graded, fed every print
        var baseTrigger: BaseFamily8754.Trigger? = null
        var base: BaseFamily8754.State? = null
    }
    /** V5.0.8754 — a fire carries its rule's graded record (launch or base family), not one family's result type. */
    data class Fire(val mint: String, val atMs: Long, val ageMs: Long, val capUsd: Double, val ruleId: String, val n: Int,
                    val exTop3: Double, val exTop3Se: Double, val tenX: Double, val armed: Boolean, val triggerEventMs: Long = atMs,
                    val gradedDelayMs8968: Long = 0L)

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
        clocks9025[mint]?.let { c -> if (c.landMs < 0L) c.landMs = (nowMs - c.t0).coerceAtLeast(0L) }   // V5.0.9025
        try { PipelineHealthCollector.labelInc("LAUNCH_SNIPER_BUY_LANDED_8749") } catch (_: Throwable) {}
    }

    private val st = ConcurrentHashMap<String, St>()
    private val fires = ConcurrentHashMap<String, Fire>()
    private val livePrints = AtomicLong(); private val latePrints = AtomicLong(); private val unborn = AtomicLong()
    private val shadowFires = AtomicLong(); private val armedFires = AtomicLong(); private val watchAdds = AtomicLong()
    private val evidenceServed = AtomicLong()
    @Volatile private var lastFire: Fire? = null

    /**
     * Pure: does a coin at [capUsd] with [buys60s] live buys in the last minute and (V5.0.8824) [totalBuys] live buys since
     * it was first heard meet [rule]'s trigger?
     */
    fun triggers8748(rule: LaunchReplay8744.Rule, capUsd: Double, buys60s: Int, totalBuys: Int = 0): Boolean =
        capUsd.isFinite() && capUsd >= rule.capUsd && buys60s >= rule.minBuys && totalBuys >= rule.totalBuys

    /**
     * One decoded print of [mint] (chain time [eventMs], received [recvMs], signature [sig]; V5.0.8828: its SOL size [sol]
     * and the buyer / seller [wallet] when the feed names one). Cheap; never throws.
     */
    fun onPrint8748(mint: String, eventMs: Long, priceSol: Double, isBuy: Boolean, recvMs: Long, sig: String, sol: Double = 0.0, wallet: String = "") {
        try {
            if (!com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint) || !(priceSol > 0.0) || !priceSol.isFinite()) return
            val lagS = (recvMs - eventMs).coerceAtLeast(0L) / 1_000L
            lagHist.incrementAndGet(LAG_EDGES_S.indexOfFirst { lagS < it }.let { if (it < 0) LAG_EDGES_S.size else it })
            val live = recvMs - eventMs in -5_000L..LaunchReplay8744.LIVE_LAG_MS_8748
            if (live) livePrints.incrementAndGet() else latePrints.incrementAndGet()
            // V5.0.8856 — the floor watch follows every witnessed launch on this feed, whatever the replays have graded
            FloorWatch8856.onPrint8856(mint, eventMs, recvMs, priceSol, isBuy, live)
            // V5.0.8755 — a rule the Lab promoted (StrategyLab8755) is traded when the replays have not proven their own
            val proven = LaunchReplay8744.proven8748() ?: com.lifecyclebot.engine.lab.StrategyLab8755.launchProven8755()
            val result = proven ?: LaunchReplay8744.shadow8748()
            val baseProven = BaseFamily8754.proven8754() ?: com.lifecyclebot.engine.lab.StrategyLab8755.baseProven8755()
            val baseRule = baseProven ?: BaseFamily8754.shadow8754()
            val attention = LaunchReplay8744.attention8825()?.rule   // V5.0.8825
            val signalProven = SignalReplay8828.proven8828()             // V5.0.8828 — the owner's signals, graded
            val signal = signalProven ?: SignalReplay8828.shadow8828()
            if (result == null && baseRule == null && attention == null && signal == null) return
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
            var watch = false
            synchronized(s) {
                if (sig.isNotBlank()) { if (sig in s.sigs) return; s.sigs.addLast(sig); if (s.sigs.size > 16) s.sigs.removeFirst() }
                s.lastSeen = recvMs
                // the live buys (window and since first heard) feed both the launch rule and the attention trigger
                if (live && ((result != null && !s.fired) || (attention != null && !s.watched))) {
                    if (isBuy) { s.buys.addLast(eventMs); s.totalBuys++ }
                    while (s.buys.isNotEmpty() && s.buys.first() < eventMs - 60_000L) s.buys.removeFirst()
                }
                // V5.0.8825 — the attention trigger: the coin goes in front of the decision loop, nothing more
                if (live && attention != null && !s.watched && triggers8748(attention, cap, s.buys.size, s.totalBuys)) { s.watched = true; watch = true }
                // the launch family: live prints only, the cap / buys trigger
                if (live && result != null && !s.fired) {
                    if (triggers8748(result.rule, cap, s.buys.size, s.totalBuys)) {
                        s.fired = true
                        fires.add(Fire(mint, recvMs, eventMs - s.bornMs, cap, result.rule.id, result.n, result.exTop3, result.exTop3Se, result.tenX,
                            armed = proven != null, triggerEventMs = eventMs, gradedDelayMs8968 = result.rule.delayMs))
                    }
                }
                // the base family: every print shapes its peak and window; only a live one can fire (as in the replay)
                if (baseRule != null) {
                    val tr = baseRule.rule.trigger
                    if (s.baseTrigger != tr) { s.baseTrigger = tr; s.base = BaseFamily8754.State(tr) }
                    val bs = s.base
                    if (bs != null && !bs.fired && bs.onPrint(eventMs - s.bornMs, priceSol, isBuy, live, solUsd) != null)
                        fires.add(Fire(mint, recvMs, eventMs - s.bornMs, cap, baseRule.rule.id, baseRule.n, baseRule.exTop3, baseRule.exTop3Se,
                            baseRule.tenX, armed = baseProven != null, triggerEventMs = eventMs, gradedDelayMs8968 = baseRule.rule.delayMs))
                }
            }
            fires.forEach { onFire(it) }
            if (watch) watch8825(mint, cap, recvMs)
            if (signal != null) signalPrint8828(mint, s.bornMs, eventMs, priceSol, isBuy, sol, wallet, live, recvMs, cap, signal, signalProven != null)
        } catch (_: Throwable) {}
    }

    private val attentionFires = AtomicLong(); private val attentionAdds = AtomicLong()

    // ── V5.0.8828 — the owner's signals, live: the same SignalTape8828 the replay graded, one per first-hour coin ──
    private const val MAX_SIGNAL_TAPES_8828 = 1_000
    private class SigTape(val bornMs: Long) { val tape = SignalTape8828(); @Volatile var lastMs = 0L }
    private val sigTapes = ConcurrentHashMap<String, SigTape>()
    private val signalFires = AtomicLong(); private val signalArmed = AtomicLong(); private val signalTapeFull = AtomicLong()

    /** Feeds one print to [mint]'s signal tape; the graded signal turning on is a fire (armed when [armed]). */
    private fun signalPrint8828(mint: String, bornMs: Long, eventMs: Long, priceSol: Double, isBuy: Boolean, sol: Double, wallet: String,
                                live: Boolean, recvMs: Long, cap: Double, rec: SignalReplay8828.Rec, armed: Boolean) {
        var st = sigTapes[mint]
        if (st == null) {
            if (sigTapes.size >= MAX_SIGNAL_TAPES_8828) sigTapes.entries.removeIf { recvMs - it.value.bornMs > HOUR || recvMs - it.value.lastMs > 10 * 60_000L }
            if (sigTapes.size >= MAX_SIGNAL_TAPES_8828) { signalTapeFull.incrementAndGet(); return }
            st = sigTapes.computeIfAbsent(mint) { SigTape(bornMs) }
        }
        st.lastMs = recvMs
        val on = synchronized(st) { st.tape.onPrint(eventMs - st.bornMs, priceSol, isBuy, sol, SignalReplay8828.walletKey8828(wallet), live) }
        if (rec.id !in on) return
        signalFires.incrementAndGet(); if (armed) signalArmed.incrementAndGet()
        onFire(Fire(mint, recvMs, eventMs - st.bornMs, cap, "SIGNAL8828:${rec.key}", rec.n, rec.exTop3, rec.exTop3Se, rec.tenX,
            armed = armed, triggerEventMs = eventMs,
            // V5.0.9025 — a first-seconds signal is evidence for the delay it was graded at plus one bar, not for 120 s
            gradedDelayMs8968 = SignalReplay8828.windowMs9025(rec.id)))
    }

    /** V5.0.8825 — a crosser meeting the attention trigger: on the watchlist to be decided on and labelled (no evidence). */
    private fun watch8825(mint: String, capUsd: Double, nowMs: Long) {
        attentionFires.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LAUNCH_SNIPER_ATTENTION_8825") } catch (_: Throwable) {}
        try { com.lifecyclebot.network.HeliusCurveTape8354.ask8354(mint, nowMs) } catch (_: Throwable) {}
        try {
            val added = com.lifecyclebot.engine.GlobalTradeRegistry.addToWatchlist(mint, mint.take(6), "LAUNCH_SNIPER_WATCH_8825",
                initialMcap = capUsd)
            if (added.added) attentionAdds.incrementAndGet()
        } catch (_: Throwable) {}
    }

    // Four bounded waiters, never an extra running evaluation. Other origins retain immediate tryAcquire.
    private val runnerWaiters8958 = java.util.concurrent.Semaphore(4)
    internal suspend fun acquireFast8958(origin: String, permits: java.util.concurrent.Semaphore, valid: () -> Boolean): Boolean {
        if (permits.tryAcquire()) return true
        if (origin != "LAUNCH_RULE_8949" || !runnerWaiters8958.tryAcquire()) return false
        try {
            repeat(6) {
                kotlinx.coroutines.delay(100L)
                if (!valid()) return false
                if (permits.tryAcquire()) return true
            }
            return false
        } finally { runnerWaiters8958.release() }
    }

    private fun onFire(f: Fire) {
        lastFire = f
        if (!f.armed) { shadowFires.incrementAndGet(); try { PipelineHealthCollector.labelInc("LAUNCH_SNIPER_SHADOW_FIRE_8748") } catch (_: Throwable) {}; return }
        armedFires.incrementAndGet()
        if (fires.size > 5_000) fires.entries.removeIf { f.atMs - it.value.atMs > EVIDENCE_TTL_MS }
        fires[f.mint] = f
        try { openClock9025(f) } catch (_: Throwable) {}   // V5.0.9025 — the fire's clock starts at its trigger print
        try { PipelineHealthCollector.labelInc("LAUNCH_SNIPER_ARMED_FIRE_8748") } catch (_: Throwable) {}
        try { com.lifecyclebot.network.HeliusCurveTape8354.ask8354(f.mint, f.atMs) } catch (_: Throwable) {}
        try {
            val added = com.lifecyclebot.engine.GlobalTradeRegistry.addToWatchlist(f.mint, f.mint.take(6), "LAUNCH_SNIPER_8748",
                initialMcap = f.capUsd)
            if (added.added) watchAdds.incrementAndGet()
        } catch (_: Throwable) {}
        // V5.0.8949 — and the coin's full pipeline runs now, not when the loop's rotation reaches it
        try { onArmed8949?.let { it(f.mint); runs8949.incrementAndGet() } } catch (_: Throwable) {}
        // V5.0.8952 — an armed fire earns the coin pump.fun's own per-trade stream (selective, budgeted)
        try { com.lifecyclebot.network.PumpFunWS.deserve8952(f.mint, "LAUNCH_RULE") } catch (_: Throwable) {}
    }

    /**
     * V5.0.8824 — NetEdge8030.preTradeCostPct8329: is [mint] inside an armed fire's evidence window? Such an entry's record is
     * the replay's own net outcome on the same trigger, every dump through the stop included, so the band's average gap
     * (PocketLedger8610.gapCostPct8823) is not charged on top of it a second time. Pure read; counts nothing.
     */
    // Network delay spends the replay trigger's window; receipt must never restart the clock.
    internal fun liveFire8959(f: Fire, nowMs: Long): Boolean = f.armed && nowMs >= f.atMs &&
        nowMs - minOf(f.triggerEventMs, f.atMs) in 0L..window8968(f.gradedDelayMs8968)

    /**
     * V5.0.8968 §A_RULE_IS_TRADED_AT_THE_DELAY_IT_WAS_PROVEN_AT — live, 22-23 Nov (owner's export, 32 closes): every buy of a
     * pump.fun coin under a $20k cap lost (10 of 10, -0.052 SOL, held 0.4-11 min), bought at $9.8k-$17.9k while the armed rule
     * fires at a $5k cap; the $20k-$1M buys made +0.062 SOL (8 of 18 won). 8749's own finding: entering a launch 10 s after
     * the trigger averaged +26..+50 %, 30 s after -4..-6 % (-23 % without the best three) — the delay IS the strategy — and
     * the replay grades the rule at its own delay (+20 s), yet a fire stayed evidence for 120 s (EVIDENCE_TTL_MS), through
     * every data wait and the 8948 evidence slot, so the coin was admitted at a delay the rule was never proven at. The
     * bot's own decision reads agree (edge_research, 25 h, first decision per coin, pump.fun under $20k: n562 mean -17 %,
     * median -21..-27 %, and still -8..-12 % under the rule's own stop 35 % / take 5x). A launch or base rule's fire is now
     * evidence only for its graded delay; after that the coin is judged on its own record like any other. A signal rule
     * (graded without a delay here) keeps the 120 s. Nothing is switched off: the rule still fires, arms, watches and
     * learns, and a coin reached inside its delay trades exactly as before.
     */
    internal fun window8968(gradedDelayMs: Long): Long =
        if (gradedDelayMs > 0L) minOf(gradedDelayMs, EVIDENCE_TTL_MS) else EVIDENCE_TTL_MS

    fun armedWindow8824(mint: String, nowMs: Long): Boolean =
        mint.isNotBlank() && fires[mint]?.let { liveFire8959(it, nowMs) } == true

    /** CandidateAdmission8136: the armed fire on [mint] as market-cell evidence, or null. */
    fun evidence8748(mint: String, nowMs: Long): CandidateAdmission8136.Evidence8136? {
        val f = fires[mint]?.takeIf { liveFire8959(it, nowMs) } ?: return null
        evidenceServed.incrementAndGet()
        return CandidateAdmission8136.Evidence8136(KIND_8748, f.ruleId, f.n, f.exTop3, f.exTop3Se,
            runnerRate = f.tenX, netOfCost = true)
    }

    /**
     * V5.0.9025 §AN_ARMED_FIRE_IS_BOUGHT_IN_THE_SECONDS_IT_WAS_PROVEN_IN — owner, 26 Nov: "runners being missed left and
     * right ... the rest of the runner work completed properly". The 03:49 capture (5.0.9023, 16.5 min): the launch rule
     * was ARMED (cap$5k buys>=3 +20s, forward n5913 +7.7 +-2.2 %), fired armed 12 times, its coins were admitted, and ONE
     * buy landed, 88 s after its trigger. The owner's launch-hour export of the same morning (2,590 pump.fun launches,
     * 48 h, 10-second bars, filled at the next bar's open +3 %, sold -6 %, 35 % stop, the doctrine line from a 2x peak)
     * dates the edge: a launch still printing bought at 0 s +21.3 +-5.3 %, at 10 s +16.0 +-4.6, at 20-30 s +7, at 60 s
     * +3.6 +-4.8, at 120 s -0.3, at 180 s -7.3 +-3.7, at 300 s -10.3, at 900 s -13.9. The runners are bought in their first
     * half minute or not at all; a buy 88 s after the trigger is a buy of the same coins at a loss.
     *  - [fireRecord9025]: the live armed fire's own record, for LaneAuthority8577.fireDecision9025 — inside the fire's
     *    graded window the learned short-term refusals are advisory (the replay graded every fire of the trigger, the
     *    ones those records refuse included); safety, the door's proofs, the high, critical data and sizing bind.
     *  - the clock ([clockLine9025]): every armed fire's seconds from its trigger to its first admission verdict, its
     *    gate verdict and its landed buy, and how each fire ended, so a capture states where the seconds go.
     */
    fun fireRecord9025(mint: String, nowMs: Long): Double? =
        fires[mint]?.takeIf { liveFire8959(it, nowMs) }?.exTop3?.takeIf { it.isFinite() }

    private const val MAX_CLOCKS_9025 = 300
    private class Clock9025(val mint: String, val t0: Long, val ageMs: Long, val capUsd: Double, val windowMs: Long) {
        @Volatile var firstAdm = ""; @Volatile var firstAdmMs = -1L; @Volatile var lastAdm = ""
        @Volatile var admitMs = -1L
        @Volatile var fdg = ""; @Volatile var fdgMs = -1L; @Volatile var allowMs = -1L
        @Volatile var landMs = -1L

        /** How this fire ended: landed, allowed and not landed, the gate's refusal, the admission's, or never judged. */
        fun ending(): String = when {
            landMs >= 0L -> if (landMs <= windowMs) "LANDED_IN_WINDOW" else "LANDED_LATE"
            allowMs >= 0L -> "GATE_ALLOWED_NOT_LANDED"
            fdg.isNotBlank() -> "GATE:$fdg"
            admitMs >= 0L -> "ADMITTED_NO_GATE_VERDICT"
            lastAdm.isNotBlank() -> "ADM:$lastAdm"
            else -> "NEVER_JUDGED"
        }
        fun row(): String = "${mint.take(6)} age${ageMs / 1000}s \$${"%.0f".format(capUsd)} win${windowMs / 1000}s" +
            (if (firstAdmMs >= 0L) " adm+${firstAdmMs / 1000}s:${firstAdm.take(34)}" else " adm-") +
            (if (admitMs >= 0L) " admit+${admitMs / 1000}s" else "") +
            (if (fdgMs >= 0L) " gate+${fdgMs / 1000}s:${if (allowMs >= 0L) "ALLOW" else fdg.take(34)}" else "") +
            (if (landMs >= 0L) " land+${landMs / 1000}s" else "")
    }
    private val clocks9025 = ConcurrentHashMap<String, Clock9025>()

    /** Pure: the short name a verdict is counted under (the admission's prefix and any figures after ':' dropped). */
    internal fun clockWhy9025(reason: String?): String =
        (reason ?: "").removePrefix("CANDIDATE_ADMISSION_8136_").substringBefore(':').trim().take(44).ifBlank { "UNNAMED" }

    private fun openClock9025(f: Fire) {
        if (clocks9025.size >= MAX_CLOCKS_9025) clocks9025.entries.sortedBy { it.value.t0 }.take(MAX_CLOCKS_9025 / 5).forEach { clocks9025.remove(it.key) }
        clocks9025[f.mint] = Clock9025(f.mint, minOf(f.triggerEventMs, f.atMs), f.ageMs, f.capUsd, window8968(f.gradedDelayMs8968))
    }

    /** CandidateAdmission8136 (LIVE): an admission terminal for [mint]; counted when the coin carries an armed fire's clock. */
    fun noteAdmission9025(mint: String, terminal: String, reason: String?, nowMs: Long = System.currentTimeMillis()) {
        val c = clocks9025[mint] ?: return
        val ms = nowMs - c.t0
        if (ms !in 0L..600_000L) return
        val v = "$terminal:${clockWhy9025(reason)}"
        if (c.firstAdmMs < 0L) { c.firstAdmMs = ms; c.firstAdm = v }
        c.lastAdm = v
        if (terminal == "ADMIT" && c.admitMs < 0L) c.admitMs = ms
    }

    /** SpecialistOwnership7951.onFdgVerdict7951: the final gate's verdict on [mint]. */
    fun noteGate9025(mint: String, allow: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis()) {
        val c = clocks9025[mint] ?: return
        val ms = nowMs - c.t0
        if (ms !in 0L..600_000L) return
        if (c.fdgMs < 0L) c.fdgMs = ms
        if (allow) { if (c.allowMs < 0L) c.allowMs = ms } else if (c.allowMs < 0L) c.fdg = clockWhy9025(reason)
    }

    /** The armed fires of this session by how they ended, and the last eight with their seconds. */
    fun clockLine9025(nowMs: Long = System.currentTimeMillis()): String {
        val all = clocks9025.values.sortedBy { it.t0 }
        val done = all.filter { it.landMs >= 0L || nowMs - it.t0 > EVIDENCE_TTL_MS }
        val ends = done.groupingBy { it.ending() }.eachCount().entries.sortedByDescending { it.value }.take(10).joinToString(",") { "${it.key}=${it.value}" }
        fun p50(xs: List<Long>) = if (xs.isEmpty()) "-" else "${xs.sorted()[xs.size / 2] / 1000}s"
        return "fireClock9025[fires=${all.size} admittedInWindow=${all.count { it.admitMs in 0L..it.windowMs }} gateAllowInWindow=${all.count { it.allowMs in 0L..it.windowMs }} " +
            "landed=${all.count { it.landMs >= 0L }} landedInWindow=${all.count { it.landMs in 0L..it.windowMs }} " +
            "firstVerdict{p50=${p50(all.mapNotNull { c -> c.firstAdmMs.takeIf { it >= 0L } })}} admit{p50=${p50(all.mapNotNull { c -> c.admitMs.takeIf { it >= 0L } })}} " +
            "gate{p50=${p50(all.mapNotNull { c -> c.fdgMs.takeIf { it >= 0L } })}} ended{${ends.ifBlank { "-" }}} " +
            "last{${all.takeLast(8).joinToString(" | ") { it.row() }.ifBlank { "-" }}} " +
            "rule=seconds from the fire's trigger print; window = the delay the rule was proven at]"
    }

    internal fun resetClocksForTest9025() { clocks9025.clear() }
    internal fun openClockForTest9025(mint: String, t0: Long, windowMs: Long) { clocks9025[mint] = Clock9025(mint, t0, 0L, 5_000.0, windowMs) }

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
            "fires{shadow=${shadowFires.get()} armed=${armedFires.get()}} runNow8949=${runs8949.get()} admitted8949=${admits8949.get()} heldOnRuleStop8949=${held8949.get()} watchAdds=${watchAdds.get()} evidenceServed=${evidenceServed.get()} " +
            "attention8825{on=${LaunchReplay8744.attention8825()?.rule?.id ?: "none"} fires=${attentionFires.get()} watchlistAdds=${attentionAdds.get()}} " +
            "signals8828{${SignalReplay8828.proven8828()?.let { "ARMED ${it.key}" } ?: SignalReplay8828.shadow8828()?.let { "SHADOW ${it.key}" } ?: "none"} " +
            "tapes=${sigTapes.size}/$MAX_SIGNAL_TAPES_8828 fires=${signalFires.get()} armed=${signalArmed.get()} tapeFull=${signalTapeFull.get()}} " +
            "lastFire=$lf rule=every live pump.fun print checked against the replay's own trigger; buys only through admission + every safety door] " +
            clockLine9025()
    }
}
