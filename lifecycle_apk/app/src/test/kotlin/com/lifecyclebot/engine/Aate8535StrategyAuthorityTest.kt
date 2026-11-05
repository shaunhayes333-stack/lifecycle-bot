package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.cortex.SetupAuthority8535
import com.lifecyclebot.engine.cortex.SetupAuthority8535.Authority
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144
import com.lifecyclebot.engine.truth.CostReconciliation8535
import com.lifecyclebot.engine.truth.RugCheckContract8535
import com.lifecyclebot.engine.truth.RunnerFunnel8535
import com.lifecyclebot.engine.truth.ScalpStyle8535
import com.lifecyclebot.engine.truth.StrategyRank8153
import com.lifecyclebot.network.DexHostBudget8287
import com.lifecyclebot.v3.scoring.SaturationPenalty8535
import com.lifecyclebot.v3.scoring.SniperPriceBasis8535
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8535 — audit P1-01/02/03/04/05/06/08/09/11/12/13/14/15/16, P2-01/03, N-01 (the strategy-authority, polarity,
 * economics, observability and budget rows).
 */
class Aate8535StrategyAuthorityTest {

    private fun f(bp: Double = 60.0, volAccel: Double = Double.NaN, txVelocity: Double = Double.NaN, chg5m: Double = 0.0,
                  runup: Double = 20.0, pxPeak: Double = 0.99, launchPhase: String = "", age: Double = 30.0, dd: Double = 0.0) =
        LanePlaybook7907.F(age = age, runup = runup, pxPeak = pxPeak, dd = dd, bp = bp, liq = 20_000.0, mcap = 80_000.0, top = 10.0,
            chg5m = chg5m, chg1h = 10.0, holderGrowth = Double.NaN, volAccel = volAccel, planSetup = "", launchPhase = launchPhase,
            txVelocity = txVelocity)

    // ── P1-01 / P1-03 / P1-13 ──

    @Test fun a_fire_carries_authority_and_only_a_triggered_setup_is_executable() {
        // token-quality conditions never grant entry authority
        for (q in SetupAuthority8535.QUALITY_ONLY_8535) assertEquals(q, Authority.TOKEN_QUALITY, SetupAuthority8535.authorityOf8535(q, f(chg5m = 5.0, volAccel = 90.0)))
        // a near-high read needs an OBSERVED breakout witness
        assertEquals(Authority.SETUP_FORMING, SetupAuthority8535.authorityOf8535("NEW_HIGH_PUSH", f(chg5m = 1.0)))
        assertEquals(Authority.SETUP_TRIGGERED, SetupAuthority8535.authorityOf8535("NEW_HIGH_PUSH", f(chg5m = 1.0, volAccel = SetupAuthority8535.WITNESS_VOL_ACCEL_8535)))
        assertEquals(Authority.SETUP_TRIGGERED, SetupAuthority8535.authorityOf8535("BREAKOUT_RUNNER", f(txVelocity = SetupAuthority8535.WITNESS_TX_VELOCITY_8535)))
        assertEquals(Authority.SETUP_TRIGGERED, SetupAuthority8535.authorityOf8535("BREAKOUT_HOLD", f(chg5m = SetupAuthority8535.WITNESS_CHG5M_PCT_8535)))
        assertEquals("already run past the bound: no upside witness", Authority.SETUP_FORMING,
            SetupAuthority8535.authorityOf8535("BREAKOUT_RUNNER", f(volAccel = 90.0, runup = SetupAuthority8535.WITNESS_MAX_RUNUP_PCT_8535 + 1.0)))
        assertFalse(SetupAuthority8535.breakoutWitness8535(Double.NaN, 90.0, 5.0, 5.0, 10.0))   // no buyers measured = no witness
        // a default-favour fire with the buy pressure unobserved is partial evidence
        for (p in SetupAuthority8535.PARTIAL_ON_UNKNOWN_BP_8535) assertEquals(p, Authority.SETUP_FORMING, SetupAuthority8535.authorityOf8535(p, f(bp = Double.NaN)))
        assertEquals(Authority.SETUP_TRIGGERED, SetupAuthority8535.authorityOf8535("HOLDER_SURGE", f(bp = 61.0)))
        assertTrue(SetupAuthority8535.NEAR_HIGH_8535.contains("TX_VELOCITY_BREAK"))
        // ordinary entry events keep their authority
        assertTrue(SetupAuthority8535.executable8535("RECLAIM_AFTER_WEAKNESS", f()))
        assertTrue(SetupAuthority8535.executable8535("IGNITION_BREAK", f(launchPhase = "IGNITION")))
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue("the admission's strategy and fired options are triggered fires only", adm.contains("val selected = selectedRaw8535?.takeIf { triggered8535(it) }"))
        assertTrue(adm.contains("pb.strategyOptions8153(ts, lane, nowMs).filter { triggered8535(it.id) }"))
        assertTrue(adm.contains("SETUP_AUTHORITY_NOT_EXECUTABLE_8535_"))
    }

