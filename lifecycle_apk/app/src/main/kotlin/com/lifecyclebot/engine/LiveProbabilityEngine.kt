package com.lifecyclebot.engine

/**
 * V5.0.4027 — LIVE PROBABILITY ENGINE FACADE.
 *
 * The bot already had probability brains, but they were scattered:
 * ForwardOutcomeModel, UnifiedPolicyHead, StrategyTelemetry lane priors, and
 * live strategy tuning each exposed a different shape. This facade gives sizing,
 * FDG/reporting, and future tuner work one probability object:
 *
 *   pWin / pRug / E[pnl] / uncertainty / samples / soft size multiplier
 *
 * Doctrine:
 *  - Live-terminal learning only for lane priors.
 *  - No external calls, no LLM, no synchronous network.
 *  - Soft-shape only: never veto, never zero size.
 *  - Bootstrap-safe: neutral until evidence exists.
 */
object LiveProbabilityEngine {
    const val VERSION = "V5.0.4030_LIVE_PROBABILITY_ENGINE"

    data class Edge(
        val lane: String,
        val pWin: Double,
        val pRug: Double,
        val expectedPnlPct: Double,
        val uncertaintyPct: Double,
        val samples: Long,
        val source: String,
        val sizeMult: Double,
        val note: String,
    ) {
        val compact: String get() =
            "$VERSION lane=$lane pWin=${"%.0f".format(pWin * 100)}% pRug=${"%.0f".format(pRug * 100)}% E=${"%+.1f".format(expectedPnlPct)}% ±${uncertaintyPct.toInt()} n=$samples src=$source size×=${"%.2f".format(sizeMult)} $note"
    }

