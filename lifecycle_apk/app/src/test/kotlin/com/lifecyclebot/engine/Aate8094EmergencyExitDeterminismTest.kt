package com.lifecyclebot.engine

import com.lifecyclebot.network.SignedTxLedger8085
import com.lifecyclebot.network.SignedTxLedger8085.State
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.8094 forensic P0 — emergency exits under provider outages: no unproven-expiry duplicate sell, no indefinite
 * suppression, no discretionary hold in front of an emergency, bounded self-retry.
 */
class Aate8094EmergencyExitDeterminismTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Before fun reset() { SignedTxLedger8085.resetForTest() }

    // Conflicting signed transactions: an unresolved sell blocks a second sell until chain evidence resolves it.
    @Test fun anUnresolvedSellBlocksASecondSellUntilChainEvidenceArrives() {
        SignedTxLedger8085.record("SIG_A", "b64", "bh", SignedTxLedger8085.Intent("MINT_E", "SELL", 100L), nowMs = 1_000L)
        SignedTxLedger8085.transition("SIG_A", State.SUBMIT_ATTEMPTED, "fanout_8094", nowMs = 1_100L)
        // Delayed / failing RPC: the outcome is UNKNOWN, never proven either way...
        SignedTxLedger8085.transition("SIG_A", State.OUTCOME_UNKNOWN, "rpc_timeout", nowMs = 1_200L)
        // ...and no amount of elapsed time alone releases the guard.
        assertNotNull(SignedTxLedger8085.unresolvedFor("MINT_E", "SELL", nowMs = 1_000L + 10 * 60_000L))
        // Expiry needs an unseen signature AND an invalid blockhash; an unreadable blockhash proves nothing.
        assertFalse(SignedTxLedger8085.expiredProven(statusSeen = false, blockhashValid = null))
        assertTrue(SignedTxLedger8085.expiredProven(statusSeen = false, blockhashValid = false))
        SignedTxLedger8085.transition("SIG_A", State.EXPIRED_PROVEN, "blockhash_invalid_signature_unseen", nowMs = 90_000L)
        assertNull(SignedTxLedger8085.unresolvedFor("MINT_E", "SELL", nowMs = 91_000L))      // the exit may go again
    }

    // A landed sell is chain truth: a later provider timeout can never turn it into an expiry or a resend.
    @Test fun aConfirmedSellIsNeverReopenedByALaterTimeout() {
        SignedTxLedger8085.record("SIG_B", "b64", "bh", SignedTxLedger8085.Intent("MINT_F", "SELL", 100L), nowMs = 1L)
        SignedTxLedger8085.transition("SIG_B", State.CONFIRMED, "status=confirmed", nowMs = 2L)
        SignedTxLedger8085.transition("SIG_B", State.EXPIRED_PROVEN, "late", nowMs = 3L)
        SignedTxLedger8085.transition("SIG_B", State.OUTCOME_UNKNOWN, "late timeout", nowMs = 4L)
        assertEquals(State.CONFIRMED, SignedTxLedger8085.get("SIG_B")!!.state)
        // The sign-time fan-out and the Sender path mark SUBMIT_ATTEMPTED twice: idempotent, one signature.
        SignedTxLedger8085.record("SIG_C", "b64", "bh", SignedTxLedger8085.Intent("MINT_G", "SELL", 100L), nowMs = 10L)
        SignedTxLedger8085.transition("SIG_C", State.SUBMIT_ATTEMPTED, "fanout_8094", nowMs = 11L)
        SignedTxLedger8085.transition("SIG_C", State.SUBMIT_ATTEMPTED, "helius_sender", nowMs = 12L)
        assertEquals(State.SUBMIT_ATTEMPTED, SignedTxLedger8085.get("SIG_C")!!.state)
    }

    // Repeated catastrophic triggers / stale lease: one bounded self-retry per mint, never parked longer than 15 s.
    @Test fun repeatedTriggersScheduleOneBoundedSelfRetry() {
        val l = com.lifecyclebot.engine.sell.CloseLease
        for (gap in listOf(-60_000L, 0L, 2_000L, 10_000L, 600_000L)) {
            val d = l.selfRetryDelayMs8094(eligibleAtMs = 100_000L + gap, nowMs = 100_000L)
            assertTrue("gap=$gap delay=$d", d in 250L..15_000L)
        }
        val lease = src("engine/sell/CloseLease.kt")
        assertTrue(lease.contains("if (pending != null && pending >= now) return            // one self-retry per mint at a time"))
        assertTrue(lease.contains("if (current(mint) != null) {"))     // a released lease is not re-dispatched
    }

    // Unavailable balance proof: an emergency sells from the last complete snapshot; a partial snapshot proves nothing.
    @Test fun unavailableBalanceProofDoesNotBlockAnEmergencyOrFakeAZero() {
        val x = src("engine/Executor.kt")
        assertTrue(x.contains("preSellGuard(ts.mint, ts.symbol ?: \"?\", if (emergency8094) null else wallet)"))
        val sa = src("engine/sell/SellAmountAuthority.kt")
        assertTrue(sa.contains("fun emergencyWalletSnapshotBalance7730(mint: String, reason: String)"))
        assertTrue(sa.contains("WalletSnapshotCompleteness7140.isLastPartial()"))
        // A retire needs a COMPLETE snapshot after the close; no snapshot / partial -> 0 (no proof).
        assertEquals(0L, walletZeroProvenAt8094(false, 5_000L, null, java.math.BigInteger.ONE))
    }

    // No discretionary hold sits in front of an emergency exit.
    @Test fun discretionaryHoldsNeverDeferAnEmergency() {
        val rg = src("engine/RunnerGrab7967.kt").substringAfter("fun deferrable7967(").substringBefore("// ── grab ──")
        assertTrue(rg.contains("if (com.lifecyclebot.engine.sell.ProtectiveExitClass7807.isEmergency(r)) return false"))
        val lh = src("engine/LifecycleHold8065.kt").substringAfter("fun passes8065(").take(400)
        assertTrue(lh.contains("Priority.STRUCTURAL_EMERGENCY.rank"))       // catastrophe + structural always pass
        val pe = src("engine/sell/PartialNetEconomics8094.kt")
        assertTrue(pe.contains("ProtectiveExitClass7807.isEmergency(reason)"))   // the fee check never holds an emergency
    }

    // Memory / main thread: Cortex saves are queued on one background thread; onDestroy waits a bounded time.
    @Test fun cortexPersistenceNeverEncodesOnTheCallerThread() {
        val c = src("engine/cortex/Cortex7885.kt")
        val persist = c.substringAfter("    private fun persist() {").substringBefore("@Synchronized private fun persistBlocking8094()")
        assertTrue(persist.contains("persistQueued8094.compareAndSet(false, true)"))          // coalesced
        assertTrue(persist.contains("persistPool8094.execute { persistQueued8094.set(false); persistBlocking8094() }"))
        val now = c.substringAfter("fun persistNow7930() {").substringBefore("private const val FLUSH_WAIT_MS_8094")
        assertTrue(now.contains("persistPool8094.submit(work).get(FLUSH_WAIT_MS_8094"))      // bounded main-thread wait
        assertFalse(c.contains("@Synchronized private fun persist() {"))
    }

    // Cortex: a strong lane read cannot overrule for a candidate whose own setup record is negative.
    @Test fun aStrongLaneCannotOverruleANegativeSetup() {
        val c = com.lifecyclebot.engine.cortex.Cortex7885
        assertTrue(c.strongSetupNegative8094(n = 12.0, meanPct = -9.0, sePct = 4.0))
        assertFalse(c.strongSetupNegative8094(n = 12.0, meanPct = -2.0, sePct = 4.0))   // not proven negative
        assertFalse(c.strongSetupNegative8094(n = 3.0, meanPct = -40.0, sePct = 1.0))   // too few reads
        val src = src("engine/cortex/Cortex7885.kt")
        val ov = src.substringAfter("fun overrulesEdgeRefusal(").substringBefore("val cell8083 =")
        assertTrue(ov.contains("LanePlaybook7907.provenLosingSetupRefusal8079(ts, a.lane)"))
        assertTrue(ov.contains("CORTEX_OVERRIDE_REFUSED_NEGATIVE_SETUP_8094"))
        assertTrue(src.contains("CORTEX_OVERRIDE_EXPLAINED_8083"))                       // every override attributed
    }

    // Reconciliation: a canonical LIVE token the wallet holds is re-tracked, never ignored as an orphan; a conclusive
    // zero streak closes a stale-unproven tracker row instead of re-firing exits at an empty wallet.
    @Test fun reconciliationRebuildsFromWalletTruthAndStopsExitsOnProvenZero() {
        val t = src("engine/HostWalletTokenTracker.kt")
        assertTrue(t.contains("if (!RECOVER_ORPHAN_WALLET_TOKENS && !canonicalLive8094) {"))
        assertTrue(t.indexOf("val canonicalLive8094 =") < t.indexOf("val canonicalOwn7137 ="))
        val b = src("engine/BotService.kt").substringAfter("private fun zeroConfirmedWithoutTrackerRow7736(").take(1600)
        assertTrue(b.contains("PositionStatus.STALE_RECOVERY_UNPROVEN"))
        assertTrue(b.contains("CONFIRMED_ZERO_NO_TRACKER_ROW_7736"))       // quarantine, never an invented sale
    }

    // Economic learning: a fully closed live round trip within dust of its fill is measured; a gap is not invented.
    @Test fun costLedgerMeasuresRealRoundTripsWithoutInventingQuantity() {
        val c = com.lifecyclebot.engine.truth.CostLedger7962
        val bi = { v: Long -> java.math.BigInteger.valueOf(v) }
        assertTrue(c.quantityMatches8094(bi(1_000_000L), bi(1_000_000L)))
        assertTrue(c.quantityMatches8094(bi(1_000_000L), bi(999_999L)))        // one unit of dust
        assertTrue(c.quantityMatches8094(bi(1_000_000L), bi(995_500L)))        // wallet-clamped final sell
        assertFalse(c.quantityMatches8094(bi(1_000_000L), bi(900_000L)))       // a missing leg is not measured
        assertFalse(c.quantityMatches8094(bi(0L), bi(0L)))
        assertEquals(0.9955, c.soldShare8094(bi(1_000_000L), bi(995_500L)), 1e-9)
        assertEquals(1.0, c.soldShare8094(bi(1_000_000L), bi(1_000_001L)), 1e-9)
        val src = src("engine/sell/SellFinalizationCoordinator.kt")
        assertTrue(src.contains("solUsdAtExit = liveSolUsd8094(),"))           // the exit is valued in USD
    }

    // An early catastrophe stop is held only by a cached executable quote that contradicts it; no quote, no delay.
    @Test fun anEarlyPhantomStopIsHeldOnlyByAContradictingExecutableQuote() {
        // Gjd3JU-shape: mark said <= -25%, the cached executable price says -3%: held.
        assertTrue(earlyStopContradicted8094(execPrice = 0.97, entryPrice = 1.0, catastropheThreshold = -25.0, hardFloorPct = 15.0))
        // The executable agrees it is a dump: the stop fires.
        assertFalse(earlyStopContradicted8094(0.70, 1.0, -25.0, 15.0))
        assertFalse(earlyStopContradicted8094(0.84, 1.0, -25.0, 15.0))             // below the -15% hard floor
        // No cached executable quote: never a delay.
        assertFalse(earlyStopContradicted8094(null, 1.0, -25.0, 15.0))
        val b = src("engine/BotService.kt")
        assertTrue(b.contains("return@run !earlyStopContradicted8094(cachedExec8094, entry8094, catastropheThreshold, HARD_FLOOR_STOP_PCT_CONST)"))
    }
}
