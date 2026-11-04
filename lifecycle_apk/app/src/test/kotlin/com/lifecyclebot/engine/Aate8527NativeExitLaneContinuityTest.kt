package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.FluidLearningAI
import com.lifecyclebot.v3.scoring.ProjectSniperAI
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527NativeExitLaneContinuityTest {
    private val eps = 1e-9
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()
    private val runnerLanes = setOf("CORE", "MOONSHOT", "SHITCOIN", "MANIPULATED",
        "EXPRESS", "PROJECT_SNIPER", "DIP_HUNTER")

    @Test fun everyNativeDeskKeepsItsContractAndPromotedStoredTrailsUsePeakPriceUnits() {
        for (lane in CoreNativeDispatch8527.nativeLanes8527) {
            val oldTightPrice = 5.70 // +500% peak at entry1; old5% high-water trail
            val price = RunnerTrail8419.nativeTrailPrice8527(lane, 1.0, 500.0, oldTightPrice)
            assertEquals(lane, if (lane in runnerLanes) 3.90 else oldTightPrice, price, eps)
        }
        for (lane in listOf("SHITCOIN", "MANIPULATED", "EXPRESS", "PROJECT_SNIPER", "DIP_HUNTER")) {
            assertEquals(lane, 1.31, RunnerTrail8419.nativeTrailPrice8527(lane, 1.0, 49.9, 1.31), eps)
            assertEquals(lane, 1.20, RunnerTrail8419.nativeTrailPrice8527(lane, 1.0, 50.0, 1.45), eps)
        }
        assertEquals(0.00000390, RunnerTrail8419.nativeTrailPrice8527("EXPRESS", 0.000001, 500.0, 0.00000570), 1e-15)
        assertEquals(1.31, RunnerTrail8419.nativeTrailPrice8527("EXPRESS", 1.0, Double.NaN, 1.31), eps)
        assertEquals(1.31, RunnerTrail8419.nativeTrailPrice8527("CORE", 0.0, 500.0, 1.31), eps)
    }

    @Test fun laneAwareLocksCannotPreemptARunnerButNonRunnerRatchetsRemain() {
        for (lane in runnerLanes) {
            assertEquals(lane, 290.0, FluidLearningAI.fluidProfitFloor(500.0, holdSeconds = 600.0, lane = lane), eps)
            assertFalse(lane, PeakDrawdownLock.shouldLock(500.0, 410.0, lane))
            assertTrue(lane, PeakDrawdownLock.shouldLock(500.0, 289.0, lane))
        }
        for (lane in listOf("QUALITY", "BLUECHIP", "TREASURY", "CASHGEN", "CYCLIC")) {
            assertTrue(lane, FluidLearningAI.fluidProfitFloor(500.0, holdSeconds = 600.0, lane = lane) > 290.0)
            assertTrue(lane, PeakDrawdownLock.shouldLock(500.0, 200.0, lane))
        }
    }

    private fun mission(high: Double) = ProjectSniperAI.SniperMission(
        "sniper8527", "TEST", 1.0, 0.01, 1_000L, 30, 20_000.0, 75_000.0, 70.0,
        highestPrice = high, extractedPct = 66)

    @Test fun sniperUsesItsOwnFastTrailUntilPromotionThenTheSingleRunnerFloor() {
        val promoted = mission(6.0)
        assertFalse(ProjectSniperAI.trailingStopHit8527(promoted, 5.0, 400.0))
        assertFalse(ProjectSniperAI.trailingStopHit8527(promoted, 3.91, 291.0))
        assertTrue(ProjectSniperAI.trailingStopHit8527(promoted, 3.89, 289.0))
        assertFalse(ProjectSniperAI.trailingStopHit8527(promoted, 0.0, 0.0))
        assertFalse(ProjectSniperAI.trailingStopHit8527(promoted, Double.NaN, 0.0))
        assertFalse(ProjectSniperAI.trailingStopHit8527(promoted, 4.0, Double.NaN))
        val fast = mission(1.40)
        assertTrue(ProjectSniperAI.trailingStopHit8527(fast, 1.21, 21.0))
        assertFalse(ProjectSniperAI.trailingStopHit8527(fast, 1.30, 30.0))
    }

    @Test fun paperPointsTrailReadsTheSameGrossFloorRatherThanTwelveGainPoints() {
        val level = requireNotNull(RunnerTrail8419.holdLayerLevel8419(500.0, Double.NaN))
        assertEquals(210.0, level.pointsUnderPeak, eps)
        assertFalse(500.0 - 400.0 >= level.pointsUnderPeak)
        assertTrue(500.0 - 289.0 >= level.pointsUnderPeak)
        val code = src("engine/LifecycleStrategy.kt").substringAfter("val paperHeldMins =")
            .substringBefore("// ── STOP LOSS ──")
        assertTrue(code.contains("val paperMark8527 = OpenPnlSanity.inspect(ts"))
        assertTrue(code.contains("if (!paperMark8527.ok"))
        assertTrue(code.contains("PositionDisplayTruth8078.ratchetPeak8080("))
        assertFalse(code.contains("pos.peakGainPct = paperGainPct"))
        assertTrue(code.contains("HeldPositionPivotArbiter.entryOwner8384(ts).ifBlank { pos.tradingMode }"))
        assertTrue(code.contains("RunnerTrail8419.runnerPosition8521(ts, pos.tradingMode, entryOwner8527)"))
        assertTrue(code.contains("runnerLevel8527?.pointsUnderPeak ?: Double.POSITIVE_INFINITY"))
        assertTrue(code.contains("else deskTrailPct8527"))
        assertTrue(code.contains("lane = entryOwner8527"))
        assertTrue(code.contains("minOf(nativeProfitFloor8527, it.levelPct)"))
        assertTrue(code.contains("paperGainPct <= -25.0")) // independent catastrophe retained
    }

    @Test fun allAffectedNativeFloorAndLockCallsitesCarryTheirActualLane() {
        val cases = listOf(
            Triple("QualityTraderAI", "QUALITY", "pos.peakPnlPct"),
            Triple("BlueChipTraderAI", "BLUECHIP", "pos.peakPnlPct"),
            Triple("ManipulatedTraderAI", "MANIPULATED", "pos.peakPnlPct"),
            Triple("ShitCoinExpress", "EXPRESS", "ride.peakPnlPct"),
            Triple("DipHunterAI", "DIP_HUNTER", "pos.recoveryHighPct"))
        for ((file, lane, peak) in cases) {
            val code = src("v3/scoring/" + file + ".kt")
            val duration = if (lane == "DIP_HUNTER") "dipHoldSec7389" else "_holdSec"
            assertTrue(lane, code.contains("fluidProfitFloor(" + peak + ", holdSeconds = " + duration + ", lane = \"" + lane + "\")"))
            if (lane != "MANIPULATED")
                assertTrue(lane, code.contains("PeakDrawdownLock.shouldLock(" + peak + ", pnlPct, \"" + lane + "\")"))
        }
        val express = src("v3/scoring/ShitCoinExpress.kt").substringAfter("fun checkExit(")
            .substringBefore("fun exitRide(")
        assertTrue(express.contains("ride.trailingStop = com.lifecyclebot.engine.RunnerTrail8419.nativeTrailPrice8527("))
        assertTrue(express.indexOf("nativeTrailPrice8527(") > express.indexOf("// Update ride phase"))
        assertTrue(express.contains("pnlPct <= -15.0"))
        val sniper = src("v3/scoring/ProjectSniperAI.kt").substringAfter("fun checkExit(")
            .substringBefore("private fun getRank(")
        assertTrue(sniper.contains("if (trailingStopHit8527(mission, currentPrice, pnlPct))"))
        assertTrue(sniper.indexOf("pnlPct <= STOP_LOSS_PCT") < sniper.indexOf("if (trailingStopHit8527"))
        assertTrue(sniper.indexOf("PRICE_UNVERIFIED_8527") < sniper.indexOf("// Update highest price"))
        assertTrue(sniper.contains("currentBuyPressure < 45")) // independent native flow risk retained
    }

    @Test fun lifecycleAdaptiveInputsDoNotInventDevOwnershipGraduationVolumeOrOrderFlow() {
        val code = src("engine/LifecycleStrategy.kt").substringAfter("val adaptiveScore = AdaptiveLearningEngine.calculateAdaptiveScore(")
            .substringBefore("// Very conservative blend:")
        assertTrue(code.contains("devWalletPct = Double.NaN"))
        assertTrue(code.contains("bondingCurveProgress = if (ts.tokenMap.migratedOrGraduated) 100.0 else Double.NaN"))
        assertTrue(code.contains("volumeUsd = ts.tokenMap.volume24hUsd?.takeIf"))
        assertTrue(code.contains("buyRatioPct = LaneInputTruth8236.buyPressure8491(ts"))
        assertTrue(code.contains("holderCount = ts.history.lastOrNull()?.holderCount?.takeIf { it > 0 } ?: -1"))
        assertTrue(code.contains("ts.safety.checkedAt > 0L"))
        assertTrue(code.contains("ts.tokenMap.topHolderConcentrationPct?.takeIf"))
        assertTrue(code.contains("ts.safety.topHolderObservedAtMs8522 > 0L"))
        assertTrue(code.contains("ts.holderDataResolved && it.isFinite()"))
        assertFalse(code.contains("devWalletPct = 0.0"))
        assertFalse(code.contains("volumeUsd = ts.lastLiquidityUsd *"))
        assertFalse(code.contains("buyRatioPct = pressScore"))
    }

    @Test fun invalidNativeMarksAreNotConvertedIntoZeroPnlExits() {
        assertFalse(OpenPnlSanity.inspect(1.0, Double.NaN, emit = false).ok)
        assertFalse(OpenPnlSanity.inspect(1.0, 0.0, emit = false).ok)
        for (file in listOf("BlueChipTraderAI", "ShitCoinExpress", "DipHunterAI", "ProjectSniperAI")) {
            val exit = src("v3/scoring/" + file + ".kt").substringAfter("fun checkExit(")
                .substringBefore("\n    fun ")
            assertFalse(file, exit.contains("?.pnlPct ?: 0.0"))
        }
    }
}
