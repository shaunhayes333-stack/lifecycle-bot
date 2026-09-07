from pathlib import Path
import re

ROOT = Path("lifecycle_apk")
MAIN = ROOT / "app/src/main/kotlin/com/lifecyclebot"
TEST = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine"


def read(path: Path) -> str:
    return path.read_text()


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"6684 anchor missing: {label}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"6684 start anchor missing: {label}")
    b = text.find(end, a + len(start))
    if b < 0:
        raise SystemExit(f"6684 end anchor missing: {label}")
    return text[:a] + replacement + text[b:]


# ---------------------------------------------------------------------------
# 1) SINGLE ADAPTIVE RUNTIME AUTHORITY
# ---------------------------------------------------------------------------
runtime_path = MAIN / "engine/AdaptiveIntelligenceRuntime6684.kt"
runtime_src = r'''package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.engine.lab.LabAssetClass
import com.lifecyclebot.engine.lab.LlmLabEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6684 — SINGLE ADAPTIVE INTELLIGENCE RUNTIME AUTHORITY.
 *
 * Source repair for the long-running control-plane loops that were originally
 * wired directly into BotService and were later silently amputated while the
 * giant service was split/refactored.  Intelligence objects existing in source
 * is not sufficient: Lab invention, sentience reflection, SSI pilot directives,
 * bleeder re-proof and auto-tuning must have one durable lifecycle owner.
 *
 * This runtime NEVER submits an order.  It owns background learning/research
 * cadence only. Executor/FDG/canonical ledgers and hard safety remain sovereign.
 */
object AdaptiveIntelligenceRuntime6684 {
    const val VERSION = "V5.0.6684_ADAPTIVE_INTELLIGENCE_RUNTIME"

    private val started = AtomicBoolean(false)
    private val loopCount = AtomicLong(0L)
    private val lastScoutMs = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext

        // Restore the ORIGINAL source-of-creation lifecycle edges.
        try { LlmLabEngine.start(app) } catch (t: Throwable) {
            ErrorLogger.warn("AdaptiveRuntime6684", "LLM Lab start failed: ${t.message}")
        }
        try { SentienceOrchestrator.start(app) } catch (t: Throwable) {
            ErrorLogger.warn("AdaptiveRuntime6684", "Sentience start failed: ${t.message}")
        }
        try { SsiPilotCouncil.start() } catch (t: Throwable) {
            ErrorLogger.warn("AdaptiveRuntime6684", "SSI pilot start failed: ${t.message}")
        }

        scope.launch {
            ErrorLogger.info("AdaptiveRuntime6684", "🧠 $VERSION started")
            while (isActive) {
                loopCount.incrementAndGet()

                // Lab creation/evaluation/cull is internally cadence-gated.
                try { LlmLabEngine.tick { buildUniverse() } } catch (t: Throwable) {
                    try { PipelineHealthCollector.labelInc("ADAPTIVE_LAB_TICK_ERROR_6684") } catch (_: Throwable) {}
                }

                // One quarantine/re-proof authority. Both functions self-throttle.
                try { LaneAutoPauseGuard.evaluateLive() } catch (_: Throwable) {}
                try { LaneShadowProofLoop.evaluate() } catch (_: Throwable) {}

                // Re-proof scout is intentionally slower than the 10s Lab evaluator.
                val now = System.currentTimeMillis()
                val lastScout = lastScoutMs.get()
                if (now - lastScout >= 60_000L && lastScoutMs.compareAndSet(lastScout, now)) {
                    try { ChronicBleederScout.tick() } catch (_: Throwable) {}
                }

                // Uses canonical-outcome count + its own 5m gate; safe to call often.
                try { SentienceHooks.maybeAutoTune(app) } catch (_: Throwable) {}

                delay(10_000L)
            }
        }
    }

    private fun buildUniverse(): List<LlmLabEngine.LabUniverseTick> {
        val out = ArrayList<LlmLabEngine.LabUniverseTick>()
        val regime = try {
            when (com.lifecyclebot.v4.meta.CrossMarketRegimeAI.assessRegime().mode) {
                com.lifecyclebot.v4.meta.GlobalRiskMode.RISK_ON,
                com.lifecyclebot.v4.meta.GlobalRiskMode.TRENDING -> "BULL"
                com.lifecyclebot.v4.meta.GlobalRiskMode.RISK_OFF -> "BEAR"
                else -> "CHOP"
            }
        } catch (_: Throwable) { "ANY" }

        val tokens = try {
            synchronized(BotService.status.tokens) { BotService.status.tokens.values.toList() }
        } catch (_: Throwable) { emptyList() }
        tokens.forEach { ts ->
            val price = ts.lastPrice.takeIf { it > 0.0 } ?: ts.history.lastOrNull()?.priceUsd ?: 0.0
            if (price > 0.0) {
                out += LlmLabEngine.LabUniverseTick(
                    symbol = ts.symbol.ifBlank { ts.mint.take(8) },
                    mint = ts.mint,
                    asset = LabAssetClass.MEME,
                    price = price,
                    score = ts.entryScore.toInt().coerceIn(0, 100),
                    regime = regime,
                )
            }
        }

        fun push(symbol: String, mint: String, asset: LabAssetClass, price: Double) {
            if (symbol.isBlank() || price <= 0.0) return
            out += LlmLabEngine.LabUniverseTick(symbol, mint, asset, price, 50, regime)
        }
        try { com.lifecyclebot.perps.CryptoAltTrader.getOpenPositions().forEach { p -> push(p.market.symbol, p.market.symbol, LabAssetClass.ALT, p.currentPrice) } } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.TokenizedStockTrader.getActivePositions().forEach { p -> push(p.market.symbol, p.market.symbol, LabAssetClass.STOCK, p.currentPrice) } } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.PerpsTraderAI.getActivePositions().forEach { p -> push(p.market.symbol, p.market.symbol, LabAssetClass.MARKETS, p.currentPrice) } } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.ForexTrader.getAllPositions().forEach { p -> push(p.market.symbol, p.market.symbol, LabAssetClass.FOREX, p.currentPrice) } } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.MetalsTrader.getAllPositions().forEach { p -> push(p.market.symbol, p.market.symbol, LabAssetClass.METAL, p.currentPrice) } } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.CommoditiesTrader.getAllPositions().forEach { p -> push(p.market.symbol, p.market.symbol, LabAssetClass.COMMODITY, p.currentPrice) } } catch (_: Throwable) {}
        return out
    }

    fun statusLine(): String = "$VERSION started=${started.get()} loops=${loopCount.get()} lab=${try { LlmLabEngine.statusLine().take(180) } catch (_: Throwable) { "unavailable" }}"
}
'''
write(runtime_path, runtime_src)

