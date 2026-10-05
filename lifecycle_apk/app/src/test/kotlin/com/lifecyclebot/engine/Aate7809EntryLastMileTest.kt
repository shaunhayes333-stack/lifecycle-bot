package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440
import com.lifecyclebot.engine.truth.ExecutionSnapshotAuthority6496
import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.QuoteRevalidation7809
import com.lifecyclebot.engine.truth.SealedOrderSizeAuthority6497
import com.lifecyclebot.engine.truth.TradePlan7739
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7809 — EntryLastMile: quote revalidation, sealed size coherence, precise refusals, birth memory. */
class Aate7809EntryLastMileTest {

    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun seal(mint: String, size: Double, lane: String) =
        SealedOrderSizeAuthority6497.sealFor(
            mint,
            OrderSizeResolver6441.Resolution(size, size, size, size, size, size, true, "OK"),
            lane,
        )

    // ── 9: FINALITY_BLOCK:resolvedOrderSizeSol(0.0178->0.0112) ──────────────

    @Test fun sameLaneNewerSealIsRevalidationNotDrift() {
        val mint = "Size7809Same${System.nanoTime()}"
        seal(mint, 0.0178, "SHITCOIN")
        ExecutionSnapshotAuthority6496.record(mint, "SHITCOIN", "SAFE", "LIVE:$mint", 0.0178)
        Thread.sleep(3)
        seal(mint, 0.0112, "SHITCOIN") // e.g. the wallet moved and the lane re-sized
        val now = SealedOrderSizeAuthority6497.sealedSize(mint) ?: 0.0
        assertNull(ExecutionSnapshotAuthority6496.matchOrDriftReason(mint, "SHITCOIN", "SAFE", "LIVE:$mint", now))
        assertEquals(0.0112, ExecutionSnapshotAuthority6496.sealedSnapshot6609(mint)!!.resolvedOrderSizeSol, 1e-12)
    }

    @Test fun otherLaneSealDoesNotShrinkThisTicket() {
        val mint = "Size7809Cross${System.nanoTime()}"
        seal(mint, 0.0178, "MOONSHOT")
        ExecutionSnapshotAuthority6496.record(mint, "MOONSHOT", "SAFE", "LIVE:$mint", 0.0178)
        Thread.sleep(3)
        seal(mint, 0.0112, "SHITCOIN")
        assertNull(ExecutionSnapshotAuthority6496.matchOrDriftReason(mint, "MOONSHOT", "SAFE", "LIVE:$mint", 0.0112))
        assertEquals(0.0178, ExecutionSnapshotAuthority6496.sealedSnapshot6609(mint)!!.resolvedOrderSizeSol, 1e-12)
    }

    @Test fun shrinkWithoutNewerSealStillDrifts() {
        val mint = "Size7809Shrink${System.nanoTime()}"
        seal(mint, 0.0178, "SHITCOIN")
        Thread.sleep(3)
        ExecutionSnapshotAuthority6496.record(mint, "SHITCOIN", "SAFE", "LIVE:$mint", 0.0178)
        val drift = ExecutionSnapshotAuthority6496.matchOrDriftReason(mint, "SHITCOIN", "SAFE", "LIVE:$mint", 0.0112)
        assertNotNull(drift)
        assertTrue(drift!!.contains("resolvedOrderSizeSol"))
    }

    // ── 10: CHOKEPOINT_7742 names its read ─────────────────────────────────

