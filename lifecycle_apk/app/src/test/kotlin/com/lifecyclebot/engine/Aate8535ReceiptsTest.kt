package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexOpportunityPolarity8522
import com.lifecyclebot.engine.sell.SellRouteSpine8144
import com.lifecyclebot.engine.sell.SellRouteSpine8144.Stage8144
import com.lifecyclebot.v3.eligibility.DGradeLooperTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** V5.0.8535 — audit P0-11 (the Cortex receipt on every bound decision), P0-12 (the emergency receipt), P2-02 (the tracker race). */
class Aate8535ReceiptsTest {

    // ── P0-11 ──

    @Test fun every_projection_carries_its_receipt_and_unknowns_are_named() {
        val line = CortexOpportunityPolarity8522.receiptLine8535("ABSTAIN", "REFUSE", 0.41, -3.25, 0.12, 48.0, 0.25, 3.4, "REFUSE_BUCKET_INVERSION_UNPROVEN_8527")
        assertEquals("ABSTAIN raw=REFUSE pUp=0.41 net=-3.2% tail=0.12 n=48 null=0.25 voters=3.4 why=REFUSE_BUCKET_INVERSION_UNPROVEN_8527", line)
        val unknown = CortexOpportunityPolarity8522.receiptLine8535("ABSTAIN", "-", Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "NO_CURRENT_ASSESSMENT")
        assertTrue(unknown.contains("pUp=? net=?% tail=? n=? null=? voters=?"))
        assertEquals(0.5, CortexOpportunityPolarity8522.nullFraction8535(doubleArrayOf(1.0, Double.NaN, 2.0, Double.NaN), 4), 0.0)
        assertTrue(CortexOpportunityPolarity8522.nullFraction8535(DoubleArray(0), 0).isNaN())
        val src = File("src/main/kotlin/com/lifecyclebot/engine/cortex/CortexOpportunityPolarity8522.kt").readText()
        assertTrue("the receipt reads the assessment itself", src.contains("pUp8535 = assessment?.fused?.pWin ?: Double.NaN"))
        assertTrue(src.contains("recentReceipts8535.addLast("))
        // the inversion itself is still only a proven lane cohort's: no global negation
        assertTrue(src.contains("REFUSE_BUCKET_INVERSION_UNPROVEN_8527") && src.contains("STRONG_BUCKET_INVERSION_UNPROVEN_8527"))
    }

    // ── P0-12 ──

    @Test fun an_emergency_exit_leaves_a_timestamped_receipt_naming_the_stage_that_ate_the_bound() {
        val ms = mapOf(Stage8144.CLOSE_LEASE to 4_100L, Stage8144.TOKEN_BALANCE to 300L, Stage8144.VENUE_RESOLVE to 200L,
            Stage8144.QUOTE_BUILD to 1_900L, Stage8144.SIGN to 150L, Stage8144.BROADCAST to 400L, Stage8144.RPC_ACK to 900L)
        assertEquals(Stage8144.CLOSE_LEASE, SellRouteSpine8144.dominantStage8535(ms))
        assertNull(SellRouteSpine8144.dominantStage8535(mapOf(Stage8144.CHAIN_FINALITY to 5_000L)))
        val line = SellRouteSpine8144.receiptLine8535("MintAbcdefgh", "LIVE:p1", 1_000L, 8_079L, ms, Stage8144.QUOTE_BUILD, 2, "CONFIRMED")
        assertTrue(line.startsWith("EMRG MintAbcd pos=LIVE:p1 triggerAt=1000 broadcastAt=8079 t2b=7079ms bound=3000ms"))
        assertTrue(line.contains("dominant=CLOSE_LEASE failed=QUOTE_BUILDx2 outcome=CONFIRMED BREACH"))
        val inside = SellRouteSpine8144.receiptLine8535("m", "", 1_000L, 2_500L, ms, null, 0, "CLOSED")
        assertFalse(inside.contains("BREACH")); assertTrue(inside.contains("pos=- "))
        assertTrue(SellRouteSpine8144.receiptLine8535("m", "", 1_000L, 0L, ms, null, 0, "ABANDONED").contains("broadcastAt=- t2b=-ms"))
        assertEquals(3_000L, SellRouteSpine8144.EMERGENCY_T2B_BOUND_MS_8535)
        assertTrue("the ring is readable by the brief", SellRouteSpine8144.emergencyReceipts8535().size <= 20)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/sell/SellRouteSpine8144.kt").readText()
        assertTrue(src.contains("if (t.emergency) try { receipt8535(t, outcome) }"))
        assertTrue(src.contains("EMERGENCY_T2B_BREACH_STAGE_8535_"))
    }

    // ── P2-02 ──

    @Test fun the_looper_tracker_keeps_its_window_semantics_under_concurrent_proposals() {
        DGradeLooperTracker.clear()
        val t0 = 5_000_000L
        val pool = Executors.newFixedThreadPool(8)
        val errors = AtomicInteger(0)
        repeat(8) { w ->
            pool.execute {
                try {
                    repeat(400) { i ->
                        val mint = "mint${(w * 400 + i) % 50}"
                        DGradeLooperTracker.recordProposal(mint, if (i % 3 == 0) "D" else "C", i % 30, t0 + i)
                        DGradeLooperTracker.shouldBlockDGradeLooper(mint, "D", 5, t0 + i)
                    }
                } catch (_: Throwable) { errors.incrementAndGet() }
            }
        }
        pool.shutdown(); assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals("no exception under concurrent record / check / clean", 0, errors.get())
        DGradeLooperTracker.clear()
        repeat(9) { DGradeLooperTracker.recordProposal("loop", "D", 5, t0 + it) }
        assertFalse(DGradeLooperTracker.shouldBlockDGradeLooper("loop", "D", 5, t0 + 10))
        DGradeLooperTracker.recordProposal("loop", "D", 5, t0 + 10)
        assertTrue("ten D-grade low-confidence proposals inside two minutes block", DGradeLooperTracker.shouldBlockDGradeLooper("loop", "D", 5, t0 + 11))
        assertFalse("not for a better grade or confidence", DGradeLooperTracker.shouldBlockDGradeLooper("loop", "C", 5, t0 + 11))
        assertFalse(DGradeLooperTracker.shouldBlockDGradeLooper("loop", "D", 15, t0 + 11))
        assertFalse("the window passes", DGradeLooperTracker.shouldBlockDGradeLooper("loop", "D", 5, t0 + 2 * 60_000L + 11))
        assertEquals(0, DGradeLooperTracker.proposalCount8535("loop", t0 + 2 * 60_000L + 11))
    }
}