    // ── P1-02 ──

    @Test fun the_ignition_stage_has_its_own_strategies_and_the_launch_lanes_read_them() {
        val pb = File("src/main/kotlin/com/lifecyclebot/engine/cortex/LanePlaybook7907.kt").readText()
        assertTrue(pb.contains("Setup(\"IGNITION_BREAK\", FLOW)") && pb.contains("f.launchPhase == \"IGNITION\" && le(f.age, 120.0)"))
        assertTrue(pb.contains("Setup(\"POST_IGNITION_RETEST\", FLOW)"))
        assertTrue(pb.contains("val native = native8383 + launch8535(lane, native8383)"))
        for (lane in listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED")) {
            val ids = LanePlaybook7907.menuIds(lane)
            assertTrue(lane, ids.contains("IGNITION_BREAK") && ids.contains("POST_IGNITION_RETEST"))
        }
        assertTrue("LAUNCH_CONTINUATION keeps its id, predicate and bins", pb.contains("private val LAUNCH_CONTINUATION = Setup(\"LAUNCH_CONTINUATION\", PROBE) { f ->"))
        assertTrue("appended after every earlier bin", LanePlaybook7907.menuIds("MOONSHOT").indexOf("IGNITION_BREAK") > LanePlaybook7907.menuIds("MOONSHOT").indexOf("LAUNCH_CONTINUATION"))
        assertFalse("not a swing desk's native strategy", LanePlaybook7907.menuIds("BLUECHIP").contains("IGNITION_BREAK"))
    }

    // ── P1-04 / P1-05 ──

    @Test fun saturation_is_docked_in_every_lane_and_every_recovery_mode() {
        assertEquals(0, SaturationPenalty8535.moonshot8535(80.0, "PRE_IGNITION"))
        assertEquals(0, SaturationPenalty8535.moonshot8535(88.0, "PRE_IGNITION"))
        assertEquals(12, SaturationPenalty8535.moonshot8535(SaturationPenalty8535.BP_SATURATED_8535, "PRE_IGNITION"))
        assertEquals(22, SaturationPenalty8535.moonshot8535(95.0, "EXPANDING"))
        assertEquals(10, SaturationPenalty8535.moonshot8535(SaturationPenalty8535.BP_ONE_SIDED_8535, "POST_PUMP_FADE"))
        assertEquals(0, SaturationPenalty8535.moonshot8535(Double.NaN, "EXPANDING"))
        assertEquals(0, SaturationPenalty8535.scalp8535(70.0, 10.0))
        assertEquals(10, SaturationPenalty8535.scalp8535(86.0, 5.0))
        assertEquals(20, SaturationPenalty8535.scalp8535(60.0, SaturationPenalty8535.SCALP_MOMENTUM_PARABOLIC_8535))
        assertEquals(30, SaturationPenalty8535.scalp8535(90.0, SaturationPenalty8535.SCALP_MOMENTUM_LATE_8535))
        assertEquals(0, SaturationPenalty8535.treasury8535(70.0, 75.0))
        assertEquals(10, SaturationPenalty8535.treasury8535(91.0, 50.0))
        assertEquals(18, SaturationPenalty8535.treasury8535(86.0, 92.0))
        val moon = File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        assertTrue(moon.contains("val saturation8535 = SaturationPenalty8535.moonshot8535(buyPressurePct, phase)") && moon.contains("score -= saturation8535"))
        assertTrue("a crowded tape earns no more than healthy demand", moon.contains("buyPressurePct > 55 -> 10") && !moon.contains("buyPressurePct > 75 -> 18")) // superseded by V5.0.8555 §LANE_SWEEP: 5 of 6 MOONSHOT live closes bought the top
        val cash = File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        assertTrue(cash.contains("val overheat8535 = SaturationPenalty8535.scalp8535(buyPressurePct, momentum)"))
        assertTrue("the AGGRESSIVE anti-FOMO clamp stays", cash.contains("wr_recovery_anti_fomo=buyPressure="))
        val tb = File("src/main/kotlin/com/lifecyclebot/engine/TreasuryBrain.kt").readText()
        assertTrue(tb.contains("SaturationPenalty8535.treasury8535(press, mom)"))
        val man = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ManipulatedTraderAI.kt").readText()
        assertTrue("MANIPULATED keeps its own exhaustion signature", man.contains("if (momentum >= 15.0 && buyPressurePct >= 85.0) {"))
    }

