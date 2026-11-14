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
    }
    data class Fire(val mint: String, val atMs: Long, val ageMs: Long, val capUsd: Double, val result: LaunchReplay8744.Result, val armed: Boolean)

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
            if (recvMs - eventMs !in -5_000L..LaunchReplay8744.LIVE_LAG_MS_8748) { latePrints.incrementAndGet(); return }
            livePrints.incrementAndGet()
            val proven = LaunchReplay8744.proven8748()
            val result = proven ?: LaunchReplay8744.shadow8748() ?: return
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
            val fire = synchronized(s) {
                if (s.fired) return
                if (sig.isNotBlank()) { if (sig in s.sigs) return; s.sigs.addLast(sig); if (s.sigs.size > 16) s.sigs.removeFirst() }
                s.lastSeen = recvMs
                if (isBuy) s.buys.addLast(eventMs)
                while (s.buys.isNotEmpty() && s.buys.first() < eventMs - 60_000L) s.buys.removeFirst()
                if (!triggers8748(result.rule, cap, s.buys.size)) return
                s.fired = true
                Fire(mint, recvMs, eventMs - s.bornMs, cap, result, armed = proven != null)
            }
            onFire(fire)
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
        return CandidateAdmission8136.Evidence8136("LAUNCH_RULE_8748", f.result.rule.id, f.result.n, f.result.exTop3, f.result.exTop3Se,
            runnerRate = f.result.tenX, netOfCost = true)
    }

    fun statusLine8748(): String {
        val p = LaunchReplay8744.proven8748(); val sh = LaunchReplay8744.shadow8748()
        val mode = when { p != null -> "ARMED on ${p.rule.id}"; sh != null -> "SHADOW on ${sh.rule.id}"; else -> "IDLE (no replay rule with n>=50 yet)" }
        val lf = lastFire?.let { "${it.mint.take(6)} age=${it.ageMs / 1000}s cap=\$${"%.0f".format(it.capUsd)} ${if (it.armed) "armed" else "shadow"}" } ?: "-"
        return "sniper8748[$mode tracked=${st.size} livePrints=${livePrints.get()} latePrints=${latePrints.get()} noBirth=${unborn.get()} " +
            "fires{shadow=${shadowFires.get()} armed=${armedFires.get()}} watchAdds=${watchAdds.get()} evidenceServed=${evidenceServed.get()} " +
            "lastFire=$lf rule=every live pump.fun print checked against the replay's own trigger; buys only through admission + every safety door]"
    }
}
