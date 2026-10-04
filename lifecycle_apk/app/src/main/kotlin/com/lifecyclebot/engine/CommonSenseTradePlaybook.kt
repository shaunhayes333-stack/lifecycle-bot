package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.util.AppDispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4573 — operator common-sense crypto trading playbook.
 *
 * This is not an LLM, not a hot-path network fetch, and not a cosmetic report.
 * It is a cached data helper that turns the operator's trading basics into a
 * live pre-buy contract:
 *   - no real SOL when token map / route / price / liquidity / safety are unknown
 *   - no buy unless price is near a logical structure zone or a high-quality
 *     momentum setup with room to profit
 *   - weak confidence becomes size shaping, not whole-lane amputation
 *   - each candidate snapshot is refreshed on AppDispatchers.sideEffect so the
 *     executor hot path reads bounded local state only.
 */
object CommonSenseTradePlaybook {
    const val VERSION = "V5.0.4573_COMMON_SENSE_PLAYBOOK"

    data class Snapshot(
        val mint: String,
        val symbol: String,
        val lane: String,
        val style: String,
        val score: Double,
        val priceKnown: Boolean,
        val liquidityKnown: Boolean,
        val liquidityUsd: Double,
        val routeKnown: Boolean,
        val tokenMapComplete: Boolean,
        val safetyKnown: Boolean,
        val rugClean: Boolean,
        val holderAcceptable: Boolean,
        val logicalBuyZone: Boolean,
        val invalidationKnown: Boolean,
        val riskRewardAcceptable: Boolean,
        val tradeType: String,
        val confidence: String,
        val sizeMultiplier: Double,
        val reasons: List<String>,
        val hardSafetyBlocked: Boolean = false,
        val providerBlindSafety: Boolean = false,
        val holderHardRisk: Boolean = false,
        val dangerousStructure: Boolean = false,
        val brainSetup: String = "",
        val brainConfidence: Double = 0.0,
        val brainContext: String = "",
        val capturedAtMs: Long = System.currentTimeMillis(),
        val evidenceKey7432: String = "",
    )

    data class Verdict(
        val allowed: Boolean,
        val reason: String,
        val detail: String,
        val tradeType: String,
        val confidence: String,
        val sizeMultiplier: Double,
        val snapshot: Snapshot,
    )

    private val cache = ConcurrentHashMap<String, Snapshot>()
    private const val CACHE_TTL_MS = 8_000L
    private const val PLAN_MIN_RR_7783 = 1.5

    // A mint is shared by specialist desks. Cached conclusions must never
    // cross lane, generation, score, safety or price evidence boundaries.
    private fun evidenceKey7432(ts: TokenState, lane: String, style: String, score: Double): String =
        listOf(ts.mint, canon(lane), style, score,
            LaneExecutionCoordinator.candidateVersionFor(ts.mint),
            ts.lastPrice, ts.lastPriceUpdate, ts.lastPriceSource,
            ts.lastLiquidityUsd, ts.phase, ts.signal, ts.source,
            ts.lastSafetyCheck, ts.safety.checkedAt, ts.safety.tier,
            ts.safety.isBlocked, ts.safety.hardBlockReasons,
            ts.tokenMap.routeStatus, ts.tokenMap.hydrationComplete,
            ts.tokenMap.expectedOutAmount).joinToString("|")

