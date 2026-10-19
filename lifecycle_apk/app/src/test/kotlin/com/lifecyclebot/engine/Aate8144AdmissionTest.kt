package com.lifecyclebot.engine

import com.lifecyclebot.engine.LaneAgeLiquidityScope8140.Outcome8140
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.AdmissionBirth8144.Kind8144
import com.lifecyclebot.engine.truth.AdmissionBirth8144.Window8144
import com.lifecyclebot.engine.truth.AdmissionBirth8144.Witness8144
import com.lifecyclebot.engine.truth.AdmissionHandoff8144
import com.lifecyclebot.engine.truth.AdmissionHandoff8144.LaneRead8144
import com.lifecyclebot.engine.truth.AdmissionHandoff8144.State8144
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144.Miss8144
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144.Trace8144
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.TokenBirthHydrator7441
import com.lifecyclebot.engine.truth.TokenBirthHydrator7441.SigRow8144
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8144 — PROJECT_SNIPER and live runner admission: birth from every witness (create frame, pump.fun coin record,
 * oldest signature, pair creation) with unknown kept apart from old, hand-offs that count only when the receiver has
 * judged, missing liquidity that defers, proven alternative setups weighed before a default setup's adverse timing,
 * coalesced missing-data re-evaluation, and the runner decision-trace replay classifier. Pure functions, explicit clocks.
 */
