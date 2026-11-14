package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.LabAssetClass
import com.lifecyclebot.engine.lab.LabStrategy
import com.lifecyclebot.engine.lab.LabStrategyStatus
import com.lifecyclebot.engine.lab.StrategyLab8755
import com.lifecyclebot.engine.lab.StrategyLab8755.Family
import com.lifecyclebot.engine.truth.LaunchReplay8744
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8755 — the Lab grades honestly and thinks wider: typed hypotheses, held-out / replay records, one bar, automatic
 * implementation, a miner, a runner head, an evidence-briefed strategist; the structural door binds only while it protects.
 */
class Aate8755StrategyLabTest {
    private fun line(net: Double, txVel: Double, lane: String = "MOONSHOT") =
        """{"t":1,"horizonMs":900000,"mint":"m","lane":"$lane","src":"MARKET_HUNT_SHITCOIN,X","liq":5000,"mcap":20000,"age":3,"runup":10,"txVel":$txVel,"v3":70,"net":$net}"""

    @Test fun aDecisionRowCarriesItsFeaturesSourceAndCappedNet() {
        val r = StrategyLab8755.row8755(line(4_000.0, 7.0))!!
        assertEquals("MARKET_HUNT_SHITCOIN", r.src)
        assertEquals(1_000.0, r.net, 0.0)     // a label is capped at +1000 %
        assertEquals(7.0, r.f[StrategyLab8755.FEATURES.indexOf("txVel")], 0.0)
        assertTrue(r.f[StrategyLab8755.FEATURES.indexOf("top")].isNaN())
        assertNull(StrategyLab8755.row8755(line(5.0, 7.0, lane = "SETUP_X")))
        assertNull(StrategyLab8755.row8755("""{"lane":"MOONSHOT"}"""))
    }

    @Test fun aCellMatchesOnlyInsideItsBoxLaneAndSource() {
        val spec = StrategyLab8755.Spec(Family.CELL, "MOONSHOT", conds = listOf(StrategyLab8755.Cond("txVel", 5.0, 20.0)))
        val f = StrategyLab8755.row8755(line(1.0, 7.0))!!.f
        assertTrue(StrategyLab8755.matches8755(spec, "MOONSHOT", "ANYSRC", f))
        assertFalse(StrategyLab8755.matches8755(spec, "EXPRESS", "ANYSRC", f))
        assertFalse(StrategyLab8755.matches8755(spec.copy(source = "PUMP_PORTAL_WS"), "MOONSHOT", "MARKET_HUNT_SHITCOIN", f))
        assertFalse(StrategyLab8755.matches8755(spec, "MOONSHOT", "", StrategyLab8755.row8755(line(1.0, 3.0))!!.f))
        val score = StrategyLab8755.Spec(Family.SCORE, scoreMin = 60)
        assertTrue(StrategyLab8755.matches8755(score, "MOONSHOT", "", f))
        assertFalse(StrategyLab8755.matches8755(score.copy(scoreMin = 80), "MOONSHOT", "", f))
    }

    @Test fun oneBarForEveryFamily() {
        val broad = StrategyLab8755.record8755(List(150) { if (it % 3 == 0) 40.0 else -5.0 })!!
        assertTrue(StrategyLab8755.passes8755(broad, Family.CELL))
        val carried = StrategyLab8755.record8755(List(147) { -10.0 } + listOf(5_000.0, 3_000.0, 2_000.0))!!
        assertTrue(carried.mean > 0.0)
        assertFalse(StrategyLab8755.passes8755(carried, Family.CELL))                  // carried by three coins
        val thin = StrategyLab8755.record8755(List(60) { if (it % 3 == 0) 40.0 else -5.0 })!!
        assertFalse(StrategyLab8755.passes8755(thin, Family.CELL))                     // n < 100
        assertFalse(StrategyLab8755.passes8755(broad, Family.LAUNCH, replayOk = false)) // not the ladder / latency the bot has
        assertTrue(StrategyLab8755.passes8755(broad, Family.LAUNCH, replayOk = true))
        // held-out families need two SEs, replayed ones one
        val edge = StrategyLab8755.Record(200, 3.0, 0.0, 3.0, 2.0, 0.0)
        assertTrue(StrategyLab8755.passes8755(edge, Family.BASE)); assertFalse(StrategyLab8755.passes8755(edge, Family.CELL))
    }

