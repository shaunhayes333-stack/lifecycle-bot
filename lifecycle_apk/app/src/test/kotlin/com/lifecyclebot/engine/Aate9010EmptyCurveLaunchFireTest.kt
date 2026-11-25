package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.engine.truth.SignalTape8828
import com.lifecyclebot.network.PumpCurveKeys7269
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9010 — the SOL already in a pump.fun curve is a selection rule (8046), not a custody or exit-capacity fact, and a
 * live armed launch fire answers it: at the buy door, at the early gate, in the LP proof, at the lane floor. A held
 * position's curve is exit-safe while it is an active curve. The launch tape gains "at its open on a curve nobody has
 * bought". The buy door's pending proofs are counted by name.
 */
class Aate9010EmptyCurveLaunchFireTest {
    private val now = 1_795_610_000_000L
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theAmountBarIsAnsweredOnlyByAFireAndNeverByAnUnknownReading() {
        val d = OnChainDepth8038
        assertFalse(d.curveAmountOk9010(null, 0.5, true))
        assertFalse(d.curveAmountOk9010(Double.NaN, 0.5, true))
        assertFalse(d.curveAmountOk9010(-0.1, 0.5, true))
        assertFalse("no fire: the bar as before", d.curveAmountOk9010(0.3, 0.5, false))
        assertTrue(d.curveAmountOk9010(0.3, 0.5, true))
        assertTrue(d.curveAmountOk9010(0.0, 0.5, true))
        assertTrue(d.curveAmountOk9010(0.7, 0.5, false))
        assertEquals(3.0, d.MIN_CURVE_REAL_SOL_8046, 0.0)
        assertFalse(d.launchFireAnswers9010("", now))
        assertFalse("no fire on a coin the sniper never saw", d.launchFireAnswers9010("NoFire9010AAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", now))
        assertTrue(d.fireAnsweredLine9010().startsWith("curveAmountAnsweredByFire9010="))
        // 8046 itself is unchanged
        assertTrue(d.curveNobodyBought8046(1.9)); assertFalse(d.curveNobodyBought8046(3.0)); assertFalse(d.curveNobodyBought8046(null))
    }

    @Test fun withoutAFireAnEmptyCurveIsRefusedEverywhereItWas() {
        val thin = "ThinCurve9010AAAAAAAAAAAAAAAAAAAAAAAAAAApump"
        CurveReserves7951.noteFrame7951(thin, 30.3, 1_000_000.0, System.currentTimeMillis() - 1_000L)   // 0.3 SOL real
        val t = System.currentTimeMillis()
        assertFalse(LiquidityOwnership8522.pumpCurveByStream9007(thin, t))
        assertFalse(LiquidityOwnership8522.activeCurveVerified(thin, t))
        assertFalse(LaneAgeLiquidityTruth8140.activeCurveExitSafe8319(thin, t))
        val door = src("engine/truth/OnChainDepth8038.kt").substringAfter("fun buyRefusal8038(").substringBefore("fun earlyLaunchRefusal8048(")
        assertTrue(door.contains("val fire9010 = launchFireAnswers9010(ts.mint, nowMs)"))
        assertTrue(door.contains("if (!fire9010 && curveNobodyBought8046(curveReal8046)) {"))
        assertTrue(door.contains("return \"CURVE_NOBODY_BOUGHT_8046\""))
        val early = src("engine/truth/OnChainDepth8038.kt").substringAfter("fun earlyLaunchRefusal8048(").substringBefore("enum class LaunchBuyers8252")
        assertTrue(early.contains("if (launchFireAnswers9010(ts.mint, nowMs))"))
        assertTrue(early.contains("curveNobodyBought8046(real) -> \"CURVE_NOBODY_BOUGHT_8046\""))
    }

