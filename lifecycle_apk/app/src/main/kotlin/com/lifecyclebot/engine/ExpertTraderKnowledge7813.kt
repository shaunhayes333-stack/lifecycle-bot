package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * V5.0.7813 — canonical expert-trader doctrine + entry feature ledger.
 *
 * One vocabulary, one decision-time snapshot, one position-bound grade path.
 * This does NOT create a second gate. Hunters may use it for ordering, the
 * oracle/SSI may consume its bounded prior, while safety/FDG/finality remain
 * the only admission/execution authorities.
 *
 * Live terminal outcomes are strongest. Feature polarity is explicit: positive
 * and negative manifestations never share an expectancy bucket. 60-minute
 * forward labels (including refused candidates) are counterfactual evidence at
 * 0.20 weight. PAPER may
 * seed LIVE only while LIVE evidence is thin and is shrunk to 0.20 strength.
 */
object ExpertTraderKnowledge7813 {
    enum class Feature {
        IGNITION,
        BREAKOUT_EXPANSION,
        RECLAIM,
        CONTINUATION,
        RELATIVE_STRENGTH,
        LIQUIDITY_EXPANSION,
        PARTICIPATION_EXPANSION,
        BUY_PRESSURE,
        MULTI_PROVIDER_AGREEMENT,
        EXITABILITY,
        ROUTE_QUALITY,
        HOLDER_QUALITY,
        REGIME_FIT,
        CHASE_RISK,
        DISTRIBUTION,
        EXHAUSTION,
    }

    data class Snapshot(
        val mint: String,
        val lane: String,
        val mode: String,
        val source: String,
        val regime: String,
        val score: Int,
        val confidence: Double,
        val values: Map<Feature, Double>,
        val atMs: Long = System.currentTimeMillis(),
    )

    data class Prior(
        val deltaPct: Double,
        val confidence: Double,
        val label: String,
        val contributions: List<String>,
    ) {
        companion object {
            val NEUTRAL = Prior(0.0, 0.0, "expert7813(neutral)", emptyList())
        }
    }

    private data class Stat(
        var weight: Double = 0.0,
        var sumReturnPct: Double = 0.0,
        var wins: Double = 0.0,
        var runners: Double = 0.0,
    ) {
        fun meanPct(): Double = if (weight > 0.0) sumReturnPct / weight else 0.0
        fun winRate(): Double = if (weight > 0.0) wins / weight else 0.0
        fun runnerRate(): Double = if (weight > 0.0) runners / weight else 0.0
    }

    private data class Evidence(val meanPct: Double, val confidence: Double, val runnerRate: Double, val weight: Double)

    private const val MAX_PENDING = 4096
    private const val MAX_BOUND = 1024
    private const val PENDING_TTL_MS = 70L * 60_000L
    private const val COUNTERFACTUAL_WEIGHT = 0.20
    private const val PAPER_TO_LIVE_WEIGHT = 0.20

    private val stats = ConcurrentHashMap<String, Stat>()
    private val pending = ConcurrentHashMap<String, Snapshot>()
    private val byPosition = ConcurrentHashMap<String, Snapshot>()
    private val settled = ConcurrentHashMap.newKeySet<String>()
    private val subscribed = AtomicBoolean(false)
    private val captures = AtomicLong(0L)
    private val binds = AtomicLong(0L)
    private val terminalGrades = AtomicLong(0L)
    private val forwardGrades = AtomicLong(0L)

    private fun modeNow(): String =
        try { if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE" } catch (_: Throwable) { "LIVE" }

    private fun canonicalLane(raw: String): String =
        try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(raw) }
        catch (_: Throwable) { raw.trim().uppercase() }

    private fun pendingKey(mode: String, lane: String, mint: String): String =
        "${mode.uppercase()}|${canonicalLane(lane)}|${mint.trim()}"

    private fun polarity(value: Double): String = if (value >= 0.0) "POS" else "NEG"

    private fun statKey(mode: String, lane: String, regime: String, feature: Feature, value: Double): String =
        "${mode.uppercase()}|${canonicalLane(lane)}|${regime.uppercase()}|${feature.name}|${polarity(value)}"

