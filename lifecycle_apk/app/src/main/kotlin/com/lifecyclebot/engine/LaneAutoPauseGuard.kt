package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.4588 — LANE AUTO-PAUSE GUARD (operator P0 tasks a + b).
 *
 * Operator directive on 2026-02:
 *   • "EXPRESS needs to find the right strategy and tokens to trade!
 *      theres real opportunity for profit its just dumb at the source."
 *   • "MANIPULATED can be paused until llm lab proves a strategy thats
 *      profitable and is introduced then unpaused"
 *
 * The lanes literally have 0/19 (EXPRESS) and 5/33 with -50% EV
 * (MANIPULATED) as of build 5.0.4587 — no size shaping short of
 * multiplying-by-zero recovers from that. Rather than force zero-size
 * (which hides the lane from telemetry and pattern learning), this
 * guard blocks admission at the FDG level and marks the lane
 * "quarantined — awaiting LLM Lab strategy".
 *
 * A paused lane can still be run in the LLM Lab sandbox / shadow paper
 * — the guard only blocks the LIVE admission path.
 *
 * Rules (auto-pause fires when ALL true for the lane):
 *   • Sample floor:  n >= 15 trainable closes
 *   • Verdict:       (wins == 0)  OR  (WR < 20% AND EV <= -40%)
 *   • Not manually resumed since the last auto-pause event
 *
 * Doctrine:
 *   • Never touches paper / sandbox / lab paths — only LIVE admission.
 *   • Fail-open: any exception returns false (no block).
 *   • Persists across restarts via LearningPersistence so a device
 *     reboot doesn't silently reopen a proven-toxic lane.
 *   • Manual resume via LaneAutoPauseGuard.manualResume(lane) once
 *     an LLM Lab shadow proof shows >30% WR (or operator override).
 */
object LaneAutoPauseGuard {
    const val VERSION = "V5.0.4588_LANE_AUTO_PAUSE"
    private const val PERSIST_KEY = "LANE_AUTO_PAUSE_STATE"

    // Triggers
    // V5.0.6067 — AGGRESSIVE LANE AUTO-PAUSE.
    // Operator P0 (V5.0.6066 report): PRESALE_SNIPE lane appeared fresh and
    // burned n=10 straight losses for -0.2473 SOL before the guard could
    // trigger (previous ZERO_WIN_MIN_SAMPLE=15). QUALITY at n=10 WR=0% also
    // slipped through. Wallet went 0.6022 -> 0.4938 SOL (-18%) in 40 min.
    // Lower thresholds so bleeders quarantine 5-8 trades sooner:
    //   ZERO_WIN_MIN_SAMPLE 15 -> 8   (pause after 8 straight losses)
    //   TOXIC_MIN_SAMPLE     20 -> 12  (pause toxic lanes at n=12)
    //   TOXIC_WR_PCT         20 -> 20  (unchanged)
    //   TOXIC_EV_PCT        -40 -> -20 (much stricter EV floor)
    // Non-priority safety: MOONSHOT/STANDARD are handled by the compound
    // sizing floor (V5.0.6066), not this guard. Manual resume remains.
    private const val MIN_SAMPLE = 8
    private const val ZERO_WIN_MIN_SAMPLE = 8
    private const val TOXIC_WR_PCT = 20.0
    private const val TOXIC_EV_PCT = -20.0
    private const val TOXIC_MIN_SAMPLE = 12

    data class PauseState(
        val lane: String,
        val pausedAt: Long,
        val reason: String,
        val sample: Int,
        val wins: Int,
        val wrPct: Double,
        val evPct: Double,
    )

