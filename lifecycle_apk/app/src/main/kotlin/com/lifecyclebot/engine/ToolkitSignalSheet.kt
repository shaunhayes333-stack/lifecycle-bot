package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.util.AppDispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * V5.0.3852 — ToolkitSignalSheet.
 *
 * Read-only, hot-path-safe aggregation layer for the already-existing trading toolkit.
 * This does NOT execute trades, does NOT call FDG, does NOT call network/LLM APIs, and
 * does NOT expand lane fanout. It converts dormant chart/degen/hold/crypto/whale signals
 * into one compact per-token sheet consumed by AgenticStyleRouter.
 *
 * Contract:
 *   scanner/token state -> ToolkitSignalSheet.snapshot() -> AgenticStyleRouter -> existing
 *   bounded lane/tool affinity -> existing FDG/executor path.
 *
 * Performance contract:
 *   AgenticStyleRouter reads a cached helper snapshot. Full-sheet refresh runs as a
 *   silent coroutine on AppDispatchers.sideEffect, single-flight per mint. Cold cache
 *   returns a cheap O(1) fallback sheet and warms in the background. No bot-loop choke.
 */
object ToolkitSignalSheet {
    private const val CACHE_TTL_MS = 2_500L
    // V5.0.7476 — unchanged analytical evidence does not need to rebuild the
    // whole multi-desk sheet every 2.5s. Real fingerprint changes still refresh
    // immediately; this only extends reuse when the inputs are identical.
    private const val UNCHANGED_CACHE_TTL_MS_7476 = 15_000L
    private data class CacheEntry(val sheet: Sheet, val tsMs: Long, val fingerprint: Int)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val deskStageCounts6599 = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    // V5.0.7481 — this is telemetry idempotency, not trading authority.
    // Keep a bounded timestamped cache aligned with the causal-funnel horizon
    // instead of an unbounded lifetime set.
    private val deskStageOnce7481 = ConcurrentHashMap<String, Long>()
    private const val DESK_STAGE_TTL_MS_7481 = 1_800_000L
    private const val DESK_STAGE_SOFT_CAP_7481 = 48_000
    private val deskStagePruneAt7481 = java.util.concurrent.atomic.AtomicLong(0L)

    private fun firstDeskStage7481(key: String): Boolean {
        val now = System.currentTimeMillis()
        val prior = deskStageOnce7481.putIfAbsent(key, now)
        if (prior != null && now - prior <= DESK_STAGE_TTL_MS_7481) return false
        if (prior != null) {
            // Expired telemetry identity: atomically refresh it. The canonical
            // causal/ticket/execution authorities still own economic idempotency.
            if (!deskStageOnce7481.replace(key, prior, now)) return false
        }
        if (deskStageOnce7481.size > DESK_STAGE_SOFT_CAP_7481 &&
            now - deskStagePruneAt7481.get() > 60_000L &&
            deskStagePruneAt7481.compareAndSet(deskStagePruneAt7481.get(), now)
        ) {
            val cutoff = now - DESK_STAGE_TTL_MS_7481
            deskStageOnce7481.entries.removeIf { it.value < cutoff }
            if (deskStageOnce7481.size > DESK_STAGE_SOFT_CAP_7481) {
                val overflow = deskStageOnce7481.size - DESK_STAGE_SOFT_CAP_7481
                deskStageOnce7481.entries
                    .sortedBy { it.value }
                    .take(overflow)
                    .forEach { deskStageOnce7481.remove(it.key, it.value) }
            }
            try { PipelineHealthCollector.labelInc("DESK_STAGE_DEDUPE_CACHE_PRUNED_7481") } catch (_: Throwable) {}
        }
        return true
    }

