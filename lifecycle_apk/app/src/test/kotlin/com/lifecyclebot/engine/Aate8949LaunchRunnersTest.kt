package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaneAuthority8577
import com.lifecyclebot.engine.truth.LaunchSniper8748
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.engine.truth.appendRow8949
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8949 — the launch replay's proven rule is bought and held: an armed fire runs the coin's pipeline at once, the rule
 * answers the lane-wide entry priors like the other proven narrower groups, and its position holds on the rule's stop.
 */
class Aate8949LaunchRunnersTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val now = 1_800_000_000_000L

    @Test fun theProvenRuleAnswersTheLaneWidePriors() {
        assertTrue(LaneAuthority8577.answersPriors8874(LaunchSniper8748.KIND_8748))
        assertEquals("LAUNCH_RULE_8748", LaunchSniper8748.KIND_8748)
        assertFalse(LaneAuthority8577.answersPriors8874("MARKET_CELL"))
        // hard safety, structure, sellability and economics are not priors: the admission still refuses on them
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(adm.contains("c.safetyRefusal?.let { trace += \"safety=\$it\"; return done(Terminal8136.REFUSE, \"SAFETY_REFUSED_8136\", true, c.selectedSetup) }"))
    }

    @Test fun aFiresPositionHoldsOnTheRulesStopInsideTheOrdinaryMaximum() {
        assertEquals("graded 35 %, held at the ordinary maximum", 25.0, LaunchSniper8748.ruleStop8949(35.0, now, now + 5_000L)!!, 0.0)
        assertEquals(20.0, LaunchSniper8748.ruleStop8949(20.0, now, now + 5_000L)!!, 0.0)
        assertNull("bought long after the fire was admitted: not the fire's position", LaunchSniper8748.ruleStop8949(35.0, now, now + 11L * 60_000L))
        assertNull(LaunchSniper8748.ruleStop8949(Double.NaN, now, now))
        assertNull("no admitted fire", LaunchSniper8748.ruleStopMag8949("NoSuchMint", now))
        val stop = src("engine/cortex/StopAuthority7887.kt")
        assertTrue(stop.contains("if (rule8949 != null && rule8949 > out + 1e-9) return com.lifecyclebot.engine.ExitThresholds8418.Binding8418(rule8949, \"LAUNCH_RULE_8949\")"))
        assertTrue("never tighter than the stop the position already had", stop.indexOf("rule8949 > out") > 0)
    }

    @Test fun anArmedFireRunsTheCoinsPipelineAtOnce() {
        val sniper = src("engine/truth/LaunchSniper8748.kt")
        assertTrue(sniper.contains("try { onArmed8949?.let { it(f.mint); runs8949.incrementAndGet() } } catch (_: Throwable) {}"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("dispatchArmed9054(mint, \"LAUNCH_RULE_8949\", f9054.opportunityId9043)"))
        assertTrue(bot.contains("promoteHotLaunchNow7921(mint, sym8949, name8949, streamGeneration, source8945 = source, onceKey8945 = fireKey)"))
        assertTrue(bot.contains("dispatchArmed9054(mint, \"BASE_BREAKOUT_8995\", fireKey)"))
        assertTrue(bot.contains("if (source8945 == \"LAUNCH_PRINTS_8942\" && status.tokens.containsKey(mint))"))
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("LaunchSniper8748.noteAdmission8949(ts.mint, k, it.terminal.name, nowMs)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 137. The launch rule's fires are bought and held (8949)"))
    }

    @Test fun theMemoryWorkKeepsTheSameRecordsWithLessGarbage() {
        // a stored record gains its row as text, trimmed to the last rows, the kept rows unchanged
        assertEquals("1,2", appendRow8949(null, "1,2", 3))
        assertEquals("1,2", appendRow8949("", "1,2", 3))
        assertEquals("1,1;2,2;3,3", appendRow8949("1,1;2,2", "3,3", 3))
        assertEquals("2,2;3,3;4,4", appendRow8949("1,1;2,2;3,3", "4,4", 3))
        assertEquals("legacy line breaks count as row breaks", "b,2;c,3", appendRow8949("a,1\nb,2", "c,3", 2))
        // the cost breakdown's digits without a Formatter: as "%.Nf" prints them
        for (v in listOf(0.0, 1.2, 5.949999, 0.000301, 23150.0, 1234.5678, 3.48, 0.71)) {
            assertEquals("%.2f".format(java.util.Locale.ROOT, v), NetEdge8030.fixed8949(v, 2))
            assertEquals("%.6f".format(java.util.Locale.ROOT, v), NetEdge8030.fixed8949(v, 6))
            assertEquals("%.0f".format(java.util.Locale.ROOT, v), NetEdge8030.fixed8949(v, 0))
        }
        assertEquals("NaN", NetEdge8030.fixed8949(Double.NaN, 2))
        val store = src("engine/truth/ShadowColdStore8397.kt")
        assertTrue(store.contains("payloads[k] = appendRow8949(raw, rowText, maxRows)"))
        assertFalse("no list of strings per row in the decode", store.substringAfter("private fun decode(value: String?)").substringBefore("private fun encode(").contains("row.split(','"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("private val RX8949_1 = Regex(\"lane=([A-Z0-9_]+)\")"))
    }
}