    // ── P1-06 ──

    @Test fun the_scalp_tradability_figure_follows_the_strategys_style() {
        assertEquals(ScalpStyle8535.Style.RANGE, ScalpStyle8535.of8535("RANGE_LOW_BOUNCE"))
        assertEquals(ScalpStyle8535.Style.RECLAIM, ScalpStyle8535.of8535("RECLAIM_AFTER_WEAKNESS"))
        assertEquals(ScalpStyle8535.Style.MOMENTUM, ScalpStyle8535.of8535("IGNITION_5M"))
        assertEquals("unknown = the stricter test", ScalpStyle8535.Style.MOMENTUM, ScalpStyle8535.of8535(null))
        assertEquals(4.0, ScalpStyle8535.tradabilityPct8535(ScalpStyle8535.Style.MOMENTUM, 4.0, 15.0)!!, 0.0)
        assertEquals(15.0, ScalpStyle8535.tradabilityPct8535(ScalpStyle8535.Style.RANGE, -2.0, 15.0)!!, 0.0)
        assertNull("a sparse range is unobserved, not flat", ScalpStyle8535.tradabilityPct8535(ScalpStyle8535.Style.RECLAIM, -2.0, null))
        val ne = File("src/main/kotlin/com/lifecyclebot/engine/truth/NetEdge8030.kt").readText()
        assertTrue(ne.contains("ScalpStyle8535.tradabilityPct8535(style8535, realizedMovePct8035(ts), denseRangePct8535(ts))"))
        assertTrue("the 3x bar itself is unchanged", ne.contains("private const val SCALP_EDGE_MULT_8054 = 3.0"))
    }

    // ── P1-08 ──

    private fun c(ts: Long, px: Double, synthetic: Boolean = false) =
        Candle(ts = ts, priceUsd = px, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0, synthetic = synthetic)

    @Test fun the_sniper_price_basis_is_the_earliest_real_print_or_unknown_by_name() {
        val shuffled = listOf(c(3_000L, 3.0), c(1_000L, 1.0), c(2_000L, 2.0), c(500L, 0.5, synthetic = true), c(0L, 0.1))
        assertEquals(1.0, SniperPriceBasis8535.firstTradablePrice8535(shuffled)!!, 0.0)
        assertEquals(200.0, SniperPriceBasis8535.changeFromFirstPct8535(shuffled, 3.0)!!, 1e-9)
        assertNull(SniperPriceBasis8535.firstTradablePrice8535(emptyList()))
        assertNull("a synthetic-only history has no basis", SniperPriceBasis8535.firstTradablePrice8535(listOf(c(1_000L, 1.0, synthetic = true))))
        assertNull(SniperPriceBasis8535.changeFromFirstPct8535(shuffled, 0.0))
        val ps = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ProjectSniperAI.kt").readText()
        assertTrue(ps.contains("?: return noEngage(\"\${SniperPriceBasis8535.UNKNOWN_8535}: no dated first print\", tokenAgeSecs, ThreatLevel.YELLOW)"))
        assertFalse(ps.contains("ts.history.firstOrNull()?.priceUsd ?: currentPrice"))
    }

    // ── P1-09 ──

    @Test fun core_and_cyclic_ready_must_name_a_fired_strategy() {
        assertTrue(SetupAuthority8535.requiresNamedSetup8535("cyclic") && SetupAuthority8535.requiresNamedSetup8535("CORE"))
        assertFalse(SetupAuthority8535.requiresNamedSetup8535("SHITCOIN"))
        assertEquals(SetupAuthority8535.NAMED_SETUP_LANES_8535, setOf("CORE", "CYCLIC"))
        val br = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        assertTrue(br.contains("reason = \"\${lane}_NO_NAMED_SETUP_8535:\" + opinion.reason.take(60)"))
        assertTrue("CORE may inherit the fired setup of the specialist it ensembles", br.contains("it.lane != \"CORE\" && it.authoritative && it.eligible && it.firedSetup8535 != null"))
    }

