package com.lifecyclebot.engine.lab

import android.content.Context
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.GeminiCopilot
import com.lifecyclebot.engine.SentienceHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * V5.9.402 — LLM Lab orchestrator.
 *
 * The Lab is the LLM's mini-universe. It owns:
 *   • A pool of LLM-invented strategies (LabStrategy)
 *   • Its own synthetic paper bankroll (default 100 SOL)
 *   • A pending real-money approval queue (LabApproval)
 *
 * The Lab is fully wired through the AATE universe:
 *   • Reads `SentienceHooks.crossEngineBias` to size against engine sentiment.
 *   • Calls `SentienceHooks.recordEngineOutcome("LAB", …)` on every close so
 *     the universe sees Lab outcomes too.
 *   • Reads regimes from `CrossMarketRegimeAI` when available.
 *
 * Tick cadence (cheap, gated per call):
 *   • CREATION cycle:    every 4h while LLM available + < strategy cap
 *   • EVALUATION cycle:  every 30s — paper position TP/SL/timeout sweep
 *   • CULL cycle:        every 1h — archive losers, request promote on winners
 */
object LlmLabEngine {
    private const val TAG = "LlmLabEngine"

    // ── Tuning knobs ────────────────────────────────────────────────────────
    // V5.9.408 — cranked aggression: lab trades constantly so promoted ideas
    // can pass into the main universe quickly.
    private const val CREATION_INTERVAL_MS         = 12L * 60L * 1000L          // 12min steady (5/hr)
    private const val CREATION_FAST_INTERVAL_MS    = 3L * 60L * 1000L           // 3min in bootstrap (20/hr)
    private const val CREATION_FAST_WINDOW_MS      = 90L * 60L * 1000L          // first 90min after install
    private const val EVAL_INTERVAL_MS             = 10_000L                    // 10s (was 30s) → 3× the trade volume
    private const val CULL_INTERVAL_MS             = 20L * 60L * 1000L          // 20min
    private const val HEARTBEAT_INTERVAL_MS        = 60_000L                    // 60s heartbeat

    private const val MAX_LIVE_STRATEGIES   = 36       // 24 → 36 (more variety)
    private const val MAX_OPEN_POSITIONS    = 24       // 16 → 24
    private const val MAX_PER_STRATEGY_OPEN = 1

    // ── State ───────────────────────────────────────────────────────────────
    private val lastEvalMs       = AtomicLong(0)
    private val lastCullMs       = AtomicLong(0)
    private val lastHeartbeatMs  = AtomicLong(0)
    private val firstStartMs     = AtomicLong(0)
    private val autoPivotSeededAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var ctxRef: Context? = null
    private data class ExternalOutcome6078(
        val lane: String,
        val style: String,
        val pnlPct: Double,
        val pnlSol: Double,
        val trainable: Boolean,
        val accepted: Boolean,
        val paper: Boolean,
        val atMs: Long,
    )
    private val externalOutcomes6078 = ConcurrentLinkedDeque<ExternalOutcome6078>()

    fun recordExternalOutcome6078(
        lane: String,
        style: String,
        pnlPct: Double,
        pnlSol: Double,
        trainable: Boolean,
        accepted: Boolean,
        paper: Boolean,
    ) {
        externalOutcomes6078.addFirst(ExternalOutcome6078(lane.take(32), style.take(64), pnlPct, pnlSol, trainable, accepted, paper, System.currentTimeMillis()))
        while (externalOutcomes6078.size > 240) externalOutcomes6078.pollLast()
    }

    fun externalOutcomeSummary6078(): String = try {
        val rows = externalOutcomes6078.toList()
        if (rows.isEmpty()) "externalResults=0"
        else {
            val acc = rows.count { it.accepted }
            val train = rows.count { it.trainable }
            val wr = rows.count { it.pnlPct > 0.0 } * 100.0 / rows.size
            val byLane = rows.groupBy { it.lane.ifBlank { "UNKNOWN" } }.entries
                .sortedByDescending { it.value.size }
                .take(4)
                .joinToString(",") { e -> "${e.key}:${e.value.size}/${"%+.1f".format(e.value.sumOf { it.pnlPct } / e.value.size)}%" }
            "externalResults=${rows.size} accepted=$acc trainable=$train WR=${"%.0f".format(wr)}% lanes=[$byLane]"
        }
    } catch (_: Throwable) { "externalResults=unavailable" }

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────
    fun start(context: Context) {
        if (ctxRef != null) return
        ctxRef = context.applicationContext
        LlmLabStore.init(context)
        firstStartMs.set(System.currentTimeMillis())
        ErrorLogger.info(TAG, "🧪 LlmLab started — ${LlmLabStore.summary()}")
        // Seed a default strategy so the lab isn't empty on first run.
        seedDefaultsIfEmpty()
    }

