package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AgreementPocket8874
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8874 — owner, 20 Nov: "please finish out the full list of work from our document please and remember this has to
 * boost profits and return to capturing runners". The plan's first admit path: an off-pump.fun coin at a $5k-$20k cap on
 * which the V3 score (23+) and the edge model's forecast (+10 %+) agree, admitted per lane on that lane's own proven record.
 */
class Aate8874AgreementPocketTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val other = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pump = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFpump"

    @Before fun setUp() = AgreementPocket8874.resetForTest8874()
    @After fun tearDown() = AgreementPocket8874.resetForTest8874()

    @Test fun thePocketIsAnOffPumpCoinWhereTheScoreAndTheForecastAgree() {
        assertTrue(AgreementPocket8874.inPocket8874(other, 12_000.0, 30.0, 18.0))
        assertFalse("pump.fun mints lost in every cut (+0.8 % on the same rule)", AgreementPocket8874.inPocket8874(pump, 12_000.0, 30.0, 18.0))
        assertTrue("edges are inclusive below", AgreementPocket8874.inPocket8874(other, 5_000.0, 23.0, 10.0))
        assertFalse("and exclusive at the top of the cap band", AgreementPocket8874.inPocket8874(other, 20_000.0, 30.0, 18.0))
        assertFalse(AgreementPocket8874.inPocket8874(other, 4_999.0, 30.0, 18.0))
        assertFalse("the score alone is not the pocket", AgreementPocket8874.inPocket8874(other, 12_000.0, 30.0, 9.9))
        assertFalse("the forecast alone is not the pocket", AgreementPocket8874.inPocket8874(other, 12_000.0, 22.9, 40.0))
        assertFalse("an unread value is never in", AgreementPocket8874.inPocket8874(other, Double.NaN, 30.0, 18.0))
        assertFalse(AgreementPocket8874.inPocket8874(other, 12_000.0, 30.0, Double.NaN))
        assertEquals("MOONSHOT_AGREE", AgreementPocket8874.key8874(" moonshot "))
        assertNull(AgreementPocket8874.key8874(" "))
    }

    @Test fun aLaneAdmitsOnlyOnItsOwnProvenRecord() {
        // 40 coins in MOONSHOT's pocket: half +80 %, half -10 % — mean +35 %, proven; CORE has only losing labels
        repeat(40) { i -> AgreementPocket8874.addForTest8874("MOONSHOT", "M$i", if (i % 2 == 0) 80.0 else -10.0, 1_000L + i) }
        repeat(40) { i -> AgreementPocket8874.addForTest8874("CORE", "C$i", -10.0, 1_000L + i) }
        val moon = AgreementPocket8874.evidenceOf8874("MOONSHOT_AGREE", AgreementPocket8874.recordForTest8874("MOONSHOT"))
        assertNotNull(moon)
        assertEquals(AgreementPocket8874.KIND_8874, moon!!.kind)
        assertTrue("its lower bound is above zero: ${moon.lowerPct}", moon.lowerPct > 0.0)
        assertTrue("the admission's own support test passes it", CandidateAdmission8136.supported8136(moon, 3.0))
        assertNull("a losing lane's pocket admits nothing",
            AgreementPocket8874.evidenceOf8874("CORE_AGREE", AgreementPocket8874.recordForTest8874("CORE")))
        assertNull("a lane with no pocket record admits nothing", AgreementPocket8874.recordForTest8874("TREASURY"))
        // one label per coin per lane per 15 minutes: the same coin again inside the window is not filed twice
        val before = AgreementPocket8874.recordForTest8874("MOONSHOT")!![0]
        AgreementPocket8874.addForTest8874("MOONSHOT", "M0", 80.0, 1_000L + 60_000L)
        assertEquals(before, AgreementPocket8874.recordForTest8874("MOONSHOT")!![0], 0.0)
        val line = AgreementPocket8874.statusLine8874()
        // decayed 0.995 a label (PocketLedger8610.DECAY): 40 labels weigh 36, the later ones a little more
        assertTrue(line, line.contains("MOONSHOT=n36/+34.9%/run0%*") && line.contains("CORE=n36/-10.0%/run0%") && !line.contains("CORE=n36/-10.0%/run0%*"))
    }

    @Test fun theDevicesOwnDatasetSeedsIt() {
        fun row(mint: String, mcap: Double, v3: Double, pred: Double, net: Double?, t: Long) = JSONObject()
            .put("lane", "EXPRESS").put("mint", mint).put("mcap", mcap).put("v3", v3).put("pred", pred).put("net", net ?: JSONObject.NULL).put("t", t)
        AgreementPocket8874.seedRow8863(row(other, 9_000.0, 31.0, 22.0, 45.0, 10L))
        AgreementPocket8874.seedRow8863(row(pump, 9_000.0, 31.0, 22.0, 45.0, 20L))
        AgreementPocket8874.seedRow8863(row("X$other", 60_000.0, 31.0, 22.0, 45.0, 30L))
        AgreementPocket8874.seedRow8863(row("Y$other", 9_000.0, 31.0, 22.0, null, 40L))
        assertEquals("only the in-pocket, labelled row is filed", 1.0, AgreementPocket8874.recordForTest8874("EXPRESS")!![0], 1e-9)
        val replay = src("engine/truth/DatasetReplay8863.kt")
        assertTrue(replay.contains("{ o -> AgreementPocket8874.seedRow8863(o) },"))
        val pocket = src("engine/truth/AgreementPocket8874.kt")
        assertTrue(pocket.contains("ready = DatasetReplay8863.await8863()") && pocket.contains("internal fun seedRow8863(o: JSONObject)"))
        assertFalse("it never reads the dataset itself", pocket.contains("forEachLine") || pocket.contains("datasetFile()"))
    }

    @Test fun itIsWiredWhereThePayingCellIs() {
        val edge = src("engine/truth/EdgeModel8537.kt").substringAfter("fun learn(s: Snap, netPct: Double").substringBefore("fun verdict(")
        assertTrue(edge.contains("AgreementPocket8874.learn8874(s.mint, s.raw.lane, s.raw.mcap, s.raw.v3, s.pred, netPct, s.atMs)"))
        assertTrue("filed after the label passed every learning rule",
            edge.indexOf("AgreementPocket8874.learn8874(") > edge.indexOf("if (!plausible8617(s.raw.mcap, netPct, chainWitness))"))
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        val chain = adm.substringAfter("marketCell8723 = PayingCell8723.evidence8723(ts, lane, nowMs)").substringBefore("regimeCold8546 =")
        // superseded by V5.0.8875 — the LLM's proven answer band follows it; every earlier source still keeps its precedence
        assertTrue("after every earlier market-cell source",
            chain.contains("?: try { com.lifecyclebot.engine.lab.StrategyLab8755.evidence8755(ts, lane, nowMs) } catch (_: Throwable) { null }\n" +
                "                // V5.0.8874 — then the lane's proven agreement pocket (the V3 score and the forecast agree on an off-pump.fun \$5k-\$20k coin)\n" +
                "                ?: AgreementPocket8874.evidence8874(ts, lane, edge8874?.predPct, nowMs)\n"))
        assertTrue("the forecast it reads is the candidate's own verdict", adm.contains("val edge8874 = EdgeModel8537.verdict(ts, lane, cost8153, nowMs)"))
        val lane = src("engine/truth/LaneAuthority8577.kt").substringAfter("fun memeEntryRefusal8626(").substringBefore("\n    }\n")
        assertTrue(lane.contains("first8724 in CELL_ANSWERS_8724 && (pocket8874 || (try { PayingCell8723.pays8723(ts, lane, nowMs) }"))
        assertTrue("the narrower refusals are read again", lane.contains("narrowerRefusal8724(ts, lane.trim().uppercase(), nowMs) ?: return null"))
        assertTrue(com.lifecyclebot.engine.truth.LaneAuthority8577.answersPriors8874(AgreementPocket8874.KIND_8874))
        assertFalse("other market evidence keeps its 8724 standing", com.lifecyclebot.engine.truth.LaneAuthority8577.answersPriors8874("RUNNER_MODEL_8861"))
        assertFalse(com.lifecyclebot.engine.truth.LaneAuthority8577.answersPriors8874(null))
        assertTrue(adm.contains("AgreementPocket8874.noteAdmission8874(it.terminal.name, it.reason)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("AgreementPocket8874.statusLine8874()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 63. Where the score and the forecast agree, a lane admits on its own record (8874)"))
    }

    @Test fun theAdmissionIsCountedNeverActedOn() {
        AgreementPocket8874.noteAdmission8874("ADMIT", "MARKET_CELL_PAYS")
        AgreementPocket8874.noteAdmission8874("REFUSE", "SAFETY_REFUSED_8136")
        AgreementPocket8874.noteAdmission8874("REFUSE", "SAFETY_REFUSED_8136")
        val line = AgreementPocket8874.statusLine8874()
        assertTrue(line, line.contains("liveAdmission{REFUSE:SAFETY_REFUSED_8136=2,ADMIT:MARKET_CELL_PAYS=1}"))
    }
}