    @Test fun theStrategistAnswersInTypedFamiliesTheGradersCover() {
        val base = StrategyLab8755.specOf8755(JSONObject("""{"family":"BASE","peakCapUsd":20000,"pullback":0.6,"baseWinMs":120000,"maxRange":1.5,"minBuys":5,"mode":"BREAKOUT","ladder":"DOCTRINE","delayMs":10000}"""))!!
        assertEquals(Family.BASE, base.family)
        assertEquals(0.6, base.base!!.trigger.pullback, 0.0)
        val launch = StrategyLab8755.specOf8755(JSONObject("""{"family":"launch","capUsd":999999,"minBuys":8,"ladder":"MOONBAG"}"""))!!
        assertEquals(60_000.0, launch.launch!!.capUsd, 0.0)          // held to the range the replay covers
        assertEquals(LaunchReplay8744.Ladder.MOONBAG, launch.launch!!.ladder)
        val cell = StrategyLab8755.specOf8755(JSONObject("""{"family":"CELL","lane":"moonshot","conds":[{"f":"txVel","lo":5,"hi":30},{"f":"nope","lo":0,"hi":1}]}"""))!!
        assertEquals("MOONSHOT", cell.lane); assertEquals(1, cell.conds.size)
        assertNull(StrategyLab8755.specOf8755(JSONObject("""{"family":"CELL","conds":[]}""")))
        assertNull(StrategyLab8755.specOf8755(JSONObject("""{"family":"MAGIC"}""")))
        assertEquals(95, StrategyLab8755.specOf8755(JSONObject("""{"family":"RUNNER","pct":94}"""))!!.runnerPct)
        // round trip through what the Lab stores
        assertEquals(base, StrategyLab8755.specOf8755(JSONObject(StrategyLab8755.specJson8755(base))))
        val s = LabStrategy(id = "x", name = "n", rationale = "r", asset = LabAssetClass.MEME, entryScoreMin = 0, entryRegime = "ANY",
            takeProfitPct = 10.0, stopLossPct = -8.0, maxHoldMins = 60, sizingSol = 0.05, status = LabStrategyStatus.ACTIVE,
            spec8755 = StrategyLab8755.specJson8755(cell), honestN8755 = 150, honestExTop3_8755 = 9.0, honestSe8755 = 2.0, honestAt8755 = 1L)
        assertEquals(s.spec8755, LabStrategy.fromJson(s.toJson()).spec8755)
        assertEquals(9.0, LabStrategy.fromJson(s.toJson()).honestExTop3_8755, 0.0)
        assertTrue(StrategyLab8755.honestPass8755(s))
        assertFalse(StrategyLab8755.honestPass8755(s.copy(honestSe8755 = 5.0)))
        // a legacy (untyped) meme strategy with no honest grade does not pass; a non-meme one is not graded here
        val legacy = s.copy(spec8755 = "", honestN8755 = 0, honestExTop3_8755 = Double.NaN, honestSe8755 = Double.NaN)
        assertFalse(StrategyLab8755.honestPass8755(legacy))
        assertTrue(StrategyLab8755.honestPass8755(legacy.copy(asset = LabAssetClass.STOCK)))
    }

    @Test fun theMinerScoresWithoutItsThreeBest() {
        val acc = StrategyLab8755.Acc()
        repeat(147) { acc.add(-2.0) }; acc.add(900.0); acc.add(800.0); acc.add(700.0)
        assertTrue(acc.score() < 0.0)
        val good = StrategyLab8755.Acc(); repeat(150) { good.add(if (it % 2 == 0) 10.0 else 0.0) }
        assertTrue(good.score() > 0.0)
        val thin = StrategyLab8755.Acc(); repeat(100) { thin.add(10.0) }
        assertEquals(Double.NEGATIVE_INFINITY, thin.score(), 0.0)
    }

    @Test fun promotedStrategiesAreImplementedAutomatically() {
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains("com.lifecyclebot.engine.lab.StrategyLab8755.evidence8755(ts, lane, nowMs)"))
        assertTrue(adm.contains("!GateOutcome8418.structureBindsByRecord8755(\"CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136\", c.lane)"))
        val sn = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchSniper8748.kt").readText()
        assertTrue(sn.contains("LaunchReplay8744.proven8748() ?: com.lifecyclebot.engine.lab.StrategyLab8755.launchProven8755()"))
        assertTrue(sn.contains("BaseFamily8754.proven8754() ?: com.lifecyclebot.engine.lab.StrategyLab8755.baseProven8755()"))
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/lab/LlmLabEngine.kt").readText()
        assertTrue(lab.contains("StrategyLab8755.maybeRun8755(now)"))
        assertTrue(lab.contains("val evidence8755 = try { StrategyLab8755.brief8755() }"))
        assertTrue(lab.contains("(!meme8755 || StrategyLab8755.honestPass8755(s))"))
        assertTrue(lab.contains("if (s.spec8755.isNotBlank()) continue   // V5.0.8755 — typed hypotheses are graded on evidence"))
        val feed = File("src/main/kotlin/com/lifecyclebot/engine/lab/LabPromotedFeed.kt").readText()
        assertTrue(feed.contains("if (!StrategyLab8755.honestPass8755(s)) return \"LIVE_BAR_HONEST_RECORD_8755_n${'$'}{s.honestN8755}\""))
        val scan = File("src/main/kotlin/com/lifecyclebot/engine/SolanaMarketScanner.kt").readText()
        assertTrue(scan.contains("StrategyLab8755.sourceFactor8755(token.source.name)"))
        assertEquals(1.0, StrategyLab8755.sourceFactor8755("NEVER_SEEN"), 0.0)
    }
}