# Start the adaptive runtime once at application lifecycle source, outside the
# enormous BotService refactor surface.
app_path = MAIN / "AATEApp.kt"
app = read(app_path)
app_anchor = '''        // V5.9.433 — restore TreasuryManager here too, so the 70/30 splits'''
app_insert = '''        // V5.0.6684 — restore the adaptive control plane at the process lifecycle\n        // source instead of depending on fragile BotService tail patches. start() is\n        // non-blocking/idempotent; its work runs on a dedicated IO supervisor.\n        try {\n            com.lifecyclebot.engine.AdaptiveIntelligenceRuntime6684.start(applicationContext)\n            ErrorLogger.info("App", "AdaptiveIntelligenceRuntime6684 started")\n        } catch (e: Throwable) {\n            ErrorLogger.warn("App", "AdaptiveIntelligenceRuntime6684 start failed: ${e.message}")\n        }\n\n'''
app = replace_once(app, app_anchor, app_insert + app_anchor, "AATEApp adaptive runtime start")
write(app_path, app)

# ---------------------------------------------------------------------------
# 2) CAUSAL LAB STRATEGY IDENTITY + ONE PROOF CONTRACT
# ---------------------------------------------------------------------------
models_path = MAIN / "engine/lab/LlmLabModels.kt"
models = read(models_path)
models = replace_once(
    models,
    '''    var lastTradeAt: Long = 0L,\n    var lastEvaluatedAt: Long = 0L,\n) {''',
    '''    var lastTradeAt: Long = 0L,\n    var lastEvaluatedAt: Long = 0L,\n    // V5.0.6684 — causal re-proof identity. Generic Lab inventions leave these blank;\n    // failed-lane replacements MUST carry exact ownership instead of relying on names.\n    val targetLane: String = "",\n    val targetScoreBand: String = "",\n    val provenance: String = "GENERIC",\n) {''',
    "LabStrategy causal metadata",
)
models = replace_once(
    models,
    '''        put("createdAt", createdAt); put("lastTradeAt", lastTradeAt); put("lastEvaluatedAt", lastEvaluatedAt)''',
    '''        put("createdAt", createdAt); put("lastTradeAt", lastTradeAt); put("lastEvaluatedAt", lastEvaluatedAt)\n        put("targetLane", targetLane); put("targetScoreBand", targetScoreBand); put("provenance", provenance)''',
    "LabStrategy metadata json write",
)
models = replace_once(
    models,
    '''            lastEvaluatedAt = o.optLong("lastEvaluatedAt"),\n        )''',
    '''            lastEvaluatedAt = o.optLong("lastEvaluatedAt"),\n            targetLane = o.optString("targetLane", ""),\n            targetScoreBand = o.optString("targetScoreBand", ""),\n            provenance = o.optString("provenance", "GENERIC"),\n        )''',
    "LabStrategy metadata json read",
)
models = models.replace(
    '''    PROMOTED,     // user has approved real-money trading for this strategy''',
    '''    PROMOTED,     // paper-proof threshold passed; hard trading safety still remains sovereign''',
)
write(models_path, models)

store_path = MAIN / "engine/lab/LlmLabStore.kt"
store = read(store_path)
store = store.replace(
    '''    // captures asymmetric-R/R strategies like Genesis · Sniper that hit\n    // a low WR but with large average winners. Live-money gate\n    // (LabPromotedFeed.requireLiveApproval) still requires operator tap.''',
    '''    // captures asymmetric-R/R strategies like Genesis · Sniper that hit\n    // a lower WR but with positive realized expectancy. V5.0.6684 makes this ONE\n    // proof contract authoritative for promotion and autonomous re-introduction.''',
)
store_anchor = '''    fun activeStrategies(): List<LabStrategy> =\n        strategies.values.filter { it.status == LabStrategyStatus.ACTIVE || it.status == LabStrategyStatus.PROMOTED }\n    fun getStrategy(id: String): LabStrategy? = strategies[id]\n'''
store_insert = '''    fun activeStrategies(): List<LabStrategy> =\n        strategies.values.filter { it.status == LabStrategyStatus.ACTIVE || it.status == LabStrategyStatus.PROMOTED }\n\n    /** V5.0.6684 — single promotion/re-proof contract. */\n    fun hasPromotionProof(strategy: LabStrategy): Boolean =\n        strategy.paperTrades >= MIN_TRADES_BEFORE_PROMOTION &&\n            strategy.winRatePct() >= MIN_WR_FOR_PROMOTION_PCT &&\n            strategy.paperPnlSol >= MIN_PAPER_PNL_SOL_FOR_PROMOTION\n\n    fun hasPromotionProof(strategyId: String): Boolean =\n        strategies[strategyId]?.let(::hasPromotionProof) == true\n\n    fun getStrategy(id: String): LabStrategy? = strategies[id]\n'''
store = replace_once(store, store_anchor, store_insert, "LlmLabStore central proof")
write(store_path, store)