    private fun clamp01(v: Double): Double = v.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
    private fun signed(v: Double, scale: Double): Double =
        if (!v.isFinite() || scale <= 0.0) 0.0 else (v / scale).coerceIn(-1.0, 1.0)

    private fun ensureSubscribed() {
        if (!subscribed.compareAndSet(false, true)) return
        try {
            CanonicalTradeFinalizedBus6450.subscribe { onFinalized(it) }
        } catch (_: Throwable) {
            subscribed.set(false)
        }
    }

    private fun regimeFit(lane: String, regime: String): Double {
        val l = canonicalLane(lane)
        val r = regime.uppercase()
        return when {
            r in setOf("DUMP", "DEAD") && l in setOf("MOONSHOT", "EXPRESS", "PROJECT_SNIPER") -> -0.55
            r == "BULL_RIPPING" && l in setOf("MOONSHOT", "EXPRESS", "SHITCOIN", "QUALITY") -> 0.65
            r == "CHOP" && l == "DIP_HUNTER" -> 0.45
            r == "CHOP" && l in setOf("TREASURY", "CASHGEN", "BLUECHIP") -> 0.25
            else -> 0.0
        }
    }

    private fun laneWeight(lane: String, feature: Feature): Double {
        val l = canonicalLane(lane)
        return when (l) {
            "MOONSHOT" -> when (feature) {
                Feature.IGNITION -> 1.55
                Feature.BREAKOUT_EXPANSION, Feature.RELATIVE_STRENGTH -> 1.35
                Feature.PARTICIPATION_EXPANSION, Feature.BUY_PRESSURE, Feature.LIQUIDITY_EXPANSION -> 1.25
                Feature.CHASE_RISK, Feature.EXHAUSTION, Feature.DISTRIBUTION -> 1.45
                else -> 1.0
            }
            "PROJECT_SNIPER", "EXPRESS" -> when (feature) {
                Feature.IGNITION, Feature.PARTICIPATION_EXPANSION -> 1.40
                Feature.CHASE_RISK, Feature.EXHAUSTION -> 1.35
                else -> 1.0
            }
            "DIP_HUNTER" -> when (feature) {
                Feature.RECLAIM -> 1.60
                Feature.DISTRIBUTION, Feature.EXHAUSTION -> 1.35
                Feature.BUY_PRESSURE, Feature.LIQUIDITY_EXPANSION -> 1.20
                else -> 1.0
            }
            "QUALITY", "BLUECHIP" -> when (feature) {
                Feature.EXITABILITY, Feature.ROUTE_QUALITY, Feature.HOLDER_QUALITY,
                Feature.MULTI_PROVIDER_AGREEMENT, Feature.CONTINUATION -> 1.25
                else -> 1.0
            }
            "TREASURY", "CASHGEN" -> when (feature) {
                Feature.EXITABILITY, Feature.ROUTE_QUALITY, Feature.LIQUIDITY_EXPANSION -> 1.40
                Feature.CHASE_RISK, Feature.EXHAUSTION -> 1.25
                else -> 1.0
            }
            "CYCLIC" -> when (feature) {
                Feature.IGNITION, Feature.BREAKOUT_EXPANSION, Feature.DISTRIBUTION,
                Feature.EXHAUSTION -> 1.30
                else -> 1.0
            }
            else -> 1.0
        }
    }