    // ── P1-11 ──

    @Test fun model_and_measured_costs_reconcile_with_units_and_a_trip_count() {
        val rows = listOf(CostReconciliation8535.Row("BLUECHIP", 4.42, 10.89, 7), CostReconciliation8535.Row("EXPRESS", 4.42, 14.53, 2), CostReconciliation8535.Row("QUALITY", 4.43, null, 0))
        val line = CostReconciliation8535.line8535(rows)
        assertTrue(line, line.contains("BLUECHIP:model=4.42%/measured=10.89%(n7)/gap=+6.47"))
        assertTrue("two trips are not a gap", line.contains("EXPRESS:model=4.42%/measured=14.53%(n2)/gap=n<${CostReconciliation8535.MIN_TRIPS_8535}"))
        assertTrue(line.contains("QUALITY:model=4.43%/measured=-%(n0)/gap=-"))
        assertTrue(line.contains("unit=pct_of_ticket") && line.contains("cost=model=all_in;measured=realised"))
        val costs = File("src/main/kotlin/com/lifecyclebot/engine/Costs8369.kt").readText()
        assertTrue(costs.contains("CostLedger7962.measuredTrips8535(e.key)"))
    }

    // ── P1-12 ──

    private fun trace(held: Boolean = false, detectedAt: Long = 1_000L, firstSeen: Long = 1_000L, nativeAt: Long = 1_000L, fdg: String = "",
                      downstream: String = "", qualified: String = "SHITCOIN", binding: String = "", peak: Double = 300.0) =
        AdmissionRunnerTrace8144.Trace8144("MintXYZ", "XYZ", "SHITCOIN", peak, detectedAt, firstSeen, "PUMP", "KNOWN", "CHAIN", 60_000L, 20_000.0,
            nativeAt, "SHITCOIN:Y:70:ok", qualified, "IGNITION_5M", "ADMIT:SPECIFIC_EDGE_PROVEN_8136", "SHITCOIN", fdg, "", "", downstream,
            0.001, 2_000L, 4.4, true, "CURVE", held, binding = binding)