# ---------------------------------------------------------------------------
# 3) LLM LAB: FAILED-LANE REPROOF + PROMOTION CALLBACK
# ---------------------------------------------------------------------------
lab_path = MAIN / "engine/lab/LlmLabEngine.kt"
lab = read(lab_path)
lab = replace_once(
    lab,
    '''    private val autoPivotSeededAt = java.util.concurrent.ConcurrentHashMap<String, Long>()''',
    '''    private val autoPivotSeededAt = java.util.concurrent.ConcurrentHashMap<String, Long>()\n    private val laneReproveRequestedAt6684 = java.util.concurrent.ConcurrentHashMap<String, Long>()''',
    "Lab lane reprove cooldown",
)
# Preserve causal metadata across mutation.
lab = replace_once(
    lab,
    '''            generation = parent.generation + 1,\n            status = LabStrategyStatus.ACTIVE,''',
    '''            generation = parent.generation + 1,\n            status = LabStrategyStatus.ACTIVE,\n            targetLane = parent.targetLane,\n            targetScoreBand = parent.targetScoreBand,\n            provenance = "MUTATION:${parent.provenance}",''',
    "Lab mutation metadata",
)
# Correct Solana specialist asset mapping and stamp exact causal ownership.
old_asset = '''            val asset = when {\n                lane.contains("MOON", true) || lane.contains("SHIT", true) || lane.contains("SNIPER", true) || lane.contains("MANIP", true) -> LabAssetClass.MEME\n                lane.contains("BLUE", true) || lane.contains("QUALITY", true) -> LabAssetClass.ALT\n                else -> LabAssetClass.ANY\n            }'''
new_asset = '''            val laneU6684 = lane.trim().uppercase()\n            val solanaSpecialist6684 = laneU6684 in setOf(\n                "QUALITY", "BLUECHIP", "BLUE_CHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",\n                "MOONSHOT", "PROJECT_SNIPER", "PRESALE_SNIPE", "DIP_HUNTER", "MANIPULATED",\n                "TREASURY", "CASHGEN"\n            )\n            val asset = if (solanaSpecialist6684) LabAssetClass.MEME else LabAssetClass.ANY'''
lab = replace_once(lab, old_asset, new_asset, "Lab Solana asset mapping")
lab = replace_once(
    lab,
    '''                generation = 1,\n                status = LabStrategyStatus.ACTIVE,\n            )\n            LlmLabStore.addStrategy(strategy)''',
    '''                generation = 1,\n                status = LabStrategyStatus.ACTIVE,\n                targetLane = laneU6684,\n                targetScoreBand = scoreBand.uppercase(),\n                provenance = "TACTIC_FAILURE_REPROVE_6684",\n            )\n            LlmLabStore.addStrategy(strategy)''',
    "Lab autopivot causal stamp",
)
# Add lane-wide failed-lane LLM invention immediately before purgeArchived.
purge_anchor = '''    /** Permanently delete all archived strategies. */'''
reprove_code = r'''    /**
     * V5.0.6684 — lane-wide re-proof entry point used by the single quarantine
     * authority. It creates an immediate deterministic LAB_PROPOSED experiment
     * and asynchronously asks the LLM for a second, genuinely new replacement.
     */
    fun seedFromLaneFailure6684(lane: String, reason: String) {
        val laneU = lane.trim().uppercase()
        if (laneU.isBlank() || !LlmLabStore.isEnabled()) return
        seedFromTacticFailure(
            lane = laneU,
            scoreBand = "",
            failedTactic = "FAILED_LIVE_POLICY",
            nextTactic = "LAB_PROPOSED",
            reason = reason,
        )
        val now = System.currentTimeMillis()
        val last = laneReproveRequestedAt6684[laneU] ?: 0L
        if (now - last < 20L * 60_000L) return
        laneReproveRequestedAt6684[laneU] = now
        GlobalScope.launch(Dispatchers.IO) {
            runCatching { runLaneReplacementCreation6684(laneU, reason) }
        }
    }

    private fun runLaneReplacementCreation6684(lane: String, reason: String) {
        if (!GeminiCopilot.isConfigured() || GeminiCopilot.isAIDegraded()) return
        if (LlmLabStore.activeStrategies().size >= MAX_LIVE_STRATEGIES) return
        val prompt = """
AATE specialist lane $lane has been quarantined after canonical losses.
Failure evidence: ${reason.take(600)}
Invent ONE replacement strategy for THIS EXACT lane. It will be paper-tested
before it can be reintroduced. Do not rename or route ownership to another lane.
Return strict JSON only with: name, rationale, asset, entryScoreMin, entryRegime,
takeProfitPct, stopLossPct, maxHoldMins, sizingSol.
""".trimIndent()
        val raw = GeminiCopilot.rawText(
            userPrompt = prompt,
            systemPrompt = "You are AATE's lane re-proof quantitative research lab. Strict JSON only.",
            temperature = 0.85,
            maxTokens = 500,
        ) ?: return
        val parsed = parseStrategyJson(raw) ?: return
        val s = parsed.copy(
            id = LlmLabStore.newStrategyId(),
            asset = LabAssetClass.MEME,
            targetLane = lane,
            targetScoreBand = "",
            provenance = "LLM_LANE_REPLACEMENT_6684",
            status = LabStrategyStatus.ACTIVE,
        )
        LlmLabStore.addStrategy(s)
        try { PipelineHealthCollector.labelInc("LAB_LANE_REPLACEMENT_INVENTED_6684|$lane") } catch (_: Throwable) {}
        ErrorLogger.info(TAG, "🧪 LLM invented lane replacement ${s.name} for $lane")
    }

'''
lab = replace_once(lab, purge_anchor, reprove_code + purge_anchor, "Lab lane replacement insertion")
# Stamp generic LLM creations as generic provenance.
lab = replace_once(
    lab,
    '''            generation = gen,\n            status = LabStrategyStatus.ACTIVE,\n        )''',
    '''            generation = gen,\n            status = LabStrategyStatus.ACTIVE,\n            provenance = "LLM_GENERIC_6684",\n        )''',
    "Lab generic provenance",
)
# Replace cull promotion predicate with central proof + immediate proof callback.
old_promotion = '''            if (s.status == LabStrategyStatus.ACTIVE &&\n                s.paperTrades >= LlmLabStore.MIN_TRADES_BEFORE_PROMOTION &&\n                s.winRatePct() >= LlmLabStore.MIN_WR_FOR_PROMOTION_PCT &&\n                s.paperPnlSol >= LlmLabStore.MIN_PAPER_PNL_SOL_FOR_PROMOTION\n            ) {\n                LlmLabStore.updateStrategy(s.copy(status = LabStrategyStatus.PROMOTED))\n                ErrorLogger.info(TAG, "🧪 AUTO-PROMOTED ${s.name} → live influence " +\n                    "(${s.paperTrades} trades · WR ${\"%.0f\".format(s.winRatePct())}% · " +\n                    "PnL ${\"%+.3f\".format(s.paperPnlSol)}◎)")\n            }'''
new_promotion = '''            if (s.status == LabStrategyStatus.ACTIVE && LlmLabStore.hasPromotionProof(s)) {\n                val promoted6684 = s.copy(status = LabStrategyStatus.PROMOTED)\n                LlmLabStore.updateStrategy(promoted6684)\n                try { com.lifecyclebot.engine.LaneShadowProofLoop.onStrategyPromoted6684(promoted6684) } catch (_: Throwable) {}\n                ErrorLogger.info(TAG, "🧪 AUTO-PROMOTED ${s.name} → proof-authorised influence " +\n                    "(${s.paperTrades} trades · WR ${\"%.0f\".format(s.winRatePct())}% · " +\n                    "PnL ${\"%+.3f\".format(s.paperPnlSol)}◎ target=${s.targetLane.ifBlank { \"GLOBAL\" }})")\n            }'''
lab = replace_once(lab, old_promotion, new_promotion, "Lab central promotion")
write(lab_path, lab)