    private fun opportunityValues(
        lane: String,
        o: MarketSweep7297.OpportunitySignal?,
        mcapUsd: Double,
        liquidityUsd: Double,
        buyPressure: Double,
        providerAgreementFallback: Int,
        holderTopPct: Double,
        routeKnown: Boolean,
        priceChangeH1Pct: Double = 0.0,
    ): Map<Feature, Double> {
        val setup = o?.setup.orEmpty()
        val values = linkedMapOf<Feature, Double>()
        values[Feature.IGNITION] = if (setup == "EARLY_MOMENTUM_IGNITION") 1.0 else 0.0
        values[Feature.BREAKOUT_EXPANSION] = if (setup == "BREAKOUT_EXPANSION") 1.0 else 0.0
        values[Feature.RECLAIM] = if (setup == "DIP_RECOVERY") 1.0 else 0.0
        values[Feature.CONTINUATION] = if (setup == "CONTINUATION") 1.0 else 0.0
        values[Feature.RELATIVE_STRENGTH] = signed(o?.relativeStrengthPct ?: 0.0, 15.0)
        values[Feature.LIQUIDITY_EXPANSION] = signed(o?.liquidityDeltaPct ?: 0.0, 25.0)
        val volAccel = o?.volumeAcceleration ?: 1.0
        val txAccel = o?.txAcceleration ?: 1.0
        values[Feature.PARTICIPATION_EXPANSION] =
            (((volAccel - 1.0) / 1.5) * 0.60 + ((txAccel - 1.0) / 1.2) * 0.40).coerceIn(-1.0, 1.0)
        val bp = if (o != null) o.buyPressurePct else buyPressure
        values[Feature.BUY_PRESSURE] = signed(bp - 50.0, 25.0)
        val providers = o?.providerAgreement ?: providerAgreementFallback
        values[Feature.MULTI_PROVIDER_AGREEMENT] = when {
            providers >= 3 -> 1.0
            providers == 2 -> 0.55
            providers == 1 -> 0.0
            else -> -0.35
        }
        val ratio = if (mcapUsd > 0.0 && liquidityUsd > 0.0) mcapUsd / liquidityUsd else 999.0
        values[Feature.EXITABILITY] = when {
            ratio <= 8.0 -> 1.0
            ratio <= 25.0 -> 0.70
            ratio <= 60.0 -> 0.25
            ratio <= 120.0 -> -0.35
            else -> -0.80
        }
        values[Feature.ROUTE_QUALITY] = if (routeKnown) 0.75 else if (liquidityUsd > 0.0) 0.10 else -0.80
        values[Feature.HOLDER_QUALITY] = when {
            holderTopPct <= 0.0 -> 0.0
            holderTopPct < 15.0 -> 0.75
            holderTopPct < 30.0 -> 0.25
            holderTopPct < 50.0 -> -0.55
            else -> -1.0
        }
        values[Feature.DISTRIBUTION] = if (setup == "DISTRIBUTION") -1.0 else 0.0
        values[Feature.EXHAUSTION] = if (setup == "EXHAUSTION") -1.0 else 0.0
        val chaseMove = maxOf(abs(o?.priceVelocity5mPct ?: 0.0) * 4.0, abs(priceChangeH1Pct))
        values[Feature.CHASE_RISK] = when {
            setup == "EXHAUSTION" || setup == "DISTRIBUTION" -> -1.0
            chaseMove >= 100.0 -> -0.85
            chaseMove >= 60.0 -> -0.55
            chaseMove >= 35.0 -> -0.20
            else -> 0.15
        }
        return values
    }

    private fun buildSnapshot(ts: TokenState, lane: String, source: String, score: Int, confidence: Double): Snapshot {
        val l = canonicalLane(lane)
        val regime = try { RegimeDetector.currentRegime().name } catch (_: Throwable) { "UNKNOWN" }
        val o = try { MarketSweep7297.opportunityFor7777(ts.mint) } catch (_: Throwable) { null }
        val tm = ts.tokenMap
        val liq = ts.lastLiquidityUsd.takeIf { it.isFinite() && it > 0.0 }
            ?: tm.liquidityUsd?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val mcap = ts.lastMcap.takeIf { it.isFinite() && it > 0.0 }
            ?: tm.marketCap?.takeIf { it.isFinite() && it > 0.0 }
            ?: tm.fdv?.takeIf { it.isFinite() && it > 0.0 }
            ?: 0.0
        val top = ts.topHolderPct ?: ts.safety.topHolderPct.takeIf { it >= 0.0 } ?: 0.0
        val routeKnown = try {
            tm.pumpFunExecutable || tm.jupiterQuoteOk || tm.dexRouteOk || tm.expectedOutAmount > 0.0
        } catch (_: Throwable) { liq > 0.0 }
        val values = opportunityValues(
            lane = l,
            o = o,
            mcapUsd = mcap,
            liquidityUsd = liq,
            buyPressure = ts.lastBuyPressurePct,
            providerAgreementFallback = 0,
            holderTopPct = top,
            routeKnown = routeKnown,
        ).toMutableMap()
        values[Feature.REGIME_FIT] = regimeFit(l, regime)
        return Snapshot(
            mint = ts.mint,
            lane = l,
            mode = modeNow(),
            source = source.ifBlank { ts.source },
            regime = regime,
            score = score.coerceIn(0, 100),
            confidence = clamp01(confidence),
            values = values,
        )
    }