class Aate8144AdmissionTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val t0 = 1_800_000_000_000L

    // ── birth: every witness, unknown is not old ─────────────────────────────

    @Test fun birthResolvesFromCreateFrameOldestSignatureOrPairCreation() {
        val b = AdmissionBirth8144
        val frame = b.read8144(Witness8144(createFrameMs = t0 - 40_000L), t0)
        assertEquals(Kind8144.KNOWN, frame.kind)
        assertEquals("CREATE_FRAME", frame.source)
        assertEquals(40_000L, frame.minAgeMs)
        assertEquals(t0 - 40_000L, frame.birthMs)
        val sig = b.read8144(Witness8144(oldestSignatureMs = t0 - 90_000L), t0)
        assertEquals(Kind8144.KNOWN, sig.kind)
        assertEquals("OLDEST_SIGNATURE", sig.source)
        assertEquals(90_000L, sig.minAgeMs)
        val pair = b.read8144(Witness8144(pairCreatedMs = t0 - 3L * 3_600_000L), t0)
        assertEquals("PAIR_CREATION", pair.source)
        assertEquals(Kind8144.KNOWN, b.read8144(Witness8144(pumpRecordMs = t0 - 41_000L), t0).kind)
        // Several witnesses: the earliest is the birth; on a tie the create frame (first listed) is credited.
        val all = b.read8144(Witness8144(createFrameMs = t0 - 40_000L, pumpRecordMs = t0 - 40_000L, oldestSignatureMs = t0 - 39_000L), t0)
        assertEquals("CREATE_FRAME", all.source)
        assertEquals(40_000L, all.minAgeMs)
        // A direct witness beats any lower bound.
        assertEquals(Kind8144.KNOWN, b.read8144(Witness8144(createFrameMs = t0 - 40_000L, historyFloorMs = t0 - 900_000L), t0).kind)
        // pump.fun's created_timestamp comes in seconds or ms; implausible / future times are no witness.
        assertEquals(1_700_000_000_000L, b.epochMs8144(1_700_000_000L))
        assertEquals(1_700_000_000_123L, b.epochMs8144(1_700_000_000_123L))
        assertEquals(0L, b.epochMs8144(0L))
        assertEquals(0L, b.epochMs8144(-5L))
        assertEquals(Kind8144.UNKNOWN, b.read8144(Witness8144(createFrameMs = 5L), t0).kind)
        assertEquals(Kind8144.UNKNOWN, b.read8144(Witness8144(createFrameMs = t0 + 120_000L), t0).kind)
        // Wiring: every create frame (before the throttle) and the pump.fun coin record reach the canonical resolver.
        assertTrue(src("network/PumpFunWS.kt").contains("AdmissionBirth8144.noteCreateFrame8144(mint)"))
        assertTrue(src("engine/truth/CanonicalTokenBirthTime7440.kt").contains("AdmissionBirth8144.pumpCreateMs8144(m)"))
        assertTrue(src("network/ParallelMarkFanout7088.kt").contains("AdmissionBirth8144.notePumpCoinRecord8144(mint, JSONObject(body).optLong(\"created_timestamp\", 0L))"))
    }

    @Test fun unknownBirthDefersAndIsNeverReadAsOldOrFresh() {
        val b = AdmissionBirth8144
        val unknown = b.read8144(Witness8144(), t0)
        assertEquals(Kind8144.UNKNOWN, unknown.kind)
        assertEquals(Window8144.UNKNOWN_DEFER, b.window8144(unknown, 180_000L))
        assertEquals(Window8144.UNKNOWN_DEFER, b.window8144(unknown, 6L * 3_600_000L))
        // First seen 30 s ago: the token is at least 30 s old — not proof it is fresh, not proof it is old: defer.
        val seen = b.read8144(Witness8144(firstSeenMs = t0 - 30_000L), t0)
        assertEquals(Kind8144.PROVEN_OLDER_THAN, seen.kind)
        assertEquals(30_000L, seen.minAgeMs)
        assertEquals(Window8144.UNKNOWN_DEFER, b.window8144(seen, 180_000L))
        // A history floor 10 minutes back proves the token is past a 3-minute window: old, so it is handed on.
        val floor = b.read8144(Witness8144(historyFloorMs = t0 - 600_000L, firstSeenMs = t0 - 30_000L), t0)
        assertEquals("HISTORY_FLOOR", floor.source)
        assertEquals(600_000L, floor.minAgeMs)
        assertEquals(Window8144.PAST_WINDOW, b.window8144(floor, 180_000L))
        assertEquals(Window8144.UNKNOWN_DEFER, b.window8144(floor, 3_600_000L))
        // A known birth judges the window on the real age.
        assertEquals(Window8144.IN_WINDOW, b.window8144(b.read8144(Witness8144(createFrameMs = t0 - 40_000L), t0), 180_000L))
        assertEquals(Window8144.PAST_WINDOW, b.window8144(b.read8144(Witness8144(createFrameMs = t0 - 200_000L), t0), 180_000L))
        // The launch lanes' age gate: unknown defers, a proven bound past the window refuses, established lanes pass.
        val s = LaneAgeLiquidityScope8140
        val d = s.ageVerdictBounded8144("PROJECT_SNIPER", null, null, 3.0)
        assertEquals(Outcome8140.DEFER, d.outcome)
        assertEquals("LANE_AGE_UNKNOWN_DEFER_8140_PROJECT_SNIPER", d.label)
        val old = s.ageVerdictBounded8144("PROJECT_SNIPER", null, 10.0, 3.0)
        assertEquals(Outcome8140.REFUSE, old.outcome)
        assertEquals("LANE_AGE_PROVEN_PAST_LAUNCH_WINDOW_8144_PROJECT_SNIPER", old.label)
        assertEquals(Outcome8140.DEFER, s.ageVerdictBounded8144("PROJECT_SNIPER", null, 2.0, 3.0).outcome)
        assertEquals(Outcome8140.PASS, s.ageVerdictBounded8144("QUALITY", null, 10.0, 3.0).outcome)
        assertEquals(Outcome8140.PASS, s.ageVerdictBounded8144("SHITCOIN", 1.0, 999.0, 360.0).outcome)   // true age wins
        // Wiring: the sniper reads the witnesses before saying "hydrating", and hands an old token on.
        val sniper = src("v3/scoring/ProjectSniperAI.kt")
        assertTrue(sniper.contains("AdmissionBirth8144.provenAgeMs8144(ts, MAX_TOKEN_AGE_SECONDS * 1000L)"))
        assertTrue(sniper.contains("BIRTH_METADATA_HYDRATING_7440"))
        assertTrue(sniper.contains("return handOff8144(ts, \"TOO_OLD_8144:"))
        assertTrue(SpecialistOwnership7951.laneFitRefusal8132("TOO_OLD_8144: 600s > 180s"))
    }

    @Test fun birthPagerAdvancesPastFailedRowsAndProvesOldAge() {
        val h = TokenBirthHydrator7441
        // A full page ending in a failed transaction: the cursor is that last row (it used to be the last success).
        val page = h.scanPage8144(listOf(
            SigRow8144("S1", 1_000L, 30L, failed = false),
            SigRow8144("S2", 900L, 20L, failed = false),
            SigRow8144("S3", 800L, 10L, failed = true),
        ), 3)
        assertFalse(page.complete)
        assertEquals("S3", page.cursor)
        assertEquals(900_000L, page.oldestBlockMs)          // a failed row never dates the mint
        assertEquals(0L, page.oldestOkSlotNoTime)
        // A page of only failed rows still pages on.
        val failed = h.scanPage8144(listOf(SigRow8144("F1", 700L, 9L, true), SigRow8144("F2", 600L, 8L, true)), 2)
        assertEquals("F2", failed.cursor)
        assertEquals(Long.MAX_VALUE, failed.oldestBlockMs)
        // The last page: the creation row has no blockTime -> its slot is asked (a newer row's time is not the birth).
        val last = h.scanPage8144(listOf(SigRow8144("A", 1_000L, 50L, false), SigRow8144("B", 0L, 40L, false)), 1_000)
        assertTrue(last.complete)
        assertEquals(40L, last.oldestOkSlotNoTime)
        assertEquals(1_000_000L, last.oldestBlockMs)
        assertTrue(h.scanPage8144(emptyList(), 1_000).complete)
        // History already reaching back 7 h proves the token established; 5 h does not stop the pager.
        assertTrue(h.provenEstablished8144(t0 - 7L * 3_600_000L, t0))
        assertFalse(h.provenEstablished8144(t0 - 5L * 3_600_000L, t0))
        assertFalse(h.provenEstablished8144(Long.MAX_VALUE, t0))
        val hyd = src("engine/truth/TokenBirthHydrator7441.kt")
        assertTrue(hyd.contains("AdmissionBirth8144.noteHistoryFloor8144(mint, state.oldestBlockMs)"))
        assertTrue(hyd.contains("if (pumpRecord8144(mint)) return"))
    }

    // ── hand-off: arrival only when the receiver has judged ──────────────────

    @Test fun handoffArrivesOnlyWhenTheReceiverEvaluated() {
        val a = AdmissionHandoff8144
        val h0 = a.emit8144(null, "M8144", "PROJECT_SNIPER", "MOONSHOT", "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT", t0)
        assertEquals(State8144.EMITTED, h0.state)
        assertSame(h0, a.emit8144(h0, "M8144", "PROJECT_SNIPER", "MOONSHOT", "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT", t0 + 1_000L))
        // Emitting the name is not an arrival: with no read it expires unevaluated.
        val unread = a.expire8144(h0, t0 + a.HANDOFF_TTL_MS_8144 + 1L)
        assertEquals(State8144.EXPIRED_NOT_EVALUATED, unread.state)
        assertEquals("NAMED_MOONSHOT_NEVER_EVALUATED", unread.verdict)
        // The same assessment (stamped just before the hand-off): MOONSHOT qualifies natively -> receiver, not yet arrived.
        val reads = listOf(
            LaneRead8144("PROJECT_SNIPER", false, 0, "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT"),
            LaneRead8144("MOONSHOT", true, 62, "MOONSHOT_NATIVE_ALLOW"),
            LaneRead8144("SHITCOIN", true, 70, "OK"),
            LaneRead8144("CORE", true, 80, "CORE_ENSEMBLE"),
        )
        val h1 = a.onNative8144(h0, reads, t0 - 1_000L)
        assertEquals(State8144.RECEIVER_QUALIFIED, h1.state)
        assertEquals("MOONSHOT", h1.receiver)
        assertTrue(h1.open)
        // Another lane's FDG verdict, or the sender's, is not the receiver's evaluation.
        assertSame(h1, a.onFdg8144(h1, "SHITCOIN", true, null, t0 + 2_000L))
        assertSame(h1, a.onFdg8144(h1, "PROJECT_SNIPER", false, "X", t0 + 2_000L))
        // Qualified but never reaching the FDG is a failure, not a success.
        val starved = a.expire8144(h1, t0 + a.HANDOFF_TTL_MS_8144 + 1L)
        assertEquals(State8144.EXPIRED_NOT_EVALUATED, starved.state)
        assertEquals("QUALIFIED_MOONSHOT_NEVER_REACHED_FDG", starved.verdict)
        // The receiver's FDG verdict completes it.
        val arrived = a.onFdg8144(h1, "MOONSHOT", false, "FDG_SCORE_FLOOR", t0 + 3_000L)
        assertEquals(State8144.ARRIVED_EVALUATED, arrived.state)
        assertEquals("FDG_REFUSED:FDG_SCORE_FLOOR", arrived.verdict)
        assertFalse(arrived.open)
        // A read older than the hand-off is not an evaluation of it.
        assertSame(h0, a.onNative8144(h0, reads, t0 - 10_000L))
        // The named lane judged and refused: arrived (evaluated), with its refusal.
        val refused = a.onNative8144(h0, listOf(LaneRead8144("MOONSHOT", false, 20, "MCAP_TOO_LOW"), LaneRead8144("SHITCOIN", false, 10, "LOW_SCORE")), t0)
        assertEquals(State8144.ARRIVED_EVALUATED, refused.state)
        assertEquals("NATIVE_REFUSED:MCAP_TOO_LOW", refused.verdict)
        // The named lane not in scope and nobody qualified: no receiver.
        val none = a.onNative8144(h0, listOf(LaneRead8144("SHITCOIN", false, 10, "A"), LaneRead8144("EXPRESS", false, 5, "B")), t0)
        assertEquals(State8144.NO_QUALIFIED_RECEIVER, none.state)
        assertEquals("NONE_OF_2_QUALIFIED", none.verdict)
        // The named lane refuses but another qualifies: the qualified lane is the receiver, and its later refusal arrives.
        val other = a.onNative8144(h0, listOf(LaneRead8144("MOONSHOT", false, 20, "MCAP_TOO_LOW"), LaneRead8144("EXPRESS", true, 55, "RIDE")), t0)
        assertEquals("EXPRESS", other.receiver)
        assertEquals("OTHER_QUALIFIED", other.verdict)
        val otherDone = a.onNative8144(other, listOf(LaneRead8144("EXPRESS", false, 30, "NO_CONTINUATION")), t0 + 5_000L)
        assertEquals(State8144.ARRIVED_EVALUATED, otherDone.state)
        assertEquals("NATIVE_REFUSED:NO_CONTINUATION", otherDone.verdict)
        // A fit transfer keeps the same provenance; when no compatible lane remains it closes explicitly.
        val transferred = a.retarget8153(h1, "MOONSHOT", "EXPRESS", "LANE_CAP", t0 + 4_000L)
        assertEquals("EXPRESS", transferred.receiver)
        assertEquals(setOf("MOONSHOT"), transferred.tried8153)
        assertEquals(State8144.RECEIVER_QUALIFIED, transferred.state)
        val noSuccessor = a.retarget8153(transferred, "EXPRESS", "", "FIT_NO_COMPATIBLE_RECEIVER", t0 + 5_000L)
        assertEquals(State8144.NO_QUALIFIED_RECEIVER, noSuccessor.state)
        assertEquals("FIT_NO_COMPATIBLE_RECEIVER:FIT_NO_COMPATIBLE_RECEIVER", noSuccessor.verdict)
        assertEquals(t0 + 5_000L, noSuccessor.settledAtMs)
        // The receiver is never the sender or CORE.
        assertEquals("SHITCOIN", a.receiver8144(null, "PROJECT_SNIPER", reads.filter { it.lane != "MOONSHOT" }))
        // A settled hand-off is not re-opened by the sender's next identical refusal, until the hold has passed.
        assertSame(arrived, a.emit8144(arrived, "M8144", "PROJECT_SNIPER", "MOONSHOT", "R", t0 + 60_000L))
        val again = a.emit8144(arrived, "M8144", "PROJECT_SNIPER", "MOONSHOT", "R", t0 + 3_000L + a.SETTLED_HOLD_MS_8144 + 1L)
        assertNotSame(arrived, again)
        assertEquals(State8144.EMITTED, again.state)
        // Wiring: native snapshots and FDG verdicts feed the ledger; the sniper's routes emit into it.
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("AdmissionHandoff8144.onNativeSnapshot8144(s.mint, s.evaluatedAtMs,"))
        assertTrue(src("engine/SpecialistOwnership7951.kt").contains("AdmissionHandoff8144.onFdgVerdict8144(mint, lane, shouldTrade, blockReason, nowMs)"))
        assertTrue(src("engine/truth/LaunchLifecycle8136.kt").contains("AdmissionHandoff8144.emitHandoff8144(mint, \"PROJECT_SNIPER\", route.handoffLane, route.reason)"))
        assertEquals("MOONSHOT", com.lifecyclebot.engine.truth.LaunchLifecycle8136.handoffLaneFor8144(25_000.0, 10_000.0))
        assertEquals("SHITCOIN", com.lifecyclebot.engine.truth.LaunchLifecycle8136.handoffLaneFor8144(6_000.0, 10_000.0))
    }

    // ── liquidity: missing defers, a real shallow pool refuses ───────────────

    @Test fun missingLiquidityDefersAndOnlyARealShallowPoolRefuses() {
        val s = LaneAgeLiquidityScope8140
        for (missing in listOf<Double?>(null, 0.0, Double.NaN, -1.0)) {
            val v = s.liquidityVerdict8140("PROJECT_SNIPER", missing, 2_000.0, live = true)
            assertEquals("$missing", Outcome8140.DEFER, v.outcome)
            assertEquals("LANE_LIQ_UNHYDRATED_DEFER_8140_PROJECT_SNIPER", v.label)
            assertEquals(v.label, s.refusalReason8140(v, 0.0, 2_000.0))      // never "$0 < $2000"
        }
        assertEquals(Outcome8140.REFUSE, s.liquidityVerdict8140("PROJECT_SNIPER", 1_500.0, 2_000.0, live = true).outcome)
        assertEquals(Outcome8140.PASS, s.liquidityVerdict8140("PROJECT_SNIPER", 2_500.0, 2_000.0, live = true).outcome)
        // The live exit-safe floor ($500) still refuses a real $300 pool even when the lane's own floor is lower.
        assertEquals(Outcome8140.REFUSE, s.liquidityVerdict8140("SHITCOIN", 300.0, 100.0, live = true).outcome)
        assertEquals(Outcome8140.PASS, s.liquidityVerdict8140("SHITCOIN", 300.0, 100.0, live = false).outcome)
    }

    // ── admission: proven alternatives first, coalesced re-evaluation ────────

    private fun setup(id: String, n: Int, mean: Double, se: Double) = Evidence8136(CandidateAdmission8136.KIND_SETUP, id, n, mean, se)

    private fun candidate(setups: List<Evidence8136>, timing: Evidence8136?) = Candidate8136(
        mint = "ADM8144pump", lane = "PROJECT_SNIPER", selectedSetup = "PRE_IGNITION_BASE", priceAgeMs = 5_000L,
        expectedGrossPct = 30.0, costPct = 6.0, downsidePct = 12.0, expectedGrossBySetup = setups.associate { it.id to 30.0 }, setups = setups, timing = timing,
        genericScore = 12.0, genericFloor = 30.0,
    )

    @Test fun provenAlternativeSetupIsAdmittedOverTheDefaultSetupsAdverseTiming() {
        // The default (selected) setup is not losing overall but loses at this timing band (n20, -6±2 -> upper -4).
        val timing = Evidence8136(CandidateAdmission8136.KIND_TIMING, "PRE_IGNITION_BASE@LATE", 20, -6.0, 2.0)
        val selected = setup("PRE_IGNITION_BASE", 40, 1.0, 2.0)            // upper +3: not losing; lower -1: not supported
        val alt = setup("LAUNCH_LADDER_PROVEN", 40, 9.0, 3.0)              // lower +6: supported on its own record
        val d = CandidateAdmission8136.decide8136(candidate(listOf(selected, alt), timing))
        assertEquals(Terminal8136.ADMIT, d.terminal)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason)
        assertEquals("LAUNCH_LADDER_PROVEN", d.chosenSetup)
        assertTrue(d.trace.any { it.startsWith("altSetup=SETUP:LAUNCH_LADDER_PROVEN n40") })
        // With no proven alternative the adverse timing still refuses (the proven-negative control is kept).
        val alone = CandidateAdmission8136.decide8136(candidate(listOf(selected), timing))
        assertEquals(Terminal8136.REFUSE, alone.terminal)
        assertEquals("TIMING_ADVERSE_8136", alone.reason)
        // A losing default setup with no alternative is still refused on its own record.
        val losing = CandidateAdmission8136.decide8136(candidate(listOf(setup("PRE_IGNITION_BASE", 40, -8.0, 2.0)), null))
        assertEquals("SETUP_RECORD_LOSING_8136", losing.reason)
    }

    @Test fun missingDataReEvaluationIsScheduledPerFeatureNotEveryCycle() {
        val c = CandidateAdmission8136
        val r1 = c.nextReeval8144(null, 1_000L)
        assertEquals(6_000L, r1.dueMs)
        assertEquals(1, r1.attempts)
        val r2 = c.nextReeval8144(r1, 6_000L)
        assertEquals(16_000L, r2.dueMs)
        val r3 = c.nextReeval8144(r2, 16_000L)
        assertEquals(36_000L, r3.dueMs)
        val r4 = c.nextReeval8144(r3, 36_000L)
        assertEquals(76_000L, r4.dueMs)
        val r5 = c.nextReeval8144(r4, 76_000L)
        assertEquals(76_000L + c.REEVAL_MAX_MS_8144, r5.dueMs)              // capped
        assertEquals(c.REEVAL_BASE_MS_8144, r1.dueMs - 1_000L)
        assertTrue(c.reevalDue8144(null, 0L))
        assertFalse(c.reevalDue8144(r1, 5_999L))
        assertTrue(c.reevalDue8144(r1, 6_000L))
        assertTrue(c.reevalDue8144(CandidateAdmission8136.Reeval8144(1_000_000L, 1), 0L))   // clock moved back
        // The features a deferral waits for are read back from its own trace.
        val d = c.decide8136(candidate(emptyList(), null).copy(missingInputs = listOf("shape:UNKNOWN"), priceAgeMs = Long.MAX_VALUE))
        assertEquals(Terminal8136.DEFER_DATA, d.terminal)
        assertEquals(listOf("shape:UNKNOWN", "price"), c.missingOf8144(d))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("CandidateAdmission8136.deferBudgetFor8144(ts.mint, missing, nowMs)"))
        val admission = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(admission.contains("coalescedDefer8144(ts.mint, candidate.lane, identity, version, mode, nowMs) ?: decide8136(candidate)"))
        assertTrue(admission.contains("if (h.candidateVersion != candidateVersion || h.mode != mode) return null"))
        assertTrue(admission.contains("if (h.candidate != cacheIdentity8144(candidate)) return null"))
    }

    // ── runner replay classifier ─────────────────────────────────────────────

    private val base = Trace8144(
        mint = "RUN8144pump", symbol = "RUNR", lane = "MOONSHOT", peakPct = 1_315.0, detectedAtMs = t0 + 3_600_000L,
        firstSeenMs = t0, source = "PUMP_FUN_NEW", birthKind = "KNOWN", birthSource = "PUMP_CREATE_EVENT", birthAgeMs = 45_000L,
        liquidityUsd = 6_000.0, nativeAtMs = t0 + 5_000L, natives = "MOONSHOT:Y:62:MOONSHOT_NATIVE_ALLOW",
        qualified = "MOONSHOT", setup = "LAUNCH_LADDER_PROVEN", admission = "", fdgLane = "", fdgVerdict = "", handoff = "",
        refusedBy = "", downstream = "", markPx = 1.2e-5, windowAtMs = t0 + 5_000L, windowCostPct = 6.5,
        windowCostMeasured = true, route = "CURVE", held = false,
        peakAtMs8239 = t0 + 3_000_000L, holdingMode8239 = "LIVE", holdingPositionId8239 = "P8144",
        holdingEntryAtMs8239 = t0 + 10_000L, holdingCloseAtMs8239 = t0 + 3_600_000L, holdingEntryPx8239 = 1.2e-5,
    )

    @Test fun replayClassifiesEachMissWithItsRefusingGate() {
        val r = AdmissionRunnerTrace8144
        assertEquals(Miss8144.CAPTURED, r.classify8144(base.copy(held = true)).miss)
        // A hand-off nobody evaluated while MOONSHOT had qualified: wrongly refused, by the hand-off.
        val lost = r.classify8144(base.copy(refusedBy = "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT",
            handoff = "EXPIRED_NOT_EVALUATED:MOONSHOT:QUALIFIED_MOONSHOT_NEVER_REACHED_FDG"))
        assertEquals(Miss8144.WRONGLY_REFUSED, lost.miss)
        assertTrue(lost.gate, lost.gate.startsWith("HANDOFF_NOT_EVALUATED"))
        // The same hand-off with no lane qualified at the time was correct.
        assertEquals(Miss8144.CORRECT_AT_DECISION, r.classify8144(base.copy(refusedBy = "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT", qualified = "")).miss)
        // It arrived and the receiver refused on its own fit rule with nothing qualified: correct, gate = receiver verdict.
        val arrived = r.classify8144(base.copy(refusedBy = "PHASE_HANDOFF_8136_BIRTH_TO_SHITCOIN_FLOW_UNKNOWN_PRICE_ONLY", qualified = "",
            handoff = "ARRIVED_EVALUATED:SHITCOIN:NATIVE_REFUSED:MCAP_TOO_LOW"))
        assertEquals(Miss8144.CORRECT_AT_DECISION, arrived.miss)
        assertEquals("NO_LANE_QUALIFIED:NATIVE_REFUSED:MCAP_TOO_LOW", arrived.gate)
        // Refused as "hydrating" while the birth was known at decision time: wrongly refused; truly unknown: a data gap.
        assertEquals(Miss8144.WRONGLY_REFUSED, r.classify8144(base.copy(refusedBy = "BIRTH_METADATA_HYDRATING_7440")).miss)
        val gap = r.classify8144(base.copy(refusedBy = "BIRTH_METADATA_HYDRATING_7440", birthKind = "UNKNOWN"))
        assertEquals(Miss8144.DATA_GAP, gap.miss)
        assertEquals("BIRTH_UNKNOWN:BIRTH_METADATA_HYDRATING_7440", gap.gate)
        // An unread pool refused as low is wrong; an unread pool deferred is a data gap; a real shallow pool is correct.
        assertEquals(Miss8144.WRONGLY_REFUSED, r.classify8144(base.copy(refusedBy = "LOW_LIQ: \$0 < \$2000", liquidityUsd = 0.0)).miss)
        assertEquals(Miss8144.DATA_GAP, r.classify8144(base.copy(refusedBy = "LANE_LIQ_UNHYDRATED_DEFER_8140_MOONSHOT", liquidityUsd = 0.0)).miss)
        assertEquals(Miss8144.CORRECT_AT_DECISION, r.classify8144(base.copy(refusedBy = "LANE_LIQ_BELOW_FLOOR_8140_MOONSHOT: \$900 < \$2000", liquidityUsd = 900.0)).miss)
        // Hard safety and a proven negative edge stand, whatever the coin did afterwards.
        val safe = r.classify8144(base.copy(admission = "REFUSE:SAFETY_REFUSED_8136@MOONSHOT"))
        assertEquals(Miss8144.CORRECT_AT_DECISION, safe.miss)
        assertTrue(safe.gate.startsWith("SAFETY:"))
        assertEquals(Miss8144.CORRECT_AT_DECISION, r.classify8144(base.copy(refusedBy = "SETUP_RECORD_LOSING_8136")).miss)
        // A generic prior over a natively qualified specialist is wrong; without one it is correct.
        assertEquals(Miss8144.WRONGLY_REFUSED, r.classify8144(base.copy(refusedBy = "GENERIC_SCORE_FLOOR_8136")).miss)
        assertEquals(Miss8144.CORRECT_AT_DECISION, r.classify8144(base.copy(refusedBy = "GENERIC_SCORE_FLOOR_8136", qualified = "")).miss)
        // Admitted, then stopped downstream: wrongly refused by that stop; no evaluation at all: not evaluated.
        assertEquals("ADMITTED>SIZE_CONVICTION_6909", r.classify8144(base.copy(downstream = "SIZE_CONVICTION_6909")).gate)
        assertEquals(Miss8144.NOT_EVALUATED, r.classify8144(base.copy(nativeAtMs = 0L, natives = "", qualified = "")).miss)
        assertEquals(Miss8144.DATA_GAP, r.classify8144(base.copy(admission = "DEFER_DATA:DATA_INCOMPLETE_DEFER_8136@MOONSHOT")).miss)
        // The summary counts each class and the wrong refusals by gate.
        val list = listOf(base.copy(held = true), base.copy(refusedBy = "GENERIC_SCORE_FLOOR_8136"), base.copy(refusedBy = "SETUP_RECORD_LOSING_8136"))
        val line = r.summary8144(list)
        assertTrue(line, line.startsWith("runners=3 held_entry=1 correct_at_decision=1 wrongly_refused=1"))
        assertTrue(line, line.contains("wrongBy{GENERIC_PRIOR_OVER_QUALIFIED=1}"))
        assertEquals("runners=0", r.summary8144(emptyList()))
    }

    @Test fun exportedTracesReplayFromTheirJsonl() {
        val r = AdmissionRunnerTrace8144
        val a = base.copy(refusedBy = "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT", handoff = "EXPIRED_NOT_EVALUATED:MOONSHOT:X")
        val b = base.copy(mint = "RUN8144bpump", symbol = "RUNB", peakPct = 591.0, held = true)
        val text = listOf(a, b).joinToString("\n") { r.toJson8144(it).toString() } + "\nnot json\n"
        val back = r.parseJsonl8144(text)
        assertEquals(listOf(a, b), back)
        assertEquals(listOf(Miss8144.WRONGLY_REFUSED, Miss8144.CAPTURED), back.map { r.classify8144(it).miss })
        // Wiring: the runner capture freezes the trace and prints the replay on its status line.
        val rc = src("engine/truth/RunnerCapture8017.kt")
        assertTrue(rc.contains("AdmissionRunnerTrace8144.capture8144(mint, symbol, lane, r.startPx, r.peakPct, r.refusedBy, downstream8047[mint], r.held, nowMs)"))
        assertTrue(rc.contains("AdmissionRunnerTrace8144.statusLine8144()"))
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("AdmissionRunnerTrace8144.noteAdmission8144(mint, laneRaw, d.terminal.name, d.reason)"))
    }
}