    @Test fun the_runner_funnel_names_where_each_opportunity_was_lost_and_what_was_bankable() {
        val never = trace(detectedAt = 0L, nativeAt = 0L)
        assertEquals(RunnerFunnel8535.Bucket.NEVER_DETECTED, RunnerFunnel8535.bucketOf8535(never, AdmissionRunnerTrace8144.classify8144(never)))
        val noBuy = trace(fdg = "ALLOW")
        assertEquals(RunnerFunnel8535.Bucket.NO_BUY_ATTEMPT, RunnerFunnel8535.bucketOf8535(noBuy, AdmissionRunnerTrace8144.classify8144(noBuy)))
        val soldEarly = trace(held = true, downstream = "FILLED@+12 SOLD@+30")
        assertEquals(RunnerFunnel8535.Bucket.SOLD_PREMATURELY, RunnerFunnel8535.bucketOf8535(soldEarly, AdmissionRunnerTrace8144.classify8144(soldEarly)))
        val filledLate = trace(held = true, downstream = "FILLED@+140 SOLD@+250")
        assertEquals(RunnerFunnel8535.Bucket.FILLED_LATE, RunnerFunnel8535.bucketOf8535(filledLate, AdmissionRunnerTrace8144.classify8144(filledLate)))
        val captured = trace(held = true, downstream = "FILLED@+10 SOLD@+180")
        assertEquals(RunnerFunnel8535.Bucket.CAPTURED, RunnerFunnel8535.bucketOf8535(captured, AdmissionRunnerTrace8144.classify8144(captured)))
        val elected = trace(binding = "ELECTION_SHADOWED:CORE")
        assertEquals(RunnerFunnel8535.Bucket.ELECTED_OUT, RunnerFunnel8535.bucketOf8535(elected, AdmissionRunnerTrace8144.classify8144(elected)))
        val late = trace(detectedAt = 1_000L + RunnerFunnel8535.LATE_MS_8535 + 1L, nativeAt = 0L, qualified = "")
        assertEquals("detected after the run was on", RunnerFunnel8535.Bucket.DETECTED_LATE,
            RunnerFunnel8535.bucketOf8535(late.copy(admission = "", fdgVerdict = ""), AdmissionRunnerTrace8144.Verdict8144(AdmissionRunnerTrace8144.Miss8144.NOT_EVALUATED, "")))
        assertEquals(100.0, RunnerFunnel8535.FILLED_LATE_PCT_8535, 0.0); assertEquals(0.25, RunnerFunnel8535.SOLD_EARLY_SHARE_8535, 0.0)
        assertEquals(30.0, RunnerFunnel8535.pctToken8535("FILLED@+12 SOLD@+30", "SOLD"), 0.0)
        assertTrue(RunnerFunnel8535.pctToken8535("FILLED@+12", "SOLD").isNaN())
        // bankable: a +300 % peak on the 25/25/25 + trail-30 ladder, net of 4.4 %
        val b = RunnerFunnel8535.bankablePct8535(300.0, 4.4)
        assertEquals(0.25 * 50 + 0.25 * 100 + 0.25 * 200 + 0.25 * ((4.0 * 0.70 - 1.0) * 100.0) - 4.4, b, 1e-9)
        assertTrue("below the first rung only the trail is banked", RunnerFunnel8535.bankablePct8535(20.0, 4.4) < 0.0)
        assertEquals(0.0, RunnerFunnel8535.bankablePct8535(Double.NaN, 4.4), 0.0)
        val line = RunnerFunnel8535.line8535(listOf(never, noBuy, soldEarly, captured))
        assertTrue(line, line.startsWith("funnel8535{never_detected=1"))
        assertTrue(line.contains("no_buy_attempt=1") && line.contains("sold_prematurely=1") && line.contains("captured=1") && line.contains("bankableMissedPctOfTicket="))
        assertTrue(AdmissionRunnerTrace8144.summary8144(listOf(noBuy)).contains("funnel8535{"))
        assertEquals("funnel8535{runners=0}", RunnerFunnel8535.line8535(emptyList()))
    }

    // ── P1-14 ──

