package com.lifecyclebot.engine

import com.lifecyclebot.engine.LaneAgeLiquidityScope8140.Outcome8140
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8140 — age and liquidity are lane rules; established tokens are not refused on launch-era gates. */
class Aate8140LaneAgeLiquidityTest {
    private val s = LaneAgeLiquidityScope8140
    private val liveWindowMin = 360.0          // V3's live launch window (6 h)
    private val threeDaysMin = 3.0 * 24.0 * 60.0

    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── age ──

    @Test fun establishedLaneWithThreeDayOldDeepTokenIsNotRefusedOnAge() {
        for (lane in listOf("QUALITY", "BLUECHIP", "BLUE_CHIP", "DIP_HUNTER", "TREASURY", "CORE", "V3_CORE",
                "MOONSHOT", "CYCLIC", "CASHGEN")) {
            val v = s.ageVerdict8140(lane, threeDaysMin, liveWindowMin)
            assertEquals(lane, Outcome8140.PASS, v.outcome)
            assertFalse(lane, v.blocksEntry)
            assertFalse(lane, s.isLaunchLane8140(lane))
        }
        // V3's trunk window does not refuse it either when its REAL pool is established-deep ...
        assertFalse(s.v3AgeWindowRefuses8140(threeDaysMin, liveWindowMin, 250_000.0))
        assertFalse(s.v3AgeWindowRefuses8140(threeDaysMin, liveWindowMin, s.ESTABLISHED_SCOPE_MIN_LIQ_USD_8140))
        // ... and the established lane's own liquidity floor passes on a deep real pool.
        assertEquals(Outcome8140.PASS, s.liquidityVerdict8140("QUALITY", 250_000.0, 10_000.0, live = true).outcome)
    }

    @Test fun v3WindowStillRefusesOldTokensNoEstablishedLaneCouldTrade() {
        assertTrue(s.v3AgeWindowRefuses8140(threeDaysMin, liveWindowMin, 900.0))
        assertTrue(s.v3AgeWindowRefuses8140(threeDaysMin, liveWindowMin, 0.0))          // unread pool: still a launch-era token
        assertFalse(s.v3AgeWindowRefuses8140(120.0, liveWindowMin, 900.0))             // inside the window: never refused on age
        assertFalse(s.v3AgeWindowRefuses8140(Double.NaN, liveWindowMin, 900.0))
    }

    @Test fun shitcoinWithOldTrueAgeIsRefusedWithItsLaneLabel() {
        val v = s.ageVerdict8140("SHITCOIN", threeDaysMin, liveWindowMin)
        assertEquals(Outcome8140.REFUSE, v.outcome)
        assertEquals("LANE_AGE_PAST_LAUNCH_WINDOW_8140_SHITCOIN", v.label)
        assertEquals(Outcome8140.REFUSE, s.ageVerdict8140("MANIPULATED", 400.0, liveWindowMin).outcome)
        assertEquals(Outcome8140.REFUSE, s.ageVerdict8140("project_sniper", 400.0, liveWindowMin).outcome)
        // a real launch inside the window passes
        assertEquals(Outcome8140.PASS, s.ageVerdict8140("SHITCOIN", 12.0, liveWindowMin).outcome)
    }

    @Test fun watchlistYoungButChainOldTokenIsRefusedBySHITCOIN() {
        val watchlistAgeMin = 2.0                  // the bot saw it two minutes ago
        val chainAgeMin = threeDaysMin             // the mint was born three days ago
        val shitcoinAge = s.laneAgeMinutes8140("SHITCOIN", chainAgeMin, watchlistAgeMin)
        assertEquals(chainAgeMin, shitcoinAge!!, 1e-9)                                  // chain truth, not first-seen
        assertEquals(Outcome8140.REFUSE, s.ageVerdict8140("SHITCOIN", shitcoinAge, liveWindowMin).outcome)
        // unknown chain age is never "fresh": a launch lane defers rather than reading watchlist time
        assertNull(s.laneAgeMinutes8140("SHITCOIN", null, watchlistAgeMin))
        val unknown = s.ageVerdict8140("SHITCOIN", null, liveWindowMin)
        assertEquals(Outcome8140.DEFER, unknown.outcome)
        assertEquals("LANE_AGE_UNKNOWN_DEFER_8140_SHITCOIN", unknown.label)
        // an established lane reads the older of the two lower bounds: not "too new" when chain-old
        assertEquals(chainAgeMin, s.laneAgeMinutes8140("QUALITY", chainAgeMin, watchlistAgeMin)!!, 1e-9)
        assertEquals(watchlistAgeMin, s.laneAgeMinutes8140("QUALITY", null, watchlistAgeMin)!!, 1e-9)
        assertEquals(Outcome8140.PASS, s.ageVerdict8140("QUALITY", null, liveWindowMin).outcome)
    }