    /** V5.0.6107 — Lab paper must train live-compounding economics too. */
    private fun economicLabSizingSol(fraction: Double = 0.08): Double {
        val bankroll = try { LlmLabStore.getPaperBalance() } catch (_: Throwable) { LlmLabStore.DEFAULT_PAPER_BALANCE_SOL }
        return (bankroll.coerceAtLeast(LlmLabStore.DEFAULT_PAPER_BALANCE_SOL) * fraction.coerceIn(0.03, 0.25)).coerceIn(1.0, 20.0)
    }

    /** Called periodically from BotService.botLoop. Gated; cheap. */
    fun tick(universeFeed: () -> List<LabUniverseTick>) {
        if (!LlmLabStore.isEnabled()) return
        val now = System.currentTimeMillis()

        // Heartbeat — proves the Lab is alive in the logs even if it's not
        // firing trades yet.
        if (now - lastHeartbeatMs.get() >= HEARTBEAT_INTERVAL_MS) {
            lastHeartbeatMs.set(now)
            ErrorLogger.info(TAG, "🧪 heartbeat — ${LlmLabStore.summary()}")
        }

        // 1. CREATION — fast cadence (10min) during the first hour after start,
        //    steady (4h) afterward. Async, never blocks the loop.
        val sinceStart = now - firstStartMs.get()
        val creationGap = if (sinceStart < CREATION_FAST_WINDOW_MS) CREATION_FAST_INTERVAL_MS else CREATION_INTERVAL_MS
        if (now - LlmLabStore.getLastCreationMs() >= creationGap) {
            LlmLabStore.markCreated()  // mark pre-emptively to avoid re-entry on parallel ticks
            GlobalScope.launch(Dispatchers.IO) { runCatching { runCreationCycle() } }
        }

        // 2. EVALUATION — every 30s, in-line (no LLM call, just price math).
        if (now - lastEvalMs.get() >= EVAL_INTERVAL_MS) {
            lastEvalMs.set(now)
            runCatching { runEvaluationCycle(universeFeed()) }
        }

        // 3. CULL + promotion proposals — every 1h.
        if (now - lastCullMs.get() >= CULL_INTERVAL_MS) {
            lastCullMs.set(now)
            runCatching { runCullCycle() }
        }

        // V5.0.6120g — SWARM LAB WINNER CONSUMER (Tier D). Every 30 min, pull
        // the swarm's promoted lab strategies from other AATE instances via
        // CollectiveLearning.getSwarmLabWinners and seed any we haven't seen
        // before into our local strategy pool. 8× LLM invention throughput
        // — while our Lab is inventing 1 strategy per creation gap, the 7
        // other instances are inventing theirs and cross-pollinating.
        // Bounded (max 4 seeds per pull), rate-limited (30 min between pulls),
        // deduped by strategy name, fail-open on any Turso error.
        if (now - lastSwarmSeedMs.get() >= SWARM_SEED_INTERVAL_MS) {
            lastSwarmSeedMs.set(now)
            GlobalScope.launch(Dispatchers.IO) {
                runCatching { runSwarmSeedCycle() }
            }
        }
    }

