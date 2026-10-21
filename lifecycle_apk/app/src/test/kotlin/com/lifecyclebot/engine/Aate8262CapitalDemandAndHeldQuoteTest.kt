package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OpportunityRotation8098
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8262 — an APPROVED live entry the wallet cannot fund is capital-rotation demand (8261: 63 starved, demands=0),
 * and a held sell quote stamped at request time stays a usable exit mark long enough to arrive (Fartcoin +370 %,
 * quote 16 s old, mark=NONE).
 */
class Aate8262CapitalDemandAndHeldQuoteTest {
    @Test fun aCapitalStarvedApprovedEntryRaisesBookDemandOnce() {
        val r = OpportunityRotation8098
        val now = System.currentTimeMillis() + 7_200_000L
        assertFalse(r.bookDemand8103(now))
        r.noteCapitalDemand8262("", now)
        assertFalse(r.bookDemand8103(now))
        r.noteCapitalDemand8262("STARVED8262", now)
        assertTrue(r.bookDemand8103(now))
        r.noteCapitalDemand8262("STARVED8262", now + 1_000L)      // same entry re-evaluated: one demand
        val held = listOf(OpportunityRotation8098.Held("A8262", -4.0), OpportunityRotation8098.Held("B8262", -9.0))
        assertFalse(r.shouldRotate8098("A8262", r.BOOK_8103, held, now + 2_000L))
        assertTrue(r.shouldRotate8098("B8262", r.BOOK_8103, held, now + 2_000L))
        assertFalse(r.bookDemand8103(now + 2_000L))
        r.sellRequested8270("B8262", accepted = true, nowMs = now + 2_500L)
        r.capitalConfirmed8270("B8262", now + 3_000L)                          // the lifecycle completes; nothing live is left behind
        // victims are still never a runner, a tail, or a fresh position
        assertFalse(r.victimEligible8098(5.0, 370.0, 10 * 60_000L, isTail = false, markFresh = true))
        assertFalse(r.victimEligible8098(-8.0, 2.0, 60_000L, isTail = false, markFresh = true))
        assertFalse(r.victimEligible8098(-8.0, 2.0, 10 * 60_000L, isTail = true, markFresh = true))
        assertFalse(r.victimEligible8098(-8.0, 2.0, 10 * 60_000L, isTail = false, markFresh = false))
        assertTrue(r.victimEligible8098(-8.0, 2.0, 10 * 60_000L, isTail = false, markFresh = true))
    }

    @Test fun theStarvedEntryIsWiredFromTheSealToRotationDemand() {
        val m = File("src/main/kotlin/com/lifecyclebot/engine/MemeChokes7951.kt").readText()
        assertTrue(m.contains("OpportunityRotation8098.noteCapitalDemand8262(mint)"))
        val f = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(f.contains("MemeChokes7951.noteCapitalStarved7951(ts.mint, lane, cash - com.lifecyclebot.engine.truth.LiveSpendReserveAuthority7255.RESERVE_SOL, minimum, config.paperMode)"))
    }
}
