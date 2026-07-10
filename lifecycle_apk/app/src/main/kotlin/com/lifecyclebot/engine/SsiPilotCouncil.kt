package com.lifecyclebot.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6073 — SSI PILOT COUNCIL. The LLM becomes the PILOT; the operator is
 * the CONTROL TOWER.
 *
 * Fuses the previously-disconnected intelligence stack into ONE actuated hand:
 *   • SentienceOrchestrator symbolic state (risk / conf / health / edge / mood)
 *   • MetaCognitionAI layer trust
 *   • Live lane truth from TradeHistoryStore clean terminal closes
 *   • LaneAutoPauseGuard + LaneQuarantineController state
 *   • LLM Lab strategy summary
 * ...and asks the multi-provider LLM council (Gemini→Emergent→OpenRouter→
 * Groq→Cerebras→Mistral failover via GeminiCopilot) for a bounded PILOT
 * DIRECTIVE every cycle.
 *
 * Actuation (hard-clamped, fail-open to 1.0):
 *   • sizeBias        — global sizing hand   (live 0.55..1.80, paper 0.45..2.10)
 *   • laneFocus (≤3)  — 1.25× on named lanes
 *   • laneAvoid (≤3)  — 0.45× on named lanes
 *   • exitPatience    — exposed for exit layers (0.65..1.55)
 *   • resumeLane      — PAPER/LIVE: autonomous resume for non-safety lane pauses;
 *                       hard SecurityGuard/KillSwitch/rug/wallet safety is untouched.
 *   • note            — pilot monologue for UI/reports.
 *
 * Directive TTL 45 min — a dead LLM chain decays the pilot to neutral, it can
 * never leave a stale hand on the stick.
 */
object SsiPilotCouncil {
    const val VERSION = "V5.0.6073_SSI_PILOT"
    private const val PERSIST_KEY = "SSI_PILOT_DIRECTIVE"
    private const val CYCLE_MS = 5 * 60_000L
    private const val WARMUP_MS = 3 * 60_000L
    private const val DIRECTIVE_TTL_MS = 45 * 60_000L
    private const val KNOWN_LANES = "MOONSHOT,SHITCOIN,EXPRESS,QUALITY,BLUECHIP,TREASURY,CASHGEN,MANIPULATED,DIP_HUNTER,PRESALE_SNIPE,STANDARD"

    data class Directive(
        val sizeBias: Double,
        val laneFocus: Set<String>,
        val laneAvoid: Set<String>,
        val exitPatience: Double,
        val note: String,
        val atMs: Long,
    )

    @Volatile private var directive: Directive? = null
    @Volatile private var running = false
    @Volatile private var lastError: String? = null
    private var job: Job? = null
    private val cycleCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val laneAgg = ConcurrentHashMap<String, String>()

    @Synchronized
    fun start() {
        if (running) return
        running = true
        restore()
        job = GlobalScope.launch(Dispatchers.IO) {
            delay(WARMUP_MS)
            ErrorLogger.info("SsiPilotCouncil", "🧭 $VERSION started — LLM pilot loop every ${CYCLE_MS / 60_000}min")
            while (isActive && running) {
                try { runOneCycle() } catch (t: Throwable) {
                    lastError = t.message?.take(120)
                    ErrorLogger.debug("SsiPilotCouncil", "cycle failed: ${t.message?.take(120)}")
                }
                delay(CYCLE_MS)
            }
        }
    }

    @Synchronized
    fun stop() { running = false; job?.cancel(); job = null }

    // ── ACTUATION READS ────────────────────────────────────────────────────

    /** Bounded pilot sizing hand consumed by Executor's sizing stack. */
    fun sizeMultiplierForLane(lane: String?): Double {
        val d = freshDirective() ?: return 1.0
        val l = (lane ?: "").uppercase()
        var m = d.sizeBias
        if (l.isNotBlank()) {
            if (d.laneAvoid.any { l.contains(it) }) m *= 0.45
            else if (d.laneFocus.any { l.contains(it) }) m *= 1.25
        }
        val paper = try { GlobalTradeRegistry.isPaperMode } catch (_: Throwable) { true }
        return if (paper) m.coerceIn(0.40, 2.25) else m.coerceIn(0.45, 1.90)
    }

    /** Exposed for exit layers (runner hold patience shaping). */
    fun exitPatience(): Double = freshDirective()?.exitPatience ?: 1.0

    fun pilotNote(): String = freshDirective()?.note ?: ""

