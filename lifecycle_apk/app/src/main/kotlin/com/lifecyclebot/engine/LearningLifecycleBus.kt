package com.lifecyclebot.engine

/**
 * V5.0.4534 — source-level lifecycle learning bus.
 *
 * Tiny hot-path facade over MathematicalEdgeEngine. It standardizes labels for
 * candidate/reject/admit/sizing/fill/exit/terminal exposure so learning engines
 * receive the causal decision stream, not only terminal result rows.
 *
 * No I/O, no API/LLM calls, no trade authority. Heavy consumers stay behind
 * MathematicalEdgeEngine / ChokeReliefBus coroutine fanout.
 */
object LearningLifecycleBus {
    const val VERSION = "V5.0.4534_LEARNING_LIFECYCLE_BUS"

    fun preFdgCandidate(
        lane: String,
        source: String,
        mint: String,
        symbol: String,
        reason: String,
        score: Double,
        confidence: Double,
        liquidityUsd: Double,
        marketCapUsd: Double,
        regime: String,
    ) = entry("pre_fdg_candidate", lane, source, mint, symbol, "CANDIDATE", reason, score, confidence, liquidityUsd, marketCapUsd, regime = regime)

    fun preFdgReject(
        label: String,
        lane: String,
        source: String,
        mint: String,
        symbol: String,
        reason: String,
        score: Double,
        confidence: Double,
        liquidityUsd: Double,
        marketCapUsd: Double,
        regime: String,
    ) = entry("pre_fdg_reject", lane, source, mint, symbol, label, reason, score, confidence, liquidityUsd, marketCapUsd, regime = regime)

    fun preFdgProbe(
        label: String,
        lane: String,
        source: String,
        mint: String,
        symbol: String,
        reason: String,
        score: Double,
        confidence: Double,
        liquidityUsd: Double,
        marketCapUsd: Double,
        finalSol: Double,
        regime: String,
    ) = entry("pre_fdg_probe", lane, source, mint, symbol, label, reason, score, confidence, liquidityUsd, marketCapUsd, finalSol = finalSol, regime = regime)

    fun preFdgAdmit(
        lane: String,
        source: String,
        mint: String,
        symbol: String,
        score: Double,
        confidence: Double,
        liquidityUsd: Double,
        marketCapUsd: Double,
        regime: String,
        style: String = lane,
    ) = entry("pre_fdg_admit", lane, source, mint, symbol, "BUY_QUALIFIED", "", score, confidence, liquidityUsd, marketCapUsd, regime = regime, style = style)

    fun entry(
        stage: String,
        lane: String,
        source: String,
        mint: String,
        symbol: String,
        decision: String,
        reason: String,
        score: Double,
        confidence: Double,
        liquidityUsd: Double,
        marketCapUsd: Double,
        proposedSol: Double = -1.0,
        finalSol: Double = -1.0,
        regime: String = "",
        style: String = "",
    ) {
        try {
            PipelineHealthCollector.labelInc("LEARNING_LIFECYCLE_${stage.uppercase().take(32)}")
            if (decision.isNotBlank()) PipelineHealthCollector.labelInc("LEARNING_LIFECYCLE_DECISION_${decision.uppercase().take(40)}")
        } catch (_: Throwable) {}
        try {
            MathematicalEdgeEngine.captureEntryOpportunity(
                stage = stage,
                lane = lane,
                source = source,
                mint = mint,
                symbol = symbol,
                decision = decision,
                reason = reason,
                score = score,
                confidence = confidence,
                liquidityUsd = liquidityUsd,
                marketCapUsd = marketCapUsd,
                proposedSol = proposedSol,
                finalSol = finalSol,
                style = style,
                regime = regime,
            )
        } catch (_: Throwable) {}
    }


    fun scorerComponents(
        scoringPath: String,
        candidate: com.lifecyclebot.v3.scanner.CandidateSnapshot,
        components: List<com.lifecyclebot.v3.scoring.ScoreComponent>,
    ) {
        val total = try { components.sumOf { it.value } } catch (_: Throwable) { 0 }
        val topPositive = try { components.filter { it.value > 0 }.sortedByDescending { it.value }.take(4).joinToString("|") { "${it.name}:${it.value}" } } catch (_: Throwable) { "" }
        val topNegative = try { components.filter { it.value < 0 }.sortedBy { it.value }.take(4).joinToString("|") { "${it.name}:${it.value}" } } catch (_: Throwable) { "" }
        val fatalNames = try { components.filter { it.fatal }.joinToString("|") { it.name } } catch (_: Throwable) { "" }
        val decision = when {
            fatalNames.isNotBlank() -> "SCORER_FATAL"
            total >= 55 -> "SCORER_STRONG_BUY"
            total >= 25 -> "SCORER_POSITIVE"
            total <= -20 -> "SCORER_NEGATIVE"
            else -> "SCORER_NEUTRAL"
        }
        entry(
            stage = "v3_scorer_components",
            lane = "V3_SCORER",
            source = candidate.source.name,
            mint = candidate.mint,
            symbol = candidate.symbol,
            decision = decision,
            reason = "path=$scoringPath total=$total pos=$topPositive neg=$topNegative fatal=$fatalNames components=${components.size}",
            score = total.toDouble(),
            confidence = ((components.count { kotlin.math.abs(it.value) >= 3 } * 100.0) / components.size.coerceAtLeast(1)).coerceIn(0.0, 100.0),
            liquidityUsd = candidate.liquidityUsd,
            marketCapUsd = candidate.marketCapUsd,
            regime = try { RegimeDetector.currentRegime().name } catch (_: Throwable) { "UNKNOWN" },
            style = scoringPath,
        )
    }

    fun status(): String = "${VERSION} signals=candidate,reject,probe,admit,sizing,fill,exit,terminal source_level=true coroutine_consumers=MathematicalEdgeEngine+ChokeReliefBus no_trade_authority=true"
}
