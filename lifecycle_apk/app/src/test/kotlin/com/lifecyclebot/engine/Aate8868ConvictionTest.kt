package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.GateOutcome8418
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.engine.truth.TrajectoryVerdict8855
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8868 — owner: "you can see theres trading things that need to be fixed … use the data vs the code". On the 19 Nov
 * dataset the model's strong forecasts paid in every main lane while each lane's authority was read only on all its picks
 * from +1 %, and the proven call was read after the optional data waits had parked the coin. A forecast is now judged on the
 * record of its own strength, and a proven call is not parked on a read the bot could not take.
 */
class Aate8868ConvictionTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Before fun setup() { GateOutcome8418.resetForTests8418(); TrajectoryVerdict8855.resetForTest8855(); EdgeModel8537.resetForTest8537() }
    @After fun cleanup() { GateOutcome8418.resetForTests8418(); TrajectoryVerdict8855.resetForTest8855(); EdgeModel8537.resetForTest8537() }

    // the measured MOONSHOT tiers (+5 / +10 / +15 / +20 %), lane average -6.9 %
    private val mN = intArrayOf(164, 84, 44, 29)
    private val mMean = doubleArrayOf(5.1, 11.8, 13.5, 19.5)
    private val mSe = doubleArrayOf(3.7, 5.8, 6.6, 8.2)

    @Test fun aForecastIsReadAtTheNarrowestProvenTierItReached() {
        assertEquals(15.0, EdgeModel8537.convictionTier8868(25.0, mN, mMean, mSe, -6.9), 0.0)
        assertEquals("the +20 % tier holds 29: not yet", 15.0, EdgeModel8537.convictionTier8868(40.0, mN, mMean, mSe, -6.9), 0.0)
        assertEquals(10.0, EdgeModel8537.convictionTier8868(12.0, mN, mMean, mSe, -6.9), 0.0)
        assertEquals(5.0, EdgeModel8537.convictionTier8868(7.0, mN, mMean, mSe, -6.9), 0.0)
        assertTrue("below every tier", EdgeModel8537.convictionTier8868(4.9, mN, mMean, mSe, -6.9).isNaN())
        assertTrue(EdgeModel8537.convictionTier8868(Double.NaN, mN, mMean, mSe, -6.9).isNaN())
        // QUALITY: +5 % unproven (2.6 +/- 4.7), +10 % proven, +15 % 28 coins — a +25 % forecast reads the +10 % tier
        assertEquals(10.0, EdgeModel8537.convictionTier8868(25.0, intArrayOf(110, 47, 28, 22),
            doubleArrayOf(2.6, 10.7, 3.5, -3.7), doubleArrayOf(4.7, 8.4, 9.4, 9.8), -7.8), 0.0)
        // PROJECT_SNIPER: no tier pays, none earns
        assertTrue(EdgeModel8537.convictionTier8868(30.0, intArrayOf(56, 36, 23, 18),
            doubleArrayOf(-11.4, -12.3, -19.7, -15.1), doubleArrayOf(7.5, 10.4, 9.9, 11.0), -12.9).isNaN())
        // a tier must beat the lane's own average, as the picks' authority must
        assertTrue(EdgeModel8537.convictionTier8868(25.0, mN, mMean, mSe, 20.0).isNaN())
        assertEquals(6.9, EdgeModel8537.convictionLower8868(15.0, mMean, mSe), 1e-9)
        assertTrue(EdgeModel8537.convictionLower8868(7.5, mMean, mSe).isNaN())
    }

    @Test fun theRecordFillsEveryTierItsForecastReachedClippedAsTheRefusalRecord() {
        val o = EdgeModel8537.Oos()
        repeat(40) { o.add(12.0, 20.0) }
        repeat(40) { o.add(2.0, -10.0) }
        o.add(30.0, 900.0)
        assertEquals(41, o.tierN[0]); assertEquals(41, o.tierN[1]); assertEquals(1, o.tierN[2]); assertEquals(1, o.tierN[3])
        assertEquals("one runner is clipped at +200 %", 40 * 20.0 + 200.0, o.tierSum[0], 1e-9)
        assertEquals(81, o.allN)
        o.addTier8868(Double.NaN, 5.0); o.addTier8868(25.0, Double.NaN)
        assertEquals(41, o.tierN[0])
        assertTrue(o.tierSe8868(2).isInfinite())
    }

    @Test fun aStrengthTierCallsPositiveAndNamesItsProvenBound() {
        val tier = EdgeModel8537.Verdict(25.0, false, "MIND8842", 900L, 345, -1.7, 2.3, -6.9,
            conviction8868 = 15.0, convictionLower8868 = 6.9)
        assertEquals(EdgeModel8537.Call.POSITIVE, EdgeModel8537.callOf(tier))
        assertEquals(6.9, EdgeModel8537.provenLower8868(tier), 1e-9)
        val none = tier.copy(conviction8868 = Double.NaN, convictionLower8868 = Double.NaN)
        assertEquals("the diluted +1 % picks alone prove nothing", EdgeModel8537.Call.NONE, EdgeModel8537.callOf(none))
        assertTrue(EdgeModel8537.provenLower8868(none).isNaN())
        val lane = none.copy(authority = true, oosSelMeanPct = 8.0, oosSelSePct = 2.0)
        assertEquals(6.0, EdgeModel8537.provenLower8868(lane), 1e-9)
        // a forecast loss is never a positive call, whatever tier is named
        assertEquals(EdgeModel8537.Call.NONE, EdgeModel8537.callOf(tier.copy(predPct = -3.0)))
    }

    @Test fun theSeedTakesOnlyRowsTheLiveRecordWouldHaveTaken() {
        fun row(fill: Boolean = true, epoch: Int = 8801, pred: Double? = 22.0) = JSONObject()
            .put("mint", "Abcdefghijkmnpqrstuvwxyz123456789pump").put("lane", "MOONSHOT").put("fill", fill)
            .put("horizonMs", 900_000).put("mcap", 40_000.0).put("net", 30.0).put("cost", 4.0)
            .put("model", "MIND8842").put("modelEpoch", epoch).apply { if (pred != null) put("pred", pred) }
        val r = EdgeModel8537.tierSeedRow8868(row())!!
        assertEquals("net|MOONSHOT|MIND8842", r.first)
        assertEquals(22.0, r.second, 0.0)
        assertEquals(EdgeModel8537.managedTarget8722("MOONSHOT", 30.0, 4.0), r.third, 1e-9)
        assertNull("unfilled", EdgeModel8537.tierSeedRow8868(row(fill = false)))
        assertNull("another model epoch", EdgeModel8537.tierSeedRow8868(row(epoch = 8642)))
        assertNull("no frozen forecast", EdgeModel8537.tierSeedRow8868(row(pred = null)))
    }

    private val base = CandidateAdmission8136.Candidate8136("m8868", "MOONSHOT", null,
        expectedGrossPct = Double.NaN, costPct = 4.5, genericScore = 80.0, genericFloor = 15.0, live = true)
    private fun proven(lower: Double = 6.9) = EdgeModel8537.Verdict(25.0, false, "MIND8842", 900L, 345, -1.7, 2.3, -6.9,
        conviction8868 = 15.0, convictionLower8868 = lower)
    private val unproven = EdgeModel8537.Verdict(25.0, false, "MIND8842", 900L, 345, -1.7, 2.3, -6.9)

    @Test fun aProvenForecastIsNotParkedOnAReadTheBotCouldNotTake() {
        val d = CandidateAdmission8136.decide8136(base.copy(entryWait8506 = "FLOW_UNOBSERVED", edge8537 = proven()))
        assertEquals(d.trace.joinToString(" | "), Terminal8136.ADMIT, d.terminal)
        assertEquals(CandidateAdmission8136.EDGE_MODEL_PROVEN_8537, d.reason)
        assertTrue(d.trace.any { it == "edgeModelProven8868 optionalFlow=swaps,bp,chg" })
        assertTrue(d.trace.any { it == "entryWait8506=FLOW_UNOBSERVED overriddenBy=EDGE_MODEL_8868" })
        assertTrue(d.trace.any { it.startsWith("grossFromEdgeModelLower8868=+6.9 cost=+4.5") })
        assertEquals(6.9, d.expectedNetPct, 1e-9)
        // the same coin without a proven call waits, as before
        val w = CandidateAdmission8136.decide8136(base.copy(entryWait8506 = "FLOW_UNOBSERVED", edge8537 = unproven))
        assertEquals(Terminal8136.DEFER_DATA, w.terminal)
        assertEquals("DATA_INCOMPLETE_DEFER_8136", w.reason)
    }

    @Test fun economicsJudgeTheProvenBoundAsAnyMove() {
        // +2.0 net on a 4.5 % trip: gross 6.5 < 1.5 x 4.5
        val d = CandidateAdmission8136.decide8136(base.copy(edge8537 = proven(lower = 2.0)))
        assertEquals(Terminal8136.REFUSE, d.terminal)
        assertEquals("EXECUTION_ECONOMICS_8136", d.reason)
        // a known current move is never replaced by the record (8846)
        val k = CandidateAdmission8136.decide8136(base.copy(expectedGrossPct = 5.0, edge8537 = proven()))
        assertEquals("EXECUTION_ECONOMICS_8136", k.reason)
        assertFalse(k.trace.any { it.startsWith("grossFromEdgeModelLower8868") })
        // a proven loss to zero never supplies a move
        val z = CandidateAdmission8136.decide8136(base.copy(edge8537 = proven(lower = -1.0)))
        assertEquals(Terminal8136.DEFER_DATA, z.terminal)
    }

    @Test fun anUnprovenFallingHourGivesWayAProvenLosingOneStillRefuses() {
        val falling = base.copy(moveWait8418 = NetEdge8030.MOVE_WAIT_FALLING_8418)
        val d = CandidateAdmission8136.decide8136(falling.copy(edge8537 = proven()))
        assertEquals(d.trace.joinToString(" | "), Terminal8136.ADMIT, d.terminal)
        assertTrue(d.trace.any { it.contains("edgeModelProven8868") && it.startsWith("fallingHour8418") })
        val w = CandidateAdmission8136.decide8136(falling.copy(edge8537 = unproven))
        assertEquals(CandidateAdmission8136.FALLING_HOUR_DEFER_8418, w.reason)
        val a = src("engine/truth/CandidateAdmission8136.kt")
        // V5.0.8965: a cell-priced move (grossFromCell8965) meets the same falling-hour block.
        assertTrue(a.contains("if ((!decisionGross.isFinite() || grossFromModel8868 || grossFromCell8965) && moveWaitChosen8418 == NetEdge8030.MOVE_WAIT_FALLING_8418 && !paperExplore8539) {"))
        // the proven-losing refusal sits before the model's way through
        assertTrue(a.indexOf("return done(Terminal8136.REFUSE, FALLING_HOUR_PROVEN_LOSING_8418, true, chosen)") <
            a.indexOf("trace += \"fallingHour8418 setup=\${chosen ?: \"-\"} unproven=\${proof8418?.compact ?: \"n0\"} edgeModelProven8868\""))
    }

    @Test fun theTiersPersistSeedOnceAndShowInTheStatusLine() {
        val m = src("engine/truth/EdgeModel8537.kt")
        assertTrue(m.contains("o.writeInt(CONVICTION_BLOCK_8868); o.writeInt(CONVICTION_TIERS_8868.size); o.writeInt(oos.size)"))
        assertTrue(m.contains("} else if (!conviction8868Loaded) {"))
        assertTrue(m.contains("for (o in oos.values) { o.tierN.fill(0); o.tierSum.fill(0.0); o.tierSq.fill(0.0) }"))
        assertTrue(m.contains("v.conviction8868.isFinite() && v.predPct >= v.conviction8868 -> Call.POSITIVE"))
        assertTrue(EdgeModel8537.statusLine().contains("convSeed8868="))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 59. A forecast judged at its own strength (8868)"))
    }
}