    fun statusLine(): String {
        val d = directive
        return if (d == null) "$VERSION: no directive yet err=${lastError ?: "-"}"
        else "$VERSION: bias=${"%.2f".format(d.sizeBias)} focus=${d.laneFocus.joinToString("+").ifEmpty { "-" }} " +
            "avoid=${d.laneAvoid.joinToString("+").ifEmpty { "-" }} patience=${"%.2f".format(d.exitPatience)} " +
            "ageMin=${(System.currentTimeMillis() - d.atMs) / 60_000} cycles=${cycleCount.get()} note=${d.note.take(90)}"
    }

    private fun freshDirective(): Directive? {
        val d = directive ?: return null
        return if (System.currentTimeMillis() - d.atMs <= DIRECTIVE_TTL_MS) d else null
    }

    // ── CYCLE ──────────────────────────────────────────────────────────────

    private fun runOneCycle() {
        cycleCount.incrementAndGet()
        val state = harvestState()
        val raw = GeminiCopilot.rawText(
            userPrompt = state,
            systemPrompt = SYSTEM_PROMPT,
            temperature = 0.65,
            maxTokens = 500,
        ) ?: run {
            lastError = "llm_chain_unavailable"
            try { PipelineHealthCollector.labelInc("SSI_PILOT_LLM_UNAVAILABLE_6073") } catch (_: Throwable) {}
            return
        }
        val parsed = parseDirective(raw) ?: run {
            lastError = "parse_failed"
            try { PipelineHealthCollector.labelInc("SSI_PILOT_PARSE_FAILED_6073") } catch (_: Throwable) {}
            return
        }
        directive = parsed
        lastError = null
        persist(parsed)
        try {
            ForensicLogger.lifecycle(
                "SSI_PILOT_DIRECTIVE_6073",
                "bias=${"%.2f".format(parsed.sizeBias)} focus=${parsed.laneFocus.joinToString("+")} avoid=${parsed.laneAvoid.joinToString("+")} patience=${"%.2f".format(parsed.exitPatience)} note=${parsed.note.take(120)}",
            )
            PipelineHealthCollector.labelInc("SSI_PILOT_DIRECTIVE_APPLIED_6073")
        } catch (_: Throwable) {}
        try { SentienceOrchestrator.noteRuntimeEvent("SSI_PILOT", parsed.note.take(200), "INFO") } catch (_: Throwable) {}
    }

    private fun harvestState(): String = buildString {
        val paper = try { GlobalTradeRegistry.isPaperMode } catch (_: Throwable) { true }
        append("MODE=${if (paper) "PAPER" else "LIVE"}\n")
        try {
            append("SYMBOLIC risk=${"%.2f".format(SymbolicContext.overallRisk)} conf=${"%.2f".format(SymbolicContext.overallConfidence)} ")
            append("health=${"%.2f".format(SymbolicContext.marketHealth)} edge=${"%.2f".format(SymbolicContext.edgeStrength)} mood=${SymbolicContext.emotionalState}\n")
        } catch (_: Throwable) {}
        try {
            laneAgg.clear()
            val closes = TradeHistoryStore.getRecentCleanStrategyTerminalTrades(limit = 300)
            val byLane = closes.groupBy { it.tradingMode.trim().uppercase().ifBlank { "STANDARD" } }
            append("LANES(last300 closes):\n")
            byLane.entries.sortedByDescending { it.value.size }.take(10).forEach { (lane, ts) ->
                val wins = ts.count { it.pnlPct >= 5.0 }
                val ev = if (ts.isNotEmpty()) ts.sumOf { it.pnlPct } / ts.size else 0.0
                val line = "n=${ts.size} wr=${if (ts.isNotEmpty()) wins * 100 / ts.size else 0}% ev=${"%.1f".format(ev)}%"
                laneAgg[lane] = line
                append("  $lane $line\n")
            }
        } catch (_: Throwable) {}
        try { append("PAUSED=${LaneAutoPauseGuard.pausedLanes().joinToString(",").ifEmpty { "-" }}\n") } catch (_: Throwable) {}
        try { append("QUARANTINE=${LaneQuarantineController.statusLine().take(200)}\n") } catch (_: Throwable) {}
        try { append("LAB=${com.lifecyclebot.engine.lab.LlmLabEngine.statusLine().take(300)}\n") } catch (_: Throwable) {}
        try { append("RESULTS6078=${com.lifecyclebot.engine.lab.LlmLabEngine.externalOutcomeSummary6078().take(240)}\n") } catch (_: Throwable) {}
        try { append("METACOG trades_analyzed=${com.lifecyclebot.v3.scoring.MetaCognitionAI.getTotalTradesAnalyzed()}\n") } catch (_: Throwable) {}
        try { append("WALLET sol=${"%.4f".format(if (paper) BotService.status.paperWalletSol else BotService.status.walletSol)}\n") } catch (_: Throwable) {}
    }