    fun warmAsync(ts: TokenState, lane: String, style: String, score: Double) {
        val mint = ts.mint
        if (mint.isBlank()) return
        val key = evidenceKey7432(ts, lane, style, score)
        try {
            kotlinx.coroutines.GlobalScope.launch(AppDispatchers.sideEffect) {
                try {
                    val built = buildSnapshot(ts, lane, style, score).copy(evidenceKey7432 = key)
                    if (evidenceKey7432(ts, lane, style, score) == key) cache[mint] = built
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun assessPreBuy(
        ts: TokenState,
        lane: String,
        style: String,
        score: Double,
        basisTrusted: Boolean,
        routeTrustedFromStyle: Boolean,
    ): Verdict {
        val now = System.currentTimeMillis()
        var key7432 = evidenceKey7432(ts, lane, style, score)
        val cached = cache[ts.mint]
        var snap = if (cached != null && cached.evidenceKey7432 == key7432 &&
            now - cached.capturedAtMs in 0..CACHE_TTL_MS) cached
            else buildSnapshot(ts, lane, style, score).copy(evidenceKey7432 = key7432)
        if (evidenceKey7432(ts, lane, style, score) != key7432) {
            key7432 = evidenceKey7432(ts, lane, style, score)
            snap = buildSnapshot(ts, lane, style, score).copy(evidenceKey7432 = key7432)
        }
        if (evidenceKey7432(ts, lane, style, score) != key7432) {
            // Mutable market/safety state changed twice during construction.
            // Executor releases this attempt as a nonterminal evidence deferral.
            try { PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_INPUT_STALE_7432") } catch (_: Throwable) {}
            return Verdict(false, "EVIDENCE_CHANGED_DURING_PREBUY_7432", "dependency=market_or_safety_evidence_changed", snap.tradeType, snap.confidence, 0.0, snap)
        }
        if (cached != null && cached.evidenceKey7432 != key7432) try {
            PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_INPUT_STALE_7432")
            if (!cached.riskRewardAcceptable && snap.riskRewardAcceptable)
                PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_RR_FALSE_BLOCK_PREVENTED_7432")
            if (cached.dangerousStructure && !snap.dangerousStructure)
                PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_LIFECYCLE_STALE_STATE_PREVENTED_7432")
        } catch (_: Throwable) {}
        warmAsync(ts, lane, style, score)
        if (snap.priceKnown && snap.liquidityKnown && snap.safetyKnown)
            try { PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_INPUT_COMPLETE_7432") } catch (_: Throwable) {}
        else try { PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_INPUT_MISSING_7432") } catch (_: Throwable) {}

        fun deny(reason: String, extra: String = ""): Verdict {
            // Preserve the deciding input before verbose research/setup prose
            // truncates it in the live failure tile.
            val detail = (listOf("lane=${snap.lane} score=${snap.score} liq=${snap.liquidityUsd}", extra) + snap.reasons)
                .filter { it.isNotBlank() }.joinToString("|").take(240)
            if (reason == "RISK_REWARD_POOR" || reason == "LIFECYCLE_DANGER_NON_MANIPULATED_7425") try {
                val markAgeMs = if (ts.lastPriceUpdate > 0L) (now - ts.lastPriceUpdate).coerceAtLeast(0L) else -1L
                ForensicLogger.lifecycle("COMMON_SENSE_PREBUY_CAUSAL_INPUT_7432",
                    "mint=${snap.mint} lane=${snap.lane} style=${snap.style} reason=$reason " +
                    "candidateVersion=${LaneExecutionCoordinator.candidateVersionFor(snap.mint)} " +
                    "entryPrice=${ts.lastPrice} markSource=${ts.lastPriceSource} markAgeMs=$markAgeMs " +
                    "liquidityUsd=${snap.liquidityUsd} marketCapUsd=${ts.lastMcap} " +
                    "requestedSizeSol=executor_scope executableSizeSol=not_planned " +
                    "expectedSlippage=not_measured feeEstimate=not_quoted " +
                    "expectedUpside=not_calculated expectedDownside=not_calculated " +
                    "predictedEV=not_calculated stopDistance=not_available targetDistance=not_available " +
                    "rrMethod=score_liquidity_structure_heuristic rrAcceptable=${snap.riskRewardAcceptable} " +
                    "lifecyclePhase=${ts.phase} lifecycleAgeMs=not_verified " +
                    "sourceTimestamp=not_available signalTimestamp=not_available nowMs=$now " +
                    "priceMovementSinceSignal=not_available score=${snap.score} " +
                    "fdgSealedScore=executor_scope oracleState=not_attached " +
                    "safetyKnown=${snap.safetyKnown} liquidityKnown=${snap.liquidityKnown} " +
                    "priceKnown=${snap.priceKnown} tokenMapComplete=${snap.tokenMapComplete} " +
                    "evidenceKey=${snap.evidenceKey7432.hashCode()}")
                PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_RR_CALC_7432")
            } catch (_: Throwable) {}
            try {
                ForensicLogger.lifecycle(
                    "COMMON_SENSE_PREBUY_BLOCK_4573",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=${snap.lane} style=${snap.style} reason=$reason tradeType=${snap.tradeType} detail=$detail",
                )
                PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_BLOCK_4573_$reason")
                PipelineHealthCollector.labelInc("COMMON_SENSE_TRADETYPE_${snap.tradeType}")
            } catch (_: Throwable) {}
            return Verdict(false, reason, detail, snap.tradeType, snap.confidence, 0.0, snap)
        }

        if (!basisTrusted || !snap.priceKnown) return deny("PRICE_BASIS_UNKNOWN", "basisTrusted=$basisTrusted priceKnown=${snap.priceKnown}")
        if (!snap.liquidityKnown) return deny("LIQUIDITY_UNKNOWN", "liq=${snap.liquidityUsd}")
        if (!snap.routeKnown || !routeTrustedFromStyle) return deny("SELL_ROUTE_UNKNOWN", "routeKnown=${snap.routeKnown} routeTrustedFromStyle=$routeTrustedFromStyle")
        if (!snap.tokenMapComplete) return deny("TOKEN_MAP_INCOMPLETE", "tokenMapComplete=false")
        fun allowShaped(reason: String, mult: Double, extra: String): Verdict {
            val shaped = mult.coerceIn(0.35, 1.0)
            val detail = (snap.reasons + extra).filter { it.isNotBlank() }.joinToString("|").take(240)
            try {
                ForensicLogger.lifecycle(
                    "COMMON_SENSE_PREBUY_SHAPED_4575",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=${snap.lane} style=${snap.style} reason=$reason tradeType=${snap.tradeType} conf=${snap.confidence} mult=${"%.2f".format(shaped)} detail=$detail action=trade_setup_pivot_not_block",
                )
                PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_SHAPED_4575")
                PipelineHealthCollector.labelInc("COMMON_SENSE_SHAPED_$reason")
                PipelineHealthCollector.labelInc("COMMON_SENSE_TRADETYPE_${snap.tradeType}")
            } catch (_: Throwable) {}
            return Verdict(true, reason, detail, snap.tradeType, "LOW", minOf(snap.sizeMultiplier, shaped), snap)
        }

        val setupKnown = snap.tradeType != "NO_STRUCTURE"
        // V5.0.7403 — a label is not proof. Only a setup with an actual
        // logical zone, known invalidation and acceptable R:R may soften
        // provider-blind uncertainty.
        val structureProven7403 = setupKnown && snap.logicalBuyZone &&
            snap.invalidationKnown && snap.riskRewardAcceptable && !snap.dangerousStructure
        val tradeableSetup = structureProven7403 && snap.liquidityUsd >= 500.0 &&
            snap.routeKnown && snap.tokenMapComplete

        // V5.0.6020 — fluid score doctrine. Score floors are scaffolding:
        // soft while the lane is bootstrapping, tighter during advisory calibration,
        // then fade out as UnifiedPolicyHead becomes LEARNED/AUTHORITATIVE.
        val agiAuthority6020 = try { UnifiedPolicyHead.currentAuthority(snap.lane) } catch (_: Throwable) { UnifiedPolicyHead.AuthorityTier.BOOTSTRAP }
        fun fluidScore6020(base: Double): Double = when (agiAuthority6020) {
            UnifiedPolicyHead.AuthorityTier.BOOTSTRAP -> (base - 18.0).coerceAtLeast(25.0)
            UnifiedPolicyHead.AuthorityTier.ADVISORY -> (base + 4.0).coerceAtMost(72.0)
            UnifiedPolicyHead.AuthorityTier.LEARNED -> (base - 12.0).coerceAtLeast(20.0)
            UnifiedPolicyHead.AuthorityTier.AUTHORITATIVE -> (base - 20.0).coerceAtLeast(25.0)
        }
        // V5.0.7403 — lane identity is not setup proof. A "good" lane
        // cannot manufacture a buy zone merely because it historically behaved well.
        val structureBackedLane7403 = snap.brainSetup.isNotBlank() &&
            snap.brainSetup !in setOf("REGIME_DEFENSIVE_PROBE", "MEV_PROTECTED_ENTRY", "NO_STRUCTURE")
        // V5.0.4585 — source choke fix. True hard safety still blocks, but
        // provider-blind safety/holder uncertainty no longer kills almost every
        // FDG-allowed lane after Executor starts. The 4584 report showed
        // COMMON_SENSE_PREBUY_SAFETY_OR_HOLDER_RISK=176 alongside holder-proof
        // blind pressure=190. Unknown/pending provider state becomes a lane-local
        // tactic/size pivot when the setup, route, token map and liquidity exist.
        if (snap.hardSafetyBlocked || snap.holderHardRisk) {
            return deny("TRUE_HARD_SAFETY_OR_HOLDER_RISK", "hardSafety=${snap.hardSafetyBlocked} holderHard=${snap.holderHardRisk} safetyKnown=${snap.safetyKnown} rugClean=${snap.rugClean} holders=${snap.holderAcceptable}")
        }
        // V5.0.7425 — lifecycle danger is not a normal-lane dip signal.
        // POST_PUMP_EXHAUSTION / free-fall / breakdown may only be considered
        // by the explicitly MANIPULATED desk. DIP_HUNTER/QUALITY must wait for
        // the stage router's reclaim confirmation instead of catching the knife.
        if (!snap.lane.equals("MANIPULATED", true) &&
            (snap.tradeType == "POST_PUMP_EXHAUSTION" || snap.dangerousStructure)) {
            return deny(
                "LIFECYCLE_DANGER_NON_MANIPULATED_7425",
                "lane=${snap.lane} tradeType=${snap.tradeType} dangerous=${snap.dangerousStructure}",
            )
        }
        if (!snap.safetyKnown || !snap.rugClean || !snap.holderAcceptable) {
            if (tradeableSetup && snap.score >= fluidScore6020(55.0)) {
                return allowShaped(
                    "SAFETY_HOLDER_UNCONFIRMED_TACTIC_PIVOT",
                    0.50,
                    "providerBlind=${snap.providerBlindSafety} safetyKnown=${snap.safetyKnown} rugClean=${snap.rugClean} holders=${snap.holderAcceptable} tradeType=${snap.tradeType}",
                )
            }
            return deny("SAFETY_OR_HOLDER_RISK", "safetyKnown=${snap.safetyKnown} rugClean=${snap.rugClean} holders=${snap.holderAcceptable}")
        }
        // V5.0.7783 — one setup authority, not two. FinalDecisionGate's
        // TradePlan7739 admitted this mint with a named setup, a stop and
        // targets: that IS the buy zone, the invalidation and the R:R this
        // playbook asks for. The 5.0.7781 fresh live run admitted 43 plans and
        // this check then refused 24 of the resulting buys as
        // NO_LOGICAL_BUY_ZONE / RISK_REWARD_POOR, re-deriving "structure" from
        // phase text. Hard safety, rug/holder, and post-pump danger above stay
        // binding. Field Manual: define entry, stop and target before entry —
        // the plan did; refusing it again is a second opinion, not a gate.
        val plan7783 = try { com.lifecyclebot.engine.truth.TradePlan7739.freshPlan7783(ts.mint, now) } catch (_: Throwable) { null }
        if (plan7783 != null && snap.liquidityUsd >= 500.0) {
            val stop7783 = kotlin.math.abs(plan7783.stopPnlPct)
            val rr7783 = if (stop7783 > 0.0) plan7783.firstTargetPnlPct / stop7783 else 0.0
            if (rr7783 >= PLAN_MIN_RR_7783) {
                try {
                    ForensicLogger.lifecycle(
                        "COMMON_SENSE_PLAN_ZONE_7783",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=${snap.lane} setup=${plan7783.setup.name} stop=-${"%.1f".format(stop7783)}% first=+${"%.1f".format(plan7783.firstTargetPnlPct)}% rr=${"%.2f".format(rr7783)} tradeType=${snap.tradeType} zone=${snap.logicalBuyZone} rrHeuristic=${snap.riskRewardAcceptable}",
                    )
                    PipelineHealthCollector.labelInc("COMMON_SENSE_PLAN_ZONE_7783_${plan7783.setup.name}")
                    PipelineHealthCollector.labelInc("COMMON_SENSE_TRADETYPE_${snap.tradeType}")
                } catch (_: Throwable) {}
                return Verdict(true, "PLAN_ZONE_7783", "setup=${plan7783.setup.name} rr=${"%.2f".format(rr7783)}", snap.tradeType, snap.confidence, snap.sizeMultiplier, snap)
            }
        }
        if (!snap.logicalBuyZone) {
            val liquidExecutable = snap.liquidityUsd >= 1_500.0 && snap.routeKnown && snap.tokenMapComplete
            if (structureBackedLane7403 && liquidExecutable && !snap.dangerousStructure &&
                snap.score >= fluidScore6020(58.0)) {
                return allowShaped(
                    "STRUCTURE_UNCERTAIN_BUT_BRAIN_SETUP_PRESENT_7403",
                    0.35,
                    "lane=${snap.lane} setup=${snap.brainSetup} agiAuth=${agiAuthority6020.name} tradeType=${snap.tradeType}",
                )
            }
            return deny("NO_LOGICAL_BUY_ZONE", "tradeType=${snap.tradeType} lane=${snap.lane} setup=${snap.brainSetup} agiAuth=${agiAuthority6020.name}")
        }

        if (!snap.invalidationKnown) {
            val earlyLaunchImplicit7403 = snap.tradeType == "NEW_TOKEN_EARLY_LIFECYCLE" &&
                !snap.dangerousStructure && snap.logicalBuyZone
            if (earlyLaunchImplicit7403) {
                return allowShaped("EARLY_LAUNCH_IMPLICIT_INVALIDATION_7403", 0.50,
                    "invalidation=launch_flow_failure_or_recent_low tradeType=" + snap.tradeType)
            }
            return deny("NO_CLEAR_INVALIDATION", "tradeType=" + snap.tradeType)
        }
        if (!snap.riskRewardAcceptable) {
            return deny("RISK_REWARD_POOR", "score=" + snap.score + " liq=" + snap.liquidityUsd)
        }

        try {
            ForensicLogger.lifecycle(
                "COMMON_SENSE_PREBUY_ALLOW_4573",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=${snap.lane} style=${snap.style} tradeType=${snap.tradeType} conf=${snap.confidence} mult=${"%.2f".format(snap.sizeMultiplier)} reasons=${snap.reasons.joinToString("|").take(180)}",
            )
            PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_ALLOW_4573")
            PipelineHealthCollector.labelInc("COMMON_SENSE_TRADETYPE_${snap.tradeType}")
        } catch (_: Throwable) {}
        return Verdict(true, "ALLOW", snap.reasons.joinToString("|").take(240), snap.tradeType, snap.confidence, snap.sizeMultiplier, snap)
    }

    fun statusLine(): String = try {
        val rows = cache.values.sortedByDescending { it.capturedAtMs }.take(8)
        if (rows.isEmpty()) "$VERSION cache=empty background_only=true"
        else "$VERSION cache=${rows.size} " + rows.joinToString(" · ") { "${it.symbol.take(8)}:${it.lane}/${it.tradeType}/${it.confidence}×${"%.2f".format(it.sizeMultiplier)}" }
    } catch (_: Throwable) { "$VERSION unavailable" }

    private fun buildSnapshot(ts: TokenState, laneRaw: String, styleRaw: String, scoreRaw: Double): Snapshot {
        val lane = canon(laneRaw.ifBlank { ts.position.tradingMode.ifBlank { "STANDARD" } })
        val style = styleRaw.ifBlank { lane }
        val score = scoreRaw.coerceIn(0.0, 100.0)
        val priceKnown = ts.lastPrice.isFinite() && ts.lastPrice > 0.0
        val liq = ts.lastLiquidityUsd.takeIf { it.isFinite() } ?: 0.0
        val liquidityKnown = liq > 0.0
        val tokenMap = try { TokenMapAuthority.ensureDiscoveryTokenMap(ts, ts.source) } catch (_: Throwable) { ts.tokenMap }
        val liqVerdict = try { TokenMapAuthority.liquidityVerdict(ts) } catch (_: Throwable) { null }
        val routeKnown = try { TokenMapAuthority.executableForLiveBuy(ts) } catch (_: Throwable) { false } || liqVerdict?.executable == true || tokenMap.expectedOutAmount > 0.0
        val tokenMapComplete = try {
            tokenMap.hydrationComplete && !tokenMap.routeStatus.equals("NO_ROUTE", true) && !tokenMap.routeStatus.equals("UNKNOWN", true) && routeKnown
        } catch (_: Throwable) { routeKnown }
        val safety = ts.safety
        val rc = safety.rugcheckStatus.uppercase(Locale.US)
        val safetyKnown = ts.lastSafetyCheck > 0L || safety.checkedAt > 0L
        val hardSafetyBlocked = safety.isBlocked || safety.tier == SafetyTier.HARD_BLOCK || safety.hardBlockReasons.isNotEmpty()
        val providerBlindSafety = !hardSafetyBlocked && (!safetyKnown || safety.rugcheckScore == 0 || rc in setOf("UNKNOWN", "TIMEOUT", "PENDING", "PENDING_REVIEW", "ERROR"))
        val rugClean = !hardSafetyBlocked && !providerBlindSafety
        val topHolder = try { listOfNotNull(ts.topHolderPct, safety.topHolderPct.takeIf { it >= 0.0 }).maxOrNull() ?: -1.0 } catch (_: Throwable) { -1.0 }
        val holderHardRisk = topHolder >= 55.0 || safety.summary.contains("holder concentration hard", true) || safety.summary.contains("holder hard", true)
        val holderAcceptable = !holderHardRisk && (topHolder < 0.0 || topHolder <= 35.0) && !safety.summary.contains("top holder", true)

        // V5.0.6021 — CommonSense was data blind: it only read lane/style/phase/source,
        // while ToolkitSignalSheet/ResearchScout/UltimateEdge already held cached chart,
        // setup, lane-vote and research context. Read cached/in-memory brains only; no
        // hot-path provider/LLM/network call. snapshot() may schedule side-effect refresh.
        val toolkit6021 = try { ToolkitSignalSheet.snapshot(ts) } catch (_: Throwable) { null }
        val toolkitText6021 = try {
            listOfNotNull(
                toolkit6021?.setup?.name,
                toolkit6021?.chartPattern,
                toolkit6021?.entryStyle,
                toolkit6021?.exitStyle,
                toolkit6021?.laneVotes?.joinToString("_"),
                toolkit6021?.toolVotes?.joinToString("_"),
                toolkit6021?.compactReason,
            ).joinToString(" ")
        } catch (_: Throwable) { "" }
        val researchText6021 = try { ResearchScout.riskHint(ts.mint).take(180) } catch (_: Throwable) { "" }
        val edgeText6021 = try { UltimateEdgeEngine.cached(ts.mint, lane)?.compact()?.take(220) ?: "" } catch (_: Throwable) { "" }
        try { UltimateEdgeEngine.enqueueRefresh(ts.mint, ts.symbol, lane, ts.source, score.toInt(), "common_sense_brain_context_6021") } catch (_: Throwable) {}
        val policyAuth6021 = try { UnifiedPolicyHead.currentAuthority(lane).name } catch (_: Throwable) { "BOOTSTRAP" }
        val text = listOf(
            lane, style, ts.phase, ts.signal, ts.source, tokenMap.routeStatus,
            try { ts.meta.emafanAlignment } catch (_: Throwable) { "" },
            toolkitText6021,
            researchText6021,
            edgeText6021,
            "POLICY_$policyAuth6021",
        ).joinToString(" ").uppercase(Locale.US).replace('-', '_')

        // V5.0.7403 — do not let lane/source identity manufacture its own proof.
        // DIP_HUNTER is not evidence of a reclaim; PUMP_FUN is not evidence that
        // the token is still early; SMART/WHALE text is not proof of accumulation.
        val structureText7403 = listOf(
            ts.phase,
            ts.signal,
            try { ts.meta.emafanAlignment } catch (_: Throwable) { "" },
            toolkit6021?.setup?.name ?: "",
            toolkit6021?.chartPattern ?: "",
            toolkit6021?.entryStyle ?: "",
            toolkit6021?.compactReason ?: "",
            researchText6021,
            edgeText6021,
        ).joinToString(" ").uppercase(Locale.US).replace('-', '_')
        val launch7403 = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
        val brainConfidence6021 = maxOf(score, toolkit6021?.confidence ?: 0.0).coerceIn(0.0, 100.0)
        val tradeType = when {
            launch7403?.tooLateForSnipe == true -> "POST_PUMP_EXHAUSTION"
            launch7403?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION ||
                launch7403?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION ->
                "NEW_TOKEN_EARLY_LIFECYCLE"
            else -> classifyTradeType(structureText7403, brainConfidence6021, liq)
        }
        val lateChase = launch7403?.tooLateForSnipe == true ||
            structureText7403.contains("OVEREXTENDED") || structureText7403.contains("VERTICAL") ||
            structureText7403.contains("CHASE") || structureText7403.contains("FREE_FALL") ||
            structureText7403.contains("FREEFALL")
        val breakdown = structureText7403.contains("BREAKDOWN") &&
            !structureText7403.contains("FAILED_BREAKDOWN") &&
            !structureText7403.contains("RECLAIM")
        val logicalBuyZone = tradeType != "NO_STRUCTURE" && !lateChase && !breakdown
        val invalidationKnown = logicalBuyZone && (text.contains("SUPPORT") || text.contains("VWAP") || text.contains("EMA") || text.contains("RETEST") || text.contains("RANGE") || text.contains("SWEEP") || text.contains("RECLAIM") || text.contains("HIGHER_LOW") || tradeType in setOf("NEW_TOKEN_EARLY_LIFECYCLE", "MOMENTUM_SCALP", "ACCUMULATION_BREAKOUT", "LIQUIDITY_DEPTH_QUALITY", "NARRATIVE_ROTATION", "WHALE_ACCUMULATION_FOLLOW"))
        // V5.0.7719 §THE_GATE_KNEW_THINGS_THE_BOT_HAD_NOT_LEARNED.
        //
        // Operator, on 26 RISK_REWARD_POOR refusals in a 4-minute live window
        // (5.0.7716): "moonshot Sniper etc target as low as those ranges.
        // scoring is meant to be fluid with a rough start of around 15 and
        // $1500 market cap. remember the bot is supposed to have a chance to
        // learn but will not if scoring is set like the bot already knows and
        // has an intelligence base." The 42-58 score / $1,000-1,500 liquidity
        // bars below are the MATURE end. For the launch lanes they now start
        // at the lane's cold-start score prior (ColdStartPriors: 15-20) and
        // $500 of liquidity (the sellability floor), and walk to the mature
        // bars with FluidLearningAI's learning progress, exactly as the lane
        // scorers themselves do. Non-launch lanes keep the mature bars.
        val launchLane7719 = canon(lane).let {
            it.contains("MOONSHOT") || it.contains("SHITCOIN") || it.contains("SNIPER") ||
                it.contains("MANIPULATED") || it.contains("EXPRESS") || it.contains("PUMP")
        }
        val progress7719 = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress().coerceIn(0.0, 1.0)
        } catch (_: Throwable) { 1.0 }
        val coldScore7719 = try { ColdStartPriors.coldStartScoreFloor(canon(lane)).toDouble() } catch (_: Throwable) { 15.0 }
        fun fluid7719(bootstrap: Double, mature: Double): Double =
            if (launchLane7719) bootstrap + (mature - bootstrap) * progress7719 else mature
        if (launchLane7719 && progress7719 < 1.0) {
            try { PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_FLUID_LAUNCH_FLOOR_7719") } catch (_: Throwable) {}
        }
        val riskRewardAcceptable = when {
            !liquidityKnown || liq < 500.0 -> false
            lateChase || breakdown -> false
            tradeType == "MOMENTUM_SCALP" -> score >= fluid7719(coldScore7719, 58.0) && liq >= fluid7719(500.0, 1_500.0)
            tradeType == "NEW_TOKEN_EARLY_LIFECYCLE" -> score >= fluid7719(coldScore7719, 52.0) && liq >= fluid7719(500.0, 1_500.0)
            tradeType in setOf("ACCUMULATION_BREAKOUT", "LIQUIDITY_DEPTH_QUALITY", "PULLBACK_BUY", "VWAP_RECLAIM", "EMA_RECLAIM", "HIGHER_LOW_CONTINUATION") -> score >= fluid7719(coldScore7719, 38.0) && liq >= fluid7719(500.0, 1_000.0)
            tradeType == "POST_PUMP_EXHAUSTION" -> false
            else -> score >= fluid7719(coldScore7719, 42.0) && liq >= fluid7719(500.0, 1_000.0)
        }
        val reasons = mutableListOf<String>()
        if (priceKnown) reasons += "price_known" else reasons += "price_unknown"
        if (liquidityKnown) reasons += "liq=${liq.toInt()}" else reasons += "liq_unknown"
        if (routeKnown) reasons += "route_known" else reasons += "route_unknown"
        if (tokenMapComplete) reasons += "token_map_complete" else reasons += "token_map_incomplete"
        if (rugClean) reasons += "rug_clean" else if (hardSafetyBlocked) reasons += "rug_hard_blocked" else reasons += "rug_provider_blind_or_pending"
        if (holderAcceptable) reasons += "holders_ok" else if (holderHardRisk) reasons += "holders_hard_risk" else reasons += "holders_soft_or_unknown"
        reasons += "tradeType=$tradeType"
        if (toolkit6021 != null) reasons += "brainSetup=${toolkit6021.setup.name}:conf=${toolkit6021.confidence.toInt()}:chart=${toolkit6021.chartPattern.take(32)}"
        if (researchText6021.isNotBlank()) reasons += "research=${researchText6021.take(48)}"
        if (edgeText6021.isNotBlank()) reasons += "edge=${edgeText6021.take(48)}"
        val confidence = when {
            score >= 70.0 && liq >= 10_000.0 && tradeType !in setOf("MOMENTUM_SCALP", "NEW_TOKEN_EARLY_LIFECYCLE") -> "HIGH"
            score >= 58.0 && liq >= 1_500.0 -> "MEDIUM"
            else -> "LOW"
        }
        val sizeMult = when (confidence) {
            "HIGH" -> 1.0
            "MEDIUM" -> 0.72
            else -> 0.35
        }
        return Snapshot(ts.mint, ts.symbol, lane, style, score, priceKnown, liquidityKnown, liq, routeKnown, tokenMapComplete, safetyKnown, rugClean, holderAcceptable, logicalBuyZone, invalidationKnown, riskRewardAcceptable, tradeType, confidence, sizeMult, reasons, hardSafetyBlocked, providerBlindSafety, holderHardRisk, lateChase || breakdown, toolkit6021?.setup?.name ?: "", toolkit6021?.confidence ?: 0.0, listOf(toolkitText6021, researchText6021, edgeText6021).filter { it.isNotBlank() }.joinToString(" | ").take(320))
    }

    private fun classifyTradeType(text: String, score: Double, liq: Double): String = when {
        text.contains("PULLBACK_RECLAIM") || (text.contains("PULLBACK") && text.contains("RECLAIM")) -> "PULLBACK_BUY"
        text.contains("BREAKOUT") && text.contains("RETEST") -> "BREAKOUT_RETEST"
        text.contains("RANGE_LOW") || (text.contains("RANGE") && text.contains("SUPPORT")) -> "RANGE_LOW_BUY"
        text.contains("SWEEP") && text.contains("RECLAIM") -> "LIQUIDITY_SWEEP_REVERSAL"
        text.contains("VWAP") && text.contains("RECLAIM") -> "VWAP_RECLAIM"
        text.contains("EMA") && text.contains("RECLAIM") -> "EMA_RECLAIM"
        text.contains("HIGHER_LOW") || text.contains("TREND_CONTINUATION") -> "HIGHER_LOW_CONTINUATION"
        text.contains("ACCUMULATION") || text.contains("COMPRESSION") || text.contains("BASE") -> "ACCUMULATION_BREAKOUT"
        text.contains("CAPITULATION") || text.contains("PANIC_REVERSION") -> "CAPITULATION_BOUNCE"
        text.contains("FAILED_BREAKDOWN") -> "FAILED_BREAKDOWN_REVERSAL"
        text.contains("LIQUIDITY_DEPTH_QUALITY") -> "LIQUIDITY_DEPTH_QUALITY"
        text.contains("NARRATIVE") || text.contains("SECTOR") -> "NARRATIVE_ROTATION"
        (text.contains("WHALE_ACCUMULATION") || text.contains("SMART_WALLET_ACCUMULATION")) && !text.contains("SELLING") -> "WHALE_ACCUMULATION_FOLLOW"
        text.contains("MOMENTUM") || text.contains("DEGEN_MICRO_SNIPE") -> "MOMENTUM_SCALP"
        else -> "NO_STRUCTURE"
    }

    private fun canon(raw: String): String = try { LiveGrowthDoctrine.canonicalLane(raw) } catch (_: Throwable) { raw.uppercase(Locale.US).replace('-', '_').replace(' ', '_') }
}
