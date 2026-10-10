package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.HunterMandates8031
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8031 — a reason for every verdict (buy / hold / sell), graded; hunters pick what their lane can buy; heap census. */
class Aate8031DecisionReasonsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun everyVerdictHasANamedRule() {
        assertEquals("MCAP_TOO_LOW", DecisionReasons8031.rule8031("MCAP too low: \$2670 < \$75000"))
        assertEquals("NO_CONFIRMED_SCALP_SETUP", DecisionReasons8031.rule8031("NO_CONFIRMED_SCALP_SETUP_7547"))
        assertEquals("LIVE|HOLD:NET_PROFIT_8030:TICK_PROFIT_LOCK@SHITCOIN",
            DecisionReasons8031.key8031(true, "HOLD", "NET_PROFIT_8030", "TICK_PROFIT_LOCK_peak6_now3", "shitcoin"))
        assertTrue(DecisionReasons8031.gradeFor8031("nothing") == null)
        assertTrue(DecisionReasons8031.statusLine().contains("native["))
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("DecisionReasons8031.nativeVerdicts8031(ts, out.values)"))
        val e = src("engine/Executor.kt")
        for (g in listOf("RUNNER_HOLD_7967", "BASIS_BREAK_8019", "NET_PROFIT_8030", "NET_PROFIT_PARTIAL_8030"))
            assertTrue(g, e.contains("DecisionReasons8031.hold8031(ts, \"$g\", reason)"))
        assertTrue(src("engine/truth/CanonicalFinalizedTradeBus6464.kt").contains("DecisionReasons8031.sell8031(env)"))
        assertTrue(src("engine/truth/ExitRegret7752.kt").contains("DecisionReasons8031.tick8031(priceFor, nowMs)"))
    }

    @Test fun huntersPickWhatTheirLaneCanBuy() {
        assertTrue(HunterMandates8031.dipFits8031(5.0, 50_000.0, 400_000.0, -12.0, Double.NaN, Double.NaN))
        assertFalse(HunterMandates8031.dipFits8031(1.0, 50_000.0, 400_000.0, -12.0, Double.NaN, Double.NaN))   // too young
        assertFalse(HunterMandates8031.dipFits8031(5.0, 8_000.0, 60_000.0, -12.0, Double.NaN, Double.NaN))     // thin
        assertFalse(HunterMandates8031.dipFits8031(5.0, 50_000.0, 400_000.0, -1.0, -5.0, Double.NaN))          // no pullback
        assertFalse(HunterMandates8031.dipFits8031(5.0, 50_000.0, 400_000.0, -12.0, Double.NaN, -4.0))         // still falling
        assertTrue(HunterMandates8031.dipRank8031(-25.0, Double.NaN) > HunterMandates8031.dipRank8031(-50.0, Double.NaN))
        assertTrue(HunterMandates8031.expressFits8031(0.5, 12.0, 3.0))
        assertFalse(HunterMandates8031.expressFits8031(0.5, 45.0, 3.0))   // CHASE_EXTENDED natively
        assertFalse(HunterMandates8031.expressFits8031(0.0, 12.0, 3.0))   // unknown age
        assertFalse(HunterMandates8031.knownAgeWithin8031(0.0, 0.05))
        assertTrue(HunterMandates8031.knownAgeWithin8031(0.02, 0.05))
    }

    @Test fun treasuryReadyMeansElectableAndRescueSkipsLanesWithoutABuyer() {
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("TREASURY_ROLE_FLOOR_8031"))
        assertTrue(src("engine/BotService.kt").contains("private val RESCUE_EXCLUDED_8031 = setOf(\"CORE\", \"CYCLIC\")"))
    }

    @Test fun theHeapCensusNamesTheBigStores() {
        assertEquals("b:2KB,a:1KB", MemoryGuard7977.topSizes8031(mapOf("a" to 1024L, "b" to 2048L)))
        assertEquals("-", MemoryGuard7977.topSizes8031(emptyMap()))
    }
}