# ---------------------------------------------------------------------------
# 4) PROMOTED FEED: REAL LANE-AWARE, PROOF-AUTHORISED CONSUMER API
# ---------------------------------------------------------------------------
feed_path = MAIN / "engine/lab/LabPromotedFeed.kt"
feed_src = r'''package com.lifecyclebot.engine.lab

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6684 — proof-authorised Lab strategy bridge.
 *
 * Generic proven inventions may softly shape the whole matching asset class.
 * Failed-lane re-proof is stricter: only a PROMOTED strategy carrying an exact
 * targetLane (and, when supplied, targetScoreBand) may re-authorise that lane.
 * Free-text strategy names are never proof identity.
 */
object LabPromotedFeed {
    private const val TAG = "LabPromotedFeed"
    private val manualLiveAuthorised = ConcurrentHashMap.newKeySet<String>()

    fun grantLiveAuthority(strategyId: String) {
        manualLiveAuthorised.add(strategyId)
        ErrorLogger.info(TAG, "🧪 Explicit live authority granted to $strategyId")
    }
    fun revokeLiveAuthority(strategyId: String) { manualLiveAuthorised.remove(strategyId) }

    /** Lab proof itself is sufficient for adaptive strategy authority; hard FDG/safety still applies. */
    fun requireLiveApproval(strategyId: String): Boolean {
        val s = LlmLabStore.getStrategy(strategyId)
        if (s != null && s.status == LabStrategyStatus.PROMOTED && LlmLabStore.hasPromotionProof(s)) return false
        return !manualLiveAuthorised.contains(strategyId)
    }
    fun isLiveAuthorised(strategyId: String): Boolean = !requireLiveApproval(strategyId)

    data class EntryNudge(
        val strategyId: String,
        val strategyName: String,
        val sizeMultiplier: Double,
        val scoreFloor: Int,
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val maxHoldMins: Int,
    )

    fun canonLane(raw: String): String {
        val u = raw.trim().uppercase()
        return when {
            u == "BLUE_CHIP" -> "BLUECHIP"
            u == "PROJECT_SNIPER" || u.contains("PRESALE") -> "PRESALE_SNIPE"
            else -> u
        }
    }

    private fun scoreBandCompatible(strategy: LabStrategy, scoreBand: String): Boolean {
        if (strategy.targetScoreBand.isBlank() || scoreBand.isBlank()) return true
        return strategy.targetScoreBand.equals(scoreBand, true)
    }

    /** Exact lane proof only. Generic MEME winners can never resurrect a failed specialist. */
    fun provenReplacementForLane(lane: String, scoreBand: String = ""): LabStrategy? {
        val wanted = canonLane(lane)
        if (wanted.isBlank()) return null
        return LlmLabStore.allStrategies()
            .asSequence()
            .filter { it.status == LabStrategyStatus.PROMOTED && LlmLabStore.hasPromotionProof(it) }
            .filter { it.targetLane.isNotBlank() && canonLane(it.targetLane) == wanted }
            .filter { scoreBandCompatible(it, scoreBand) }
            .maxByOrNull { it.paperPnlSol / it.paperTrades.coerceAtLeast(1) }
    }

    /** Lane-wide proof is required to lift a lane-level quarantine. */
    fun provenLaneWideReplacement(lane: String): LabStrategy? {
        val wanted = canonLane(lane)
        return LlmLabStore.allStrategies()
            .asSequence()
            .filter { it.status == LabStrategyStatus.PROMOTED && LlmLabStore.hasPromotionProof(it) }
            .filter { it.targetLane.isNotBlank() && canonLane(it.targetLane) == wanted }
            .filter { it.targetScoreBand.isBlank() }
            .maxByOrNull { it.paperPnlSol / it.paperTrades.coerceAtLeast(1) }
    }

    /** Best proven strategy for normal actuation: exact lane first, then generic asset winner. */
    fun bestPromotedForLane(lane: String, scoreBand: String = "", allowGeneric: Boolean = true): LabStrategy? {
        provenReplacementForLane(lane, scoreBand)?.let { return it }
        if (!allowGeneric) return null
        return LlmLabStore.allStrategies()
            .asSequence()
            .filter { it.status == LabStrategyStatus.PROMOTED && LlmLabStore.hasPromotionProof(it) }
            .filter { it.targetLane.isBlank() }
            .filter { it.asset == LabAssetClass.MEME || it.asset == LabAssetClass.ANY }
            .maxByOrNull { it.paperPnlSol / it.paperTrades.coerceAtLeast(1) }
    }

    fun entryNudge(asset: LabAssetClass, score: Int): EntryNudge? =
        entryNudge(asset, score, lane = "", scoreBand = "")

    fun entryNudge(asset: LabAssetClass, score: Int, lane: String, scoreBand: String): EntryNudge? {
        val exact = if (lane.isNotBlank()) provenReplacementForLane(lane, scoreBand) else null
        val candidates = if (exact != null) listOf(exact) else LlmLabStore.allStrategies().filter {
            it.status == LabStrategyStatus.PROMOTED && LlmLabStore.hasPromotionProof(it) &&
                it.targetLane.isBlank() && (it.asset == LabAssetClass.ANY || it.asset == asset)
        }
        val best = candidates.filter { score >= it.entryScoreMin }
            .maxByOrNull { it.paperPnlSol / it.paperTrades.coerceAtLeast(1) } ?: return null
        val mult = (0.5 + best.winRatePct() / 100.0).coerceIn(0.75, 1.50)
        return EntryNudge(best.id, best.name, mult, best.entryScoreMin,
            best.takeProfitPct, best.stopLossPct, best.maxHoldMins)
    }

    fun executionMultiplierForLane(lane: String, scoreBand: String): Double {
        val s = bestPromotedForLane(lane, scoreBand, allowGeneric = true) ?: return 1.0
        return (0.5 + s.winRatePct() / 100.0).coerceIn(0.75, 1.50)
    }

    fun shouldExitByPromotedRule(asset: LabAssetClass, pnlPct: Double, holdMinutes: Long): Boolean {
        val promoted = LlmLabStore.allStrategies().filter {
            it.status == LabStrategyStatus.PROMOTED && LlmLabStore.hasPromotionProof(it) &&
                it.targetLane.isBlank() && (it.asset == LabAssetClass.ANY || it.asset == asset)
        }
        return promoted.any { pnlPct >= it.takeProfitPct || pnlPct <= it.stopLossPct || holdMinutes >= it.maxHoldMins }
    }

    fun summary(): Pair<Int, Double> {
        val promoted = LlmLabStore.allStrategies().filter { it.status == LabStrategyStatus.PROMOTED && LlmLabStore.hasPromotionProof(it) }
        return promoted.size to promoted.sumOf { it.paperPnlSol }
    }
}
'''
write(feed_path, feed_src)

