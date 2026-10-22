package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CellTail8303
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8303 — fresh PumpPortal launches reach the lanes (AUDIT_FRESH_LAUNCH_8303.md): a witnessed create is not parked
 * by the post-8048 depth estimate, an evicted row no longer blocks the band entry, and a PumpPortal launch keeps its
 * feed as its forward-label family through the tape / probation pathways.
 */
class Aate8303FreshLaunchTest {

    private fun src(rel: String): String = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val now = 1_800_000_000_000L

    @Test fun theCreateDepthEstimateIsBelowTheColdPumpCutSince8048() {
        // A 28 SOL create: the 8048 real-SOL estimate is ~0.02 SOL — under $1,500 at any SOL price, so every create parked.
        val realSol = com.lifecyclebot.engine.truth.LiquidityTruth8037.curveRealSolFromCap8048(28.0)
        assertTrue(realSol < 0.1)
        assertTrue(realSol * 300.0 < 1_500.0)
        // The pre-8048 estimate 7384 calibrated the cut on: cap x 0.85 (~$4.8k at $200).
        assertTrue(28.0 * 200.0 * 0.85 > 1_500.0)
    }

    @Test fun aWitnessedCreateInsideTheSniperWindowIsFresh() {
        assertTrue(FreshLaunchIntake8303.freshCreate8303("PUMP_PORTAL_WS", now - 1_000L, now))
        assertTrue(FreshLaunchIntake8303.freshCreate8303("pump_portal_ws", now - 180_000L, now))
        assertFalse(FreshLaunchIntake8303.freshCreate8303("PUMP_PORTAL_WS", now - 180_001L, now))
        assertFalse(FreshLaunchIntake8303.freshCreate8303("PUMP_PORTAL_WS", null, now))
        assertFalse(FreshLaunchIntake8303.freshCreate8303("PUMP_PORTAL_WS", 0L, now))
        assertFalse(FreshLaunchIntake8303.freshCreate8303("PUMP_FUN_NEW", now - 1_000L, now))
        assertFalse(FreshLaunchIntake8303.freshCreate8303("LAUNCH_HEAT_7921", now - 1_000L, now))
        assertEquals(180_000L, FreshLaunchIntake8303.HOT_WINDOW_MS_8303)
        assertTrue(src("v3/scoring/ProjectSniperAI.kt").contains("private const val MAX_TOKEN_AGE_SECONDS = 180"))
    }

    @Test fun pressureStillParksAndTheExemptionOnlyLiftsTheColdPumpPark() {
        assertTrue(FreshLaunchIntake8303.parkDecision8303(wouldPark = false, pressurePark = true, exempt = true))
        assertTrue(FreshLaunchIntake8303.parkDecision8303(wouldPark = true, pressurePark = true, exempt = true))
        assertFalse(FreshLaunchIntake8303.parkDecision8303(wouldPark = true, pressurePark = false, exempt = true))
        assertTrue(FreshLaunchIntake8303.parkDecision8303(wouldPark = true, pressurePark = false, exempt = false))
        assertFalse(FreshLaunchIntake8303.parkDecision8303(wouldPark = false, pressurePark = false, exempt = false))
        // Runtime: an un-parked or pressure-parked row is decided without reading any witness.
        assertFalse(FreshLaunchIntake8303.park8303(false, false, "PUMP_PORTAL_WS", "x"))
        assertTrue(FreshLaunchIntake8303.park8303(false, true, "PUMP_PORTAL_WS", "x"))
        // A create with no witnessed birth parks as before.
        assertTrue(FreshLaunchIntake8303.park8303(true, false, "PUMP_PORTAL_WS", "NoWitnessMint11111111111111111111111pump"))
    }

    @Test fun theBudgetClipsBursts() {
        val recent = (0 until 24).map { now - 1_000L * it }
        assertFalse(FreshLaunchIntake8303.budgetOpen8303(recent, now))
        assertTrue(FreshLaunchIntake8303.budgetOpen8303(recent.drop(1), now))
        assertTrue(FreshLaunchIntake8303.budgetOpen8303(recent.map { it - 60_000L }, now))
        assertTrue(FreshLaunchIntake8303.budgetOpen8303(emptyList(), now))
    }

    @Test fun anEvictedRowNoLongerBlocksTheBandEntry() {
        assertFalse(FreshLaunchIntake8303.rowBlocksBandEntry8303(rowExists = false, watched = true, held = true))
        assertFalse(FreshLaunchIntake8303.rowBlocksBandEntry8303(rowExists = true, watched = false, held = false))
        assertTrue(FreshLaunchIntake8303.rowBlocksBandEntry8303(rowExists = true, watched = true, held = false))
        assertTrue(FreshLaunchIntake8303.rowBlocksBandEntry8303(rowExists = true, watched = false, held = true))
        assertFalse(FreshLaunchIntake8303.rowWatched8303(false, "x"))
    }

