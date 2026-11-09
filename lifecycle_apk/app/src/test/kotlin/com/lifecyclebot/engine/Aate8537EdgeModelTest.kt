package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.EdgeModel8537.Raw
import com.lifecyclebot.engine.truth.AdmissionEdge8180
import com.lifecyclebot.v3.scoring.LaunchBonusLiquidity8537
import com.lifecyclebot.v3.scoring.ScoreComponent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import org.json.JSONObject
import kotlin.random.Random

/**
 * V5.0.8537 — owner: "I need a lot more edge from everything". A learned expected-net scorer, trained on every judged coin's
 * forward label and judged only on predictions it made before the outcome existed, decides once its own picks have paid.
 */
class Aate8537EdgeModelTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Before fun reset() = EdgeModel8537.resetForTest8537()
    @After fun clean() = EdgeModel8537.resetForTest8537()

    private fun raw(lane: String = "SHITCOIN", liq: Double = 20_000.0, bp: Double = 60.0, src: String = "PUMP_PORTAL",
                    phase: String = "IGNITION", flags: Int = 0) =
        Raw(lane, src, liq, 80_000.0, 12.0, 30.0, 0.8, 10.0, bp, 3.0, 20.0, 60.0, 15.0, phase, 40.0, 5.0, flags = flags)

    @Test fun legacyGhostsAndUnfillableOutcomesNeverRebuildThePredictionModel() {
        fun row(fill: Boolean, horizon: Long, cap: Double, net: Double) = JSONObject()
            .put("lane", "MOONSHOT").put("fill", fill).put("horizonMs", horizon).put("mcap", cap).put("net", net)
        assertTrue("genuine confirmed 10x low-cap launches remain learnable",
            EdgeModel8537.replayAccept8642(row(true, 900_000, 15_000.0, 1_000.0)))
        assertTrue(EdgeModel8537.replayAccept8642(row(true, 900_000, 200_000.0, -30.0)))
        assertFalse("unfillable ghosts do not teach", EdgeModel8537.replayAccept8642(row(false, 900_000, 15_000.0, 1_000_000.0)))
        assertFalse("legacy rows without horizon have no matching learning objective",
            EdgeModel8537.replayAccept8642(row(true, 0, 15_000.0, 40.0)))
        assertFalse("wrong horizon cannot distort a long-hold strategy",
            EdgeModel8537.replayAccept8642(row(true, 300_000, 15_000.0, 40.0)))
        assertFalse("million-dollar-cap million-percent seed ghosts are rejected",
            EdgeModel8537.replayAccept8642(row(true, 900_000, 2_960_000.0, 1_406_280_000.0)))
    }

    @Test fun a_five_to_twelve_x_runner_at_a_real_market_cap_must_not_be_deleted_from_learning() {
        assertTrue("5x net is possible at a $200k initial cap", EdgeModel8537.plausible8617(200_000.0, 500.0))
        assertTrue("the old 100% ceiling erased 11x winners at $2m", EdgeModel8537.plausible8617(2_000_000.0, 1_100.0))
        assertFalse("an extreme unsupported jump still needs source proof", EdgeModel8537.plausible8617(2_000_000.0, 3_000.0))
        assertTrue("a same-venue chain witness can validate an extraordinary runner", EdgeModel8537.plausible8617(2_000_000.0, 3_000.0, true))
        assertFalse("a billion-percent wrong-basis mark is never valid", EdgeModel8537.plausible8617(2_956_010.0, 1_406_280_000.0, true))
        val r = raw(lane = "MOONSHOT")
        val s = EdgeModel8537.Snap(r, EdgeModel8537.vectorOf(r), Double.NaN, "COLD", 10_000_000L, "ValidRunnerPump", false,
            900_000L)
        val witnessed = JSONObject(EdgeModel8537.datasetLine(s, 3_000.0, witnessed = true))
        assertTrue(witnessed.getBoolean("witness"))
        assertTrue("the new epoch can replay independently witnessed runners", EdgeModel8537.replayAccept8642(witnessed))
        // At this snapshot's actual $80k cap a 30x result is within the conservative 50x bound.
        // An unverified 30x at a $2m cap is not: it needs the independent price witness above.
        val unwitnessedLargeCap = JSONObject(EdgeModel8537.datasetLine(s, 3_000.0)).put("mcap", 2_000_000.0)
        assertFalse("unwitnessed large-cap mark cannot replay as a runner", EdgeModel8537.replayAccept8642(unwitnessedLargeCap))
    }

    @Test fun bins_and_vector_layout() {
        assertEquals(0, EdgeModel8537.binOf(Double.NaN, EdgeModel8537.LIQ_EDGES))
        assertEquals(1, EdgeModel8537.binOf(167.0, EdgeModel8537.LIQ_EDGES))
        assertEquals(EdgeModel8537.LIQ_EDGES.size + 1, EdgeModel8537.binOf(1e9, EdgeModel8537.LIQ_EDGES))
        assertEquals(1, EdgeModel8537.sourceBucket("pump_portal,DEX_TRENDING"))
        assertEquals(EdgeModel8537.SOURCES.size, EdgeModel8537.sourceBucket("SOMETHING_ELSE"))
        val x = EdgeModel8537.vectorOf(raw(flags = EdgeModel8537.flagsOf(true, false, true)))
        assertEquals(EdgeModel8537.DIM, x.size)
        assertEquals(1.0, x[0], 0.0)
        // one hot per block: bias + 15 bins + phase + source + lane + 2 flags, plus the cost and V3 numerics
        assertEquals(1 + 15 + 3 + 2, x.count { it == 1.0 })
        assertEquals(5, EdgeModel8537.flagsOf(true, false, true))
        assertEquals(EdgeModel8537.FLAGS.size, 7)
        assertEquals(EdgeModel8537.LANES.size, 12)
        assertEquals(EdgeModel8537.PHASES.size, 4)
    }

    @Test fun a_cold_model_predicts_nothing_and_decides_nothing() {
        val (p, key) = EdgeModel8537.predictRawForTest8537(raw())
        assertTrue(p.isNaN()); assertEquals("COLD", key)
        assertEquals(EdgeModel8537.Call.NONE, EdgeModel8537.callOf(null))
        assertEquals(EdgeModel8537.Call.NONE, EdgeModel8537.callOf(verdict(-5.0, authority = false)))
    }

    @Test fun it_learns_which_coins_pay_and_earns_authority_only_out_of_sample() {
        val rnd = Random(8537)
        repeat(EdgeModel8537.WARM_N - 1) { EdgeModel8537.learnRawForTest8537(raw(liq = 5_000.0, bp = 50.0), -6.0 + rnd.nextDouble(-3.0, 3.0)) }
        assertNull("nothing is scored out of sample while the model is cold", EdgeModel8537.oosForTest8537("SHITCOIN"))
        repeat(1_500) {
            val good = rnd.nextBoolean()
            val r = if (good) raw(liq = 100_000.0, bp = 75.0) else raw(liq = listOf(800.0, 5_000.0).random(rnd), bp = 50.0)
            val y = if (good) 9.0 + rnd.nextDouble(-4.0, 4.0) else -7.0 + rnd.nextDouble(-4.0, 4.0)
            EdgeModel8537.learnRawForTest8537(r, y)
        }
        val (pGood, key) = EdgeModel8537.predictRawForTest8537(raw(liq = 100_000.0, bp = 75.0))
        val (pBad, _) = EdgeModel8537.predictRawForTest8537(raw(liq = 800.0, bp = 50.0))
        assertEquals("the lane has enough labels for its own model (${EdgeModel8537.LANE_OWN_MIN_N}+)", "SHITCOIN", key)
        assertTrue("good bin predicted to pay: $pGood", pGood >= EdgeModel8537.MIN_EDGE_PCT)
        assertTrue("bad bin predicted to lose: $pBad", pBad < 0.0)
        val o = EdgeModel8537.oosForTest8537("SHITCOIN")!!
        assertTrue(o.selN >= EdgeModel8537.AUTH_MIN_N)
        assertTrue(EdgeModel8537.authority(o.selN, o.selMean, o.selSe, o.allMean))
        assertTrue(o.negMean < 0.0)
        assertTrue(EdgeModel8537.statusLine().contains("SHITCOIN[") && EdgeModel8537.statusLine().contains("AUTHORITY"))
    }

    @Test fun authority_needs_thirty_picks_that_paid_past_one_se_and_beat_the_lane() {
        assertFalse("thin", EdgeModel8537.authority(EdgeModel8537.AUTH_MIN_N - 1, 5.0, 1.0, -3.0))
        assertFalse("inside one SE", EdgeModel8537.authority(40, 1.0, 1.5, -3.0))
        assertFalse("no better than buying everything", EdgeModel8537.authority(40, 3.0, 1.0, 4.0))
        assertTrue(EdgeModel8537.authority(40, 3.0, 1.0, -3.0))
    }

    @Test fun runnerOutcomesAreGradedInNetPercent() {
        val tally = EdgeModel8537.Oos()
        val pick = EdgeModel8537.target8567(1.004)
        repeat(9) { tally.add(pick, -20.0) }
        tally.add(pick, 300.0)
        assertEquals(10, tally.selN)
        assertEquals(12.0, tally.selMean, 1e-9)
    }

    @Test fun noise_earns_no_authority() {
        val rnd = Random(1)
        repeat(2_500) { EdgeModel8537.learnRawForTest8537(raw(liq = listOf(800.0, 5_000.0, 100_000.0).random(rnd), bp = rnd.nextDouble(30.0, 90.0)), -4.0 + rnd.nextDouble(-8.0, 8.0)) }
        val o = EdgeModel8537.oosForTest8537("SHITCOIN")
        assertFalse(o != null && EdgeModel8537.authority(o.selN, o.selMean, o.selSe, o.allMean))
    }

    @Test fun the_call_reads_authority_and_sign() {
        assertEquals(EdgeModel8537.Call.NEGATIVE, EdgeModel8537.callOf(verdict(-0.5)))
        assertEquals(EdgeModel8537.Call.NONE, EdgeModel8537.callOf(verdict(0.5)))
        assertEquals(EdgeModel8537.Call.POSITIVE, EdgeModel8537.callOf(verdict(EdgeModel8537.MIN_EDGE_PCT)))
        assertEquals(EdgeModel8537.Call.NONE, EdgeModel8537.callOf(verdict(Double.NaN)))
    }

    private fun verdict(p: Double, authority: Boolean = true) =
        EdgeModel8537.Verdict(p, authority, "SHITCOIN", 900L, 40, 3.0, 1.0, -4.0)

    private val base = CandidateAdmission8136.Candidate8136("m8537", "SHITCOIN", null,
        expectedGrossPct = 20.0, costPct = 4.5, genericScore = 80.0, genericFloor = 15.0)

    @Test fun an_authoritative_negative_refuses_and_a_positive_admits_as_proven() {
        assertEquals("GENERIC_PRIOR_CLEARS_8136", CandidateAdmission8136.decide8136(base).reason)
        val neg = CandidateAdmission8136.decide8136(base.copy(edge8537 = verdict(-3.0)))
        assertEquals(CandidateAdmission8136.Terminal8136.REFUSE, neg.terminal)
        assertEquals(CandidateAdmission8136.EDGE_MODEL_NEGATIVE_8537, neg.reason)
        assertTrue(neg.specific)
        assertEquals("without authority the model decides nothing", "GENERIC_PRIOR_CLEARS_8136",
            CandidateAdmission8136.decide8136(base.copy(edge8537 = verdict(-3.0, authority = false))).reason)
        val pos = CandidateAdmission8136.decide8136(base.copy(genericScore = 5.0, edge8537 = verdict(4.0)))
        assertEquals(CandidateAdmission8136.Terminal8136.ADMIT, pos.terminal)
        assertEquals(CandidateAdmission8136.EDGE_MODEL_PROVEN_8537, pos.reason)
        assertTrue(pos.specific)
        assertTrue("a proven model pick carries its edge downstream", AdmissionEdge8180.carriesEdge8180(pos))
        // live: the native lane opinion no longer reaches money, but an authoritative pick does
        val live = CandidateAdmission8136.decide8136(base.copy(live = true, edge8537 = verdict(4.0)))
        assertEquals(CandidateAdmission8136.EDGE_MODEL_PROVEN_8537, live.reason)
    }

    @Test fun a_supported_record_keeps_a_mild_negative_but_not_a_loss_past_the_round_trip() {
        val supported = Evidence8136(CandidateAdmission8136.KIND_SETUP, "PULLBACK", 60, 9.0, 1.0)
        val c = base.copy(selectedSetup = "PULLBACK", setups = listOf(supported))
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", CandidateAdmission8136.decide8136(c.copy(edge8537 = verdict(-2.0))).reason)
        assertEquals(CandidateAdmission8136.EDGE_MODEL_NEGATIVE_8537,
            CandidateAdmission8136.decide8136(c.copy(edge8537 = verdict(-6.0))).reason)
    }

    @Test fun wiring_labeler_candidate_live_proof_brief_and_export() {
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(lab.contains("it.edge8537 = EdgeModel8537.snapshot(ts, l, cost.coerceIn(0.0, 60.0), admitted, nowMs)"))
        assertTrue(lab.contains("o.edge8537?.let { o.edge8537 = null; EdgeModel8537.learn(it, net, witness) }"))
        assertTrue(lab.contains("EdgeModel8537.saveNow()"))
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(adm.contains("edge8537 = EdgeModel8537.verdict(ts, lane, cost8153, nowMs),"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("admission.reason == CandidateAdmission8136.EDGE_MODEL_PROVEN_8537"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("EdgeModel8537.statusLine()"))
        assertTrue(src("ui/ReportCopyDialog8198.kt").contains("EdgeModel8537.datasetFile()"))
    }

    @Test fun dataset_row_and_offline_replay_share_the_layout() {
        // superseded (8567): the model holds log returns; the row prints the net % they mean (2.5 % = target8567(2.5))
        val s = EdgeModel8537.Snap(raw(), EdgeModel8537.vectorOf(raw()), EdgeModel8537.target8567(2.5), "GLOBAL", 1_700_000_000_000L, "MintX", true)
        val line = EdgeModel8537.datasetLine(s, -4.25)
        // superseded by V5.0.8628 — each row carries its label horizon after the time: was
        // `line.startsWith("{\"t\":1700000000000,\"mint\":\"MintX\",\"lane\":\"SHITCOIN\"")`.
        assertTrue(line.startsWith("{\"t\":1700000000000,\"horizonMs\":${s.horizonMs},\"mint\":\"MintX\",\"lane\":\"SHITCOIN\""))
        assertTrue(line.contains("\"liq\":20000.0,") || line.contains("\"liq\":20000,"))
        assertTrue(line.contains("\"pred\":2.50000") && line.contains("\"net\":-4.25000") && line.contains("\"flags\":0"))
        val py = File("../ci/edge_replay_8537.py").readText()
        assertTrue(py.contains("LIQ_EDGES = [3000.0, 10000.0, 50000.0, 250000.0]"))
        assertTrue(py.contains("TURNOVER_EDGES = [0.1, 0.5, 2.0, 6.0]"))
        assertTrue(py.contains("N_FLAGS = ${EdgeModel8537.FLAGS.size}"))
        assertEquals(listOf(3_000.0, 10_000.0, 50_000.0, 250_000.0), EdgeModel8537.LIQ_EDGES.toList())
        assertEquals(listOf(0.1, 0.5, 2.0, 6.0), EdgeModel8537.TURNOVER_EDGES.toList())
        for (e in listOf(EdgeModel8537.MCAP_EDGES, EdgeModel8537.AGE_EDGES, EdgeModel8537.RUNUP_EDGES, EdgeModel8537.PEAK_EDGES,
                EdgeModel8537.DD_EDGES, EdgeModel8537.BP_EDGES, EdgeModel8537.CHG5_EDGES, EdgeModel8537.CHG1H_EDGES,
                EdgeModel8537.VOLACC_EDGES, EdgeModel8537.TOP_EDGES, EdgeModel8537.HOLDERVEL_EDGES, EdgeModel8537.TXVEL_EDGES,
                EdgeModel8537.CURVE_EDGES))
            assertTrue(py.contains(e.joinToString(", ", "[", "]")))
        assertEquals(EdgeModel8537.LANES.joinToString("\", \"", "\"", "\""),
            py.substringAfter("LANES = [").substringBefore("]").replace("\n", "").replace(Regex("\\s+"), " ").trim().trimEnd(','))
    }

    @Test fun the_fresh_launch_bonus_shrinks_with_the_pool() {
        val ign = ScoreComponent("fresh_launch_bonus", 35, "IGNITION")
        assertEquals("$167 of liquidity keeps none of it", 0, LaunchBonusLiquidity8537.scaled8537(ign, 167.0)!!.value)
        assertEquals(12, LaunchBonusLiquidity8537.scaled8537(ign, 2_000.0)!!.value)
        assertEquals(23, LaunchBonusLiquidity8537.scaled8537(ign, 6_000.0)!!.value)
        assertEquals(35, LaunchBonusLiquidity8537.scaled8537(ign, LaunchBonusLiquidity8537.FULL_USD)!!.value)
        assertEquals("unread pool keeps half", 0.5, LaunchBonusLiquidity8537.scale8537(Double.NaN), 0.0)
        assertEquals(0.0, LaunchBonusLiquidity8537.scale8537(LaunchBonusLiquidity8537.THIN_USD - 1.0), 0.0)
        assertEquals(1.0 / 3.0, LaunchBonusLiquidity8537.scale8537(LaunchBonusLiquidity8537.SMALL_USD - 1.0), 1e-12)
        val fade = ScoreComponent("fresh_launch_bonus", -25, "POST_PUMP_FADE")
        assertEquals("a penalty is never scaled", fade, LaunchBonusLiquidity8537.scaled8537(fade, 100.0))
        assertNull(LaunchBonusLiquidity8537.scaled8537(null, 100.0))
        assertTrue(src("v3/scoring/UnifiedScorer.kt").contains("LaunchBonusLiquidity8537.scaled8537(launchTimingRaw7809(candidate), candidate.liquidityUsd)"))
    }
}