    private val lastSwarmSeedMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val SWARM_SEED_INTERVAL_MS = 30L * 60L * 1000L
    private val seenSwarmStrategyNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private suspend fun runSwarmSeedCycle() {
        val winners = try {
            com.lifecyclebot.collective.CollectiveLearning.getSwarmLabWinners(24L * 60L * 60L * 1000L)
        } catch (_: Throwable) { emptyList() }
        if (winners.isEmpty()) return

        var seeded = 0
        for (row in winners) {
            if (seeded >= 4) break
            try {
                val name = (row["symbol"] as? String)?.trim().orEmpty()
                if (name.isBlank()) continue
                if (seenSwarmStrategyNames.contains(name)) continue
                val wr = (row["score"] as? Number)?.toDouble() ?: 0.0
                val avgPnl = (row["price"] as? Number)?.toDouble() ?: 0.0
                // Only take clearly winning swarm strategies. Weak seeds
                // just pollute our pool.
                if (wr < 45.0 || avgPnl < 15.0) continue
                seenSwarmStrategyNames.add(name)
                // Build a synthetic seed strategy from the swarm hint. The
                // Lab's own EVAL cycle will decide within days whether to
                // promote or cull it — this just puts it in the arena.
                val seed = LabStrategy(
                    id = LlmLabStore.newStrategyId(),
                    name = "swarm/$name".take(64),
                    rationale = "seeded from hive swarm — wr=${wr.toInt()}% avgPnl=${avgPnl.toInt()}% n_swarm_verified",
                    asset = LabAssetClass.MEME,
                    entryScoreMin = 55,
                    entryRegime = "ANY",
                    takeProfitPct = 40.0,
                    stopLossPct = -12.0,
                    maxHoldMins = 45,
                    sizingSol = economicLabSizingSol(0.08),
                    status = LabStrategyStatus.DRAFT,
                )
                LlmLabStore.addStrategy(seed)
                seeded += 1
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SWARM_LAB_SEED_ACCEPTED_6120g") } catch (_: Throwable) {}
            } catch (_: Throwable) { /* skip malformed row */ }
        }
        if (seeded > 0) {
            ErrorLogger.info(TAG, "🐝🧪 Swarm seeded $seeded new strategies from hive")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tick payload from BotService — the universe-wide view the lab sees.
    // ────────────────────────────────────────────────────────────────────────
    data class LabUniverseTick(
        val symbol: String,
        val mint: String,
        val asset: LabAssetClass,
        val price: Double,
        val score: Int,        // 0..100 — composite AATE score for the symbol
        val regime: String,    // "BULL" | "BEAR" | "CHOP" | "ANY"
    )

    // ────────────────────────────────────────────────────────────────────────
    // 1. Creation cycle
    // ────────────────────────────────────────────────────────────────────────
    private fun seedDefaultsIfEmpty() {
        if (LlmLabStore.allStrategies().isNotEmpty()) return
        // V5.9.405 — seed THREE distinct personalities so the lab feels alive
        // from the first second instead of just one Genesis. Each has a
        // different risk/reward archetype to give the LLM diverse parents
        // to evolve from.
        val seeds = listOf(
            LabStrategy(
                id = LlmLabStore.newStrategyId(),
                name = "Genesis · Hunter",
                rationale = "Aggressive momentum hunter — high TP, tight SL, fast hold. Catches launches.",
                asset = LabAssetClass.MEME,
                entryScoreMin = 55,  entryRegime = "ANY",
                takeProfitPct = 25.0, stopLossPct = -8.0, maxHoldMins = 60,
                sizingSol = economicLabSizingSol(0.06),   generation = 1, status = LabStrategyStatus.ACTIVE,
            ),
            LabStrategy(
                id = LlmLabStore.newStrategyId(),
                name = "Genesis · Sniper",
                rationale = "Quality-gated sniper — only top-30% scores, asymmetric R/R, lets winners ride.",
                asset = LabAssetClass.ANY,
                entryScoreMin = 70,  entryRegime = "ANY",
                takeProfitPct = 50.0, stopLossPct = -10.0, maxHoldMins = 180,
                sizingSol = economicLabSizingSol(0.08),   generation = 1, status = LabStrategyStatus.ACTIVE,
            ),
            LabStrategy(
                id = LlmLabStore.newStrategyId(),
                name = "Genesis · Scalper",
                rationale = "Quick scalper — lower scores, tiny targets, churn for high frequency wins.",
                asset = LabAssetClass.ANY,
                entryScoreMin = 50,  entryRegime = "ANY",
                takeProfitPct = 8.0, stopLossPct = -5.0, maxHoldMins = 30,
                sizingSol = economicLabSizingSol(0.10),   generation = 1, status = LabStrategyStatus.ACTIVE,
            ),
        )
        seeds.forEach { LlmLabStore.addStrategy(it) }
        ErrorLogger.info(TAG, "🧪 SEEDED ${seeds.size} genesis strategies — Hunter, Sniper, Scalper")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Manual user actions (exposed via UI)
    // ────────────────────────────────────────────────────────────────────────

    /** Force an immediate creation cycle — ignores the rate limit. */
    fun forceSpawn() {
        ErrorLogger.info(TAG, "🧪 FORCE SPAWN requested by user")
        LlmLabStore.markCreated()
        GlobalScope.launch(Dispatchers.IO) { runCatching { runCreationCycle() } }
    }

    /** Mutate the top paper performer — birth a child strategy with tweaked params. */
    fun mutateBest() {
        val parent = LlmLabStore.allStrategies()
            .filter { it.paperTrades >= 5 }
            .maxByOrNull { it.paperPnlSol } ?: run {
                ErrorLogger.info(TAG, "🧪 MUTATE skipped — no parent with ≥5 trades")
                return
            }
        // 80%-of-parent params with random nudges (+/- 20%)
        fun jitter(d: Double, pct: Double = 0.20): Double {
            val delta = d * pct * (Math.random() * 2 - 1)
            return d + delta
        }
        val child = LabStrategy(
            id = LlmLabStore.newStrategyId(),
            name = parent.name.replaceFirst(Regex("\\s*·\\s*Mut\\d+$"), "") + " · Mut${parent.generation}",
            rationale = "Mutation of ${parent.name}: jittered TP/SL/hold to explore the local optimum.",
            asset = parent.asset,
            entryScoreMin = (parent.entryScoreMin + (-5..5).random()).coerceIn(40, 95),
            entryRegime = parent.entryRegime,
            takeProfitPct = jitter(parent.takeProfitPct).coerceIn(3.0, 100.0),
            stopLossPct = jitter(parent.stopLossPct).coerceIn(-50.0, -2.0),
            maxHoldMins = jitter(parent.maxHoldMins.toDouble()).toInt().coerceIn(15, 480),
            sizingSol = jitter(maxOf(parent.sizingSol, economicLabSizingSol(0.05))).coerceIn(economicLabSizingSol(0.05), economicLabSizingSol(0.20)),
            parentId = parent.id,
            generation = parent.generation + 1,
            status = LabStrategyStatus.ACTIVE,
        )
        LlmLabStore.addStrategy(child)
        ErrorLogger.info(TAG, "🧪 MUTATED ${parent.name} → ${child.name}")
    }

    /**
     * V5.0.3822 — AUTONOMOUS_LAB_PIVOT_SEED.
     *
     * Safe AI/autonomy upgrade: when TacticSwitcher proves a tactic has failed,
     * the lab gets a paper-only ACTIVE strategy derived from that lane/bucket/tactic.
     * This is NOT an LLM hot-path call, NOT live trading, NOT a lane disable, and
     * NOT a promotion. The lab may paper-test the idea inside its sandbox. Only if
     * normal lab proof later promotes it does LabPromotedFeed expose a read-only
     * nudge to the normal operating loop; the lab never directly controls trading.
     */
    fun seedFromTacticFailure(lane: String, scoreBand: String, failedTactic: String, nextTactic: String, reason: String) {
        try {
            if (!LlmLabStore.isEnabled()) return
            val key = "${lane.uppercase().take(18)}|${scoreBand.uppercase().take(8)}|${nextTactic.uppercase().take(16)}"
            val now = System.currentTimeMillis()
            val last = autoPivotSeededAt[key] ?: 0L
            if (now - last < 20L * 60_000L) return
            if (LlmLabStore.activeStrategies().size >= MAX_LIVE_STRATEGIES) return
            autoPivotSeededAt[key] = now

            fun midScore(b: String): Int = when (b.uppercase()) {
                "S0-10" -> 8; "S11-25" -> 18; "S26-40" -> 33; "S41-60" -> 50; "S61+" -> 70; else -> 45
            }
            val tactic = nextTactic.uppercase()
            val params = when {
                tactic.contains("PULLBACK") -> Triple(12.0, -5.0, 45)
                tactic.contains("REACCUM") -> Triple(18.0, -7.0, 90)
                tactic.contains("BREAKOUT") -> Triple(30.0, -9.0, 150)
                tactic.contains("LAB") -> Triple(22.0, -6.0, 90)
                else -> Triple(10.0, -5.0, 35)
            }
            val asset = when {
                lane.contains("MOON", true) || lane.contains("SHIT", true) || lane.contains("SNIPER", true) || lane.contains("MANIP", true) -> LabAssetClass.MEME
                lane.contains("BLUE", true) || lane.contains("QUALITY", true) -> LabAssetClass.ALT
                else -> LabAssetClass.ANY
            }
            val strategy = LabStrategy(
                id = LlmLabStore.newStrategyId(),
                name = "AutoPivot · ${lane.uppercase().take(10)} ${scoreBand.take(6)} ${nextTactic.take(6)}",
                rationale = "Auto-seeded after tactic failure: $lane/$scoreBand $failedTactic→$nextTactic ($reason). ACTIVE lab paper experiment only; not promoted/live-authorized.",
                asset = asset,
                entryScoreMin = midScore(scoreBand).coerceIn(8, 95),
                entryRegime = "ANY",
                takeProfitPct = params.first,
                stopLossPct = params.second,
                maxHoldMins = params.third,
                sizingSol = economicLabSizingSol(0.08),
                parentId = null,
                generation = 1,
                status = LabStrategyStatus.ACTIVE,
            )
            LlmLabStore.addStrategy(strategy)
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LAB_AUTOPIVOT_STRATEGY_SEEDED") } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.SentienceOrchestrator.noteRuntimeEvent("LAB_AUTOPIVOT_SEED", strategy.rationale, "INFO") } catch (_: Throwable) {}
            ErrorLogger.info(TAG, "🧪 AUTOPIVOT seeded ${strategy.name}: ${strategy.rationale.take(120)}")
        } catch (e: Throwable) {
            ErrorLogger.debug(TAG, "autopivot seed skipped: ${e.message}")
        }
    }

    /** Permanently delete all archived strategies. */
    fun purgeArchived(): Int {
        val archived = LlmLabStore.allStrategies().filter { it.status == LabStrategyStatus.ARCHIVED }
        archived.forEach { LlmLabStore.removeStrategy(it.id) }
        if (archived.isNotEmpty()) ErrorLogger.info(TAG, "🧪 PURGED ${archived.size} archived strategies")
        return archived.size
    }

    /** Top-up the lab paper bankroll by N SOL (paper-only — costs nothing). */
    fun topUpBankroll(amountSol: Double) {
        LlmLabStore.adjustPaperBalance(amountSol)
        ErrorLogger.info(TAG, "🧪 TOPUP +${amountSol}◎ paper bankroll")
    }

    private fun runCreationCycle() {
        val live = LlmLabStore.activeStrategies().size
        if (live >= MAX_LIVE_STRATEGIES) {
            ErrorLogger.info(TAG, "🧪 Creation skipped — at cap ($live/$MAX_LIVE_STRATEGIES)")
            return
        }
        if (!GeminiCopilot.isConfigured() || GeminiCopilot.isAIDegraded()) {
            ErrorLogger.info(TAG, "🧪 Creation skipped — LLM unavailable")
            return
        }

        // V5.0.6120h — LANE-RECOVERY BIAS. Drain any pending recovery hints
        // from Green-EV Governor. If a lane is paused because its live EV
        // has bled below -3%, focus the LLM prompt on inventing a strategy
        // for THAT lane specifically. Once we successfully paper-promote a
        // strategy for the paused lane, the Governor sees the recovery-win
        // streak clear and re-enables live trading on the lane. Fully
        // autonomous recovery loop. Non-blocking: if the queue is empty,
        // fall through to the standard prompt.
        val recoveryHints = try { com.lifecyclebot.engine.LabRecoveryHintQueue.drainAll() } catch (_: Throwable) { emptyList() }
        val recoveryTarget = recoveryHints.firstOrNull()
        val laneBias = if (recoveryTarget != null) {
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "LAB_LANE_RECOVERY_BIAS_6120h",
                    "lane=${recoveryTarget.lane} ev=${"%.2f".format(recoveryTarget.evPct)}% wr=${"%.2f".format(recoveryTarget.wr * 100)}% — biasing next lab invention to recover this lane",
                )
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LAB_LANE_RECOVERY_BIAS_6120h")
            } catch (_: Throwable) {}
            """

═══ URGENT LANE RECOVERY BIAS ═══
The Green-EV Governor has PAUSED lane "${recoveryTarget.lane}" due to
persistent negative EV (${"%.2f".format(recoveryTarget.evPct)}% over
${recoveryTarget.n} live trades, WR ${"%.0f".format(recoveryTarget.wr * 100)}%).

Your invented strategy MUST target this lane. Prefer tighter stops
(SL between -6% and -10%), faster take-profit (TP between 12% and 25%),
shorter hold (30-90 minutes), and only fire on the strongest score
signals (entryScoreMin >= 70). This is the specific bleeder pattern
the swarm needs solved. Name the strategy "recover/${recoveryTarget.lane}_gen_${System.currentTimeMillis().toString().takeLast(6)}".
"""
        } else ""

        val recent = LlmLabStore.allStrategies()
            .sortedByDescending { it.lastEvaluatedAt }
            .take(8)
            .joinToString("\n") { s ->
                "- ${s.name} (${s.asset}, gen ${s.generation}) trades=${s.paperTrades} wr=${"%.0f".format(s.winRatePct())}% pnl=${"%.2f".format(s.paperPnlSol)}◎ status=${s.status}"
            }

        val prompt = """
You are the AATE Lab strategist. Invent ONE new paper-trading strategy for the
lab to try, distinct from the existing list below. Output STRICT JSON only.

EXISTING STRATEGIES:
$recent
$laneBias
OUTPUT JSON FIELDS (all required):
  name           short tag, <= 32 chars
  rationale      one sentence, <= 120 chars
  asset          one of: MEME, ALT, MARKETS, STOCK, FOREX, METAL, COMMODITY, ANY
  entryScoreMin  integer 50..95
  entryRegime    one of: ANY, BULL, BEAR, CHOP
  takeProfitPct  number 3..50
  stopLossPct    number -3..-30 (negative)
  maxHoldMins    integer 15..480
  sizingSol      number 1.0..20.0, preferably 5%-20% of the current lab bankroll; no micro/toy sizing

Reply with just the JSON object, nothing else.
        """.trimIndent()

        val raw = try {
            GeminiCopilot.rawText(
                userPrompt = prompt,
                systemPrompt = "You are a quantitative strategist. Reply with strict JSON only.",
                temperature = 0.9,
                maxTokens = 400
            )
        } catch (e: Throwable) {
            ErrorLogger.warn(TAG, "Creation LLM call failed: ${e.message}")
            null
        } ?: return

        val parsed = parseStrategyJson(raw) ?: run {
            ErrorLogger.warn(TAG, "🧪 Strategy parse failed; reply: ${raw.take(160)}")
            return
        }

        // Pick a parent (best paper performer if any) for lineage.
        val parent = LlmLabStore.allStrategies()
            .filter { it.paperTrades >= 5 }
            .maxByOrNull { it.paperPnlSol }
        val gen = (parent?.generation ?: 0) + 1

        val s = parsed.copy(
            id = LlmLabStore.newStrategyId(),
            parentId = parent?.id,
            generation = gen,
            status = LabStrategyStatus.ACTIVE,
        )
        LlmLabStore.addStrategy(s)
        ErrorLogger.info(TAG, "🧪 LLM created strategy: ${s.name} (${s.asset}, gen=$gen, parent=${parent?.name ?: "none"})")
    }

    /**
     * Lenient JSON parser — strips fences, extracts the first {...} block.
     */
    private fun parseStrategyJson(reply: String): LabStrategy? {
        val cleaned = reply.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end   = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(cleaned.substring(start, end + 1))
            LabStrategy(
                id = "",   // filled by caller
                name = o.optString("name").ifBlank { return null }.take(32),
                rationale = o.optString("rationale").take(140),
                asset = LabAssetClass.parse(o.optString("asset")),
                entryScoreMin = o.optInt("entryScoreMin", 70).coerceIn(40, 99),
                entryRegime = o.optString("entryRegime", "ANY").uppercase(),
                takeProfitPct = o.optDouble("takeProfitPct", 10.0).coerceIn(2.0, 100.0),
                stopLossPct = o.optDouble("stopLossPct", -8.0).coerceIn(-50.0, -2.0),
                maxHoldMins = o.optInt("maxHoldMins", 90).coerceIn(10, 720),
                sizingSol = o.optDouble("sizingSol", economicLabSizingSol(0.08)).coerceIn(economicLabSizingSol(0.05), economicLabSizingSol(0.20)),
                generation = 1,
                status = LabStrategyStatus.ACTIVE,
            )
        } catch (e: Throwable) {
            ErrorLogger.warn(TAG, "JSON parse error: ${e.message}")
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. Evaluation cycle — fast, no LLM
    // ────────────────────────────────────────────────────────────────────────
    private fun runEvaluationCycle(universe: List<LabUniverseTick>) {
        if (universe.isEmpty()) return

        // Update existing positions (TP/SL/timeout sweep).
        // V5.9.733 — STRICT MINT MATCH. Operator journal showed COPIUM
        // logging +53,936% with LAB_TAKE_PROFIT and PBTC +13,626% — both
        // Pump.fun-style names where MULTIPLE distinct mints share the
        // same ticker. Previous logic fell back to symbol match if the
        // mint didn't appear in the universe, which silently swapped in
        // a different token's price and produced phantom 500x prints.
        // Mint is the only safe key — symbol fallback removed entirely.
        // If the position's mint isn't in this universe tick batch, just
        // skip; we'll catch it on the next cycle.
        val openPositions = LlmLabStore.allPositions()
        for (pos in openPositions) {
            val tick = universe.firstOrNull { it.mint == pos.mint }
            val live = tick?.price ?: continue
            LlmLabTrader.checkExit(pos, live)
        }

        // Maybe open a new position for ACTIVE strategies that don't have one.
        if (LlmLabStore.allPositions().size >= MAX_OPEN_POSITIONS) return
        val active = LlmLabStore.activeStrategies()
        if (active.isEmpty()) return

        for (s in active) {
            val openForStrategy = LlmLabStore.positionsForStrategy(s.id).size
            if (openForStrategy >= MAX_PER_STRATEGY_OPEN) continue

            val candidate = pickCandidate(s, universe) ?: continue
            // Bias size by symbiosis cross-engine multiplier.
            val biasMult = try {
                SentienceHooks.crossEngineBias(when (s.asset) {
                    LabAssetClass.ALT -> "ALTS"
                    LabAssetClass.MEME -> "MEME"
                    else -> "MARKETS"
                })
            } catch (_: Throwable) { 1.0 }

            val strategyEconomicSize6107 = maxOf(s.sizingSol, economicLabSizingSol(0.05))
            val sized = (strategyEconomicSize6107 * biasMult).coerceIn(strategyEconomicSize6107 * 0.5, strategyEconomicSize6107 * 1.5)
            if (sized > LlmLabStore.getPaperBalance()) continue
            LlmLabTrader.openPaper(s, candidate, sized)
        }
    }

    private fun pickCandidate(s: LabStrategy, universe: List<LabUniverseTick>): LabUniverseTick? {
        val pool = universe.filter { tick ->
            (s.asset == LabAssetClass.ANY || tick.asset == s.asset) &&
            tick.score >= s.entryScoreMin &&
            (s.entryRegime == "ANY" || tick.regime == s.entryRegime) &&
            tick.price > 0.0 &&
            LlmLabStore.openPosition(s.id, tick.mint) == null
        }
        if (pool.isEmpty()) return null
        return pool.maxByOrNull { it.score }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. Cull + AUTO-PROMOTION (proven strategies graduate into the live flow)
    // ────────────────────────────────────────────────────────────────────────
    private fun runCullCycle() {
        for (s in LlmLabStore.allStrategies()) {
            if (s.status == LabStrategyStatus.ARCHIVED) continue

            // Loser cull
            if (s.paperTrades >= LlmLabStore.ARCHIVE_LOSER_AFTER_TRADES &&
                s.winRatePct() < LlmLabStore.ARCHIVE_LOSER_BELOW_WR_PCT) {
                LlmLabStore.archiveStrategy(s.id, "WR ${"%.0f".format(s.winRatePct())}% < ${LlmLabStore.ARCHIVE_LOSER_BELOW_WR_PCT}% after ${s.paperTrades} trades")
                continue
            }

            // AUTO-PROMOTION — once a strategy proves itself in paper, it
            // graduates into PROMOTED status and starts nudging the live
            // engine via LabPromotedFeed. No user approval needed for paper
            // proving → live influence; the real-money guardrail still kicks
            // in inside Executor.doBuy via LabPromotedFeed.requireLiveApproval.
            if (s.status == LabStrategyStatus.ACTIVE &&
                s.paperTrades >= LlmLabStore.MIN_TRADES_BEFORE_PROMOTION &&
                s.winRatePct() >= LlmLabStore.MIN_WR_FOR_PROMOTION_PCT &&
                s.paperPnlSol >= LlmLabStore.MIN_PAPER_PNL_SOL_FOR_PROMOTION
            ) {
                LlmLabStore.updateStrategy(s.copy(status = LabStrategyStatus.PROMOTED))
                try { markLanePolicyRecoveredByLab6107(s) } catch (_: Throwable) {}
                ErrorLogger.info(TAG, "🧪 AUTO-PROMOTED ${s.name} → live influence " +
                    "(${s.paperTrades} trades · WR ${"%.0f".format(s.winRatePct())}% · " +
                    "PnL ${"%+.3f".format(s.paperPnlSol)}◎)")
            }
        }
    }

    private fun markLanePolicyRecoveredByLab6107(s: LabStrategy) {
        // AutoPivot names are emitted as: "AutoPivot · LANE SCORE TACTIC".
        // When one proves itself, mark the original lane/bucket as improved so
        // LanePolicy can climb out of RETRAINING and reintroduce execution.
        val raw = s.name.removePrefix("AutoPivot ·").trim()
        if (raw.isBlank()) return
        val parts = raw.split(" ").filter { it.isNotBlank() }
        val lane = parts.getOrNull(0)?.trim()?.uppercase() ?: return
        val scoreBand = parts.getOrNull(1)?.trim()?.uppercase()?.takeIf { it.startsWith("S") } ?: "S41-60"
        com.lifecyclebot.engine.learning.LanePolicy.noteImprovement(lane, scoreBand)
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LAB_POLICY_REINTRODUCTION_6107") } catch (_: Throwable) {}
        ErrorLogger.info(TAG, "🧪 LAB_POLICY_REINTRODUCTION_6107 lane=$lane band=$scoreBand via ${s.name}")
    }

    // ────────────────────────────────────────────────────────────────────────
    // User decisions on approvals
    // ────────────────────────────────────────────────────────────────────────
    fun approveApproval(approvalId: String) {
        val a = LlmLabStore.allApprovals().firstOrNull { it.id == approvalId } ?: return
        if (a.status != LabApprovalStatus.PENDING) return
        LlmLabStore.decideApproval(approvalId, LabApprovalStatus.APPROVED)
        when (a.kind) {
            LabApprovalKind.PROMOTE_TO_LIVE -> {
                val s = LlmLabStore.getStrategy(a.strategyId ?: "") ?: return
                // Strategy already auto-promoted to PROMOTED; this approval grants
                // live (real-money) spend authority via LabPromotedFeed.
                LabPromotedFeed.grantLiveAuthority(s.id)
                ErrorLogger.info(TAG, "🧪 LIVE SPEND AUTHORISED for ${s.name}")
            }
            LabApprovalKind.SINGLE_LIVE_TRADE -> {
                ErrorLogger.info(TAG, "🧪 SINGLE LIVE TRADE approved (id=$approvalId) — strategy=${a.strategyId} symbol=${a.symbol}")
                a.strategyId?.let { LabPromotedFeed.grantLiveAuthority(it) }
            }
            LabApprovalKind.TRANSFER_TO_MAIN_PAPER -> {
                if (LlmLabStore.getPaperBalance() >= a.amountSol) {
                    LlmLabStore.adjustPaperBalance(-a.amountSol)
                    try {
                        com.lifecyclebot.engine.BotService.status.paperWalletSol += a.amountSol
                    } catch (_: Throwable) {}
                    ErrorLogger.info(TAG, "🧪 TRANSFER ${a.amountSol}◎ Lab paper → main paper wallet")
                }
            }
        }
    }

    fun denyApproval(approvalId: String) {
        LlmLabStore.decideApproval(approvalId, LabApprovalStatus.DENIED)
    }

    /**
     * Manual entry point for the LLM (or chat layer) to request a real-money
     * trade on a specific symbol. Always queued for user approval — never
     * executes immediately.
     */
    fun requestSingleLiveTrade(strategyId: String, symbol: String, amountSol: Double, reason: String) {
        LlmLabStore.addApproval(LabApproval(
            id = LlmLabStore.newApprovalId(),
            kind = LabApprovalKind.SINGLE_LIVE_TRADE,
            strategyId = strategyId,
            symbol = symbol,
            amountSol = amountSol,
            reason = reason.take(200),
        ))
    }

    /** LLM-initiated transfer of lab paper SOL into the main paper wallet. */
    fun requestTransferToMainPaper(amountSol: Double, reason: String) {
        LlmLabStore.addApproval(LabApproval(
            id = LlmLabStore.newApprovalId(),
            kind = LabApprovalKind.TRANSFER_TO_MAIN_PAPER,
            strategyId = null, symbol = null,
            amountSol = max(0.0, amountSol),
            reason = reason.take(200),
        ))
    }

    fun statusLine(): String = LlmLabStore.summary() + " | " + externalOutcomeSummary6078()
}
