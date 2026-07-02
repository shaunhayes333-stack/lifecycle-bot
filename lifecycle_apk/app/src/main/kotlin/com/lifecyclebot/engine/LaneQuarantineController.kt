package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.LabStrategyStatus
import com.lifecyclebot.engine.lab.LlmLabStore
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🚧 LANE QUARANTINE CONTROLLER — V5.0.6002
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Operator directive 2026-07-02:
 *   *"the bleeders need to be paused until the llm lab these are contributes
 *    a winning tactic and is implemented! these would be manipulated and
 *    project sniper and dip Hunter. once the lab proves strategy they can be
 *    reintroduced into trading Autonomously"*
 *
 * PURPOSE
 * -------
 * A tiny runtime state brain that:
 *   1. Holds a hard-pause list of catastrophically-bleeding lanes:
 *      MANIPULATED, PROJECT_SNIPER, DIP_HUNTER  (per operator).
 *   2. Continuously scans LlmLabStore for a PROMOTED strategy whose
 *      name/rationale mentions the paused lane AND meets a proof floor
 *      (≥5 paper trades, ≥50% WR, ≥+0 SOL paper PnL). When found, the lane
 *      is autonomously released. Fluid — no manual toggle required.
 *   3. Emits a forensic event on quarantine + resume so the operator can
 *      audit the ecosystem's evolution.
 *
 * DESIGN INTENT
 * -------------
 * Quarantine is scoped to BUY / entry paths only. Existing open positions
 * ride out normally (10-second or 10-week holds unaffected). Exit paths
 * still work. This is a fluid pause, not a lane deletion.
 *
 * Lab match is by tag-in-name convention: a lab strategy targeting
 * "MANIPULATED" or "SNIPER" or "DIP_HUNTER" (case-insensitive substring in
 * `name` OR `rationale`) with proof floor met releases the lane. LlmLabStore
 * uses free-text names so this is the least-invasive tag.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object LaneQuarantineController {
    const val VERSION = "V5.0.6002_LANE_QUARANTINE_CONTROLLER"

    // Operator-seeded quarantine set. LlmLab promotion releases each lane
    // autonomously when a matching PROMOTED strategy is proven.
    // V5.0.6003 — EXTEND to EXPRESS + SHITCOIN. Operator dump 17:22 showed
    // both now match the exact 0%-WR-bleeder pattern that quarantined the
    // original three: EXPRESS n=7 W/L=0/7 EV=-83%/trade, SHITCOIN n=5
    // W/L=0/5 EV=-31%/trade. Same doctrine — pause until LLM Lab
    // paper-proves a strategy for each, then autonomous resume.
    private val seedQuarantine = setOf("MANIPULATED", "PROJECT_SNIPER", "DIP_HUNTER", "EXPRESS", "SHITCOIN")

    // V5.0.6003 — DYNAMIC AUTO-QUARANTINE. Any lane hitting the raw-reality-
    // clamp pattern (n≥5 closes AND WR≤15% AND EV≤-40%) is automatically
    // added to the quarantine set at runtime, symmetrically with the raw-
    // journal reality clamp in LiveProbabilityEngine. Fluid: releases via
    // the same LLM Lab promotion path. Prevents future bleeders from
    // requiring another operator directive.
    private val dynamicQuarantine = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastDynamicScanMs: Long = 0L
    private const val DYNAMIC_SCAN_TTL_MS = 20_000L
    private const val DYN_MIN_N = 5
    private const val DYN_MAX_WR_PCT = 15.0
    private const val DYN_MAX_MEAN_PNL_PCT = -40.0

    // Runtime-released lanes (autonomous resume). Once resumed, stays resumed
    // for the process lifetime (avoids oscillation). Fresh install re-seeds
    // the quarantine.
    private val releasedLanes = ConcurrentHashMap.newKeySet<String>()

    // Paper proof floor for a lab strategy to justify releasing its lane.
    private const val MIN_PAPER_TRADES_TO_RELEASE = 5
    private const val MIN_PAPER_WR_PCT_TO_RELEASE = 50.0
    private const val MIN_PAPER_PNL_SOL_TO_RELEASE = 0.0

    // Lab-scan throttle: no need to re-check every millisecond.
    @Volatile private var lastLabScanMs: Long = 0L
    private const val LAB_SCAN_TTL_MS = 30_000L

    /** True if the lane is currently blocked from taking new BUY entries. */
    fun isQuarantined(lane: String): Boolean {
        val l = lane.trim().uppercase()
        // Fast path: not quarantinable if not seeded AND not dynamically added.
        if (l !in seedQuarantine && l !in dynamicQuarantine) {
            maybeScanForDynamicQuarantine()
            if (l !in dynamicQuarantine) return false
        }
        if (l in releasedLanes) return false
        maybeScanLabForAutoResume()
        maybeScanForDynamicQuarantine()
        return (l in seedQuarantine || l in dynamicQuarantine) && l !in releasedLanes
    }

    /**
     * V5.0.6003 — DYNAMIC auto-quarantine scan. Reads the raw-journal
     * leaderboard (bypasses ledger sanitization). Any lane hitting the
     * reality-clamp criteria (n≥5 AND WR≤15% AND meanPnl≤-40%) is
     * automatically added to dynamicQuarantine. Same LLM Lab promotion
     * releases it. Prevents new bleeders from requiring another operator
     * directive.
     */
    private fun maybeScanForDynamicQuarantine() {
        val now = System.currentTimeMillis()
        if (now - lastDynamicScanMs < DYNAMIC_SCAN_TTL_MS) return
        lastDynamicScanMs = now
        try {
            val raw = TradeHistoryStore.getRecentValidClosedTradesRaw(limit = 500, includePartials = false)
            if (raw.isEmpty()) return
            val laneStats = raw.filter { it.side.equals("SELL", true) }
                .groupBy { it.tradingMode.trim().uppercase().ifBlank { "STANDARD" } }
            for ((lane, trades) in laneStats) {
                if (lane in seedQuarantine || lane in dynamicQuarantine) continue
                if (lane == "STANDARD" || lane == "CORE" || lane == "V3_CORE") continue
                if (lane == "MOONSHOT") continue  // MOONSHOT winning, never dynamically quarantine
                val n = trades.size
                if (n < DYN_MIN_N) continue
                val wins = trades.count { it.pnlPct > 0.0 }
                val losses = trades.count { it.pnlPct < 0.0 }
                val wlDenom = wins + losses
                val wrPct = if (wlDenom > 0) wins * 100.0 / wlDenom else 0.0
                val meanPnl = if (n > 0) trades.sumOf { it.pnlPct } / n else 0.0
                if (wrPct <= DYN_MAX_WR_PCT && meanPnl <= DYN_MAX_MEAN_PNL_PCT) {
                    dynamicQuarantine.add(lane)
                    try {
                        ForensicLogger.lifecycle(
                            "LANE_QUARANTINE_AUTO_ADDED_6003",
                            "lane=$lane n=$n wr=${"%.1f".format(wrPct)}% meanPnl=${"%+.1f".format(meanPnl)}% note=raw_journal_reality_bleeder_auto_paused",
                        )
                        PipelineHealthCollector.labelInc("LANE_QUARANTINE_AUTO_ADDED_6003_$lane")
                        ErrorLogger.info("LaneQuarantineController", "🚧 AUTO-QUARANTINE $lane (n=$n WR=${"%.1f".format(wrPct)}% mean=${"%+.1f".format(meanPnl)}%)")
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) { /* dynamic scan must never break the caller */ }
    }

    /** Log a blocked entry attempt (called at the buy-lane gate). */
    fun logBlockedEntry(lane: String, symbol: String, mint: String, primary: String) {
        try {
            ForensicLogger.lifecycle(
                "LANE_QUARANTINED_BLOCKED_ENTRY_6002",
                "lane=${lane.uppercase()} symbol=$symbol mint=${mint.take(10)} primary=$primary reason=awaiting_llm_lab_promotion",
            )
            PipelineHealthCollector.labelInc("LANE_QUARANTINED_BLOCKED_ENTRY_6002_${lane.uppercase()}")
        } catch (_: Throwable) {}
    }

    /**
     * Autonomous resume: scan LlmLabStore for a PROMOTED strategy tagged to
     * any quarantined lane. Meet the proof floor → release that lane.
     */
    private fun maybeScanLabForAutoResume() {
        val now = System.currentTimeMillis()
        if (now - lastLabScanMs < LAB_SCAN_TTL_MS) return
        lastLabScanMs = now
        try {
            val strategies = try { LlmLabStore.allStrategies() } catch (_: Throwable) { emptyList() }
            val allQuarantined = seedQuarantine + dynamicQuarantine
            for (lane in allQuarantined) {
                if (lane in releasedLanes) continue
                val tag = laneTag(lane)
                val proofStrategy = strategies.firstOrNull { s ->
                    s.status == LabStrategyStatus.PROMOTED &&
                        (s.name.contains(tag, ignoreCase = true) ||
                         s.rationale.contains(tag, ignoreCase = true) ||
                         s.name.contains(lane, ignoreCase = true) ||
                         s.rationale.contains(lane, ignoreCase = true)) &&
                        s.paperTrades >= MIN_PAPER_TRADES_TO_RELEASE &&
                        s.winRatePct() >= MIN_PAPER_WR_PCT_TO_RELEASE &&
                        s.paperPnlSol >= MIN_PAPER_PNL_SOL_TO_RELEASE
                }
                if (proofStrategy != null) {
                    releasedLanes.add(lane)
                    try {
                        ForensicLogger.lifecycle(
                            "LANE_QUARANTINE_AUTO_RESUMED_6002",
                            "lane=$lane strategy=${proofStrategy.name} paperN=${proofStrategy.paperTrades} paperWR=${"%.1f".format(proofStrategy.winRatePct())}% paperPnl=${"%+.3f".format(proofStrategy.paperPnlSol)} SOL note=llm_lab_promoted_release",
                        )
                        PipelineHealthCollector.labelInc("LANE_QUARANTINE_AUTO_RESUMED_6002_$lane")
                        ErrorLogger.info("LaneQuarantineController", "🧪 AUTO-RESUME $lane via lab strategy '${proofStrategy.name}' (paperN=${proofStrategy.paperTrades} WR=${"%.1f".format(proofStrategy.winRatePct())}%)")
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) { /* lab scan must never break the caller */ }
    }

    // Short recognisable tag by lane so the LLM naming convention has options.
    private fun laneTag(lane: String): String = when (lane) {
        "MANIPULATED" -> "MANIP"
        "PROJECT_SNIPER" -> "SNIPER"
        "DIP_HUNTER" -> "DIP"
        else -> lane
    }

    fun quarantineSnapshot(): Map<String, Boolean> = (seedQuarantine + dynamicQuarantine).associateWith { it in releasedLanes }

    fun statusLine(): String {
        val allQuar = seedQuarantine + dynamicQuarantine
        val active = allQuar.filter { it !in releasedLanes }
        val resumed = releasedLanes.toList().sorted()
        val dyn = dynamicQuarantine.toList().sorted()
        return "$VERSION seed=${seedQuarantine.joinToString(",")} dyn=${if (dyn.isEmpty()) "-" else dyn.joinToString(",")} active=${if (active.isEmpty()) "-" else active.joinToString(",")} resumed=${if (resumed.isEmpty()) "-" else resumed.joinToString(",")}"
    }
}