# ---------------------------------------------------------------------------
# 5) ONE EXACT-LANE SHADOW PROOF LOOP (NO BLACKLIST / NO ASSET-CLASS GUESSING)
# ---------------------------------------------------------------------------
shadow_path = MAIN / "engine/LaneShadowProofLoop.kt"
shadow_src = r'''package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.LabPromotedFeed
import com.lifecyclebot.engine.lab.LabStrategy
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6684 — exact causal lane re-proof loop.
 *
 * A failed specialist stays economically quarantined while scanners, shadow,
 * counterfactual learning and the Lab keep working. It re-enters only when a
 * PROMOTED, centrally-proven Lab strategy explicitly targets that exact lane.
 * Generic MEME winners and free-text name matches are not proof.
 */
object LaneShadowProofLoop {
    const val VERSION = "V5.0.6684_LANE_SHADOW_PROOF_LOOP"
    private const val EVAL_INTERVAL_MS = 30_000L
    private val lastEvalMs = AtomicLong(0L)

    fun evaluate() {
        val now = System.currentTimeMillis()
        val last = lastEvalMs.get()
        if (now - last < EVAL_INTERVAL_MS || !lastEvalMs.compareAndSet(last, now)) return
        LaneAutoPauseGuard.pausedLanes().forEach { lane ->
            val proof = LabPromotedFeed.provenLaneWideReplacement(lane) ?: return@forEach
            resume(lane, proof)
        }
    }

    fun onStrategyPromoted6684(strategy: LabStrategy) {
        if (strategy.targetLane.isBlank() || strategy.targetScoreBand.isNotBlank()) return
        if (!com.lifecyclebot.engine.lab.LlmLabStore.hasPromotionProof(strategy)) return
        resume(strategy.targetLane, strategy)
    }

    private fun resume(lane: String, proof: LabStrategy) {
        if (LaneAutoPauseGuard.resumeWithLabProof6684(lane, proof.id)) {
            try {
                ForensicLogger.lifecycle(
                    "LANE_SHADOW_PROOF_RESUMED_6684",
                    "lane=${LabPromotedFeed.canonLane(lane)} strategy=${proof.id} name=${proof.name.take(48)} n=${proof.paperTrades} wr=${"%.1f".format(proof.winRatePct())}% pnl=${"%+.4f".format(proof.paperPnlSol)}",
                )
                PipelineHealthCollector.labelInc("LANE_SHADOW_PROOF_RESUMED_6684|${LabPromotedFeed.canonLane(lane)}")
            } catch (_: Throwable) {}
        }
    }

    /** Compatibility UI hooks: proof authority cannot be bypassed by toggles. */
    fun allowLaneResume(lane: String) {
        try { PipelineHealthCollector.labelInc("SHADOW_PROOF_OPERATOR_REQUEST_REQUIRES_LAB_6684|${LabPromotedFeed.canonLane(lane)}") } catch (_: Throwable) {}
        evaluate()
    }
    fun blockLaneResume(lane: String) { /* quarantine authority owns state; retained for binary/UI compatibility */ }
    fun blacklistedLanes(): Set<String> = emptySet()
    fun isResumeBlocked(lane: String?): Boolean = lane?.let { LaneAutoPauseGuard.isPausedLive(it) } == true

    fun statusLine(): String = "$VERSION paused=${LaneAutoPauseGuard.pausedLanes().size} proof=exact_target_lane+central_promotion_contract"
}
'''
write(shadow_path, shadow_src)

# ---------------------------------------------------------------------------
# 6) LANE AUTO-PAUSE: MODE-MATCHED CANONICAL TRUTH, NO SELF-RECOVERY BYPASS
# ---------------------------------------------------------------------------
pause_path = MAIN / "engine/LaneAutoPauseGuard.kt"
pause = read(pause_path)
# Remove static historical hard-seed block. Current evidence must create state.
seed_start = pause.find('            // V5.0.4594 — HARD SEED for proven-toxic lanes')
seed_end_marker = '            try { persistAsync() } catch (_: Throwable) {}'
if seed_start >= 0:
    seed_end = pause.find(seed_end_marker, seed_start)
    if seed_end < 0:
        raise SystemExit("6684 anchor missing: LaneAutoPause hard seed end")
    seed_end += len(seed_end_marker)
    pause = pause[:seed_start] + '''            // V5.0.6684 — no historical hard-seed lanes. Persisted/current canonical\n            // evidence is the only quarantine source; stale operator snapshots are not policy.\n''' + pause[seed_end:]
else:
    raise SystemExit("6684 anchor missing: LaneAutoPause hard seed start")

# statusFor/isPaused paper-bypass doctrine: quarantine is economic, shadow/lab remain trainable.
ispaused_start = '    fun isPaused(lane: String?): Boolean {'
ispaused_end = '    /** Live-only check that bypasses the paper override.'
a = pause.find(ispaused_start); b = pause.find(ispaused_end, a)
if a < 0 or b < 0:
    raise SystemExit("6684 anchor missing: LaneAutoPause isPaused")
pause = pause[:a] + '''    fun isPaused(lane: String?): Boolean = statusFor(lane) != null\n\n    /** V5.0.6684 — same persisted quarantine state in both modes. */\n    fun isPausedLive(lane: String?): Boolean = statusFor(lane) != null\n\n''' + pause[b + len(ispaused_end):]
# Remove duplicate old isPausedLive declaration if left by slicing.
pause = re.sub(r'\n\s*fun isPausedLive\(lane: String\?\): Boolean = statusFor\(lane\) != null\n', '\n', pause, count=1)
# Reinsert our desired definition if regex removed the first occurrence.
marker = '    fun isPaused(lane: String?): Boolean = statusFor(lane) != null\n'
if marker in pause and 'fun isPausedLive(lane: String?): Boolean' not in pause:
    pause = pause.replace(marker, marker + '\n    fun isPausedLive(lane: String?): Boolean = statusFor(lane) != null\n', 1)

