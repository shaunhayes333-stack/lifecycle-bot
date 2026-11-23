package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.LaneHunter7297
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.market.PocketRecovery8961
import com.lifecyclebot.engine.truth.LanePocket8934
import com.lifecyclebot.engine.truth.PocketHunt8961
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class Aate8961PocketDiscoveryTest {
    @Before fun setUp() = LanePocket8934.resetForTest8934()
    @After fun tearDown() = LanePocket8934.resetForTest8934()

    @Test fun freshLaneDecisionAnswersDerivedPocketFeaturesButCurrentSweepOwnsCapAndLiquidity() {
        val sweep = setOf("origin=OTHER", "setup=NA", "pred=NA", "mcap=20-100k", "liq=5-20k")
        val decision = PocketHunt8961.Observation(setOf("setup=LAUNCH_CONTINUATION", "pred=10+",
            "mcap=10-20k", "liq=20-100k"), 1000L)
        val fresh = PocketHunt8961.withDecision(sweep, decision, 2000L)
        assertEquals(PocketHunt8961.Match.MATCHED, PocketHunt8961.match(
            listOf("setup=LAUNCH_CONTINUATION", "pred=10+"), fresh, 1500L, 2000L))
        assertEquals(PocketHunt8961.Match.NONE, PocketHunt8961.match(listOf("mcap=10-20k"), fresh, 1500L, 2000L))
        assertTrue(fresh.contains("liq=5-20k"))
        assertEquals(sweep, PocketHunt8961.withDecision(sweep, decision, 121001L))
        assertEquals(sweep, PocketHunt8961.withDecision(sweep, decision, 999L))
    }

    @Test fun theActualLanePocketUsesItsRecentAssessmentAndExpiresIt() {
        for (i in 0 until 80) LanePocket8934.fileForTest8934(JSONObject()
            .put("t", i * 1000L + 1L).put("horizonMs", 3_600_000L).put("mint", "coin$i")
            .put("lane", "CORE").put("mcap", 50_000.0).put("liq", 25_000.0)
            .put("stage", "BASE_START").put("setup", "LAUNCH_CONTINUATION").put("v3", 30.0)
            .put("pred", if (i % 2 == 0) 12.0 else -10.0).put("net", if (i % 2 == 0) 25.0 else -40.0))
        LanePocket8934.mineForTest8934()
        assertTrue(LanePocket8934.provenForTest8934("CORE").any { it.conds == listOf("pred=10+") })
        assertEquals(PocketHunt8961.Match.NEEDS_READ,
            LanePocket8934.huntMatch8961("CORE", "candidate", 25_000.0, 50_000.0, 1000L, 2000L))
        LanePocket8934.rememberHuntDecision8961("CORE", "candidate", LanePocket8934.features8934(
            "candidate", "BASE_START", "LAUNCH_CONTINUATION", 30.0, 12.0, 25_000.0, 50_000.0).toSet(), 1000L, 2000L)
        assertEquals(PocketHunt8961.Match.MATCHED,
            LanePocket8934.huntMatch8961("CORE", "candidate", 25_000.0, 50_000.0, 1000L, 2000L))
        assertEquals(PocketHunt8961.Match.NONE,
            LanePocket8934.huntMatch8961("MOONSHOT", "candidate", 25_000.0, 50_000.0, 1000L, 2000L))
        assertEquals(PocketHunt8961.Match.NEEDS_READ,
            LanePocket8934.huntMatch8961("CORE", "candidate", 25_000.0, 50_000.0, 122000L, 122000L))
    }

    @Test fun unreadMandateRowsCanReceiveABoundedReadWithoutBecomingEligible() {
        val order = listOf("ready", "unknown", "wrong", "answered")
        val matches = order.associateWith { if (it == "wrong") PocketHunt8961.Match.NONE else PocketHunt8961.Match.NEEDS_READ }
        val eligible = setOf("ready")
        assertEquals(listOf("unknown"), PocketHunt8961.recoveryPicks(order, matches, eligible,
            setOf("ready", "unknown", "wrong")))
        assertEquals(setOf("ready"), eligible)
        assertTrue(PocketHunt8961.recoveryPicks(order, matches, eligible, order.toSet(), 0).isEmpty())
    }

    @Test fun recoveryBudgetDeduplicatesSharedMintsAndRotatesAcrossLaneBooks() {
        val books = linkedMapOf("CORE" to listOf("shared", "core"), "MOONSHOT" to listOf("shared", "moon"),
            "SHITCOIN" to listOf("shit"), "EXPRESS" to listOf("express"), "MANIPULATED" to listOf("manip"))
        assertEquals(listOf("shared", "shit", "express", "manip"), PocketHunt8961.recoveryBudget(books, 0))
        assertEquals(listOf("manip", "shared", "shit", "express"), PocketHunt8961.recoveryBudget(books, 4))
        assertEquals(2, PocketHunt8961.recoveryBudget(books, 3, 2).size)
    }

    @Test fun theFourthWatchedPocketSurvivesFreshRotationButCooldownAndLossDemotionsStillApply() {
        val pockets = listOf("a", "b", "c", "d", "cooling", "losing")
        val fresh = (1..12).map { "fresh$it" }
        val ranked = pockets + fresh
        val matches = pockets.associateWith { PocketHunt8961.Match.MATCHED }
        val ordinary = LaneHunter7297.freshFirst8170(ranked, { it in pockets }, 12)
        assertFalse(ordinary.contains("d"))
        val picks = PocketHunt8961.deliveryPicks(ranked, ordinary, matches, setOf("cooling", "losing"), 12)
        assertEquals(pockets.take(4), picks.take(4))
        assertEquals(12, picks.size)
        assertEquals(8, picks.count { it in fresh })
        assertFalse(picks.contains("cooling"))
        assertFalse(picks.contains("losing"))
    }

    @Test fun missingLiquidityOrUnobservedHourRequestsDataButAReportedFlatHourIsKnown() {
        val row = MarketSweep7297.Row("mint", "T", "Token", 1.0, 15_000.0, 5_000.0, 1000.0, 1000.0,
            0.0, 10, 10, 10.0, false, 0.1, setOf("test"), hourChangeObserved8417 = true)
        assertFalse(LaneHunter7297.pocketInputMissing8961("MOONSHOT", row))
        assertTrue(LaneHunter7297.pocketInputMissing8961("MOONSHOT", row.copy(hourChangeObserved8417 = false)))
        assertTrue(LaneHunter7297.pocketInputMissing8961("CORE", row.copy(liquidityUsd = Double.NaN)))
        assertFalse(LaneHunter7297.pocketInputMissing8961("CORE", row.copy(liquidityUsd = 1.0)))
    }

    @Test fun earlyMissingFileDoesNotSealAnEmptyPocketBookAndExistingEmptyFileCanLoad() {
        val dir = java.nio.file.Files.createTempDirectory("pocket-load-8961").toFile()
        try {
            val file = File(dir, "research.jsonl")
            assertFalse(LanePocket8934.loadFile8961(file))
            file.writeText("")
            assertTrue(LanePocket8934.loadFile8961(file))
            assertFalse(LanePocket8934.loadFile8961(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun offWatchAnswersNeedTheirRequestGenerationAndAFreshPostRequestObservation() {
        assertTrue(PocketRecovery8961.answerCurrent(1000L, 2000L, 3000L, 7L, 7L))
        assertFalse(PocketRecovery8961.answerCurrent(1000L, 999L, 3000L, 7L, 7L))
        assertFalse(PocketRecovery8961.answerCurrent(1000L, 3001L, 3000L, 7L, 7L))
        assertFalse(PocketRecovery8961.answerCurrent(1000L, 2000L, 3000L, 7L, 8L))
        assertFalse(PocketRecovery8961.answerCurrent(1000L, 120000L, 122000L, 7L, 7L))
        assertFalse(PocketRecovery8961.answerCurrent(1000L, 2000L, 18000L, 7L, 7L))
    }

    @Test fun offWatchRecoveryUsesTheProtectedIntakeAndPreservesChainReadState() {
        fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()
        val service = src("engine/BotService.kt")
        val callback = service.substringAfter("private fun recoverPocketDiscovery8961(").substringBefore("private fun promoteHotLaunchNow7921(")
        assertTrue(service.contains("recoverPocketDiscovery8961(mint, false, streamGeneration)"))
        assertTrue(service.contains("recoverPocketDiscovery8961(mint, true, streamGeneration)"))
        assertTrue(callback.contains("admitProtectedMemeIntake("))
        assertTrue(callback.contains("expectedRuntimeGeneration = generation"))
        assertTrue(callback.contains("BotRuntimeController.isLiveGeneration(generation)"))
        assertTrue(callback.indexOf("admitProtectedMemeIntake(") < callback.indexOf("PocketRecovery8961.applyAnswered("))
        val recovery = src("engine/market/PocketRecovery8961.kt")
        assertTrue(recovery.contains("ChainConfirm8950.confirm8950(ts,"))
        assertTrue(recovery.contains("CandleCoverage8284.oneMinuteBars8284(target.mint).groupBy"))
        assertTrue(recovery.contains("CurveReserves7951.accountObservation8522(mint, nowMs, 15_000L)"))
        assertTrue(recovery.contains("fact.observedAtMs, nowMs"))
        assertTrue(src("network/DexscreenerApi.kt").contains("PocketRecovery8961.poolAvailable(mint, pair, System.currentTimeMillis())"))
        assertTrue(recovery.contains("poolReadStarted.compareAndSet(false, true)"))
        assertTrue(recovery.indexOf("ChainConfirm8950.armDepth8959(mint, nowMs)") <
            recovery.indexOf("OnChainDepth8038.needDepth8047(p.token, nowMs, decisionPing8959 = true)"))
        assertFalse(recovery.contains("SpecialistCandidateBooks7803.publishHunt"))
        assertFalse(recovery.contains("status.tokens["))
    }
}
