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

    /** V5.0.8732 — the second status line: which proofs the PENDING coins wait on and what the admission decided of them. */
    fun waitLine8732(): String = "payCell8732[waits{${top8732(waits8732)}} admission{${top8732(decisions8732)}}" +
        " lastWait=${lastWait8732.ifBlank { "-" }} lastTrace=${lastTrace8732.ifBlank { "-" }}]"

    fun statusLine8725(): String = "lpState8725[" + STATES_8725.joinToString(" ") { st ->
        val s = try { ForwardReturnLabeler7731.laneStatFor7737("$PREFIX_8725$st") } catch (_: Throwable) { null }
        st + "=" + (if (s == null || s.n60 <= 0) "n0" else "n${s.n60}/${"%+.1f".format(s.meanNet60Pct)}%±${"%.1f".format(s.stderr60Pct)}/run${"%.0f".format(100.0 * s.runnerRate60)}%")
    } + " rule=paying-cell coins by what the live buy door said, forward net; the door is unchanged]"
}