# Replace entire evaluateLive with mode-matched clean StrategyTelemetry metrics.
eval_start = '    fun evaluateLive() {'
eval_end = '    /** Manual resume — for LLM Lab success or operator override. */'
eval_code = r'''    fun evaluateLive() {
        val now = System.currentTimeMillis()
        val last = lastEvalMs.get()
        if (now - last < 30_000L || !lastEvalMs.compareAndSet(last, now)) return
        ensureLoaded()
        try {
            val paper6684 = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
            val board = if (paper6684) {
                StrategyTelemetry.computeCleanPaperTerminalLeaderboard(limit = 2_000)
            } else {
                StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 2_000)
            }
            var mutated = false
            for (m in board) {
                val lane = canonLane(m.strategy)
                if (lane.isBlank() || paused.containsKey(lane)) continue
                val zeroWin = m.trades >= ZERO_WIN_MIN_SAMPLE && m.wins == 0
                val toxic = m.trades >= TOXIC_MIN_SAMPLE && m.winRatePct < TOXIC_WR_PCT && m.meanPnlPct <= TOXIC_EV_PCT
                if (!zeroWin && !toxic) continue
                val reason = if (zeroWin) "zero_win_n${m.trades}_canonical_${if (paper6684) "paper" else "live"}" else
                    "toxic_wr${"%.0f".format(m.winRatePct)}_ev${"%.0f".format(m.meanPnlPct)}_canonical_${if (paper6684) "paper" else "live"}"
                paused[lane] = PauseState(lane, now, reason, m.trades, m.wins, m.winRatePct, m.meanPnlPct)
                mutated = true
                try {
                    com.lifecyclebot.engine.lab.LlmLabEngine.seedFromLaneFailure6684(lane, reason)
                    ForensicLogger.lifecycle("LANE_QUARANTINED_FOR_LAB_REPROOF_6684",
                        "lane=$lane mode=${if (paper6684) "paper" else "live"} n=${m.trades} wr=${"%.1f".format(m.winRatePct)} ev=${"%.1f".format(m.meanPnlPct)} reason=$reason")
                    PipelineHealthCollector.labelInc("LANE_QUARANTINED_FOR_LAB_REPROOF_6684|$lane")
                } catch (_: Throwable) {}
            }
            // V5.0.6684: NO WR-only self-recovery. A failed lane remains paused
            // until resumeWithLabProof6684 validates an exact lane-wide Lab replacement.
            if (mutated) persistAsync()
        } catch (_: Throwable) {}
    }

    /** External compatibility path for other bleeder detectors: one quarantine authority. */
    fun quarantineExternal6684(
        lane: String, sample: Int, wins: Int, wrPct: Double, evPct: Double, reason: String,
    ): Boolean {
        ensureLoaded()
        val key = canonLane(lane)
        if (key.isBlank() || paused.containsKey(key)) return false
        paused[key] = PauseState(key, System.currentTimeMillis(), reason.take(160), sample, wins, wrPct, evPct)
        persistAsync()
        try { com.lifecyclebot.engine.lab.LlmLabEngine.seedFromLaneFailure6684(key, reason) } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("LANE_EXTERNAL_QUARANTINE_6684|$key") } catch (_: Throwable) {}
        return true
    }

    /** Only an exact, centrally-proven lane-wide Lab replacement may autonomously resume. */
    fun resumeWithLabProof6684(lane: String, strategyId: String): Boolean {
        ensureLoaded()
        val key = canonLane(lane)
        val s = try { com.lifecyclebot.engine.lab.LlmLabStore.getStrategy(strategyId) } catch (_: Throwable) { null } ?: return false
        val exact = com.lifecyclebot.engine.lab.LabPromotedFeed.canonLane(s.targetLane) == com.lifecyclebot.engine.lab.LabPromotedFeed.canonLane(key)
        val proven = s.status == com.lifecyclebot.engine.lab.LabStrategyStatus.PROMOTED &&
            s.targetLane.isNotBlank() && s.targetScoreBand.isBlank() &&
            exact && com.lifecyclebot.engine.lab.LlmLabStore.hasPromotionProof(s)
        if (!proven) {
            try { PipelineHealthCollector.labelInc("LANE_REPROOF_REJECTED_6684|$key") } catch (_: Throwable) {}
            return false
        }
        val removed = paused.remove(key) ?: return false
        persistAsync()
        try {
            ForensicLogger.lifecycle("LANE_REPROOF_ACCEPTED_6684", "lane=$key strategy=${s.id} n=${s.paperTrades} wr=${"%.1f".format(s.winRatePct())}% pnl=${"%+.4f".format(s.paperPnlSol)} original=${removed.reason}")
            PipelineHealthCollector.labelInc("LANE_REPROOF_ACCEPTED_6684|$key")
        } catch (_: Throwable) {}
        return true
    }

'''
pause = replace_between(pause, eval_start, eval_end, eval_code, "LaneAutoPause evaluateLive")
# Manual resume remains explicit operator override only; update doctrine text.
pause = pause.replace(
    '    /** Manual resume — for LLM Lab success or operator override. */',
    '    /** Explicit operator override only. Autonomous code must use resumeWithLabProof6684(). */',
)
# Remove stale recovery constants/comments if present (no behavioral authority).
pause = re.sub(r'\n\s*// V5\.0\.6305 — LANE BLEED AUTO-RECOVERY thresholds\..*?private const val RECOVERY_EV_PCT = 0\.0\n', '\n', pause, flags=re.S)
write(pause_path, pause)

# ---------------------------------------------------------------------------
# 7) COLLAPSE DUPLICATE QUARANTINE CONTROLLER INTO COMPATIBILITY FACADE
# ---------------------------------------------------------------------------
quar_path = MAIN / "engine/LaneQuarantineController.kt"
quar_src = r'''package com.lifecyclebot.engine

/**
 * V5.0.6684 — compatibility facade over LaneAutoPauseGuard.
 *
 * Older patches created a second quarantine set, a second release set, separate
 * thresholds, and free-text Lab name matching. That split-brain is removed.
 */
object LaneQuarantineController {
    const val VERSION = "V5.0.6684_LANE_QUARANTINE_FACADE"
    private const val SCAN_TTL_MS = 20_000L
    private const val MIN_N = 8
    private const val MAX_WR_PCT = 20.0
    private const val MAX_MEAN_PNL_PCT = -8.0
    @Volatile private var lastScanMs = 0L

    fun isQuarantined(lane: String): Boolean {
        maybeScan()
        return LaneAutoPauseGuard.isPausedLive(lane)
    }

    private fun maybeScan() {
        val now = System.currentTimeMillis()
        if (now - lastScanMs < SCAN_TTL_MS) return
        lastScanMs = now
        try {
            val paper = RuntimeModeAuthority.isPaper()
            val board = if (paper) StrategyTelemetry.computeCleanPaperTerminalLeaderboard(1_500)
                        else StrategyTelemetry.computeCleanLiveTerminalLeaderboard(1_500)
            board.forEach { m ->
                val lane = com.lifecyclebot.engine.lab.LabPromotedFeed.canonLane(m.strategy)
                if (lane.isBlank() || lane in setOf("STANDARD", "V3", "V3_CORE")) return@forEach
                if (m.trades >= MIN_N && m.winRatePct <= MAX_WR_PCT && m.meanPnlPct <= MAX_MEAN_PNL_PCT) {
                    LaneAutoPauseGuard.quarantineExternal6684(lane, m.trades, m.wins, m.winRatePct, m.meanPnlPct,
                        "compat_bleeder_scan_6684 mode=${if (paper) "paper" else "live"}")
                }
            }
        } catch (_: Throwable) {}
    }

    fun logBlockedEntry(lane: String, symbol: String, mint: String, primary: String) {
        try {
            ForensicLogger.lifecycle("LANE_QUARANTINED_BLOCKED_ENTRY_6684",
                "lane=${com.lifecyclebot.engine.lab.LabPromotedFeed.canonLane(lane)} symbol=$symbol mint=${mint.take(10)} primary=$primary authority=LaneAutoPauseGuard")
            PipelineHealthCollector.labelInc("LANE_QUARANTINED_BLOCKED_ENTRY_6684|${com.lifecyclebot.engine.lab.LabPromotedFeed.canonLane(lane)}")
        } catch (_: Throwable) {}
    }

    fun quarantineSnapshot(): Map<String, Boolean> = LaneAutoPauseGuard.pausedLanes().associateWith { false }
    fun statusLine(): String = "$VERSION ${LaneAutoPauseGuard.statusLine()}"
}
'''
write(quar_path, quar_src)