    private val paused = ConcurrentHashMap<String, PauseState>()
    @Volatile private var loaded = false
    private val lastEvalMs = AtomicLong(0L)

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val blob = LearningPersistence.load(PERSIST_KEY) ?: return
                val arr = org.json.JSONArray(blob)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val lane = o.optString("lane", "").uppercase()
                    if (lane.isEmpty()) continue
                    paused[lane] = PauseState(
                        lane = lane,
                        pausedAt = o.optLong("pausedAt", System.currentTimeMillis()),
                        reason = o.optString("reason", "restored"),
                        sample = o.optInt("sample", 0),
                        wins = o.optInt("wins", 0),
                        wrPct = o.optDouble("wrPct", 0.0),
                        evPct = o.optDouble("evPct", 0.0),
                    )
                }
            } catch (_: Throwable) {}
            // V5.0.4594 — HARD SEED for proven-toxic lanes (operator P0 4593
            // dump: wallet dropped -0.154 SOL/hr because evaluateLive path
            // never latched pauses despite direct-journal rewrite). Seed
            // EXPRESS (0/31 lifetime WR) and MANIPULATED (14.6% WR, -0.48 SOL
            // cumulative) as paused-at-load so they're locked out from
            // module init regardless of evaluateLive execution. Both
            // buckets are already flagged in LosingPatternMemory danger set;
            // operator can manualResume() after LLM Lab shadow proof.
            val nowSeed4594 = System.currentTimeMillis()
            listOf(
                Triple("EXPRESS", "hard_seed_4594_zero_win_31_trades", -91.6),
                Triple("MANIPULATED", "hard_seed_4594_wr14pct_ev48neg", -48.2),
                // V5.0.6067 — SEED PROVEN-TOXIC LANES from V5.0.6066 operator report.
                // These four burned wallet from 0.6022 -> 0.4938 SOL in 40 minutes.
                // PRESALE_SNIPE n=10 WR=0% ev=-21% PnL=-0.2473 SOL (biggest bleeder)
                // QUALITY       n=10 WR=0% ev=-32.6% PnL=-0.0615 SOL
                // TREASURY      n=13 WR=7.7% ev=-13% (only 1 fluke +0.10 SOL trade)
                // Operator can manualResume() any of them if desired.
                Triple("PRESALE_SNIPE", "hard_seed_6067_zero_win_10_trades_ev_-21pct", -21.03),
                Triple("QUALITY", "hard_seed_6067_zero_win_10_trades_ev_-32pct", -32.61),
            ).forEach { (lane, reason, ev) ->
                if (!paused.containsKey(lane)) {
                    paused[lane] = PauseState(
                        lane = lane,
                        pausedAt = nowSeed4594,
                        reason = reason,
                        sample = 30,
                        wins = 0,
                        wrPct = 0.0,
                        evPct = ev,
                    )
                    try {
                        ErrorLogger.warn(
                            "LaneAutoPauseGuard",
                            "🔒 LANE_HARD_SEED_PAUSED_4594 lane=$lane reason=$reason — pre-paused at guard init; manualResume() to lift",
                        )
                    } catch (_: Throwable) {}
                }
            }
            try { persistAsync() } catch (_: Throwable) {}
        }
    }

    private fun persistAsync() {
        try {
            val arr = org.json.JSONArray()
            paused.values.forEach { s ->
                arr.put(
                    org.json.JSONObject()
                        .put("lane", s.lane)
                        .put("pausedAt", s.pausedAt)
                        .put("reason", s.reason)
                        .put("sample", s.sample)
                        .put("wins", s.wins)
                        .put("wrPct", s.wrPct)
                        .put("evPct", s.evPct),
                )
            }
            LearningPersistence.save(PERSIST_KEY, arr.toString())
        } catch (_: Throwable) {}
    }

    /**
     * Cheap read used by FDG on every admission. Fail-open on any
     * exception. Returns null if lane is not paused; returns the pause
     * state if it is.
     */
    fun statusFor(lane: String?): PauseState? {
        if (lane.isNullOrBlank()) return null
        ensureLoaded()
        return paused[lane.uppercase()]
    }

    /** True if the lane is currently auto-paused. */
    fun isPaused(lane: String?): Boolean = statusFor(lane) != null

    /**
     * Snapshot all trainable lane telemetry from the CLEAN-TRUTH TradeHistoryStore
     * (V5.0.4592 — direct journal read; bypasses StrategyTelemetry cache which
     * was lagging behind live-terminal closes and letting proven-toxic lanes
     * add 4-6 more losers before the guard latched). Cheap; safe to call once
     * per bot-loop cycle (rate-limited to 30s).
     */
    fun evaluateLive() {
        val now = System.currentTimeMillis()
        val last = lastEvalMs.get()
        if (now - last < 30_000L) return
        if (!lastEvalMs.compareAndSet(last, now)) return
        ensureLoaded()
        try {
            // V5.0.4592 — DIRECT clean-truth journal read.
            // Operator P0: LaneAutoPauseGuard was reading via
            // LiveProbabilityEngine.laneSnapshots -> StrategyTelemetry which
            // filters/dedupes and can lag. Read TradeHistoryStore's
            // getRecentCleanStrategyTerminalTrades directly so wins=0 lanes
            // are quarantined on the very next tick after crossing n>=15.
            val clean = try {
                TradeHistoryStore.getRecentCleanStrategyTerminalTrades(limit = 2000)
            } catch (_: Throwable) { emptyList() }
            if (clean.isEmpty()) {
                try { ErrorLogger.debug("LaneAutoPauseGuard", "$VERSION evaluateLive: clean terminal trades empty (store cold)") } catch (_: Throwable) {}
                return
            }

            // Aggregate by tradingMode (lane) using the same win threshold
            // (V5.0.4102): pnlPct >= 5% counts as a win. V5.0.4593 — dropped
            // the side="SELL" filter because getRecentCleanStrategyTerminalTrades
            // already returns terminal (sell/partial-sell) rows only, and the
            // extra side check was silently dropping every row when TradeHistory
            // used side="EXIT" or similar in some code paths.
            data class Agg(var sample: Int = 0, var wins: Int = 0, var pnlSum: Double = 0.0)
            val byLane = HashMap<String, Agg>()
            for (t in clean) {
                val lane = t.tradingMode.trim().uppercase()
                if (lane.isBlank()) continue
                val agg = byLane.getOrPut(lane) { Agg() }
                agg.sample += 1
                if (t.pnlPct >= 5.0) agg.wins += 1
                agg.pnlSum += t.pnlPct
            }
            try {
                ErrorLogger.info(
                    "LaneAutoPauseGuard",
                    "$VERSION evaluateLive: clean=${clean.size} lanes=${byLane.size} paused=${paused.size} snapshot=" +
                        byLane.entries.joinToString(" ") { (l, a) -> "$l(n=${a.sample},w=${a.wins},ev=${"%.0f".format(if (a.sample > 0) a.pnlSum / a.sample else 0.0)}%)" },
                )
            } catch (_: Throwable) {}

            var mutated = false
            for ((lane, agg) in byLane) {
                if (paused.containsKey(lane)) continue
                val wrPct = if (agg.sample > 0) agg.wins.toDouble() / agg.sample.toDouble() * 100.0 else 0.0
                val evPct = if (agg.sample > 0) agg.pnlSum / agg.sample else 0.0
                val zeroWin = agg.sample >= ZERO_WIN_MIN_SAMPLE && agg.wins == 0
                val toxic = agg.sample >= TOXIC_MIN_SAMPLE &&
                    wrPct < TOXIC_WR_PCT &&
                    evPct <= TOXIC_EV_PCT
                if (zeroWin || toxic) {
                    val reason = if (zeroWin) "zero_win_n${agg.sample}_direct_journal" else "toxic_wr${"%.0f".format(wrPct)}_ev${"%.0f".format(evPct)}_direct_journal"
                    paused[lane] = PauseState(
                        lane = lane,
                        pausedAt = now,
                        reason = reason,
                        sample = agg.sample,
                        wins = agg.wins,
                        wrPct = wrPct,
                        evPct = evPct,
                    )
                    mutated = true
                    try {
                        ErrorLogger.warn(
                            "LaneAutoPauseGuard",
                            "🛑 LANE_AUTO_PAUSED lane=$lane reason=$reason n=${agg.sample} wins=${agg.wins} wr=${"%.1f".format(wrPct)}% ev=${"%.1f".format(evPct)}% — awaiting LLM Lab shadow-proof to resume",
                        )
                        PipelineHealthCollector.labelInc("LANE_AUTO_PAUSED_$lane")
                        PipelineHealthCollector.labelInc("LANE_AUTO_PAUSED_DIRECT_JOURNAL_4592")
                    } catch (_: Throwable) {}
                }
            }
            if (mutated) persistAsync()
        } catch (_: Throwable) {}
    }

    /** Manual resume — for LLM Lab success or operator override. */
    fun manualResume(lane: String, note: String = "manual") {
        ensureLoaded()
        val removed = paused.remove(lane.uppercase())
        if (removed != null) {
            try {
                ErrorLogger.info(
                    "LaneAutoPauseGuard",
                    "✅ LANE_MANUAL_RESUMED lane=${removed.lane} originalReason=${removed.reason} pausedFor=${(System.currentTimeMillis() - removed.pausedAt) / 60_000L}m note=$note",
                )
                PipelineHealthCollector.labelInc("LANE_MANUAL_RESUMED_${removed.lane}")
            } catch (_: Throwable) {}
            persistAsync()
        }
    }

    /** Diagnostic status line for reports. */
    fun statusLine(): String {
        ensureLoaded()
        if (paused.isEmpty()) return "$VERSION: no lanes paused"
        val s = paused.values.joinToString(" | ") { p ->
            "${p.lane}(n=${p.sample} wr=${"%.0f".format(p.wrPct)}% ev=${"%.0f".format(p.evPct)}% reason=${p.reason})"
        }
        return "$VERSION: ${paused.size} paused → $s"
    }

    fun pausedLanes(): Set<String> {
        ensureLoaded()
        return paused.keys.toSet()
    }
}
