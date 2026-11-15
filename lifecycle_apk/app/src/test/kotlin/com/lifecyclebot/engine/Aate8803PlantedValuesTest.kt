package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.MemeMeta7973
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.PrintWitness8750
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8803 — owner: "phantom price issue bug. what else like that is in the data". An audit of the 5.0.8797 dataset
 * (56,583 rows, 6,526 coins) for values the bot INFERRED and stored as facts:
 *  - curve progress was the market cap inverted, so a wrong cap made a wrong curve (580 rows "graduated" under 5 minutes
 *    old; one coin 100 % -> 57 % -> 0 % in 20 s) — now read from the curve itself;
 *  - 2,081 rows held a cap below a live curve's opening price (as low as $212) — the cap fact now obeys curve physics;
 *  - 8.0 % of labels (4,553) had an exit mark equal to the entry mark, unwitnessed: exactly -cost, a feed that did not
 *    move — no learner takes them;
 *  - 47 % of rows read v3 = 0 — the unscored default — now unknown.
 */
class Aate8803PlantedValuesTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun curveProgressIsTheCurvesOwnNeverTheCapInverted() {
        assertEquals(1.0, MemeMeta7973.curveProgressOf8803(true, 40.0), 0.0)
        assertEquals(0.5, MemeMeta7973.curveProgressOf8803(false, 42.5), 1e-12)
        assertEquals(1.0, MemeMeta7973.curveProgressOf8803(false, 90.0), 0.0)
        assertTrue("no curve reading, no graduation: unknown", MemeMeta7973.curveProgressOf8803(false, null).isNaN())
        assertTrue(MemeMeta7973.curveProgress8803("So11111111111111111111111111111111111111112").isNaN())   // not a pump coin
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("curve7978 = try { com.lifecyclebot.engine.market.MemeMeta7973.curveProgress8803(ts.mint, nowMs) }"))
        assertFalse(src("engine/cortex/LanePlaybook7907.kt").contains("curveProgress7973(ts.lastMcap"))
        assertTrue(src("engine/truth/SpecialistMiner7972.kt").contains("meta.curveProgress8803(ts.mint)"))
    }

    @Test fun aLiveCurveCannotHoldACapBelowItsOpeningPrice() {
        val sol = 73.0
        assertFalse("$212 is 2.9 SOL of cap", PrintWitness8750.curveCapOk8803(212.0, sol, true))
        assertTrue(PrintWitness8750.curveCapOk8803(2_200.0, sol, true))                      // the launch price, ~30 SOL
        assertTrue(PrintWitness8750.curveCapOk8803(30_000.0, sol, true))                     // near graduation
        assertFalse(PrintWitness8750.curveCapOk8803(200_000.0, sol, true))                   // 2,740 SOL: no curve holds it
        assertTrue("off the curve a dump below the opening price is real", PrintWitness8750.curveCapOk8803(212.0, sol, false))
        assertTrue("no SOL price: no judgement", PrintWitness8750.curveCapOk8803(212.0, 0.0, true))
        assertTrue(src("engine/truth/TokenFacts.kt").contains("count(refused, \"\${field.name}:CURVE_PHYSICS_8803\"); return false"))
    }

    @Test fun aLabelThatSawNoNewPriceIsNotAnOutcome() {
        assertTrue(EdgeModel8537.noNewPrice8803(-2.7453, 2.7453, false))
        assertFalse("a witnessed flat outcome stands", EdgeModel8537.noNewPrice8803(-2.7453, 2.7453, true))
        assertFalse(EdgeModel8537.noNewPrice8803(-2.70, 2.7453, false))
        assertFalse(EdgeModel8537.noNewPrice8803(Double.NaN, 2.7453, false))
        val horizon = com.lifecyclebot.engine.truth.PocketLedger8610.targetHorizon("CORE")
        fun row(net: Double, witness: Boolean) = org.json.JSONObject("""{"t":1,"mint":"AbcPUMPpump","lane":"CORE","fill":true,
            "horizonMs":$horizon,"mcap":40000,"cost":2.7453,"net":$net,"witness":$witness}""")
        assertFalse(EdgeModel8537.replayAccept8642(row(-2.7453, false)))
        assertTrue(EdgeModel8537.replayAccept8642(row(4.2, false)))
        val edge = src("engine/truth/EdgeModel8537.kt")
        assertTrue(edge.contains("if (noNewPrice8803(netPct, s.raw.costPct, chainWitness)) {"))
        assertTrue(edge.contains("ts.entryScore.takeIf { it != 0.0 }"))
    }

    @Test fun theJournalSnapshotTestIsHermetic() {
        val t = File("src/test/kotlin/com/lifecyclebot/engine/Aate6619JournalDerivedHeroCoverageTest.kt").readText()
        val body = t.substringAfter("fun aate6619_authority_snapshot_reports_journal_replay_source()")
        assertTrue(body.indexOf("JournalEconomicReplay6619.resetForTest()") in 0 until body.indexOf("ledger.initialize(11.7647)"))
    }
}