    // ── liquidity ──

    @Test fun unknownLiquidityDefersAndIsNeverReportedLow() {
        for (unread in listOf<Double?>(null, 0.0, -1.0, Double.NaN)) {
            val v = s.liquidityVerdict8140("BLUECHIP", unread, 15_000.0, live = true)
            assertEquals(unread.toString(), Outcome8140.DEFER, v.outcome)
            assertEquals("LANE_LIQ_UNHYDRATED_DEFER_8140_BLUECHIP", v.label)
            assertEquals("LANE_LIQ_UNHYDRATED_DEFER_8140_BLUECHIP", s.refusalReason8140(v, unread ?: 0.0, 15_000.0))
        }
        // the defer label is not a "low liquidity" verdict
        assertFalse(s.liquidityVerdict8140("SHITCOIN", 0.0, 300.0, live = false).label.contains("BELOW"))
    }

    @Test fun realLiquidityBelowTheLanesFloorRefusesWithTheLaneLabel() {
        val v = s.liquidityVerdict8140("DIP_HUNTER", 6_000.0, 10_000.0, live = false)
        assertEquals(Outcome8140.REFUSE, v.outcome)
        assertEquals("LANE_LIQ_BELOW_FLOOR_8140_DIP_HUNTER", v.label)
        assertEquals("LANE_LIQ_BELOW_FLOOR_8140_DIP_HUNTER: \$6000 < \$10000", s.refusalReason8140(v, 6_000.0, 10_000.0))
        assertNull(s.refusalReason8140(s.liquidityVerdict8140("DIP_HUNTER", 12_000.0, 10_000.0, live = false), 12_000.0, 10_000.0))
        // each lane is judged on its OWN floor: $2.5k is enough for MOONSHOT ($2k) and not for QUALITY ($3k)
        assertEquals(Outcome8140.PASS, s.liquidityVerdict8140("MOONSHOT", 2_500.0, 2_000.0, live = false).outcome)
        assertEquals(Outcome8140.REFUSE, s.liquidityVerdict8140("QUALITY", 2_500.0, 3_000.0, live = false).outcome)
    }

    @Test fun exitSafeHardFloorIsUnchangedAndNeverUndercutInLive() {
        assertEquals(500.0, s.EXIT_SAFE_HARD_FLOOR_USD_8140, 0.0)
        assertTrue(src("engine/PreTradeHardGate.kt").contains("MIN_LIVE_LIQ_HARD_FLOOR_USD = 500.0"))
        // a lane floor below the exit-safe floor is raised to it in LIVE, never the other way round
        assertEquals(500.0, s.effectiveLiquidityFloor8140(300.0, live = true), 0.0)
        assertEquals(300.0, s.effectiveLiquidityFloor8140(300.0, live = false), 0.0)
        assertEquals(10_000.0, s.effectiveLiquidityFloor8140(10_000.0, live = true), 0.0)
        assertEquals(Outcome8140.REFUSE, s.liquidityVerdict8140("SHITCOIN", 400.0, 300.0, live = true).outcome)
        assertEquals(Outcome8140.PASS, s.liquidityVerdict8140("SHITCOIN", 400.0, 300.0, live = false).outcome)
        // TRUE_ZERO_LIQUIDITY stays terminal and the live hard floor block is still there
        assertTrue(src("engine/PreTradeHardGate.kt").contains("LIQUIDITY_BELOW_EXIT_SAFE_FLOOR"))
        assertTrue(src("engine/BotService.kt").contains("result.reason.contains(\"TRUE_ZERO_LIQUIDITY\", ignoreCase = true)"))
    }

