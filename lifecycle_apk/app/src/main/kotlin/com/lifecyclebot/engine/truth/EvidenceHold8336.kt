package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8336 §EVIDENCE_BEFORE_LIVE — owner: "the bot needs to hold off live trading until it can build sufficient
 * evidence."
 *
 * 5.0.8329 live (03:10): 12 closes, 2 wins, PF 0.12; every lane's graded labels negative; Cortex 0 STRONG cells. The
 * live entries came through doors that need no measured edge (ADMIT_8114_8326 137, exploration slot 266).
 *
 * The rule, per meme lane, while [enabled8336]:
 *   - a LIVE entry is refused (EVIDENCE_HOLD_8336_<lane>) until the lane's own PAPER record since [startMs8336] proves:
 *     at least [MIN_N_8336] whole closes, mean return minus one standard error above [MARGIN_PCT_8336] (paper fills are
 *     optimistic), and profit factor at least [MIN_PF_8336];
 *   - PAPER is never refused here: paper is where the evidence is built.
 * Only paper closes made after the hold started count ([startMs8336], persisted): older paper rows were made by a looser
 * entry policy. While [rehearsal8336] is on, Entry8114Mode8326 decides PAPER meme entries the same way it decides LIVE
 * ones, so the paper record measures the live policy (see Entry8114Mode8326.liveActive8326).
 * A lane that has proven goes live; its live closes then answer to EntryEconomics8329 (3+ closes, mean < 0 shuts it).
 * Hard safety, exits and sizing are untouched.
 */
object EvidenceHold8336 {
    const val MIN_N_8336 = 30
    const val MARGIN_PCT_8336 = 2.0
    const val MIN_PF_8336 = 1.3
    private const val CACHE_MS_8336 = 30_000L
    private const val PREFS_8336 = "aate_evidence_hold_8336"
    private const val KEY_START_8336 = "start_ms"

    /** On by default; the owner can switch it off (live trades without paper proof, as 8329). */
    @Volatile var enabled8336: Boolean = true
    /** Paper meme entries are decided by the live (8114-mode) decision while the hold is on. */
    @Volatile var rehearsal8336: Boolean = true
    @Volatile var startMs8336: Long = 0L
        private set

    data class Proof8336(val n: Int, val meanPct: Double, val sePct: Double, val pf: Double, val proven: Boolean)

    private val refused = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var cache: Pair<Long, Map<String, Proof8336>>? = null

    /** BotService.onCreate: the hold's start (first install of 8336) is persisted so a restart keeps counting. */
    fun init8336(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences(PREFS_8336, Context.MODE_PRIVATE)
            var s = p.getLong(KEY_START_8336, 0L)
            if (s <= 0L) { s = System.currentTimeMillis(); p.edit().putLong(KEY_START_8336, s).apply() }
            startMs8336 = s
        } catch (_: Throwable) { if (startMs8336 <= 0L) startMs8336 = System.currentTimeMillis() }
    }

    /** Pure: the proof a lane's paper returns (% per whole close) and SOL results make. */
    fun proof8336(returnsPct: List<Double>, pnlSol: List<Double>): Proof8336 {
        val r = returnsPct.filter { it.isFinite() }.map { it.coerceIn(-100.0, 1_000.0) }
        val n = r.size
        if (n == 0) return Proof8336(0, Double.NaN, Double.NaN, Double.NaN, false)
        val mean = r.average()
        val se = if (n > 1) kotlin.math.sqrt(r.sumOf { (it - mean) * (it - mean) } / (n - 1)) / kotlin.math.sqrt(n.toDouble()) else Double.POSITIVE_INFINITY
        val wins = pnlSol.filter { it.isFinite() && it > 0.0 }.sum()
        val losses = -pnlSol.filter { it.isFinite() && it < 0.0 }.sum()
        val pf = if (losses > 0.0) wins / losses else if (wins > 0.0) Double.POSITIVE_INFINITY else 0.0
        val proven = n >= MIN_N_8336 && mean - se > MARGIN_PCT_8336 && pf >= MIN_PF_8336
        return Proof8336(n, mean, se, pf, proven)
    }

    private fun canon(lane: String): String =
        (try { CanonicalLaneIdentity6506.canonical(lane) } catch (_: Throwable) { "" }).uppercase().ifBlank { lane.trim().uppercase() }

    private fun proofs8336(nowMs: Long): Map<String, Proof8336> {
        cache?.let { (at, m) -> if (nowMs - at in 0L until CACHE_MS_8336) return m }
        val since = startMs8336
        val rows: List<Trade> = try {
            com.lifecyclebot.engine.TradeHistoryStore.getRecentValidClosedTradesRaw(limit = 5_000, includePartials = false)
        } catch (_: Throwable) { emptyList() }
        val paper = rows.filter { it.mode.equals("paper", true) && it.side.equals("SELL", true) && it.ts >= since }
        val m = paper.groupBy { canon(it.tradingMode.ifBlank { "STANDARD" }) }.mapValues { (_, t) ->
            proof8336(t.map { it.pnlPct }, t.map { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol })
        }
        cache = nowMs to m
        return m
    }

    fun proofFor8336(lane: String, nowMs: Long = System.currentTimeMillis()): Proof8336 =
        proofs8336(nowMs)[canon(lane)] ?: Proof8336(0, Double.NaN, Double.NaN, Double.NaN, false)

    /** The live refusal for [lane] while the hold is on and its paper record has not proven; null otherwise. */
    fun refusal8336(lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        if (!enabled8336 || paper) return null
        val live = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        if (!live) return null
        val l = canon(lane)
        if (proofFor8336(l, nowMs).proven) return null
        refused.computeIfAbsent(l) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EVIDENCE_HOLD_8336_$l") } catch (_: Throwable) {}
        return "EVIDENCE_HOLD_8336_$l"
    }

    fun statusLine8336(nowMs: Long = System.currentTimeMillis()): String {
        val lanes = proofs8336(nowMs).entries.sortedByDescending { it.value.n }.joinToString(" · ") { (l, p) ->
            "$l n${p.n}/${MIN_N_8336} mean${"%+.1f".format(p.meanPct)}% se${"%.1f".format(p.sePct)} pf${"%.2f".format(p.pf)}${if (p.proven) " LIVE" else " held"}"
        }.ifBlank { "no paper closes since start" }
        return "evidenceHold8336[on=$enabled8336 rehearsal=$rehearsal8336 sinceMin=${if (startMs8336 > 0) (nowMs - startMs8336) / 60_000 else -1} " +
            "bar=n>=$MIN_N_8336,mean-se>$MARGIN_PCT_8336%,pf>=$MIN_PF_8336 refused=${refused.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} $lanes]"
    }
}
