package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7791MoonshotAuthorityRestorationTest {
    private fun src(p:String)=File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun paperMoonshotIsStillAnExploratoryTailScanner(){
        val s=src("v3/scoring/MoonshotTraderAI.kt")
        assertTrue(s.contains("if (isPaper) 20 else 30"))
        assertTrue(s.contains("if (isPaper) 28 else 38"))
        assertTrue(s.contains("MOONSHOT_GENERIC_WR_FLOOR_IGNORED_TAIL_LANE_7791"))
    }

    @Test fun adaptiveGateOptimisesTailNotRawWinRate(){
        val s=src("engine/MoonshotAdaptiveGate.kt")
        assertTrue(s.contains("megaRate"))
        assertTrue(s.contains("runnerRate"))
        assertFalse(s.contains("private const val TARGET_WR_PCT"))
        assertTrue(s.contains("MAX_TIGHTEN_BIAS = 6"))
    }

    @Test fun scannerAndOwnershipRespectSpecialistAuthority(){
        val h=src("engine/market/LaneHunter7297.kt")
        assertTrue(h.contains("MIN_MARKET_CAP_BOOTSTRAP_USD_7719"))
        val t=src("engine/ToolkitSignalSheet.kt")
        assertTrue(t.contains("LANE_HUNT_CLAIM_YIELDED_TO_STRONGER_NATIVE_7791"))
        assertFalse(t.contains("(otherBest7448 + 11.0)"))
        val b=src("engine/BotService.kt")
        val block=b.substringAfter("val roleFitPrimary6614 = when").substringBefore("val scoreForPivot4524")
        assertTrue(block.indexOf("strongestRole6614") < block.indexOf("huntClaim7297"))
    }

    @Test fun earlyHunterReachesNativeBrainBeforeOwnership(){
        val s=src("v3/scoring/MoonshotTraderAI.kt")
        assertTrue(s.contains("EarlyMoonshotHunter6415.scoreCandidate"))
        assertTrue(s.contains("MOONSHOT_EARLY_HUNTER_NATIVE_LIFT_7791"))
    }

    @Test fun runnerCoreIsRetainedAndDownsideIsNotWidened(){
        val r=src("engine/RunnerExitProfile7277.kt")
        assertTrue(r.contains("giveBackArmPct7791"))
        val m=src("engine/MoonbagRunner7322.kt")
        assertTrue(m.contains("MOONSHOT_BANK_FRACTION_7791 = 0.35"))
        val h=src("engine/truth/MoonshotHoldProfileRegistry6415.kt")
        assertTrue(h.contains("pnlPct <= 0.0"))
    }
}