# ---------------------------------------------------------------------------
# 8) SSI PILOT MAY REQUEST RECHECK, NEVER BYPASS LAB PROOF
# ---------------------------------------------------------------------------
ssi_path = MAIN / "engine/SsiPilotCouncil.kt"
ssi = read(ssi_path)
ssi = ssi.replace(
    'private const val WARMUP_MS = 3 * 60_000L',
    'private const val WARMUP_MS = 30_000L  // V5.0.6684: pilot alive before early trade batches',
)
ssi = ssi.replace(
    'private const val KNOWN_LANES = "MOONSHOT,SHITCOIN,EXPRESS,QUALITY,BLUECHIP,TREASURY,CASHGEN,MANIPULATED,DIP_HUNTER,PRESALE_SNIPE,STANDARD"',
    'private const val KNOWN_LANES = "MOONSHOT,SHITCOIN,CYCLIC,EXPRESS,CORE,QUALITY,BLUECHIP,TREASURY,CASHGEN,MANIPULATED,DIP_HUNTER,PROJECT_SNIPER,PRESALE_SNIPE,STANDARD"',
)
resume_start = '    /** V5.0.6090: pilot flies autonomously in PAPER and LIVE for non-safety lane pauses only. */'
resume_end = '    // ── PERSISTENCE'
resume_replacement = r'''    /** V5.0.6684 — SSI can request a re-check but cannot manufacture proof. */
    private fun handleResumeRequest(laneRaw: String, paper: Boolean) {
        val lane = laneRaw.trim().uppercase()
        if (lane.isBlank() || !KNOWN_LANES.contains(lane)) return
        try {
            if (lane !in LaneAutoPauseGuard.pausedLanes()) return
            val proof = com.lifecyclebot.engine.lab.LabPromotedFeed.provenLaneWideReplacement(lane)
            if (proof == null) {
                try { PipelineHealthCollector.labelInc("SSI_RESUME_WAITING_LAB_PROOF_6684|$lane") } catch (_: Throwable) {}
                return
            }
            if (LaneAutoPauseGuard.resumeWithLabProof6684(lane, proof.id)) {
                ForensicLogger.lifecycle("SSI_PILOT_LANE_RESUMED_6684",
                    "lane=$lane mode=${if (paper) "paper" else "live"} strategy=${proof.id} authority=exact_lab_proof")
                PipelineHealthCollector.labelInc("SSI_PILOT_LANE_RESUMED_6684|$lane")
            }
        } catch (_: Throwable) {}
    }

'''
ssi = replace_between(ssi, resume_start, resume_end, resume_replacement, "SSI proof-gated resume")
write(ssi_path, ssi)

# ---------------------------------------------------------------------------
# 9) SHADOW-TRAIN / FDG RE-ENTRY AUTHORITY + LAB SIZE ACTUATION
# ---------------------------------------------------------------------------
bucket_path = MAIN / "engine/BucketExecutionState.kt"
bucket = read(bucket_path)
old_state = '''            if (meanToxic || lossToxic) State.SHADOW_TRAIN_ONLY else State.EXECUTABLE'''
new_state = '''            if (meanToxic || lossToxic) {\n                val band6684 = try { LosingPatternMemory.scoreBand(score) } catch (_: Throwable) { "" }\n                val labProof6684 = try { com.lifecyclebot.engine.lab.LabPromotedFeed.provenReplacementForLane(lane, band6684) } catch (_: Throwable) { null }\n                if (labProof6684 != null) State.EXECUTABLE else State.SHADOW_TRAIN_ONLY\n            } else State.EXECUTABLE'''
bucket = replace_once(bucket, old_state, new_state, "BucketExecutionState Lab proof escape")
write(bucket_path, bucket)

fdg_route_path = MAIN / "engine/learning/FdgRouteVerdict.kt"
fdg = read(fdg_route_path)
fdg = replace_once(
    fdg,
    '''        if (hardReason != null && hardReason.isNotBlank()) return Verdict.BLOCK_HARD_SAFETY\n\n        val policy = LanePolicy.effectiveState(lane, scoreBand)''',
    '''        if (hardReason != null && hardReason.isNotBlank()) return Verdict.BLOCK_HARD_SAFETY\n\n        // V5.0.6684 — a centrally-proven exact replacement may re-enter cautiously.\n        // It never overrides hard safety/mode/duplicate/invalid-data checks above.\n        val provenReplacement6684 = try {\n            com.lifecyclebot.engine.lab.LabPromotedFeed.provenReplacementForLane(lane, scoreBand)\n        } catch (_: Throwable) { null }\n        if (provenReplacement6684 != null) return Verdict.ALLOW_REDUCED_SIZE\n\n        val policy = LanePolicy.effectiveState(lane, scoreBand)''',
    "FdgRouteVerdict proof re-entry",
)
fdg = fdg.replace(
    '''            LanePolicy.State.RETRAINING             -> Verdict.ALLOW_PAPER_MICRO\n            LanePolicy.State.SHADOW_TRACK_ONLY       -> Verdict.ALLOW_PAPER_MICRO  // V5.9.1325: never stop trading\n            LanePolicy.State.TRAIN_ONLY_NO_OPEN     -> Verdict.ALLOW_PAPER_MICRO  // V5.9.1325: never stop trading''',
    '''            LanePolicy.State.RETRAINING             -> Verdict.ROUTE_SHADOW_TRACK\n            LanePolicy.State.SHADOW_TRACK_ONLY       -> Verdict.ROUTE_SHADOW_TRACK\n            LanePolicy.State.TRAIN_ONLY_NO_OPEN      -> Verdict.ROUTE_TRAIN_ONLY''',
)
size_start = '    fun sizeMultiplier(verdict: Verdict, lane: String, scoreBand: String): Double {'
size_end = '    data class Snapshot('
size_code = r'''    fun sizeMultiplier(verdict: Verdict, lane: String, scoreBand: String): Double {
        if (!verdict.executable) return 0.0
        val exactReplacement = try { com.lifecyclebot.engine.lab.LabPromotedFeed.provenReplacementForLane(lane, scoreBand) } catch (_: Throwable) { null }
        val base = LanePolicy.effectiveExecutionWeight(lane, scoreBand)
        val bleedCap = if (exactReplacement != null) null else LanePolicy.bleedExecutionCap(lane, scoreBand)
        val routed = when (verdict) {
            Verdict.ALLOW_NORMAL -> if (bleedCap != null) minOf(base, bleedCap).coerceIn(0.05, 0.85) else base.coerceAtLeast(0.85)
            Verdict.ALLOW_REDUCED_SIZE -> if (bleedCap != null) minOf(base, bleedCap).coerceIn(0.05, 0.70) else base.coerceIn(0.30, 0.70)
            Verdict.ALLOW_PAPER_MICRO -> minOf(base, bleedCap ?: 0.15).coerceIn(0.05, 0.15)
            else -> 0.0
        }
        val labMult = try { com.lifecyclebot.engine.lab.LabPromotedFeed.executionMultiplierForLane(lane, scoreBand) } catch (_: Throwable) { 1.0 }
        return (routed * labMult).coerceIn(0.0, 1.0)
    }

'''
fdg = replace_between(fdg, size_start, size_end, size_code, "FdgRouteVerdict size authority")
write(fdg_route_path, fdg)