    private val configuredMemeDesks6599 = listOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE", "MOONSHOT",
        "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN",
    )
    fun configuredMemeDesks6647(): List<String> = configuredMemeDesks6599.toList()

    enum class Setup {
        NONE,
        DIAMOND_HANDS_RUNNER,
        DEGEN_MICRO_SNIPE,
        PUMP_GRADUATION_SNIPE,
        CHART_BREAKOUT,
        CHART_PULLBACK_RECLAIM,
        WHALE_ACCUMULATION_HOLD,
        EXHAUSTION_QUICK_FLIP,
        MAINSTREAM_CRYPTO_SWING,
        VOLUME_IGNITION_SCALP,
        SMART_WALLET_COPY_FOLLOW,
        NARRATIVE_SOCIAL_IGNITION,
        LIQUIDITY_DEPTH_QUALITY,
        PANIC_REVERSION_BOUNCE,
        ARB_FLOW_IMBALANCE,
        MEV_PROTECTED_ENTRY,
        REENTRY_RECOVERY,
        CASHFLOW_SCALP,
        CYCLIC_COMPOUND,
        REGIME_DEFENSIVE_PROBE,
    }

    data class DeskHypothesis(
        val lane: String,
        val setup: Setup,
        val conviction: Double,
        val entryStyle: String,
        val exitStyle: String,
        val holdMult: Double,
        val sizeMult: Double,
        val tpMult: Double,
        val reason: String,
    )

    data class Sheet(
        val setup: Setup,
        val confidence: Double,
        val chartPattern: String,
        val entryStyle: String,
        val exitStyle: String,
        val holdMult: Double,
        val sizeMult: Double,
        val tpMult: Double,
        val laneVotes: Set<String>,
        val deskHypotheses: Map<String, DeskHypothesis>,
        val toolVotes: Set<String>,
        val reasons: List<String>,
    ) {
        val compactReason: String get() = reasons.take(5).joinToString(";")
    }

    private data class Candidate(
        val setup: Setup,
        val score: Double,
        val chart: String,
        val entry: String,
        val exit: String,
        val hold: Double,
        val size: Double,
        val tp: Double,
        val lanes: Set<String>,
        val tools: Set<String>,
        val reasons: List<String>,
    )

    fun snapshot(ts: TokenState, classification: ModeRouter.Classification? = null): Sheet {
        val now = System.currentTimeMillis()
        val fp = fingerprint(ts, classification)
        val existing = cache[ts.mint]
        if (existing != null) {
            val age7476 = now - existing.tsMs
            if (existing.fingerprint == fp && age7476 <= UNCHANGED_CACHE_TTL_MS_7476) {
                if (age7476 > CACHE_TTL_MS) {
                    try { PipelineHealthCollector.labelInc("TOOLKIT_UNCHANGED_SHEET_REUSED_7476") } catch (_: Throwable) {}
                }
                return existing.sheet
            }
        }
        refreshAsync(ts, classification, fp)
        return existing?.sheet ?: fallbackSheet(ts, classification)
    }

    fun fallbackSheet(ts: TokenState, classification: ModeRouter.Classification? = null): Sheet {
        val tt = classification?.tradeType ?: ModeRouter.TradeType.UNKNOWN
        val launch7402 = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
        val weakRegime = try {
            val r = RegimeDetector.current()
            r.regime == RegimeDetector.Regime.DUMP || (r.regime == RegimeDetector.Regime.CHOP && r.recentWrPct < 25.0)
        } catch (_: Throwable) { false }
        val setup = when (tt) {
            ModeRouter.TradeType.BREAKOUT_CONTINUATION, ModeRouter.TradeType.GRADUATION -> if (weakRegime) Setup.LIQUIDITY_DEPTH_QUALITY else Setup.CHART_BREAKOUT
            ModeRouter.TradeType.FRESH_LAUNCH -> when {
                launch7402?.tooLateForSnipe == true -> Setup.EXHAUSTION_QUICK_FLIP
                weakRegime -> Setup.REGIME_DEFENSIVE_PROBE
                else -> Setup.DEGEN_MICRO_SNIPE
            }
            ModeRouter.TradeType.REVERSAL_RECLAIM -> Setup.CHART_PULLBACK_RECLAIM
            ModeRouter.TradeType.WHALE_ACCUMULATION -> Setup.WHALE_ACCUMULATION_HOLD
            ModeRouter.TradeType.TREND_PULLBACK -> Setup.MAINSTREAM_CRYPTO_SWING
            ModeRouter.TradeType.SENTIMENT_IGNITION -> Setup.NARRATIVE_SOCIAL_IGNITION
            ModeRouter.TradeType.COPY_TRADE -> Setup.SMART_WALLET_COPY_FOLLOW
            else -> Setup.NONE
        }
        return Sheet(
            setup = setup,
            confidence = (classification?.confidence ?: 0.0).coerceIn(0.0, 55.0),
            chartPattern = "snapshot_pending",
            entryStyle = "cached_or_pending",
            exitStyle = "default_until_sheet_refresh",
            holdMult = 1.0,
            sizeMult = 1.0,
            tpMult = 1.0,
            laneVotes = emptySet(),
            deskHypotheses = emptyMap(),
            toolVotes = emptySet(),
            reasons = listOf("silent_refresh_pending", "type=$tt", "mint=${ts.mint.take(8)}") + if (weakRegime) listOf("regime=weak_runtime") else emptyList(),
        )
    }

    private fun refreshAsync(ts: TokenState, classification: ModeRouter.Classification?, fp: Int) {
        val mint = ts.mint
        if (mint.isBlank()) return
        if (!inFlight.add(mint)) return
        GlobalScope.launch(AppDispatchers.sideEffect) {
            try {
                try {
                    InternetEdgeDesk.refreshAsync(
                        trigger = "toolkit_sheet",
                        context = "symbol=${ts.symbol} source=${ts.source} liq=${ts.lastLiquidityUsd.toInt()} mcap=${ts.lastMcap.toInt()} score=${ts.lastV3Score ?: ts.entryScore.toInt()} confidence=${ts.lastV3Confidence ?: 0} classification=${classification?.tradeType}",
                    )
                } catch (_: Throwable) {}
                val built = build(ts, classification)
                cache[mint] = CacheEntry(built, System.currentTimeMillis(), fp)
                try { PipelineHealthCollector.labelInc("TOOLKIT_SIGNAL_SHEET_REFRESHED") } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("TOOLKIT_SETUP_${built.setup.name}") } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("TOOLKIT_CHART_${built.chartPattern.uppercase().take(48)}") } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("TOOLKIT_MOVEMENT_${built.chartPattern.uppercase().take(48)}") } catch (_: Throwable) {}
            } catch (_: Throwable) {
                try { PipelineHealthCollector.labelInc("TOOLKIT_SIGNAL_SHEET_REFRESH_FAILED") } catch (_: Throwable) {}
            } finally {
                inFlight.remove(mint)
            }
        }
    }

    private fun meaningfulPriceBucket7476(price: Double): Long {
        if (!price.isFinite() || price <= 0.0) return 0L
        // Log-space 10bp buckets are scale independent: a sub-cent meme and a
        // $200 asset invalidate on the same relative move. This preserves fast
        // reaction to actual movement without treating a timestamp-only update
        // as new information.
        return try { (kotlin.math.ln(price) * 10_000.0).toLong() } catch (_: Throwable) { price.toBits() }
    }

    private fun fingerprint(ts: TokenState, classification: ModeRouter.Classification?): Int = listOf(
        ts.mint,
        // V5.0.7476 — lastPriceUpdate is a clock tick, not analytical evidence.
        // Keeping it here invalidated the full sheet on every quote timestamp
        // even when price/history/flow/liquidity had not changed.
        ts.history.size,
        meaningfulPriceBucket7476(ts.lastPrice),
        ts.lastV3Score,
        ts.lastV3Confidence,
        ts.lastBuyPressurePct.toInt(),
        ts.lastSellPressurePct.toInt(),
        ts.lastLiquidityUsd.toInt(),
        ts.lastMcap.toInt(),
        ts.source,
        classification?.tradeType?.name,
        classification?.confidence?.toInt(),
    ).hashCode()

    fun build(ts: TokenState, classification: ModeRouter.Classification? = null): Sheet {
        val hist = try { ts.history.toList().filter { it.priceUsd.isFinite() && it.priceUsd > 0.0 } } catch (_: Throwable) { emptyList() }
        val prices = hist.map { it.priceUsd }
        // V5.0.7389 — volume features use only real (non-synthetic, volume > 0) candles; tick-appended candles carry no volume.
        val vols = hist.filter { !it.synthetic }.map { it.vol }.filter { it.isFinite() && it > 0.0 }
        val realVolumeLowData = vols.size < 8
        val last = prices.lastOrNull() ?: ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val launch7402 = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
        val ageMin = try { com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts) ?: Double.NaN } catch (_: Throwable) { Double.NaN }
        val src = ts.source.uppercase()
        val liq = ts.lastLiquidityUsd.takeIf { it.isFinite() } ?: 0.0

        // V5.0.7630 — restore the V4 liquidity-fragility brain from cached,
        // already-observed evidence. Its readers (SymbolicExitReasoner and
        // TradeLessonRecorder) were live while analyze() had no production
        // caller, so they consumed the same default fragility for every token.
        // Unknown spread/slippage/impact remain neutral rather than invented.
        try {
            val latestReal7630 = hist.asReversed().firstOrNull {
                !it.synthetic && it.volume24h.isFinite() && it.volume24h > 0.0
            }
            val topHolder7630 = listOfNotNull(
                ts.safety.topHolderPct.takeIf { it.isFinite() && it >= 0.0 },
                ts.topHolderPct?.takeIf { it.isFinite() && it >= 0.0 },
            ).maxOrNull() ?: 0.0
            val poolAgeDays7630 = if (ageMin.isFinite() && ageMin >= 0.0) {
                (ageMin / 1440.0).toInt().coerceAtLeast(0)
            } else 999
            val upperWickPct7630 = hist.takeLast(30)
                .filter {
                    !it.synthetic && it.priceUsd.isFinite() && it.priceUsd > 0.0 &&
                        it.highUsd.isFinite() && it.highUsd > 0.0
                }
                .map {
                    (((it.highUsd - it.priceUsd).coerceAtLeast(0.0)) / it.priceUsd * 100.0)
                        .coerceIn(0.0, 10_000.0)
                }
            com.lifecyclebot.v4.meta.LiquidityFragilityAI.analyze(
                market = "MEME",
                symbol = ts.symbol,
                id = ts.mint,
                depthUsd = liq,
                volume24hUsd = latestReal7630?.volume24h ?: 0.0,
                topHolderPct = topHolder7630,
                poolAgeDays = poolAgeDays7630,
                recentWickPcts = upperWickPct7630,
            )
            PipelineHealthCollector.labelInc("LIQUIDITY_FRAGILITY_CACHED_FEED_7630")
        } catch (_: Throwable) {
            try { PipelineHealthCollector.labelInc("LIQUIDITY_FRAGILITY_CACHED_FEED_FAILED_7630") } catch (_: Throwable) {}
        }

        val mcap = ts.lastMcap.takeIf { it.isFinite() } ?: 0.0
        val bp = ts.lastBuyPressurePct.takeIf { it.isFinite() } ?: 50.0
        val conf = (ts.lastV3Confidence ?: 50).coerceIn(0, 100).toDouble()
        val v3 = (ts.lastV3Score ?: ts.entryScore.toInt()).coerceIn(-100, 150).toDouble()
        val tt = classification?.tradeType ?: try { ModeRouter.classify(ts).tradeType } catch (_: Throwable) { ModeRouter.TradeType.UNKNOWN }
        val regime = try { RegimeDetector.current() } catch (_: Throwable) { null }
        val movementSignal = try { MovementPatternSignal.from(ts) } catch (_: Throwable) { null }
        val nativeBrains7542 = SpecialistBrainBridge7542.evaluate(ts)

        val move5 = pctMove(prices.takeLast(6))
        val move12 = pctMove(prices.takeLast(13))
        val pullbackFromHigh = if (prices.size >= 6 && last > 0.0) {
            val hi = prices.takeLast(12).maxOrNull() ?: last
            if (hi > 0.0) ((hi - last) / hi) * 100.0 else 0.0
        } else 0.0
        val nearHigh = if (prices.size >= 6 && last > 0.0) {
            val hi = prices.takeLast(12).maxOrNull() ?: last
            hi > 0.0 && last >= hi * 0.92
        } else false
        val volIgnition = if (vols.size >= 8) {
            val recent = vols.takeLast(3).average()
            val prior = vols.dropLast(3).takeLast(5).average().coerceAtLeast(1.0)
            recent / prior
        } else 1.0
        val higherLows = if (prices.size >= 5) prices.takeLast(5).zipWithNext { a, b -> b >= a * 0.985 }.count { it } else 0
        val wickBought = hist.takeLast(4).count { c -> c.lowUsd > 0.0 && c.priceUsd > c.lowUsd * 1.02 }
        val upperWicks = hist.takeLast(4).count { it.hasUpperWick }
        val toolHints = try { ts.toolAffinity.map { it.uppercase() }.toSet() } catch (_: Throwable) { emptySet() }
        val laneHints = try { ts.laneAffinity.map { it.uppercase() }.toSet() } catch (_: Throwable) { emptySet() }
        val sellPressure = ts.lastSellPressurePct.takeIf { it.isFinite() } ?: 50.0
        val sentimentScore = try { ts.sentiment.score } catch (_: Throwable) { 0.0 }
        val volatility = ts.volatility ?: 0.0
        val momentum = ts.momentum ?: 0.0
        val copyHint = toolHints.any { it.contains("COPY") || it.contains("SMART") || it.contains("WHALE") }
        // V5.0.7403 — TRENDING is lagging visibility, not social ignition evidence.
        val socialHint = toolHints.any { it.contains("NARRATIVE") || it.contains("SOCIAL") || it.contains("SENTIMENT") }
        val mevRisk = toolHints.any { it.contains("MEV") || it.contains("JITO") } || (upperWicks >= 2 && sellPressure > 58.0)
        val arbHint = toolHints.any { it.contains("ARB") || it.contains("FLOW") || it.contains("VENUE") }

        val candidates = mutableListOf<Candidate>()

        fun add(c: Candidate) { if (c.score > 0.0) candidates += c }

        // Core movement signal: operator-grade chart-pattern-to-movement recognition.
        // This runs on the same in-memory history as the rest of the sheet and makes
        // movement patterns visible to style routing immediately, not only after the
        // async SmartChart scanner warms the cache.
        movementSignal?.let { ms ->
            add(Candidate(
                setup = when (ms.pattern) {
                    "BREAKOUT_CONTINUATION" -> Setup.CHART_BREAKOUT
                    "PULLBACK_RECLAIM" -> Setup.CHART_PULLBACK_RECLAIM
                    // V5.0.7389 — never derive a liquidity/accumulation setup from low-data (volume-less) movement input.
                    "ACCUMULATION_COMPRESSION" -> if (realVolumeLowData) Setup.NONE else Setup.LIQUIDITY_DEPTH_QUALITY
                    "EXHAUSTION_CHASE" -> Setup.EXHAUSTION_QUICK_FLIP
                    "VOLUME_IGNITION" -> if (launch7402?.tooLateForSnipe == true) Setup.EXHAUSTION_QUICK_FLIP else Setup.VOLUME_IGNITION_SCALP
                    "FREEFALL_NO_RECLAIM" -> Setup.REGIME_DEFENSIVE_PROBE
                    else -> Setup.NONE
                },
                score = ms.confidence,
                chart = ms.pattern.lowercase(),
                entry = ms.timing,
                exit = "movement_aware_hold_and_trail",
                hold = ms.holdMult,
                size = ms.sizeMult,
                tp = if (ms.holdMult > 1.4) 1.25 else 0.92,
                lanes = when (ms.pattern) {
                    "BREAKOUT_CONTINUATION" -> setOf("MOONSHOT", "QUALITY")
                    "PULLBACK_RECLAIM" -> setOf("DIP_HUNTER", "QUALITY", "CYCLIC", "CASHGEN")
                    "ACCUMULATION_COMPRESSION" -> setOf("QUALITY", "BLUECHIP", "CYCLIC")
                    "EXHAUSTION_CHASE" -> setOf("EXPRESS", "MANIPULATED", "SHITCOIN")
                    "VOLUME_IGNITION" -> if (launch7402?.tooLateForSnipe == true)
                        setOf("EXPRESS", "MANIPULATED", "SHITCOIN")
                    else setOf("EXPRESS", "SHITCOIN", "MOONSHOT")
                    else -> setOf("SHITCOIN")
                },
                tools = setOf("SMART_CHART", "PATTERN_CLASSIFIER", "MFE_TRAIL", "MOVEMENT_PATTERN"),
                reasons = listOf("movement=${ms.pattern}", "conf=${ms.confidence.toInt()}", ms.reason)
            ))
        }

        // V5.0.7389 — every additive `if` term in the score expressions below is parenthesised; unparenthesised
        // `x + if (c) 10.0 else 0.0 + if (d) ...` parsed as `else (0.0 + ...)` and dropped trailing terms when c was true.
        // Diamond hands / runner: strong structure, high confidence, near highs, not a scalp.
        add(Candidate(
            setup = Setup.DIAMOND_HANDS_RUNNER,
            score = if (launch7402?.tooLateForSnipe == true && ageMin <= 10.0) 0.0 else
                (if (nearHigh) 18.0 else 0.0) + (move12.coerceAtLeast(0.0) * 0.45).coerceAtMost(28.0) +
                    conf * 0.25 + (if (liq >= 8_000.0) 10.0 else 0.0) + (if (higherLows >= 3) 10.0 else 0.0),
            chart = "runner_near_high",
            entry = "breakout_retest_or_strength_add",
            exit = "diamond_hands_high_water_trail",
            hold = 2.80,
            size = 0.92,
            tp = 1.55,
            lanes = setOf("MOONSHOT", "QUALITY"),
            tools = setOf("DIAMOND_HANDS", "MFE_TRAIL", "BREAKOUT", "SMART_CHART"),
            reasons = listOf("nearHigh=$nearHigh", "move12=${move12.toInt()}%", "higherLows=$higherLows", "conf=${conf.toInt()}")
        ))

        // V5.0.7450 — PRE-PARABOLA LAUNCH TAPE.
        // Organic ignition and coordinated/dev-led ignition are different
        // market theses and now generate different specialist hypotheses.
        val pumpLike = src.contains("PUMP") || src.contains("NEW_POOL") || src.contains("RAYDIUM_NEW")
        val launchEarly7450 = launch7402 != null &&
            launch7402.birthResolved &&
            !launch7402.tooLateForSnipe &&
            launch7402.phase in setOf(
                com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION,
                com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION,
            )
        val organicIgnition7450 = launchEarly7450 &&
            launch7402!!.distinctBuyers60s >= 3 &&
            launch7402.largestBuyerSharePct60s <= 65.0 &&
            launch7402.devSellTx60s == 0 &&
            launch7402.buySharePct >= 55.0
        val coordinatedIgnition7450 = launchEarly7450 &&
            launch7402!!.devSellTx60s == 0 &&
            (
                launch7402.largestBuyerSharePct60s >= 40.0 ||
                launch7402.repeatBuyerWallets60s >= 2 ||
                launch7402.devBuyTx60s > 0 ||
                launch7402.smartMoneyBuyers60s >= 2
            )

        add(Candidate(
            setup = if (src.contains("GRADUATE") || tt == ModeRouter.TradeType.GRADUATION)
                Setup.PUMP_GRADUATION_SNIPE else Setup.DEGEN_MICRO_SNIPE,
            score = when {
                !organicIgnition7450 -> 0.0
                launch7402!!.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION ->
                    54.0 +
                        launch7402.distinctBuyers60s.coerceAtMost(8) * 3.0 +
                        launch7402.smartMoneyBuyers60s.coerceAtMost(3) * 5.0 +
                        (if (launch7402.accelerationRising) 8.0 else 0.0) +
                        (if (liq in 1_000.0..25_000.0) 8.0 else 0.0)
                else ->
                    42.0 +
                        launch7402!!.distinctBuyers60s.coerceAtMost(8) * 2.5 +
                        launch7402.smartMoneyBuyers60s.coerceAtMost(3) * 4.0 +
                        (if (launch7402.accelerationRising) 6.0 else 0.0)
            },
            chart = "launch_tape_organic_ignition",
            entry = "pre_parabola_broad_flow",
            exit = "quick_flip_then_runner_tail",
            hold = 0.55,
            size = 0.62,
            tp = 0.90,
            lanes = setOf("PROJECT_SNIPER", "MOONSHOT", "SHITCOIN"),
            tools = setOf("LAUNCH_TAPE", "BUYER_BREADTH", "SMART_MONEY", "PUMP_FUN", "SNIPE_AGE_GATE"),
            reasons = listOf(
                "phase=${launch7402?.phase}",
                "buyers=${launch7402?.distinctBuyers60s}",
                "largestBuyer=${launch7402?.largestBuyerSharePct60s?.toInt()}%",
                "smart=${launch7402?.smartMoneyBuyers60s}",
                "accel=${launch7402?.accelerationRising}",
            )
        ))

        add(Candidate(
            setup = Setup.EXHAUSTION_QUICK_FLIP,
            score = when {
                !coordinatedIgnition7450 -> 0.0
                launch7402!!.largestBuyerSharePct60s >= 75.0 && launch7402.smartMoneyBuyers60s == 0 ->
                    42.0
                else ->
                    50.0 +
                        launch7402.repeatBuyerWallets60s.coerceAtMost(4) * 5.0 +
                        launch7402.smartMoneyBuyers60s.coerceAtMost(3) * 6.0 +
                        (if (launch7402.devBuyTx60s > 0) 7.0 else 0.0) +
                        (if (launch7402.accelerationRising) 8.0 else 0.0)
            },
            chart = "launch_tape_coordinated_ignition",
            entry = "coordination_before_visible_expansion",
            exit = "tight_manipulation_trail",
            hold = 0.42,
            size = 0.48,
            tp = 0.78,
            lanes = setOf("MANIPULATED", "PROJECT_SNIPER"),
            tools = setOf("LAUNCH_TAPE", "WALLET_CONCENTRATION", "DEV_FLOW", "SMART_MONEY", "MEV_AWARE"),
            reasons = listOf(
                "largestBuyer=${launch7402?.largestBuyerSharePct60s?.toInt()}%",
                "top3=${launch7402?.top3BuyerSharePct60s?.toInt()}%",
                "repeatWallets=${launch7402?.repeatBuyerWallets60s}",
                "devB/S=${launch7402?.devBuyTx60s}/${launch7402?.devSellTx60s}",
                "smart=${launch7402?.smartMoneyBuyers60s}",
            )
        ))

        // Source/age alone can keep the candidate visible while the event tape
        // warms, but can no longer outrank real launch-flow hypotheses.
        add(Candidate(
            setup = if (src.contains("GRADUATE") || tt == ModeRouter.TradeType.GRADUATION)
                Setup.PUMP_GRADUATION_SNIPE else Setup.DEGEN_MICRO_SNIPE,
            score = when {
                launch7402?.tooLateForSnipe == true -> 0.0
                launchEarly7450 && (organicIgnition7450 || coordinatedIgnition7450) -> 0.0
                pumpLike && ageMin.isFinite() && ageMin <= 3.0 ->
                    18.0 + (if (liq in 1_000.0..25_000.0) 6.0 else 0.0)
                else -> 0.0
            },
            chart = "fresh_source_waiting_for_tape",
            entry = "low_conviction_metadata_only",
            exit = "default_until_flow_arrives",
            hold = 0.35,
            size = 0.35,
            tp = 0.75,
            lanes = setOf("PROJECT_SNIPER", "SHITCOIN"),
            tools = setOf("PUMP_FUN", "SNIPE_AGE_GATE"),
            reasons = listOf(
                "src=$src",
                "age=${if (ageMin.isFinite()) ageMin.toInt() else -1}m",
                "tape=not_yet_causal"
            )
        ))

        // Chart breakout: prior impulse + higher lows + volume ignition.
        add(Candidate(
            setup = Setup.CHART_BREAKOUT,
            score = if (launch7402?.tooLateForSnipe == true && ageMin <= 10.0) 0.0 else
                (if (move12 > 18.0) 18.0 else 0.0) + (if (higherLows >= 3) 18.0 else 0.0) +
                    ((volIgnition - 1.0) * 18.0).coerceIn(0.0, 24.0) + (if (nearHigh) 12.0 else 0.0) + conf * 0.18,
            chart = "breakout_continuation",
            entry = "breakout_confirmation",
            exit = "runner_trail_partial_delayed",
            hold = 1.75,
            size = 1.02,
            tp = 1.30,
            lanes = setOf("MOONSHOT", "QUALITY"),
            tools = setOf("SMART_CHART", "CHART_BREAKOUT", "PATTERN_CLASSIFIER", "VOLUME_IGNITION"),
            reasons = listOf("move12=${move12.toInt()}%", "higherLows=$higherLows", "volIgn=${"%.1f".format(volIgnition)}x", "nearHigh=$nearHigh")
        ))

        // Pullback reclaim: depth has a SWEET SPOT. A deeper crash is not a
        // better dip. Require visible reclaim/stabilisation before rewarding depth.
        val reclaimDepthScore7403 = when {
            pullbackFromHigh in 10.0..35.0 -> 24.0
            pullbackFromHigh > 35.0 && pullbackFromHigh <= 50.0 -> 12.0
            pullbackFromHigh > 50.0 -> 0.0
            else -> 0.0
        }
        val reclaimConfirmed7403 = wickBought >= 2 && bp >= 52.0 && move5 >= 0.0
        add(Candidate(
            setup = Setup.CHART_PULLBACK_RECLAIM,
            score = if (!reclaimConfirmed7403) 0.0 else
                reclaimDepthScore7403 + 18.0 + ((bp - 50.0) * 0.6).coerceIn(0.0, 18.0) +
                    move5.coerceIn(0.0, 12.0),
            chart = "pullback_reclaim",
            entry = "dip_reclaim_confirmation",
            exit = "reclaim_scalp_or_swing",
            hold = 1.25,
            size = 0.82,
            tp = 1.05,
            lanes = setOf("DIP_HUNTER", "QUALITY", "CYCLIC", "CASHGEN"),
            tools = setOf("PULLBACK_RECLAIM", "SMART_CHART", "REENTRY_RECOVERY", "DIP_RECLAIM"),
            reasons = listOf("pullback=${pullbackFromHigh.toInt()}%", "wickBought=$wickBought", "bp=${bp.toInt()}", "move5=${move5.toInt()}%")
        ))

        // Whale/mainstream crypto swing: high liq/mcap, quality trend, not micro-pump.
        val mainstream = liq >= 30_000.0 || mcap >= 1_000_000.0 || src.contains("COINGECKO") || src.contains("BIRDEYE")
        add(Candidate(
            setup = if (tt == ModeRouter.TradeType.WHALE_ACCUMULATION) Setup.WHALE_ACCUMULATION_HOLD else Setup.MAINSTREAM_CRYPTO_SWING,
            score = (if (mainstream) 30.0 else 0.0) + conf * 0.25 + (if (higherLows >= 3) 12.0 else 0.0) + (if (abs(move5) < 18.0) 8.0 else 0.0),
            chart = "quality_accumulation_swing",
            entry = "quality_pullback_or_accumulation",
            exit = "swing_hold_trailing",
            hold = 2.10,
            size = 0.96,
            tp = 1.22,
            lanes = setOf("QUALITY", "BLUECHIP"),
            tools = setOf("MAINSTREAM_CRYPTO", "WHALE", "QUALITY_DEPTH", "SWING"),
            reasons = listOf("mainstream=$mainstream", "liq=${liq.toInt()}", "mcap=${mcap.toInt()}", "type=$tt")
        ))

        // Exhaustion is exit/risk evidence, not a reason to open a new long.
        // Keep the setup visible to routing, but give it no bullish desk score.
        add(Candidate(
            setup = Setup.EXHAUSTION_QUICK_FLIP,
            score = 0.0,
            chart = "exhaustion_upper_wick",
            entry = "late_momentum_scalp_only",
            exit = "fast_bank_tight_trail",
            hold = 0.38,
            size = 0.58,
            tp = 0.72,
            lanes = setOf("EXPRESS", "MANIPULATED", "SHITCOIN"),
            tools = setOf("EXHAUSTION", "QUICK_FLIP", "UPPER_WICK", "SCALP"),
            reasons = listOf("upperWicks=$upperWicks", "move5=${move5.toInt()}%")
        ))

        // Volume ignition scalp: flow is waking up but not structurally diamond-hands yet.
        add(Candidate(
            setup = Setup.VOLUME_IGNITION_SCALP,
            score = when {
                launch7402?.tooLateForSnipe == true -> 0.0
                launch7402?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION ->
                    45.0 + (bp - 50.0).coerceAtLeast(0.0) * 0.5
                launch7402?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION ->
                    32.0 + (bp - 50.0).coerceAtLeast(0.0) * 0.4
                ageMin > 3.0 -> 0.0
                else -> ((volIgnition - 1.0) * 18.0).coerceIn(0.0, 28.0) +
                    (bp - 50.0).coerceAtLeast(0.0) * 0.5
            },
            chart = "volume_ignition",
            entry = "ignition_scalp",
            exit = "bank_first_strength_then_tail",
            hold = 0.75,
            size = 0.78,
            tp = 0.92,
            lanes = setOf("EXPRESS", "SHITCOIN"),
            tools = setOf("VOLUME_IGNITION", "ORDER_FLOW", "SCALP", "DEGEN_EXIT"),
            reasons = listOf("volIgn=${"%.1f".format(volIgnition)}x", "bp=${bp.toInt()}", "move5=${move5.toInt()}%")
        ))

        // Smart-wallet/copy follow: use existing whale/copy hints as style votes, never a separate executor.
        add(Candidate(
            setup = Setup.SMART_WALLET_COPY_FOLLOW,
            score = (if (tt == ModeRouter.TradeType.COPY_TRADE || tt == ModeRouter.TradeType.WHALE_ACCUMULATION) 45.0 else 0.0) +
                (if ((tt == ModeRouter.TradeType.COPY_TRADE || tt == ModeRouter.TradeType.WHALE_ACCUMULATION) && copyHint) 8.0 else 0.0) +
                conf * 0.12 + if (liq >= 5_000.0) 6.0 else 0.0,
            chart = "smart_wallet_follow",
            entry = "copy_follow_confirmed_flow",
            exit = "leader_like_partial_then_trail",
            hold = 1.70,
            size = 0.82,
            tp = 1.12,
            lanes = setOf("QUALITY", "MOONSHOT", "BLUECHIP"),
            tools = setOf("COPY_TRADE", "WHALE_WALLET", "INSIDER_COPY", "SMART_MONEY"),
            reasons = listOf("copyHint=$copyHint", "type=$tt", "toolHints=${toolHints.take(4).joinToString("+")}")
        ))

        // Narrative/social ignition: already has sentiment/narrative systems; route as a bounded style.
        add(Candidate(
            setup = Setup.NARRATIVE_SOCIAL_IGNITION,
            score = if (launch7402?.tooLateForSnipe == true && ageMin <= 10.0) 0.0 else {
                val sentimentConfirmed7403 = tt == ModeRouter.TradeType.SENTIMENT_IGNITION || (socialHint && sentimentScore > 10.0)
                if (!sentimentConfirmed7403) 0.0 else 32.0 + sentimentScore.coerceAtLeast(0.0) * 0.30 +
                    (bp - 50.0).coerceAtLeast(0.0) * 0.4
            },
            chart = "narrative_social_ignition",
            entry = "narrative_momentum_confirm",
            exit = "narrative_fade_quick_trail",
            hold = 0.85,
            size = 0.72,
            tp = 1.02,
            lanes = setOf("MANIPULATED", "SHITCOIN", "EXPRESS"),
            tools = setOf("NARRATIVE", "SOCIAL", "SENTIMENT", "DEX_SOCIAL"),
            reasons = listOf("socialHint=$socialHint", "sent=${sentimentScore.toInt()}", "src=$src")
        ))

        // Liquidity depth quality: use liquidity/depth/quality toolkit for safer larger-cap crypto setups.
        add(Candidate(
            setup = Setup.LIQUIDITY_DEPTH_QUALITY,
            score = (if (liq >= 50_000.0) 36.0 else 0.0) + (if (mcap >= 1_000_000.0) 14.0 else 0.0) + conf * 0.20 + (if (sellPressure <= 52.0) 8.0 else 0.0),
            chart = "liquidity_depth_quality",
            entry = "liquid_quality_accumulation",
            exit = "quality_depth_swing_trail",
            hold = 2.20,
            size = 1.05,
            tp = 1.18,
            lanes = setOf("QUALITY", "BLUECHIP", "TREASURY"),
            tools = setOf("LIQUIDITY_DEPTH", "QUALITY_DEPTH", "BLUECHIP", "MAINSTREAM_CRYPTO"),
            reasons = listOf("liq=${liq.toInt()}", "mcap=${mcap.toInt()}", "sell=${sellPressure.toInt()}")
        ))

        // V5.0.7541 — CASHGEN is a distinct active cashflow specialist, not a
        // synonym for TREASURY. Its designed pond is established/liquid flow with
        // quick profit capture. This creates a CASHGEN-native hypothesis so the
        // dedicated TreasuryScannerFeed affinity can actually become ownership.
        val cashflowEligible7541 = mainstream && liq >= 10_000.0 &&
            bp >= 50.0 && momentum >= 0.0 && sellPressure < 55.0
        add(Candidate(
            setup = Setup.CASHFLOW_SCALP,
            score = if (!cashflowEligible7541) 0.0 else
                34.0 + ((bp - 50.0) * 0.7).coerceIn(0.0, 18.0) +
                    momentum.coerceIn(0.0, 12.0) + conf * 0.12,
            chart = "cashflow_liquid_momentum",
            entry = "cashgen_liquid_flow_scalp",
            exit = "cashgen_quick_bank_trail",
            hold = 0.55,
            size = 0.82,
            tp = 0.78,
            lanes = setOf("CASHGEN"),
            tools = setOf("LIQUIDITY_DEPTH", "ORDER_FLOW", "SCALP", "TREASURY_FEED"),
            reasons = listOf("cashgen=true", "liq=${liq.toInt()}", "bp=${bp.toInt()}", "mom=${momentum.toInt()}")
        ))

        // V5.0.7541 — CYCLIC is the opportunistic compounder. It should receive
        // a hypothesis whenever the common spine sees a sellable positive-trend
        // opportunity; the ring/exit engine remains the lane-specific authority.
        val cyclicEligible7541 = liq >= 5_000.0 && last > 0.0 &&
            (v3 >= 28.0 || conf >= 55.0) && sellPressure < 58.0 &&
            (momentum >= 0.0 || higherLows >= 2)
        add(Candidate(
            setup = Setup.CYCLIC_COMPOUND,
            score = if (!cyclicEligible7541) 0.0 else
                30.0 + (v3.coerceIn(0.0, 60.0) * 0.35) +
                    (conf.coerceIn(0.0, 100.0) * 0.15) +
                    momentum.coerceIn(0.0, 12.0),
            chart = "cyclic_opportunity_compound",
            entry = "cyclic_best_available_positive_edge",
            exit = "cyclic_lane_compound_exit",
            hold = 1.35,
            size = 0.72,
            tp = 1.18,
            lanes = setOf("CYCLIC"),
            tools = setOf("CYCLIC", "COMPOUND", "V3", "SELLABILITY"),
            reasons = listOf("cyclic=true", "v3=${v3.toInt()}", "conf=${conf.toInt()}", "liq=${liq.toInt()}")
        ))

        // Panic reversion / recovery requires a RECLAIM. One wick while still
        // falling is not a bounce, and >55% collapse is catastrophe territory.
        val panicReclaim7403 = pullbackFromHigh in 25.0..55.0 && wickBought >= 2 &&
            move5 > 1.0 && bp >= 52.0 && sellPressure < 52.0
        add(Candidate(
            setup = if (laneHints.contains("DIP_HUNTER")) Setup.REENTRY_RECOVERY else Setup.PANIC_REVERSION_BOUNCE,
            score = if (!panicReclaim7403) 0.0 else
                38.0 + move5.coerceIn(0.0, 12.0) + ((bp - 50.0) * 0.5).coerceIn(0.0, 15.0),
            chart = "panic_reversion_bounce",
            entry = "panic_reclaim_probe",
            exit = "bounce_bank_or_recovery_trail",
            hold = 1.05,
            size = 0.62,
            tp = 0.95,
            lanes = setOf("DIP_HUNTER", "TREASURY", "QUALITY", "CYCLIC", "CASHGEN"),
            tools = setOf("PANIC_REVERSION", "REENTRY_RECOVERY", "DIP_RECLAIM", "PATTERN_BACKTESTER"),
            reasons = listOf("pullback=${pullbackFromHigh.toInt()}%", "wickBought=$wickBought", "move5=${move5.toInt()}%")
        ))

        // Arb/flow imbalance: consume existing arb/order-flow names as a routing style, not a new venue executor.
        add(Candidate(
            setup = Setup.ARB_FLOW_IMBALANCE,
            score = (if (arbHint) 42.0 else 0.0) + momentum.coerceAtLeast(0.0).coerceAtMost(30.0) + ((volIgnition - 1.0) * 10.0).coerceIn(0.0, 15.0),
            chart = "arb_flow_imbalance",
            entry = "flow_imbalance_probe",
            exit = "fast_mean_or_momentum_exit",
            hold = 0.55,
            size = 0.60,
            tp = 0.78,
            lanes = setOf("EXPRESS", "SHITCOIN"),
            tools = setOf("ARB", "FLOW_IMBALANCE", "VENUE_LAG", "ORDER_FLOW"),
            reasons = listOf("arbHint=$arbHint", "mom=${momentum.toInt()}", "volIgn=${"%.1f".format(volIgnition)}x")
        ))

        // MEV / hostile microstructure is risk metadata, never bullish alpha.
        // A defensive-probe label must not win a real-money entry election merely
        // because sell pressure and volatility are high.
        add(Candidate(
            setup = if (mevRisk) Setup.MEV_PROTECTED_ENTRY else Setup.REGIME_DEFENSIVE_PROBE,
            score = 0.0,
            chart = "mev_or_hostile_microstructure",
            entry = "protected_probe_only",
            exit = "tight_invalidated_exit",
            hold = 0.50,
            size = 0.42,
            tp = 0.75,
            lanes = setOf("SHITCOIN", "EXPRESS", "MANIPULATED"),
            tools = setOf("MEV_PROTECTION", "JITO", "DEFENSIVE_PROBE", "TOXIC_GUARD"),
            reasons = listOf("mevRisk=$mevRisk", "upperWicks=$upperWicks", "sell=${sellPressure.toInt()}", "vol=${volatility.toInt()}")
        ))

        val internetRiskMode = try { InternetEdgeDesk.snapshot().riskMode } catch (_: Throwable) { "unknown" }
        // V5.0.4052 — report showed InternetEdge riskMode=hostile while degen_micro_snipe
        // and fresh_pool_momentum still dominated setup selection. Treat HOSTILE as a
        // defensive/risk-off state and combine it with DUMP regime bias before choosing.
        val defensiveRisk = internetRiskMode.equals("risk_off", ignoreCase = true) || internetRiskMode.equals("hostile", ignoreCase = true)
        fun causalScore(c: Candidate): Double = c.score + InternetEdgeDesk.setupScoreBias(c.setup.name) +
            regimeSetupBias(c.setup, regime) + riskOffSetupBias(c.setup, defensiveRisk)
        val bestByDesk = linkedMapOf<String, Pair<Candidate, Double>>()
        candidates.forEach { candidate ->
            val score = causalScore(candidate).coerceIn(0.0, 100.0)
            if (score >= 25.0) candidate.lanes.forEach { rawLane ->
                val lane = rawLane.uppercase()
                val prior = bestByDesk[lane]
                if (prior == null || score > prior.second) bestByDesk[lane] = candidate to score
            }
        }
        val deskHypotheses = bestByDesk.mapValuesTo(linkedMapOf()) { (lane, pair) ->
            val c = pair.first
            DeskHypothesis(
                lane = lane, setup = c.setup, conviction = pair.second,
                entryStyle = c.entry, exitStyle = c.exit,
                holdMult = c.hold.coerceIn(0.30, 3.50), sizeMult = c.size.coerceIn(0.30, 1.15),
                tpMult = c.tp.coerceIn(0.60, 1.70), reason = c.reasons.take(4).joinToString(";"),
            )
        }

        nativeBrains7542.opinions.forEach { (lane, native) ->
            if (lane == "CORE" || !native.authoritative) return@forEach
            if (!native.eligible) {
                if (deskHypotheses.remove(lane) != null) try { PipelineHealthCollector.labelInc("NATIVE_BRAIN_VETO_APPLIED_7542_$lane") } catch (_: Throwable) {}
            } else {
                val setup = try { Setup.valueOf(native.setup) } catch (_: Throwable) { Setup.NONE }
                deskHypotheses[lane] = DeskHypothesis(
                    lane, setup, maxOf(native.score,native.confidence).toDouble().coerceIn(25.0,100.0),
                    native.entryStyle,native.exitStyle,native.holdMult.coerceIn(0.30,3.50),
                    native.sizeMult.coerceIn(0.30,1.15),native.tpMult.coerceIn(0.60,1.70),
                    "nativeBrain7542;${native.reason}"
                )
                try { PipelineHealthCollector.labelInc("NATIVE_BRAIN_HYPOTHESIS_APPLIED_7542_$lane") } catch (_: Throwable) {}
            }
        }
        // V5.0.7796 — CORE must exist BEFORE hunter-claim arbitration.
        // Previously its native hypothesis was appended after the claim block, so a
        // MARKET_HUNT_CORE claim could never find deskHypotheses["CORE"] and therefore
        // could never influence ownership. CORE remains an ensemble: its native brain
        // must still mark it authoritative+eligible before the hunter can tie-break.
        nativeBrains7542.opinions["CORE"]?.takeIf { it.authoritative && it.eligible }?.let { native ->
            val setup = try { Setup.valueOf(native.setup) } catch (_: Throwable) { Setup.NONE }
            deskHypotheses["CORE"] = DeskHypothesis(
                "CORE", setup, maxOf(native.score,native.confidence).toDouble().coerceIn(25.0,100.0),
                native.entryStyle,native.exitStyle,native.holdMult.coerceIn(0.30,3.50),
                native.sizeMult.coerceIn(0.30,1.15),native.tpMult.coerceIn(0.60,1.70),
                "nativeBrain7542;${native.reason}"
            )
        }

        // V5.0.7803 — specialist hunters own RESIDENT candidate books.
        // A mint may be watched by several lanes at once. Discovery does not elect
        // an owner. Each resident hunter can only nudge its OWN already-qualified
        // hypothesis; no hunter can evict another lane's candidate.
        val candidateVersion7622 = LaneExecutionCoordinator.candidateVersionFor(ts.mint)
        val residentHunterLanes7803 = try {
            com.lifecyclebot.engine.market.LaneHunter7297.claimsFor7803(
                ts.mint,
                maxOf(ts.lastMcap, ts.lastFdv).takeIf { it.isFinite() } ?: 0.0,
            )
        } catch (_: Throwable) { emptySet() }

        residentHunterLanes7803.forEach { claimLaneRaw ->
            val claimLane = claimLaneRaw.uppercase()
                .replace("BLUE_CHIP", "BLUECHIP")
                .replace("SHITCOIN_EXPRESS", "EXPRESS")
            val existing = deskHypotheses[claimLane] ?: return@forEach
            val identityEligible = try {
                LaneEntryContract6342.isLaneIdentityEligible7252(ts, claimLane)
            } catch (_: Throwable) { false }
            if (identityEligible) {
                deskHypotheses[claimLane] = existing.copy(
                    conviction = (existing.conviction + 4.0).coerceAtMost(100.0),
                    reason = existing.reason + ";residentHunter7803=" + claimLane,
                )
                try { PipelineHealthCollector.labelInc("LANE_RESIDENT_HUNTER_TIEBREAK_7803_" + claimLane) } catch (_: Throwable) {}
            }
        }

        // V5.0.7803 audit — QUALIFIED and READY are deliberately different.
        // Generic toolkit hypotheses may qualify a specialist for continued
        // observation, but only that specialist's current authoritative native
        // evaluator can publish a simultaneous pre-authorizer READY proposal.
        // This prevents both generic-hypothesis ownership and stale READY state.
        val nativeReady7803 = nativeBrains7542.opinions.values
            .filter { it.authoritative && it.eligible }
            .associateBy { it.lane.uppercase() }
        val nativeRefused7803 = nativeBrains7542.opinions.values
            .filter { it.authoritative && !it.eligible }
            .map { it.lane.uppercase() }
            .toSet()

        // Advance each resident state independently. Crypto deliberately reuses
        // this intelligence sheet, but its candidates must never contaminate the
        // Meme Trader's resident books. Domain identity is explicit here.
        val cryptoDesk7803 = ts.lastPriceSource.equals("CRYPTO_ALT_DESK_7391", true) ||
            ts.source.contains("CRYPTO", true)
        deskHypotheses.values.forEach { h ->
            try {
                if (cryptoDesk7803) {
                    com.lifecyclebot.perps.CryptoStrategyCandidateBooks7803.qualify(
                        assetKey = ts.mint,
                        symbol = ts.symbol,
                        strategy = "DESK_" + h.lane,
                        candidateVersion = candidateVersion7622,
                        score = h.conviction.toInt(),
                        confidence = h.conviction.toInt(),
                        reason = h.reason,
                    )
                } else {
                    // Keep useful generic/native intelligence resident as QUALIFIED.
                    // READY is stricter: the lane's own authoritative evaluator must
                    // currently say it would enter this token.
                    com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.markQualified(
                        lane = h.lane,
                        mint = ts.mint,
                        symbol = ts.symbol,
                        candidateVersion = candidateVersion7622,
                        conviction = h.conviction,
                        reason = h.reason,
                    )
                    nativeReady7803[h.lane.uppercase()]?.let { native ->
                        com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.markReady(
                            lane = h.lane,
                            mint = ts.mint,
                            symbol = ts.symbol,
                            candidateVersion = candidateVersion7622,
                            score = native.score,
                            confidence = native.confidence.toDouble(),
                            reason = "NATIVE_SPECIALIST_READY_7803;" + native.reason,
                        )
                    }
                }
            } catch (_: Throwable) {}
        }

        // A native refusal must invalidate a READY proposal from an earlier
        // refresh in the same candidate generation. Otherwise READY_TTL could
        // let a lane keep competing for up to 45 seconds after its own brain
        // changed to should-not-enter.
        if (!cryptoDesk7803) nativeRefused7803.forEach { lane ->
            try {
                com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.markLost(
                    lane, ts.mint, candidateVersion7622, "NATIVE_SPECIALIST_REFUSED_7803",
                )
            } catch (_: Throwable) {}
        }

        // Candidate-specific convictions are carried into the meme execution
        // coordinator only for the Meme Trader. Crypto performs its own READY
        // strategy reduction before entering CRYPTO_ALT canonical execution.
        if (!cryptoDesk7803) try {
            LaneExecutionCoordinator.registerQualifiedContest7803(
                ts.mint,
                candidateVersion7622,
                deskHypotheses.mapValues { it.value.conviction },
            )
        } catch (_: Throwable) {}
        // V5.0.7346 / 7622 — the causal identity uses the same pinned generation
        // as the qualified specialist contest above.
        val causalId6647 = "${ts.mint}:$candidateVersion7622"
        deskHypotheses.values.forEach { h ->
            recordDeskStage(h.lane, "POOL", causalId6647)
            recordDeskStage(h.lane, "QUALIFIED", causalId6647)
        }
        // V5.0.6609 §RESTORE_SPECIALIST_LIVENESS (operator directive Feb 2026:
        //   "Every enabled specialist: taskAlive=true, poolAlive=true,
        //   discoveryAlive=true. No specialist remains DEAD merely because
        //   its current learned edge is poor.").
        //   Prior behaviour: only desks that WON hypothesis election got
        //   POOL/QUALIFIED credit — so DIP_HUNTER / CYCLIC / CORE / TREASURY
        //   / CASHGEN reported taskAlive=false / poolAlive=false /
        //   discoveryAlive=false and status=DEAD despite hundreds of
        //   candidates flowing through the pipeline. Every meme candidate
        //   IS in the observational pool of every configured meme desk
        //   even when that desk didn't win the hypothesis contest. Bump
        //   POOL for all configured desks so operator's specialist-
        //   liveness invariant reflects reality; QUALIFIED remains
        //   winner-only (that carries a stricter meaning — "the desk
        //   produced an actionable hypothesis").
        // No fabricated pool/liveness credit for desks that did not observe
        // this candidate. Runtime liveness is owned by registered jobs below.
        val best = candidates.maxByOrNull { causalScore(it) } ?: Candidate(
            setup = Setup.NONE, score = 0.0, chart = "none", entry = "none", exit = "default", hold = 1.0, size = 1.0, tp = 1.0,
            lanes = emptySet(), tools = emptySet(), reasons = listOf("no_toolkit_setup")
        )
        val finalBias = InternetEdgeDesk.setupScoreBias(best.setup.name) + regimeSetupBias(best.setup, regime) + riskOffSetupBias(best.setup, defensiveRisk)
        val boundedConf = (best.score + finalBias).coerceIn(0.0, 100.0)
        return Sheet(
            setup = if (boundedConf >= 25.0) best.setup else Setup.NONE,
            confidence = boundedConf,
            chartPattern = best.chart,
            entryStyle = best.entry,
            exitStyle = best.exit,
            holdMult = best.hold.coerceIn(0.30, 3.50),
            sizeMult = best.size.coerceIn(0.30, 1.15),
            tpMult = best.tp.coerceIn(0.60, 1.70),
            laneVotes = deskHypotheses.keys,
            deskHypotheses = deskHypotheses,
            toolVotes = best.tools,
            reasons = best.reasons + listOf("internetBias=${InternetEdgeDesk.setupScoreBias(best.setup.name).toInt()}", "regimeBias=${regimeSetupBias(best.setup, regime).toInt()}", "riskOffBias=${riskOffSetupBias(best.setup, defensiveRisk).toInt()}", "regime=${regime?.regime ?: "unknown"}", internetRiskMode),
        )
    }

    fun recordDeskStage(lane: String, stage: String, eventId: String = "") {
        // V5.0.7807 — fold every lane alias through the ONE lane authority
        // (DIP/SNIPER/MANIP/CASH_GEN/MOON_SHOT/... had their own funnel keys),
        // then keep EXEC/OPEN on the owning lane of the ticket they execute
        // (Field Manual L337: one stamped evidence snapshot per candidate).
        val l = ticketLineageLane7807(
            com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(
                lane.uppercase().replace("BLUE_CHIP", "BLUECHIP").replace("SHITCOIN_EXPRESS", "EXPRESS"),
            ),
            stage.uppercase(), eventId,
        )
        if (l.isBlank()) return
        val st = stage.uppercase()
        if (eventId.isBlank()) {
            try {
                PipelineHealthCollector.labelInc("SPECIALIST_STAGE_BLANK_CAUSAL_ID_REJECTED_6647")
                ForensicLogger.lifecycle("SPECIALIST_STAGE_BLANK_CAUSAL_ID_REJECTED_6647", "lane=$l stage=$st")
            } catch (_: Throwable) {}
            return
        }
        // V5.0.7214 §EVERY_DROP_ON_THIS_PATH_NOW_HAS_A_NAME.
        //
        // This function is the single funnel entry point, and it has three ways
        // to discard a stage: a blank causal id (counted, above), this
        // idempotency dedupe (counted NOWHERE until now), and an unresolvable
        // record key further down (counted). Two of the three were visible.
        //
        // That mattered on the 5.0.7212 snapshot, where markN=0 sizedN=0
        // ticketN=0 across twelve lanes had at least four candidate
        // explanations and the report could not separate them. It turned out
        // the labelled-counter block prints only the top 401 and hid "+820
        // more", so the counters that DID exist were invisible anyway — which
        // is why the report now pins this family by prefix rather than hoping
        // it ranks.
        //
        // `offered` is the honest denominator: how many times a producer tried
        // to stamp this lane and stage. offered > 0 with a zero funnel count
        // means the stamp was made and dropped; offered == 0 means no producer
        // ran. Those two need different fixes and were indistinguishable.
        try { PipelineHealthCollector.labelInc("DESK_STAGE_OFFERED_7214_$st") } catch (_: Throwable) {}
        if (!firstDeskStage7481("$l|$st|$eventId")) {
            try { PipelineHealthCollector.labelInc("DESK_STAGE_DEDUPED_7214_$st") } catch (_: Throwable) {}
            return
        }
        deskStageCounts6599.computeIfAbsent("$l|$st") { java.util.concurrent.atomic.AtomicLong(0L) }.incrementAndGet()
        try { com.lifecyclebot.engine.truth.SpecialistRuntimeRegistry6647.offer(l, st, eventId) } catch (_: Throwable) {}
        // V5.0.6625 — SINGLE SOURCE FAN-OUT into the P2/P3/P4/P5 receivers.
        // Every specialist stage change goes through this one function, so
        // wiring here means the receivers cannot drift apart from the desk
        // counters (P5 SPECIALIST_CAUSAL_FUNNEL invariant) and no callsite
        // can be forgotten. See MemeExecutionFunnelReceivers6625.kt.
        try { fanOutToReceivers6625(l, st, eventId) } catch (_: Throwable) {}

        // V5.0.7459 — revive RuntimeTune6833 §7 from the SAME deduped causal
        // stage stream. These counters existed for SHITCOIN sized→ticket→exec
        // choke diagnosis but had zero production callers, so their status
        // line could never carry trustworthy runtime evidence.
        try {
            when (st) {
                "SIZED_EXECUTABLE" -> com.lifecyclebot.engine.truth.RuntimeTune6833.recordSized6833(l)
                "TICKET" -> com.lifecyclebot.engine.truth.RuntimeTune6833.recordTicket6833(l)
                "EXEC" -> com.lifecyclebot.engine.truth.RuntimeTune6833.recordExec6833(l)
            }
            if (l == "SHITCOIN" && st in setOf("SIZED_EXECUTABLE", "TICKET", "EXEC")) {
                com.lifecyclebot.engine.truth.RuntimeTune6833.evaluateHandoffChoke6833()
            }
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.6625 — fan-out helper. Kept private so no other caller can
     * bypass the desk-stage authority path. `eventId` is intentionally
     * used only as an idempotency key: parsing structure from it would
     * couple the receivers to callsite formatting.
     *
     * V5.0.6626 §RUNTIME_LOOP_UNCHOKE §3 — 500ms per-key debounce.
     * Even though recordDeskStage already dedupes on (lane|stage|eventId)
     * via `deskStageOnce7481`, the fan-out itself walks five receivers
     * per call. Under a hot burst that dedupe cache can be reset or
     * skipped by a caller passing an empty eventId; the debounce here
     * guarantees the receivers themselves see at most one fan-out per
     * 500ms per key, protecting the Main thread even in pathological
     * cases. The receivers are idempotent so this is behaviour-preserving.
     */
    private val fanOutDebounce6626 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val FAN_OUT_DEBOUNCE_MS_6626 = 500L
    private fun fanOutToReceivers6625(lane: String, stage: String, eventId: String) {
        // V5.0.6626 §RUNTIME_LOOP_UNCHOKE §3 — 500ms per-key debounce.
        val nowMs6626 = System.currentTimeMillis()
        val debounceKey6626 = "$lane|$stage|$eventId"
        val prev6626 = fanOutDebounce6626.put(debounceKey6626, nowMs6626)
        if (prev6626 != null && nowMs6626 - prev6626 < FAN_OUT_DEBOUNCE_MS_6626) {
            try {
                com.lifecyclebot.engine.truth.HotLabelCoalescer6626
                    .inc6626("MEME_FANOUT_DEBOUNCED_6626")
            } catch (_: Throwable) {}
            return
        }
        // Opportunistic garbage-collect: keep the debounce map bounded.
        if (fanOutDebounce6626.size > 4096) {
            try {
                val cutoff6626 = nowMs6626 - (FAN_OUT_DEBOUNCE_MS_6626 * 4L)
                fanOutDebounce6626.entries.removeIf { it.value < cutoff6626 }
            } catch (_: Throwable) {}
        }
        // Derive a stable attemptId for the backlog. Callers pass either a
        // positionId (":reason" suffixed) or an attemptId; strip the suffix
        // so BUY_INTENT / TICKET / EXEC etc. all match on the same key.
        val attemptId = eventId
        val parts6647 = eventId.split(':')
        val canonicalAttempt6647 = parts6647.size >= 7 && parts6647[1].uppercase() in setOf("PAPER", "LIVE")
        val positionEvent6647 = parts6647.size >= 2 && parts6647[0].uppercase() in setOf("PAPER", "LIVE")
        val mint = when {
            canonicalAttempt6647 -> parts6647[2]
            positionEvent6647 -> parts6647[1]
            parts6647.size >= 2 && parts6647[1].toLongOrNull() != null -> parts6647[0]
            else -> ""
        }

        // V5.0.7683 — terminal position events are not candidate-generation
        // events. The 7677 runtime showed 184 generic unresolved-id drops and
        // they decomposed exactly to SELL_ATTEMPT=73 + SELL_CONFIRMED=73 +
        // FINALIZED=38. Reconstructing those from the current scanner version
        // risks attaching an old/restored close to a newer candidate.
        //
        // Prefer the exact OPEN causal record that is still awaiting terminal
        // finality. If it does not exist (legacy/restored/evicted entry), keep
        // the terminal event forensic-only instead of poisoning the generic
        // entry-lineage unresolved counter.
        // V5.0.7809 — EXIT_TRIGGER is a position event too: its required
        // predecessor is the OPEN record, not the newest scanner record for the
        // mint (latestCandidateVersion6647 picked a newer, never-opened
        // candidate and counted EXIT there, or dropped it as an unresolved id
        // when no record/version existed for a restored position).
        val terminalPositionStage7683 =
            positionEvent6647 && stage in setOf("SELL_ATTEMPT", "SELL_CONFIRMED", "FINALIZED", "EXIT_TRIGGER")
        val terminalOpenKey7683 = if (terminalPositionStage7683 && mint.isNotBlank()) try {
            com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
                .latestUnfinalizedOpenKey6713(mint, lane)
        } catch (_: Throwable) { null } else null
        // V5.0.7471 — downstream specialist stages must follow the immutable
        // sealed execution intent, not whichever candidateVersion the callback
        // happens to carry after scanner/election generation advances.
        val parsedCandidateVersion7471 = when {
            canonicalAttempt6647 -> parts6647[5].toLongOrNull() ?: 0L
            positionEvent6647 -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
                .latestCandidateVersion6647(mint, lane)
                ?: LaneExecutionCoordinator.candidateVersionFor(mint)
            parts6647.size >= 2 -> parts6647[1].toLongOrNull() ?: 0L
            else -> 0L
        }
        val resolvedMode7471 = when {
            canonicalAttempt6647 -> parts6647[1].uppercase()
            positionEvent6647 -> parts6647[0].uppercase()
            else -> try { RuntimeModeAuthority.authority().name } catch (_: Throwable) { "PAPER" }
        }
        // V5.0.7809 §THE_ATTEMPT_OWNS_ITS_VERSION — resolution order is now
        //   1. the attempt's OWN immutable ticket (executionTickets[attemptId]),
        //   2. the ticket lineage bound when TICKET was stamped on this attempt
        //      (survives terminalize/expiry; any stage, not only EXEC/OPEN),
        //   3. the exact-version active intent, then the newest SAME-LANE intent.
        // Before, (3) ran first and its any-version fallback picked the newest
        // intent across ALL lanes before filtering by lane, so a newer version
        // sealed by another specialist (or a re-sealed newer version of this
        // lane) pulled SIZE/TICKET/EXEC of one attempt onto different records:
        // SHITCOIN raw TICKET suppressed (TICKET_CHOKED), MOONSHOT/MANIPULATED
        // EXEC orphaned (EXEC_CHOKED). Every source is a real sealed object for
        // this exact mint/mode/lane — nothing is inferred (Field Manual L337:
        // one stamped evidence snapshot per candidate, shared by every stage).
        val sealedIntent7471 = if (mint.isNotBlank()) try {
            val canonicalLane7809 = com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(lane)
            fun sameAttemptScope7809(candidate7809: ExecutableOpenGate.ExecutionIntent?): ExecutableOpenGate.ExecutionIntent? =
                candidate7809?.takeIf { intent ->
                    intent.mint == mint && intent.mode.equals(resolvedMode7471, true) &&
                        com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(intent.canonicalLane) == canonicalLane7809
                }
            val ownTicket7809 = if (attemptId.isNotBlank()) sameAttemptScope7809(ExecutableOpenGate.ticketForAttempt(attemptId)) else null
            val exact = if (ownTicket7809 == null && parsedCandidateVersion7471 > 0L)
                sameAttemptScope7809(ExecutableOpenGate.activeExecutionIntent6519(resolvedMode7471, mint, parsedCandidateVersion7471))
            else null
            ownTicket7809 ?: exact ?: ExecutableOpenGate.activeExecutionIntentForLane7809(resolvedMode7471, mint, canonicalLane7809)
        } catch (_: Throwable) { null } else null
        // V5.0.7807/7809 — a stage on an attemptId a real ticket was stamped on
        // keeps that ticket's sealed version even when the intent is gone or a
        // newer same-lane intent exists. Not used when (1) already resolved the
        // attempt's own ticket.
        val boundLineage7807 = if (sealedIntent7471?.attemptId != attemptId) {
            attemptTicketLineage7809(attemptId)?.takeIf { it.lane == lane && it.mode.equals(resolvedMode7471, true) }
        } else null
        val candidateVersion6647 = if (boundLineage7807 != null) boundLineage7807.candidateVersion
            else sealedIntent7471?.candidateVersion ?: parsedCandidateVersion7471
        if (stage == "TICKET" && sealedIntent7471 != null && boundLineage7807 == null) {
            bindTicketLineage7807(attemptId, sealedIntent7471.candidateVersion, lane, resolvedMode7471)
        }
        // V5.0.7790 — executable size is a lifecycle stage, not advisory math.
        // If no immutable intent owns this exact mode/mint/version/lane, do not
        // feed SIZED_EXECUTABLE into the causal funnel. Sizing still occurred;
        // only the false execution-progress stamp is withheld.
        // V5.0.7809 — an attempt with a bound ticket lineage WAS sealed; its
        // size is not advisory even after the intent was terminalized.
        if (stage == "SIZED_EXECUTABLE" && sealedIntent7471 == null && boundLineage7807 == null) {
            try {
                PipelineHealthCollector.labelInc("ADVISORY_SIZE_STAMP_WITHHELD_7790")
                ForensicLogger.lifecycle("ADVISORY_SIZE_STAMP_WITHHELD_7790",
                    "mint=${mint.take(10)} lane=$lane mode=$resolvedMode7471 version=$parsedCandidateVersion7471 eventId=${eventId.take(80)}")
            } catch (_: Throwable) {}
            return
        }
        if (sealedIntent7471 != null && parsedCandidateVersion7471 > 0L &&
            sealedIntent7471.candidateVersion != parsedCandidateVersion7471
        ) try {
            PipelineHealthCollector.labelInc("SPECIALIST_CAUSAL_SEALED_VERSION_REBOUND_7471")
            PipelineHealthCollector.labelInc("SPECIALIST_CAUSAL_SEALED_VERSION_REBOUND_7471_$lane")
            ForensicLogger.lifecycle(
                "SPECIALIST_CAUSAL_SEALED_VERSION_REBOUND_7471",
                "mint=${mint.take(10)} lane=$lane mode=$resolvedMode7471 callbackVersion=$parsedCandidateVersion7471 sealedVersion=${sealedIntent7471.candidateVersion} action=bind_downstream_stage_to_immutable_intent",
            )
        } catch (_: Throwable) {}
        val priorCausalKey6647 = if (mint.isNotBlank())
            com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.latestKey6647(mint, lane)
        else null
        val causalIdentity6647 = if (mint.isNotBlank() && candidateVersion6647 > 0L)
            "$mint:$candidateVersion6647:$lane" else attemptId

        // P3 — pending intent backlog drainage.
        when (stage) {
            "BUY_INTENT" -> com.lifecyclebot.engine.truth.PendingIntentBacklog6625
                .record6625(causalIdentity6647, lane, mint)
            "TICKET", "EXEC", "SELL_CONFIRMED", "FINALIZED", "SIZE_REJECT",
            "MARK_REJECT", "FDG_BLOCK", "AUTH_REJECT", "SUPERSEDED", "STALE" -> com.lifecyclebot.engine.truth.PendingIntentBacklog6625
                .consume6625(causalIdentity6647, stage)
        }

        // P2 — EXPRESS handoff funnel. Stamp the exact hop counters the
        // operator forensic requested. Non-EXPRESS lanes are ignored so
        // BLUECHIP/CORE/MOONSHOT counters don't drift into these.
        if (lane == "EXPRESS") {
            when (stage) {
                "BUY_INTENT" -> com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onIntentSeen6625(causalIdentity6647)
                "MARK_READY" -> com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onMarkAcquisition6625(causalIdentity6647, true)
                "MARK_REJECT" -> com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onMarkAcquisition6625(causalIdentity6647, false, "MARK_REJECT")
                "SIZED_EXECUTABLE" -> {
                    com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onSizingBridgeEntry6625(causalIdentity6647)
                    com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onSizingResult6625(causalIdentity6647, sizedSol = 1.0)
                }
                "SIZE_REJECT" -> {
                    com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onSizingBridgeEntry6625(causalIdentity6647)
                    com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onSizingResult6625(causalIdentity6647, sizedSol = 0.0, reason = "SIZE_REJECT")
                }
                "TICKET" -> com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onTicketSealed6625(causalIdentity6647)
                "EXEC" -> com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onExecuted6625(causalIdentity6647)
                "AUTH_REJECT", "SUPERSEDED", "STALE", "FDG_BLOCK" ->
                    com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onTerminalized6625(causalIdentity6647, stage)
            }
        }

        // P4 — MOONSHOT exit transaction continuity. Retries with the same
        // positionId (SELL_ATTEMPT re-fires) resume the same tx id instead
        // of creating competing states; SELL_CONFIRMED terminates it.
        if (lane == "MOONSHOT") {
            when (stage) {
                "SELL_ATTEMPT" -> com.lifecyclebot.engine.truth.MoonshotExitTransaction6625
                    .beginOrResumeTransaction6625(positionId = attemptId, txIdIfNew = attemptId)
                "SELL_CONFIRMED", "FINALIZED" -> com.lifecyclebot.engine.truth.MoonshotExitTransaction6625
                    .terminate6625(positionId = attemptId)
            }
        }

        // P5 — SpecialistCausalFunnel keyed by the SAME record every stage
        // reads/writes from, so it becomes structurally impossible to print
        // impossible combos like fdgAllow=0 exec=113 for the same intentId.
        val causalStage = when (stage) {
            "POOL" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.DISCOVER
            "QUALIFIED" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.QUALIFY
            "OWNER_SELECTED" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.OWNER
            "BUY_INTENT" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.INTENT
            "FDG_ALLOW", "FDG_BLOCK" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.FDG
            "MARK_READY", "MARK_REJECT" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.MARK
            "SIZED_EXECUTABLE", "SIZE_REJECT" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.SIZE
            "TICKET" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.TICKET
            "EXEC" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.EXEC
            "POSITION_OPENED" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.OPEN
            "EXIT_TRIGGER" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.EXIT
            "SELL_ATTEMPT", "SELL_CONFIRMED" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.SELL
            "FINALIZED" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.FINALIZE
            "LEARNING" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.LEARN
            else -> null
        }
        if (causalStage != null) {
            if (terminalPositionStage7683) {
                if (terminalOpenKey7683 != null) {
                    com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
                        .stamp6625(terminalOpenKey7683, causalStage, stage)
                    try {
                        PipelineHealthCollector.labelInc("SPECIALIST_TERMINAL_POSITION_REBOUND_7683")
                        PipelineHealthCollector.labelInc("SPECIALIST_TERMINAL_POSITION_REBOUND_7683_$stage")
                    } catch (_: Throwable) {}
                } else {
                    // Canonical finality/learning may still be published by the
                    // finalized bus. This counter means only that the transient
                    // specialist causal OPEN record is unavailable.
                    try {
                        PipelineHealthCollector.labelInc("SPECIALIST_TERMINAL_NO_OPEN_CAUSAL_RECORD_7683")
                        PipelineHealthCollector.labelInc("SPECIALIST_TERMINAL_NO_OPEN_CAUSAL_RECORD_7683_$stage")
                    } catch (_: Throwable) {}
                }
            } else if (mint.isNotBlank() && candidateVersion6647 > 0L) {
                val expectedIntentId6647 = "$mint:$candidateVersion6647:$lane"
                val resolvedMode6858 = resolvedMode7471
                // V5.0.6858 §THE_CANONICAL_STAGES_ORPHANED_THEMSELVES — the reuse
                // test used to carry `!canonicalAttempt6647`, so a stage arriving on
                // a canonical attemptId was FORBIDDEN from joining the record its own
                // earlier stages had already written. The key includes runId, and the
                // two paths derive it differently: a canonical attempt uses
                // parts6647[0] (the attempt's own run id) while everything else uses
                // BotRuntimeController.currentGeneration(). Same mint, same candidate
                // version, same lane, same mode — different runId, therefore a
                // different keyString, therefore a brand-new Record holding SIZE with
                // no DISCOVER, no INTENT and no MARK_READY on it.
                //
                // That is exactly the shape laneSnapshot6647 counts as phantom:
                //   `if (executableSize && (DISCOVER !in stages || INTENT !in stages
                //     || !markReady)) phantom++`
                // and phantomSizedOnly != 0 is a hard failure in
                // ExecutionSpineAcceptance6647. The CI runtime witness has been
                // failing on PHANTOM_SIZED_ONLY with zero committed paper buy tickets
                // while the same log shows PAPER_SELL_OK=9 — the pipeline was working
                // and the telemetry was reporting it as two disconnected halves.
                // It also defeats this module's stated purpose, "keyed by the SAME
                // record every stage reads/writes from, so it becomes structurally
                // impossible to print impossible combos like fdgAllow=0 exec=113 for
                // the same intentId".
                //
                // Reuse is now keyed on causal identity rather than on which code path
                // happened to report the stage: same intentId AND same mode. The mode
                // check is what the canonicalAttempt guard was really protecting — a
                // PAPER and a LIVE attempt on the same mint and version must never
                // merge — and it is now enforced directly instead of by proxy.
                val exactCausalKey7524 = try {
                    com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
                        .keyForIntent7524(mint, lane, expectedIntentId6647, resolvedMode6858)
                } catch (_: Throwable) { null }
                val key = exactCausalKey7524 ?: priorCausalKey6647?.takeIf {
                    it.intentId == expectedIntentId6647 && it.mode.equals(resolvedMode6858, true)
                } ?: com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.CausalKey(
                        runId = if (canonicalAttempt6647) parts6647[0] else BotRuntimeController.currentGeneration().toString(),
                        mode = resolvedMode6858,
                        mint = mint, lane = lane, authorityVersion = 6551L,
                        intentId = expectedIntentId6647,
                    )
                if (exactCausalKey7524 != null && priorCausalKey6647 != null && (
                    priorCausalKey6647.intentId != exactCausalKey7524.intentId ||
                    !priorCausalKey6647.mode.equals(exactCausalKey7524.mode, true) ||
                    priorCausalKey6647.runId != exactCausalKey7524.runId
                )) {
                    try { PipelineHealthCollector.labelInc("SPECIALIST_CAUSAL_EXACT_INTENT_REBOUND_7524") } catch (_: Throwable) {}
                }
                // V5.0.7464 — a specialist may be elected after the initial
                // Toolkit hypothesis pass. When scanner/intake provenance
                // already proves this mint belonged to this lane, recover only
                // DISCOVER/QUALIFY on the SAME immutable key before stamping
                // the real downstream stage. No affinity proof => no backfill.
                if (causalStage.ordinal >= com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.OWNER.ordinal) {
                    try {
                        com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
                            .ensureAffinityLineage7464(key, causalStage)
                    } catch (_: Throwable) {}
                }
                // V5.0.7810 — SIZED_EXECUTABLE is allowed into the causal funnel
                // only after the exact immutable record has its real predecessors.
                // The sizing calculation still happened; only false executable
                // progress is withheld. No predecessor is fabricated here.
                if (stage == "SIZED_EXECUTABLE" &&
                    !com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.executablePredecessorsReady7810(key)
                ) {
                    try {
                        PipelineHealthCollector.labelInc("EXECUTABLE_SIZE_WITHHELD_MISSING_PREDECESSOR_7810")
                        ForensicLogger.lifecycle(
                            "EXECUTABLE_SIZE_WITHHELD_MISSING_PREDECESSOR_7810",
                            "mint=${mint.take(10)} lane=$lane mode=$resolvedMode6858 version=$candidateVersion6647 eventId=${eventId.take(80)}",
                        )
                    } catch (_: Throwable) {}
                    return
                }
                com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.stamp6625(key, causalStage, stage)
            } else {
                try { PipelineHealthCollector.labelInc("SPECIALIST_CAUSAL_UNRESOLVED_ID_REJECTED_6647") } catch (_: Throwable) {}
                // V5.0.7214 — which half was unresolvable, and on which stage.
                // "unresolved id" covered two different producer bugs: an
                // eventId whose mint could not be parsed out of it at all, and
                // one that parsed but carried no candidate version. The first is
                // a key-format fault at the call site, the second is a stamp
                // made before LaneExecutionCoordinator had a version for the
                // mint. Same counter, opposite fixes.
                try {
                    PipelineHealthCollector.labelInc(
                        if (mint.isBlank()) "DESK_STAGE_DROPPED_NO_MINT_IN_KEY_7214_$stage"
                        else "DESK_STAGE_DROPPED_NO_CANDIDATE_VERSION_7214_$stage",
                    )
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * V5.0.7807 §ONE_TICKET_ONE_OWNING_LANE_RECORD.
     *
     * When TICKET binds to a sealed immutable intent, remember on that exact
     * attemptId which candidate version and owning lane the ticket belongs to.
     * EXEC and POSITION_OPENED for the SAME attemptId then join that record
     * even if the intent was revoked/expired before the fill callback (the
     * fallback lane/version Executor passes in that case is not the ticket's),
     * which is what printed SPECIALIST_CAUSAL_ORPHAN_STAGE_7537 missing=NO_TICKET.
     * Only an attemptId that a real ticket was stamped on is ever rebound; no
     * stage is created here (Field Manual L337: one stamped evidence snapshot
     * per candidate, shared by every specialist stage).
     */
    private data class TicketLineage7807(val candidateVersion: Long, val lane: String, val mode: String)
    private val ticketLineage7807 = object : java.util.LinkedHashMap<String, TicketLineage7807>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TicketLineage7807>?): Boolean = size > 4096
    }

    private fun bindTicketLineage7807(attemptId: String, candidateVersion: Long, lane: String, mode: String) {
        if (attemptId.isBlank() || candidateVersion <= 0L || lane.isBlank()) return
        synchronized(ticketLineage7807) { ticketLineage7807[attemptId] = TicketLineage7807(candidateVersion, lane, mode.uppercase()) }
    }

    private fun boundTicketLineage7807(attemptId: String, stage: String): TicketLineage7807? {
        if (stage != "EXEC" && stage != "POSITION_OPENED") return null
        if (attemptId.isBlank()) return null
        return synchronized(ticketLineage7807) { ticketLineage7807[attemptId] }
    }

    /**
     * V5.0.7809 §EXEC_STAMPS_THE_ATTEMPT_IT_EXECUTED.
     *
     * Executor stamped EXEC/POSITION_OPENED on `sealedIntent?.attemptId ?:
     * fallback`, where sealedIntent came from activeExecutionIntent6519(mode,
     * mint, identity.fdgCandidateVersion). That lookup is lane-blind and, on a
     * miss, returns the newest live intent for the mint — another specialist's
     * (MANIPULATED filled through the shared shitCoinBuy transport landed on
     * SHITCOIN's intent) or a newer re-sealed version (MOONSHOT re-evaluations),
     * and the live path fell back to the positionId, whose version is guessed
     * from the newest scanner record. Either way EXEC joined a record with no
     * TICKET and the lane read EXEC_CHOKED.
     *
     * The executor's own attemptId IS the ticket it executed (canOpen's
     * execKey). When that attempt still names a real ticket — live, or bound by
     * a stamped TICKET — EXEC/OPEN go on it, on the ticket's lane. Otherwise the
     * previous behaviour is unchanged. Nothing is stamped that did not happen:
     * this is only the identity of a real fill (Field Manual L337).
     */
    fun recordEntryExecOpen7809(fallbackLane: String, ownAttemptId: String, fallbackAttemptId: String?) {
        val own = ownAttemptId.trim()
        val ownTicket = if (own.isNotBlank()) try { ExecutableOpenGate.ticketForAttempt(own) } catch (_: Throwable) { null } else null
        val ownLineage = if (ownTicket == null) attemptTicketLineage7809(own) else null
        val ticketLane = ownTicket?.let {
            com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(it.canonicalLane.ifBlank { it.lane })
        }?.takeIf { it.isNotBlank() } ?: ownLineage?.lane
        val fallback = fallbackAttemptId?.takeIf { it.isNotBlank() } ?: own
        val causalId = if (ticketLane != null) own else fallback
        val lane = ticketLane ?: fallbackLane
        if (ticketLane != null && causalId != fallback) try {
            PipelineHealthCollector.labelInc("SPECIALIST_EXEC_BOUND_TO_OWN_TICKET_7809")
            PipelineHealthCollector.labelInc("SPECIALIST_EXEC_BOUND_TO_OWN_TICKET_7809_$lane")
            ForensicLogger.lifecycle(
                "SPECIALIST_EXEC_BOUND_TO_OWN_TICKET_7809",
                "lane=$lane callerLane=$fallbackLane attemptId=${own.take(80)} previous=${fallback.take(80)}",
            )
        } catch (_: Throwable) {}
        recordDeskStage(lane, "EXEC", causalId)
        recordDeskStage(lane, "POSITION_OPENED", causalId)
    }

    /** V5.0.7809 — the lineage bound on this exact attemptId, for ANY stage. */
    private fun attemptTicketLineage7809(attemptId: String): TicketLineage7807? {
        if (attemptId.isBlank()) return null
        return synchronized(ticketLineage7807) { ticketLineage7807[attemptId] }
    }

    private fun ticketLineageLane7807(lane: String, stage: String, eventId: String): String {
        val bound = boundTicketLineage7807(eventId, stage) ?: return lane
        if (bound.lane == lane) return lane
        try {
            PipelineHealthCollector.labelInc("SPECIALIST_EXEC_REBOUND_TO_TICKET_LANE_7807")
            ForensicLogger.lifecycle(
                "SPECIALIST_EXEC_REBOUND_TO_TICKET_LANE_7807",
                "stage=$stage callerLane=$lane ticketLane=${bound.lane} version=${bound.candidateVersion} attemptId=${eventId.take(80)}",
            )
        } catch (_: Throwable) {}
        return bound.lane
    }

    fun recordContributorSummary(summary: String, stage: String, eventId: String = "") {
        Regex("(?:^|\\|)([A-Z_]+):[A-Z0-9_]+:").findAll(summary.uppercase()).forEach { m ->
            recordDeskStage(m.groupValues[1], stage, eventId)
        }
    }

    private fun deskCount6599(lane: String, stage: String): Long = deskStageCounts6599["${lane.uppercase()}|${stage.uppercase()}"]?.get() ?: 0L

    private val causalIssueCounts6600 = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun recordCausalIssue6600(issue: String, lane: String = "UNKNOWN", detail: String = "") {
        val key = issue.trim().replace(Regex("[^A-Za-z0-9_]"), "_")
        causalIssueCounts6600.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicLong(0L) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("MEME_SPECIALIST_CAUSAL_$key")
            ForensicLogger.lifecycle("MEME_SPECIALIST_CAUSAL_$key", "lane=$lane ${detail.take(180)}")
        } catch (_: Throwable) {}
    }

    private fun causalIssue6600(issue: String): Long = causalIssueCounts6600[issue]?.get() ?: 0L

    /**
     * V5.0.7809 §A_REFUSAL_IS_NOT_A_CHOKE.
     *
     * On the specialist lanes SIZE is stamped only when a ticket is published
     * (ExecutableOpenGate.mirrorTicketPredecessors7807) or a buy opens, so every
     * refusal between the sealed mark and the ticket — TradeAuthorizer finality,
     * LiveRiskPolicy7807's pre-ticket/final verdicts (LANE_SLOT_CAP_7807,
     * COST_CONSUMES_MOVE_7807, SIZE_BELOW_MIN_RISK_TOO_WIDE_7807), an
     * OrderSizeResolver refusal, a live buy failure — reached the funnel as
     * nothing at all and the lane read the generic SIZING_CHOKED. Genuine
     * policy refusals stay refusals; the report now names them (Field Manual
     * L468: the decision carries its reason; L415: reconcile with what happened).
     */
    private val preSizeRefusals7809 = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun recordPreSizeRefusal7809(lane: String, reason: String) {
        val l = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(lane) } catch (_: Throwable) { lane.trim().uppercase() }
        val r = reason.substringBefore(':').trim().uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(64)
        if (l.isBlank() || r.isBlank()) return
        if (preSizeRefusals7809.size > 2_000) preSizeRefusals7809.clear()
        preSizeRefusals7809.computeIfAbsent("$l|$r") { java.util.concurrent.atomic.AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SPECIALIST_PRE_SIZE_REFUSAL_7809_$l") } catch (_: Throwable) {}
    }

    /** "REASON=n,REASON=n" (largest first, at most four) or "" when the lane recorded none. */
    private fun preSizeRefusalSummary7809(lane: String): String {
        val prefix = "${lane.uppercase()}|"
        return preSizeRefusals7809.entries
            .filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.value.get() }
            .take(4)
            .joinToString(",") { "${it.key.removePrefix(prefix)}=${it.value.get()}" }
    }

    /**
     * V5.0.7809 — open canonical positions per canonical lane, for liveness.
     * A lane whose causal window (30-minute record TTL) holds no fresh DISCOVER
     * while it still owns open positions is holding, not DEAD.
     */
    private fun openPositionsByLane7809(): Map<String, Int> = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
            .groupingBy { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(it.lane) }
            .eachCount()
    } catch (_: Throwable) { emptyMap() }

    fun specialistCausalFunnel6600(): String = buildString {
        appendLine("===== MEME SPECIALIST CAUSAL FUNNEL =====")
        configuredMemeDesks6599.forEach { lane ->
            val s = com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.laneSnapshot6647(lane)
            fun n(stage: com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage) = s.counts[stage] ?: 0
            fun o(outcome: String) = s.outcomes[outcome] ?: 0
            appendLine("$lane discovered=${n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.DISCOVER)} qualified=${n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.QUALIFY)} ownerSelected=${n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.OWNER)} buyIntent=${n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.INTENT)} fdgAllow=${o("FDG_ALLOW")} fdgBlock=${o("FDG_BLOCK")} markReady=${o("MARK_READY")} markReject=${o("MARK_REJECT")} sizedExecutable=${n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.SIZE)} sizeReject=${o("SIZE_REJECT")} ticket=${n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.TICKET)} exec=${n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.EXEC)} positionOpened=${n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.OPEN)} exit=${n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.EXIT)} sellAttempt=${o("SELL_ATTEMPT")} sellConfirmed=${o("SELL_CONFIRMED")} finalized=${n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.FINALIZE)} learningDelivered=${n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.LEARN)} phantomSizedOnly=${s.phantomSizedOnly}")
            // V5.0.6883 — name the missing predecessor and one orphaned key.
            // A bare phantom count has never been enough to fix
            // J_PHANTOM_SIZED_ONLY; the breakdown says which hop dropped its
            // stamp and gives the intentId to grep for.
            // V5.0.7214 §THE_DISAMBIGUATOR_COULD_NOT_PRINT_IN_THE_CASE_IT_WAS_FOR.
            //
            // 7086 added the raw stage tally precisely so "ticket=0" could be
            // told apart from "ticket was built and the count was suppressed",
            // and its own comment says so. But it gated the print on
            // `phantomSizedOnly > 0`, and phantomSizedOnly is only incremented
            // when `executableSize` is true — i.e. when a SIZE outcome EXISTS
            // (MemeExecutionFunnelReceivers6625:536,539).
            //
            // So when the SIZE stamp is missing ENTIRELY — the 5.0.7212 live
            // shape, sizedN=0 sizeReject=0 phantomSizedOnly=0 on all twelve
            // lanes — phantomSizedOnly is 0, the gate is false, and the raw
            // counts that would have answered the question are computed and
            // thrown away. A remedy behind a threshold it cannot reach, which
            // is the same defect class as 7148, 7154 and 7204.
            //
            // Print the raw tally whenever a validated stage reads zero while
            // its raw counterpart does not. That is the only condition under
            // which the two numbers say different things, so it is the only
            // condition worth the line — and it no longer depends on a
            // predecessor stamp being present to report that a predecessor
            // stamp is absent.
            // V5.0.7809 — refusals between the sealed mark and SIZE, by name.
            preSizeRefusalSummary7809(lane).takeIf { it.isNotBlank() }?.let { appendLine("$lane preSizeRefusals7809=$it") }
            val raw7214 = s.rawCounts7086
            fun rawOf7214(stage: com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage) =
                raw7214[stage] ?: 0
            val suppressed7214 = com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.values()
                .filter { (s.counts[it] ?: 0) == 0 && rawOf7214(it) > 0 }
            if (suppressed7214.isNotEmpty()) {
                appendLine(
                    "$lane suppressedStages=${suppressed7214.joinToString(",") { "${it.name}:raw=${rawOf7214(it)}" }} " +
                        "read=these_stages_WERE_reached_and_the_validated_count_dropped_them_for_a_missing_predecessor",
                )
                try {
                    PipelineHealthCollector.labelInc("FUNNEL_STAGE_COUNT_SUPPRESSED_7214")
                    suppressed7214.forEach {
                        PipelineHealthCollector.labelInc("FUNNEL_STAGE_COUNT_SUPPRESSED_7214_${it.name}")
                    }
                } catch (_: Throwable) {}
            }
            if (s.phantomSizedOnly > 0) {
                appendLine("$lane phantomMissing=${s.phantomMissing6883.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "NONE" }} phantomSampleIntentId=${s.phantomSampleIntentId6883.ifBlank { "NONE" }}")
                // V5.0.7086 — the RAW stage tally beside the validated one.
                //
                // `sizedExecutable`, `ticket` and `exec` above are causally
                // validated: MemeExecutionFunnelReceivers6625 only tallies a
                // stage when the same record also carries its predecessors, and
                // Stage.TICKET/Stage.EXEC both require Stage.INTENT. So a lane
                // whose phantomMissing is dominated by NO_INTENT reports
                // ticket=0 exec=0 WHETHER OR NOT tickets were built.
                //
                // Print both and the ambiguity disappears:
                //   rawTicket=0   -> nothing built. A real execution choke.
                //   rawTicket>0   -> tickets built, the count was suppressed by
                //                    a missing INTENT stamp. A TELEMETRY defect,
                //                    and aiming an authority rewrite at it would
                //                    be repairing a report.
                val raw7086 = s.rawCounts7086
                appendLine(
                    "$lane rawSized=${raw7086[com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.SIZE] ?: 0}" +
                        " rawTicket=${raw7086[com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.TICKET] ?: 0}" +
                        " rawExec=${raw7086[com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.EXEC] ?: 0}" +
                        " rawOpen=${raw7086[com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.OPEN] ?: 0}" +
                        " read=rawTicket_gt_0_with_ticket_eq_0_is_a_suppressed_count_not_a_choke",
                )
            }
        }
        appendLine("ownerLaneChangedAfterSelection=${causalIssue6600("ownerLaneChangedAfterSelection")}")
        appendLine("crossLaneExecutionRewrite=${causalIssue6600("crossLaneExecutionRewrite")}")
        appendLine("telemetryOnlySuppression=${causalIssue6600("telemetryOnlySuppression")}")
        appendLine("missingExecutableMarkWithValidSource=${causalIssue6600("missingExecutableMarkWithValidSource")}")
        appendLine("specialistLearningMissing=${causalIssue6600("specialistLearningMissing")}")
        appendLine("sellCanonicalLookupFailure=${causalIssue6600("sellCanonicalLookupFailure")}")
        appendLine("LANE_EXEC_WITHOUT_SAME_LANE_CANONICAL_INTENT=${causalIssue6600("LANE_EXEC_WITHOUT_SAME_LANE_CANONICAL_INTENT")}")
        appendLine("LANE_EXEC_WITHOUT_SEALED_FDG_PROVENANCE=${causalIssue6600("LANE_EXEC_WITHOUT_SEALED_FDG_PROVENANCE")}")
        appendLine("SPECIALIST_INTENT_WITHOUT_FDG_OUTCOME=${causalIssue6600("SPECIALIST_INTENT_WITHOUT_FDG_OUTCOME")}")
    }

    fun designatedRoleLivenessReport6599(): String = buildString {
        appendLine("===== MEME SPECIALIST ROLE LIVENESS =====")
        val openByLane7809 = openPositionsByLane7809()
        configuredMemeDesks6599.forEach { lane ->
            val causal = com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.laneSnapshot6647(lane)
            val runtime = com.lifecyclebot.engine.truth.SpecialistRuntimeRegistry6647.snapshot(lane)
            fun n(stage: com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage) = (causal.counts[stage] ?: 0).toLong()
            val pool = n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.DISCOVER)
            val qualified = n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.QUALIFY)
            val intent = n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.INTENT)
            val owner = n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.OWNER)
            fun o(outcome: String) = (causal.outcomes[outcome] ?: 0).toLong()
            val fdgAllow = o("FDG_ALLOW")
            val fdgBlock = o("FDG_BLOCK")
            val mark = n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.MARK)
            val sized = n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.SIZE)
            val ticket = n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.TICKET)
            val exec = n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.EXEC)
            val opened = n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.OPEN)
            val sellAttempt = o("SELL_ATTEMPT")
            val sellConfirmed = o("SELL_CONFIRMED")
            val finalized = n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.FINALIZE)
            val learn = n(com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.LEARN)
            // V5.0.6658 §STATUS_DETECTION_DECISION_AWARENESS (operator Feb
            //   2026 P0 mandate: authority/routing/state repair, no
            //   threshold tuning, no fallback masking).
            //   SIZE/MARK/TICKET/EXEC are executable-side stamps. When
            //   every FDG outcome for a lane was FDG_BLOCK there is no
            //   sized/ticket/exec work to observe, so declaring the lane
            //   SIZING_CHOKED / TICKET_CHOKED misleads the operator into
            //   thinking the specialist is broken when in fact the lane
            //   emitted verdicts and the block was authorised. Guard the
            //   post-FDG chokes on `fdgAllow > 0`; if only blocks were
            //   emitted the status is FDG_BLOCKED_ALL (the operator's
            //   real state) rather than a downstream choke. When
            //   fdgAllow > 0 and the downstream stage still is 0, the
            //   choke label remains correct and actionable.
            // V5.0.7214 §MARK_CHOKED_WAS_UNREACHABLE_SO_EVERY_LANE_SAID_SIZING.
            //
            // Operator 5.0.7212, every live lane:
            //   QUALITY ... fdgN=2 markN=0 sizedN=0 ticketN=0 execN=0
            //               status=SIZING_CHOKED
            // and in the same snapshot, the sizing authority itself:
            //   Order size resolver (§6441): resolves=63 exec=63 skip=0
            //
            // Sixty-three resolutions, sixty-three executable, zero skipped.
            // The sizer refused nothing. "SIZING_CHOKED" was not a measurement.
            //
            // The order of these branches is the defect.
            // MemeExecutionFunnelReceivers6625:557 validates Stage.SIZE as
            //   `executableSize && DISCOVER in stages && INTENT in stages && markReady`
            // so a record with no MARK can NEVER be counted as SIZE. `sized == 0`
            // is therefore IMPLIED by `mark == 0`, and because it is tested first
            // the MARK_CHOKED branch below it is unreachable in exactly the case
            // it was written for. Every missing mark has been reported as a
            // sizing fault since the branch order was set, which sends whoever
            // reads it at OrderSizeResolver — the one component the same snapshot
            // exonerates.
            //
            // Mark is now tested before size, and the two mark conditions are
            // separated, because they are not the same fault:
            //   MARK_STAGE_UNRECORDED — neither MARK_READY nor MARK_REJECT was
            //     ever recorded for this lane. No producer ran. This is a
            //     TELEMETRY hole, and on the live path it is the expected state:
            //     the only unconditional MARK producer is in paperBuy
            //     (Executor:14748), so a LIVE run records no mark stage at all
            //     and four downstream stages validated against it read zero by
            //     construction.
            //   MARK_CHOKED — the stage WAS recorded and every outcome was a
            //     refusal. That is a real feed/mark fault.
            //
            // No threshold, lane rule or execution path changes. This only stops
            // the report naming the wrong subsystem.
            val markRefusals7214 = o("MARK_REJECT")
            val markStageRecorded7214 = mark > 0L || markRefusals7214 > 0L
            val status = when {
                pool == 0L -> "DEAD"
                qualified == 0L -> "DISCOVERY_ONLY"
                intent == 0L -> "INTENT_CHOKED"
                fdgAllow + fdgBlock == 0L -> "FDG_CHOKED"
                fdgAllow == 0L -> "FDG_BLOCKED_ALL"
                !markStageRecorded7214 -> "MARK_STAGE_UNRECORDED_7214"
                mark == 0L -> "MARK_CHOKED"
                sized == 0L -> "SIZING_CHOKED"
                ticket == 0L -> "TICKET_CHOKED"
                exec == 0L || opened == 0L -> "EXEC_CHOKED"
                sellAttempt > 0L && sellConfirmed == 0L -> "EXIT_CHOKED"
                finalized > 0L && learn == 0L -> "LEARNING_CHOKED"
                else -> "ACTIVE"
            }
            // V5.0.7809 — name what the generic labels hid (see recordPreSizeRefusal7809):
            //   SIZING_CHOKED + an executable size outcome that failed lineage
            //     validation -> the size happened; a predecessor stamp is missing.
            //   SIZING_CHOKED + recorded refusals -> the exact refusal, not a choke.
            //   DEAD + open canonical positions on the lane -> holding, not dead
            //     (the causal window is 30 minutes; positions outlive it).
            val refusals7809 = if (status == "SIZING_CHOKED") preSizeRefusalSummary7809(lane) else ""
            val executableSizeOutcomes7809 = (causal.outcomes["SIZED_EXECUTABLE"] ?: 0).toLong()
            val openOnLane7809 = openByLane7809[lane] ?: 0
            val reportedStatus7809 = when {
                status == "SIZING_CHOKED" && executableSizeOutcomes7809 > 0L -> "SIZE_LINEAGE_INCOMPLETE_7809"
                refusals7809.isNotBlank() -> "REFUSED_BEFORE_SIZE_7809"
                status == "DEAD" && openOnLane7809 > 0 -> "HOLDING_NO_FRESH_DISCOVERY_7809"
                else -> status
            }
            // V5.0.7214 — the contradiction, counted where both numbers are in
            // hand. A lane reported SIZING_CHOKED while the only authority that
            // decides what "executable size" means reported no refusals at all.
            if (status == "SIZING_CHOKED") {
                try {
                    val skips7214 = com.lifecyclebot.engine.truth.OrderSizeResolver6441.skippedCount7214()
                    if (skips7214 <= 0L) {
                        PipelineHealthCollector.labelInc("FUNNEL_SIZING_CHOKED_CONTRADICTED_BY_RESOLVER_7214")
                        PipelineHealthCollector.labelInc("FUNNEL_SIZING_CHOKED_CONTRADICTED_BY_RESOLVER_7214_$lane")
                    }
                } catch (_: Throwable) {}
            }
            if (status == "MARK_STAGE_UNRECORDED_7214") {
                try { PipelineHealthCollector.labelInc("FUNNEL_MARK_STAGE_HAS_NO_PRODUCER_7214_$lane") } catch (_: Throwable) {}
            }
            // V5.0.7221 §I_DROPPED_THE_THIRD_TERM_OF_THE_DIRECTIVE'S_EQUATION.
            //
            // 7214 asserted `fdgAllow == mark + markReject` and it fired on 11
            // of 12 lanes in the 5.0.7219 snapshot — QUALITY fdgAllow=56 mark=2,
            // CORE 69 vs 0. That is not eleven defects, it is one wrong
            // invariant. The operator's directive #2 wrote the equation as
            //
            //     FDG_ALLOW == MARK_READY + MARK_REJECT + EXPLICIT_CANCEL
            //
            // and I encoded it without the last term. FDG_ALLOW is stamped
            // (BotService:29564) on every cycle a candidate is allowed;
            // MARK_READY is stamped (BotService:29558) only inside the block
            // that runs when a ticket intent exists. Every allow that the exec
            // gate, a probe budget, or the entry authority then declines is an
            // EXPLICIT_CANCEL by the directive's own definition, and there were
            // hundreds of them — DESK_STAGE_OFFERED FDG_ALLOW=759 against
            // MARK_READY=16 on the same snapshot.
            //
            // The equation balances when the cancel term is counted, and the
            // invariant that actually protects execution is the one the data
            // already satisfies on every lane: a TICKET must carry a mark
            // verdict. QUALITY ticket=2 mark=2, MOONSHOT 3 vs 4, EXPRESS 1 vs 1,
            // and nowhere does ticket exceed mark. That is the assertion now.
            // The cancel term is printed, not alarmed on, because a cancelled
            // allow is the pipeline working.
            val fdgAllowNotTicketed7221 = (fdgAllow - ticket).coerceAtLeast(0L)
            if (ticket > mark + markRefusals7214) {
                try {
                    PipelineHealthCollector.labelInc("TICKET_WITHOUT_MARK_VERDICT_7221")
                    PipelineHealthCollector.labelInc("TICKET_WITHOUT_MARK_VERDICT_7221_$lane")
                } catch (_: Throwable) {}
            }
            try {
                // Keep 7214's key so old snapshots diff, but it now measures the
                // cancel term rather than a false alarm.
                if (fdgAllowNotTicketed7221 > 0L) {
                    PipelineHealthCollector.labelInc("FDG_ALLOW_EXPLICIT_CANCEL_7221_$lane")
                }
            } catch (_: Throwable) {}
            val executionEligible = ticket > 0L && exec > 0L
            val native7608 = try { SpecialistBrainBridge7542.laneRuntime7542(lane) } catch (_: Throwable) { null }
            val nativeReason7608 = native7608?.reason?.replace("\n", " ")?.take(120).orEmpty()
            val liveQuarantine7609 = try { LaneQuarantineController.isQuarantined(lane) } catch (_: Throwable) { false }
            val buyerEnabled7609 = if (lane == "MANIPULATED") try { BotService.manipulatedBuyerEnabled7609() } catch (_: Throwable) { false } else true
            val ownershipModel7609 = "SELF"
            // V5.0.7815 — SpecialistBrainBridge7542 deliberately fan-outs every
            // token through every native brain. Its cached "last opinion" is NOT
            // the lane hunter's selected prey. Label that scope explicitly so a
            // QUALITY rejection of a $4k global-fanout token is never diagnosed
            // again as QUALITY's 75k+ hunter routing the wrong pond.
            val resident7815 = try {
                com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.snapshot(lane)
            } catch (_: Throwable) { emptyList() }
            val residentReady7815 = resident7815.count {
                it.state == com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.State.READY
            }
            appendLine("$lane runtimeAlive=${runtime.runtimeAlive} trafficSeen=${runtime.trafficSeen} candidateQualified=${qualified > 0L} executionEligible=$executionEligible heartbeatAtMs=${runtime.heartbeatAtMs} queueOwner=${runtime.queueOwner.ifBlank { "NONE" }} queueDepth=${runtime.queueDepth} candidateN=$pool qualifiedN=$qualified ownerSelectedN=$owner buyIntentN=$intent fdgN=$fdgAllow markN=$mark sizedN=$sized ticketN=$ticket execN=$exec positionOpenedN=$opened finalizedN=$finalized learningN=$learn phantomSizedOnly=${causal.phantomSizedOnly} capitalAvailable=SHARED_CANONICAL status=$reportedStatus7809 rawStatus7809=$status preSizeRefusals7809=${refusals7809.ifBlank { "NONE" }} openPositions7809=$openOnLane7809 residentOwnLane7815=${resident7815.size} residentReady7815=$residentReady7815 nativeScope7815=GLOBAL_FANOUT_LAST_TOKEN nativeCalled=${native7608?.called ?: 0} nativeAllow=${native7608?.allowed ?: 0} nativeReject=${native7608?.rejected ?: 0} nativeErr=${native7608?.errors ?: 0} nativeEligible=${native7608?.eligible ?: false} nativeScore=${native7608?.score ?: 0} nativeConf=${native7608?.confidence ?: 0} nativeReason=$nativeReason7608 liveQuarantine=$liveQuarantine7609 buyerEnabled=$buyerEnabled7609 ownershipModel=$ownershipModel7609")
        }
        appendLine("PROJECT_SNIPER_NON_SNIPER_ADMISSION = ${deskCount6599("PROJECT_SNIPER", "NON_SNIPER_ADMISSION")}")
    }

    fun specialistCapitalReport6599(): String = buildString {
        appendLine("===== MEME SPECIALIST CAPITAL =====")
        val paperMode6686 = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }
        // V5.0.7371 — capital used is measured against this mode's cash only; paper
        // positions showed 0.64/0.67/0.48 SOL used against a 0.31 SOL live wallet.
        val positions = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
                .filter { it.mode.equals(if (paperMode6686) "paper" else "live", true) }
        } catch (_: Throwable) { emptyList() }
        val capital = if (paperMode6686) try { com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.snapshot() } catch (_: Throwable) { null } else null
        val sharedCash = if (paperMode6686) capital?.availableCashSol ?: 0.0 else try { BotService.status.walletSol.coerceAtLeast(0.0) } catch (_: Throwable) { 0.0 }
        val sharedEquity = if (paperMode6686) capital?.totalEquitySol ?: sharedCash else sharedCash
        val capitalSource6686 = if (paperMode6686) "PAPER_CAPITAL_AUTHORITY_6577" else "LIVE_WALLET_AUTHORITY_6686"
        val weights = configuredMemeDesks6599.associateWith { lane ->
            val expectancy = try { LaneExpectancyDamper.sizeMultiplier(lane) } catch (_: Throwable) { 1.0 }
            val opportunity = (1.0 + kotlin.math.ln1p(deskCount6599(lane, "QUALIFIED").toDouble())).coerceAtMost(4.0)
            (expectancy.coerceIn(0.25, 1.50) * opportunity).coerceAtLeast(0.01)
        }
        val weightSum = weights.values.sum().coerceAtLeast(0.01)
        configuredMemeDesks6599.forEach { lane ->
            val owned = positions.filter { it.lane.equals(lane, true) || (lane == "BLUECHIP" && it.lane.equals("BLUE_CHIP", true)) }
            val used = owned.sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
            val pending = (deskCount6599(lane, "BUY_INTENT") - deskCount6599(lane, "EXEC")).coerceAtLeast(0L)
            val targetPct = (weights.getValue(lane) / weightSum * 100.0).coerceIn(0.0, 100.0)
            val targetSol = sharedEquity * (targetPct / 100.0)
            // V5.0.6912 §THE_REPORT_MUST_SHOW_WHAT_ACTUALLY_GATES.
            //
            // The target computed above is expectancy x OPPORTUNITY, where
            // opportunity is ln1p(qualified) — i.e. scanner traffic. The
            // authority that actually gates admissions,
            // LaneCapitalFairness6732, deliberately uses expectancy ALONE and
            // says why in its own comment: tying budget to traffic "conflates
            // 'this lane has candidates to admit' with 'this lane has
            // budget'". It is right, and that means this line — the surface
            // the operator reads — has been reporting a target that nothing
            // enforces, against an equity base (cash + openMarketValue) that
            // the enforcer no longer uses either.
            //
            // Print both, labelled. The advisory figure stays for continuity;
            // enforced* is the one that decides whether a buy happens, so the
            // next snapshot can verify the §6912 block directly instead of
            // inferring it.
            val enforced6912 = try {
                com.lifecyclebot.engine.truth.LaneCapitalFairness6732
                    .headroomFor(if (paperMode6686) "PAPER" else "LIVE", lane)
            } catch (_: Throwable) { null }
            val enforcedTxt6912 = if (enforced6912 != null && enforced6912.targetSol > 0.0) {
                " enforcedTargetSol=${"%.4f".format(enforced6912.targetSol)}" +
                    " enforcedUtil=${"%.2f".format(enforced6912.utilization)}x" +
                    " enforcedHeadroom=${enforced6912.hasHeadroom}"
            } else " enforcedTargetSol=n/a enforcedUtil=n/a enforcedHeadroom=n/a"
            appendLine("$lane targetAllocation=${"%.2f".format(targetPct)}%(advisory) targetSol=${"%.4f".format(targetSol)}(advisory)$enforcedTxt6912 availableAllocation=sharedCash:${"%.4f".format(sharedCash)} usedAllocation=${"%.4f".format(used)} openPositions=${owned.size} pendingIntents=$pending capitalStarved=${pending > 0L && (sharedCash <= 0.0 || com.lifecyclebot.engine.truth.OrderSizeResolver6441.capitalStarvedNow7194())} capitalRefusals7194=${com.lifecyclebot.engine.truth.OrderSizeResolver6441.capitalRefusalCount7194()} starvedByLane=NONE allocationDecisionSource=${capitalSource6686}+LANE_EXPECTANCY+OPPORTUNITY_PRESSURE")
        }
    }

    fun contributionSummary(ts: TokenState, classification: ModeRouter.Classification? = null): String {
        val sheet = snapshot(ts, classification)
        return sheet.deskHypotheses.values.sortedByDescending { it.conviction }.joinToString("|") { h ->
            "${h.lane}:${h.setup.name}:${"%.1f".format(h.conviction)}:${h.entryStyle}:${h.exitStyle}:hold=${"%.2f".format(h.holdMult)}:size=${"%.2f".format(h.sizeMult)}:tp=${"%.2f".format(h.tpMult)}"
        }
    }

    private fun riskOffSetupBias(setup: Setup, riskOff: Boolean): Double {
        if (!riskOff) return 0.0
        return when (setup) {
            Setup.DEGEN_MICRO_SNIPE,
            Setup.PUMP_GRADUATION_SNIPE,
            Setup.EXHAUSTION_QUICK_FLIP,
            Setup.VOLUME_IGNITION_SCALP,
            Setup.NARRATIVE_SOCIAL_IGNITION,
            Setup.ARB_FLOW_IMBALANCE,
            Setup.MEV_PROTECTED_ENTRY -> -35.0
            Setup.LIQUIDITY_DEPTH_QUALITY,
            Setup.MAINSTREAM_CRYPTO_SWING,
            Setup.CHART_PULLBACK_RECLAIM,
            Setup.REENTRY_RECOVERY,
            Setup.PANIC_REVERSION_BOUNCE,
            Setup.REGIME_DEFENSIVE_PROBE -> 22.0
            else -> 0.0
        }
    }

    private fun regimeSetupBias(setup: Setup, regime: RegimeDetector.RegimeSnapshot?): Double {
        val r = regime?.regime ?: return 0.0
        val weakChop = (r == RegimeDetector.Regime.CHOP && regime.recentWrPct < 25.0) || r == RegimeDetector.Regime.DUMP
        if (!weakChop) return 0.0
        return when (setup) {
            // 4052 report: DUMP wr=6.4%, meanPnl=-28.72%, toolkit still selected
            // degen_micro_snipe/fresh_pool_momentum. In DUMP, pure birth momentum must
            // lose to depth/reclaim/recovery structures. This is bias only — no veto.
            Setup.DEGEN_MICRO_SNIPE -> if (r == RegimeDetector.Regime.DUMP) -48.0 else -18.0
            Setup.PUMP_GRADUATION_SNIPE -> if (r == RegimeDetector.Regime.DUMP) -36.0 else -12.0
            Setup.VOLUME_IGNITION_SCALP -> if (r == RegimeDetector.Regime.DUMP) -34.0 else -10.0
            Setup.EXHAUSTION_QUICK_FLIP -> if (r == RegimeDetector.Regime.DUMP) -24.0 else -8.0
            Setup.ARB_FLOW_IMBALANCE -> if (r == RegimeDetector.Regime.DUMP) -22.0 else -8.0
            Setup.NARRATIVE_SOCIAL_IGNITION -> if (r == RegimeDetector.Regime.DUMP) -30.0 else -6.0
            // Prefer structures that survive chop/dump instead of pure birth momentum.
            Setup.CHART_PULLBACK_RECLAIM -> if (r == RegimeDetector.Regime.DUMP) 18.0 else 10.0
            Setup.PANIC_REVERSION_BOUNCE -> if (r == RegimeDetector.Regime.DUMP) 16.0 else 8.0
            Setup.LIQUIDITY_DEPTH_QUALITY -> if (r == RegimeDetector.Regime.DUMP) 18.0 else 8.0
            Setup.MAINSTREAM_CRYPTO_SWING -> if (r == RegimeDetector.Regime.DUMP) 12.0 else 6.0
            Setup.SMART_WALLET_COPY_FOLLOW -> if (r == RegimeDetector.Regime.DUMP) 14.0 else 4.0
            Setup.REGIME_DEFENSIVE_PROBE -> if (r == RegimeDetector.Regime.DUMP) 14.0 else 6.0
            else -> 0.0
        }
    }

    private fun pctMove(prices: List<Double>): Double {
        if (prices.size < 2) return 0.0
        val first = prices.first().takeIf { it > 0.0 } ?: return 0.0
        val last = prices.last()
        return ((last - first) / first) * 100.0
    }
}