    fun forecast(
        rawLane: String?,
        score: Int,
        quality: String,
        regime: String,
        edgePhase: String = "UNKNOWN",
        candidateConfidence: Double = 0.50,
        mlEntryConfidence: Double = 0.50,
        evRatio: Double = 0.50,
        metaConviction: Double = 0.50,
    ): Edge {
        val lane = canonical(rawLane)
        // V5.0.4596 — FLUID PAUSED-LANE DAMPENER (operator architectural
        // principle: "all gates are meant to be in a fluid state eventually
        // be removed once the SUPER AGI / SSI stack take over once they
        // have enough learnt intelligence... these should not just be
        // result-based decisions either").
        //
        // Rather than a rigid mult=0.0 block, this backstop applies a
        // *fluid dampener* that the AGI stack can override. Purpose is to
        // stop capital hemorrhage from bypass paths while still permitting
        // learning probes AND respecting the AGI's forward-looking
        // authority (not just backward-looking WR).
        //
        // Dampener tiers:
        //   • Base (no AGI signal): mult=0.10 → tiny learning probe still
        //     runs so the paused lane doesn't go completely dark for the
        //     learning loop.
        //   • Lab strategy proven for this lane: mult=0.35 → sandbox brain
        //     has forward-looking evidence, upgrade to normal probe size.
        //   • UnifiedPolicyHead AUTHORITATIVE + positive fwd prediction:
        //     mult=0.55 → per-lane brain has trained authority.
        //
        // As the AGI matures, the dampener fades naturally because these
        // AGI signals mature. Eventually the LaneAutoPauseGuard hard-seed
        // itself gets released (via operator's LaneShadowProofLoop.
        // allowLaneResume) and this backstop becomes a no-op.
        try {
            if (LaneAutoPauseGuard.isPaused(lane)) {
                val laneU = lane.uppercase()
                // Consult AGI signals for override authority
                val labProven = try {
                    com.lifecyclebot.engine.lab.LlmLabStore.allStrategies()
                        .any { s ->
                            val target = s.asset.name.uppercase()
                            (target == laneU || (target == "MEME" && laneU in setOf("SHITCOIN","MOONSHOT","EXPRESS","MANIPULATED"))) &&
                                s.status == com.lifecyclebot.engine.lab.LabStrategyStatus.PROMOTED
                        }
                } catch (_: Throwable) { false }
                val policyAuthoritative = try {
                    UnifiedPolicyHead.formatForPipelineDump()
                        .let { dump -> dump.contains("$laneU") && dump.contains("AUTHORITATIVE") && !dump.contains("bootstrap") }
                } catch (_: Throwable) { false }
                val agiMult = when {
                    policyAuthoritative -> 0.55  // per-lane brain trained + authoritative
                    labProven          -> 0.35   // sandbox has forward-looking proof
                    else               -> 0.10   // tiny learning probe (not zero — keeps loop fed)
                }
                val agiSrc = when {
                    policyAuthoritative -> "paused+policy_authoritative"
                    labProven          -> "paused+lab_proven"
                    else               -> "paused+learning_probe"
                }
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_PROBABILITY_LANE_PAUSED_FLUID_DAMPENED_4596",
                        "lane=$lane mult=$agiMult src=$agiSrc labProven=$labProven policyAuthoritative=$policyAuthoritative note=fluid_not_binary_agi_can_override",
                    )
                    PipelineHealthCollector.labelInc("LIVE_PROBABILITY_LANE_PAUSED_FLUID_${laneU}")
                } catch (_: Throwable) {}
                return Edge(lane, 0.35, 0.0, 0.0, 0.0, 0L, agiSrc, agiMult, "fluid_paused_dampener_4596=$agiMult")
            }
        } catch (_: Throwable) {}
        return try {
            val fwd = ForwardOutcomeModel.forecast(
                lane,
                score.coerceIn(0, 100),
                quality.ifBlank { "U" }.take(3),
                regime.ifBlank { "NORMAL" },
                edgePhase.ifBlank { "UNKNOWN" },
            )
            val laneMetric = StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500)
                .firstOrNull { canonical(it.strategy).equals(lane, ignoreCase = true) }

            val laneSamples = laneMetric?.trades?.toLong() ?: 0L
            val lanePWin = if (laneMetric != null && (laneMetric.wins + laneMetric.losses) > 0) {
                laneMetric.winRatePct.coerceIn(0.0, 100.0) / 100.0
            } else 0.5
            val laneE = laneMetric?.meanPnlPct ?: 0.0
            val laneSol = laneMetric?.totalSolPnl ?: 0.0

            val fwdWeight = when {
                fwd.samples >= 60L -> 0.70
                fwd.samples >= 25L -> 0.55
                fwd.samples >= 10L -> 0.40
                else -> 0.0
            }
            val laneWeight = when {
                laneSamples >= 80L -> 0.35
                laneSamples >= 25L -> 0.25
                laneSamples >= 8L -> 0.15
                else -> 0.0
            }
            val totalW = (fwdWeight + laneWeight).coerceAtLeast(0.0001)
            val pBase = if (fwdWeight + laneWeight > 0.0) {
                ((fwd.pWin * fwdWeight) + (lanePWin * laneWeight)) / totalW
            } else 0.5
            val eBase = if (fwdWeight + laneWeight > 0.0) {
                ((fwd.expectedPnl * fwdWeight) + (laneE * laneWeight)) / totalW
            } else 0.0

            val signals = UnifiedPolicyHead.Signals(
                mlEntryConf = mlEntryConfidence.coerceIn(0.0, 1.0),
                symGreenLight = pBase.coerceIn(0.0, 1.0),
                evRatio = evRatio.coerceIn(0.0, 1.0),
                metaConviction = metaConviction.coerceIn(0.0, 1.0),
                fwdPWin = fwd.pWin.coerceIn(0.0, 1.0),
                candConf = candidateConfidence.coerceIn(0.0, 1.0),
            )
            val policyP = UnifiedPolicyHead.predictWinProb(signals).coerceIn(0.0, 1.0)
            val policyW = if (UnifiedPolicyHead.formatForPipelineDump().contains("bootstrap")) 0.0 else 0.20
            val pWin = ((pBase * (1.0 - policyW)) + (policyP * policyW)).coerceIn(0.02, 0.98)

            val probabilityEdge = (pWin - 0.50) * 1.55
            // V5.0.4030 — HIT-RATE AUTHORITY. The screenshot/report showed the
            // wallet down ~44% with ~22% WR while STANDARD still got size×>1
            // because positive mean PnL/SOL overpowered low pWin. That is a
            // capital-allocation bug: outlier winners may justify tiny probes and
            // runner patience, but they must not authorize larger entries until
            // live hit-rate is at least bootstrap-healthy.
            val lowHitRateCap = when {
                maxOf(pWin, lanePWin) < 0.28 -> 0.42
                maxOf(pWin, lanePWin) < 0.35 -> 0.68
                maxOf(pWin, lanePWin) < 0.42 -> 0.92
                else -> 1.60
            }
            // V5.0.4596 — QUALITY VOLUME BOOST (operator directive:
            // "we need to increase quality volume ... entry sizes are still
            // stupidly small"). The rigid lowHitRateCap creates a death
            // spiral: bad trades → low lane WR → cap clamps at 0.42x →
            // every subsequent trade is tiny → hard to recover expectancy
            // even on genuine high-quality tokens. This quality-boost
            // fluidly multiplies the cap using AGI-produced forward signals
            // (composite score, ForwardOutcomeModel pWin, forward pRug)
            // so premium tokens still get executable sizes even when the
            // lane sample is polluted. Low-quality tokens still get tiny
            // sizes as intended. Aligns with fluid-gates doctrine — not
            // purely result-based, respects forward-looking AGI signals.
            val qualityBoost = run {
                var boost = 1.0
                boost *= when {
                    score >= 85 -> 1.35   // premium composite score
                    score >= 70 -> 1.15
                    score >= 55 -> 1.00
                    else        -> 0.75   // low quality shrinks
                }
                boost *= when {
                    fwd.pWin >= 0.60 -> 1.20   // strong forward pWin
                    fwd.pWin >= 0.50 -> 1.05
                    fwd.pWin >= 0.40 -> 1.00
                    else             -> 0.90
                }
                boost *= when {
                    fwd.pRug <= 0.15 -> 1.10   // clean rug forecast
                    fwd.pRug <= 0.30 -> 1.00
                    fwd.pRug >= 0.50 -> 0.70   // heavy rug risk shrinks
                    else             -> 0.90
                }
                boost.coerceIn(0.50, 1.80)
            }
            val qualityAwareCap = (lowHitRateCap * qualityBoost).coerceIn(0.35, 2.00)
            try {
                if (qualityBoost >= 1.20 || qualityBoost <= 0.75) {
                    ForensicLogger.lifecycle(
                        "LIVE_PROBABILITY_QUALITY_BOOST_4596",
                        "lane=$lane score=$score qualityBoost=${"%.2f".format(qualityBoost)} lowHitCap=${"%.2f".format(lowHitRateCap)} qualityAwareCap=${"%.2f".format(qualityAwareCap)} fwdPWin=${"%.2f".format(fwd.pWin)} fwdPRug=${"%.2f".format(fwd.pRug)}",
                    )
                    PipelineHealthCollector.labelInc("LIVE_PROBABILITY_QUALITY_BOOST_4596_${lane.uppercase()}")
                }
            } catch (_: Throwable) {}
            val pnlEdge = if (maxOf(pWin, lanePWin) >= 0.35) (eBase / 140.0).coerceIn(-0.35, 0.35) else (eBase / 220.0).coerceIn(-0.25, 0.10)
            val solEdge = if (maxOf(pWin, lanePWin) >= 0.35) (laneSol / 0.55).coerceIn(-0.30, 0.28) else (laneSol / 0.85).coerceIn(-0.25, 0.08)
            val rugPenalty = fwd.pRug.coerceIn(0.0, 0.80) * 0.75
            val uncertaintyPenalty = (fwd.dispersion / 180.0).coerceIn(0.0, 0.22)
            val rawMult = (1.0 + probabilityEdge + pnlEdge + solEdge - rugPenalty - uncertaintyPenalty)
                .coerceIn(0.40, 1.60)
            val mult = minOf(rawMult, qualityAwareCap).coerceIn(0.40, 1.80)
            // V5.0.4572 — LIVE RAPID RE-EDUCATION, NOT PAID BOOTSTRAP TUITION.
            // Real capital has no bootstrap grace. A few clear bad live outcomes are
            // enough to declare the current lane bucket toxic, but the response must
            // be lane-local tactic/style pivoting — not repeating the same shit buy,
            // and not a learned-strategy zero-size that hides the lane from training.
            // Keep an executable defensive floor so downstream AgenticStyleRouter /
            // LiveStylePivotRouter can switch tactic inside the lane while telemetry
            // exposes the toxic bucket immediately.
            //
            // V5.0.4587 — UNCHOKE (operator P0 "meme trader seems pretty choked out").
            // Original V4572 gate triggered at n≥2 with EV≤-20% which was clamping
            // every fresh lane at size×0.35, INCLUDING healthy STANDARD (49% pWin,
            // n=5) that just happened to have a flat mean. Now the trigger requires
            // meaningful lane sample (n≥6) UNLESS the lane is a known-catastrophic
            // meme lane (SHITCOIN/MANIPULATED/EXPRESS) which still trips faster.
            // Non-catastrophic lanes with n<6 pass through with the natural math
            // multiplier so proven-healthy lanes aren't punished for two flat trades.
            val laneUpperForShaper4587 = lane.uppercase()
            val isCatastrophicLaneShaper4587 =
                laneUpperForShaper4587 == "SHITCOIN" ||
                laneUpperForShaper4587 == "MANIPULATED" ||
                laneUpperForShaper4587 == "EXPRESS"
            val rapidPivotToxicBucket4572 = run {
                val laneN = laneSamples
                val minSample4587 = if (isCatastrophicLaneShaper4587) 3L else 6L
                val sampleClear = laneN >= minSample4587
                val badTwoTradeEV = laneN >= minSample4587 && eBase <= -20.0
                val catastrophicEV = laneN >= 3L && eBase <= -40.0
                val zeroWrEnough = lanePWin <= 0.001 && laneN >= (if (isCatastrophicLaneShaper4587) 3L else 5L)
                val doomEV = eBase <= -60.0 && laneN >= 2L
                sampleClear && (badTwoTradeEV || catastrophicEV || zeroWrEnough || doomEV)
            }
            val postPivotMult = if (rapidPivotToxicBucket4572) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_PROBABILITY_RAPID_PIVOT_SHAPED_4572",
                        "lane=$lane n=$laneSamples pWin=${"%.0f".format(lanePWin*100)}% E=${"%+.1f".format(eBase)}% action=lane_local_tactic_pivot sizeFloor=0.35 no_live_bootstrap_tuition=true",
                    )
                    PipelineHealthCollector.labelInc("LIVE_PROBABILITY_RAPID_PIVOT_SHAPED_4572_${lane.uppercase()}")
                } catch (_: Throwable) {}
                minOf(mult, 0.35).coerceAtLeast(0.35)
            } else mult

            // V5.0.5999 — WIRED: ScoreExpectancyTracker.liveSizeShape folded
            // into the Edge multiplier so per-(lane,score-band) EV learning
            // shapes both entry (Executor) AND the Edge sizing surface. Prior
            // to this, only Executor consumed liveSizeShape; the Edge sizeMult
            // reported to FDG/reporting/tuner did not reflect it. Doctrine =
            // EV AS EDGE (V5.0.4599): dampen toxic buckets, embolden proven
            // +EV buckets. Fluid — never a veto.
            val scoreShape = try {
                ScoreExpectancyTracker.liveSizeShape(lane, score.coerceIn(0, 100))
            } catch (_: Throwable) { ScoreExpectancyTracker.LiveSizeShape(1.0, 0, 0.0, "error") }
            val finalMult = (postPivotMult * scoreShape.multiplier).coerceIn(0.10, 2.20)
            try {
                if (scoreShape.multiplier != 1.0 && scoreShape.samples >= 3) {
                    ForensicLogger.lifecycle(
                        "LIVE_PROBABILITY_SIZE_SHAPE_5999",
                        "lane=$lane score=$score bandMult=${"%.2f".format(scoreShape.multiplier)} bandN=${scoreShape.samples} bandMean=${"%+.1f".format(scoreShape.meanPnlPct)}% reason=${scoreShape.reason} finalMult=${"%.2f".format(finalMult)}",
                    )
                    PipelineHealthCollector.labelInc("LIVE_PROBABILITY_SIZE_SHAPE_5999_${lane.uppercase()}")
                }
            } catch (_: Throwable) {}

            val src = listOfNotNull(
                if (fwdWeight > 0.0) "fwd:${fwd.source}" else null,
                if (laneWeight > 0.0) "lane" else null,
                if (policyW > 0.0) "policy" else null,
                if (scoreShape.samples >= 3 && scoreShape.multiplier != 1.0) "band" else null,
            ).joinToString("+").ifBlank { "bootstrap" }
            Edge(lane, pWin, fwd.pRug, eBase, fwd.dispersion, maxOf(fwd.samples, laneSamples), src, finalMult, (if (laneSol > 0.0) "netSOL=${"%+.3f".format(laneSol)} " else "") + "hitCap=${"%.2f".format(lowHitRateCap)} bandShape=${"%.2f".format(scoreShape.multiplier)}")
        } catch (_: Throwable) {
            Edge(lane, 0.5, 0.0, 0.0, 0.0, 0L, "failopen", 1.0, "")
        }
    }

    fun statusLine(): String = try {
        val rows = StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500)
            .filter { it.trades >= 5 }
            .take(6)
            .map { forecast(it.strategy, 50, "U", "NORMAL") }
        if (rows.isEmpty()) "LiveProbabilityEngine: rapid-live/no clean terminal rows yet"
        else "LiveProbabilityEngine: " + rows.joinToString(" · ") { "${it.lane}:pWin=${"%.0f".format(it.pWin * 100)}% E=${"%+.1f".format(it.expectedPnlPct)}% size×=${"%.2f".format(it.sizeMult)} n=${it.samples}" }
    } catch (_: Throwable) { "LiveProbabilityEngine: unavailable" }

    /**
     * V5.0.4588 — expose per-lane rollup snapshots for LaneAutoPauseGuard.
     * Reads from the same StrategyTelemetry clean-truth ledger that
     * powers statusLine(). Each snapshot is derived from confirmed
     * live-terminal closes only (no paper contamination).
     */
    data class LaneSnapshot(
        val lane: String,
        val sample: Int,
        val wins: Int,
        val wrPct: Double,
        val evPct: Double,
    )

    fun laneSnapshots(): List<LaneSnapshot> = try {
        StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500).map { row ->
            LaneSnapshot(
                lane = row.strategy.uppercase(),
                sample = row.trades,
                wins = row.wins,
                wrPct = if (row.trades > 0) row.wins.toDouble() / row.trades.toDouble() * 100.0 else 0.0,
                evPct = row.meanPnlPct,
            )
        }
    } catch (_: Throwable) { emptyList() }

    private fun canonical(raw: String?): String {
        val r = raw?.trim()?.takeIf { it.isNotBlank() } ?: return "STANDARD"
        return try { LiveGrowthDoctrine.canonicalLane(r) } catch (_: Throwable) { r.uppercase() }
    }
}