    @Test fun one_rugcheck_contract_confirmed_rug_refuses_danger_is_graded() {
        assertEquals(RugCheckContract8535.State.CONFIRMED_RUG, RugCheckContract8535.of8535(0))
        assertEquals(RugCheckContract8535.State.PENDING, RugCheckContract8535.of8535(1))
        assertEquals(RugCheckContract8535.State.PENDING, RugCheckContract8535.of8535(-1))
        assertEquals(RugCheckContract8535.State.DANGER, RugCheckContract8535.of8535(2))
        assertEquals(RugCheckContract8535.State.DANGER, RugCheckContract8535.of8535(RugCheckContract8535.DANGER_MAX_8535))
        assertEquals(RugCheckContract8535.State.SAFE, RugCheckContract8535.of8535(15))
        assertTrue(RugCheckContract8535.refuses8535(0)); assertFalse(RugCheckContract8535.refuses8535(2)); assertFalse(RugCheckContract8535.refuses8535(-1))
        assertEquals(13, RugCheckContract8535.penalty8535(2)); assertEquals(1, RugCheckContract8535.penalty8535(14)); assertEquals(0, RugCheckContract8535.penalty8535(15)); assertEquals(0, RugCheckContract8535.penalty8535(1))
        val moon = File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        assertTrue(moon.contains("RugCheckContract8535.refuses8535(rugcheckScore)) \"rugcheck_0_confirmed_rug\""))
        assertTrue(moon.contains("internal fun rugcheckPenalty8528(score: Int): Int = com.lifecyclebot.engine.truth.RugCheckContract8535.penalty8535(score)"))
        assertFalse("the stale comment is gone", moon.contains("the danger band 2..14 (risk 86..98) is refused in both\n        // modes again"))
        // downstream reads the same contract: FDG grades the band, the authorizer refuses only the confirmed rug
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("rugcheckStatus == \"CONFIRMED\" && rugcheckScore in 2..rugcheckThreshold -> {") && fdg.contains("tags.add(\"low_rc_penalty_only\")"))
        assertTrue(fdg.contains("rugcheckStatus == \"CONFIRMED\" && rugcheckScore == 0 -> true"))
        val se = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistExecution7948.kt").readText()
        assertTrue(se.contains("fun isConfirmedRugForLive7948(rugcheckScore: Int, paper: Boolean): Boolean = !paper && rugcheckScore == 0"))
    }

    // ── P1-15 ──

    @Test fun a_forming_thin_record_orders_unproven_fired_setups_without_granting_authority() {
        val forming = StrategyRank8153.Option8153("IGNITION_5M", 7, 9.0, 3.0)        // 9 - 4.5 = +4.5 > 0
        val thin = StrategyRank8153.Option8153("MICRO_FLAG", 7, 3.0, 3.0)            // 3 - 4.5 < 0
        val tooFew = StrategyRank8153.Option8153("SECOND_LEG", 4, 20.0, 1.0)
        assertTrue(StrategyRank8153.forming8535(forming, 4.0)); assertFalse(StrategyRank8153.forming8535(thin, 4.0)); assertFalse(StrategyRank8153.forming8535(tooFew, 4.0))
        assertEquals(5, StrategyRank8153.FORMING_MIN_N_8535)
        val pick = StrategyRank8153.select8153("MICRO_FLAG", listOf(forming, thin), 4.0)
        assertEquals("IGNITION_5M", pick.id)
        assertEquals("the tier stays UNPROVEN: no admission authority", StrategyRank8153.Tier8153.UNPROVEN, pick.tier)
        assertTrue(pick.basis.startsWith("FORMING_8535:"))
        assertFalse(StrategyRank8153.evidenced8153(pick.tier))
        // a VALID record still wins outright; a forming default is not displaced
        val valid = StrategyRank8153.Option8153("DIP_IN_UPTREND", 14, 9.0, 2.0)
        assertEquals("DIP_IN_UPTREND", StrategyRank8153.select8153("MICRO_FLAG", listOf(forming, valid), 4.0).id)
        assertEquals("MICRO_FLAG", StrategyRank8153.select8153("MICRO_FLAG", listOf(forming, forming.copy(id = "MICRO_FLAG", meanPct = 12.0)), 4.0).id)
    }

    // ── P1-16 / P2-01 / P2-03 ──

    @Test fun decision_episodes_join_native_admission_fdg_stages_and_labels_per_candidate() {
        DecisionEpisode8535.clearForTest8535()
        DecisionEpisode8535.noteNative8535("SHITCOIN", "MintA", 7L, 112.0, 150.0, true, "IGNITION_5M", 1_000L)
        DecisionEpisode8535.noteAdmission8535("SHITCOIN", "MintA", 7L, "ADMIT", "SPECIFIC_EDGE_PROVEN_8136", "IGNITION_5M", listOf("lane=SHITCOIN", "gross=+30"), 1_100L)
        DecisionEpisode8535.noteFdg8535("SHITCOIN", "MintA", 7L, true, null, 1_200L)
        DecisionEpisode8535.noteStage8535("SHITCOIN", "MintA", 7L, "TICKET", 1_300L)
        DecisionEpisode8535.noteStage8535("SHITCOIN", "MintA", 7L, "TICKET", 1_301L)
        DecisionEpisode8535.noteLabel8535("SHITCOIN", "MintA", "TERMINAL:late:RATE_LIMIT", 1_400L)
        assertEquals(1, DecisionEpisode8535.complete8535())
        val row = DecisionEpisode8535.recent8535(1).single()
        assertTrue(row, row.startsWith("MintA|SHITCOIN|v7 native=112/150:Y setup=IGNITION_5M admission=ADMIT:SPECIFIC_EDGE_PROVEN_8136#"))
        assertTrue(row.contains(" fdg=ALLOW stages=TICKET label=TERMINAL:late:RATE_LIMIT"))
        assertEquals(DecisionEpisode8535.hash8535(listOf("lane=SHITCOIN", "gross=+30")), row.substringAfter('#').substringBefore(' '))
        DecisionEpisode8535.noteFdg8535("CRYPTO_ALT", "coingecko:bitcoin", 3L, false, "NET_EDGE_CRYPTO_TOO_SLOW_8042", 2_000L)
        assertEquals(1, DecisionEpisode8535.complete8535())
        assertTrue(DecisionEpisode8535.reportLine8535("CRYPTO_ALT").contains("fdg=BLOCK:NET_EDGE_CRYPTO_TOO_SLOW_8042"))
        assertTrue(DecisionEpisode8535.reportLine8535("SHITCOIN").startsWith("episodes8535[open=1 complete=1"))
        // bounded ring
        repeat(DecisionEpisode8535.MAX_8535 + 5) { DecisionEpisode8535.noteStage8535("CORE", "m$it", 1L, "QUALIFIED", 3_000L + it) }
        assertTrue(DecisionEpisode8535.reportLine8535().startsWith("episodes8535[open=${DecisionEpisode8535.MAX_8535}"))
        DecisionEpisode8535.clearForTest8535()
        // producers
        val oc = File("src/main/kotlin/com/lifecyclebot/engine/OwnershipChain8364.kt").readText()
        assertTrue(oc.contains("DecisionEpisode8535.noteFdg8535(lane, mint, c.version, allow, reason, nowMs)"))
        assertTrue(oc.contains("DecisionEpisode8535.noteStage8535(lane, mint, c.version, step.name, nowMs)"))
        assertTrue("P1-16: the crypto desk's FDG refusal is counted by asset and reason", oc.contains("CRYPTO_FDG_REJECT_8535_\${mint.take(24)}_\${reasonKey8364(reason)}"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText().contains("DecisionEpisode8535.noteAdmission8535(candidate.lane, ts.mint, version, it.terminal.name, it.reason, it.chosenSetup, it.trace, nowMs)"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText().contains("DecisionEpisode8535.noteNative8535(lane, s.mint, version, o.rawScore8535.toDouble()"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText().contains("DecisionEpisode8535.noteLabel8535(o.lane, o.mint, \"TERMINAL:\$why:\$cause8535\", nowMs)"))
        // P2-03: every published metric carries its tag
        val t = MetricTag8535.LANES60_8535.line()
        assertEquals("{unit=pct_net_return cohort=forward_labels_all_decisions epoch=label_epoch_8169 horizon=60m basis=executable_mark_at_decision cost=labelCostPct8534 dedup=mint|lane}", t)
        assertEquals("lanes60$t", MetricTag8535.tagged("lanes60", MetricTag8535.LANES60_8535))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText().contains("\"      lanes60\${com.lifecyclebot.engine.MetricTag8535.LANES60_8535.line()}: "))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText().contains("nativeTag8535=\${MetricTag8535.NATIVE_COUNTERS_8535.line()} nativeCalled="))
        assertTrue(oc.contains("\${MetricTag8535.CHAIN_8535.line()}"))
        assertTrue(MetricTag8535.MODEL_VS_MARK_8535.unit == "pct_of_ticket" && MetricTag8535.NATIVE_COUNTERS_8535.cohort == "native_opinions_post_review")
    }

    // ── N-01 ──

    @Test fun a_repeated_exit_read_of_the_same_coin_is_coalesced_before_the_bucket_is_charged() {
        val p = "/tokens/v1/solana/MintHeld8535"
        val t0 = 50_000_000L
        assertTrue(DexHostBudget8287.exitPathAllowed8535(p, t0))
        val before = DexHostBudget8287.exitCoalesced8535()
        assertFalse(DexHostBudget8287.exitPathAllowed8535(p, t0 + 500L))
        assertFalse(DexHostBudget8287.exitPathAllowed8535(p, t0 + DexHostBudget8287.EXIT_PATH_MIN_GAP_MS_8535 - 1L))
        assertEquals(before + 2L, DexHostBudget8287.exitCoalesced8535())
        assertTrue("another coin is not held back", DexHostBudget8287.exitPathAllowed8535("/tokens/v1/solana/Other8535", t0 + 500L))
        assertTrue("the gap passed", DexHostBudget8287.exitPathAllowed8535(p, t0 + DexHostBudget8287.EXIT_PATH_MIN_GAP_MS_8535))
        assertTrue(DexHostBudget8287.statusLine8287(t0).contains("exitCoalesced8535="))
        val src = File("src/main/kotlin/com/lifecyclebot/network/DexHostBudget8287.kt").readText()
        assertTrue("coalesced before take8287 charges the bucket", src.indexOf("DexHostBudget8287.exitPathAllowed8535(req.url.encodedPath") < src.indexOf("val wait = DexHostBudget8287.take8287(System.currentTimeMillis(), prio)"))
        assertTrue(src.contains("\"DEX_EXIT_PATH_COALESCED_8535\""))
    }
}