    private fun marketSnapshot(lane: String, r: MarketSweep7297.Row): Snapshot {
        val l = canonicalLane(lane)
        val regime = try { RegimeDetector.currentRegime().name } catch (_: Throwable) { "UNKNOWN" }
        val o = try { MarketSweep7297.opportunityFor7777(r.mint) } catch (_: Throwable) { null }
        val values = opportunityValues(
            lane = l,
            o = o,
            mcapUsd = r.mcapUsd,
            liquidityUsd = r.liquidityUsd,
            buyPressure = o?.buyPressurePct ?: 50.0,
            providerAgreementFallback = r.providers.size,
            holderTopPct = 0.0,
            routeKnown = r.liquidityUsd > 0.0,
            priceChangeH1Pct = r.priceChangeH1Pct,
        ).toMutableMap()
        values[Feature.REGIME_FIT] = regimeFit(l, regime)
        return Snapshot(r.mint, l, modeNow(), r.providers.joinToString("+"), regime, 50, 0.50, values)
    }

    private fun evidence(mode: String, lane: String, regime: String, feature: Feature, value: Double): Evidence? {
        fun one(m: String, r: String): Stat? = stats[statKey(m, lane, r, feature, value)]
        val exact = one(mode, regime)
        val agg = one(mode, "ALL")
        fun ev(s: Stat?): Evidence? {
            if (s == null || s.weight <= 0.0) return null
            val c = (s.weight / (s.weight + 8.0)).coerceIn(0.0, 1.0)
            return Evidence(s.meanPct(), c, s.runnerRate(), s.weight)
        }
        val e = ev(exact)
        val a = ev(agg)
        val own = when {
            e != null && a != null -> {
                val ew = e.confidence.coerceAtLeast(0.05)
                val aw = a.confidence.coerceAtLeast(0.05)
                Evidence(
                    meanPct = (e.meanPct * ew + a.meanPct * aw) / (ew + aw),
                    confidence = maxOf(e.confidence, a.confidence * 0.85),
                    runnerRate = (e.runnerRate * ew + a.runnerRate * aw) / (ew + aw),
                    weight = maxOf(e.weight, a.weight),
                )
            }
            e != null -> e
            else -> a
        }
        if (mode != "LIVE") return own
        val paper = ev(one("PAPER", regime)) ?: ev(one("PAPER", "ALL"))
        if (own == null) {
            return paper?.copy(
                meanPct = paper.meanPct * PAPER_TO_LIVE_WEIGHT,
                confidence = paper.confidence * PAPER_TO_LIVE_WEIGHT,
                runnerRate = paper.runnerRate * PAPER_TO_LIVE_WEIGHT,
            )
        }
        if (own.weight >= 8.0 || paper == null) return own
        val p = PAPER_TO_LIVE_WEIGHT * (1.0 - own.weight / 8.0).coerceIn(0.0, 1.0)
        return Evidence(
            meanPct = own.meanPct + paper.meanPct * p,
            confidence = (own.confidence + paper.confidence * p).coerceIn(0.0, 1.0),
            runnerRate = (own.runnerRate + paper.runnerRate * p).coerceIn(0.0, 1.0),
            weight = own.weight,
        )
    }

