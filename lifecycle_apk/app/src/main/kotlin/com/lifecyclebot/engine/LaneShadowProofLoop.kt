package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.LabAssetClass
import com.lifecyclebot.engine.lab.LabStrategy
import com.lifecyclebot.engine.lab.LabStrategyStatus
import com.lifecyclebot.engine.lab.LlmLabStore
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.4590 — LANE SHADOW-PROOF LOOP (operator P1).
 *
 * Operator directive on 2026-02:
 *   "P1 - LLM Lab shadow-proof loop: paused lanes need auto-resume path
 *    (if sandbox proves >30% WR + positive EV over 20 shadow trades)"
 *
 * DOCTRINE:
 *   • LaneAutoPauseGuard (V5.0.4588) quarantines proven-toxic lanes
 *     (n>=15 wins=0 OR n>=20 wr<20% ev<-40%) from LIVE admission but
 *     keeps them in the paper/sandbox universe.
 *   • The LLM Lab (LlmLabEngine, V5.9.402) already invents and paper-
 *     tests strategies constantly with its own synthetic bankroll and
 *     per-strategy paperTrades/paperWins/paperPnlSol counters.
 *   • This loop bridges the two: when a Lab strategy targeting a
 *     paused lane's asset class hits the operator proof bar
 *     (paperTrades>=20 AND WR>=30% AND paperPnlSol>0), auto-resume
 *     that lane by calling LaneAutoPauseGuard.manualResume().
 *
 * ASSET-CLASS ↔ LANE MAPPING (best-effort):
 *   • MEME class strategies → any paused meme lane
 *     (SHITCOIN, EXPRESS, MANIPULATED, MOONSHOT, DIP_HUNTER, PROJECT_SNIPER)
 *   • ALT class strategies → CRYPTO_SPOT, CRYPTO_LEV (crypto perps universe)
 *   • MARKETS class → STANDARD, CORE, V3
 *   • STOCK class → tokenized-stock lanes (future)
 *
 * FAIL-OPEN:
 *   • Any exception in the loop is swallowed. Paused lanes stay paused
 *     until proof arrives or operator manually resumes.
 *   • Rate-limited to one evaluation every 5 minutes; cheap read of
 *     Lab's already-persisted state.
 */
object LaneShadowProofLoop {
    const val VERSION = "V5.0.4594_LANE_SHADOW_PROOF_LOOP"

    /** Operator's proof bar for auto-resume. */
    private const val PROOF_MIN_TRADES = 20
    private const val PROOF_MIN_WR_PCT = 30.0
    private const val PROOF_MIN_PNL_SOL = 0.0  // must be strictly positive

    private const val EVAL_INTERVAL_MS = 5L * 60L * 1000L  // 5 minutes

    private val lastEvalMs = AtomicLong(0L)