    @Test fun chokepointAbortCarriesPreciseReason() {
        assertEquals("CHOKEPOINT_7742_PLAN_TOO_FEW_BARS", TradePlan7739.chokepointTerminalReason7809("NO_PLAN_WAIT_7739:TOO_FEW_BARS:bars=3"))
        assertEquals("CHOKEPOINT_7742_CELL_PROOF_NEGATIVE_7731", TradePlan7739.chokepointTerminalReason7809("CELL_PROOF_NEGATIVE_7731"))
        assertEquals("CHOKEPOINT_7742_UNSPECIFIED", TradePlan7739.chokepointTerminalReason7809(""))
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("emitLiveBuyFail(ts, sol, chokeReason7809(refusal7742), refusal7742)"))
        assertFalse(ex.contains("emitLiveBuyFail(ts, sol, \"CHOKEPOINT_7742\","))
    }

    // ── 2: stale quote -> bounded single-flight revalidation ───────────────

    @Test fun staleQuoteRevalidationConfirmsOnlyAgreeingPriceAndIsBounded() {
        val oldFetcher = QuoteRevalidation7809.fetcher7809
        val oldAsync = QuoteRevalidation7809.async7809
        val oldHook = QuoteRevalidation7809.onConfirmed7809
        try {
            QuoteRevalidation7809.onConfirmed7809 = { _ -> }
            QuoteRevalidation7809.resetForTest()
            QuoteRevalidation7809.async7809 = false
            var calls = 0
            QuoteRevalidation7809.fetcher7809 = { _ -> calls++; 1.01 }
            val mint = "Quote7809${System.nanoTime()}"
            val t0 = System.currentTimeMillis()
            assertTrue(QuoteRevalidation7809.request(mint, 1.0, t0))
            assertNotNull(QuoteRevalidation7809.confirmedAgeMs(mint, 1.0))
            // A confirmation never vouches for a different price.
            assertNull(QuoteRevalidation7809.confirmedAgeMs(mint, 1.5))
            // Backoff, then a hard attempt budget: no refresh loop.
            assertFalse(QuoteRevalidation7809.request(mint, 1.0, t0 + 1_000L))
            assertTrue(QuoteRevalidation7809.request(mint, 1.0, t0 + 16_000L))
            assertTrue(QuoteRevalidation7809.request(mint, 1.0, t0 + 70_000L))
            assertFalse(QuoteRevalidation7809.request(mint, 1.0, t0 + 200_000L))
            assertEquals(3, calls)

            // A quote that disagrees means the card's price is stale: nothing is confirmed.
            QuoteRevalidation7809.fetcher7809 = { _ -> 1.20 }
            val moved = "QuoteMoved7809${System.nanoTime()}"
            assertTrue(QuoteRevalidation7809.request(moved, 1.0))
            assertNull(QuoteRevalidation7809.confirmedAgeMs(moved, 1.0))
            // No answer confirms nothing either.
            QuoteRevalidation7809.fetcher7809 = { _ -> null }
            val silent = "QuoteSilent7809${System.nanoTime()}"
            assertTrue(QuoteRevalidation7809.request(silent, 1.0))
            assertNull(QuoteRevalidation7809.confirmedAgeMs(silent, 1.0))
        } finally {
            QuoteRevalidation7809.fetcher7809 = oldFetcher
            QuoteRevalidation7809.async7809 = oldAsync
            QuoteRevalidation7809.onConfirmed7809 = oldHook
            QuoteRevalidation7809.resetForTest()
        }
        val fm = src("engine/truth/FieldManual7715.kt")
        assertTrue(fm.contains("requoteIfOnlyQuoteIsOpen7809(ts, verdict)"))
        assertTrue(fm.contains("quoteAgeMs = quoteAge7809,"))
    }

    // ── 11: launch birth survives handoff ──────────────────────────────────

    @Test fun launchBirthSurvivesMutableIdentityChurn() {
        val ts = TokenState(mint = "Birth7809${System.nanoTime() % 100000}")
        ts.source = "PUMP_FUN_NEW"
        ts.lastMcap = 12_000.0
        ts.addedToWatchlistAt = System.currentTimeMillis() - 60_000L
        val first = CanonicalTokenBirthTime7440.launchAgeMs7767(ts)
        assertNotNull(first)
        // Canonical handoff rewrites the mutable fields; the birth does not move.
        ts.source = "V3_CANONICAL"
        ts.addedToWatchlistAt = System.currentTimeMillis()
        val after = CanonicalTokenBirthTime7440.launchAgeMs7767(ts)
        assertNotNull(after)
        assertTrue("age must never get younger than evidence: first=$first after=$after", after!! >= first!!)
        // A mint with no evidence at all is still unknown (and still refused by the sniper).
        val unknown = TokenState(mint = "NoBirth7809${System.nanoTime() % 100000}")
        unknown.source = "DEX_TRENDING"
        assertNull(CanonicalTokenBirthTime7440.launchAgeMs7767(unknown))
    }

    // ── 12 / 13 / 14: source contracts ─────────────────────────────────────

    @Test fun lastMileSourceContracts() {
        val cs = src("engine/CommonSenseTradePlaybook.kt")
        assertTrue(cs.contains("TokenMapAuthority.observedLiquidityUsd(ts)"))
        val fdg = src("engine/FinalDecisionGate.kt")
        // evaluate() is at the ART verifier limit (5.0.7807): the 7243 wait-promotion line stays byte-identical.
        assertTrue(fdg.contains("baseEntrySignal7243 !in setOf(\"BUY\", \"EXECUTE\") && effectiveEntryScore7292 < waitFloor7266"))
        assertTrue(fdg.contains("priorVerdictForCandidate7809(ts.mint, candidateVersion)"))
        assertTrue(fdg.contains("FDG_FANOUT_CAP_SERVED_PRIOR_VERDICT_7809"))
        val esa = src("engine/truth/ExecutionSnapshotAuthority6496.kt")
        assertTrue(esa.contains("!newerSizingDecision7809(mint, snap, resolvedOrderSizeSol)"))
    }
}