    private fun priorFor(s: Snapshot): Prior {
        if (s.values.isEmpty()) return Prior.NEUTRAL
        val active = s.values.filterValues { abs(it) >= 0.12 }
        if (active.isEmpty()) return Prior.NEUTRAL

        var doctrineSum = 0.0
        var doctrineWeight = 0.0
        var learnedSum = 0.0
        var learnedWeight = 0.0
        val parts = ArrayList<String>(active.size)

        active.forEach { (feature, value) ->
            val w = laneWeight(s.lane, feature)
            doctrineSum += value * w
            doctrineWeight += w
            val ev = evidence(s.mode, s.lane, s.regime, feature, value)
            if (ev != null && ev.confidence > 0.02) {
                var learned = (ev.meanPct / 25.0).coerceIn(-1.5, 1.5) * ev.confidence * w
                if (s.lane in setOf("MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "SHITCOIN") &&
                    ev.runnerRate > 0.10 && feature in setOf(
                        Feature.IGNITION, Feature.BREAKOUT_EXPANSION, Feature.CONTINUATION,
                        Feature.RELATIVE_STRENGTH, Feature.PARTICIPATION_EXPANSION,
                    )
                ) learned += ((ev.runnerRate - 0.10) * 1.5).coerceIn(0.0, 0.75)
                learnedSum += learned
                learnedWeight += w
                parts += "${feature.name.take(12)}:${"%+.1f".format(ev.meanPct)}%@${"%.2f".format(ev.confidence)}"
            }
        }

        val doctrine = if (doctrineWeight > 0.0) doctrineSum / doctrineWeight * 4.0 else 0.0
        val learned = if (learnedWeight > 0.0) learnedSum / learnedWeight * 4.0 else 0.0
        val delta = (doctrine + learned).coerceIn(-6.0, 6.0)
        val learnedConf = active.mapNotNull { (feature, value) ->
            evidence(s.mode, s.lane, s.regime, feature, value)?.confidence
        }.averageOrNull()
        val conf = (0.25 + (learnedConf ?: 0.0) * 0.75).coerceIn(0.0, 1.0)
        return Prior(
            deltaPct = delta,
            confidence = conf,
            label = "expert7813(${s.lane},d=${"%+.1f".format(doctrine)},l=${"%+.1f".format(learned)},c=${"%.2f".format(conf)})",
            contributions = parts.take(8),
        )
    }

    private fun Iterable<Double>.averageOrNull(): Double? {
        var n = 0
        var sum = 0.0
        for (v in this) { if (v.isFinite()) { sum += v; n++ } }
        return if (n > 0) sum / n else null
    }

    fun captureDecision7813(
        ts: TokenState?,
        lane: String,
        source: String,
        score: Int,
        confidence: Double,
    ): Prior {
        if (ts == null || ts.mint.isBlank()) return Prior.NEUTRAL
        ensureSubscribed()
        val s = buildSnapshot(ts, lane, source, score, confidence)
        pending[pendingKey(s.mode, s.lane, s.mint)] = s
        captures.incrementAndGet()
        prune()
        try { PipelineHealthCollector.labelInc("EXPERT_FEATURE_CAPTURED_7813_${s.lane}") } catch (_: Throwable) {}
        return priorFor(s)
    }

    fun peekPrior7813(mint: String, lane: String): Prior {
        if (mint.isBlank() || lane.isBlank()) return Prior.NEUTRAL
        ensureSubscribed()
        val mode = modeNow()
        val l = canonicalLane(lane)
        pending[pendingKey(mode, l, mint)]?.let { return priorFor(it) }
        val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return Prior.NEUTRAL
        return priorFor(buildSnapshot(ts, l, ts.source, ts.entryScore.toInt(), 0.50))
    }

    fun marketRankMultiplier7813(lane: String, row: MarketSweep7297.Row): Double {
        val p = priorFor(marketSnapshot(lane, row))
        return (1.0 + p.deltaPct / 24.0).coerceIn(0.75, 1.25)
    }

    fun bindPosition7813(positionId: String, mint: String, lane: String, mode: String): Boolean {
        if (positionId.isBlank() || mint.isBlank()) return false
        ensureSubscribed()
        val l = canonicalLane(lane)
        val canonicalMode = if (mode.equals("PAPER", true)) "PAPER" else "LIVE"
        val s = pending[pendingKey(canonicalMode, l, mint)] ?: run {
            val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return false
            buildSnapshot(ts, l, ts.source, ts.entryScore.toInt(), 0.50).copy(mode = canonicalMode)
        }
        byPosition[positionId] = s
        binds.incrementAndGet()
        if (byPosition.size > MAX_BOUND) {
            byPosition.entries.sortedBy { it.value.atMs }.take(byPosition.size - MAX_BOUND)
                .forEach { byPosition.remove(it.key, it.value) }
        }
        try { PipelineHealthCollector.labelInc("EXPERT_FEATURE_POSITION_BOUND_7813_${s.lane}") } catch (_: Throwable) {}
        return true
    }

    private fun updateStats(s: Snapshot, returnPct: Double, weight: Double) {
        if (!returnPct.isFinite() || weight <= 0.0) return
        val ret = returnPct.coerceIn(-95.0, StrategyTelemetry.LEARNABLE_GAIN_CEILING_PCT_7349)
        s.values.filterValues { abs(it) >= 0.12 }.forEach { (feature, value) ->
            for (regime in listOf(s.regime, "ALL")) {
                val st = stats.computeIfAbsent(statKey(s.mode, s.lane, regime, feature, value)) { Stat() }
                synchronized(st) {
                    st.weight += weight
                    st.sumReturnPct += ret * weight
                    if (ret > 0.0) st.wins += weight
                    if (ret >= 50.0) st.runners += weight
                }
            }
        }
    }

    private fun onFinalized(e: CanonicalTradeFinalizedBus6450.Event) {
        if (!CanonicalTradeFinalizedBus6450.isCleanForLearning7807(e)) return
        if (!settled.add(e.positionId)) return
        val s = byPosition.remove(e.positionId) ?: return
        updateStats(s, e.netReturnPct, 1.0)
        terminalGrades.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("EXPERT_FEATURE_TERMINAL_GRADED_7813_${s.lane}")
            ForensicLogger.lifecycle(
                "EXPERT_FEATURE_TERMINAL_GRADED_7813",
                "positionId=${e.positionId.take(20)} lane=${s.lane} ret=${"%+.2f".format(e.netReturnPct)} prior=${priorFor(s).label}",
            )
        } catch (_: Throwable) {}
    }