    // V5.0.4594 — RESUME-BLACKLIST (operator P0). Lanes listed here are
    // NEVER auto-resumed by the shadow-proof loop, even if a Lab strategy
    // hits the proof bar. Operator directive on 2026-07-02:
    //   "add manual toggle. disable until llm can prove and introduce
    //    successful strategy"
    // Rationale: EXPRESS (0/31 lifetime WR, -0.25 SOL) and MANIPULATED
    // (14.6% WR, -0.48 SOL) have historically bled the wallet. The
    // hard-seed pause at LaneAutoPauseGuard init must stick until the
    // operator explicitly enables auto-resume via allowLaneResume(lane).
    // Lab-invented strategies for these lanes still run in the sandbox;
    // they just cannot flip the LIVE switch autonomously anymore.
    private val resumeBlacklist: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        add("EXPRESS")
        add("MANIPULATED")
    }

    /** Operator toggle — allow the shadow-proof loop to auto-resume a lane. */
    fun allowLaneResume(lane: String) {
        val u = lane.uppercase()
        if (resumeBlacklist.remove(u)) {
            try {
                ErrorLogger.info(
                    "LaneShadowProofLoop",
                    "🟢 SHADOW_PROOF_RESUME_ALLOWED lane=$u note=operator_opened_lane_for_lab_auto_resume",
                )
                PipelineHealthCollector.labelInc("SHADOW_PROOF_RESUME_ALLOWED_$u")
            } catch (_: Throwable) {}
        }
    }

    /** Operator toggle — re-block a lane from being auto-resumed. */
    fun blockLaneResume(lane: String) {
        val u = lane.uppercase()
        if (resumeBlacklist.add(u)) {
            try {
                ErrorLogger.warn(
                    "LaneShadowProofLoop",
                    "🔒 SHADOW_PROOF_RESUME_BLOCKED lane=$u note=operator_reblocked_lane",
                )
                PipelineHealthCollector.labelInc("SHADOW_PROOF_RESUME_BLOCKED_$u")
            } catch (_: Throwable) {}
        }
    }

    /** Snapshot of currently blacklisted lanes (for UI / status pill). */
    fun blacklistedLanes(): Set<String> = resumeBlacklist.toSet()

    /** True if a lane is blocked from shadow-proof auto-resume. */
    fun isResumeBlocked(lane: String?): Boolean {
        if (lane.isNullOrBlank()) return false
        return resumeBlacklist.contains(lane.uppercase())
    }

    /** Which lanes belong to which LabAssetClass (loose, forgiving mapping). */
    private val MEME_LANES = setOf(
        "SHITCOIN", "EXPRESS", "MANIPULATED", "MOONSHOT",
        "DIP_HUNTER", "PROJECT_SNIPER", "CASHGEN",
        "QUALITY", "BLUECHIP", "TREASURY",  // Solana-universe higher-tiers still count as MEME asset in Lab
    )
    private val ALT_LANES = setOf(
        "CRYPTO_SPOT", "CRYPTO_LEV", "CRYPTO_ALT",
    )
    private val MARKETS_LANES = setOf(
        "STANDARD", "CORE", "V3", "MARKETS",
    )

    private fun assetForLane(lane: String): LabAssetClass? {
        val u = lane.uppercase()
        return when {
            u in MEME_LANES -> LabAssetClass.MEME
            u in ALT_LANES -> LabAssetClass.ALT
            u in MARKETS_LANES -> LabAssetClass.MARKETS
            else -> null
        }
    }

    /**
     * Called from BotService main balance-refresh loop (V5.0.4586). Cheap;
     * rate-limited to every 5 minutes. Enumerates paused lanes, matches
     * them against Lab strategies whose asset class covers the lane, and
     * auto-resumes any lane with at least one proof-passing strategy.
     */
    fun evaluate() {
        val now = System.currentTimeMillis()
        val last = lastEvalMs.get()
        if (now - last < EVAL_INTERVAL_MS) return
        if (!lastEvalMs.compareAndSet(last, now)) return

        try {
            val paused = LaneAutoPauseGuard.pausedLanes()
            if (paused.isEmpty()) return

            val allStrategies = try { LlmLabStore.allStrategies() } catch (_: Throwable) { emptyList() }
            if (allStrategies.isEmpty()) return

            for (lane in paused) {
                // V5.0.4594 — respect operator resume-blacklist. Lanes in the
                // blacklist (default: EXPRESS + MANIPULATED) stay paused until
                // operator calls allowLaneResume(lane). Prevents proven-toxic
                // lanes from re-entering LIVE on a single strong shadow-proof.
                if (resumeBlacklist.contains(lane.uppercase())) {
                    try { PipelineHealthCollector.labelInc("SHADOW_PROOF_RESUME_SKIPPED_BLACKLISTED_$lane") } catch (_: Throwable) {}
                    continue
                }
                val targetAsset = assetForLane(lane) ?: continue
                val laneProven = findProvenStrategy(allStrategies, targetAsset)
                if (laneProven != null) {
                    try {
                        LaneAutoPauseGuard.manualResume(
                            lane = lane,
                            note = "lab_shadow_proof strat=${laneProven.id} name=${laneProven.name.take(40)} n=${laneProven.paperTrades} wr=${"%.1f".format(laneProven.winRatePct())}% pnl=${"%.4f".format(laneProven.paperPnlSol)}SOL",
                        )
                        ErrorLogger.info(
                            "LaneShadowProofLoop",
                            "✅ LANE_SHADOW_PROOF_RESUMED lane=$lane strat=${laneProven.id} name='${laneProven.name}' asset=$targetAsset n=${laneProven.paperTrades} wr=${"%.1f".format(laneProven.winRatePct())}% pnl=${"%.4f".format(laneProven.paperPnlSol)}SOL",
                        )
                        PipelineHealthCollector.labelInc("LANE_SHADOW_PROOF_RESUMED_$lane")
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Find any Lab strategy that targets the given asset class AND has
     * cleared the proof bar. Prefers the strategy with the highest
     * absolute paperPnlSol among proof-passing candidates (best evidence).
     */
    private fun findProvenStrategy(strategies: List<LabStrategy>, asset: LabAssetClass): LabStrategy? {
        return strategies
            .asSequence()
            .filter { it.status == LabStrategyStatus.ACTIVE || it.status == LabStrategyStatus.PROMOTED }
            .filter { it.asset == asset || it.asset == LabAssetClass.ANY }
            .filter { s ->
                s.paperTrades >= PROOF_MIN_TRADES &&
                    s.winRatePct() >= PROOF_MIN_WR_PCT &&
                    s.paperPnlSol > PROOF_MIN_PNL_SOL
            }
            .maxByOrNull { it.paperPnlSol }
    }

    fun statusLine(): String {
        return try {
            val paused = LaneAutoPauseGuard.pausedLanes()
            val strategyCount = try { LlmLabStore.allStrategies().size } catch (_: Throwable) { 0 }
            val blocked = resumeBlacklist.joinToString(",")
            "$VERSION: paused=${paused.size} labStrategies=$strategyCount proofBar=${PROOF_MIN_TRADES}trades/${PROOF_MIN_WR_PCT.toInt()}%WR/+SOL blocked=[$blocked]"
        } catch (_: Throwable) { "$VERSION: unavailable" }
    }
}