    @Test fun laneNamesAreCanonicalisedAndUnknownLanesAreNotRefusedOnAge() {
        assertEquals("SHITCOIN_EXPRESS", s.canonical8140(" shitcoin-express "))
        assertTrue(s.isLaunchLane8140("EXPRESS"))
        assertTrue(s.isLaunchLane8140("ShitCoin_Express"))
        assertFalse(s.isLaunchLane8140("SOMETHING_NEW"))
        assertEquals(Outcome8140.PASS, s.ageVerdict8140("", threeDaysMin, liveWindowMin).outcome)
    }

    // ── wiring ──

    @Test fun theRulesAreWiredWhereTheRefusalsHappened() {
        val elig = src("v3/eligibility/EligibilityGate.kt")
        assertTrue(elig.contains("LaneAgeLiquidityScope8140.v3AgeWindowRefuses8140(candidate.ageMinutes, config.maxTokenAgeMinutes, candidate.liquidityUsd)"))
        assertTrue(elig.contains("return EligibilityResult.fail(\"TOO_OLD\")"))
        val v3 = src("v3/V3EngineManager.kt")
        assertTrue(v3.contains("LaneAgeLiquidityTruth8140.v3LiquidityDeferReason8140(ts)"))
        assertTrue(v3.contains("fun launchAgeWindowMinutes8140()"))
        assertTrue(v3.contains("maxTokenAgeMinutes = if (botCfg.paperMode) 2880.0 else 360.0"))   // the launch window itself is unchanged
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("LaneAgeLiquidityTruth8140.launchLaneAgeBlocked8140(ts, \"SHITCOIN\")"))
        assertTrue(bot.contains("LaneAgeLiquidityTruth8140.launchAgeMinutesOr8140(ts, 120.0)"))
        assertTrue(bot.contains("LaneAgeLiquidityTruth8140.establishedAgeMinutes8140(ts, qualityTokenAgeMinutes)"))
        assertTrue(bot.contains("val poolAgeSecs7389 = LaneAgeLiquidityTruth8140.chainAgeSecs8140(ts)"))
        assertTrue(bot.contains("LaneAgeLiquidityTruth8140.launchAgeMinutesOr8140(ts, 60.0)"))
        for ((file, lane) in listOf("QualityTraderAI" to "QUALITY", "BlueChipTraderAI" to "BLUECHIP", "DipHunterAI" to "DIP_HUNTER",
                "MoonshotTraderAI" to "MOONSHOT", "ShitCoinTraderAI" to "SHITCOIN", "ShitCoinExpress" to "EXPRESS",
                "ManipulatedTraderAI" to "MANIPULATED", "ProjectSniperAI" to "PROJECT_SNIPER")) {
            assertTrue(file, src("v3/scoring/$file.kt").contains("laneLiquidityRefusal8140(\"$lane\""))
        }
        // the 7951 ShitCoin routing rule is kept exactly: TOO_OLD on a launch stays fatal there
        assertFalse(MemeChokes7951.v3RejectIsLaneRouting7951("TOO_OLD"))
        // the defer reason never carries the ZERO_LIQUIDITY token the REJECTED_FATAL_V3 quarantine hook keys on
        assertFalse("LIQ_UNHYDRATED_DEFER_8140".contains("ZERO_LIQUIDITY"))
        assertTrue(src("engine/LaneAgeLiquidityTruth8140.kt").contains("\"LIQ_UNHYDRATED_DEFER_8140\""))
        // every lane label still classifies as a liquidity rule (never retirable by the veto audit)
        assertFalse(SpecialistBrainBridge7542.nativeRuleRetirable8048("LANE_LIQ_UNHYDRATED_DEFER_8140_QUALITY"))
        assertFalse(SpecialistBrainBridge7542.nativeRuleRetirable8048("LANE_LIQ_BELOW_FLOOR_8140_QUALITY: \$1 < \$3000"))
    }
}