# ---------------------------------------------------------------------------
# 10) PROVEN LAB EXIT PARAMETERS ACTUATE IN THE ACTIVE HOLDING PATH
# ---------------------------------------------------------------------------
hold_path = MAIN / "engine/HoldingLogicLayer.kt"
hold = read(hold_path)
hold_anchor = '''            // 1. CHECK FOR CRITICAL CONDITIONS (exit immediately)'''
hold_insert = r'''            // V5.0.6684 — install the best PROVEN Lab strategy into the active
            // lane exit lifecycle. AdvancedExitManager/hard safety already ran above.
            // This is strategy actuation (TP/SL/max-hold), not an order/safety bypass.
            try {
                val entryBand6684 = try { LosingPatternMemory.scoreBand(position.entryScore.toInt()) } catch (_: Throwable) { "" }
                val labStrategy6684 = com.lifecyclebot.engine.lab.LabPromotedFeed.bestPromotedForLane(
                    position.tradingMode, entryBand6684, allowGeneric = true,
                )
                if (labStrategy6684 != null) {
                    val labHoldMinutes6684 = holdTimeMs / 60_000L
                    when {
                        currentPnlPct <= labStrategy6684.stopLossPct -> return HoldEvaluation(
                            action = HoldAction.EXIT_NOW,
                            reason = "LAB_PROVEN_SL_6684:${labStrategy6684.id} pnl=${currentPnlPct.toInt()}% <= ${labStrategy6684.stopLossPct.toInt()}%",
                            confidence = 88.0, urgency = Urgency.HIGH,
                        )
                        currentPnlPct >= labStrategy6684.takeProfitPct -> return HoldEvaluation(
                            action = HoldAction.EXIT_NOW,
                            reason = "LAB_PROVEN_TP_6684:${labStrategy6684.id} pnl=${currentPnlPct.toInt()}% >= ${labStrategy6684.takeProfitPct.toInt()}%",
                            confidence = 82.0, urgency = Urgency.NORMAL,
                        )
                        labHoldMinutes6684 >= labStrategy6684.maxHoldMins -> return HoldEvaluation(
                            action = HoldAction.EXIT_NOW,
                            reason = "LAB_PROVEN_MAX_HOLD_6684:${labStrategy6684.id} hold=${labHoldMinutes6684}m >= ${labStrategy6684.maxHoldMins}m",
                            confidence = 78.0, urgency = Urgency.NORMAL,
                        )
                    }
                }
            } catch (_: Throwable) {}

'''
hold = replace_once(hold, hold_anchor, hold_insert + hold_anchor, "HoldingLogic Lab strategy actuation")
write(hold_path, hold)

# ---------------------------------------------------------------------------
# 11) CLOSED-LOOP REGRESSION CONTRACT
# ---------------------------------------------------------------------------
test_path = TEST / "Aate6684AdaptiveClosedLoopTest.kt"
test_src = r'''package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6684AdaptiveClosedLoopTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun `adaptive runtime owns all long lived intelligence drivers`() {
        val app = src("AATEApp.kt")
        val rt = src("engine/AdaptiveIntelligenceRuntime6684.kt")
        assertTrue(app.contains("AdaptiveIntelligenceRuntime6684.start(applicationContext)"))
        listOf("LlmLabEngine.start(app)", "LlmLabEngine.tick { buildUniverse() }",
            "SentienceOrchestrator.start(app)", "SsiPilotCouncil.start()",
            "LaneAutoPauseGuard.evaluateLive()", "LaneShadowProofLoop.evaluate()",
            "ChronicBleederScout.tick()", "SentienceHooks.maybeAutoTune(app)").forEach { assertTrue(it, rt.contains(it)) }
    }

    @Test fun `lab proof is causal lane identity not free text`() {
        val models = src("engine/lab/LlmLabModels.kt")
        val store = src("engine/lab/LlmLabStore.kt")
        val feed = src("engine/lab/LabPromotedFeed.kt")
        val shadow = src("engine/LaneShadowProofLoop.kt")
        assertTrue(models.contains("val targetLane: String = \"\""))
        assertTrue(models.contains("val targetScoreBand: String = \"\""))
        assertTrue(store.contains("fun hasPromotionProof(strategy: LabStrategy): Boolean"))
        assertTrue(feed.contains("fun provenReplacementForLane"))
        assertTrue(feed.contains("fun provenLaneWideReplacement"))
        assertTrue(shadow.contains("provenLaneWideReplacement(lane)"))
        assertFalse(shadow.contains("resumeBlacklist"))
        assertFalse(shadow.contains("assetForLane"))
    }

    @Test fun `failed lanes cannot self resume around the lab`() {
        val pause = src("engine/LaneAutoPauseGuard.kt")
        val ssi = src("engine/SsiPilotCouncil.kt")
        val quarantine = src("engine/LaneQuarantineController.kt")
        assertTrue(pause.contains("fun resumeWithLabProof6684"))
        assertTrue(pause.contains("LlmLabStore.hasPromotionProof(s)"))
        assertFalse(pause.contains("LANE_AUTO_RECOVERED_6305"))
        assertFalse(pause.contains("hard_seed_4594"))
        assertFalse(pause.contains("hard_seed_6067"))
        assertFalse(ssi.contains("LaneAutoPauseGuard.manualResume(lane"))
        assertTrue(ssi.contains("provenLaneWideReplacement(lane)"))
        assertFalse(quarantine.contains("releasedLanes"))
        assertFalse(quarantine.contains("s.name.contains"))
        assertTrue(quarantine.contains("quarantineExternal6684"))
    }

    @Test fun `shadow train only reopens on exact proven replacement and strategy actuates`() {
        val bucket = src("engine/BucketExecutionState.kt")
        val route = src("engine/learning/FdgRouteVerdict.kt")
        val hold = src("engine/HoldingLogicLayer.kt")
        assertTrue(bucket.contains("LabPromotedFeed.provenReplacementForLane"))
        assertTrue(route.contains("provenReplacementForLane(lane, scoreBand)"))
        assertTrue(route.contains("LanePolicy.State.SHADOW_TRACK_ONLY       -> Verdict.ROUTE_SHADOW_TRACK"))
        assertTrue(route.contains("LabPromotedFeed.executionMultiplierForLane"))
        assertTrue(hold.contains("LabPromotedFeed.bestPromotedForLane"))
        assertTrue(hold.contains("LAB_PROVEN_SL_6684"))
        assertTrue(hold.contains("LAB_PROVEN_TP_6684"))
    }
}
'''
write(test_path, test_src)

print("V5.0.6684 adaptive closed-loop source repair applied")
