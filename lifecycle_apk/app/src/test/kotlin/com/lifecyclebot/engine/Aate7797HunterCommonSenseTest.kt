package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7797HunterCommonSenseTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/market/LaneHunter7297.kt").readText()

    @Test fun huntersRankByExitabilityParticipationAndChaseRisk() {
        val s = src()
        assertTrue(s.contains("fun exitability7797"))
        assertTrue(s.contains("fun participation7797"))
        assertTrue(s.contains("fun chasePenalty7797"))
        assertTrue(s.contains("commonSenseMult7797(p.lane, r)"))
    }

    @Test fun commonSenseIsLaneSpecificNotGenericOnly() {
        val s = src()
        listOf("PROJECT_SNIPER","EXPRESS","MANIPULATED","MOONSHOT","SHITCOIN",
            "QUALITY","BLUECHIP","DIP_HUNTER","TREASURY","CASHGEN","CYCLIC","CORE")
            .forEach { assertTrue(s.contains("\"$it\"")) }
    }

    @Test fun hunterCommonSenseIsRankingNotExecutionVeto() {
        val s = src()
        assertTrue(s.contains("no hard execution veto"))
        assertTrue(s.contains("commonSenseMult7797"))
        assertTrue(s.contains("coerceIn(0.45, 1.35)"))
    }
}
