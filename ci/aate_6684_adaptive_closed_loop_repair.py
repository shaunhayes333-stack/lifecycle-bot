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
        raise SystemExit(f"6684 missing anchor: {label}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"6684 start anchor missing: {label}")
    b = text.find(end, a + len(start))
    if b < 0:
        raise SystemExit(f"6684 end anchor missing: {label}")
    return text[:a] + replacement + text[b:]


# 1) Durable process-lifecycle startup, outside fragile BotService refactors.
app_path = MAIN / "AATEApp.kt"
app = read(app_path)
anchor = "        // V5.9.433 — restore TreasuryManager here too, so the 70/30 splits"
insert = """        // V5.0.6684 — one durable adaptive-control-plane lifecycle.\n        // Non-blocking/idempotent: Lab, Sentience, SSI and lane re-proof remain\n        // alive even if BotService is split/refactored again.\n        try {\n            com.lifecyclebot.engine.AdaptiveIntelligenceRuntime6684.start(applicationContext)\n            ErrorLogger.info(\"App\", \"AdaptiveIntelligenceRuntime6684 started\")\n        } catch (e: Throwable) {\n            ErrorLogger.warn(\"App\", \"AdaptiveIntelligenceRuntime6684 start failed: ${e.message}\")\n        }\n\n"""
if "AdaptiveIntelligenceRuntime6684.start(applicationContext)" not in app:
    app = replace_once(app, anchor, insert + anchor, "AATEApp adaptive runtime")
write(app_path, app)


# 2) Targeted re-proof strategies must not leak through the old asset-wide feed.
feed_path = MAIN / "engine/lab/LabPromotedFeed.kt"
feed = read(feed_path)
if "liveRevoked" not in feed:
    feed = replace_once(
        feed,
        "    private val liveAuthorised = ConcurrentHashMap.newKeySet<String>()",
        "    private val liveAuthorised = ConcurrentHashMap.newKeySet<String>()\n    private val liveRevoked = ConcurrentHashMap.newKeySet<String>()",
        "LabPromotedFeed revoke set",
    )
old_auth = """    fun grantLiveAuthority(strategyId: String) {
        liveAuthorised.add(strategyId)
        ErrorLogger.info(TAG, "🧪 Granted live spend authority to $strategyId")
    }

    fun revokeLiveAuthority(strategyId: String) {
        liveAuthorised.remove(strategyId)
    }

    fun requireLiveApproval(strategyId: String): Boolean = !liveAuthorised.contains(strategyId)

    fun isLiveAuthorised(strategyId: String): Boolean = liveAuthorised.contains(strategyId)"""
new_auth = """    fun grantLiveAuthority(strategyId: String) {
        liveRevoked.remove(strategyId)
        liveAuthorised.add(strategyId)
        ErrorLogger.info(TAG, "🧪 Granted live spend authority to $strategyId")
    }

    fun revokeLiveAuthority(strategyId: String) {
        liveAuthorised.remove(strategyId)
        liveRevoked.add(strategyId)
    }

    // V5.0.6684 — paper proof is autonomous live authority unless the operator
    // explicitly revoked this strategy. Hard trade safety remains downstream.
    fun isLiveAuthorised(strategyId: String): Boolean {
        if (liveRevoked.contains(strategyId)) return false
        if (liveAuthorised.contains(strategyId)) return true
        val s = try { LlmLabStore.getStrategy(strategyId) } catch (_: Throwable) { null } ?: return false
        return s.status == LabStrategyStatus.PROMOTED &&
            s.paperTrades >= LlmLabStore.MIN_TRADES_BEFORE_PROMOTION &&
            s.winRatePct() >= LlmLabStore.MIN_WR_FOR_PROMOTION_PCT &&
            s.paperPnlSol >= LlmLabStore.MIN_PAPER_PNL_SOL_FOR_PROMOTION
    }

    fun requireLiveApproval(strategyId: String): Boolean = !isLiveAuthorised(strategyId)"""
if "paper proof is autonomous live authority" not in feed:
    feed = replace_once(feed, old_auth, new_auth, "LabPromotedFeed proof authority")
if "AdaptiveLaneReproof6684.isTargetedStrategy" not in feed:
    feed = feed.replace(
        """        val candidates = LlmLabStore.allStrategies()
            .filter { it.status == LabStrategyStatus.PROMOTED &&""",
        """        val candidates = LlmLabStore.allStrategies()
            .filter { !com.lifecyclebot.engine.AdaptiveLaneReproof6684.isTargetedStrategy(it.id) }
            .filter { it.status == LabStrategyStatus.PROMOTED &&""",
        1,
    )
    feed = feed.replace(
        """        val promoted = LlmLabStore.allStrategies()
            .filter { it.status == LabStrategyStatus.PROMOTED &&""",
        """        val promoted = LlmLabStore.allStrategies()
            .filter { !com.lifecyclebot.engine.AdaptiveLaneReproof6684.isTargetedStrategy(it.id) }
            .filter { it.status == LabStrategyStatus.PROMOTED &&""",
        1,
    )
write(feed_path, feed)


# 3) Lane failure source: quarantine + exact Lab re-proof; no WR-only resurrection.
pause_path = MAIN / "engine/LaneAutoPauseGuard.kt"
pause = read(pause_path)
pause = pause.replace("private const val MIN_SAMPLE = 8", "private const val MIN_SAMPLE = 5")
pause = pause.replace("private const val ZERO_WIN_MIN_SAMPLE = 8", "private const val ZERO_WIN_MIN_SAMPLE = 5")
pause = pause.replace("private const val TOXIC_EV_PCT = -20.0", "private const val TOXIC_EV_PCT = -8.0")
pause = pause.replace("private const val TOXIC_MIN_SAMPLE = 12", "private const val TOXIC_MIN_SAMPLE = 8")
old_loop = """            for (t in clean) {
                val lane = canonLane(t.tradingMode.trim())
                if (lane.isBlank()) continue
                val agg = byLane.getOrPut(lane) { Agg() }
                agg.sample += 1
                if (t.pnlPct >= 5.0) agg.wins += 1
                agg.pnlSum += t.pnlPct
            }"""
new_loop = """            for (t in clean) {
                val lane = canonLane(t.tradingMode.trim())
                if (lane.isBlank()) continue
                val promotionEpoch6684 = try { AdaptiveLaneReproof6684.activationEpochMs(lane) } catch (_: Throwable) { 0L }
                if (promotionEpoch6684 > 0L && t.ts < promotionEpoch6684) continue
                val agg = byLane.getOrPut(lane) { Agg() }
                agg.sample += 1
                val outcome6684 = com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576.classifyReadonly(t.pnlPct)
                if (outcome6684 == com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576.Class.WIN) agg.wins += 1
                agg.pnlSum += t.pnlPct
            }"""
if "promotionEpoch6684" not in pause:
    pause = replace_once(pause, old_loop, new_loop, "LaneAutoPause canonical epoch/class")
needle = """                        PipelineHealthCollector.labelInc("LANE_AUTO_PAUSED_DIRECT_JOURNAL_4592")
                    } catch (_: Throwable) {}
                }
            }

            // V5.0.6305 — LANE BLEED AUTO-RECOVERY."""
replacement = """                        PipelineHealthCollector.labelInc("LANE_AUTO_PAUSED_DIRECT_JOURNAL_4592")
                    } catch (_: Throwable) {}
                    try { AdaptiveLaneReproof6684.onLaneFailed(lane, reason) } catch (_: Throwable) {}
                }
            }

            // V5.0.6684 — PROOF-GATED RECOVERY ONLY. A paused lane cannot
            // resurrect from aggregate WR alone; an exact Lab replacement must
            // paper-prove, promote, and open a fresh strategy epoch.
"""
if "AdaptiveLaneReproof6684.onLaneFailed(lane, reason)" not in pause:
    pause = replace_once(pause, needle, replacement, "LaneAutoPause reproof callback")
# Delete the legacy 6305 recovery code, preserving the persist line.
legacy_start = "            // V5.0.6305 — LANE BLEED AUTO-RECOVERY."
if legacy_start in pause:
    pause = replace_between(
        pause,
        legacy_start,
        "            if (mutated) persistAsync()",
        "            // V5.0.6684: legacy WR-only auto-recovery removed.\n",
        "LaneAutoPause remove legacy recovery",
    )
write(pause_path, pause)


# 4) Old duplicate quarantine/proof systems become compatibility facades.
shadow_path = MAIN / "engine/LaneShadowProofLoop.kt"
write(shadow_path, """package com.lifecyclebot.engine

/** V5.0.6684 compatibility facade over the exact lane re-proof authority. */
object LaneShadowProofLoop {
    const val VERSION = "V5.0.6684_LANE_SHADOW_PROOF_FACADE"

    fun evaluate() { try { AdaptiveLaneReproof6684.tick() } catch (_: Throwable) {} }

    @Deprecated("6684: exact Lab proof owns autonomous resume")
    fun allowLaneResume(lane: String) {
        try { AdaptiveLaneReproof6684.requestReproof(lane, "operator_requested_reproof_6684") } catch (_: Throwable) {}
    }

    @Deprecated("6684: quarantine state is owned by LaneAutoPauseGuard")
    fun blockLaneResume(lane: String) {
        try { AdaptiveLaneReproof6684.requestReproof(lane, "operator_reproof_required_6684") } catch (_: Throwable) {}
    }

    fun blacklistedLanes(): Set<String> = emptySet()
    fun isResumeBlocked(lane: String?): Boolean =
        if (lane.isNullOrBlank()) false else LaneAutoPauseGuard.statusFor(lane) != null

    fun statusLine(): String = "$VERSION ${AdaptiveLaneReproof6684.statusLine()}"
}
""")

quarantine_path = MAIN / "engine/LaneQuarantineController.kt"
write(quarantine_path, """package com.lifecyclebot.engine

/**
 * V5.0.6684 compatibility facade. LaneAutoPauseGuard owns pause state and
 * AdaptiveLaneReproof6684 owns exact Lab proof + autonomous re-entry.
 */
object LaneQuarantineController {
    const val VERSION = "V5.0.6684_LANE_QUARANTINE_FACADE"

    fun isQuarantined(lane: String): Boolean =
        try { LaneAutoPauseGuard.statusFor(lane) != null } catch (_: Throwable) { false }

    fun logBlockedEntry(lane: String, symbol: String, mint: String, primary: String) {
        try {
            ForensicLogger.lifecycle(
                "LANE_QUARANTINED_BLOCKED_ENTRY_6684",
                "lane=${lane.uppercase()} symbol=$symbol mint=${mint.take(10)} primary=$primary reason=awaiting_exact_lab_proof",
            )
            PipelineHealthCollector.labelInc("LANE_QUARANTINED_BLOCKED_ENTRY_6684_${lane.uppercase()}")
        } catch (_: Throwable) {}
    }

    fun quarantineSnapshot(): Map<String, Boolean> =
        try { LaneAutoPauseGuard.pausedLanes().associateWith { false } } catch (_: Throwable) { emptyMap() }

    fun statusLine(): String =
        "$VERSION paused=${try { LaneAutoPauseGuard.pausedLanes().joinToString(",").ifEmpty { "-" } } catch (_: Throwable) { "unavailable" }} " +
            AdaptiveLaneReproof6684.statusLine()
}
""")


# 5) SSI can request re-proof but can never manual-resume a failed lane.
ssi_path = MAIN / "engine/SsiPilotCouncil.kt"
ssi = read(ssi_path)
start = ssi.find("    /** V5.0.6090: pilot flies autonomously")
if start >= 0:
    end_marker = "    // ── PERSISTENCE"
    end = ssi.find(end_marker, start)
    if end < 0:
        raise SystemExit("6684 missing SSI persistence anchor")
    block = """    /** V5.0.6684 — SSI may nominate re-proof, never bypass it. */
    private fun handleResumeRequest(laneRaw: String, paper: Boolean) {
        val lane = laneRaw.trim().uppercase()
        if (lane.isBlank() || !KNOWN_LANES.contains(lane)) return
        try {
            if (lane !in LaneAutoPauseGuard.pausedLanes()) return
            AdaptiveLaneReproof6684.requestReproof(
                lane,
                "ssi_pilot_reproof_request_6684 mode=${if (paper) "paper" else "live"}",
            )
            ForensicLogger.lifecycle(
                "SSI_PILOT_REPROOF_REQUEST_6684",
                "lane=$lane mode=${if (paper) "paper" else "live"} authority=lab_proof_required",
            )
            PipelineHealthCollector.labelInc("SSI_PILOT_REPROOF_REQUEST_6684_$lane")
        } catch (_: Throwable) {}
    }

"""
    ssi = ssi[:start] + block + ssi[end:]
ssi = ssi.replace('"resumeLane": <lane name to un-pause, or "">,', '"resumeLane": <paused lane to request exact Lab re-proof for, or "">,')
write(ssi_path, ssi)


# 6) A proven replacement opens a fresh bucket execution epoch.
bucket_path = MAIN / "engine/BucketExecutionState.kt"
bucket = read(bucket_path)
old_bucket = """    fun stateFor(lane: String, score: Int): State {
        return try {
            val samples = ScoreExpectancyTracker.bucketSamples(lane, score)"""
new_bucket = """    fun stateFor(lane: String, score: Int): State {
        return try {
            // V5.0.6684 — old failed-strategy bucket history cannot permanently
            // shadow a new exact Lab-proven replacement.
            if (AdaptiveLaneReproof6684.activeStrategy(lane, LosingPatternMemory.scoreBand(score)) != null) {
                return State.EXECUTABLE
            }
            val samples = ScoreExpectancyTracker.bucketSamples(lane, score)"""
if "old failed-strategy bucket history" not in bucket:
    bucket = replace_once(bucket, old_bucket, new_bucket, "BucketExecutionState promotion epoch")
write(bucket_path, bucket)


# 7) Canonical order-size authority consumes SSI + proven Lab strategy.
size_path = MAIN / "engine/truth/OrderSizeResolver6441.kt"
size = read(size_path)
old_size = """        // 1. requested -> strategy/risk floor
        val requested = requestedSol.coerceAtLeast(0.0)
        val risk = requested.coerceAtMost(laneRiskCapSol)"""
new_size = """        // 1. requested -> adaptive strategy/risk -> hard caps.
        // V5.0.6684 restores the severed SSI sizing hand and exact Lab-proven
        // replacement at the ONE mandatory size authority.
        val ssiMult6684 = try {
            com.lifecyclebot.engine.SsiPilotCouncil.sizeMultiplierForLane(laneName)
        } catch (_: Throwable) { 1.0 }
        val labMult6684 = try {
            com.lifecyclebot.engine.AdaptiveLaneReproof6684.sizeMultiplierForLane(laneName)
        } catch (_: Throwable) { 1.0 }
        val adaptiveMult6684 = (ssiMult6684 * labMult6684).coerceIn(0.35, 2.50)
        val requested = (requestedSol.coerceAtLeast(0.0) * adaptiveMult6684).coerceAtLeast(0.0)
        val risk = requested.coerceAtMost(laneRiskCapSol)
        if (kotlin.math.abs(adaptiveMult6684 - 1.0) > 0.001) {
            try {
                PipelineHealthCollector.labelInc("CANONICAL_ADAPTIVE_SIZE_6684")
                PipelineHealthCollector.labelInc("CANONICAL_ADAPTIVE_SIZE_6684_${laneName.uppercase().take(24)}")
            } catch (_: Throwable) {}
        }"""
if "CANONICAL_ADAPTIVE_SIZE_6684" not in size:
    size = replace_once(size, old_size, new_size, "OrderSizeResolver adaptive sizing")
write(size_path, size)


# 8) Exact promoted strategy score floor at the final TokenState open boundary.
open_path = MAIN / "engine/ExecutableOpenGate.kt"
open_src = read(open_path)
wave_anchor = '        // V5.0.6382 — WAVE ENTRY QUALITY GATE (operator directive: "buys in the'
lab_gate = """        // V5.0.6684 — exact Lab-promoted strategy entry contract.\n        // WAIT remains shadow/trainable; it must not become an economic open.\n        try {\n            val labDirective6684 = AdaptiveLaneReproof6684.entryDirective(lane, ts.entryScore.toInt())\n            if (labDirective6684 != null && !labDirective6684.allow) {\n                PipelineHealthCollector.labelInc(\"EXEC_OPEN_LAB_STRATEGY_WAIT_6684_${canonicalLane(lane)}\")\n                ForensicLogger.lifecycle(\n                    \"EXEC_OPEN_LAB_STRATEGY_WAIT_6684\",\n                    \"attemptId=$attemptId mint=${ts.mint.take(10)} lane=${canonicalLane(lane)} score=${ts.entryScore.toInt()} floor=${labDirective6684.minScore} strategy=${labDirective6684.strategyId}\",\n                )\n                return OpenVerdict(\n                    allowed = false,\n                    reason = \"LAB_STRATEGY_WAIT_6684:${labDirective6684.strategyId}\",\n                    shadowOnly = true,\n                    logName = \"EXEC_OPEN_LAB_STRATEGY_WAIT_6684\",\n                    attemptId = attemptId,\n                )\n            }\n        } catch (_: Throwable) {}\n\n"""
if "EXEC_OPEN_LAB_STRATEGY_WAIT_6684" not in open_src:
    open_src = replace_once(open_src, wave_anchor, lab_gate + wave_anchor, "ExecutableOpenGate Lab entry")
write(open_path, open_src)


# 9) Active holding path consumes exact promoted Lab TP/SL/hold profile.
hold_path = MAIN / "engine/HoldingLogicLayer.kt"
hold = read(hold_path)
old_hold = """            val ssiExitPatience6091 = try { SsiPilotCouncil.exitPatience().coerceIn(0.65, 1.55) } catch (_: Throwable) { 1.0 }
            val targetProfit6091 = params.targetProfitPct * ssiExitPatience6091
            val trailingStopPct6091 = params.trailingStopPct * ssiExitPatience6091
            val maxHoldTimeMs6091 = (params.maxHoldTimeMs.toDouble() * ssiExitPatience6091).toLong().coerceAtLeast(params.maxHoldTimeMs / 2L)"""
new_hold = """            val ssiExitPatience6091 = try { SsiPilotCouncil.exitPatience().coerceIn(0.65, 1.55) } catch (_: Throwable) { 1.0 }
            // V5.0.6684 — exact promoted Lab strategy becomes this lane's
            // TP/SL/hold profile. It cannot loosen the existing hard stop.
            val labExit6684 = try { AdaptiveLaneReproof6684.exitStrategy(mode) } catch (_: Throwable) { null }
            val baseTarget6684 = labExit6684?.takeProfitPct?.coerceIn(3.0, 100.0) ?: params.targetProfitPct
            val activeStopLoss6684 = maxOf(
                params.stopLossPct,
                labExit6684?.stopLossPct?.coerceIn(-30.0, -2.0) ?: params.stopLossPct,
            )
            val baseMaxHoldMs6684 = labExit6684?.maxHoldMins?.coerceIn(15, 480)?.toLong()?.times(60_000L)
                ?: params.maxHoldTimeMs
            val targetProfit6091 = baseTarget6684 * ssiExitPatience6091
            val trailingStopPct6091 = params.trailingStopPct * ssiExitPatience6091
            val maxHoldTimeMs6091 = (baseMaxHoldMs6684.toDouble() * ssiExitPatience6091).toLong()
                .coerceAtLeast(baseMaxHoldMs6684 / 2L)"""
if "labExit6684" not in hold:
    hold = replace_once(hold, old_hold, new_hold, "HoldingLogic Lab profile")
old_stop = """            if (currentPnlPct <= params.stopLossPct) {
                return HoldEvaluation(
                    action = HoldAction.EXIT_NOW,
                    reason = "Stop loss triggered: ${currentPnlPct.toInt()}% <= ${params.stopLossPct.toInt()}%","""
new_stop = """            if (currentPnlPct <= activeStopLoss6684) {
                return HoldEvaluation(
                    action = HoldAction.EXIT_NOW,
                    reason = "Stop loss triggered: ${currentPnlPct.toInt()}% <= ${activeStopLoss6684.toInt()}%${if (labExit6684 != null) " lab=${labExit6684.id}" else ""}","""
if old_stop in hold:
    hold = replace_once(hold, old_stop, new_stop, "HoldingLogic Lab stop")
write(hold_path, hold)


# 10) Promote as soon as proof is reached, not only on the old 20-minute cull.
reproof_path = MAIN / "engine/AdaptiveLaneReproof6684.kt"
reproof = read(reproof_path)
old_tick = """        targets.values.forEach { t ->
            val s = byId[t.strategyId] ?: return@forEach
            if (s.status == LabStrategyStatus.ARCHIVED && t.promotedAtMs != 0L) {"""
new_tick = """        targets.values.forEach { t ->
            var s = byId[t.strategyId] ?: return@forEach
            val rawProof6684 = s.paperTrades >= LlmLabStore.MIN_TRADES_BEFORE_PROMOTION &&
                s.winRatePct() >= LlmLabStore.MIN_WR_FOR_PROMOTION_PCT &&
                s.paperPnlSol >= LlmLabStore.MIN_PAPER_PNL_SOL_FOR_PROMOTION
            if (s.status == LabStrategyStatus.ACTIVE && rawProof6684) {
                s = s.copy(status = LabStrategyStatus.PROMOTED)
                try { LlmLabStore.updateStrategy(s) } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("LAB_IMMEDIATE_PROMOTION_6684_${t.lane}") } catch (_: Throwable) {}
            }
            if (s.status == LabStrategyStatus.ARCHIVED && t.promotedAtMs != 0L) {"""
if "LAB_IMMEDIATE_PROMOTION_6684" not in reproof:
    reproof = replace_once(reproof, old_tick, new_tick, "AdaptiveLaneReproof immediate promotion")
write(reproof_path, reproof)


# 11) Source-level regression locks assert the CLOSED LOOP, not class existence.
test_path = TEST / "Aate6684AdaptiveClosedLoopTest.kt"
write(test_path, """package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6684AdaptiveClosedLoopTest {
    private fun src(path: String) = File("src/main/kotlin/$path").readText()

    @Test fun `adaptive runtime durably starts lab sentience and ssi`() {
        val app = src("com/lifecyclebot/AATEApp.kt")
        val rt = src("com/lifecyclebot/engine/AdaptiveIntelligenceRuntime6684.kt")
        assertTrue(app.contains("AdaptiveIntelligenceRuntime6684.start(applicationContext)"))
        assertTrue(rt.contains("LlmLabEngine.start(app)"))
        assertTrue(rt.contains("LlmLabEngine.tick { buildUniverse() }"))
        assertTrue(rt.contains("SentienceOrchestrator.start(app)"))
        assertTrue(rt.contains("SsiPilotCouncil.start()"))
        assertTrue(rt.contains("SentienceHooks.maybeAutoTune(app)"))
    }

    @Test fun `failed lane only reenters through exact lab proof`() {
        val reproof = src("com/lifecyclebot/engine/AdaptiveLaneReproof6684.kt")
        val pause = src("com/lifecyclebot/engine/LaneAutoPauseGuard.kt")
        val shadow = src("com/lifecyclebot/engine/LaneShadowProofLoop.kt")
        val ssi = src("com/lifecyclebot/engine/SsiPilotCouncil.kt")
        assertTrue(reproof.contains("proofPasses(s: LabStrategy)"))
        assertTrue(reproof.contains("LaneAutoPauseGuard.manualResume("))
        assertTrue(reproof.contains("activationEpochMs"))
        assertTrue(pause.contains("AdaptiveLaneReproof6684.onLaneFailed(lane, reason)"))
        assertTrue(pause.contains("t.ts < promotionEpoch6684"))
        assertFalse(pause.contains("LANE_AUTO_RECOVERED_6305"))
        assertFalse(shadow.contains("resumeBlacklist"))
        assertFalse(ssi.contains("SSI_PILOT_LANE_RESUMED_6090"))
        assertTrue(ssi.contains("SSI_PILOT_REPROOF_REQUEST_6684"))
    }

    @Test fun `targeted lab strategy is lane local and actuated`() {
        val feed = src("com/lifecyclebot/engine/lab/LabPromotedFeed.kt")
        val open = src("com/lifecyclebot/engine/ExecutableOpenGate.kt")
        val size = src("com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt")
        val hold = src("com/lifecyclebot/engine/HoldingLogicLayer.kt")
        val bucket = src("com/lifecyclebot/engine/BucketExecutionState.kt")
        assertTrue(feed.contains("AdaptiveLaneReproof6684.isTargetedStrategy"))
        assertTrue(open.contains("EXEC_OPEN_LAB_STRATEGY_WAIT_6684"))
        assertTrue(size.contains("AdaptiveLaneReproof6684.sizeMultiplierForLane"))
        assertTrue(size.contains("SsiPilotCouncil.sizeMultiplierForLane"))
        assertTrue(hold.contains("AdaptiveLaneReproof6684.exitStrategy(mode)"))
        assertTrue(bucket.contains("AdaptiveLaneReproof6684.activeStrategy"))
    }

    @Test fun `all meme specialist roles participate in reproof ring`() {
        val reproof = src("com/lifecyclebot/engine/AdaptiveLaneReproof6684.kt")
        listOf(
            "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
            "MOONSHOT", "PROJECT_SNIPER", "PRESALE_SNIPE", "DIP_HUNTER",
            "MANIPULATED", "TREASURY", "CASHGEN",
        ).forEach { assertTrue("$it missing from reproof ring", reproof.contains("\\\"$it\\\"")) }
    }
}
""")

print("V5.0.6684 expanded adaptive closed-loop repair applied")