    fun recordForwardOutcome7813(
        mint: String,
        lane: String,
        netPct: Double,
        admitted: Boolean,
        observedAtMs: Long,
    ) {
        if (mint.isBlank() || lane.isBlank() || !netPct.isFinite()) return
        ensureSubscribed()
        val l = canonicalLane(lane)
        val candidates = listOf("LIVE", "PAPER").mapNotNull { pending[pendingKey(it, l, mint)] }
        val s = candidates
            .filter { observedAtMs <= 0L || it.atMs <= observedAtMs + 5_000L }
            .minByOrNull { kotlin.math.abs(it.atMs - observedAtMs) }
            ?: candidates.maxByOrNull { it.atMs }
            ?: return
        updateStats(s, netPct, COUNTERFACTUAL_WEIGHT)
        forwardGrades.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(
                "EXPERT_FEATURE_FORWARD_GRADED_7813_${if (admitted) "ADMITTED" else "REFUSED"}_${s.lane}"
            )
        } catch (_: Throwable) {}
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.atMs > PENDING_TTL_MS }
        if (pending.size > MAX_PENDING) {
            pending.entries.sortedBy { it.value.atMs }.take(pending.size - MAX_PENDING)
                .forEach { pending.remove(it.key, it.value) }
        }
    }

    fun statusLine(): String {
        val ranked = stats.entries.mapNotNull { (k, s) ->
            synchronized(s) {
                if (s.weight < 2.0 || !k.contains("|ALL|")) null
                else Triple(k, s.meanPct(), s.weight)
            }
        }
        val best = ranked.sortedByDescending { it.second }.take(3)
        val worst = ranked.sortedBy { it.second }.take(3)
        fun fmt(x: Triple<String, Double, Double>) =
            "${x.first.substringAfter("|").take(34)}=${"%+.1f".format(x.second)}%@${"%.1f".format(x.third)}"
        return "captures=${captures.get()} bound=${binds.get()} terminal=${terminalGrades.get()} " +
            "forward=${forwardGrades.get()} pending=${pending.size} boundOpen=${byPosition.size} stats=${stats.size} " +
            "best=[${best.joinToString(" · ") { fmt(it) }}] worst=[${worst.joinToString(" · ") { fmt(it) }}] " +
            "authority=ordering+bounded_prior no_safety_or_execution_authority=true"
    }

    fun reset() {
        stats.clear(); pending.clear(); byPosition.clear(); settled.clear()
        captures.set(0L); binds.set(0L); terminalGrades.set(0L); forwardGrades.set(0L)
    }

    private fun snapshotJson(s: Snapshot): JSONObject = JSONObject().apply {
        put("mint", s.mint); put("lane", s.lane); put("mode", s.mode); put("source", s.source)
        put("regime", s.regime); put("score", s.score); put("confidence", s.confidence); put("at", s.atMs)
        put("v", JSONObject().apply { s.values.forEach { (k, v) -> put(k.name, v) } })
    }

    private fun snapshotFromJson(o: JSONObject): Snapshot? = try {
        val values = linkedMapOf<Feature, Double>()
        val vo = o.optJSONObject("v") ?: JSONObject()
        val it = vo.keys()
        while (it.hasNext()) {
            val k = it.next()
            val f = try { Feature.valueOf(k) } catch (_: Throwable) { continue }
            values[f] = vo.optDouble(k, 0.0)
        }
        Snapshot(
            mint = o.optString("mint"), lane = o.optString("lane"), mode = o.optString("mode"),
            source = o.optString("source"), regime = o.optString("regime"), score = o.optInt("score", 0),
            confidence = o.optDouble("confidence", 0.5), values = values, atMs = o.optLong("at", System.currentTimeMillis()),
        ).takeIf { it.mint.isNotBlank() && it.lane.isNotBlank() }
    } catch (_: Throwable) { null }

    fun exportState(): String = try {
        JSONObject().apply {
            put("version", 7813)
            put("stats", JSONArray().apply {
                stats.forEach { (k, s) -> synchronized(s) {
                    put(JSONObject().put("k", k).put("w", s.weight).put("sum", s.sumReturnPct)
                        .put("wins", s.wins).put("runners", s.runners))
                } }
            })
            put("pending", JSONArray().apply { pending.values.take(MAX_PENDING).forEach { put(snapshotJson(it)) } })
            put("bound", JSONObject().apply { byPosition.forEach { (id, s) -> put(id, snapshotJson(s)) } })
        }.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(raw: String) {
        if (raw.isBlank() || raw == "{}") return
        try {
            val root = JSONObject(raw)
            val sa = root.optJSONArray("stats") ?: JSONArray()
            for (i in 0 until sa.length()) {
                val o = sa.optJSONObject(i) ?: continue
                val k = o.optString("k")
                if (k.isBlank()) continue
                stats[k] = Stat(o.optDouble("w", 0.0), o.optDouble("sum", 0.0),
                    o.optDouble("wins", 0.0), o.optDouble("runners", 0.0))
            }
            val pa = root.optJSONArray("pending") ?: JSONArray()
            for (i in 0 until pa.length()) {
                val s = snapshotFromJson(pa.optJSONObject(i) ?: continue) ?: continue
                pending[pendingKey(s.mode, s.lane, s.mint)] = s
            }
            val bo = root.optJSONObject("bound") ?: JSONObject()
            val it = bo.keys()
            while (it.hasNext()) {
                val id = it.next()
                val s = snapshotFromJson(bo.optJSONObject(id) ?: continue) ?: continue
                byPosition[id] = s
            }
            prune()
        } catch (_: Throwable) {}
    }
}