    @Test fun aHeldPositionsCurveIsExitSafeWhileItIsAnActiveCurve() {
        val id = "HeldCurve9010AAAAAAAAAAAAAAAAAAAAAAAAAAApump"
        val t = System.currentTimeMillis()
        assertFalse("unknown is not safe", LaneAgeLiquidityTruth8140.heldCurveSolvent9010(id, t))
        CurveReserves7951.noteFrame7951(id, 30.2, 1_000_000.0, t - 1_000L)             // 0.2 SOL real: the buyer's own
        assertTrue(LaneAgeLiquidityTruth8140.heldCurveSolvent9010(id, t))
        assertFalse("the buy-side bar is not moved by a holding", LaneAgeLiquidityTruth8140.activeCurveExitSafe8319(id, t))
        PumpCurveKeys7269.markGraduated7392(id)
        assertFalse("a graduated coin is a pool, judged as one", LaneAgeLiquidityTruth8140.heldCurveSolvent9010(id, t))
        assertFalse(LaneAgeLiquidityTruth8140.heldCurveSolvent9010("", t))
        assertTrue(src("engine/LaunchWindow8379.kt").contains("curve && LaneAgeLiquidityTruth8140.heldCurveSolvent9010(ts.mint, nowMs))"))
        // the pure floor rule is the same rule
        assertTrue(LaunchWindow8379.floorBreached8418(0.0, true, false)); assertFalse(LaunchWindow8379.floorBreached8418(0.0, true, true))
    }

    /** [n] prints in bar 0 at [open], bar 1 closing at [close]; what turns on when bar 2's first print closes bar 1. */
    private fun at20s(n: Int, open: Double, close: Double): List<String> {
        val t = SignalTape8828()
        for (k in 0 until n) t.onPrint(500L + k * 900L, open, k % 2 == 0, 0.05, -1, true)
        t.onPrint(12_000, close, true, 0.05, -1, true)
        return t.onPrint(21_000, close, true, 0.05, -1, true)
    }

    @Test fun atItsOpenOnACurveNobodyHasBought() {
        val a = SignalTape8828.AT_OPEN_EMPTY_20S_9010; val b = SignalTape8828.AT_OPEN_EMPTY_60S_9010
        assertTrue(SignalTape8828.IDS.containsAll(listOf(a, b)))
        assertFalse("price alone: no wallet needed", a in SignalTape8828.WALLET_IDS || b in SignalTape8828.WALLET_IDS)
        val tape = SignalTape8828()
        // pump.fun's opening price is 30 / 1.073e9 = 2.796e-8 SOL; 3 SOL in the curve prices it at 33^2 / (30 x 1.073e9) = 3.383e-8
        assertTrue(tape.emptyCurve9010(2.80e-8)); assertTrue(tape.emptyCurve9010(3.30e-8))
        assertFalse(tape.emptyCurve9010(3.45e-8)); assertFalse(tape.emptyCurve9010(1.00e-7))
        assertFalse(tape.emptyCurve9010(0.0)); assertFalse(tape.emptyCurve9010(Double.NaN))
        assertTrue(a in at20s(8, 2.80e-8, 2.90e-8))
        assertTrue("the plain at-its-open setting fires with it", SignalTape8828.AT_OPEN_20S_9007 in at20s(8, 2.80e-8, 2.90e-8))
        assertFalse("a curve the creator already filled is not an empty one", a in at20s(8, 1.00e-7, 1.05e-7))
        assertTrue(SignalTape8828.AT_OPEN_20S_9007 in at20s(8, 1.00e-7, 1.05e-7))
        assertFalse("too few prints", a in at20s(4, 2.80e-8, 2.90e-8))
        assertFalse("20 s is not 60 s", b in at20s(8, 2.80e-8, 2.90e-8))
    }

    @Test fun theBuyDoorsPendingProofsAreCountedByName() {
        assertEquals("LP_OWNERSHIP_PROOF_PENDING_8522", PreTradeHardGate.proofName9010("LP_OWNERSHIP_PROOF_PENDING_8522:curve"))
        assertEquals("RUGCHECK_PENDING_OR_UNKNOWN", PreTradeHardGate.proofName9010("RUGCHECK_PENDING_OR_UNKNOWN:PENDING_REVIEW"))
        assertEquals("HOLDER_DATA_UNKNOWN", PreTradeHardGate.proofName9010("HOLDER_DATA_UNKNOWN"))
        assertTrue(PreTradeHardGate.pendingProofLine9010().startsWith("criticalProofPending9010{"))
        val gate = src("engine/PreTradeHardGate.kt").substringAfter("private fun allowWithPendingProof(")
        assertTrue(gate.substringBefore("return deferSafetyProof(ts, \"LIVE_CRITICAL_PROOF_PENDING_8373\"").contains("notePending9010(essentialPending)"))
        val line = src("engine/truth/CandidateAdmission8136.kt").substringAfter("private fun deferCauseLine8700()").substringBefore("/** Pure (V5.0.8980)")
        assertTrue(line.contains("PreTradeHardGate.pendingProofLine9010()") && line.contains("OnChainDepth8038.fireAnsweredLine9010()"))
    }
}
