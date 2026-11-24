package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.PreTradeHardGate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8725 §WHAT_THE_LP_RULE_TURNS_AWAY_IS_MEASURED_NOT_BOUGHT — owner: "2. but dont buy unlocked drain coins".
 *
 * The one market cell the 8720 dataset proves pays (PayingCell8723: not pump.fun, under five minutes old, pool $10k-50k)
 * is made of pools too young to show 70 % locked LP, so the live buy door (LiquidityOwnership8522 through
 * PreTradeHardGate.requireLiveBuyAllowed) refuses or waits on nearly all of it, and the dataset cannot say whether the
 * coins that pay are the locked ones: it carries no LP column. Nothing about the door changes here and no unlocked pool
 * is bought. Every LIVE candidate sitting in its lane's paying cell is probed against that same door (the probe spends,
 * logs and counts nothing) and filed, once per coin per 15 minutes, as a refused forward label under what the door said:
 * LP proven, LP proof pending, LP blocked (unlocked or under the required lock), or blocked for another reason. The four
 * records answer the question the rule's bind should rest on: do the coins it turns away pay or drain.
 */
object LpStateRecord8725 {
    const val PREFIX_8725 = "LPSTATE8725_"
    val STATES_8725 = listOf("PROVEN", "PENDING", "LP_BLOCKED", "OTHER_BLOCKED")
    private const val REFILE_MS = 15L * 60_000L
    private val lastFiled = ConcurrentHashMap<String, Long>()

    /** Pure: the state a probed door answer is filed under. [block] / [wait] as PreTradeHardGate.doorBlockOf8717 / proofWaitOf8705 give them. */
    fun stateOf8725(allowed: Boolean, block: String?, wait: String?): String = when {
        block != null && (block.contains("LP_LOCK") || block.contains("LP_UNLOCKED") || block.contains("LP_OWNERSHIP")) -> "LP_BLOCKED"
        block != null -> "OTHER_BLOCKED"
        wait != null -> "PENDING"
        allowed -> "PROVEN"
        else -> "OTHER_BLOCKED"
    }