    private val SYSTEM_PROMPT = """
You are the SSI PILOT of an autonomous Solana meme trading engine. The human operator is your control tower; you fly the plane between checkpoints. You receive the fused state of the sentience layer (symbolic risk/confidence/mood), live lane truth (win rates and expectancy per trading lane), paused/quarantined lanes, and the strategy-lab summary.

Your job: issue ONE bounded flight directive that maximizes expectancy. Push size into proven-edge lanes, starve bleeders, and stay calm in chop. Be decisive, not timid — but respect that every number you output is clamped by hard guardrails.

Known lanes: $KNOWN_LANES

Respond ONLY with JSON:
{"sizeBias": <paper 0.45-2.10, live 0.55-1.80 global sizing multiplier>,
 "laneFocus": [<0-3 lane names to overweight>],
 "laneAvoid": [<0-3 lane names to underweight>],
 "exitPatience": <0.65-1.55, >1 = let runners breathe, <1 = bank faster>,
 "resumeLane": <lane name to un-pause, or "">,
 "note": "<one-sentence pilot rationale>"}
""".trim()

    // ── PARSE + CONTROL-TOWER GOVERNANCE ──────────────────────────────────

    private fun parseDirective(raw: String): Directive? {
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return null
        return try {
            val o = JSONObject(raw.substring(jsonStart, jsonEnd + 1))
            val paper = try { GlobalTradeRegistry.isPaperMode } catch (_: Throwable) { true }
            val bias = o.optDouble("sizeBias", 1.0).let { if (it.isFinite()) it else 1.0 }
                .coerceIn(if (paper) 0.45 else 0.55, if (paper) 2.10 else 1.80)
            val focus = readLanes(o.optJSONArray("laneFocus"))
            val avoid = readLanes(o.optJSONArray("laneAvoid")) - focus
            val patience = o.optDouble("exitPatience", 1.0).let { if (it.isFinite()) it else 1.0 }.coerceIn(0.65, 1.55)
            val note = o.optString("note", "").take(240)
            handleResumeRequest(o.optString("resumeLane", ""), paper)
            Directive(bias, focus, avoid, patience, note, System.currentTimeMillis())
        } catch (_: Throwable) { null }
    }

    private fun readLanes(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val out = HashSet<String>()
        for (i in 0 until minOf(arr.length(), 3)) {
            val l = arr.optString(i, "").trim().uppercase()
            if (l.isNotBlank() && KNOWN_LANES.contains(l)) out.add(l)
        }
        return out
    }

    /** V5.0.6090: pilot flies autonomously in PAPER and LIVE for non-safety lane pauses only. */
    private fun handleResumeRequest(laneRaw: String, paper: Boolean) {
        val lane = laneRaw.trim().uppercase()
        if (lane.isBlank() || !KNOWN_LANES.contains(lane)) return
        try {
            if (lane !in LaneAutoPauseGuard.pausedLanes()) return
            LaneAutoPauseGuard.manualResume(lane, "ssi_pilot_autonomous_${if (paper) "paper" else "live"}_6090_non_safety")
            ForensicLogger.lifecycle("SSI_PILOT_LANE_RESUMED_6090", "lane=$lane mode=${if (paper) "paper" else "live"} authority=pilot_autonomous non_safety_pause_only=true")
            PipelineHealthCollector.labelInc("SSI_PILOT_LANE_RESUMED_6090_$lane")
        } catch (_: Throwable) {}
    }

    // ── PERSISTENCE ────────────────────────────────────────────────────────

    private fun persist(d: Directive) {
        try {
            LearningPersistence.save(
                PERSIST_KEY,
                JSONObject()
                    .put("sizeBias", d.sizeBias)
                    .put("laneFocus", JSONArray(d.laneFocus.toList()))
                    .put("laneAvoid", JSONArray(d.laneAvoid.toList()))
                    .put("exitPatience", d.exitPatience)
                    .put("note", d.note)
                    .put("atMs", d.atMs)
                    .toString(),
            )
        } catch (_: Throwable) {}
    }

    private fun restore() {
        try {
            val blob = LearningPersistence.load(PERSIST_KEY) ?: return
            val o = JSONObject(blob)
            val focus = HashSet<String>(); val avoid = HashSet<String>()
            o.optJSONArray("laneFocus")?.let { for (i in 0 until it.length()) focus.add(it.optString(i)) }
            o.optJSONArray("laneAvoid")?.let { for (i in 0 until it.length()) avoid.add(it.optString(i)) }
            directive = Directive(
                sizeBias = o.optDouble("sizeBias", 1.0),
                laneFocus = focus,
                laneAvoid = avoid,
                exitPatience = o.optDouble("exitPatience", 1.0),
                note = o.optString("note", ""),
                atMs = o.optLong("atMs", 0L),
            )
        } catch (_: Throwable) {}
    }
}