    @Test fun theBandEntryReadsAnyWitnessedCreate() {
        assertEquals(5L, FreshLaunchIntake8303.earliestBirth8303(listOf(null, 9L, 5L, 0L)))
        assertNull(FreshLaunchIntake8303.earliestBirth8303(listOf(null, 0L, -3L)))
        assertNull(FreshLaunchIntake8303.createBirthMs8303("NoWitnessMint22222222222222222222222pump"))
    }

    @Test fun aWitnessedCreateKeepsItsFeedThroughTheTapeAndProbation() {
        assertEquals(listOf("PUMP_PORTAL_WS", "LAUNCH_HEAT_7921"), FreshLaunchIntake8303.heatSources8303(true).toList())
        assertEquals(listOf("LAUNCH_HEAT_7921", "PUMP_PORTAL_WS"), FreshLaunchIntake8303.heatSources8303(false).toList())
        assertEquals("PUMP_PORTAL_WS", ForwardReturnLabeler7731.sourceFamily(FreshLaunchIntake8303.heatSources8303(true).joinToString(",")))
        val parked = ScannerSourceTags8292.probationPromotionSources8292("PUMP_PORTAL_WS+PUMP_PORTAL", "PUMP_PORTAL_WS")
        assertEquals(listOf("PUMP_PORTAL_WS", "PROBATION", "PUMP_PORTAL"), parked.toList())
        assertEquals("PUMP_PORTAL_WS", ForwardReturnLabeler7731.sourceFamily(parked.joinToString(",")))
        val demoted = ScannerSourceTags8292.probationPromotionSources8292("PROBATION_DEMOTE:STALE|PUMP_PORTAL_WS,PUMP_PORTAL", "DEMOTED_PUMP_PORTAL_WS")
        assertEquals("PUMP_PORTAL_WS", demoted.first())
        // Every other promotion is unchanged (8292).
        val other = ScannerSourceTags8292.probationPromotionSources8292("SOURCE_BALANCE_DIVERT:PUMP_FUN_NEW+SCANNER_DIRECT", "SCANNER_DIRECT_PUMP_FUN_NEW")
        assertEquals(listOf("PROBATION", "PUMP_FUN_NEW"), other.toList())
        // Probation is still a wrapper: the feed is one independent source.
        assertEquals(1, ScannerSourceTags8292.independentSourceCount8292("PROBATION", parked))
    }

    @Test fun theWiringIsOneLineEach() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("val coldPump = FreshLaunchIntake8303.park8303(coldPumpBase || sourceBrainProbationOnly, pressureDecision.probationOnly && !sourceBrainHotRescue, source, mint)"))
        assertFalse(bot.contains("val coldPump = coldPumpBase || sourceBrainProbationOnly ||"))
        assertTrue(bot.contains("allSources = FreshLaunchIntake8303.heatSourcesFor8303(mint),"))
        assertTrue(bot.contains("if (mint.isBlank() || FreshLaunchIntake8303.rowWatched8303(status.tokens.containsKey(mint), mint)) return"))
        assertTrue(bot.contains("val bornAt = FreshLaunchIntake8303.createBirthMs8303(mint) ?: return"))
        // The band entry's settlement is unchanged (8136).
        assertTrue(bot.contains("PumpBandEntry8118.settleIntake8136(mint, admitted, status.tokens.containsKey(mint), ScannerHardRejectStore.isRejected(mint))"))
        assertTrue(src("engine/truth/AdmissionBirth8144.kt").contains("FreshLaunchIntake8303.statusLine8303()"))
        assertTrue(FreshLaunchIntake8303.statusLine8303().startsWith("freshLaunch8303[hot="))
    }

    @Test fun thePondIsATailNotAnOutlier() {
        // 5.0.8302 best60: PUMP_PORTAL_WS|SHITCOIN|MC_10K_100K|AGE_LT15M n=704 net +231.7% wr 43% run 33%.
        val t = CellTail8303.tail8303(231.7, 0.43, 0.33)
        assertTrue(t.medianNonPositive)
        assertEquals(7.46, t.exRunnerMaxPct, 0.05)
        assertEquals(686.97, t.runnerMinMeanPct, 0.1)
        assertTrue(t.tailDependent)
        // Not OUTLIER_SUSPECT under the 8185 cap: its winners average under +1,000 %.
        assertFalse(com.lifecyclebot.engine.truth.RobustLabelRead8185.outlierSuspect8185(704.0, 704.0 * 231.7, 704.0 * 0.43, 1_000.0))
        val broad = CellTail8303.tail8303(12.0, 0.62, 0.05)
        assertFalse(broad.medianNonPositive)
        assertFalse(broad.tailDependent)
        assertTrue(CellTail8303.line8303("K", 231.7, 0.43, 0.33).contains("TAIL_DEPENDENT"))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("CellTail8303.line8303(it.key, it.meanNet60Pct, it.winRate60, it.runnerRate60)"))
    }
}