    /** CandidateAdmission8136.assess8136 (LIVE, the coin is in its lane's paying cell): probe the buy door and file the label. */
    fun note8725(ts: TokenState, nowMs: Long = System.currentTimeMillis()) {
        try {
            val last = lastFiled[ts.mint]
            if (last != null && nowMs >= last && nowMs - last < REFILE_MS) return
            if (lastFiled.size > 6_000) lastFiled.entries.removeIf { nowMs - it.value > REFILE_MS }
            lastFiled[ts.mint] = nowMs
            val v = PreTradeHardGate.probeDoor8717(ts) ?: return
            val state = stateOf8725(v.allowed, PreTradeHardGate.doorBlockOf8717(v), PreTradeHardGate.proofWaitOf8705(v))
            // V5.0.8732 — a pending door names WHICH proof it waits on, so the record says what keeps the paying cell unbought.
            if (state == "PENDING") proofFamilies8732(v.detail).forEach { count8732(waits8732, it) }
            if (state == "PENDING") lastWait8732 = v.detail.take(220)
            PipelineHealthCollector.labelInc("$PREFIX_8725$state")
            ForwardReturnLabeler7731.observe(ts, "$PREFIX_8725$state", false, "$PREFIX_8725$state:${v.reason.take(48)}", nowMs)
            // a pending proof is asked for now, so the next read of this coin can be a proven or a blocked one
            if (state == "PENDING") try { com.lifecyclebot.engine.SafetyRefreshQueue.request(ts.mint) } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.8732 — 8731 phone run: paying cells proven in four lanes (+24.6..+32.4 %), 29 of 32 LIVE paying-cell coins were
     * PENDING at the buy door (+36.3 % forward, 34 % runners) and the record could not say which proof they waited on, nor
     * what the admission itself decided of them (ADMIT=0 across the run). Pure: the proof families a probed door's pending
     * detail ("REASON|item|item") waits on, LP ownership split by its own sub-reason.
     */
    fun proofFamilies8732(detail: String): List<String> {
        val parts = detail.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf("UNNAMED")
        val items = parts.drop(1).ifEmpty { parts }
        return items.map { item ->
            val u = item.uppercase()
            when {
                u.startsWith("SAFETY_PROOF_STALE_OR_MISSING") -> "SAFETY_REPORT_STALE"
                u.startsWith("SAFETY_POLICY_NOT_LIVE") -> "SAFETY_POLICY_PAPER"
                u.startsWith("RUGCHECK_PENDING_OR_UNKNOWN") -> "RUGCHECK"
                u == "MINT_AUTHORITY_UNKNOWN" -> "MINT_AUTHORITY"
                u.startsWith("HOLDER_") -> "HOLDERS"
                u.startsWith("LP_OWNERSHIP_PROOF_PENDING_8522") -> "LP_" + when {
                    u.contains("ANOTHER OR UNKNOWN MINT") -> "OTHER_MINT"
                    u.contains("STALE OR MISSING") -> "REPORT_STALE"
                    u.contains("NO COMPLETE OWNERSHIP") -> "NO_POOL_PROOF"
                    u.contains("CURVE LABEL") -> "CURVE_UNPROVEN"
                    u.contains("OWNERSHIP UNKNOWN") -> "LOCK_UNKNOWN"
                    else -> "OTHER"
                }
                u.startsWith("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP") || u.startsWith("TOKEN_MAP") -> "TOKEN_MAP"
                u.startsWith("LIQUIDITY_PROOF_PENDING") -> "POOL_DEPTH"
                else -> u.substringBefore(':').take(32).ifBlank { "UNNAMED" }
            }
        }.distinct()
    }

    /** CandidateAdmission8136.assess8136 (LIVE, paying-cell coin): what the admission itself decided — counted, never acted on. */
    fun noteAdmission8732(terminal: String, reason: String, trace: List<String>) {
        count8732(decisions8732, "$terminal:${reason.take(48)}")
        lastTrace8732 = trace.joinToString(" | ").take(600)
    }

    private val waits8732 = ConcurrentHashMap<String, AtomicLong>()
    private val decisions8732 = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var lastWait8732 = ""
    @Volatile private var lastTrace8732 = ""
    private fun count8732(m: ConcurrentHashMap<String, AtomicLong>, k: String) {
        if (m.size < 64 || m.containsKey(k)) m.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
    }
    private fun top8732(m: ConcurrentHashMap<String, AtomicLong>): String =
        m.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }

    /**
     * V5.0.8937 §WHAT_THE_LP_RULE_KEEPS_OUT_IS_FOLLOWED — owner, 22 Nov 14:24: "research the lp lock and come up with a smarter
     * strategy"; his standing decision: the LP rule stays, "measure what LP-blocked coins go on to do first". The 8725 record
     * probes only coins already in a lane's paying cell, which an LP-blocked coin never reaches (it is refused at intake,
     * HardRugPreFilter: 319 in 2.2 h on the 13:59 capture), so its LP_BLOCKED state was never filed: n0. Now every LIVE coin
     * whose ONLY hard safety finding is the LP rule is filed under LP_BLOCKED at intake, once per coin per 15 minutes, by its
     * band: provider lock under 30 %, 30-70 %, or the named unlocked-LP overlay. The forward labeler reads each at 5 m .. 1 h
     * (and 4 h / 24 h), with its peak and its deepest dip, so the record says whether the coins the rule keeps out drain or
     * pay. Nothing is bought; the rule is unchanged.
     */
    fun noteLpBlocked8937(ts: TokenState, hardReasons: List<String>, nowMs: Long = System.currentTimeMillis()) {
        val band = lpOnlyBand8937(hardReasons) ?: return
        val key = "lp8937|${ts.mint}"
        val last = lastFiled[key]
        if (last != null && nowMs >= last && nowMs - last < REFILE_MS) return
        // V5.0.8997 — who holds the coin's pool (PoolLock8895.spreadFor8997) is part of the label: the read is asked on the
        // first look and the label is filed on the next one with what it found, [SPREAD_WAIT_MS_8997] at most (then UNREAD)
        val pool8997 = listOf(ts.lastPricePoolAddr, ts.pairAddress).map { it.trim() }.firstOrNull { it.length in 32..44 && it != ts.mint }
        val spread8997 = pool8997?.let { try { PoolLock8895.spreadFor8997(ts.mint, it, nowMs) } catch (_: Throwable) { null } }
        if (pool8997 != null && spread8997 == null) {
            if (spreadWait8997.size > 4_000) spreadWait8997.entries.removeIf { nowMs - it.value > REFILE_MS }
            val first = spreadWait8997.putIfAbsent(key, nowMs) ?: nowMs
            if (nowMs - first in 0L until SPREAD_WAIT_MS_8997) return
        }
        spreadWait8997.remove(key)
        val cls8997 = spread8997?.cls ?: "UNREAD"
        if (lastFiled.size > 6_000) lastFiled.entries.removeIf { nowMs - it.value > REFILE_MS }
        lastFiled[key] = nowMs
        count8732(blocked8937, band)
        count8732(blockedSpread8997, cls8997)
        PipelineHealthCollector.labelInc("${PREFIX_8725}LP_BLOCKED_INTAKE_8937_$band")
        ForwardReturnLabeler7731.observe(ts, "${PREFIX_8725}LP_BLOCKED", false, "${PREFIX_8725}LP_BLOCKED:$band/$cls8997", nowMs)
    }

    /**
     * Pure (V5.0.8937): the LP band when every one of [hardReasons] is the LP rule (TokenSafetyChecker's "LP only N% locked"
     * bands and the live unlocked-LP overlay), else null — a coin also refused for anything else is not the LP rule's to measure.
     */
    fun lpOnlyBand8937(hardReasons: List<String>): String? {
        if (hardReasons.isEmpty()) return null
        var band: String? = null
        for (r in hardReasons) {
            val b = when {
                r.startsWith("LP unlocked with low liquidity") -> "UNLOCKED_FLAG"
                r.startsWith("LP only ") -> Regex("^LP only (\\d+)% locked").find(r)?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { if (it < 30) "LT30" else if (it < 70) "30_70" else null }
                else -> null
            } ?: return null
            if (band == null || b == "LT30" || (b == "UNLOCKED_FLAG" && band == "30_70")) band = b
        }
        return band
    }

    private val blocked8937 = ConcurrentHashMap<String, AtomicLong>()
    // V5.0.8997 — the LP-blocked coins by who holds their pool, and the coins waiting for that read
    private val blockedSpread8997 = ConcurrentHashMap<String, AtomicLong>()
    private val spreadWait8997 = ConcurrentHashMap<String, Long>()
    internal const val SPREAD_WAIT_MS_8997 = 20_000L

    /** Brief (V5.0.8997): LP-blocked coins filed, by the class of their pool's holders. */
    fun spreadLine8997(): String = "lpBlockedSpread8997{${top8732(blockedSpread8997)}}"

    /** V5.0.8732 — the second status line: which proofs the PENDING coins wait on and what the admission decided of them. */
    fun waitLine8732(): String = "payCell8732[waits{${top8732(waits8732)}} admission{${top8732(decisions8732)}}" +
        " lastWait=${lastWait8732.ifBlank { "-" }} lastTrace=${lastTrace8732.ifBlank { "-" }}]"

    fun statusLine8725(): String = "lpState8725[" + STATES_8725.joinToString(" ") { st ->
        val s = try { ForwardReturnLabeler7731.laneStatFor7737("$PREFIX_8725$st") } catch (_: Throwable) { null }
        st + "=" + (if (s == null || s.n60 <= 0) "n0" else "n${s.n60}/${"%+.1f".format(s.meanNet60Pct)}%±${"%.1f".format(s.stderr60Pct)}/run${"%.0f".format(100.0 * s.runnerRate60)}%")
    } + " intakeLpBlocked8937{${top8732(blocked8937)}}" +
        " rule=paying-cell coins by what the live buy door said, forward net; from 8937 LP_BLOCKED also follows every LIVE coin only the LP rule refused at intake; the door is unchanged]"
}
