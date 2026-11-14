package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import com.lifecyclebot.engine.truth.LaunchReplay8744.Rule
import com.lifecyclebot.engine.truth.LaunchSniper8748
import com.lifecyclebot.engine.truth.LlmAnalyst8746
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8748 — the launch replay tells the truth: one bad print is not a 10x, a backfilled print is not an entry, a sale
 * fills where the curve stood after the latency, a rule carried by three coins is not proven; the sniper fires on the
 * replay's own trigger; the LLM analyst's answers are read.
 */
class Aate8748HonestReplayTest {
    private val solUsd = 100.0   // 1e-7 SOL/token x 1e9 x $100 = $10k
    private val rule = Rule(10_000.0, 3, 3.0, 35.0, Ladder.HALF_TRAIL)

    @Test fun anIsolatedBadPrintIsDroppedNotBanked() {
        val p = doubleArrayOf(1e-7, 1e-7, 1e-7, 1e-7, 1e-4, 1e-7, 1e-7, 1e-7)
        val drop = LaunchReplay8744.spikes8748(p)
        assertTrue(drop[4])
        assertEquals(1, drop.count { it })
        // a real step up that holds is kept
        assertFalse(LaunchReplay8744.spikes8748(doubleArrayOf(1e-7, 1e-7, 1e-7, 5e-7, 5e-7, 6e-7))[3])
        val t = longArrayOf(0, 1_000, 2_000, 4_000, 30_000, 40_000, 50_000, 3_000_000)
        val net = LaunchReplay8744.simulate8744(t, p, BooleanArray(t.size) { true }, rule, solUsd, 0.0)!!
        assertEquals(0.0, net, 1e-9)   // flat coin, the 1000x print never sold anything
    }

    @Test fun aBackfilledPrintNeverTriggersAnEntry() {
        val t = longArrayOf(0, 1_000, 2_000, 4_000, 60_000, 120_000)
        val p = doubleArrayOf(1e-7, 1e-7, 1e-7, 1e-7, 4e-7, 1e-6)
        val b = BooleanArray(t.size) { true }
        assertNull(LaunchReplay8744.simulate8744(t, p, b, rule, solUsd, 0.0, live = BooleanArray(t.size) { false }))
        // live only from the 60 s print: its own buy and the 120 s one are the only buys it can count (needs 3) -> no entry
        assertNull(LaunchReplay8744.simulate8744(t, p, b, rule, solUsd, 0.0, live = BooleanArray(t.size) { it >= 4 }))
        assertNotNull(LaunchReplay8744.simulate8744(t, p, b, rule, solUsd, 0.0, live = BooleanArray(t.size) { true }))
    }

    @Test fun theEntryFillsWhereTheCurveStoodAfterTheLatency() {
        // trigger at 2 s; at +10 s the curve stands at the 11 s print (1.5e-7), not the trigger's 1e-7
        val t = longArrayOf(0, 1_000, 2_000, 11_000, 13_000, 60_000, 3_000_000)
        val p = doubleArrayOf(1e-7, 1e-7, 1e-7, 1.5e-7, 1.6e-7, 1.5e-7, 1.5e-7)
        val b = BooleanArray(t.size) { true }
        val fast = LaunchReplay8744.simulate8744(t, p, b, rule.copy(delayMs = 2_000L), solUsd, 0.0)!!
        val slow = LaunchReplay8744.simulate8744(t, p, b, rule.copy(delayMs = 10_000L), solUsd, 0.0)!!
        assertEquals(50.0, fast, 1e-6)    // entered at 1e-7, held to the hour's 1.5e-7
        assertEquals(0.0, slow, 1e-6)     // entered at 1.5e-7: the latency ate the move
    }

    @Test fun aRuleCarriedByThreeCoinsIsNotProven() {
        val r = Rule(10_000.0, 8, 3.0, 20.0, Ladder.DOCTRINE, 10_000L)
        val carried = List(117) { -10.0 } + listOf(5_000.0, 3_000.0, 2_000.0)
        val res = LaunchReplay8744.stats8748(r, carried)!!
        assertTrue(res.mean > 0.0)
        assertEquals(-10.0, res.exTop3, 1e-9)
        assertEquals(-10.0, res.median, 1e-9)
        assertNull(LaunchReplay8744.provenOf8748(listOf(res)))
        val broad = LaunchReplay8744.stats8748(r, List(120) { if (it % 3 == 0) 40.0 else -5.0 })!!
        assertEquals(broad, LaunchReplay8744.provenOf8748(listOf(broad)))
        // the same record on a ladder the bot does not run, or at a latency it cannot meet, is not tradeable
        assertNull(LaunchReplay8744.provenOf8748(listOf(broad.copy(rule = r.copy(ladder = Ladder.MOONBAG)))))
        assertNull(LaunchReplay8744.provenOf8748(listOf(broad.copy(rule = r.copy(delayMs = 2_000L)))))
    }

    @Test fun theSniperFiresOnTheReplaysOwnTriggerAndFeedsAdmission() {
        assertTrue(LaunchSniper8748.triggers8748(rule, 10_500.0, 3))
        assertFalse(LaunchSniper8748.triggers8748(rule, 9_000.0, 30))
        assertFalse(LaunchSniper8748.triggers8748(rule, 50_000.0, 2))
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains("marketCell8723 = PayingCell8723.evidence8723(ts, lane, nowMs) ?: LaunchSniper8748.evidence8748(ts.mint, nowMs)\n"))   // V5.0.8755: then the Lab's promoted strategies
        val helius = File("src/main/kotlin/com/lifecyclebot/network/HeliusCurveTape8354.kt").readText()
        // V5.0.8750 (supersedes the direct call): every program print takes the one witnessed launch-evidence route first
        assertTrue(helius.indexOf("TradeTape8100.launchEvidence8750(") in 0 until helius.indexOf("if (!cares8354(mint, nowMs))"))
        val hour = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchHour8628.kt").readText()
        assertTrue(hour.contains("ALTER TABLE trade ADD COLUMN r INTEGER"))
    }

    @Test fun theAnalystsAnswersAreRead() {
        assertEquals(0.12, LlmAnalyst8746.parse8746("{\"p_up\": 0.12, \"why\": \"thin\"}")!!, 1e-9)
        assertEquals(0.12, LlmAnalyst8746.parse8746("```json\n{\"p_up\": \"0.12\"}\n```")!!, 1e-9)
        assertEquals(0.12, LlmAnalyst8746.parse8746("P_UP = 12%")!!, 1e-9)
        assertEquals(0.3, LlmAnalyst8746.parse8746("{\"p_up\": 30}")!!, 1e-9)
        assertNull(LlmAnalyst8746.parse8746("I cannot estimate that."))
        assertNull(LlmAnalyst8746.parse8746("{\"p_up\": 250}"))
    }
}
